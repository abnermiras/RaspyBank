package com.raspybank.lancamento.servico;

import com.raspybank.lancamento.dominio.Cartao;
import com.raspybank.lancamento.dominio.Categoria;
import com.raspybank.lancamento.dominio.CodigoSistemico;
import com.raspybank.lancamento.dominio.Conta;
import com.raspybank.lancamento.dominio.ContaAmbiente;
import com.raspybank.lancamento.dominio.Fatura;
import com.raspybank.lancamento.dominio.FormaPagamento;
import com.raspybank.lancamento.dominio.Lancamento;
import com.raspybank.lancamento.dominio.LinhaDaFatura;
import com.raspybank.lancamento.dominio.SituacaoLancamento;
import com.raspybank.lancamento.dominio.TipoCartaoEmitido;
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
        fecharVencidasSePuder(ambienteId, cartaoId, hoje);

        List<Fatura> lista = faturas.doAno(cartaoId,
            LocalDate.of(ano, 1, 1), LocalDate.of(ano, 12, 1));

        return comNumeros(lista, cartaoId, ambienteId, hoje);
    }

    @Transactional
    public Item buscar(UUID ambienteId, UUID faturaId, LocalDate hoje) {
        Fatura f = faturas.findById(faturaId)
            .orElseThrow(() -> new RecursoNaoEncontrado("Fatura nao encontrada"));

        exigirCartao(ambienteId, f.getCartaoId());
        fecharVencidasSePuder(ambienteId, f.getCartaoId(), hoje);

        return comNumeros(List.of(f), f.getCartaoId(), ambienteId, hoje).get(0);
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
    @SuppressWarnings("unchecked")
    public List<LinhaDaFatura> lancamentosDa(UUID ambienteId, UUID faturaId) {
        Fatura f = faturas.findById(faturaId)
            .orElseThrow(() -> new RecursoNaoEncontrado("Fatura nao encontrada"));
        exigirCartao(ambienteId, f.getCartaoId());

        List<Object[]> linhas = em.createNativeQuery("""
                SELECT id, meu, data_caixa, data_competencia, tipo, situacao, valor,
                       forma_pagamento, descricao, categoria_id, categoria_nome,
                       subcategoria_id, subcategoria_nome, quem_nome,
                       cartao_emitido_id, titular, tipo_emitido, final_do_cartao,
                       parcela_numero, parcela_total
                  FROM app_extrato_da_fatura(:fatura, :ambiente)
                """)
            .setParameter("fatura", faturaId)
            .setParameter("ambiente", ambienteId)
            .getResultList();

        return linhas.stream()
            .map(l -> new LinhaDaFatura(
                (UUID) l[0],
                (Boolean) l[1],
                ((java.sql.Date) l[2]).toLocalDate(),
                ((java.sql.Date) l[3]).toLocalDate(),
                TipoLancamento.valueOf((String) l[4]),
                SituacaoLancamento.valueOf((String) l[5]),
                (BigDecimal) l[6],
                l[7] == null ? null : FormaPagamento.valueOf((String) l[7]),
                (String) l[8],
                (UUID) l[9],
                (String) l[10],
                (UUID) l[11],
                (String) l[12],
                (String) l[13],
                (UUID) l[14],
                (String) l[15],
                l[16] == null ? null : TipoCartaoEmitido.valueOf((String) l[16]),
                (String) l[17],
                l[18] == null ? null : ((Number) l[18]).shortValue(),
                l[19] == null ? null : ((Number) l[19]).shortValue()))
            .toList();
    }

    // =========================================================================
    // Fechar e reabrir (B-D50)
    // =========================================================================

    @Transactional
    public Fatura fechar(UUID ambienteId, UUID faturaId) {
        Fatura f = exigirFatura(ambienteId, faturaId);

        // B-D108 revogou o B-D100, e o motivo veio de B-D107: quem paga a fatura
        // e o dono do contrato, entao fechar o ciclo tambem e dele. Quem recebeu
        // um plastico agiria sobre um documento que nao paga e cujo total nao ve.
        exigirCartaoNaoEmprestado(ambienteId, f.getCartaoId());

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
        exigirCartaoNaoEmprestado(ambienteId, f.getCartaoId());
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
    /**
     * O fechamento automatico so roda para quem PODE fechar (B-D108).
     *
     * <p>Sem esta guarda, abrir a tela de cartoes de quem recebeu um plastico
     * dispararia um UPDATE que {@code pol_fatura_porta} recusa — e a recusa da
     * politica nao produz erro de permissao: ela nao encontra a linha, e o
     * Hibernate transforma isso num 500 sobre estado obsoleto. A tela dela
     * quebraria ao abrir, num caminho que nem e dela.</p>
     *
     * <p>Consequencia aceita: se so ela abrir o sistema por semanas, o ciclo fica
     * aberto ate ele entrar. E coerente com B-D107 — o ciclo e do contrato.</p>
     */
    private void fecharVencidasSePuder(UUID ambienteId, UUID cartaoId, LocalDate hoje) {
        boolean emprestado = vinculos.findById(new ContaAmbiente.Chave(cartaoId, ambienteId))
            .map(v -> !v.isOrigem())
            .orElse(false);

        if (!emprestado) {
            fecharVencidas(cartaoId, hoje);
        }
    }

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

        // B-D107: quem paga a fatura e o dono do contrato. Ela compra no plastico
        // que recebeu, e o acerto entre os dois acontece fora do cartao — por
        // transferencia, ou porque ele paga e ela devolve. Revoga o B-D99, e ele
        // decidiu assim sabendo: "no final a conta vai chegar com o total e eu sou
        // o responsavel para fazer esse pagamento".
        //
        // Cada um pagar uma parte e conversa ADIADA, nao descartada.
        exigirCartaoNaoEmprestado(ambienteId, cartao.getContaId());

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

    /**
     * Os numeros das faturas, ATRAVESSANDO ambientes (B-D87/B-D96).
     *
     * <p>Passa por {@code app_total_da_fatura} e nao pelo repositorio, e o motivo
     * e o mesmo do saldo da conta — com uma consequencia pior: a RLS esconde as
     * compras de quem divide o cartao, e um total que as ignora faz a fatura
     * <b>parecer menor do que e</b>. O pagamento sairia curto e a pessoa
     * descobriria com juros.</p>
     *
     * <p>Uma consulta para a tela inteira, e nao uma por fatura: o
     * {@code CROSS JOIN LATERAL} chama a funcao uma vez por linha de
     * {@code fatura} sem a aplicacao enviar a lista de ids — quem enumera e a
     * propria tabela, que a RLS ja filtra.</p>
     */
    @SuppressWarnings("unchecked")
    private List<Item> comNumeros(List<Fatura> lista, UUID cartaoId, UUID ambienteId,
                                  LocalDate hoje) {
        if (lista.isEmpty()) {
            return List.of();
        }

        // O ESCOPO do numero depende de quem pergunta (B-D110), e este e o unico
        // lugar do sistema em que a mesma pergunta tem duas respostas legitimas:
        //
        //   o dono ve o total do CONTRATO — e o valor unico que o banco cobra;
        //   quem recebeu plasticos ve o total DAQUELES plasticos, e mais nada.
        //
        // Devolver o total do contrato para ela seria entregar o volume de gastos
        // dos outros nove plasticos num numero so. E o pagamento nem entra na
        // conta dela: por B-D107 ela nao paga, e pagamento e movimento do
        // contrato.
        // Por AMBIENTE, e nao por usuario (B-D111): quem tem os dois acessos —
        // membro do ambiente dele E dona de um plastico — via o total do contrato
        // tambem dentro do ambiente dela, onde so a parte dela deveria estar.
        boolean doDono = vinculos.findById(new ContaAmbiente.Chave(cartaoId, ambienteId))
            .filter(ContaAmbiente::estaAtivo)
            .map(ContaAmbiente::isOrigem)
            .orElse(false);

        List<Object[]> linhas = doDono
            ? em.createNativeQuery("""
                    SELECT f.id, t.compras, t.pagamentos
                      FROM fatura f
                      CROSS JOIN LATERAL app_total_da_fatura(f.id) t
                     WHERE f.cartao_id = :cartao
                    """)
                .setParameter("cartao", cartaoId)
                .getResultList()
            : em.createNativeQuery("""
                    SELECT f.id, coalesce(SUM(t.previsto), 0), 0::numeric
                      FROM fatura f
                      JOIN cartao_emitido_ambiente cea ON cea.ambiente_id = :ambiente
                                                      AND cea.encerrado_em IS NULL
                      JOIN cartao_emitido ce ON ce.id = cea.cartao_emitido_id
                                            AND ce.cartao_id = f.cartao_id
                      CROSS JOIN LATERAL app_total_do_plastico(ce.id, f.id) t
                     WHERE f.cartao_id = :cartao
                     GROUP BY f.id
                    """)
                .setParameter("cartao", cartaoId)
                .setParameter("ambiente", ambienteId)
                .getResultList();

        Map<UUID, TotalDaFatura> totais = new HashMap<>();
        for (Object[] l : linhas) {
            totais.put((UUID) l[0],
                       new TotalDaFatura((UUID) l[0], (BigDecimal) l[1], (BigDecimal) l[2]));
        }

        return lista.stream()
            .map(f -> new Item(f,
                               totais.getOrDefault(f.getId(), TotalDaFatura.zeradaPara(f.getId())),
                               hoje,
                               doDono))
            .toList();
    }

    private Fatura exigirFatura(UUID ambienteId, UUID faturaId) {
        Fatura f = faturas.findById(faturaId)
            .orElseThrow(() -> new RecursoNaoEncontrado("Fatura nao encontrada"));
        exigirCartao(ambienteId, f.getCartaoId());
        return f;
    }

    private Cartao exigirCartao(UUID ambienteId, UUID cartaoId) {
        if (vinculos.findById(new ContaAmbiente.Chave(cartaoId, ambienteId))
                .filter(ContaAmbiente::estaAtivo)
                .isEmpty()) {
            throw new RecursoNaoEncontrado("Cartao nao encontrado");
        }
        return cartoes.findById(cartaoId)
            .orElseThrow(() -> new RecursoNaoEncontrado("Cartao nao encontrado"));
    }

    /**
     * O cartao nasceu neste ambiente — exigencia de fechar, reabrir e pagar
     * (B-D107/B-D108).
     *
     * <p>{@code pol_fatura_porta} ja recusa no banco; a checagem aqui existe
     * porque a recusa da politica nao produz erro — o UPDATE nao encontra a linha,
     * e o Hibernate transforma isso num 500 sobre estado obsoleto.</p>
     */
    private void exigirCartaoNaoEmprestado(UUID ambienteId, UUID cartaoId) {
        boolean emprestado = vinculos.findById(new ContaAmbiente.Chave(cartaoId, ambienteId))
            .map(v -> !v.isOrigem())
            .orElse(false);

        if (emprestado) {
            throw new OperacaoNaoPermitida(
                "Este cartao foi dividido com voce. Fechar a fatura voce pode;"
                    + " reabrir desfaz o fechamento para as duas pessoas, e e de quem"
                    + " abriu o contrato.");
        }
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
    /**
     * Uma fatura com os numeros que QUEM PERGUNTA pode ver (B-D110).
     *
     * <p>{@code doContrato} falso significa que {@code numeros} soma apenas os
     * plasticos de quem esta olhando — e que {@code quitacao} nao tem sentido
     * nenhum ali, porque quem paga e o dono (B-D107). A tela usa o marcador para
     * nao exibir estado de pagamento a quem nao paga.</p>
     */
    public record Item(Fatura fatura, TotalDaFatura numeros, LocalDate hoje,
                       boolean doContrato) {

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
