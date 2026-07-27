package com.raspybank.lancamento.servico;

import com.raspybank.lancamento.dominio.Categoria;
import com.raspybank.lancamento.dominio.CodigoSistemico;
import com.raspybank.lancamento.dominio.Conta;
import com.raspybank.lancamento.dominio.ContaAmbiente;
import com.raspybank.lancamento.dominio.Lancamento;
import com.raspybank.lancamento.dominio.NaturezaConta;
import com.raspybank.lancamento.dominio.TipoLancamento;
import com.raspybank.lancamento.repositorio.CategoriaRepositorio;
import com.raspybank.lancamento.repositorio.ContaAmbienteRepositorio;
import com.raspybank.lancamento.repositorio.ContaRepositorio;
import com.raspybank.lancamento.repositorio.LancamentoRepositorio;
import com.raspybank.lancamento.dominio.SaldoDaConta;
import com.raspybank.shared.erro.ConflitoDeEstado;
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

    @PersistenceContext
    private EntityManager em;

    public ContaServico(ContaRepositorio contas,
                        ContaAmbienteRepositorio vinculos,
                        CategoriaRepositorio categorias,
                        LancamentoRepositorio lancamentos) {
        this.contas = contas;
        this.vinculos = vinculos;
        this.categorias = categorias;
        this.lancamentos = lancamentos;
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

        List<Conta> lista = incluirEncerradas
            ? contas.doAmbiente(ambienteId)
            : contas.ativasDoAmbiente(ambienteId);

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

        return lista.stream()
            .map(c -> new Resumo(
                c,
                saldos.getOrDefault(c.getId(), SaldoDaConta.zeradoPara(c.getId())),
                ambientesPorConta.getOrDefault(c.getId(), List.of())))
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
        Conta c = exigir(ambienteId, contaId);

        SaldoDaConta saldo = lancamentos.saldos(List.of(contaId)).stream()
            .findFirst()
            .orElseGet(() -> SaldoDaConta.zeradoPara(contaId));

        List<UUID> ambienteIds = vinculos.findByContaId(contaId).stream()
            .map(ContaAmbiente::getAmbienteId)
            .toList();

        return new Resumo(c, saldo, ambienteIds);
    }

    @Transactional(readOnly = true)
    public SaldoDaConta saldo(UUID ambienteId, UUID contaId) {
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
                       BigDecimal saldoInicial, UUID usuarioId, LocalDate hoje) {

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

        if (saldoInicial != null && saldoInicial.signum() != 0) {
            registrarAjusteDeAbertura(ambienteId, contaId, saldoInicial, usuarioId, hoje);
        }

        return contas.findById(contaId).orElseThrow(() -> new IllegalStateException(
            "Conta criada pela porta estreita nao ficou visivel — vinculo ausente?"));
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
    public record Resumo(Conta conta, SaldoDaConta saldo, List<UUID> ambienteIds) {
    }
}
