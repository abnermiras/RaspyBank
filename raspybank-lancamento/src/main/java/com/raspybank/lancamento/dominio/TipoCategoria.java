package com.raspybank.lancamento.dominio;

import java.util.Optional;

/**
 * Sentido do dinheiro que uma categoria aceita (F12).
 *
 * <p>Valores conferidos contra o CHECK real {@code ck_categoria_tipo}
 * (V10, 26/07/2026): ENTRADA, SAIDA, AMBOS. Exatamente estes.</p>
 *
 * <p><b>Por que existe AMBOS aqui e nao existe em {@link TipoLancamento}.</b>
 * Uma categoria e um rotulo que pode servir aos dois sentidos — transferencia
 * e ajuste de saldo sao os casos que obrigaram a criar o valor. Um lancamento
 * concreto, ao contrario, sempre entrou ou sempre saiu. Sao duas perguntas
 * diferentes e por isso sao dois enums, mesmo vivendo no mesmo modulo:
 * juntar os dois obrigaria o codigo a tratar em todo lugar um AMBOS que
 * nunca pode aparecer num lancamento.</p>
 *
 * <p>Contrato P2: {@code name()} == valor do CHECK. Mapeado com
 * {@code @Enumerated(EnumType.STRING)}. NUNCA usar ORDINAL — o banco guarda
 * texto, e o ordinal muda silenciosamente quando alguem reordena o enum.</p>
 */
public enum TipoCategoria {

    /** Dinheiro que chega: salario, reembolso, rendimento. */
    ENTRADA,

    /** Dinheiro que sai: e o caso da grande maioria das categorias. */
    SAIDA,

    /** Serve aos dois sentidos. Hoje, so as tres sistemicas (B-D13). */
    AMBOS;

    /** Diz se uma categoria deste tipo pode classificar um lancamento daquele. */
    public boolean aceita(TipoLancamento tipo) {
        return this == AMBOS || name().equals(tipo.name());
    }

    /**
     * O sentido que esta categoria impoe ao lancamento — vazio se ela aceita
     * os dois.
     *
     * <p>E o que permite ao {@code POST /api/lancamentos} <b>nao ter campo de
     * tipo</b> no caso comum: escolher "Mercado" ja diz que e saida, e
     * perguntar de novo seria pedir a mesma informacao duas vezes. So quando a
     * categoria e {@code AMBOS} — as tres sistemicas — o corpo precisa
     * declarar o sentido, porque ai a categoria realmente nao sabe.</p>
     */
    public Optional<TipoLancamento> sentidoUnico() {
        return this == AMBOS
            ? Optional.empty()
            : Optional.of(TipoLancamento.valueOf(name()));
    }
}
