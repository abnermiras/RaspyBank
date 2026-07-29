package com.raspybank.lancamento.servico;

import com.raspybank.lancamento.dominio.Cartao;
import com.raspybank.lancamento.dominio.CartaoEmitido;
import com.raspybank.lancamento.dominio.Conta;
import com.raspybank.lancamento.dominio.ContaAmbiente;
import com.raspybank.lancamento.dominio.Fatura;
import com.raspybank.lancamento.dominio.FormaPagamento;
import com.raspybank.lancamento.dominio.NaturezaConta;
import com.raspybank.lancamento.dominio.SaldoDaConta;
import com.raspybank.lancamento.dominio.TipoCartaoEmitido;
import com.raspybank.lancamento.repositorio.CartaoEmitidoRepositorio;
import com.raspybank.lancamento.repositorio.CartaoRepositorio;
import com.raspybank.lancamento.repositorio.ContaAmbienteRepositorio;
import com.raspybank.lancamento.repositorio.ContaFormaPagamentoRepositorio;
import com.raspybank.lancamento.repositorio.ContaRepositorio;
import com.raspybank.lancamento.repositorio.FaturaRepositorio;
import com.raspybank.lancamento.repositorio.LancamentoRepositorio;
import com.raspybank.shared.erro.ConflitoDeEstado;
import com.raspybank.shared.erro.OperacaoNaoPermitida;
import com.raspybank.shared.erro.RecursoNaoEncontrado;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Casos de uso do cartao de credito (T-06).
 *
 * <h3>Criar cartao e criar uma conta</h3>
 *
 * <p>O cartao E uma conta {@code PASSIVO} (B-D47), entao a criacao passa pela
 * mesma porta estreita {@code app_criar_conta()} que a V10 abriu — e pelo mesmo
 * motivo: a politica de RLS nao enxerga uma conta que ainda nao tem vinculo.</p>
 *
 * <p><b>Nenhuma porta estreita nova foi necessaria.</b> A linha em {@code cartao}
 * entra DEPOIS que a conta e o vinculo existem, e nesse ponto
 * {@code app_contas_do_usuario()} ja devolve a conta — o {@code WITH CHECK}
 * passa. O impasse que justificou a excecao de B-D19 na V10 nao se repete.</p>
 *
 * <h3>O limite nao trava nada</h3>
 *
 * <p>B-D48. Nenhum metodo aqui recusa operacao por estouro de limite. Os numeros
 * de {@link Resumo} existem para bater com o app do banco — e o banco de verdade
 * e quem recusa a compra.</p>
 */
@Service
public class CartaoServico {

    /**
     * Quantas faturas nascem com o cartao.
     *
     * <p>Doze porque o parcelamento precisa de onde cair (F20/F23): uma compra
     * em 10x lancada hoje ocupa dez ciclos a frente, e gerar sob demanda faria a
     * compra criar faturas como efeito colateral — o tipo de escrita escondida
     * que fica dificil de explicar quando o numero sai errado.</p>
     */
    private static final int HORIZONTE_DE_FATURAS = 12;

    private final CartaoRepositorio cartoes;
    private final CartaoEmitidoRepositorio emitidos;
    private final FaturaRepositorio faturas;
    private final ContaRepositorio contas;
    private final ContaAmbienteRepositorio vinculos;
    private final ContaFormaPagamentoRepositorio formasDePagamento;
    private final LancamentoRepositorio lancamentos;

    @PersistenceContext
    private EntityManager em;

    public CartaoServico(CartaoRepositorio cartoes,
                         CartaoEmitidoRepositorio emitidos,
                         FaturaRepositorio faturas,
                         ContaRepositorio contas,
                         ContaAmbienteRepositorio vinculos,
                         ContaFormaPagamentoRepositorio formasDePagamento,
                         LancamentoRepositorio lancamentos) {
        this.cartoes = cartoes;
        this.emitidos = emitidos;
        this.faturas = faturas;
        this.contas = contas;
        this.vinculos = vinculos;
        this.formasDePagamento = formasDePagamento;
        this.lancamentos = lancamentos;
    }

    // =========================================================================
    // Leitura
    // =========================================================================

    @Transactional(readOnly = true)
    public List<Resumo> listar(UUID ambienteId, boolean incluirEncerrados) {
        List<Cartao> lista = incluirEncerrados
            ? cartoes.doAmbiente(ambienteId)
            : cartoes.ativosDoAmbiente(ambienteId);

        if (lista.isEmpty()) {
            return List.of();
        }

        List<UUID> ids = lista.stream().map(Cartao::getContaId).toList();

        // Duas consultas para a tela inteira, e nao duas por cartao.
        Map<UUID, SaldoDaConta> saldos = new HashMap<>();
        for (SaldoDaConta s : lancamentos.saldos(ids)) {
            saldos.put(s.contaId(), s);
        }

        Map<UUID, List<CartaoEmitido>> porCartao = new HashMap<>();
        for (CartaoEmitido e : emitidos.findByCartaoIdIn(ids)) {
            porCartao.computeIfAbsent(e.getCartaoId(), k -> new ArrayList<>()).add(e);
        }

        Map<UUID, String> nomesDeBanco = nomesDasContas(
            lista.stream().map(Cartao::getContaBancoId).distinct().toList());

        return lista.stream()
            .map(c -> new Resumo(
                c,
                nomesDeBanco.getOrDefault(c.getContaBancoId(), "(conta removida)"),
                saldos.getOrDefault(c.getContaId(), SaldoDaConta.zeradoPara(c.getContaId())),
                porCartao.getOrDefault(c.getContaId(), List.of())))
            .toList();
    }

    @Transactional(readOnly = true)
    public Resumo resumo(UUID ambienteId, UUID cartaoId) {
        Cartao c = exigir(ambienteId, cartaoId);

        SaldoDaConta saldo = lancamentos.saldos(List.of(cartaoId)).stream()
            .findFirst()
            .orElseGet(() -> SaldoDaConta.zeradoPara(cartaoId));

        String banco = nomesDasContas(List.of(c.getContaBancoId()))
            .getOrDefault(c.getContaBancoId(), "(conta removida)");

        return new Resumo(c, banco, saldo, emitidos.findByCartaoIdOrderByCriadoEm(cartaoId));
    }

    // =========================================================================
    // Escrita
    // =========================================================================

    /**
     * Cria o contrato, a conta que o representa e as doze primeiras faturas.
     *
     * @param hoje data de referencia da geracao das faturas, injetada e nao
     *             consultada (padrao B-C3)
     */
    @Transactional
    public Cartao criar(UUID ambienteId, UUID contaBancoId, String nome, BigDecimal limite,
                        int diaVencimento, int diasParaFechamento,
                        String nomeTitular, String finalDoCartao, LocalDate hoje) {

        exigirBancoUtilizavel(ambienteId, contaBancoId);

        // A conta do cartao nasce pela mesma porta estreita da V10: a politica
        // nao enxerga conta sem vinculo, e nenhuma ordem de INSERT resolve isso.
        UUID contaId = (UUID) em.createNativeQuery(
                "SELECT app_criar_conta(:ambiente, :nome, :natureza)")
            .setParameter("ambiente", ambienteId)
            .setParameter("nome", nome)
            .setParameter("natureza", NaturezaConta.PASSIVO.name())
            .getSingleResult();

        // O flush faz a funcao rodar AGORA. Sem ele o Hibernate poderia adiar a
        // consulta nativa, e a linha de cartao esbarraria na chave estrangeira.
        em.flush();

        Cartao cartao = cartoes.save(
            new Cartao(contaId, contaBancoId, nome, limite, diaVencimento, diasParaFechamento));

        // O contrato nasce com o cartao FISICO junto, e nao vazio.
        //
        // Pedido dele nos testes de negocio: "quando eu crio um cartao eu preciso
        // informar os 4 ultimos digitos, para que seja possivel depois la na hora
        // de lancar o gasto dizer nubank - fisico - 4352" (B-D63). Criar o
        // contrato e depois ter que criar o plastico seria duas etapas para uma
        // coisa so — e um cartao sem nenhum emitido nao pode receber compra
        // nenhuma, entao ele nasceria inutil.
        if (finalDoCartao != null && !finalDoCartao.isBlank()) {
            emitidos.save(new CartaoEmitido(
                contaId, nomeTitular, TipoCartaoEmitido.FISICO, finalDoCartao, null));
        }

        gerarFaturasAte(cartao, YearMonth.from(hoje).plusMonths(HORIZONTE_DE_FATURAS - 1L));

        return cartao;
    }

    @Transactional
    public Cartao alterar(UUID ambienteId, UUID cartaoId, String nome, BigDecimal limite) {
        Cartao c = exigir(ambienteId, cartaoId);
        c.renomear(nome);
        c.alterarLimite(limite);

        // O nome da conta acompanha o do cartao: sao a mesma coisa para quem
        // olha a T-05, e deixa-los divergir criaria dois nomes para uma entidade.
        contas.findById(cartaoId).ifPresent(conta -> conta.renomear(nome));

        return c;
    }

    /**
     * Muda o ciclo, e regera SOMENTE as faturas futuras.
     *
     * <p>As faturas ja fechadas e a corrente ficam como estao, de proposito: o
     * ciclo que ja correu nao pode mudar depois, senao uma compra ja lancada
     * migraria de fatura sozinha — e o extrato de um mes fechado mudaria sem
     * ninguem ter tocado nele.</p>
     */
    @Transactional
    public Cartao reagendarCiclo(UUID ambienteId, UUID cartaoId,
                                 int diaVencimento, int diasParaFechamento, LocalDate hoje) {

        Cartao c = exigir(ambienteId, cartaoId);
        c.reagendarCiclo(diaVencimento, diasParaFechamento);

        YearMonth mesCorrente = YearMonth.from(hoje);
        for (Fatura f : faturas.findByCartaoIdOrderByVencimento(cartaoId)) {
            if (f.getMes().isAfter(mesCorrente) && f.estaAberta()) {
                f.reagendar(c.vencimentoEm(f.getMes()), c.fechamentoEm(f.getMes()));
            }
        }
        return c;
    }

    /**
     * Encerra o cartao e, em cascata, TODOS os emitidos dele.
     *
     * <h4>Encerrar NAO exige divida zero — e a diferenca em relacao a conta</h4>
     *
     * <p>A primeira versao copiou a regra de F7, que exige saldo zero para
     * encerrar uma conta. Estava errado, e o Abner corrigiu: <i>"o fato de eu
     * encerrar um cartao nao some com o futuro e nem anula ele, a
     * responsabilidade de pagar as faturas em aberto e as dividas futuras
     * continua"</i>.</p>
     *
     * <p>E o mundo real: cancelar o cartao no aplicativo do banco nao perdoa a
     * fatura. O paralelo com conta nao valia — encerrar uma conta COM saldo faria
     * dinheiro sumir do patrimonio, enquanto encerrar um cartao com divida nao
     * muda numero nenhum. A divida continua inteira, as parcelas futuras
     * continuam chegando, e as faturas continuam pagaveis.</p>
     *
     * <p>O que encerrar faz e uma coisa so: <b>impedir compra nova</b>. O cartao
     * some da lista de meios de pagamento, e e isso.</p>
     *
     * <h4>A cascata</h4>
     *
     * <p>Encerrar o contrato cancela todos os plasticos e virtuais debaixo dele
     * (B-D65). Nao ha cartao emitido de um contrato encerrado: o banco cancela o
     * conjunto, nao a capa.</p>
     */
    @Transactional
    public Cartao encerrar(UUID ambienteId, UUID cartaoId) {
        Cartao c = exigir(ambienteId, cartaoId);
        OffsetDateTime agora = OffsetDateTime.now();

        c.encerrar(agora);

        for (CartaoEmitido e : emitidos.findByCartaoIdOrderByCriadoEm(cartaoId)) {
            if (!e.estaCancelado()) {
                e.cancelar(agora);
            }
        }

        // A conta do cartao NAO e encerrada junto, e isso e deliberado: conta
        // encerrada com saldo seria o defeito que F7 evita, e a divida do cartao
        // continua existindo. O que impede compra nova e o encerrado_em do
        // cartao, nao o da conta.
        return c;
    }

    /**
     * Reabre o contrato — e NAO reativa os emitidos.
     *
     * <p>Poderia descascatear o encerramento, e nao faz de proposito: reativar
     * em massa ressuscitaria um virtual que a pessoa matou de proposito antes,
     * e cartao virtual e feito para ser descartado. Ressuscitar em silencio e
     * pior do que um clique a mais.</p>
     *
     * <p>A tela mostra cada emitido com o proprio botao, e diz que eles
     * continuam cancelados.</p>
     */
    @Transactional
    public Cartao reabrir(UUID ambienteId, UUID cartaoId) {
        Cartao c = exigir(ambienteId, cartaoId);
        c.reabrir();
        contas.findById(cartaoId).ifPresent(Conta::reabrir);
        return c;
    }

    // =========================================================================
    // Cartoes emitidos
    // =========================================================================

    @Transactional
    public CartaoEmitido emitir(UUID ambienteId, UUID cartaoId, String nomeTitular,
                                TipoCartaoEmitido tipo, String finalDoCartao,
                                BigDecimal limiteProprio) {

        exigir(ambienteId, cartaoId);
        return emitidos.save(
            new CartaoEmitido(cartaoId, nomeTitular, tipo, finalDoCartao, limiteProprio));
    }

    /**
     * Cancela um emitido. Nao apaga.
     *
     * <p>Um virtual descartado depois de uma compra precisa continuar explicando
     * aquela compra — apagar apagaria o passado, que e a mesma razao de F7 e
     * B-D4.</p>
     */
    @Transactional
    public CartaoEmitido cancelarEmitido(UUID ambienteId, UUID cartaoId, UUID emitidoId) {
        exigir(ambienteId, cartaoId);
        CartaoEmitido e = exigirEmitido(cartaoId, emitidoId);
        e.cancelar(OffsetDateTime.now());
        return e;
    }

    @Transactional
    public CartaoEmitido reativarEmitido(UUID ambienteId, UUID cartaoId, UUID emitidoId) {
        exigir(ambienteId, cartaoId);
        CartaoEmitido e = exigirEmitido(cartaoId, emitidoId);
        e.reativar();
        return e;
    }

    // =========================================================================
    // Faturas
    // =========================================================================

    /**
     * Garante que existam faturas ate o mes pedido, sem repetir as que ja ha.
     *
     * <p>Idempotente de proposito: e chamada na criacao do cartao e sempre que o
     * horizonte precisa esticar (uma compra em 24x, por exemplo). Rodar duas
     * vezes nao pode duplicar ciclo — {@code ux_fatura_ciclo} tambem garante
     * isso no banco.</p>
     */
    @Transactional
    public void gerarFaturasAte(Cartao cartao, YearMonth ultimoMes) {
        YearMonth proximo = faturas.findFirstByCartaoIdOrderByMesReferenciaDesc(cartao.getContaId())
            .map(f -> f.getMes().plusMonths(1))
            .orElse(YearMonth.from(LocalDate.now()));

        for (YearMonth mes = proximo; !mes.isAfter(ultimoMes); mes = mes.plusMonths(1)) {
            faturas.save(new Fatura(
                cartao.getContaId(), mes, cartao.vencimentoEm(mes), cartao.fechamentoEm(mes)));
        }
    }

    // =========================================================================
    // Guardas
    // =========================================================================

    /**
     * A conta do banco existe neste ambiente, esta aberta, e NAO e fisica.
     *
     * <p>A ultima condicao e B-D45 apoiada em B-D41: conta cuja lista de formas
     * e so {@code DINHEIRO} e fisica — carteira, gaveta, cofre — e papel moeda
     * nao emite cartao de credito.</p>
     *
     * <p>A checagem vive aqui e nao no banco porque um CHECK nao faz
     * subconsulta, e um gatilho seria a ferramenta mais cara para uma regra que
     * nao corrompe dinheiro — mesmo criterio de B-D41.</p>
     */
    private void exigirBancoUtilizavel(UUID ambienteId, UUID contaBancoId) {
        if (vinculos.findById(new ContaAmbiente.Chave(contaBancoId, ambienteId)).isEmpty()) {
            throw new RecursoNaoEncontrado("Conta do banco nao encontrada");
        }

        Conta banco = contas.findById(contaBancoId).orElseThrow(
            () -> new RecursoNaoEncontrado("Conta do banco nao encontrada"));

        if (banco.estaEncerrada()) {
            throw new OperacaoNaoPermitida(
                "A conta '" + banco.getNome() + "' esta encerrada e nao pode receber um cartao");
        }

        List<FormaPagamento> formas = formasDePagamento.findByContaId(contaBancoId).stream()
            .map(f -> f.getForma())
            .toList();

        if (formas.size() == 1 && formas.contains(FormaPagamento.DINHEIRO)) {
            throw new OperacaoNaoPermitida(
                "A conta '" + banco.getNome() + "' e uma conta fisica — carteira, gaveta,"
                    + " cofre — e papel moeda nao emite cartao de credito."
                    + " Escolha uma conta de banco.");
        }
    }

    /** Mesmo recorte de B-D21/B-D25: id de outro ambiente e 404, nunca 403. */
    private Cartao exigir(UUID ambienteId, UUID cartaoId) {
        if (vinculos.findById(new ContaAmbiente.Chave(cartaoId, ambienteId)).isEmpty()) {
            throw new RecursoNaoEncontrado("Cartao nao encontrado");
        }
        return cartoes.findById(cartaoId).orElseThrow(
            () -> new RecursoNaoEncontrado("Cartao nao encontrado"));
    }

    private CartaoEmitido exigirEmitido(UUID cartaoId, UUID emitidoId) {
        return emitidos.findById(emitidoId)
            .filter(e -> e.getCartaoId().equals(cartaoId))
            .orElseThrow(() -> new RecursoNaoEncontrado("Cartao emitido nao encontrado"));
    }

    private Map<UUID, String> nomesDasContas(List<UUID> ids) {
        Map<UUID, String> nomes = new HashMap<>();
        for (Conta c : contas.findAllById(ids)) {
            nomes.put(c.getId(), c.getNome());
        }
        return nomes;
    }

    // =========================================================================

    /**
     * Um cartao com o que a T-06 precisa mostrar junto dele.
     *
     * <p>Os tres numeros do limite sao calculados aqui e nao guardados (P1).
     * {@code consumido} usa o saldo COM PREVISTOS de propósito: as parcelas
     * futuras ja existem como lancamentos desde a compra (F23), com data no
     * futuro, entao so o numero com previstos enxerga a divida contratada
     * inteira — que e o que o app do banco mostra (B-D48).</p>
     */
    public record Resumo(Cartao cartao, String nomeDoBanco,
                         SaldoDaConta saldo, List<CartaoEmitido> emitidos) {

        public BigDecimal consumido() {
            return saldo.comPrevistos().abs();
        }

        /** Pode ficar NEGATIVO, e isso e informacao: o limite estourou. */
        public BigDecimal disponivel() {
            return cartao.getLimite().subtract(consumido());
        }
    }
}
