package com.raspybank.ambiente.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Ambiente Financeiro. Modulo M2.
 *
 * <p>E a fronteira de dados do sistema (principio P4): todo dado financeiro
 * pertence a exatamente um ambiente, e ninguem enxerga dado de ambiente ao
 * qual nao pertence (RF-M2-01, RF-M2-06).</p>
 *
 * <p>A mesma estrutura atende tres cenarios sem alterar regra de negocio
 * nenhuma: uso individual, casal com financas comuns, e varias familias
 * independentes na mesma instalacao.</p>
 */
@Entity
@Table(name = "ambiente")
public class Ambiente {

    @Id
    @GeneratedValue
    @Column(name = "id", insertable = false, updatable = false)
    private UUID id;

    @Column(name = "nome", nullable = false)
    private String nome;

    @Column(name = "criado_em", insertable = false, updatable = false)
    private OffsetDateTime criadoEm;

    @Column(name = "atualizado_em", insertable = false, updatable = false)
    private OffsetDateTime atualizadoEm;

    /**
     * Unico mecanismo de ciclo de vida (B-D5): nulo significa ativo,
     * preenchido significa arquivado, e anular reverte.
     *
     * <p>Ate a V10 existia tambem uma coluna {@code status} (ATIVO/INATIVO)
     * que ninguem nunca gravou. Duas colunas respondendo a mesma pergunta
     * garantem que alguma consulta vai checar so uma — foi a inconsistencia
     * I-01, e a V10 derrubou a coluna. O historico financeiro e preservado
     * nos dois casos: exclusao aqui e sempre logica.</p>
     */
    @Column(name = "excluido_em")
    private OffsetDateTime excluidoEm;

    protected Ambiente() {
    }

    public Ambiente(String nome) {
        this.nome = nome;
    }

    public boolean estaExcluido() {
        return excluidoEm != null;
    }

    public UUID getId()                     { return id; }
    public String getNome()                 { return nome; }
    public OffsetDateTime getCriadoEm()     { return criadoEm; }
    public OffsetDateTime getExcluidoEm()   { return excluidoEm; }

    public void setNome(String nome) { this.nome = nome; }
}
