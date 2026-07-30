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
 *
 * <h3>O que a V16 acrescentou, e o segundo furo que ela fechou</h3>
 *
 * <p>{@code origem} responde onde a conta NASCEU (B-D92). Sem ela, "conta
 * propria" passava a incluir a conta emprestada: depois do compartilhamento a
 * conta do dono esta ligada ao ambiente de quem recebeu, que e dono do proprio
 * ambiente — e {@code app_contas_proprias()} devolvia a conta dele para ela.
 * O estrago era o mesmo furo de cima pelo avesso: ela podia <b>desvincular a
 * conta do ambiente de quem a criou</b>, e a conta desaparecia para ele.</p>
 *
 * <p>{@code encerradoEm} e a revogacao (B-D93), e ela e logica por dois motivos
 * somados. O primeiro e estrutural: {@code fk_lancamento_conta} e
 * {@code ON DELETE RESTRICT}, entao a partir do primeiro lancamento dela o
 * {@code DELETE} do vinculo e recusado. O segundo e mais importante: aquele
 * dinheiro saiu da conta de verdade, e apagar o lancamento faria o saldo do
 * dono divergir do extrato do banco.</p>
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

    /**
     * O ambiente onde a conta nasceu (B-D92). Somente leitura por aqui: a linha
     * de origem nasce em {@code app_criar_conta}, a porta estreita, e
     * {@code pol_ca_vincular} recusa qualquer INSERT que a declare verdadeira.
     * Vinculo criado pela aplicacao e sempre emprestado.
     */
    @Column(name = "origem", insertable = false, updatable = false)
    private boolean origem;

    /** Revogacao logica (B-D93). Escrevivel: e o unico campo que muda aqui. */
    @Column(name = "encerrado_em")
    private OffsetDateTime encerradoEm;

    @Column(name = "criado_em", insertable = false, updatable = false)
    private OffsetDateTime criadoEm;

    protected ContaAmbiente() {
    }

    public ContaAmbiente(UUID contaId, UUID ambienteId) {
        this.contaId = contaId;
        this.ambienteId = ambienteId;
    }

    public UUID getContaId()               { return contaId; }
    public UUID getAmbienteId()            { return ambienteId; }
    public boolean isOrigem()              { return origem; }
    public OffsetDateTime getEncerradoEm() { return encerradoEm; }
    public boolean estaAtivo()             { return encerradoEm == null; }
    public OffsetDateTime getCriadoEm()    { return criadoEm; }

    /**
     * Devolve a conta emprestada. Quem sai e ela mesma — o dono revoga por
     * {@code app_revogar_conta_compartilhada}, porque a linha dela esta num
     * ambiente que ele nao pode ver (B-D90).
     */
    public void encerrar(OffsetDateTime quando) {
        if (origem) {
            throw new IllegalStateException(
                "Vinculo de origem nao se encerra: seria a conta desaparecendo para quem a criou");
        }
        this.encerradoEm = quando;
    }

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
