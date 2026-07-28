package com.raspybank.lancamento.servico;

import com.raspybank.lancamento.dominio.Categoria;
import com.raspybank.lancamento.dominio.CodigoSistemico;
import com.raspybank.lancamento.dominio.Conta;
import com.raspybank.lancamento.dominio.ContaAmbiente;
import com.raspybank.lancamento.dominio.ContaFormaPagamento;
import com.raspybank.lancamento.dominio.FormaPagamento;
import com.raspybank.lancamento.dominio.Lancamento;
import com.raspybank.lancamento.dominio.NaturezaConta;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
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

    @PersistenceContext
    private EntityManager em;

    public ContaServico(ContaRepositorio contas,
                        ContaAmbienteRepositorio vinculos,
                        CategoriaRepositorio categorias,
                        LancamentoRepositorio lancamentos,
                        ContaFormaPagamentoRepositorio formasDePagamento,
                        SituacaoVencidaServico vencidos) {
        this.contas = contas;
        this.vinculos = vinculos;
        this.categorias = categorias;
        this.lancamentos = lancamentos;
        this.formasDePagamento = formasDePagamento;
        this.vencidos = vencidos;
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

        Map<UUID, SaldoDaConta> saldos = lancamentos.saldos(ids).stream()
            .collect(Collectors.toMap(SaldoDaConta::contaId, Function.identity()));

        Map<UUID, List<UUID>> ambientesPorConta = new HashMap<>();
        for (ContaAmbiente v : vinculos.findByContaIdIn(ids)) {
            ambientesPorConta
                .computeIfAbsent(v.getContaId(), k -> new ArrayList<>())
                .add(v.getAmbienteId());
        }

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
                formasPorConta.getOrDefault(c.getId(), List.of())))
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

        SaldoDaConta saldo = lancamentos.saldos(List.of(contaId)).stream()
            .findFirst()
            .orElseGet(() -> SaldoDaConta.zeradoPara(contaId));

        List<UUID> ambienteIds = vinculos.findByContaId(contaId).stream()
            .map(ContaAmbiente::getAmbienteId)
            .toList();

        return new Resumo(c, saldo, ambienteIds, formasDePagamento.findByContaId(contaId));
    }

    @Transactional(readOnly = true)
    public SaldoDaConta saldo(UUID ambienteId, UUID contaId) {
        vencidos.realizarVencidos(ambienteId, LocalDate.now());
        exigir(ambienteId, contaId);
        return lancamentos.saldos(List.of(contaId)).stream()
            .findFirst()
            .orElseGet(() -> SaldoDaConta.zeradoPara(contaId));
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

        Conta c = exigir(ambienteId, contaId);

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
        Conta c = exigir(ambienteId, id);
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
        Conta c = exigir(ambienteId, id);

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
        Conta c = exigir(ambienteId, id);
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
        if (vinculos.findById(new ContaAmbiente.Chave(id, ambienteId)).isEmpty()) {
            throw new RecursoNaoEncontrado("Conta nao encontrada");
        }
        return contas.findById(id).orElseThrow(
            () -> new RecursoNaoEncontrado("Conta nao encontrada"));
    }

    /** Uma conta com o que a T-05 precisa mostrar junto dela. */
    public record Resumo(Conta conta, SaldoDaConta saldo, List<UUID> ambienteIds,
                         List<ContaFormaPagamento> formasDePagamento) {
    }
}
