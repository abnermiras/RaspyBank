package com.raspybank.lancamento.servico;

import com.raspybank.lancamento.dominio.Cartao;
import com.raspybank.lancamento.dominio.Categoria;
import com.raspybank.lancamento.dominio.CodigoSistemico;
import com.raspybank.lancamento.dominio.Conta;
import com.raspybank.lancamento.dominio.ContaAmbiente;
import com.raspybank.lancamento.dominio.Fatura;
import com.raspybank.lancamento.dominio.FormaPagamento;
import com.raspybank.lancamento.dominio.Lancamento;
import com.raspybank.lancamento.dominio.TipoLancamento;
import com.raspybank.lancamento.dominio.TotalDaFatura;
import com.raspybank.lancamento.repositorio.CartaoRepositorio;
import com.raspybank.lancamento.repositorio.CategoriaRepositorio;
import com.raspybank.lancamento.repositorio.ContaAmbienteRepositorio;
import com.raspybank.lancamento.repositorio.ContaFormaPagamentoRepositorio;
import com.raspybank.lancamento.repositorio.ContaRepositorio;
import com.raspybank.lancamento.repositorio.FaturaRepositorio;
import com.raspybank.lancamento.repositorio.LancamentoRepositorio;
import com.raspybank.shared.erro.OperacaoNaoPermitida;
import com.raspybank.shared.erro.RecursoNaoEncontrado;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Casos de uso da fatura: ver, fechar, reabrir e pagar.
 *
 * <h3>Nenhum estado e guardado</h3>
 *
 * <p>F19 permite uma coluna so, {@code fechada_em}. Total, pago, quitacao e
 * vencimento saem de calculo — e B-D58 separa o estado em TRES perguntas
 * independentes, porque uma fatura ABERTA pode estar parcialmente paga e num
 * enum unico esse caso nao teria nome.</p>
 *
 * <h3>Pagar reusa a maquina da transferencia</h3>
 *
 * <p>Um pagamento e um par de lancamentos ligados (B-D38), com duas coisas a
 * mais: os dois apontam para a fatura, e a perna de saida carrega forma de
 * pagamento. Reusar significa que {@code ON DELETE CASCADE} tambem vale aqui —
 * meio pagamento nao consegue existir.</p>
 */
@Service
public class FaturaServico {

    private final FaturaRepositorio faturas;
    private final CartaoRepositorio cartoes;
    private final LancamentoRepositorio lancamentos;
    private final CategoriaRepositorio categorias;
    private final ContaRepositorio contas;
    private final ContaAmbienteRepositorio vinculos;
    private final ContaFormaPagamentoRepositorio formasDePagamento;
    private final LancamentoServico lancamentoServico;

    @PersistenceContext
    private EntityManager em;

    public FaturaServico(FaturaRepositorio faturas,
                         CartaoRepositorio cartoes,
                         LancamentoRepositorio lancamentos,
                         CategoriaRepositorio categorias,
                         ContaRepositorio contas,
                         ContaAmbienteRepositorio vinculos,
                         ContaFormaPagamentoRepositorio formasDePagamento,
                         LancamentoServico lancamentoServico) {
        this.faturas = faturas;
        this.cartoes = cartoes;
        this.lancamentos = lancamentos;
        this.categorias = categorias;
        this.contas = contas;
        this.vinculos = vinculos;
        this.formasDePagamento = formasDePagamento;
        this.lancamentoServico = lancamentoServico;
    }

    // =========================================================================
    // Leitura
    // =========================================================================

    /**
     * As faturas de um ano, com os numeros calculados.
     *
     * <p>Fecha as vencidas antes de responder, pelo mesmo criterio de
     * {@code SituacaoVencidaServico}: sem isso, uma fatura cujo fechamento
     * passou continuaria aberta e recebendo compra nova — e o mes seguinte
     * comecaria errado sem ninguem perceber.</p>
     */
    @Transactional
    public List<Item> listar(UUID ambienteId, UUID cartaoId, int ano, LocalDate hoje) {
        exigirCartao(ambienteId, cartaoId);
        fecharVencidas(cartaoId, hoje);

        List<Fatura> lista = faturas.doAno(cartaoId,
            LocalDate.of(ano, 1, 1), LocalDate.of(ano, 12, 1));

        return comNumeros(lista, cartaoId, hoje);
    }

    @Transactional
    public Item buscar(UUID ambienteId, UUID faturaId, LocalDate hoje) {
        Fatura f = faturas.findById(faturaId)
            .orElseThrow(() -> new RecursoNaoEncontrado("Fatura nao encontrada"));

        exigirCartao(ambienteId, f.getCartaoId());
        fecharVencidas(f.getCartaoId(), hoje);

        return comNumeros(List.of(f), f.getCartaoId(), hoje).get(0);
    }

    /**
     * O EXTRATO da fatura: cada gasto com categoria, subcategoria e o cartao que
     * o fez.
     *
     * <p>Pedido nos testes de negocio de 28/07/2026: <i>"vai mostrar os gastos
     * de cada cartao virtual, de cada cartao fisico, no mesmo mes. Porque no
     * final das contas e uma fatura que esta sendo paga."</i></p>
     *
     * <p>Reusa {@code LancamentoServico.comNomes} em vez de repetir a resolucao
     * de nomes: sao os mesmos tres lotes de consulta, e duas copias divergiriam
     * na primeira coluna nova.</p>
     */
    @Transactional(readOnly = true)
    public List<LancamentoServico.Item> lancamentosDa(UUID ambienteId, UUID faturaId) {
        Fatura f = faturas.findById(faturaId)
            .orElseThrow(() -> new RecursoNaoEncontrado("Fatura nao encontrada"));
        exigirCartao(ambienteId, f.getCartaoId());

        return lancamentoServico.detalhar(
            lancamentos.findByFaturaIdOrderByDataCompetenciaDescCriadoEmDesc(faturaId));
    }

    // =========================================================================
    // Fechar e reabrir (B-D50)
    // =========================================================================

    @Transactional
    public Fatura fechar(UUID ambienteId, UUID faturaId) {
        Fatura f = exigirFatura(ambienteId, faturaId);
        f.fechar(OffsetDateTime.now());
        return f;
    }

    /**
     * Reabre — revisao de F21, que dizia que fatura fechada e imutavel.
     *
     * <p>Motivo dado pelo dono do projeto: <i>"e mais para se ele fechar a
     * fatura sem querer poder abrir"</i>. Cada banco tem sua regra de
     * fechamento, e um clique errado nao pode ser definitivo.</p>
     *
     * <p><b>Reabrir uma fatura ja paga e permitido</b>, e o estado continua
     * coerente sem tratamento especial: {@code quitacao} deriva das somas, entao
     * ela segue QUITADA; e {@code vencida} exige ciclo FECHADA, entao volta a
     * falso. Foi para isso que B-D58 separou as tres perguntas — num enum unico,
     * "aberta e paga" nao teria valor.</p>
     */
    @Transactional
    public Fatura reabrir(UUID ambienteId, UUID faturaId) {
        Fatura f = exigirFatura(ambienteId, faturaId);
        f.reabrir();
        return f;
    }

    /**
     * Fecha as que passaram do fechamento previsto.
     *
     * <p>Na leitura e nao num job agendado, pela mesma razao de
     * {@code SituacaoVencidaServico}: rotina de fundo nao tem identidade na
     * sessao, entao a RLS nao a enxerga e o UPDATE alcancaria zero linhas.</p>
     */
    private void fecharVencidas(UUID cartaoId, LocalDate hoje) {
        for (Fatura f : faturas.vencidasParaFechar(List.of(cartaoId), hoje)) {
            f.fechar(OffsetDateTime.now());
        }
        em.flush();
    }

    // =========================================================================
    // Pagamento (B-D51, B-D57)
    // =========================================================================

    /**
     * Paga a fatura — total ou em parte, aberta ou fechada.
     *
     * <h4>Tres liberdades, todas pedidas explicitamente</h4>
     *
     * <p><b>Valor parcial.</b> Pagar 1.000 de uma fatura de 5.000 e legitimo, e
     * e o que torna {@code PARCIAL} um estado real (B-D52). Foi ele quem
     * confirmou que existe, ao dizer "o valor que vou pagar".</p>
     *
     * <p><b>Fatura ABERTA aceita pagamento</b>, e nao e conveniencia: e o
     * mecanismo de liberar limite (B-D57). Fatura aberta de 5.000 com 1.000 de
     * disponivel e uma compra de 2.000 para fazer — paga 1.000 antecipado, o
     * pendente cai para 4.000, o disponivel sobe para 2.000, e a compra passa.
     * Sem antecipacao o limite so voltaria no vencimento.</p>
     *
     * <p><b>A conta pagadora e livre</b>, inclusive de outro banco: pagar a
     * fatura do Nubank com a conta do C6, via boleto.</p>
     *
     * <h4>O par, e por que a fatura marca os dois lados</h4>
     *
     * <p>Saida na conta pagadora, entrada na conta do cartao, ligadas como a
     * transferencia. As DUAS carregam {@code faturaId} (B-D59): a entrada porque
     * e ela que abate a divida, e a saida para que o extrato da conta pagadora
     * consiga dizer QUAL fatura aquele dinheiro pagou.</p>
     *
     * <p>Consequencia: quem soma o total da fatura precisa filtrar tambem pela
     * conta do cartao, senao contaria a saida da corrente como se fosse compra.
     * E o que {@code TotalDaFatura} faz.</p>
     */
    @Transactional
    public Par pagar(UUID ambienteId, UUID usuarioId, UUID faturaId, UUID contaOrigemId,
                     BigDecimal valor, LocalDate dataCaixa, FormaPagamento forma,
                     LocalDate hoje) {

        Fatura fatura = exigirFatura(ambienteId, faturaId);
        Cartao cartao = exigirCartao(ambienteId, fatura.getCartaoId());

        if (contaOrigemId.equals(cartao.getContaId())) {
            throw new OperacaoNaoPermitida(
                "A conta de origem nao pode ser o proprio cartao —"
                    + " o cartao nao paga a si mesmo");
        }

        Conta origem = exigirContaUtilizavel(ambienteId, contaOrigemId);

        Categoria pagamento = categorias
            .buscarSistemica(ambienteId, CodigoSistemico.PAGAMENTO_FATURA)
            .orElseThrow(() -> new IllegalStateException(
                "Ambiente sem a categoria sistemica PAGAMENTO_FATURA —"
                    + " fn_criar_categorias_sistemicas da V12 nao rodou"));

        Lancamento saida = new Lancamento(
            pagamento, contaOrigemId, TipoLancamento.SAIDA, valor,
            dataCaixa, dataCaixa, usuarioId, hoje);

        Lancamento entrada = new Lancamento(
            pagamento, cartao.getContaId(), TipoLancamento.ENTRADA, valor,
            dataCaixa, dataCaixa, usuarioId, hoje);

        String rotulo = "Pagamento da fatura " + fatura.getMes() + " — " + cartao.getNome();
        saida.descrever(rotulo);
        entrada.descrever("Pagamento recebido de " + origem.getNome());

        // A forma vale so na perna de SAIDA: e de la que o dinheiro sai, e e a
        // lista daquela conta que manda. A entrada no cartao nao tem "como foi
        // pago" — ela E o pagamento chegando.
        saida.pagarPor(conferirFormaAceita(contaOrigemId, forma));

        lancamentos.save(saida);
        lancamentos.save(entrada);

        // Sem este flush os ids ainda nao existem e o vinculo apontaria para nada.
        em.flush();

        saida.emparelharCom(entrada.getId());
        entrada.emparelharCom(saida.getId());

        saida.cobrarNaFatura(faturaId, dataCaixa, hoje);
        entrada.cobrarNaFatura(faturaId, dataCaixa, hoje);

        return new Par(saida, entrada);
    }

    // =========================================================================
    // Guardas e apoio
    // =========================================================================

    private List<Item> comNumeros(List<Fatura> lista, UUID cartaoId, LocalDate hoje) {
        if (lista.isEmpty()) {
            return List.of();
        }

        Map<UUID, TotalDaFatura> totais = new HashMap<>();
        for (TotalDaFatura t : lancamentos.totaisDasFaturas(
                lista.stream().map(Fatura::getId).toList(), cartaoId)) {
            totais.put(t.faturaId(), t);
        }

        return lista.stream()
            .map(f -> new Item(f, totais.getOrDefault(f.getId(),
                                                      TotalDaFatura.zeradaPara(f.getId())), hoje))
            .toList();
    }

    private Fatura exigirFatura(UUID ambienteId, UUID faturaId) {
        Fatura f = faturas.findById(faturaId)
            .orElseThrow(() -> new RecursoNaoEncontrado("Fatura nao encontrada"));
        exigirCartao(ambienteId, f.getCartaoId());
        return f;
    }

    private Cartao exigirCartao(UUID ambienteId, UUID cartaoId) {
        if (vinculos.findById(new ContaAmbiente.Chave(cartaoId, ambienteId)).isEmpty()) {
            throw new RecursoNaoEncontrado("Cartao nao encontrado");
        }
        return cartoes.findById(cartaoId)
            .orElseThrow(() -> new RecursoNaoEncontrado("Cartao nao encontrado"));
    }

    private Conta exigirContaUtilizavel(UUID ambienteId, UUID contaId) {
        if (vinculos.findById(new ContaAmbiente.Chave(contaId, ambienteId)).isEmpty()) {
            throw new RecursoNaoEncontrado("Conta de origem nao encontrada");
        }
        Conta c = contas.findById(contaId).orElseThrow(
            () -> new RecursoNaoEncontrado("Conta de origem nao encontrada"));

        if (c.estaEncerrada()) {
            throw new OperacaoNaoPermitida(
                "A conta '" + c.getNome() + "' esta encerrada."
                    + " Reabra a conta antes de pagar por ela.");
        }
        return c;
    }

    /**
     * Mesma checagem de {@code LancamentoServico}, aplicada a conta pagadora.
     *
     * <p>As duas chaves compostas do banco barrariam de qualquer jeito; o que se
     * ganha aqui e a frase com as formas que cabem.</p>
     */
    private FormaPagamento conferirFormaAceita(UUID contaId, FormaPagamento forma) {
        if (forma == null) {
            return null;
        }

        List<FormaPagamento> daConta = formasDePagamento.findByContaId(contaId).stream()
            .map(f -> f.getForma())
            .toList();

        if (!daConta.contains(forma)) {
            throw new OperacaoNaoPermitida(
                "A conta de origem nao aceita " + forma + ". Formas aceitas: " + daConta);
        }
        if (!forma.aceita(TipoLancamento.SAIDA)) {
            throw new OperacaoNaoPermitida(forma + " nao serve para pagamento");
        }
        return forma;
    }

    // =========================================================================

    /**
     * Uma fatura com os numeros e as tres respostas de estado (B-D58).
     *
     * <p>Os tres campos derivados sao metodos e nao colunas — F19 nao permite
     * coluna de status, e nao precisaria: guardar o que se calcula em duas somas
     * seria criar um numero para reconciliar.</p>
     */
    public record Item(Fatura fatura, TotalDaFatura numeros, LocalDate hoje) {

        public com.raspybank.lancamento.dominio.CicloFatura ciclo() {
            return fatura.ciclo();
        }

        public com.raspybank.lancamento.dominio.QuitacaoFatura quitacao() {
            return fatura.quitacao(numeros.total(), numeros.pago());
        }

        public boolean vencida() {
            return fatura.estaVencida(numeros.total(), numeros.pago(), hoje);
        }
    }

    /** As duas pernas do pagamento, na ordem em que a tela as mostra. */
    public record Par(Lancamento saida, Lancamento entrada) {}
}
