package com.raspybank.lancamento.dominio;

import com.raspybank.shared.erro.OperacaoNaoPermitida;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * A entidade que justifica todas as outras.
 *
 * <p>Fonte unica de verdade sobre dinheiro (P1/R1): saldo, total por
 * categoria, patrimonio — tudo e soma daqui, nada e guardado pronto.</p>
 *
 * <h3>Por que as datas sao {@link LocalDate} e nao instantes</h3>
 *
 * <p>O banco guarda timestamps em UTC. Com o regime de caixa (P-T2), um
 * lancamento as 21h de 31/jan em Sao Paulo seria 01/fev em UTC e cairia no
 * MES ERRADO do quadro central. Data de dinheiro nao tem hora, e
 * {@code date} nao tem fuso para errar (B-D8).</p>
 *
 * <h3>As tres chaves compostas, que esta classe nao repete</h3>
 *
 * <p>A V10 garante no banco que a conta esteja visivel no ambiente do
 * lancamento (B-D2), que a categoria seja do mesmo ambiente (F9) e que a
 * subcategoria pertenca a categoria informada (F11). Sao regras de negocio
 * viradas impossibilidade estrutural — nao um {@code if} que alguem um dia
 * esquece de escrever.</p>
 *
 * <p>As validacoes que existem aqui nao duplicam aquelas: elas cobrem o que
 * o banco <b>nao</b> alcanca (o sentido aceito pela categoria, a escala do
 * valor) ou existem para dar mensagem melhor antes da ida ao banco. Quando
 * as duas falarem da mesma regra, a do banco e a que manda.</p>
 */
@Entity
@Table(name = "lancamento")
public class Lancamento {

    @Id
    @GeneratedValue
    @Column(name = "id", insertable = false, updatable = false)
    private UUID id;

    /**
     * O ambiente ATIVO na criacao (B-D2), nao o ambiente "da conta" — que
     * nao existe, ja que a conta pode estar visivel em varios. E por ele que
     * o relatorio filtra (F33), e e a resposta para "de quem e o gasto numa
     * conta conjunta".
     */
    @Column(name = "ambiente_id", nullable = false, updatable = false)
    private UUID ambienteId;

    @Column(name = "conta_id", nullable = false)
    private UUID contaId;

    @Column(name = "categoria_id", nullable = false)
    private UUID categoriaId;

    /** Opcional (F11): classificar ate o segundo nivel e escolha, nao dever. */
    @Column(name = "subcategoria_id")
    private UUID subcategoriaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false)
    private TipoLancamento tipo;

    @Enumerated(EnumType.STRING)
    @Column(name = "situacao", nullable = false)
    private SituacaoLancamento situacao;

    /**
     * {@code numeric(15,2)} — F1, sem excecao. {@code double} para dinheiro e
     * proibido. Sempre POSITIVO: o sinal e responsabilidade do
     * {@link TipoLancamento}, nunca do valor.
     */
    @Column(name = "valor", nullable = false)
    private BigDecimal valor;

    @Column(name = "descricao")
    private String descricao;

    /** Texto livre (F29). Anexo ficou fora da v1.0. */
    @Column(name = "observacao")
    private String observacao;

    /** Quando o fato ocorreu — a compra, o servico prestado. */
    @Column(name = "data_competencia", nullable = false)
    private LocalDate dataCompetencia;

    /** Quando o dinheiro sai ou entra do bolso. E esta que o quadro usa (P-T2). */
    @Column(name = "data_caixa", nullable = false)
    private LocalDate dataCaixa;

    /** Imutavel (F32). O lancamento sobrevive a remocao de quem o criou. */
    @Column(name = "criado_por", nullable = false, updatable = false)
    private UUID criadoPor;

    /** Dimensao de analise, nao de acesso (F32). Pode mudar. */
    @Column(name = "responsavel_id")
    private UUID responsavelId;

    @Column(name = "criado_em", insertable = false, updatable = false)
    private OffsetDateTime criadoEm;

    @Column(name = "atualizado_em", insertable = false, updatable = false)
    private OffsetDateTime atualizadoEm;

    protected Lancamento() {
    }

    /**
     * Registra um lancamento novo.
     *
     * <p>Recebe a {@link Categoria} inteira, e nao o id dela, por tres
     * motivos: dela sai o {@code ambiente_id} (evitando que o chamador
     * informe um ambiente que nao e o da categoria), dela se verifica o
     * sentido aceito (F12), e dela se sabe se esta arquivada.</p>
     *
     * <p>A situacao nao e parametro: deriva da data de caixa (B-D9). O
     * formulario da T-08 nao pergunta.</p>
     *
     * @param hoje data de referencia da derivacao, injetada e nao consultada
     *             (padrao B-C3) — e o que torna a regra testavel em qualquer dia
     */
    public Lancamento(Categoria categoria,
                      UUID contaId,
                      TipoLancamento tipo,
                      BigDecimal valor,
                      LocalDate dataCompetencia,
                      LocalDate dataCaixa,
                      UUID criadoPor,
                      LocalDate hoje) {

        Objects.requireNonNull(categoria, "categoria e obrigatoria");
        exigirValorDeDinheiro(valor);

        if (categoria.estaArquivada()) {
            throw new OperacaoNaoPermitida(
                "Categoria arquivada nao classifica lancamento novo: " + categoria.getNome());
        }
        if (!categoria.getTipo().aceita(tipo)) {
            throw new OperacaoNaoPermitida(
                "Categoria '" + categoria.getNome() + "' e do tipo "
                    + categoria.getTipo() + " e nao aceita lancamento de " + tipo);
        }

        this.ambienteId = categoria.getAmbienteId();
        this.categoriaId = categoria.getId();
        this.contaId = Objects.requireNonNull(contaId, "contaId e obrigatorio");
        this.tipo = tipo;
        this.valor = valor;
        this.dataCompetencia = Objects.requireNonNull(dataCompetencia, "dataCompetencia e obrigatoria");
        this.dataCaixa = Objects.requireNonNull(dataCaixa, "dataCaixa e obrigatoria");
        this.criadoPor = Objects.requireNonNull(criadoPor, "criadoPor e obrigatorio");
        this.situacao = SituacaoLancamento.derivarDe(dataCaixa, hoje);
    }

    /**
     * Classifica no segundo nivel.
     *
     * <p>A checagem repete o que a chave composta ja garante (F11), de
     * proposito: aqui a mensagem diz qual categoria e qual subcategoria, e a
     * do banco diria apenas que uma constraint falhou.</p>
     */
    public void classificarEm(Subcategoria subcategoria) {
        if (subcategoria == null) {
            this.subcategoriaId = null;
            return;
        }
        if (!subcategoria.getCategoriaId().equals(categoriaId)) {
            throw new OperacaoNaoPermitida(
                "Subcategoria '" + subcategoria.getNome()
                    + "' nao pertence a categoria do lancamento");
        }
        this.subcategoriaId = subcategoria.getId();
    }

    /**
     * Move o lancamento para outra categoria, refazendo a classificacao.
     *
     * <p>A subcategoria e zerada porque ela pertencia a categoria antiga —
     * manter o id faria a chave composta recusar a linha, e adivinhar uma
     * equivalente na categoria nova seria inventar dado.</p>
     */
    public void reclassificar(Categoria novaCategoria) {
        if (!novaCategoria.getAmbienteId().equals(ambienteId)) {
            throw new OperacaoNaoPermitida("Categoria de outro ambiente");
        }
        if (!novaCategoria.getTipo().aceita(tipo)) {
            throw new OperacaoNaoPermitida(
                "Categoria '" + novaCategoria.getNome() + "' nao aceita lancamento de " + tipo);
        }
        this.categoriaId = novaCategoria.getId();
        this.subcategoriaId = null;
    }

    /**
     * Altera o valor e a data de caixa, rederivando a situacao (B-D9).
     *
     * <p>Corrigir a data de um gasto para o mes que vem transforma um
     * REALIZADO em PREVISTO, e isso e o comportamento desejado: a situacao
     * segue a data, sempre — a menos que alguem a fixe por
     * {@link #corrigirSituacao}.</p>
     */
    public void reagendar(LocalDate novaDataCaixa, LocalDate hoje) {
        this.dataCaixa = Objects.requireNonNull(novaDataCaixa, "dataCaixa e obrigatoria");
        this.situacao = SituacaoLancamento.derivarDe(novaDataCaixa, hoje);
    }

    /**
     * Fixa a situacao contra a derivacao, quando a pessoa tem razao.
     *
     * <p>E por causa deste metodo que B-D9 nao virou gatilho no banco: o
     * boleto agendado para amanha que ja foi debitado hoje existe, e uma
     * regra que o banco impoe e uma regra que o usuario nao consegue
     * contrariar.</p>
     */
    public void corrigirSituacao(SituacaoLancamento situacao) {
        this.situacao = Objects.requireNonNull(situacao, "situacao e obrigatoria");
    }

    public void alterarValor(BigDecimal novoValor) {
        exigirValorDeDinheiro(novoValor);
        this.valor = novoValor;
    }

    public void descrever(String descricao)     { this.descricao = descricao; }
    public void observar(String observacao)     { this.observacao = observacao; }
    public void atribuirA(UUID responsavelId)   { this.responsavelId = responsavelId; }
    public void moverPara(UUID contaId)         { this.contaId = Objects.requireNonNull(contaId); }

    public void ajustarCompetencia(LocalDate data) {
        this.dataCompetencia = Objects.requireNonNull(data, "dataCompetencia e obrigatoria");
    }

    /**
     * Duas regras de dinheiro num lugar so.
     *
     * <p>A primeira espelha o CHECK {@code valor > 0}. A segunda o banco nao
     * faz: {@code numeric(15,2)} <b>arredonda em silencio</b> o que vier com
     * mais casas, e 10,005 viraria 10,01 sem ninguem saber. Recusar e melhor
     * do que gravar um numero que o usuario nao digitou.</p>
     */
    private static void exigirValorDeDinheiro(BigDecimal valor) {
        Objects.requireNonNull(valor, "valor e obrigatorio");
        if (valor.signum() <= 0) {
            throw new OperacaoNaoPermitida(
                "Valor deve ser positivo — o sinal vem do tipo, nao do valor: " + valor);
        }
        if (valor.stripTrailingZeros().scale() > 2) {
            throw new OperacaoNaoPermitida(
                "Valor com mais de duas casas decimais seria arredondado em silencio: " + valor);
        }
    }

    public UUID getId()                         { return id; }
    public UUID getAmbienteId()                 { return ambienteId; }
    public UUID getContaId()                    { return contaId; }
    public UUID getCategoriaId()                { return categoriaId; }
    public UUID getSubcategoriaId()             { return subcategoriaId; }
    public TipoLancamento getTipo()             { return tipo; }
    public SituacaoLancamento getSituacao()     { return situacao; }
    public BigDecimal getValor()                { return valor; }
    public String getDescricao()                { return descricao; }
    public String getObservacao()               { return observacao; }
    public LocalDate getDataCompetencia()       { return dataCompetencia; }
    public LocalDate getDataCaixa()             { return dataCaixa; }
    public UUID getCriadoPor()                  { return criadoPor; }
    public UUID getResponsavelId()              { return responsavelId; }
    public OffsetDateTime getCriadoEm()         { return criadoEm; }
}
