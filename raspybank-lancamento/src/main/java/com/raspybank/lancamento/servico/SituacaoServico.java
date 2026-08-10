package com.raspybank.lancamento.servico;

import com.raspybank.lancamento.dominio.Cartao;
import com.raspybank.lancamento.dominio.Fatura;
import com.raspybank.lancamento.dominio.QuitacaoFatura;
import com.raspybank.lancamento.dominio.SituacaoLancamento;
import com.raspybank.lancamento.dominio.TotalDaFatura;
import com.raspybank.lancamento.repositorio.CartaoRepositorio;
import com.raspybank.lancamento.repositorio.FaturaRepositorio;
import com.raspybank.lancamento.repositorio.LancamentoRepositorio;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mantem a coluna {@code situacao} coerente — por DUAS regras, nao uma.
 *
 * <p>Antes do I-29 esta classe se chamava {@code SituacaoVencidaServico} e
 * tinha uma regra so. O nome mudou porque passou a ter duas, e um nome que
 * descreve metade do trabalho e um nome que mente.</p>
 *
 * <h3>Regra 1 — o resto do sistema: a data manda (B-D9)</h3>
 *
 * <p>O previsto que venceu vira realizado. O boleto do condominio agendado
 * para 30/08 vira realizado sozinho em 30/08, sem ninguem confirmar nada — foi
 * para nao existir flag manual que B-D9 trocou a pergunta pela consequencia.</p>
 *
 * <p>O defeito que isto consertou continua valendo: {@code derivarDe} so rodava
 * ao nascer e ao editar, entao a conta de luz de 05/08 continuava PREVISTA em
 * 06/08 e o saldo realizado a ignorava <b>para sempre</b>.</p>
 *
 * <h3>Regra 2 — compra de cartao: a fatura manda (I-29)</h3>
 *
 * <p><b>Compra de cartao e REALIZADO se, e somente se, a fatura estiver
 * FECHADA e QUITADA. Em qualquer outro caso, PREVISTO — independente da
 * data.</b></p>
 *
 * <p>A data de caixa de uma compra e o vencimento da fatura (F14), e vencimento
 * e uma PREVISAO de quando o dinheiro sai. Derivar a situacao dela errava nos
 * dois sentidos: fatura paga antes do vencimento mantinha as compras previstas
 * (a fatura dizia QUITADA e cada linha dentro dela dizia "ainda vai sair"), e
 * fatura vencida e nao paga virava tudo para realizado no dia do vencimento,
 * afirmando no mapa um gasto que nao houve.</p>
 *
 * <p><b>Por que FECHADA <i>e</i> QUITADA, e nao quitada sozinha.</b> Fatura
 * fechada nao recebe lancamento novo ({@code exigirFaturaAberta}), entao o
 * total para de se mexer e a regra fica estavel por construcao. Com "quitada
 * sozinha", pagar por inteiro uma fatura ABERTA realizaria tudo, e a proxima
 * compra a cair nela desfaria a quitacao e mandaria todas as compras de volta
 * para previsto. A antecipacao (B-D57) nao perde nada com isso: ela libera
 * limite por {@code consumido() = saldo.comPrevistos().abs()}, que soma
 * previsto e realizado igual — nunca dependeu da situacao das compras.</p>
 *
 * <p><b>Pagamento parcial nao aloca nada.</b> Nao ha regra que diga quais
 * compras foram pagas com 100 de uma fatura de 282, e inventar uma seria
 * inventar dado. Nao quitada e previsto, inteira. Os 100 que sairam do bolso
 * aparecem na perna de SAIDA do pagamento, na conta corrente, que segue a
 * propria data.</p>
 *
 * <h3>Por que RECALCULA, em vez de virar no momento do pagamento</h3>
 *
 * <p>A quitacao muda por caminhos demais: pagar, excluir um pagamento, editar
 * o valor de um pagamento, editar o valor de uma compra, lancar compra em
 * fatura aberta, fechar, reabrir — e, quando o I-25 chegar, um credito. Um
 * flip por caminho sao sete lugares para esquecer um, e o esquecido nao da
 * sinal: fica uma situacao errada que nenhuma tela contradiz. O recalculo e
 * auto-curavel — qualquer que tenha sido o caminho, a proxima leitura acerta.</p>
 *
 * <h3>Por que na LEITURA, e nao num job agendado</h3>
 *
 * <p>Mesma razao das duas regras, e ela e de RLS: rotina de fundo nao tem
 * {@code raspybank.usuario_id} na sessao, entao nenhuma politica consegue
 * avalia-la e o UPDATE alcancaria zero linhas. Faze-lo funcionar exigiria uma
 * funcao {@code SECURITY DEFINER} nova — decisao que passa por B-D19 e pelo
 * inventario de {@code docs/security-definer.md}.</p>
 *
 * <p>Aqui o recalculo roda <b>dentro da requisicao da pessoa</b>: a identidade
 * ja esta estabelecida, a RLS funciona sem furo, e a auditoria grava o usuario
 * de verdade. O custo: se ninguem abrir o sistema, nada vira — aceitavel,
 * porque tambem nao ha ninguem lendo o numero errado.</p>
 *
 * <h3>O que a regra 2 custou, e foi aceito</h3>
 *
 * <p><b>Ela e de mao DUPLA</b>, ao contrario da regra 1. Isso reabre a briga
 * que o desenho original evitava: {@code corrigirSituacao} (B-D22) deixa de
 * valer para compra de cartao, porque a proxima leitura desfaz a correcao. E
 * defensavel — a situacao de uma compra deixou de ser julgamento e virou fato
 * derivado da fatura —, mas e uma liberdade que some.</p>
 *
 * <p><b>Reabrir uma fatura paga devolve tudo para previsto</b>, e fechar de
 * novo devolve para realizado. Coerente com B-D50.</p>
 *
 * <p><b>Fatura nunca paga mantem as compras previstas para sempre.</b> E divida
 * em aberto, e mostrar isso e informacao — mas o mapa acumula previsto antigo.</p>
 */
@Service
public class SituacaoServico {

    private final LancamentoRepositorio lancamentos;
    private final FaturaRepositorio faturas;
    private final CartaoRepositorio cartoes;

    @PersistenceContext
    private EntityManager em;

    public SituacaoServico(LancamentoRepositorio lancamentos,
                           FaturaRepositorio faturas,
                           CartaoRepositorio cartoes) {
        this.lancamentos = lancamentos;
        this.faturas = faturas;
        this.cartoes = cartoes;
    }

    /**
     * Aplica as duas regras e devolve quanto cada uma mexeu.
     *
     * <p>{@code REQUIRES_NEW} porque quem chama esta quase sempre numa
     * transacao {@code readOnly}: o extrato, os saldos e o mapa sao todos de
     * leitura, e um UPDATE dentro deles falharia. A transacao propria tambem
     * deixa o acerto persistido mesmo que a leitura seguinte encontre
     * problema.</p>
     *
     * <p>Devolve os dois numeros separados, e nao a soma: e o que permite a um
     * teste provar QUAL regra agiu, em vez de inferir pelo efeito. Foi assim
     * que a regra 1 ja era testada.</p>
     *
     * @param hoje injetado e nao consultado (padrao B-C3) — e o que torna a
     *             regra testavel em qualquer dia, inclusive na virada do mes
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Sincronizacao sincronizar(UUID ambienteId, LocalDate hoje) {
        int porData = lancamentos.realizarPrevistosVencidos(ambienteId, hoje);
        int porFatura = sincronizarComprasDeCartao(ambienteId);
        return new Sincronizacao(porData, porFatura);
    }

    /**
     * A regra 2, sobre os cartoes que sao DESTE ambiente e dele nasceram.
     *
     * <p>So os proprios (B-D107/B-D108): quem recebeu um plastico dividido
     * enxerga o cartao, mas nao paga a fatura nem fecha o ciclo, e nao deveria
     * mover a situacao de um documento inteiro que nao e dele. E a mesma
     * guarda de {@code fecharVencidasSePuder} — sem ela, abrir a tela dela
     * dispararia um UPDATE que a politica recusa.</p>
     */
    private int sincronizarComprasDeCartao(UUID ambienteId) {
        List<UUID> contasDeCartao = cartoes.propriosDoAmbiente(ambienteId).stream()
            .map(Cartao::getContaId)
            .toList();

        if (contasDeCartao.isEmpty()) {
            return 0;
        }

        List<UUID> comLancamento = lancamentos.faturasComLancamentoNoCartao(contasDeCartao);
        if (comLancamento.isEmpty()) {
            return 0;
        }

        Map<UUID, TotalDaFatura> numeros = totaisAtravessandoAmbientes(comLancamento);

        List<UUID> paraRealizar = new ArrayList<>();
        List<UUID> paraPrever = new ArrayList<>();

        for (Fatura f : faturas.findAllById(comLancamento)) {
            TotalDaFatura n = numeros.getOrDefault(f.getId(), TotalDaFatura.zeradaPara(f.getId()));

            // A pergunta e feita a FATURA, nao remontada aqui. "Pago >= total"
            // escrito nesta classe seria uma segunda copia da regra de B-D58, e
            // duas copias divergem — foi para nao ter agregado em dois lugares
            // que P1 existe.
            boolean fechadaEQuitada = !f.estaAberta()
                && f.quitacao(n.total(), n.pago()) == QuitacaoFatura.QUITADA;

            (fechadaEQuitada ? paraRealizar : paraPrever).add(f.getId());
        }

        int mexidos = 0;
        if (!paraRealizar.isEmpty()) {
            mexidos += lancamentos.ajustarSituacaoDasCompras(
                paraRealizar, SituacaoLancamento.REALIZADO);
        }
        if (!paraPrever.isEmpty()) {
            mexidos += lancamentos.ajustarSituacaoDasCompras(
                paraPrever, SituacaoLancamento.PREVISTO);
        }
        return mexidos;
    }

    /**
     * Compras e pagamentos de cada fatura, pela funcao e nao pelo repositorio.
     *
     * <p>{@code app_total_da_fatura} e {@code SECURITY DEFINER} porque o total
     * de uma fatura ATRAVESSA ambientes (B-D87/B-D96): a RLS esconde as compras
     * de quem divide o cartao, e um total que as ignora faz a fatura parecer
     * menor do que e. Aqui a consequencia de usar a soma recortada seria pior
     * que um numero feio na tela — uma fatura pareceria quitada por faltar
     * compra na conta, e as compras do dono virariam REALIZADO sem ninguem ter
     * pago.</p>
     */
    @SuppressWarnings("unchecked")
    private Map<UUID, TotalDaFatura> totaisAtravessandoAmbientes(List<UUID> faturaIds) {
        List<Object[]> linhas = em.createNativeQuery("""
                SELECT f.id, t.compras, t.pagamentos
                  FROM fatura f
                  CROSS JOIN LATERAL app_total_da_fatura(f.id) t
                 WHERE f.id IN (:ids)
                """)
            .setParameter("ids", faturaIds)
            .getResultList();

        Map<UUID, TotalDaFatura> totais = new HashMap<>();
        for (Object[] l : linhas) {
            totais.put((UUID) l[0],
                       new TotalDaFatura((UUID) l[0], (BigDecimal) l[1], (BigDecimal) l[2]));
        }
        return totais;
    }

    /**
     * Quantas linhas cada regra mexeu.
     *
     * @param porData   regra 1 (B-D9): previstos vencidos que viraram realizados
     * @param porFatura regra 2 (I-29): compras de cartao que trocaram de situacao,
     *                  nos dois sentidos
     */
    public record Sincronizacao(int porData, int porFatura) {

        public int total() {
            return porData + porFatura;
        }
    }
}
