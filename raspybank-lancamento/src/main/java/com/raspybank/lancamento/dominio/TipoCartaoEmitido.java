package com.raspybank.lancamento.dominio;

/**
 * Plastico ou numero — as duas formas de um cartao existir.
 *
 * <p>Valores conferidos contra o CHECK real {@code ck_cartao_emitido_tipo}
 * (V12, 28/07/2026): exatamente estes dois.</p>
 *
 * <p>A distincao nao muda regra nenhuma de dinheiro: os dois consomem o mesmo
 * limite do contrato e caem na mesma fatura. Ela existe porque a pessoa precisa
 * reconhecer o cartao que tem na mao — e porque virtual se cria e se cancela aos
 * montes, enquanto fisico e um por titular.</p>
 */
public enum TipoCartaoEmitido {

    /** O plastico. Um por titular, normalmente. */
    FISICO,

    /** Numero gerado para uma compra ou uma assinatura. Descartavel por natureza. */
    VIRTUAL
}
