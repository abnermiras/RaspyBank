package com.raspybank.lancamento.dominio;

/**
 * Sentido de um lancamento concreto.
 *
 * <p>Valores conferidos contra o CHECK real {@code ck_lancamento_tipo}
 * (V10, 26/07/2026): ENTRADA, SAIDA. Exatamente estes — sem AMBOS, ver
 * {@link TipoCategoria}.</p>
 *
 * <p><b>O sinal do dinheiro mora aqui, nunca no valor.</b> A coluna
 * {@code valor} tem CHECK {@code > 0}: uma saida de 50 reais e
 * {@code SAIDA, 50.00}, jamais {@code -50.00}. Duas representacoes para a
 * mesma saida seria garantir que alguma soma um dia esqueceria de tratar
 * uma delas.</p>
 */
public enum TipoLancamento {

    ENTRADA,
    SAIDA
}
