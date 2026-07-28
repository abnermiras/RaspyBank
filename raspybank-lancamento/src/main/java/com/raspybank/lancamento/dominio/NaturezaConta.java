package com.raspybank.lancamento.dominio;

/**
 * Natureza patrimonial da conta (F6).
 *
 * <p>Valores conferidos contra o CHECK real {@code ck_conta_natureza}
 * (V10, 26/07/2026): ATIVO, PASSIVO. Exatamente estes.</p>
 *
 * <p>O patrimonio e a diferenca entre a soma dos ATIVOS e a soma dos
 * PASSIVOS — calculada na hora, nunca guardada (P1). E por isso que esta
 * distincao e uma coluna e nao um detalhe de tela: sem ela, somar as contas
 * daria um numero sem significado.</p>
 */
public enum NaturezaConta {

    /** O dinheiro e seu: conta corrente, poupanca, carteira, investimento. */
    ATIVO,

    /** Voce deve: cartao de credito (V12), emprestimo, financiamento. */
    PASSIVO
}
