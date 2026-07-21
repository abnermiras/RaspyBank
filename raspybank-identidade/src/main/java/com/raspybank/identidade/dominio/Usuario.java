package com.raspybank.identidade.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Pessoa autenticada no sistema. Modulo M1.
 *
 * <p>Pertence a um ou mais Ambientes (RF-M2-02). Note que esta classe NAO tem
 * uma colecao de ambientes: o vinculo mora no modulo de ambiente. Contextos se
 * referenciam por identificador, nunca por objeto — e o que permite extrair um
 * deles para outro processo sem quebrar o outro.</p>
 */
@Entity
@Table(name = "usuario")
public class Usuario {

    /**
     * O identificador e gerado pelo BANCO, via {@code DEFAULT uuidv7()}.
     *
     * <p>{@code GenerationType.UUID} deixaria o Hibernate gerar um UUID
     * aleatorio (versao 4) na aplicacao, fragmentando o indice. Queremos o
     * UUIDv7 do Postgres 18, que e ordenado por tempo.</p>
     *
     * <p>{@code insertable = false} instrui o Hibernate a nao enviar a coluna
     * no INSERT, deixando o valor padrao do banco atuar. O
     * {@code @GeneratedValue} sem estrategia faz o Hibernate ler o valor de
     * volta apos gravar.</p>
     */
    @Id
    @GeneratedValue
    @Column(name = "id", insertable = false, updatable = false)
    private UUID id;

    @Column(name = "nome", nullable = false)
    private String nome;

    @Column(name = "email", nullable = false)
    private String email;

    /** Hash da senha. Nunca a senha em texto. */
    @Column(name = "senha_hash", nullable = false)
    private String senhaHash;

    /** Opcional — RF-M1-01. */
    @Column(name = "telegram_id")
    private String telegramId;

    @Column(name = "status", nullable = false)
    private String status = "ativo";

    @Column(name = "criado_em", insertable = false, updatable = false)
    private OffsetDateTime criadoEm;

    @Column(name = "atualizado_em", insertable = false, updatable = false)
    private OffsetDateTime atualizadoEm;

    /** Exigido pelo JPA. Nao usar diretamente. */
    protected Usuario() {
    }

    public Usuario(String nome, String email, String senhaHash) {
        this.nome = nome;
        this.email = email;
        this.senhaHash = senhaHash;
    }

    public UUID getId()                    { return id; }
    public String getNome()                { return nome; }
    public String getEmail()               { return email; }
    public String getSenhaHash()           { return senhaHash; }
    public String getTelegramId()          { return telegramId; }
    public String getStatus()              { return status; }
    public OffsetDateTime getCriadoEm()    { return criadoEm; }
    public OffsetDateTime getAtualizadoEm(){ return atualizadoEm; }

    public void setNome(String nome)             { this.nome = nome; }
    public void setTelegramId(String telegramId) { this.telegramId = telegramId; }
    public void setSenhaHash(String senhaHash)   { this.senhaHash = senhaHash; }
}
