package com.raspybank.lancamento.dominio;

import com.raspybank.shared.erro.OperacaoNaoPermitida;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;

/**
 * O CONTRATO de credito com o banco (F17). Nao e o plastico.
 *
 * <p>"Black" e "Diamond" no mesmo Nubank sao dois cartoes desta tabela, cada um
 * com limite proprio. Os plasticos e virtuais que vivem debaixo de cada um sao
 * {@link CartaoEmitido}, e todos consomem o limite do contrato.</p>
 *
 * <h3>Isto tambem e uma Conta</h3>
 *
 * <p>{@code contaId} e chave primaria E estrangeira (F5/B-D47). Nao e elegancia:
 * a divida do cartao e saldo, e saldo e soma de lancamentos (P1). Uma coluna
 * "valor devido" faria o mesmo numero existir em dois lugares — exatamente o que
 * R1 existe para impedir.</p>
 *
 * <p>De graca vem: o cartao aparece na T-05 com a divida a vista, o patrimonio
 * (F6) ja o subtrai porque a natureza e {@code PASSIVO}, e pagar a fatura reusa
 * a maquina da transferencia sem uma linha de codigo nova.</p>
 *
 * <h3>O limite nao trava nada</h3>
 *
 * <p>B-D48, palavras do dono do projeto: <i>"o limite do cartao e meramente
 * informativo e o sistema nao precisa travar, apenas mostrar o que foi
 * consumido"</i>. Nenhuma compra e recusada por estoura-lo. O numero existe para
 * bater com o app do banco — e o banco de verdade e quem recusa.</p>
 */
@Entity
@Table(name = "cartao")
public class Cartao {

    @Id
    @Column(name = "conta_id")
    private UUID contaId;

    /** A conta do banco. O servico recusa que seja fisica (B-D45, via B-D41). */
    @Column(name = "conta_banco_id", nullable = false)
    private UUID contaBancoId;

    @Column(name = "nome", nullable = false)
    private String nome;

    @Column(name = "limite", nullable = false)
    private BigDecimal limite;

    @Column(name = "dia_vencimento", nullable = false)
    private short diaVencimento;

    @Column(name = "dias_para_fechamento", nullable = false)
    private short diasParaFechamento;

    @Column(name = "encerrado_em")
    private OffsetDateTime encerradoEm;

    @Column(name = "criado_em", insertable = false, updatable = false)
    private OffsetDateTime criadoEm;

    protected Cartao() {
    }

    public Cartao(UUID contaId, UUID contaBancoId, String nome, BigDecimal limite,
                  int diaVencimento, int diasParaFechamento) {

        this.contaId = Objects.requireNonNull(contaId, "contaId e obrigatorio");
        this.contaBancoId = Objects.requireNonNull(contaBancoId, "contaBancoId e obrigatorio");

        if (contaId.equals(contaBancoId)) {
            throw new OperacaoNaoPermitida("O cartao nao pode ser o proprio banco");
        }

        this.nome = Objects.requireNonNull(nome, "nome e obrigatorio");
        this.limite = exigirLimite(limite);
        this.diaVencimento = (short) exigirDiaDoMes(diaVencimento);
        this.diasParaFechamento = (short) exigirDiasParaFechamento(diasParaFechamento);
    }

    // =========================================================================
    // A regra do fechamento
    // =========================================================================

    /**
     * Quando esta fatura fecha: vencimento menos os dias, recuando para a sexta
     * se cair em fim de semana.
     *
     * <p>Regra pedida em 28/07/2026 (B-D49). O recuo para a <b>sexta anterior</b>
     * — e nao para a segunda seguinte — nao e detalhe: adiar o fechamento deixaria
     * a compra de sabado entrar numa fatura que vence em cinco dias, e a pessoa
     * pagaria no mes errado.</p>
     *
     * <p>Metodo estatico e puro, com {@code vencimento} como parametro (padrao
     * B-C3): a regra e testavel em qualquer dia, inclusive na virada do ano.</p>
     */
    public static LocalDate fechamentoDe(LocalDate vencimento, int diasParaFechamento) {
        LocalDate fechamento = vencimento.minusDays(diasParaFechamento);

        if (fechamento.getDayOfWeek() == DayOfWeek.SATURDAY) {
            return fechamento.minusDays(1);
        }
        if (fechamento.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return fechamento.minusDays(2);
        }
        return fechamento;
    }

    /**
     * O vencimento no mes pedido, respeitando meses curtos.
     *
     * <p>Vencimento dia 31 em fevereiro vira dia 28 (ou 29). Sem este recorte, a
     * geracao das faturas quebraria em quatro meses do ano — e quebraria com uma
     * excecao de data, que e o pior lugar para descobrir a regra.</p>
     */
    public LocalDate vencimentoEm(YearMonth mes) {
        return mes.atDay(Math.min(diaVencimento, mes.lengthOfMonth()));
    }

    /** O fechamento previsto da fatura daquele mes. */
    public LocalDate fechamentoEm(YearMonth mes) {
        return fechamentoDe(vencimentoEm(mes), diasParaFechamento);
    }

    // =========================================================================

    public void renomear(String novoNome) {
        this.nome = Objects.requireNonNull(novoNome, "nome e obrigatorio");
    }

    public void alterarLimite(BigDecimal novoLimite) {
        this.limite = exigirLimite(novoLimite);
    }

    /**
     * Muda o vencimento e o intervalo de fechamento.
     *
     * <p>Nao mexe nas faturas ja geradas de proposito: o ciclo que ja correu nao
     * pode mudar depois, senao uma compra migraria de fatura sozinha. Quem chama
     * decide se regera as faturas FUTURAS.</p>
     */
    public void reagendarCiclo(int diaVencimento, int diasParaFechamento) {
        this.diaVencimento = (short) exigirDiaDoMes(diaVencimento);
        this.diasParaFechamento = (short) exigirDiasParaFechamento(diasParaFechamento);
    }

    public void encerrar(OffsetDateTime quando) { this.encerradoEm = quando; }
    public void reabrir()                       { this.encerradoEm = null; }
    public boolean estaEncerrado()              { return encerradoEm != null; }

    // =========================================================================

    private static BigDecimal exigirLimite(BigDecimal limite) {
        Objects.requireNonNull(limite, "limite e obrigatorio");
        if (limite.signum() <= 0) {
            throw new OperacaoNaoPermitida("Limite deve ser positivo: " + limite);
        }
        if (limite.stripTrailingZeros().scale() > 2) {
            throw new OperacaoNaoPermitida(
                "Limite com mais de duas casas decimais seria arredondado em silencio: " + limite);
        }
        return limite;
    }

    private static int exigirDiaDoMes(int dia) {
        if (dia < 1 || dia > 31) {
            throw new OperacaoNaoPermitida("Dia de vencimento deve estar entre 1 e 31: " + dia);
        }
        return dia;
    }

    private static int exigirDiasParaFechamento(int dias) {
        if (dias < 0 || dias > 28) {
            throw new OperacaoNaoPermitida(
                "Dias para fechamento deve estar entre 0 e 28 — o fechamento precisa"
                    + " caber no mes anterior ao vencimento, inclusive em fevereiro: " + dias);
        }
        return dias;
    }

    public UUID getContaId()                 { return contaId; }
    public UUID getContaBancoId()            { return contaBancoId; }
    public String getNome()                  { return nome; }
    public BigDecimal getLimite()            { return limite; }
    public int getDiaVencimento()            { return diaVencimento; }
    public int getDiasParaFechamento()       { return diasParaFechamento; }
    public OffsetDateTime getEncerradoEm()   { return encerradoEm; }
    public OffsetDateTime getCriadoEm()      { return criadoEm; }
}
