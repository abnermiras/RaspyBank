package com.raspybank.lancamento.dominio;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Os dois numeros de uma fatura, calculados na hora.
 *
 * <p>F19 proibe coluna de total, e por bom motivo: um total guardado divergiria
 * da soma dos lancamentos no primeiro estorno, e a divergencia nao daria sinal.
 * Aqui os dois vem de uma agregacao unica.</p>
 *
 * <p><b>Os dois filtram pela conta do cartao</b>, e nao so pelo id da fatura. O
 * pagamento marca a fatura nas DUAS pernas — para aparecer tambem no extrato da
 * conta pagadora, ver B-D59 — entao somar por fatura sem olhar a conta contaria
 * a perna de saida da corrente como se fosse compra no cartao.</p>
 *
 * @param total compras: as saidas lancadas na conta do cartao naquela fatura
 * @param pago  pagamentos: as entradas na conta do cartao naquela fatura
 */
public record TotalDaFatura(UUID faturaId, BigDecimal total, BigDecimal pago) {

    public TotalDaFatura {
        total = total == null ? BigDecimal.ZERO : total;
        pago = pago == null ? BigDecimal.ZERO : pago;
    }

    /** Fatura sem lancamento nenhum nao aparece na agregacao; quem chama completa. */
    public static TotalDaFatura zeradaPara(UUID faturaId) {
        return new TotalDaFatura(faturaId, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public BigDecimal aPagar() {
        BigDecimal restante = total.subtract(pago);
        return restante.signum() < 0 ? BigDecimal.ZERO : restante;
    }
}
