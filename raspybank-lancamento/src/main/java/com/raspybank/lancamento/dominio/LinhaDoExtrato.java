package com.raspybank.lancamento.dominio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Uma linha do extrato de uma conta, que pode ser de outro ambiente (B-D89).
 *
 * <p>Nao e um {@link Lancamento}, e a diferenca e o ponto: o lancamento alheio
 * chega <b>recortado</b>. {@code descricao}, {@code categoriaId} e
 * {@code categoriaNome} vem nulos quando {@code meu} e falso — e nao porque
 * alguem os filtrou aqui, mas porque {@code app_extrato_da_conta} nao devolve
 * essas colunas do lancamento de outro ambiente (B-D97). O que a aplicacao
 * nunca recebeu, ela nao vaza.</p>
 *
 * <p>O que sobra e o que basta para o saldo bater com o extrato do banco: valor,
 * data, forma de pagamento e <b>quem</b>. O "quem" vem de
 * {@code lancamento.criado_por}, que carimba o autor desde a V10.</p>
 *
 * <p>A descricao fica de fora junto com a categoria pelo mesmo motivo pratico:
 * e texto livre, e e onde as pessoas escrevem o que nao pretendiam dividir —
 * <i>"presente da Luciana"</i> e exatamente o caso.</p>
 *
 * <p>As colunas de parcela existem para o cartao (B-D102): as proximas parcelas
 * sao dinheiro do dono preso no limite dele, e faturas de meses que ainda nao
 * chegaram ja nascem com valor comprometido. Numa conta comum vem nulas.</p>
 */
public record LinhaDoExtrato(
    UUID id,
    boolean meu,
    LocalDate data,
    TipoLancamento tipo,
    SituacaoLancamento situacao,
    BigDecimal valor,
    FormaPagamento formaPagamento,
    String descricao,
    UUID categoriaId,
    String categoriaNome,
    String quemNome,
    UUID faturaId,
    Integer parcelaNumero,
    Integer parcelaTotal
) {

    /** O valor com sinal, para a tela nao repetir a regra de F1 em JavaScript. */
    public BigDecimal valorComSinal() {
        return tipo == TipoLancamento.ENTRADA ? valor : valor.negate();
    }

    public boolean parcelado() {
        return parcelaTotal != null && parcelaTotal > 1;
    }
}
