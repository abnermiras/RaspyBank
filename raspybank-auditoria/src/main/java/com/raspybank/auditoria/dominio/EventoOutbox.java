package com.raspybank.auditoria.dominio;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Evento gravado na mesma transacao do dado que o originou.
 *
 * <p>Grava-se aqui em vez de publicar direto porque publicar depois do commit
 * abre uma janela: se a publicacao falhar, o banco diz que o fato aconteceu e
 * o resto do sistema nunca fica sabendo. Na mesma transacao, ou os dois
 * entram ou nenhum entra.</p>
 *
 * <p>A entrega e "pelo menos uma vez": se o relay cair entre entregar e marcar
 * como publicado, o evento sai de novo. Todo consumidor precisa ser
 * idempotente.</p>
 */
@Entity
@Table(name = "outbox")
public class EventoOutbox {

    @Id
    @GeneratedValue
    @Column(name = "id", insertable = false, updatable = false)
    private UUID id;

    @Column(name = "ambiente_id")
    private UUID ambienteId;

    /** Nome em linguagem de negocio: LancamentoRegistrado, nao LancamentoInsertEvent. */
    @Column(name = "tipo_evento", nullable = false)
    private String tipoEvento;

    @Column(name = "agregado", nullable = false)
    private String agregado;

    @Column(name = "agregado_id")
    private UUID agregadoId;

    /**
     * Conteudo do evento. Deve ser AUTOCONTIDO: quem consome nao pode precisar
     * consultar a tabela de origem para entender o que aconteceu. E a diferenca
     * entre modulos desacoplados e modulos que so fingem estar desacoplados.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "criado_em", nullable = false)
    private OffsetDateTime criadoEm = OffsetDateTime.now();

    /** Nulo enquanto nao entregue. */
    @Column(name = "publicado_em")
    private OffsetDateTime publicadoEm;

    @Column(name = "tentativas", nullable = false)
    private int tentativas = 0;

    @Column(name = "ultimo_erro")
    private String ultimoErro;

    protected EventoOutbox() {
    }

    public EventoOutbox(UUID ambienteId, String tipoEvento, String agregado,
                        UUID agregadoId, String payload) {
        this.ambienteId = ambienteId;
        this.tipoEvento = tipoEvento;
        this.agregado = agregado;
        this.agregadoId = agregadoId;
        this.payload = payload;
    }

    public void marcarPublicado() {
        this.publicadoEm = OffsetDateTime.now();
        this.ultimoErro = null;
    }

    public void registrarFalha(String erro) {
        this.tentativas++;
        this.ultimoErro = erro;
    }

    public boolean estaPendente() {
        return publicadoEm == null;
    }

    public UUID getId()                   { return id; }
    public UUID getAmbienteId()           { return ambienteId; }
    public String getTipoEvento()         { return tipoEvento; }
    public String getAgregado()           { return agregado; }
    public UUID getAgregadoId()           { return agregadoId; }
    public String getPayload()            { return payload; }
    public OffsetDateTime getCriadoEm()   { return criadoEm; }
    public OffsetDateTime getPublicadoEm(){ return publicadoEm; }
    public int getTentativas()            { return tentativas; }
    public String getUltimoErro()         { return ultimoErro; }
}
