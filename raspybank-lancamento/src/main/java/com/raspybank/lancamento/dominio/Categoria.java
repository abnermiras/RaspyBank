package com.raspybank.lancamento.dominio;

import com.raspybank.shared.erro.OperacaoNaoPermitida;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Primeiro nivel da classificacao (F8). Modulo M3.
 *
 * <p>Categorias sao COPIADAS por ambiente (F9), nunca compartilhadas: duas
 * casas na mesma instalacao tem cada uma o seu "Mercado", com id proprio.
 * Compartilhar criaria entre ambientes exatamente a dependencia que a
 * fronteira de dados existe para impedir.</p>
 *
 * <h3>O que esta classe NAO tem</h3>
 *
 * <p><b>Nao tem colecao de subcategorias.</b> A T-04 mostra os dois niveis
 * juntos e o endpoint devolve aninhado, mas quem monta o aninhamento e a
 * consulta, nao o mapeamento. Uma colecao {@code @OneToMany} carregaria as
 * subcategorias em toda leitura de categoria — inclusive nas do relatorio,
 * que nao precisam delas.</p>
 *
 * <p><b>Nao tem exclusao.</b> Categoria se arquiva (B-D4 / R8). Arquivada,
 * some do seletor da T-08 e continua nomeando o historico inteiro. E por
 * isso que o lancamento nao precisa congelar o nome: o id sempre resolve.</p>
 */
@Entity
@Table(name = "categoria")
public class Categoria {

    /** Gerado pelo banco via {@code DEFAULT uuidv7()} — ver {@code Usuario#id}. */
    @Id
    @GeneratedValue
    @Column(name = "id", insertable = false, updatable = false)
    private UUID id;

    /**
     * O ambiente dono. Identificador, nunca a entidade: o contexto de
     * lancamento nao conhece o de ambiente, e o teste de arquitetura garante.
     */
    @Column(name = "ambiente_id", nullable = false, updatable = false)
    private UUID ambienteId;

    /** Preenchido so nas sistemicas (F10). Ver {@link CodigoSistemico}. */
    @Column(name = "codigo", updatable = false)
    private String codigo;

    @Column(name = "nome", nullable = false)
    private String nome;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false)
    private TipoCategoria tipo;

    /**
     * "Pode editar?" (F10). Falso nas categorias que a pessoa criou.
     *
     * <p>Nao confundir com {@link #entraNoMapa}: sao duas perguntas
     * diferentes, e trata-las como uma so foi a armadilha registrada em
     * B-D15. Ver o comentario do campo vizinho.</p>
     */
    @Column(name = "sistemica", nullable = false, updatable = false)
    private boolean sistemica;

    /**
     * "Conta como gasto no mapa?" (relatorio T-07).
     *
     * <p>Quase coincide com {@link #sistemica}, e a excecao e justamente o
     * caso perigoso: {@code NAO_CLASSIFICADO} e sistemica e <b>e gasto de
     * verdade</b> — so falta o rotulo. Uma flag so faria os gastos do bot do
     * Telegram sumirem do total, em silencio, que e o pior tipo de erro num
     * relatorio.</p>
     */
    @Column(name = "entra_no_mapa", nullable = false, updatable = false)
    private boolean entraNoMapa;

    @Column(name = "arquivada_em")
    private OffsetDateTime arquivadaEm;

    @Column(name = "criado_em", insertable = false, updatable = false)
    private OffsetDateTime criadoEm;

    @Column(name = "atualizado_em", insertable = false, updatable = false)
    private OffsetDateTime atualizadoEm;

    protected Categoria() {
    }

    /**
     * Cria uma categoria comum — a unica que a aplicacao cria.
     *
     * <p>As sistemicas nascem com o ambiente, pela funcao
     * {@code fn_criar_categorias_sistemicas} (B-D16), e nao passam por aqui.
     * Por isso este construtor nao aceita {@code codigo}: uma categoria com
     * codigo criada pela aplicacao seria uma sistemica sem cadeado, e o
     * CHECK {@code ck_categoria_codigo} recusaria a linha de qualquer forma.</p>
     */
    public Categoria(UUID ambienteId, String nome, TipoCategoria tipo) {
        this.ambienteId = ambienteId;
        this.nome = nome;
        this.tipo = tipo;
        this.codigo = null;
        this.sistemica = false;
        this.entraNoMapa = true;
    }

    /**
     * Renomear e acao leve (B-D3): troca o texto e nada mais.
     *
     * <p>O novo nome aparece em todos os lancamentos, passados inclusive,
     * porque o lancamento guarda o id e o nome vem daqui. Nunca cria
     * categoria nova.</p>
     */
    public void renomear(String novoNome) {
        exigirEditavel("renomear");
        this.nome = novoNome;
    }

    /** Troca o sentido aceito. Mesmo cadeado do rename. */
    public void mudarTipo(TipoCategoria novoTipo) {
        exigirEditavel("mudar o tipo de");
        this.tipo = novoTipo;
    }

    public void arquivar(OffsetDateTime quando) {
        exigirEditavel("arquivar");
        this.arquivadaEm = quando;
    }

    public void desarquivar() {
        exigirEditavel("desarquivar");
        this.arquivadaEm = null;
    }

    public boolean estaArquivada() {
        return arquivadaEm != null;
    }

    /**
     * O cadeado de F10, num lugar so.
     *
     * <p>Repetir a condicao em cada metodo publico seria repetir a chance de
     * esquecer dela num metodo futuro.</p>
     */
    private void exigirEditavel(String acao) {
        if (sistemica) {
            throw new OperacaoNaoPermitida(
                "Nao e possivel " + acao + " uma categoria sistemica (" + codigo + ")");
        }
    }

    public UUID getId()                    { return id; }
    public UUID getAmbienteId()            { return ambienteId; }
    public String getCodigo()              { return codigo; }
    public String getNome()                { return nome; }
    public TipoCategoria getTipo()         { return tipo; }
    public boolean isSistemica()           { return sistemica; }
    public boolean isEntraNoMapa()         { return entraNoMapa; }
    public OffsetDateTime getArquivadaEm() { return arquivadaEm; }
    public OffsetDateTime getCriadoEm()    { return criadoEm; }
}
