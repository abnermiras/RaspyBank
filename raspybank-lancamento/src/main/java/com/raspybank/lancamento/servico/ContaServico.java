package com.raspybank.lancamento.servico;

import com.raspybank.lancamento.dominio.Categoria;
import com.raspybank.lancamento.dominio.CodigoSistemico;
import com.raspybank.lancamento.dominio.Conta;
import com.raspybank.lancamento.dominio.ContaAmbiente;
import com.raspybank.lancamento.dominio.ContaFormaPagamento;
import com.raspybank.lancamento.dominio.FormaPagamento;
import com.raspybank.lancamento.dominio.Lancamento;
import com.raspybank.lancamento.dominio.LinhaDoExtrato;
import com.raspybank.lancamento.dominio.NaturezaConta;
import com.raspybank.lancamento.dominio.SituacaoLancamento;
import com.raspybank.lancamento.dominio.TipoLancamento;
import com.raspybank.lancamento.repositorio.CategoriaRepositorio;
import com.raspybank.lancamento.repositorio.ContaAmbienteRepositorio;
import com.raspybank.lancamento.repositorio.ContaFormaPagamentoRepositorio;
import com.raspybank.lancamento.repositorio.ContaRepositorio;
import com.raspybank.lancamento.repositorio.LancamentoRepositorio;
import com.raspybank.lancamento.dominio.SaldoDaConta;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Casos de uso da conta (T-05).
 *
 * <h3>Criar conta e o unico caso do modulo que nao usa o repositorio</h3>
 *
 * <p>Criar conta e gravar duas linhas — a conta e o vinculo — e a primeira so
 * se torna visivel depois da segunda. A politica {@code pol_conta_vinculada}
 * pergunta a {@code app_contas_do_usuario()} se a conta e visivel, e para uma
 * conta que esta nascendo a resposta e sempre NAO. Um {@code save()} gravaria
 * uma linha que nem quem a criou consegue ler.</p>
 *
 * <p>A saida e {@code app_criar_conta(...)}: porta unica, estreita, que faz as
 * duas insercoes com privilegio de proprietario e <b>confere o vinculo do
 * usuario com o ambiente</b> antes. E a excecao legitima do criterio B-D19 —
 * impasse com a politica, nao conveniencia de camada.</p>
 *
 * <p>Do segundo passo em diante a conta ja e visivel, e leitura e alteracao
 * passam pelo repositorio normalmente.</p>
 */
@Service
public class ContaServico {

    private final ContaRepositorio contas;
    private final ContaAmbienteRepositorio vinculos;
    private final CategoriaRepositorio categorias;
    private final LancamentoRepositorio lancamentos;
    private final ContaFormaPagamentoRepositorio formasDePagamento;
    private final SituacaoVencidaServico vencidos;
    private final CompartilhamentoContaServico compartilhamentos;

    @PersistenceContext
    private EntityManager em;

    public ContaServico(ContaRepositorio contas,
                        ContaAmbienteRepositorio vinculos,
                        CategoriaRepositorio categorias,
                        LancamentoRepositorio lancamentos,
                        ContaFormaPagamentoRepositorio formasDePagamento,
                        SituacaoVencidaServico vencidos,
                        CompartilhamentoContaServico compartilhamentos) {
        this.contas = contas;
        this.vinculos = vinculos;
        this.categorias = categorias;
        this.lancamentos = lancamentos;
        this.formasDePagamento = formasDePagamento;
        this.vencidos = vencidos;
        this.compartilhamentos = compartilhamentos;
    }

    // =========================================================================
    // Leitura
    // =========================================================================

    /**
     * As contas do ambiente com saldo e vinculos, em tres consultas fixas.
     *
     * <p>Tres, e nao tres por conta: uma para as contas, uma para os saldos de
     * todas elas, uma para os vinculos de todas elas.</p>
     */
    @Transactional(readOnly = true)
    public List<Resumo> listar(UUID ambienteId, boolean incluirEncerradas) {

        // O saldo REALIZADO e uma soma filtrada por situacao: sem a virada, o
        // boleto vencido ontem ficaria de fora do numero que a pessoa confere
        // contra o extrato do banco.
        vencidos.realizarVencidos(ambienteId, LocalDate.now());

        // Bancarias: o cartao e uma conta, mas nao e um lugar onde se guarda
        // dinheiro, e mostra-lo aqui confunde (B-D62). Ele tem tela propria.
        List<Conta> lista = contas.bancariasDoAmbiente(ambienteId, incluirEncerradas);

        if (lista.isEmpty()) {
            return List.of();
        }

        List<UUID> ids = lista.stream().map(Conta::getId).toList();

        Map<UUID, SaldoDaConta> saldos = saldosQueAtravessam(ambienteId);

        // Vinculo ATIVO (B-D93): a conta devolvida sai da lista de ambientes na
        // hora, e o encerrado nao volta a aparecer como se nada tivesse
        // acontecido.
        Map<UUID, List<UUID>> ambientesPorConta = new HashMap<>();
        Map<UUID, Boolean> origemPorConta = new HashMap<>();
        for (ContaAmbiente v : vinculos.findByContaIdInAndEncerradoEmIsNull(ids)) {
            ambientesPorConta
                .computeIfAbsent(v.getContaId(), k -> new ArrayList<>())
                .add(v.getAmbienteId());

            // O vinculo DESTE ambiente e quem responde "ha porta aqui?" (B-D95).
            if (v.getAmbienteId().equals(ambienteId)) {
                origemPorConta.put(v.getContaId(), v.isOrigem());
            }
        }

        Set<UUID> compartilhadas = contasCompartilhadas(ambienteId);
        Map<UUID, String> recebidasDe = donosDasContasRecebidas(ambienteId);

        // Duas perguntas parecidas que NAO sao a mesma, e confundi-las foi o
        // defeito que CompartilhamentoApiTest apanhou: "a conta nasceu aqui?"
        // (origem) responde quem mexe no dinheiro dela, e o convidado do
        // ambiente mexe (B-D76); "eu sou dono deste ambiente?" responde quem
        // mexe na PORTA, e so o dono compartilha (B-D91).
        boolean souDonoDoAmbiente = souDonoDoAmbiente(ambienteId);

        // Quarta consulta, mesmo criterio das outras tres: uma para as formas de
        // TODAS as contas, nao uma por conta.
        Map<UUID, List<ContaFormaPagamento>> formasPorConta = new HashMap<>();
        for (ContaFormaPagamento f : formasDePagamento.findByContaIdIn(ids)) {
            formasPorConta
                .computeIfAbsent(f.getContaId(), k -> new ArrayList<>())
                .add(f);
        }

        return lista.stream()
            .map(c -> new Resumo(
                c,
                saldos.getOrDefault(c.getId(), SaldoDaConta.zeradoPara(c.getId())),
                ambientesPorConta.getOrDefault(c.getId(), List.of()),
                formasPorConta.getOrDefault(c.getId(), List.of()),
                origemPorConta.getOrDefault(c.getId(), true),
                souDonoDoAmbiente && origemPorConta.getOrDefault(c.getId(), false),
                compartilhadas.contains(c.getId()),
                recebidasDe.get(c.getId())))
            .toList();
    }

    /**
     * Uma conta com saldo e vinculos — a mesma forma que a listagem devolve.
     *
     * <p>Existe para que criar, renomear e encerrar respondam <b>igual</b> ao
     * {@code GET}. Devolver uma forma reduzida nas escritas obrigaria a tela a
     * ter dois caminhos de leitura para o mesmo objeto, e o segundo caminho e
     * sempre o que fica desatualizado.</p>
     */
    @Transactional(readOnly = true)
    public Resumo resumo(UUID ambienteId, UUID contaId) {
        vencidos.realizarVencidos(ambienteId, LocalDate.now());
        Conta c = exigir(ambienteId, contaId);

        SaldoDaConta saldo = saldo(ambienteId, contaId);

        List<ContaAmbiente> ativos = vinculos.findByContaId(contaId).stream()
            .filter(ContaAmbiente::estaAtivo)
            .toList();

        boolean origem = ativos.stream()
            .filter(v -> v.getAmbienteId().equals(ambienteId))
            .findFirst()
            .map(ContaAmbiente::isOrigem)
            .orElse(true);

        boolean podeCompartilhar = origem && souDonoDoAmbiente(ambienteId);

        return new Resumo(
            c,
            saldo,
            ativos.stream().map(ContaAmbiente::getAmbienteId).toList(),
            formasDePagamento.findByContaId(contaId),
            origem,
            podeCompartilhar,
            // A pergunta so pode ser feita por quem passa pelo porteiro de
            // app_compartilhamentos_da_conta — que e o dono. Para o convidado do
            // ambiente, perguntar levantaria excecao e derrubaria a tela inteira.
            podeCompartilhar && !compartilhamentos.compartilhamentos(contaId).isEmpty(),
            origem ? null : compartilhamentos.donoDaConta(contaId).nome());
    }

    /**
     * O saldo de UMA conta, atravessando ambientes (B-D87).
     *
     * <p>Passa por {@code app_saldo_da_conta} e nao pelo repositorio: a RLS
     * esconde o lancamento do outro ambiente, e esconde certo — a politica
     * correta e justamente a que o esconde. O que sobra e uma pergunta legitima,
     * <i>"quanto tem nesta conta?"</i>, cuja resposta atravessa uma fronteira que
     * o resto do sistema nao deve atravessar. Ver B-D96 e o porteiro na primeira
     * linha da funcao.</p>
     *
     * <p>Sem isto, os dois veriam saldos diferentes na mesma conta e cada um
     * conferiria o proprio numero contra o mesmo extrato do banco.</p>
     */
    @Transactional(readOnly = true)
    public SaldoDaConta saldo(UUID ambienteId, UUID contaId) {
        vencidos.realizarVencidos(ambienteId, LocalDate.now());
        exigir(ambienteId, contaId);

        Object[] l = (Object[]) em.createNativeQuery(
                "SELECT realizado, previsto FROM app_saldo_da_conta(:conta)")
            .setParameter("conta", contaId)
            .getSingleResult();

        return new SaldoDaConta(contaId, (BigDecimal) l[0], (BigDecimal) l[1]);
    }

    /**
     * O extrato da conta, e e aqui que ela se confere contra o banco (§2d).
     *
     * <p>Atravessa ambientes; o extrato do MES ({@code GET /api/lancamentos})
     * continua sendo o do ambiente e nao atravessa. Sao duas perguntas
     * diferentes, e a que bate com o extrato do banco e esta.</p>
     *
     * <p>A linha alheia chega recortada, e nao por filtro daqui: a funcao nao
     * devolve descricao nem categoria do lancamento de outro ambiente (B-D89 via
     * B-D97). O que a aplicacao nunca recebeu, ela nao vaza.</p>
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<LinhaDoExtrato> extrato(UUID ambienteId, UUID contaId,
                                        LocalDate inicio, LocalDate fim) {
        vencidos.realizarVencidos(ambienteId, LocalDate.now());
        exigir(ambienteId, contaId);

        List<Object[]> linhas = em.createNativeQuery("""
                SELECT id, meu, data_caixa, tipo, situacao, valor, forma_pagamento,
                       descricao, categoria_id, categoria_nome, quem_nome,
                       fatura_id, parcela_numero, parcela_total
                  FROM app_extrato_da_conta(:conta, :inicio, :fim)
                """)
            .setParameter("conta", contaId)
            .setParameter("inicio", inicio)
            .setParameter("fim", fim)
            .getResultList();

        return linhas.stream()
            .map(l -> new LinhaDoExtrato(
                (UUID) l[0],
                (Boolean) l[1],
                ((java.sql.Date) l[2]).toLocalDate(),
                TipoLancamento.valueOf((String) l[3]),
                SituacaoLancamento.valueOf((String) l[4]),
                (BigDecimal) l[5],
                l[6] == null ? null : FormaPagamento.valueOf((String) l[6]),
                (String) l[7],
                (UUID) l[8],
                (String) l[9],
                (String) l[10],
                (UUID) l[11],
                l[12] == null ? null : ((Number) l[12]).intValue(),
                l[13] == null ? null : ((Number) l[13]).intValue()))
            .toList();
    }

    // =========================================================================
    // Escrita
    // =========================================================================

    /**
     * Cria a conta e, se houver saldo inicial, o lancamento que o representa.
     *
     * <p><b>Nao existe campo de saldo</b> (P1): abrir uma conta com 3.000 reais
     * e registrar um lancamento na categoria sistemica {@code AJUSTE} (A13). A
     * tela mostra isso em vez de fingir um campo magico, e o extrato explica de
     * onde veio o numero — o que uma coluna nunca explicaria.</p>
     *
     * <p>Saldo inicial negativo e legitimo e comum: e o caso de uma conta
     * {@code PASSIVO}, ou de uma corrente no vermelho. O sinal escolhe o
     * sentido do lancamento; o valor gravado segue positivo (F1).</p>
     *
     * @param hoje data de referencia da derivacao de situacao (B-D9)
     */
    @Transactional
    public Conta criar(UUID ambienteId, String nome, NaturezaConta natureza,
                       BigDecimal saldoInicial, Set<FormaPagamento> formas,
                       FormaPagamento padraoSaida, FormaPagamento padraoEntrada,
                       UUID usuarioId, LocalDate hoje) {

        UUID contaId = (UUID) em.createNativeQuery(
                "SELECT app_criar_conta(:ambiente, :nome, :natureza)")
            .setParameter("ambiente", ambienteId)
            .setParameter("nome", nome)
            .setParameter("natureza", natureza.name())
            .getSingleResult();

        // O flush explicito faz a funcao rodar AGORA, antes de o lancamento do
        // saldo inicial precisar da conta. Sem ele, o Hibernate poderia adiar a
        // consulta nativa e o lancamento esbarraria na chave composta.
        em.flush();

        // As formas entram ANTES do saldo de abertura, mas isso nao muda nada
        // para ele: o lancamento de abertura e sistemico (AJUSTE) e nunca
        // recebe forma de pagamento — saldo inicial nao foi "pago" de jeito
        // nenhum. Ver registrarAjusteDeAbertura.
        gravarFormas(contaId, formas, padraoSaida, padraoEntrada);

        if (saldoInicial != null && saldoInicial.signum() != 0) {
            registrarAjusteDeAbertura(ambienteId, contaId, saldoInicial, usuarioId, hoje);
        }

        return contas.findById(contaId).orElseThrow(() -> new IllegalStateException(
            "Conta criada pela porta estreita nao ficou visivel — vinculo ausente?"));
    }

    /**
     * Substitui a lista de formas de pagamento aceitas pela conta (T-05).
     *
     * <p>Substituicao e nao acrescimo: a tela mostra a lista inteira com as
     * caixas marcadas, entao o que ela envia <b>e</b> o estado desejado. Um
     * endpoint de acrescimo exigiria um segundo de remocao, e desmarcar uma
     * caixa passaria a ser duas chamadas.</p>
     *
     * <p>Remover uma forma que algum lancamento da conta ja usou e recusado com
     * <b>409</b>, e a mensagem diz quantos. A alternativa seria apagar a forma
     * dos lancamentos antigos — destruindo em silencio exatamente o dado que
     * esta funcionalidade veio registrar.</p>
     *
     * <p>Os dois padroes podem ser nulos: aceitar tres formas sem ter
     * preferencia e legitimo. Se informados, precisam estar em {@code formas} e
     * aceitar o sentido correspondente.</p>
     */
    @Transactional
    public Conta definirFormasDePagamento(UUID ambienteId, UUID contaId,
                                          Set<FormaPagamento> formas,
                                          FormaPagamento padraoSaida,
                                          FormaPagamento padraoEntrada) {

        Conta c = exigirNaoEmprestada(ambienteId, contaId);

        Set<FormaPagamento> desejadas = formas == null ? Set.of() : formas;

        for (ContaFormaPagamento atual : formasDePagamento.findByContaId(contaId)) {
            if (desejadas.contains(atual.getForma())) {
                continue;
            }
            long emUso = lancamentos.countByContaIdAndFormaPagamento(contaId, atual.getForma());
            if (emUso > 0) {
                throw new ConflitoDeEstado(
                    "Nao da para tirar " + atual.getForma() + " desta conta: "
                        + emUso + " lancamento(s) usam essa forma."
                        + " Altere esses lancamentos antes.");
            }
        }

        gravarFormas(contaId, desejadas, padraoSaida, padraoEntrada);
        return c;
    }

    @Transactional
    public Conta renomear(UUID ambienteId, UUID id, String nome) {
        Conta c = exigirNaoEmprestada(ambienteId, id);
        c.renomear(nome);
        return c;
    }

    /**
     * Encerra a conta (F7). Recusa com <b>409</b> se ainda houver dinheiro.
     *
     * <p>Dinheiro nao evapora: encerrar com saldo faria o patrimonio total cair
     * sem que nenhuma saida tivesse sido registrada. O caminho e transferir ou
     * ajustar antes — e o 409 diz isso, em vez de apenas recusar.</p>
     *
     * <p>A checagem olha o saldo <b>realizado</b>. Previsto e agenda, nao
     * dinheiro: um boleto marcado para o mes que vem numa conta que se esta
     * fechando e um lancamento a corrigir, nao um impedimento.</p>
     */
    @Transactional
    public Conta encerrar(UUID ambienteId, UUID id) {
        Conta c = exigirNaoEmprestada(ambienteId, id);

        SaldoDaConta saldo = saldo(ambienteId, id);
        if (!saldo.estaZerado()) {
            throw new ConflitoDeEstado(
                "Conta com saldo de " + saldo.realizado().toPlainString()
                    + " nao pode ser encerrada. Transfira ou ajuste o valor antes.");
        }

        c.encerrar(OffsetDateTime.now());
        return c;
    }

    @Transactional
    public Conta reabrir(UUID ambienteId, UUID id) {
        Conta c = exigirNaoEmprestada(ambienteId, id);
        c.reabrir();
        return c;
    }

    // =========================================================================

    /**
     * Grava a lista desejada, em tres passos que existem por causa dos indices.
     *
     * <p>{@code ux_cfp_padrao_saida} e {@code ux_cfp_padrao_entrada} sao
     * indices unicos parciais, verificados a cada comando. Mover o padrao de
     * saida de {@code DEBITO} para {@code PIX}: marcar PIX antes de desmarcar
     * DEBITO deixa duas linhas verdadeiras no meio do caminho e o banco recusa.
     * Por isso <b>tudo</b> e desmarcado primeiro, as linhas novas nascem
     * falsas, e so no fim uma de cada sentido e marcada.</p>
     *
     * <p>Os {@code flush()} nao sao supersticao: sem eles o Hibernate pode
     * juntar os tres passos numa ordem propria e recriar exatamente o estado
     * intermediario que a sequencia existe para evitar.</p>
     */
    private void gravarFormas(UUID contaId, Set<FormaPagamento> formas,
                              FormaPagamento padraoSaida, FormaPagamento padraoEntrada) {

        Set<FormaPagamento> desejadas = formas == null ? Set.of() : formas;

        // Papel moeda ou dinheiro virtual, nunca os dois. Ver o javadoc de
        // FormaPagamento.listaEhCoerente: uma lista com DINHEIRO e PIX
        // descreveria uma conta que nao existe.
        if (!FormaPagamento.listaEhCoerente(desejadas)) {
            throw new OperacaoNaoPermitida(
                "DINHEIRO e papel moeda e so existe em conta fisica — carteira, gaveta,"
                    + " cofre — que nao aceita pix nem boleto. Escolha DINHEIRO sozinho,"
                    + " ou qualquer combinacao das outras formas.");
        }

        exigirPadraoCoerente(desejadas, padraoSaida, TipoLancamento.SAIDA);
        exigirPadraoCoerente(desejadas, padraoEntrada, TipoLancamento.ENTRADA);

        List<ContaFormaPagamento> atuais = formasDePagamento.findByContaId(contaId);

        // Passo 1 — nenhuma padrao, e as removidas saem.
        atuais.forEach(f -> {
            f.definirPadrao(TipoLancamento.SAIDA, false);
            f.definirPadrao(TipoLancamento.ENTRADA, false);
        });
        formasDePagamento.deleteAll(atuais.stream()
            .filter(f -> !desejadas.contains(f.getForma()))
            .toList());
        em.flush();

        // Passo 2 — as que faltam entram, todas falsas.
        Set<FormaPagamento> jaExistem = atuais.stream()
            .map(ContaFormaPagamento::getForma)
            .filter(desejadas::contains)
            .collect(Collectors.toSet());

        desejadas.stream()
            .filter(nova -> !jaExistem.contains(nova))
            .forEach(nova -> formasDePagamento.save(new ContaFormaPagamento(contaId, nova)));
        em.flush();

        // Passo 3 — agora sim, uma de cada sentido.
        marcarPadrao(contaId, padraoSaida, TipoLancamento.SAIDA);
        marcarPadrao(contaId, padraoEntrada, TipoLancamento.ENTRADA);
        em.flush();
    }

    /**
     * Recusa padrao incoerente com uma frase, antes que o banco recuse com um
     * codigo de constraint.
     *
     * <p>A segunda checagem — se a forma aceita o sentido — nao esta duplicando
     * o banco por acaso. A tabela {@code conta_forma_pagamento} de proposito
     * NAO tem um CHECK impedindo {@code CREDITO_EM_CONTA} como padrao de saida:
     * seria a segunda copia da regra de sentido. O dado ruim nao consegue
     * produzir lancamento ruim de qualquer jeito, porque
     * {@code fk_lancamento_forma_sentido} barra na hora de lancar. O que se
     * ganha aqui e falhar cedo, e com uma frase que a tela exibe.</p>
     */
    private static void exigirPadraoCoerente(Set<FormaPagamento> desejadas,
                                             FormaPagamento padrao,
                                             TipoLancamento sentido) {
        if (padrao == null) {
            return;
        }
        if (!desejadas.contains(padrao)) {
            throw new OperacaoNaoPermitida(
                "Forma padrao " + padrao + " precisa estar entre as formas aceitas pela conta");
        }
        if (!padrao.aceita(sentido)) {
            throw new OperacaoNaoPermitida(
                padrao + " nao serve para " + sentido + ", entao nao pode ser o padrao desse sentido");
        }
    }

    private void marcarPadrao(UUID contaId, FormaPagamento forma, TipoLancamento sentido) {
        if (forma == null) {
            return;
        }
        formasDePagamento
            .findById(new ContaFormaPagamento.Chave(contaId, forma))
            .orElseThrow(() -> new IllegalStateException("Forma padrao nao ficou gravada: " + forma))
            .definirPadrao(sentido, true);
    }

    private void registrarAjusteDeAbertura(UUID ambienteId, UUID contaId,
                                           BigDecimal saldoInicial, UUID usuarioId,
                                           LocalDate hoje) {

        Categoria ajuste = categorias.buscarSistemica(ambienteId, CodigoSistemico.AJUSTE)
            .orElseThrow(() -> new IllegalStateException(
                "Ambiente sem a categoria sistemica AJUSTE — fn_criar_categorias_sistemicas nao rodou"));

        TipoLancamento sentido = saldoInicial.signum() > 0
            ? TipoLancamento.ENTRADA
            : TipoLancamento.SAIDA;

        Lancamento abertura = new Lancamento(
            ajuste, contaId, sentido, saldoInicial.abs(), hoje, hoje, usuarioId, hoje);
        abertura.descrever("Saldo inicial");

        lancamentos.save(abertura);
    }

    /**
     * Mesmo recorte de B-D21 usado em categoria: id de outro ambiente e 404.
     *
     * <p>Aqui a pergunta e feita ao <b>vinculo</b>, e nao a conta: conta nao
     * tem coluna de ambiente (R7). Buscar o par exato custa uma leitura por
     * chave primaria — e diz a coisa certa, que e "esta conta aparece neste
     * ambiente?".</p>
     */
    private Conta exigir(UUID ambienteId, UUID id) {
        // O filtro de ativo nao e decorativo: pol_ca_leitura mostra tambem o
        // vinculo encerrado (a pessoa precisa poder ver que devolveu a conta), e
        // sem ele a conta revogada continuaria passando por esta porta — para
        // morrer duas linhas abaixo com um 404 confuso, vindo da conta e nao do
        // vinculo.
        if (vinculos.findById(new ContaAmbiente.Chave(id, ambienteId))
                .filter(ContaAmbiente::estaAtivo)
                .isEmpty()) {
            throw new RecursoNaoEncontrado("Conta nao encontrada");
        }
        return contas.findById(id).orElseThrow(
            () -> new RecursoNaoEncontrado("Conta nao encontrada"));
    }

    /**
     * A conta nasceu neste ambiente — exigencia de renomear, encerrar, reabrir e
     * mexer nas formas (B-D95).
     *
     * <p>{@code pol_conta_escrita} ja recusa no banco, e a checagem aqui existe
     * porque a recusa da politica <b>nao produz erro</b>: o UPDATE simplesmente
     * nao encontra a linha, e o Hibernate transforma isso num 500 sobre estado
     * obsoleto. A pessoa merece a frase, e o 403.</p>
     *
     * <p>Nao confundir com "ser dono do ambiente": quem entrou no ambiente por
     * convite (V15) renomeia e encerra a vontade, porque ali isso e DINHEIRO
     * (B-D76). O que esta barrado e mexer no cadastro de uma conta que nasceu na
     * casa de outra pessoa.</p>
     */
    private Conta exigirNaoEmprestada(UUID ambienteId, UUID id) {
        Conta c = exigir(ambienteId, id);

        boolean emprestada = vinculos.findById(new ContaAmbiente.Chave(id, ambienteId))
            .map(v -> !v.isOrigem())
            .orElse(false);

        if (emprestada) {
            throw new OperacaoNaoPermitida(
                "Esta conta foi compartilhada com voce. Lance nela a vontade;"
                    + " renomear, encerrar e mudar as formas de pagamento sao de quem a abriu.");
        }
        return c;
    }

    /**
     * Os saldos de TODAS as contas do ambiente, atravessando ambientes, numa
     * consulta so.
     *
     * <p>Uma consulta para a tela inteira, e nao uma por conta — o mesmo
     * criterio de {@code somarPorConta}, que ela substitui. O
     * {@code CROSS JOIN LATERAL} chama {@code app_saldo_da_conta} uma vez por
     * linha de {@code conta_ambiente} sem que a aplicacao precise enviar a lista
     * de ids: quem enumera as contas e o proprio vinculo, que a RLS ja filtra
     * pelo lado de quem pergunta.</p>
     *
     * <p>Vem cartao junto, e nao ha problema: quem chama procura por id, e a
     * listagem ja filtrou as bancarias antes (B-D62).</p>
     */
    @SuppressWarnings("unchecked")
    private Map<UUID, SaldoDaConta> saldosQueAtravessam(UUID ambienteId) {
        List<Object[]> linhas = em.createNativeQuery("""
                SELECT ca.conta_id, s.realizado, s.previsto
                  FROM conta_ambiente ca
                  CROSS JOIN LATERAL app_saldo_da_conta(ca.conta_id) s
                 WHERE ca.ambiente_id = :ambiente
                   AND ca.encerrado_em IS NULL
                """)
            .setParameter("ambiente", ambienteId)
            .getResultList();

        Map<UUID, SaldoDaConta> saldos = new HashMap<>();
        for (Object[] l : linhas) {
            UUID contaId = (UUID) l[0];
            saldos.put(contaId, new SaldoDaConta(contaId, (BigDecimal) l[1], (BigDecimal) l[2]));
        }
        return saldos;
    }

    /**
     * Quais contas deste ambiente estao nas maos de mais alguem.
     *
     * <p>Explica na tela por que o saldo e maior do que a soma dos lancamentos
     * visiveis — sem esta marca, a conta compartilhada pareceria ter um erro de
     * soma.</p>
     *
     * <p>O filtro por {@code app_contas_proprias()} nao e redundancia do
     * porteiro da funcao: e o que impede o porteiro de <b>levantar excecao</b>.
     * Sem ele, uma conta emprestada nesta lista abortaria a consulta inteira, e a
     * tela de contas quebraria para quem recebeu uma.</p>
     */
    @SuppressWarnings("unchecked")
    private Set<UUID> contasCompartilhadas(UUID ambienteId) {
        List<UUID> ids = em.createNativeQuery("""
                SELECT DISTINCT ca.conta_id
                  FROM conta_ambiente ca
                  CROSS JOIN LATERAL app_compartilhamentos_da_conta(ca.conta_id) x
                 WHERE ca.ambiente_id = :ambiente
                   AND ca.conta_id IN (SELECT app_contas_proprias())
                """)
            .setParameter("ambiente", ambienteId)
            .getResultList();

        return new HashSet<>(ids);
    }

    /**
     * Sou dono deste ambiente, ou entrei nele por convite (V15)?
     *
     * <p>Uma pergunta ao banco em vez de um parametro vindo do controlador: a
     * resposta e a mesma que o porteiro das funcoes usa, e duas formulacoes da
     * mesma regra divergem no dia em que uma delas muda.</p>
     */
    private boolean souDonoDoAmbiente(UUID ambienteId) {
        return (Boolean) em.createNativeQuery(
                "SELECT :ambiente IN (SELECT app_ambientes_proprios())")
            .setParameter("ambiente", ambienteId)
            .getSingleResult();
    }

    /** O nome de quem abriu cada conta que este ambiente recebeu emprestada. */
    @SuppressWarnings("unchecked")
    private Map<UUID, String> donosDasContasRecebidas(UUID ambienteId) {
        List<Object[]> linhas = em.createNativeQuery("""
                SELECT ca.conta_id, d.nome
                  FROM conta_ambiente ca
                  CROSS JOIN LATERAL app_dono_da_conta(ca.conta_id) d
                 WHERE ca.ambiente_id = :ambiente
                   AND NOT ca.origem
                   AND ca.encerrado_em IS NULL
                """)
            .setParameter("ambiente", ambienteId)
            .getResultList();

        Map<UUID, String> donos = new HashMap<>();
        for (Object[] l : linhas) {
            donos.put((UUID) l[0], (String) l[1]);
        }
        return donos;
    }

    /**
     * Uma conta com o que a T-05 precisa mostrar junto dela.
     *
     * <p>Os quatro ultimos campos sao da V16 e respondem quatro perguntas da
     * tela. {@code origem} — a conta nasceu aqui? E o que libera renomear,
     * encerrar e mexer nas formas, que sao DINHEIRO e valem tambem para quem
     * entrou no ambiente por convite (B-D76). {@code podeCompartilhar} — sou dono
     * do ambiente onde ela nasceu? E o que libera a PORTA (B-D91), e e mais
     * estreito que o anterior de proposito. {@code compartilhada} — alguem mais
     * tem esta conta? {@code recebidaDe} — de quem eu a recebi?</p>
     */
    public record Resumo(Conta conta, SaldoDaConta saldo, List<UUID> ambienteIds,
                         List<ContaFormaPagamento> formasDePagamento,
                         boolean origem, boolean podeCompartilhar,
                         boolean compartilhada, String recebidaDe) {
    }
}
