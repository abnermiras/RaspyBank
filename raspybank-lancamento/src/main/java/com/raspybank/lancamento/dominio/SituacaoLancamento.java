package com.raspybank.lancamento.dominio;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Se o dinheiro ja se moveu ou ainda vai se mover.
 *
 * <p>Valores conferidos contra o CHECK real {@code ck_lancamento_situacao}
 * (V10, 26/07/2026): PREVISTO, REALIZADO. Exatamente estes.</p>
 *
 * <h3>A regra de derivacao (B-D9), e por que ela vive aqui</h3>
 *
 * <p>F15 dizia que todo lancamento fora de cartao nascia PREVISTO. Escrita
 * antes de existir tela, ao pe da letra ela fazia a pessoa cadastrar dez
 * gastos ja pagos e confirmar os dez, um a um, so para a tela central sair
 * do zero. B-D9 emendou: <b>a situacao deixa de ser pergunta e vira
 * consequencia da data que a pessoa digitou.</b> O formulario da T-08 nao
 * tem campo de situacao.</p>
 *
 * <p><b>Por que nao e um gatilho no banco.</b> Deliberado, e esta escrito no
 * proprio SQL da V10: o PUT permite corrigir a situacao explicitamente, e
 * uma regra que o banco impoe e uma regra que o usuario nao consegue
 * contrariar quando tem razao. O banco garante o que nao pode variar (o
 * CHECK); a derivacao, que e um padrao e nao uma lei, fica aqui.</p>
 *
 * <p><b>Por que e uma classe pura.</b> Padrao B-C3: sem Spring, sem banco,
 * sem relogio implicito — {@code hoje} entra como parametro. Um teste da
 * virada do ano roda em milissegundos e nao depende do dia em que o build
 * foi executado.</p>
 */
public enum SituacaoLancamento {

    /** Agendado para frente: a data de caixa ainda nao chegou. */
    PREVISTO,

    /** Ja aconteceu: a data de caixa e hoje ou passado. */
    REALIZADO;

    /**
     * Deriva a situacao a partir da data de caixa (B-D9).
     *
     * <p>Data no passado ou hoje resulta REALIZADO; data no futuro resulta
     * PREVISTO. O limite fica em HOJE e nao em ontem porque "paguei o
     * mercado agora" e o caso mais comum do formulario — obrigar a confirmar
     * o que acabou de acontecer seria exatamente o atrito que B-D9 removeu.</p>
     *
     * @param dataCaixa quando o dinheiro sai ou entra do bolso (regime de
     *                  caixa, P-T2). Nunca nula.
     * @param hoje      a data de referencia, injetada e nao consultada, para
     *                  que a regra seja testavel em qualquer dia.
     */
    public static SituacaoLancamento derivarDe(LocalDate dataCaixa, LocalDate hoje) {
        Objects.requireNonNull(dataCaixa, "dataCaixa e obrigatoria");
        Objects.requireNonNull(hoje, "hoje e obrigatoria");
        return dataCaixa.isAfter(hoje) ? PREVISTO : REALIZADO;
    }
}
