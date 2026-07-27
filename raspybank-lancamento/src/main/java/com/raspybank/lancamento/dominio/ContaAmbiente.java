package com.raspybank.lancamento.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Em quais ambientes uma conta aparece. O vinculo que a revisao R7 exige.
 *
 * <p>Sem esta tabela, {@link Conta} nao teria politica de RLS: o tenant e o
 * USUARIO (A08/R7), e a visibilidade de uma conta se decide por vinculo, nao
 * por campo. E ela tambem e o alvo da chave composta que amarra o lancamento
 * ao ambiente (B-D2).</p>
 *
 * <h3>O furo que esta tabela quase teve</h3>
 *
 * <p>A politica original conferia so um lado do vinculo — o ambiente. Bastava
 * conhecer o UUID de uma conta alheia para vincula-la ao proprio ambiente e
 * ganhar a conta inteira, com historico. UUID e obscuridade, nao controle de
 * acesso. A politica {@code pol_ca_ambiente} passou a exigir os <b>dois</b>
 * lados no {@code WITH CHECK} (B-D18), e {@code DominioRlsTest} guarda o
 * cenario.</p>
 *
 * <p>Vale como aviso permanente: numa tabela de ligacao, conferir um lado
 * parece suficiente e nunca e.</p>
 */
@Entity
@Table(name = "conta_ambiente")
@IdClass(ContaAmbiente.Chave.class)
public class ContaAmbiente {

    @Id
    @Column(name = "conta_id")
    private UUID contaId;

    @Id
    @Column(name = "ambiente_id")
    private UUID ambienteId;

    @Column(name = "criado_em", insertable = false, updatable = false)
    private OffsetDateTime criadoEm;

    protected ContaAmbiente() {
    }

    public ContaAmbiente(UUID contaId, UUID ambienteId) {
        this.contaId = contaId;
        this.ambienteId = ambienteId;
    }

    public UUID getContaId()            { return contaId; }
    public UUID getAmbienteId()         { return ambienteId; }
    public OffsetDateTime getCriadoEm() { return criadoEm; }

    /** Chave composta. Exigida pelo JPA quando a primaria tem mais de uma coluna. */
    public static class Chave implements Serializable {
        private UUID contaId;
        private UUID ambienteId;

        public Chave() {
        }

        public Chave(UUID contaId, UUID ambienteId) {
            this.contaId = contaId;
            this.ambienteId = ambienteId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Chave outra)) return false;
            return Objects.equals(contaId, outra.contaId)
                && Objects.equals(ambienteId, outra.ambienteId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(contaId, ambienteId);
        }
    }
}
