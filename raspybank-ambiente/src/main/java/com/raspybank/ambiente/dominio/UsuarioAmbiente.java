package com.raspybank.ambiente.dominio;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Vinculo entre um usuario e um ambiente.
 *
 * <p>Nao ha coluna de papel ou permissao: RF-M2-04 estabelece acesso total
 * para quem esta dentro. Quem compartilha financas nao tem o que esconder da
 * outra parte, e a separacao de responsabilidade e feita pelo Responsavel,
 * que e dimensao de analise e nao de acesso.</p>
 *
 * <p>A V15 acrescentou {@code dono} — um booleano, e NAO um sistema de papeis
 * (B-D75). Ele responde uma pergunta so, quem abriu a porta, e divide o mundo
 * em dois: mexer no DINHEIRO (todos) e mexer na PORTA — convidar, remover
 * acesso, renomear, apagar — que e so do dono (B-D76). O acesso total de
 * RF-M2-04 continua valendo para tudo que e dinheiro.</p>
 */
@Entity
@Table(name = "usuario_ambiente")
@IdClass(UsuarioAmbiente.Chave.class)
public class UsuarioAmbiente {

    @Id
    @Column(name = "usuario_id")
    private UUID usuarioId;

    @Id
    @Column(name = "ambiente_id")
    private UUID ambienteId;

    /**
     * Quem abriu a porta. Exatamente um por ambiente ({@code ux_ua_um_dono}),
     * e nunca gravado por aqui como verdadeiro: a linha de dono nasce nas
     * portas estreitas do banco; o que a aplicacao insere e sempre convidado.
     */
    @Column(name = "dono", insertable = false, updatable = false)
    private boolean dono;

    @Column(name = "criado_em", insertable = false, updatable = false)
    private OffsetDateTime criadoEm;

    protected UsuarioAmbiente() {
    }

    public UsuarioAmbiente(UUID usuarioId, UUID ambienteId) {
        this.usuarioId = usuarioId;
        this.ambienteId = ambienteId;
    }

    public UUID getUsuarioId()  { return usuarioId; }
    public UUID getAmbienteId() { return ambienteId; }
    public boolean isDono()     { return dono; }

    /** Chave composta. Exigida pelo JPA quando a primaria tem mais de uma coluna. */
    public static class Chave implements Serializable {
        private UUID usuarioId;
        private UUID ambienteId;

        public Chave() {
        }

        public Chave(UUID usuarioId, UUID ambienteId) {
            this.usuarioId = usuarioId;
            this.ambienteId = ambienteId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Chave outra)) return false;
            return Objects.equals(usuarioId, outra.usuarioId)
                && Objects.equals(ambienteId, outra.ambienteId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(usuarioId, ambienteId);
        }
    }
}
