package com.raspybank.identidade.dominio;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Token de renovacao, com rotacao e deteccao de reuso.
 *
 * <p>O token de acesso dura 15 minutos e nao e guardado: ele e assinado, e a
 * assinatura prova sua validade. Este aqui dura 30 dias, entao precisa poder
 * ser revogado — e o que so e possivel se houver registro.</p>
 *
 * <p>Guardamos o HASH do token, nunca o token. Mesma logica da senha: se o
 * banco vazar, os tokens nao sao utilizaveis.</p>
 */
@Entity
@Table(name = "token_renovacao")
public class TokenRenovacao {

    @Id
    @GeneratedValue
    @Column(name = "id", insertable = false, updatable = false)
    private UUID id;

    @Column(name = "usuario_id", nullable = false)
    private UUID usuarioId;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    /** Identifica a cadeia de rotacoes originada de um mesmo login. */
    @Column(name = "familia_id", nullable = false)
    private UUID familiaId;

    @Column(name = "criado_em", insertable = false, updatable = false)
    private OffsetDateTime criadoEm;

    @Column(name = "expira_em", nullable = false)
    private OffsetDateTime expiraEm;

    /** Teto absoluto herdado da familia: apos ele, a senha e exigida de novo. */
    @Column(name = "teto_em", nullable = false)
    private OffsetDateTime tetoEm;

    @Column(name = "usado_em")
    private OffsetDateTime usadoEm;

    @Column(name = "revogado_em")
    private OffsetDateTime revogadoEm;

    @Column(name = "agente_usuario")
    private String agenteUsuario;

    protected TokenRenovacao() {
    }

    public TokenRenovacao(UUID usuarioId, String tokenHash, UUID familiaId,
                          OffsetDateTime expiraEm, OffsetDateTime tetoEm,
                          String agenteUsuario) {
        this.usuarioId = usuarioId;
        this.tokenHash = tokenHash;
        this.familiaId = familiaId;
        this.expiraEm = expiraEm;
        this.tetoEm = tetoEm;
        this.agenteUsuario = agenteUsuario;
    }

    /**
     * Verifica se o token pode ser usado agora.
     *
     * <p>Sao quatro condicoes independentes, e todas precisam valer. Note que
     * "ja usado" e uma delas: apos a rotacao, o token antigo morre.</p>
     */
    public boolean estaValido(OffsetDateTime agora) {
        return usadoEm == null
            && revogadoEm == null
            && agora.isBefore(expiraEm)
            && agora.isBefore(tetoEm);
    }

    public void marcarUsado() {
        this.usadoEm = OffsetDateTime.now();
    }

    public void revogar() {
        if (this.revogadoEm == null) {
            this.revogadoEm = OffsetDateTime.now();
        }
    }

    public boolean jaFoiUsado() {
        return usadoEm != null;
    }

    public UUID getId()                 { return id; }
    public UUID getUsuarioId()          { return usuarioId; }
    public String getTokenHash()        { return tokenHash; }
    public UUID getFamiliaId()          { return familiaId; }
    public OffsetDateTime getExpiraEm() { return expiraEm; }
    public OffsetDateTime getTetoEm()   { return tetoEm; }
    public OffsetDateTime getUsadoEm()  { return usadoEm; }
}
