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
 *
 * <p><b>Esta entidade NAO mapeia a coluna senha_hash, e isso e deliberado.</b>
 * A migracao V8 removeu o privilegio de leitura dessa coluna do usuario de
 * banco da aplicacao. Mapear o campo aqui faria o Hibernate inclui-lo em todo
 * SELECT, e qualquer findById passaria a falhar com permissao negada.</p>
 *
 * <p>O hash e lido e gravado exclusivamente pelas funcoes SECURITY DEFINER
 * {@code auth_buscar_credenciais} e {@code auth_cadastrar_usuario}, que
 * executam com privilegios do proprietario do banco. Se um dia for preciso
 * trocar a senha, o caminho e uma funcao nova no mesmo padrao — nunca
 * remapear o campo.</p>
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

    /** Opcional — RF-M1-01. */
    @Column(name = "telegram_id")
    private String telegramId;

    /**
     * Valores em MAIUSCULAS por convencao fechada na Fase 2 e aplicada pela
     * migracao V8. O CHECK do banco aceita apenas ATIVO e INATIVO — minusculo
     * seria recusado.
     */
    @Column(name = "status", nullable = false)
    private String status = "ATIVO";

    @Column(name = "criado_em", insertable = false, updatable = false)
    private OffsetDateTime criadoEm;

    @Column(name = "atualizado_em", insertable = false, updatable = false)
    private OffsetDateTime atualizadoEm;

    /** Exigido pelo JPA. Nao usar diretamente. */
    protected Usuario() {
    }

    // Nao existe construtor publico de criacao.
    //
    // Criar usuario pela aplicacao seria um INSERT direto, e a coluna
    // senha_hash e NOT NULL — o INSERT falharia, porque a entidade nao mapeia
    // essa coluna. O cadastro passa por auth_cadastrar_usuario, que grava as
    // tres colunas de uma vez com privilegios de proprietario.
    //
    // A ausencia deste construtor e a documentacao executavel dessa regra:
    // quem tentar `new Usuario(...)` nao compila, e vem ler este comentario.

    public UUID getId()                     { return id; }
    public String getNome()                 { return nome; }
    public String getEmail()                { return email; }
    public String getTelegramId()           { return telegramId; }
    public String getStatus()               { return status; }
    public OffsetDateTime getCriadoEm()     { return criadoEm; }
    public OffsetDateTime getAtualizadoEm() { return atualizadoEm; }

    public void setNome(String nome)             { this.nome = nome; }
    public void setTelegramId(String telegramId) { this.telegramId = telegramId; }
}