package com.raspybank.lancamento.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Convite para receber uma conta compartilhada (§4k, B-D90/B-D94).
 *
 * <h3>Por que existe um convite aqui e nao existe no ambiente</h3>
 *
 * <p>Compartilhar ambiente e imediato, sem aceite (B-D80): o ambiente aparece
 * na lista da pessoa na hora e ela sai com um clique. Compartilhar conta tem
 * aceite por um motivo que nao e cerimonia — <b>ela escolhe em qual ambiente
 * dela a conta vai aparecer</b>, e ninguem pode adivinhar essa escolha. Cair no
 * ambiente ativo mandaria a conta domestica para o PJ sem aviso, e os gastos
 * iriam para o mapa errado ate alguem notar; notar e dificil, porque nada
 * avisa.</p>
 *
 * <h3>A linha SOME quando o convite se resolve</h3>
 *
 * <p>Nao existe coluna de situacao, e a ausencia e a decisao (B-D94). A verdade
 * sobre quem tem acesso e o vinculo em {@link ContaAmbiente}; uma situacao
 * {@code ACEITO} aqui seria uma segunda fonte para o mesmo fato, que e o
 * defeito que o I-01 ja custou uma vez neste projeto.</p>
 *
 * <p>Aceitar cria o vinculo e apaga o convite; recusar so apaga. A trilha que
 * uma situacao permanente daria — quem convidou, quem recusou, quando — fica em
 * {@code registro_auditoria}, pelo gatilho {@code tg_auditar_conta_convite}.</p>
 *
 * <h3>O que NAO esta aqui</h3>
 *
 * <p>Nao ha {@code ambienteId} de destino: quem escolhe e ela, no aceite. E nao
 * ha ambiente de origem nem autor: os dois derivam do vinculo de origem da
 * conta, e guardar o mesmo fato duas vezes e o que o I-01 corrigiu.</p>
 */
@Entity
@Table(name = "conta_convite")
public class ContaConvite {

    @Id
    @GeneratedValue
    @Column(name = "id", insertable = false, updatable = false)
    private UUID id;

    @Column(name = "conta_id", nullable = false, updatable = false)
    private UUID contaId;

    @Column(name = "convidado_id", nullable = false, updatable = false)
    private UUID convidadoId;

    /**
     * Nulo = convite de CONTA; preenchido = convite de PLASTICO (B-D106).
     *
     * <p>Uma coluna anulavel em vez de uma tabela nova: o convite ja carrega
     * conta, convidado e a trilha, e o que muda e UM detalhe do que esta sendo
     * oferecido. Tabela separada duplicaria o fluxo inteiro — criar, listar,
     * aceitar, recusar, cancelar — para diferir num campo.</p>
     */
    @Column(name = "cartao_emitido_id", updatable = false)
    private UUID cartaoEmitidoId;

    @Column(name = "criado_em", insertable = false, updatable = false)
    private OffsetDateTime criadoEm;

    protected ContaConvite() {
    }

    /** Convite de conta: a conta inteira, com o saldo e as formas dela. */
    public ContaConvite(UUID contaId, UUID convidadoId) {
        this.contaId = contaId;
        this.convidadoId = convidadoId;
    }

    /**
     * Convite de plastico (B-D106): UM cartao emitido, dentro da fatura de quem
     * abriu o contrato. {@code contaId} e a conta do cartao, e o banco confere
     * que o plastico pertence a ela ({@code fk_convite_plastico_do_cartao}).
     */
    public ContaConvite(UUID contaId, UUID convidadoId, UUID cartaoEmitidoId) {
        this.contaId = contaId;
        this.convidadoId = convidadoId;
        this.cartaoEmitidoId = cartaoEmitidoId;
    }

    public UUID getId()                 { return id; }
    public UUID getContaId()            { return contaId; }
    public UUID getConvidadoId()        { return convidadoId; }
    public UUID getCartaoEmitidoId()    { return cartaoEmitidoId; }
    public boolean ehDePlastico()       { return cartaoEmitidoId != null; }
    public OffsetDateTime getCriadoEm() { return criadoEm; }
}
