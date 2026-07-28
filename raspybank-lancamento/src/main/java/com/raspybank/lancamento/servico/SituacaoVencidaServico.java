package com.raspybank.lancamento.servico;

import com.raspybank.lancamento.repositorio.LancamentoRepositorio;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * O previsto que venceu vira realizado — sem ninguem precisar lembrar.
 *
 * <h3>O defeito que isto conserta</h3>
 *
 * <p>{@code SituacaoLancamento.derivarDe} (B-D9) so rodava em dois momentos:
 * quando o lancamento nascia e quando alguem o editava. Nada reavaliava com o
 * passar do tempo.</p>
 *
 * <p>Na pratica: a conta de luz lancada para 05/08 nascia {@code PREVISTO},
 * corretamente — e em 06/08 continuava {@code PREVISTO}, embora a data ja
 * tivesse passado. O saldo realizado da conta ignorava aquele valor <b>para
 * sempre</b>, ate alguem abrir o lancamento e edita-lo. O numero que a pessoa
 * confere contra o extrato do banco ficava errado, e nada denunciava.</p>
 *
 * <h3>Por que a virada acontece na LEITURA, e nao num job agendado</h3>
 *
 * <p>Um job agendado seria o desenho obvio, e {@code Canal.SISTEMA} ja existe
 * para ele. O problema e a RLS: uma rotina de fundo nao tem
 * {@code raspybank.usuario_id} na sessao, entao nenhuma politica consegue
 * avalia-la e o UPDATE alcancaria zero linhas. Faze-lo funcionar exigiria uma
 * funcao {@code SECURITY DEFINER} nova — decisao que passa pelo criterio B-D19
 * e pelo inventario de {@code docs/security-definer.md}, e que merece discussao
 * propria.</p>
 *
 * <p>Aqui a virada roda <b>dentro da requisicao da pessoa</b>: a identidade ja
 * esta estabelecida, a RLS funciona sem furo nenhum, e a auditoria grava o
 * usuario de verdade com {@code Canal.WEB} em vez de um {@code SISTEMA} que
 * ninguem pediu.</p>
 *
 * <p>O custo: se ninguem abrir o sistema, nada vira. Que e aceitavel porque
 * tambem nao ha ninguem lendo o numero errado — e na primeira leitura ele se
 * corrige antes de qualquer soma acontecer.</p>
 *
 * <h3>A briga que este desenho evita</h3>
 *
 * <p>A virada e de mao unica: so move {@code PREVISTO} vencido para
 * {@code REALIZADO}, nunca o contrario.</p>
 *
 * <p>Isso importa porque {@code corrigirSituacao} existe (B-D22) e as duas
 * regras poderiam brigar para sempre: a pessoa marca de volta como previsto, a
 * proxima leitura vira de novo. <b>Quando o boleto nao foi pago, a correcao
 * certa e mudar a DATA, nao a situacao</b> — "nao paguei dia 05, pago dia 12"
 * vira reagendar para 12/08, e ai ele volta a previsto sozinho, pela regra
 * normal de B-D9.</p>
 *
 * <p>O caso legitimo de {@code corrigirSituacao} continua intocado: marcar como
 * ja debitado um lancamento <b>futuro</b>. Essa direcao a virada nunca desfaz,
 * porque ela so olha o que ja venceu.</p>
 */
@Service
public class SituacaoVencidaServico {

    private final LancamentoRepositorio lancamentos;

    public SituacaoVencidaServico(LancamentoRepositorio lancamentos) {
        this.lancamentos = lancamentos;
    }

    /**
     * Vira os previstos vencidos do ambiente, e devolve quantos virou.
     *
     * <p>{@code REQUIRES_NEW} porque quem chama esta quase sempre numa
     * transacao {@code readOnly}: as leituras que dependem da situacao — o
     * extrato, os saldos, o mapa — sao todas de leitura, e um UPDATE dentro
     * delas falharia. A transacao propria tambem deixa a virada persistida
     * mesmo que a leitura seguinte encontre algum problema.</p>
     *
     * <p>Devolve {@code int} e nao {@code void} de proposito: o numero e o que
     * permite a um teste provar que a virada aconteceu, em vez de inferir pelo
     * efeito.</p>
     *
     * @param hoje injetado e nao consultado (padrao B-C3) — e o que torna a
     *             regra testavel em qualquer dia, inclusive na virada do mes
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int realizarVencidos(UUID ambienteId, LocalDate hoje) {
        return lancamentos.realizarPrevistosVencidos(ambienteId, hoje);
    }
}
