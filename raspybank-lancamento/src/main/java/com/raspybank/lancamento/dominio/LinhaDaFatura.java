package com.raspybank.lancamento.dominio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Uma linha do extrato de uma fatura, que pode ser de outro ambiente (§4l).
 *
 * <p>Irma de {@link LinhaDoExtrato}, e existe separada por uma razao concreta: a
 * fatura mostra o que a conta nao mostra — <b>qual plastico</b> fez a compra, a
 * data de competencia (que na fatura e a data da compra, nao a do caixa) e a
 * parcela. Um record com os dois conjuntos teria metade dos campos nulos em cada
 * uso, e "nulo aqui" viraria uma convencao a decorar.</p>
 *
 * <p>Como na irma, {@code descricao}, {@code categoria} e {@code subcategoria}
 * vem nulos quando {@code meu} e falso — e nao por filtro daqui:
 * {@code app_extrato_da_fatura} nao devolve essas colunas do lancamento alheio
 * (B-D89 via B-D97).</p>
 *
 * <p><b>A parcela NAO e recortada</b> (B-D102), e e a unica coisa que esta linha
 * revela e a da conta nao revelaria. O motivo: as proximas parcelas sao dinheiro
 * do dono do contrato preso no limite dele, e faturas de meses que ainda nao
 * chegaram ja nascem com valor comprometido. O custo foi dito em voz alta —
 * "3/10 de R$ 200" conta que a compra foi de R$ 2.000.</p>
 *
 * <p>O plastico tambem nao e recortado (B-D103): o contrato e do dono, e ele
 * conhece os proprios emitidos.</p>
 */
public record LinhaDaFatura(
    UUID id,
    boolean meu,
    LocalDate dataCaixa,
    LocalDate dataCompetencia,
    TipoLancamento tipo,
    SituacaoLancamento situacao,
    BigDecimal valor,
    FormaPagamento formaPagamento,
    String descricao,
    UUID categoriaId,
    String categoriaNome,
    UUID subcategoriaId,
    String subcategoriaNome,
    String quemNome,
    UUID cartaoEmitidoId,
    String titular,
    TipoCartaoEmitido tipoEmitido,
    String finalDoCartao,
    Short parcelaNumero,
    Short parcelaTotal
) {

    public boolean parcelado() {
        return parcelaTotal != null && parcelaTotal > 1;
    }
}
