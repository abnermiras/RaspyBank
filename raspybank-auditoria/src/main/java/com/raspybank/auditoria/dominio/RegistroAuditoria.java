package com.raspybank.auditoria.dominio;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Registro de auditoria de escrita. Modulo M9.
 *
 * <p>Note que ambienteId e usuarioId sao UUID, nao referencias as entidades
 * Ambiente e Usuario. Isso e proposital: contextos se referenciam por
 * identificador, nunca por objeto. E o que mantem este modulo independente e
 * extraivel — e o que faz o teste de arquitetura passar.</p>
 */
@Entity
@Table(name = "registro_auditoria")
public class RegistroAuditoria {

    @Id
    @GeneratedValue
    @Column(name = "id", insertable = false, updatable = false)
    private UUID id;

    /** Nulo para acoes fora de ambiente: login, cadastro, recuperacao de senha. */
    @Column(name = "ambiente_id")
    private UUID ambienteId;

    /**
     * Nulo quando o autor nao e identificavel — a V8 derrubou o NOT NULL da
     * coluna justamente para a trilha aceitar eventos de sistema. A entidade
     * acompanha o schema (I-17a): declarar nullable=false aqui faria o
     * Hibernate recusar o que o banco aceita.
     */
    @Column(name = "usuario_id")
    private UUID usuarioId;

    /** WEB, TELEGRAM ou SISTEMA (maiusculas desde a V8). E a razao de existir desta tabela. */
    @Column(name = "canal", nullable = false)
    private String canal;

    @Column(name = "entidade", nullable = false)
    private String entidade;

    @Column(name = "entidade_id")
    private UUID entidadeId;

    @Column(name = "operacao", nullable = false)
    private String operacao;

    /**
     * jsonb do Postgres mapeado como String.
     * A anotacao JdbcTypeCode informa ao Hibernate o tipo real da coluna;
     * sem ela, ele tentaria gravar como texto comum e o banco recusaria.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "estado_anterior")
    private String estadoAnterior;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "estado_novo")
    private String estadoNovo;

    @Column(name = "ocorrido_em", nullable = false)
    private OffsetDateTime ocorridoEm = OffsetDateTime.now();

    protected RegistroAuditoria() {
    }

    public RegistroAuditoria(UUID ambienteId, UUID usuarioId, String canal,
                             String entidade, UUID entidadeId, String operacao,
                             String estadoAnterior, String estadoNovo) {
        this.ambienteId = ambienteId;
        this.usuarioId = usuarioId;
        this.canal = canal;
        this.entidade = entidade;
        this.entidadeId = entidadeId;
        this.operacao = operacao;
        this.estadoAnterior = estadoAnterior;
        this.estadoNovo = estadoNovo;
    }

    public UUID getId()                  { return id; }
    public UUID getAmbienteId()          { return ambienteId; }
    public UUID getUsuarioId()           { return usuarioId; }
    public String getCanal()             { return canal; }
    public String getEntidade()          { return entidade; }
    public UUID getEntidadeId()          { return entidadeId; }
    public String getOperacao()          { return operacao; }
    public String getEstadoAnterior()    { return estadoAnterior; }
    public String getEstadoNovo()        { return estadoNovo; }
    public OffsetDateTime getOcorridoEm(){ return ocorridoEm; }
}
