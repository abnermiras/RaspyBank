package com.raspybank.lancamento.dominio;

/**
 * Quanto desta fatura ja foi pago.
 *
 * <p>Segunda das tres perguntas de B-D58, e independente do {@link CicloFatura}:
 * fatura aberta pode estar parcialmente paga, e fatura fechada pode estar
 * quitada. Cruzar as duas num enum so faria um dos casos ficar sem nome.</p>
 *
 * <p>Nao existe coluna para isto (F19): deriva da soma dos pagamentos contra o
 * total da fatura, que por sua vez e soma de lancamentos (P1).</p>
 */
public enum QuitacaoFatura {

    NADA_PAGO,

    /** Pagamento parcial. E o estado da antecipacao para liberar limite (B-D57). */
    PARCIAL,

    /** Pago o total ou mais. "Ou mais" acontece: pagamento a maior existe. */
    QUITADA
}
