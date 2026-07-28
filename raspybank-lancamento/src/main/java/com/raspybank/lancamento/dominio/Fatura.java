package com.raspybank.lancamento.dominio;

import com.raspybank.shared.erro.ConflitoDeEstado;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;

/**
 * Um ciclo do contrato — e ela nao tem coluna de status.
 *
 * <p>F19 e categorica: sem coluna de total, sem coluna de status. So
 * {@code fechada_em}. Tudo o mais e calculado, porque total guardado divergiria
 * da soma dos lancamentos no primeiro estorno (P1/R1).</p>
 *
 * <h3>O estado sao TRES perguntas, nao um enum de cinco valores</h3>
 *
 * <p>B-D58. As tres sao independentes:</p>
 *
 * <ul>
 *   <li>{@link CicloFatura} — ainda recebe lancamento? Deriva de {@code fechadaEm}.</li>
 *   <li>{@link QuitacaoFatura} — quanto ja foi pago? Deriva das somas.</li>
 *   <li>{@link #estaVencida} — fechada, nao quitada, e o vencimento passou.</li>
 * </ul>
 *
 * <p><b>Por que nao um enum so.</b> Uma fatura ABERTA pode estar parcialmente
 * paga: e o caso da antecipacao (B-D57), em que se paga antes do fechamento para
 * liberar limite e caber uma compra. Num enum unico esse caso nao teria nome, ou
 * ganharia um {@code ABERTA_COM_ANTECIPACAO} que existe so para tapar buraco. E
 * o mesmo erro que B-D15 ja custou uma vez.</p>
 *
 * <h3>Fechar e reversivel — revisao de F21</h3>
 *
 * <p>F21 dizia que fatura fechada e imutavel. B-D50 revogou, com o motivo dado
 * pelo dono do projeto: <i>"e mais para se ele fechar a fatura sem querer poder
 * abrir"</i>. Cada banco tem sua regra de fechamento, e um clique errado nao pode
 * ser definitivo.</p>
 */
@Entity
@Table(name = "fatura")
public class Fatura {

    @Id
    @GeneratedValue
    @Column(name = "id", insertable = false, updatable = false)
    private UUID id;

    @Column(name = "cartao_id", nullable = false, updatable = false)
    private UUID cartaoId;

    /** Sempre o dia 1 do mes de referencia. */
    @Column(name = "mes_referencia", nullable = false, updatable = false)
    private LocalDate mesReferencia;

    @Column(name = "vencimento", nullable = false)
    private LocalDate vencimento;

    /**
     * Guardado, e nao derivado do cartao a cada leitura.
     *
     * <p>{@code diasParaFechamento} pode mudar depois, e o ciclo que ja correu
     * nao pode mudar junto — senao uma compra migraria de fatura sozinha, meses
     * depois, sem ninguem ter tocado nela.</p>
     */
    @Column(name = "fechamento_previsto", nullable = false)
    private LocalDate fechamentoPrevisto;

    /** O UNICO campo de estado (F19). Nulo = aberta. */
    @Column(name = "fechada_em")
    private OffsetDateTime fechadaEm;

    @Column(name = "criado_em", insertable = false, updatable = false)
    private OffsetDateTime criadoEm;

    protected Fatura() {
    }

    public Fatura(UUID cartaoId, YearMonth mes, LocalDate vencimento,
                  LocalDate fechamentoPrevisto) {

        this.cartaoId = Objects.requireNonNull(cartaoId, "cartaoId e obrigatorio");
        this.mesReferencia = Objects.requireNonNull(mes, "mes e obrigatorio").atDay(1);
        this.vencimento = Objects.requireNonNull(vencimento, "vencimento e obrigatorio");
        this.fechamentoPrevisto =
            Objects.requireNonNull(fechamentoPrevisto, "fechamentoPrevisto e obrigatorio");
    }

    // =========================================================================
    // As tres perguntas (B-D58)
    // =========================================================================

    public CicloFatura ciclo() {
        return fechadaEm == null ? CicloFatura.ABERTA : CicloFatura.FECHADA;
    }

    /**
     * Quanto desta fatura ja foi pago, dados os dois numeros.
     *
     * <p>Os numeros entram como parametro em vez de virem de uma coluna: F19
     * proibe total guardado, e o pago e soma de lancamentos. A entidade decide a
     * regra, quem chama traz os fatos.</p>
     *
     * <p>{@code >= } e nao {@code == } no caso quitado porque pagamento a maior
     * existe — e nesse caso a fatura esta quitada, com credito sobrando.</p>
     */
    public QuitacaoFatura quitacao(BigDecimal total, BigDecimal pago) {
        if (pago.signum() <= 0) {
            return QuitacaoFatura.NADA_PAGO;
        }
        return pago.compareTo(total) >= 0 ? QuitacaoFatura.QUITADA : QuitacaoFatura.PARCIAL;
    }

    /**
     * Fechada, com o que pagar, nao quitada, e o vencimento ja passou.
     *
     * <p><b>Fatura VAZIA nunca esta vencida</b>, e esta condicao custou um teste
     * para aparecer: um cartao recem-criado tem faturas de ciclos que ja
     * passaram, todas fechadas e com total zero, e sem a checagem de
     * {@code total > 0} todas nasciam gritando "vencida". Nao ha divida atrasada
     * onde nao houve compra.</p>
     *
     * <p><b>Fatura ABERTA tambem nunca esta vencida</b>, mesmo com o vencimento
     * no passado — isso seria um cartao mal configurado, nao uma divida
     * atrasada, e tratar as duas coisas igual esconderia a primeira.</p>
     */
    public boolean estaVencida(BigDecimal total, BigDecimal pago, LocalDate hoje) {
        return ciclo() == CicloFatura.FECHADA
            && total.signum() > 0
            && quitacao(total, pago) != QuitacaoFatura.QUITADA
            && vencimento.isBefore(hoje);
    }

    // =========================================================================
    // Fechar e reabrir (B-D50)
    // =========================================================================

    public void fechar(OffsetDateTime quando) {
        if (fechadaEm != null) {
            throw new ConflitoDeEstado("Esta fatura ja esta fechada");
        }
        this.fechadaEm = quando;
    }

    public void reabrir() {
        if (fechadaEm == null) {
            throw new ConflitoDeEstado("Esta fatura nao esta fechada");
        }
        this.fechadaEm = null;
    }

    public boolean estaAberta() {
        return fechadaEm == null;
    }

    /**
     * Ja passou do fechamento previsto e ninguem fechou.
     *
     * <p>Nao fecha sozinha: quem fecha e o servico, na leitura, e este metodo e
     * so a pergunta. Separar os dois deixa a regra testavel sem banco.</p>
     */
    public boolean deveriaEstarFechada(LocalDate hoje) {
        return estaAberta() && !hoje.isBefore(fechamentoPrevisto);
    }

    /** Ajusta o ciclo de uma fatura FUTURA quando o cartao muda de vencimento. */
    public void reagendar(LocalDate vencimento, LocalDate fechamentoPrevisto) {
        this.vencimento = Objects.requireNonNull(vencimento);
        this.fechamentoPrevisto = Objects.requireNonNull(fechamentoPrevisto);
    }

    public UUID getId()                       { return id; }
    public UUID getCartaoId()                 { return cartaoId; }
    public YearMonth getMes()                 { return YearMonth.from(mesReferencia); }
    public LocalDate getMesReferencia()       { return mesReferencia; }
    public LocalDate getVencimento()          { return vencimento; }
    public LocalDate getFechamentoPrevisto()  { return fechamentoPrevisto; }
    public OffsetDateTime getFechadaEm()      { return fechadaEm; }
    public OffsetDateTime getCriadoEm()       { return criadoEm; }
}
