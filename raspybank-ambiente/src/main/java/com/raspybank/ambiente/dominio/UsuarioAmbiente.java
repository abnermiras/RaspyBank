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
