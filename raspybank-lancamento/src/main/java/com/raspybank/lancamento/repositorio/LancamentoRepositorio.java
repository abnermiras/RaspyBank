package com.raspybank.lancamento.repositorio;

import com.raspybank.lancamento.dominio.FormaPagamento;
import com.raspybank.lancamento.dominio.Lancamento;
import com.raspybank.lancamento.dominio.LinhaDoMapa;
import com.raspybank.lancamento.dominio.SaldoDaConta;
import com.raspybank.lancamento.dominio.SituacaoLancamento;
import com.raspybank.lancamento.dominio.TipoLancamento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Acesso a dados de Lancamento.
 *
 * <p>Toda consulta filtra por {@code ambienteId} pela razao explicada em
 * {@link CategoriaRepositorio}: a RLS libera os lancamentos de todos os
 * ambientes da pessoa, e a tela quer os de um so.</p>
 *
 * <p>As datas usadas para recortar periodo sao sempre {@code dataCaixa}: o
 * regime e de caixa (P-T2), e o quadro central responde "quanto saiu do
 * bolso em cada mes". {@code dataCompetencia} fica guardada para o dia em
 * que a outra lente virar alternador (F14), e nao entra em consulta nenhuma
 * ainda.</p>
 *
 * <p><b>A agregacao do mapa de gastos nao esta aqui.</b> Ela devolve dois
 * numeros por celula (realizado e previsto, B-D10) agrupados por categoria e
 * subcategoria, o que nao e uma lista de lancamentos e sim uma projecao —
 * entra junto com o endpoint do relatorio, com o formato de
 * {@code docs/api.md} a vista.</p>
 */
public interface LancamentoRepositorio extends JpaRepository<Lancamento, UUID> {

    /**
     * O extrato de um mes (T-08), do mais recente para o mais antigo.
     *
     * <p>O empate de data e desfeito por {@code criadoEm} porque
     * {@code dataCaixa} e {@code date}: cinco compras no mesmo dia sairiam em
     * ordem arbitraria, e a ordem arbitraria muda entre duas chamadas
     * iguais.</p>
     */
    List<Lancamento> findByAmbienteIdAndDataCaixaBetweenOrderByDataCaixaDescCriadoEmDesc(
        UUID ambienteId, LocalDate inicio, LocalDate fim);

    /**
     * O extrato do mes com os filtros opcionais da T-08.
     *
     * <p>Um metodo em vez de oito: com tres filtros opcionais, a familia de
     * metodos derivados do nome seria uma combinacao para cada subconjunto, e
     * a proxima coluna filtravel dobraria a lista. O padrao
     * {@code (:x IS NULL OR campo = :x)} deixa o banco decidir, e o plano
     * ignora o ramo constante.</p>
     *
     * <p>O filtro de {@code ambienteId} nao e opcional e nao esta ali por
     * seguranca — o RLS ja fez essa parte. Esta pelo motivo de B-D21: sem ele,
     * o extrato do ambiente da casa traria junto os lancamentos do freelance.</p>
     */
    @Query("""
        SELECT l FROM Lancamento l
         WHERE l.ambienteId = :ambienteId
           AND l.dataCaixa BETWEEN :inicio AND :fim
           AND (:contaId     IS NULL OR l.contaId     = :contaId)
           AND (:categoriaId IS NULL OR l.categoriaId = :categoriaId)
           AND (:situacao    IS NULL OR l.situacao    = :situacao)
         ORDER BY l.dataCaixa DESC, l.criadoEm DESC
        """)
    List<Lancamento> buscar(@Param("ambienteId") UUID ambienteId,
                            @Param("inicio") LocalDate inicio,
                            @Param("fim") LocalDate fim,
                            @Param("contaId") UUID contaId,
                            @Param("categoriaId") UUID categoriaId,
                            @Param("situacao") SituacaoLancamento situacao);

    /** Extrato de uma conta — a base do saldo, que e soma e nunca coluna (P1). */
    List<Lancamento> findByAmbienteIdAndContaIdOrderByDataCaixaDescCriadoEmDesc(
        UUID ambienteId, UUID contaId);

    /**
     * O saldo de varias contas de uma vez — dois numeros por conta.
     *
     * <p>Uma consulta para a tela inteira, e nao uma por conta. Com dez contas
     * a diferenca e invisivel; e o habito que decide, porque a versao ingenua
     * nunca da sinal de que esta errada, so fica devagar.</p>
     *
     * <p><b>O sinal e aplicado aqui</b>, na soma: {@code valor} e sempre
     * positivo no banco (F1) e o sentido mora em {@code tipo}. Somar sem o
     * CASE daria a soma do movimento, nao o saldo.</p>
     *
     * <p>Conta sem lancamento nenhum <b>nao aparece</b> no resultado — o
     * {@code GROUP BY} nao inventa grupo vazio. Quem chama completa com zero,
     * ver {@link SaldoDaConta#zeradoPara}.</p>
     */
    @Query("""
        SELECT new com.raspybank.lancamento.dominio.SaldoDaConta(
                   l.contaId,
                   SUM(CASE WHEN l.situacao = :realizado
                            THEN (CASE WHEN l.tipo = :entrada THEN l.valor ELSE -l.valor END)
                            ELSE 0 END),
                   SUM(CASE WHEN l.tipo = :entrada THEN l.valor ELSE -l.valor END))
          FROM Lancamento l
         WHERE l.contaId IN :contaIds
         GROUP BY l.contaId
        """)
    List<SaldoDaConta> somarPorConta(@Param("contaIds") Collection<UUID> contaIds,
                                     @Param("realizado") SituacaoLancamento realizado,
                                     @Param("entrada") TipoLancamento entrada);

    /** Atalho do caso comum: os dois parametros de enum sao sempre os mesmos. */
    default List<SaldoDaConta> saldos(Collection<UUID> contaIds) {
        if (contaIds.isEmpty()) {
            return List.of();
        }
        return somarPorConta(contaIds, SituacaoLancamento.REALIZADO, TipoLancamento.ENTRADA);
    }

    /**
     * A varredura unica do mapa de gastos (T-07).
     *
     * <p>Uma consulta para o quadro central inteiro: os doze meses, os dois
     * blocos, as categorias e as subcategorias. O que volta e um punhado de
     * linhas ja agregadas — o resto (aninhar, completar mes vazio, somar
     * totais) e trabalho em memoria sobre elas, nao sobre a tabela.</p>
     *
     * <h4>Tres decisoes visiveis no SQL</h4>
     *
     * <p><b>O JOIN e explicito</b> porque nao existe associacao mapeada entre
     * lancamento e categoria — o lancamento guarda o id (fronteira de
     * contexto, e o que permite extrair um modulo depois).</p>
     *
     * <p><b>{@code entraNoMapa} filtra, {@code sistemica} nao</b> (B-D15).
     * Transferencia e ajuste saem; "Nao classificado" fica, porque e gasto de
     * verdade sem rotulo. Usar a flag errada aqui faria o total mentir para
     * baixo, em silencio.</p>
     *
     * <p><b>O mes vem de {@code dataCaixa}</b> (P-T2), que e {@code date}
     * (B-D8) — sem hora, entao sem fuso para jogar a compra da noite de 31/jan
     * na coluna de fevereiro.</p>
     */
    @Query("""
        SELECT new com.raspybank.lancamento.dominio.LinhaDoMapa(
                   l.tipo,
                   l.categoriaId,
                   c.nome,
                   l.subcategoriaId,
                   s.nome,
                   month(l.dataCaixa),
                   SUM(CASE WHEN l.situacao = :realizado THEN l.valor ELSE 0 END),
                   SUM(CASE WHEN l.situacao = :realizado THEN 0 ELSE l.valor END))
          FROM Lancamento l
          JOIN Categoria c ON c.id = l.categoriaId
          LEFT JOIN Subcategoria s ON s.id = l.subcategoriaId
         WHERE l.ambienteId = :ambienteId
           AND l.dataCaixa BETWEEN :inicio AND :fim
           AND c.entraNoMapa = true
         GROUP BY l.tipo, l.categoriaId, c.nome, l.subcategoriaId, s.nome, month(l.dataCaixa)
        """)
    List<LinhaDoMapa> varrerParaOMapa(@Param("ambienteId") UUID ambienteId,
                                      @Param("inicio") LocalDate inicio,
                                      @Param("fim") LocalDate fim,
                                      @Param("realizado") SituacaoLancamento realizado);

    /** Atalho: o parametro de situacao e sempre o mesmo. */
    default List<LinhaDoMapa> mapaDoAno(UUID ambienteId, int ano) {
        return varrerParaOMapa(ambienteId,
                               LocalDate.of(ano, 1, 1),
                               LocalDate.of(ano, 12, 31),
                               SituacaoLancamento.REALIZADO);
    }

    /**
     * Existe lancamento nesta categoria?
     *
     * <p>Nao serve para autorizar exclusao — categoria nao se exclui, se
     * arquiva (B-D4). Serve para a T-04 avisar quantos lancamentos a
     * pessoa esta prestes a tirar do seletor.</p>
     */
    long countByCategoriaId(UUID categoriaId);

    /**
     * Quantos lancamentos da conta usam esta forma de pagamento.
     *
     * <p>Serve para recusar a remocao de uma forma da lista de uma conta ANTES
     * de o banco recusar. A chave {@code fk_lancamento_forma_pagamento} ja e
     * {@code ON DELETE RESTRICT} e barraria de qualquer jeito — o que se ganha
     * aqui e a frase: "3 lancamentos usam BOLETO" contra "uma restricao de
     * integridade falhou", que a tela nao tem como transformar em instrucao.</p>
     */
    long countByContaIdAndFormaPagamento(UUID contaId, FormaPagamento formaPagamento);

    /**
     * Vira para {@code REALIZADO} os previstos cuja data de caixa ja passou.
     *
     * <p>Um UPDATE em lote, e nao um laco de leitura e gravacao: a virada roda
     * no caminho de LEITURA do extrato, dos saldos e do mapa, e um laco faria
     * cada abertura de tela custar uma consulta por lancamento vencido.</p>
     *
     * <p>De mao unica de proposito — so {@code PREVISTO} para {@code REALIZADO},
     * nunca o contrario. Ver {@link com.raspybank.lancamento.servico.SituacaoVencidaServico}
     * para a briga que isso evita com {@code corrigirSituacao}.</p>
     *
     * <p>O filtro por {@code ambienteId} nao esta aqui por seguranca — a RLS ja
     * fez essa parte. Esta pelo motivo de B-D21: sem ele, abrir o extrato da
     * casa viraria tambem os previstos do freelance, e a auditoria registraria
     * uma alteracao em massa que a pessoa nao pediu.</p>
     *
     * <p>{@code clearAutomatically} porque um UPDATE em lote nao passa pelo
     * contexto de persistencia: sem ele, uma entidade ja carregada continuaria
     * dizendo {@code PREVISTO} na mesma transacao, e a tela mostraria o valor
     * velho logo depois de o banco ter mudado.</p>
     */
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE Lancamento l
           SET l.situacao = com.raspybank.lancamento.dominio.SituacaoLancamento.REALIZADO
         WHERE l.ambienteId = :ambienteId
           AND l.situacao   = com.raspybank.lancamento.dominio.SituacaoLancamento.PREVISTO
           AND l.dataCaixa <= :hoje
        """)
    int realizarPrevistosVencidos(@Param("ambienteId") UUID ambienteId,
                                  @Param("hoje") LocalDate hoje);
}
