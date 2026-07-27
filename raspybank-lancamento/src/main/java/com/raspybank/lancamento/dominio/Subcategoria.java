package com.raspybank.lancamento.dominio;

import com.raspybank.shared.erro.OperacaoNaoPermitida;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Segundo e ultimo nivel da classificacao (F8).
 *
 * <p>Nao existe sub-subcategoria, e a ausencia e a garantia: o que nao tem
 * caminho no schema nao vira dado por engano.</p>
 *
 * <h3>Por que {@code ambienteId} aparece aqui, se a categoria ja o tem</h3>
 *
 * <p>Denormalizacao deliberada, por dois motivos registrados na V10. A
 * politica de RLS precisa do ambiente, e sem a coluna ela faria subquery em
 * {@code categoria} — que tambem tem politica — fazendo cada leitura pagar
 * duas avaliacoes. E a chave composta
 * {@code (ambiente_id, categoria_id) -> categoria (ambiente_id, id)} torna a
 * copia <b>impossivel</b> de divergir.</p>
 *
 * <p>Denormalizar sem restricao cria duas verdades; com restricao, cria um
 * atalho que o banco garante.</p>
 */
@Entity
@Table(name = "subcategoria")
public class Subcategoria {

    @Id
    @GeneratedValue
    @Column(name = "id", insertable = false, updatable = false)
    private UUID id;

    @Column(name = "ambiente_id", nullable = false, updatable = false)
    private UUID ambienteId;

    @Column(name = "categoria_id", nullable = false, updatable = false)
    private UUID categoriaId;

    @Column(name = "nome", nullable = false)
    private String nome;

    @Column(name = "arquivada_em")
    private OffsetDateTime arquivadaEm;

    @Column(name = "criado_em", insertable = false, updatable = false)
    private OffsetDateTime criadoEm;

    @Column(name = "atualizado_em", insertable = false, updatable = false)
    private OffsetDateTime atualizadoEm;

    protected Subcategoria() {
    }

    /**
     * Nasce sempre a partir da categoria mae, que fornece as duas chaves.
     *
     * <p>Receber a {@link Categoria} inteira em vez de dois UUIDs soltos evita
     * o unico erro possivel aqui: passar o ambiente de um e a categoria de
     * outro. O banco recusaria pela chave composta, mas recusar antes de ir
     * ao banco da uma mensagem melhor.</p>
     *
     * <p>E permite o cadeado abaixo. Categoria sistemica nao recebe
     * subcategoria: {@code TRANSFERENCIA > Pix} nao significa nada, e as
     * tres sistemicas existem para o codigo achar, nao para a pessoa
     * organizar (F10 / B-D13).</p>
     */
    public Subcategoria(Categoria categoria, String nome) {
        if (categoria.isSistemica()) {
            throw new OperacaoNaoPermitida(
                "Categoria sistemica (" + categoria.getCodigo()
                    + ") nao recebe subcategoria");
        }
        this.ambienteId = categoria.getAmbienteId();
        this.categoriaId = categoria.getId();
        this.nome = nome;
    }

    public void renomear(String novoNome) {
        this.nome = novoNome;
    }

    public void arquivar(OffsetDateTime quando) {
        this.arquivadaEm = quando;
    }

    public void desarquivar() {
        this.arquivadaEm = null;
    }

    public boolean estaArquivada() {
        return arquivadaEm != null;
    }

    public UUID getId()                    { return id; }
    public UUID getAmbienteId()            { return ambienteId; }
    public UUID getCategoriaId()           { return categoriaId; }
    public String getNome()                { return nome; }
    public OffsetDateTime getArquivadaEm() { return arquivadaEm; }
    public OffsetDateTime getCriadoEm()    { return criadoEm; }
}
