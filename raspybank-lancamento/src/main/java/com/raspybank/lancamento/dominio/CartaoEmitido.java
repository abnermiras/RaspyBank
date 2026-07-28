package com.raspybank.lancamento.dominio;

import com.raspybank.shared.erro.OperacaoNaoPermitida;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Cada plastico ou virtual debaixo de um contrato (F18).
 *
 * <p>O adicional da Luciana, o virtual da assinatura, o fisico que voce carrega:
 * tres linhas desta tabela, um {@link Cartao} so, uma fatura so.</p>
 *
 * <h3>O titular e TEXTO, e isso e temporario por decisao</h3>
 *
 * <p>B-D53: o Abner quer registrar o adicional da Luciana <b>agora</b>, e os
 * gastos dela ja sao gasto da casa. Mas convidar usuario e o I-08, que nao
 * existe. O texto permite registrar hoje; quando o convite chegar, preenche-se
 * {@code usuarioId} e nada mais muda — nenhuma migracao, nenhum dado perdido.</p>
 *
 * <p>F22 continua valendo: {@code responsavelId} no lancamento e dimensao de
 * ANALISE, nao de acesso. Registrar que o gasto foi no cartao da Luciana nao da
 * a ela acesso a nada.</p>
 *
 * <h3>O numero completo nao e guardado</h3>
 *
 * <p>So os quatro ultimos digitos, e e decisao: numero completo de cartao e dado
 * que este sistema nao tem motivo para ter, e guardar o que nao se precisa e
 * criar responsabilidade de graca.</p>
 */
@Entity
@Table(name = "cartao_emitido")
public class CartaoEmitido {

    @Id
    @GeneratedValue
    @Column(name = "id", insertable = false, updatable = false)
    private UUID id;

    @Column(name = "cartao_id", nullable = false, updatable = false)
    private UUID cartaoId;

    @Column(name = "nome_titular", nullable = false)
    private String nomeTitular;

    /** Nulo ate o convite (I-08) existir. */
    @Column(name = "usuario_id")
    private UUID usuarioId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false)
    private TipoCartaoEmitido tipo;

    @Column(name = "final_do_cartao", nullable = false)
    private String finalDoCartao;

    /**
     * Nulo = usa o limite do contrato, que e o caso comum. Preenchido =
     * sublimite deste cartao dentro do global.
     *
     * <p>Como o limite nao trava nada (B-D48), este numero tambem e
     * informativo — serve para a tela mostrar quanto daquele adicional foi
     * consumido, nao para recusar compra.</p>
     */
    @Column(name = "limite_proprio")
    private BigDecimal limiteProprio;

    @Column(name = "cancelado_em")
    private OffsetDateTime canceladoEm;

    @Column(name = "criado_em", insertable = false, updatable = false)
    private OffsetDateTime criadoEm;

    protected CartaoEmitido() {
    }

    public CartaoEmitido(UUID cartaoId, String nomeTitular, TipoCartaoEmitido tipo,
                         String finalDoCartao, BigDecimal limiteProprio) {

        this.cartaoId = Objects.requireNonNull(cartaoId, "cartaoId e obrigatorio");
        this.nomeTitular = exigirNome(nomeTitular);
        this.tipo = Objects.requireNonNull(tipo, "tipo e obrigatorio: FISICO ou VIRTUAL");
        this.finalDoCartao = exigirQuatroDigitos(finalDoCartao);
        this.limiteProprio = exigirLimiteOpcional(limiteProprio);
    }

    /** Vincula a um usuario de verdade. Sera usado quando o convite (I-08) existir. */
    public void vincularA(UUID usuarioId) {
        this.usuarioId = usuarioId;
    }

    public void renomearTitular(String nome)  { this.nomeTitular = exigirNome(nome); }

    public void alterarLimiteProprio(BigDecimal limite) {
        this.limiteProprio = exigirLimiteOpcional(limite);
    }

    /**
     * Cancela o cartao. Nao apaga: o historico de gastos dele continua inteiro.
     *
     * <p>Mesma logica de F7 para conta e B-D4 para categoria — apagar apagaria o
     * passado. Um virtual descartado depois de uma compra precisa continuar
     * explicando aquela compra.</p>
     */
    public void cancelar(OffsetDateTime quando) { this.canceladoEm = quando; }
    public void reativar()                      { this.canceladoEm = null; }
    public boolean estaCancelado()              { return canceladoEm != null; }

    // =========================================================================

    private static String exigirNome(String nome) {
        if (nome == null || nome.isBlank()) {
            throw new OperacaoNaoPermitida("Nome do titular e obrigatorio");
        }
        return nome.trim();
    }

    /**
     * Exatamente quatro digitos.
     *
     * <p>Espelha {@code ck_cartao_emitido_final}. A mensagem existe porque a
     * constraint diria apenas que uma restricao falhou, e o caso comum e a
     * pessoa colar o numero inteiro sem perceber — que e justamente o dado que
     * este sistema nao quer receber.</p>
     */
    private static String exigirQuatroDigitos(String valor) {
        if (valor == null || !valor.matches("\\d{4}")) {
            throw new OperacaoNaoPermitida(
                "Informe apenas os QUATRO ultimos digitos do cartao — o numero"
                    + " completo nao e guardado por este sistema");
        }
        return valor;
    }

    private static BigDecimal exigirLimiteOpcional(BigDecimal limite) {
        if (limite == null) {
            return null;
        }
        if (limite.signum() <= 0) {
            throw new OperacaoNaoPermitida("Limite proprio deve ser positivo: " + limite);
        }
        if (limite.stripTrailingZeros().scale() > 2) {
            throw new OperacaoNaoPermitida(
                "Limite com mais de duas casas decimais seria arredondado em silencio: " + limite);
        }
        return limite;
    }

    public UUID getId()                     { return id; }
    public UUID getCartaoId()               { return cartaoId; }
    public String getNomeTitular()          { return nomeTitular; }
    public UUID getUsuarioId()              { return usuarioId; }
    public TipoCartaoEmitido getTipo()      { return tipo; }
    public String getFinalDoCartao()        { return finalDoCartao; }
    public BigDecimal getLimiteProprio()    { return limiteProprio; }
    public OffsetDateTime getCanceladoEm()  { return canceladoEm; }
    public OffsetDateTime getCriadoEm()     { return criadoEm; }
}
