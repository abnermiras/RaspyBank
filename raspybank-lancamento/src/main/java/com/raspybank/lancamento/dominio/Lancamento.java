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

    /**
     * Como o dinheiro se moveu (V11). Vale nos DOIS sentidos: gasto no debito e
     * salario creditado sao as duas metades da mesma pergunta.
     *
     * <p>Anulavel de proposito, e por tres razoes distintas: nem toda
     * movimentacao tem forma conhecida; saldo de abertura ({@code AJUSTE}) e
     * transferencia ({@code TRANSFERENCIA}) nao se moveram de forma alguma; e
     * registrar depois, na edicao, e legitimo.</p>
     *
     * <p>Nao entra em soma nenhuma. Quem confere sao DUAS chaves compostas no
     * banco — {@code fk_lancamento_forma_da_conta}, que exige a forma na lista
     * daquela conta, e {@code fk_lancamento_forma_sentido}, que exige que ela
     * aceite o sentido. Nenhuma das duas vira {@code if} aqui: a entidade nao
     * enxerga a lista da conta nem a tabela de sentidos, e fingir que enxerga
     * criaria uma segunda verdade.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "forma_pagamento")
    private FormaPagamento formaPagamento;

    /**
     * A outra perna da transferencia (F2). Nulo em lancamento comum.
     *
     * <p>O vinculo e MUTUO: A aponta para B e B aponta para A. Poderia ser um
     * ponteiro so, do "filho" para o "pai", e nao e de proposito — numa
     * transferencia nao existe perna principal. Escolher uma criaria uma
     * assimetria que o dominio nao tem, e todo codigo que le o par precisaria
     * saber de que lado esta.</p>
     *
     * <p>A metade mais perigosa de F16 e cumprida pelo BANCO:
     * {@code fk_lancamento_par} e {@code ON DELETE CASCADE}, entao apagar uma
     * perna apaga a outra. Sem isso, apagar metade de uma transferencia faria
     * dinheiro aparecer do nada no patrimonio.</p>
     */
    @Column(name = "lancamento_par_id")
    private UUID lancamentoParId;

    /**
     * A fatura que cobra este lancamento (V12). Nulo em conta comum.
     *
     * <p>Quando preenchido, a {@code dataCaixa} do lancamento e o VENCIMENTO da
     * fatura (F14) — porque e nesse dia que o dinheiro sai do bolso, e o regime
     * do sistema e de caixa (P-T2). E por isso que uma compra de 10x em agosto
     * aparece no mapa como dez parcelas nos dez meses seguintes, e nao como mil
     * reais em agosto (B-D54).</p>
     */
    @Column(name = "fatura_id")
    private UUID faturaId;

    /**
     * Correlaciona as parcelas da mesma compra (B-D55).
     *
     * <p><b>Nao tem tabela-alvo, e isso e decisao registrada.</b> Uma tabela
     * {@code parcela} so guardaria agregado — o total e a soma das parcelas, a
     * data da compra e a {@code dataCompetencia} que se repete em todas (F23), a
     * quantidade e a contagem — e agregado guardado contraria P1. O custo
     * assumido: nao ha chave estrangeira garantindo o grupo, a integridade dele
     * fica na aplicacao.</p>
     */
    @Column(name = "grupo_parcelamento_id")
    private UUID grupoParcelamentoId;

    @Column(name = "parcela_numero")
    private Short parcelaNumero;

    @Column(name = "parcela_total")
    private Short parcelaTotal;

    /**
     * Qual plastico ou virtual fez a compra (V13). Nulo em lancamento comum.
     *
     * <p>A V12 amarrou o lancamento a FATURA, e isso bastava para cobrar. Nao
     * bastava para explicar: uma fatura com o fisico, dois virtuais e o adicional
     * da Luciana era uma pilha de gastos sem dono.</p>
     *
     * <p>Nulo tambem nas duas pernas de um PAGAMENTO de fatura — ninguem paga a
     * fatura "com o cartao dela". Por isso a regra de
     * {@code ck_lancamento_cartao_exige_fatura} e de mao unica: quem tem cartao
     * tem fatura, mas quem tem fatura nao precisa ter cartao.</p>
     */
    @Column(name = "cartao_emitido_id")
    private UUID cartaoEmitidoId;

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

    /**
     * Registra como o dinheiro se moveu. Aceita {@code null} — nao saber e um
     * estado legitimo, e melhor que gravar um palpite.
     *
     * <p>A entidade nao confere se a forma aceita o sentido nem se ela esta na
     * lista da conta: as duas coisas sao chaves compostas no banco, e um
     * {@code if} aqui seria uma segunda verdade sobre dados que esta classe nao
     * enxerga (a lista pertence a conta, o sentido a tabela de referencia).</p>
     */
    public void pagarPor(FormaPagamento forma)  { this.formaPagamento = forma; }

    /**
     * Amarra este lancamento a outra perna da transferencia.
     *
     * <p>Chamado duas vezes ao criar uma transferencia, uma de cada lado. Quem
     * orquestra e {@code TransferenciaServico}, que e o unico lugar do sistema
     * autorizado a criar um par — a T-08 nao cria transferencia perna a perna
     * justamente porque a primeira perna sozinha ja e um saldo errado.</p>
     */
    public void emparelharCom(UUID outroLancamentoId) {
        if (id != null && id.equals(outroLancamentoId)) {
            throw new OperacaoNaoPermitida("Um lancamento nao e par de si mesmo");
        }
        this.lancamentoParId = outroLancamentoId;
    }

    /** Faz parte de uma transferencia? */
    public boolean ehPernaDeTransferencia() {
        return lancamentoParId != null;
    }

    /**
     * Move a compra para outra fatura, trazendo a data de caixa junto.
     *
     * <p>As duas andam sempre juntas (F14): a data de caixa de um lancamento de
     * cartao <b>e</b> o vencimento da fatura. Separa-las deixaria o extrato
     * dizendo um mes e a fatura dizendo outro.</p>
     *
     * <p>Quem confere se a fatura de destino esta aberta e o servico — a
     * entidade nao enxerga o estado dela.</p>
     */
    public void cobrarNaFatura(UUID faturaId, LocalDate vencimentoDaFatura, LocalDate hoje) {
        this.faturaId = Objects.requireNonNull(faturaId, "faturaId e obrigatorio");
        reagendar(vencimentoDaFatura, hoje);
    }

    /** Marca este lancamento como uma das parcelas de uma compra parcelada. */
    public void comoParcela(UUID grupo, int numero, int total) {
        if (numero < 1 || numero > total || total < 2 || total > 99) {
            throw new OperacaoNaoPermitida(
                "Parcelamento invalido: parcela " + numero + " de " + total);
        }
        this.grupoParcelamentoId = Objects.requireNonNull(grupo, "grupo e obrigatorio");
        this.parcelaNumero = (short) numero;
        this.parcelaTotal = (short) total;
    }

    public boolean ehParcelado() {
        return grupoParcelamentoId != null;
    }

    public boolean ehDeCartao() {
        return faturaId != null;
    }

    /** Registra com qual plastico ou virtual a compra foi feita (V13). */
    public void compradoCom(UUID cartaoEmitidoId) {
        this.cartaoEmitidoId = cartaoEmitidoId;
    }

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
    public FormaPagamento getFormaPagamento()   { return formaPagamento; }
    public UUID getLancamentoParId()            { return lancamentoParId; }
    public UUID getFaturaId()                   { return faturaId; }
    public UUID getGrupoParcelamentoId()        { return grupoParcelamentoId; }
    public Short getParcelaNumero()             { return parcelaNumero; }
    public Short getParcelaTotal()              { return parcelaTotal; }
    public UUID getCartaoEmitidoId()            { return cartaoEmitidoId; }
    public LocalDate getDataCompetencia()       { return dataCompetencia; }
    public LocalDate getDataCaixa()             { return dataCaixa; }
    public UUID getCriadoPor()                  { return criadoPor; }
    public UUID getResponsavelId()              { return responsavelId; }
    public OffsetDateTime getCriadoEm()         { return criadoEm; }
}
