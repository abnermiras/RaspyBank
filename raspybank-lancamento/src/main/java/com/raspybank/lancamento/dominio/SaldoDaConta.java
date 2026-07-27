package com.raspybank.lancamento.dominio;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * O saldo de uma conta, em DOIS numeros.
 *
 * <p>Nunca um so, pelo mesmo motivo de B-D10 no mapa de gastos: somar o que
 * ja aconteceu com o que esta agendado faria o numero significar duas coisas
 * ao mesmo tempo, e a tela nao teria como separar depois. Quem separa e quem
 * calcula.</p>
 *
 * <ul>
 *   <li>{@code realizado} — o que ha na conta hoje. E este que responde
 *       "posso gastar?" e este que precisa ser zero para encerrar (F7).</li>
 *   <li>{@code comPrevistos} — o realizado mais tudo que ja esta agendado.
 *       Responde "vai sobrar?".</li>
 * </ul>
 *
 * <p><b>O sinal vem do tipo</b>, nunca do valor gravado (F1): a soma converte
 * {@code SAIDA} em negativo na hora de somar, e por isso estes dois campos
 * podem ser negativos enquanto {@code lancamento.valor} nunca e.</p>
 *
 * <p><b>Limite conhecido:</b> a soma so alcanca os lancamentos que o RLS
 * libera — os dos ambientes a que a pessoa pertence. Numa conta conjunta
 * visivel tambem no ambiente pessoal do outro, cada um ve um total diferente.
 * Registrado como I-23; so passa a doer quando houver convite de usuario
 * (I-08), que ainda nao existe.</p>
 */
public record SaldoDaConta(UUID contaId, BigDecimal realizado, BigDecimal comPrevistos) {

    public static final SaldoDaConta ZERO =
        new SaldoDaConta(null, BigDecimal.ZERO, BigDecimal.ZERO);

    /** Conta sem lancamento nenhum nao volta do GROUP BY — vale zero, nao nulo. */
    public static SaldoDaConta zeradoPara(UUID contaId) {
        return new SaldoDaConta(contaId, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public boolean estaZerado() {
        return realizado.signum() == 0;
    }
}
