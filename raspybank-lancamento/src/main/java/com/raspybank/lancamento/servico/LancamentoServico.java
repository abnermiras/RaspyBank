package com.raspybank.lancamento.servico;

import com.raspybank.lancamento.dominio.Categoria;
import com.raspybank.lancamento.dominio.CodigoSistemico;
import com.raspybank.lancamento.dominio.Conta;
import com.raspybank.lancamento.dominio.ContaAmbiente;
import com.raspybank.lancamento.dominio.ContaFormaPagamento;
import com.raspybank.lancamento.dominio.FormaPagamento;
import com.raspybank.lancamento.dominio.Lancamento;
import com.raspybank.lancamento.dominio.SituacaoLancamento;
import com.raspybank.lancamento.dominio.Subcategoria;
import com.raspybank.lancamento.dominio.TipoLancamento;
import com.raspybank.lancamento.repositorio.CategoriaRepositorio;
import com.raspybank.lancamento.repositorio.ContaAmbienteRepositorio;
import com.raspybank.lancamento.repositorio.ContaFormaPagamentoRepositorio;
import com.raspybank.lancamento.repositorio.ContaRepositorio;
import com.raspybank.lancamento.repositorio.LancamentoRepositorio;
import com.raspybank.lancamento.repositorio.SubcategoriaRepositorio;
import com.raspybank.shared.erro.OperacaoNaoPermitida;
import com.raspybank.shared.erro.RecursoNaoEncontrado;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Casos de uso do lancamento (T-08).
 *
 * <h3>O que este servico NAO valida</h3>
 *
 * <p>Quase nada, e e o ponto. As tres regras mais importantes ja sao
 * impossibilidades estruturais no banco (V10): a conta tem que estar visivel
 * no ambiente (B-D2), a categoria tem que ser do mesmo ambiente (F9), a
 * subcategoria tem que pertencer a categoria (F11). O valor positivo e CHECK.
 * O sentido aceito pela categoria e a entidade quem confere.</p>
 *
 * <p>O que sobra aqui e <b>tradução</b>: transformar id em objeto, derivar o
 * que o formulario nao pergunta, e trocar um erro de constraint por uma frase
 * que a tela consegue exibir. Um servico que revalida o que o banco ja garante
 * cria uma segunda verdade — e a segunda verdade e sempre a que fica para
 * tras.</p>
 *
 * <h3>As duas derivacoes</h3>
 *
 * <ul>
 *   <li><b>Situacao</b> vem da data de caixa (B-D9). Nao ha campo no
 *       formulario.</li>
 *   <li><b>Tipo</b> vem da categoria (F12). So quando ela e {@code AMBOS} — as
 *       tres sistemicas — o corpo precisa declarar.</li>
 * </ul>
 */
@Service
public class LancamentoServico {

    private final LancamentoRepositorio lancamentos;
    private final CategoriaRepositorio categorias;
    private final SubcategoriaRepositorio subcategorias;
    private final ContaRepositorio contas;
    private final ContaAmbienteRepositorio vinculos;
    private final ContaFormaPagamentoRepositorio formasDePagamento;
    private final SituacaoVencidaServico vencidos;

    public LancamentoServico(LancamentoRepositorio lancamentos,
                             CategoriaRepositorio categorias,
                             SubcategoriaRepositorio subcategorias,
                             ContaRepositorio contas,
                             ContaAmbienteRepositorio vinculos,
                             ContaFormaPagamentoRepositorio formasDePagamento,
                             SituacaoVencidaServico vencidos) {
        this.lancamentos = lancamentos;
        this.categorias = categorias;
        this.subcategorias = subcategorias;
        this.contas = contas;
        this.vinculos = vinculos;
        this.formasDePagamento = formasDePagamento;
        this.vencidos = vencidos;
    }

    // =========================================================================
    // Leitura
    // =========================================================================

    /**
     * O extrato de um mes, com conta e classificacao ja resolvidas.
     *
     * <p>Os nomes sao buscados em lote — uma consulta para todas as contas do
     * resultado, uma para todas as categorias, uma para todas as
     * subcategorias. Resolver dentro do laco daria o mesmo JSON e uma consulta
     * por linha.</p>
     *
     * <p><b>Nada de nome congelado</b> (B-D4 / R8): o nome exibido vem sempre
     * da categoria atual. Renomear "Mercado" para "Mercado e feira" reescreve
     * o passado inteiro, e isso e o comportamento desejado — o lancamento
     * aponta para uma categoria, nao para um texto.</p>
     */
    @Transactional(readOnly = true)
    public List<Item> listar(UUID ambienteId, YearMonth mes, UUID contaId,
                             UUID categoriaId, SituacaoLancamento situacao) {

        // Antes de ler: o previsto que venceu vira realizado. Sem isto, o
        // boleto de 05/08 continuaria PREVISTO em 06/08 e o extrato mostraria
        // uma etiqueta que o calendario ja desmentiu.
        vencidos.realizarVencidos(ambienteId, LocalDate.now());

        List<Lancamento> encontrados = lancamentos.buscar(
            ambienteId, mes.atDay(1), mes.atEndOfMonth(), contaId, categoriaId, situacao);

        return comNomes(encontrados);
    }

    @Transactional(readOnly = true)
    public Item buscar(UUID ambienteId, UUID id) {
        return comNomes(List.of(exigir(ambienteId, id))).get(0);
    }

    // =========================================================================
    // Escrita
    // =========================================================================

    /**
     * Registra um lancamento novo.
     *
     * @param tipoDeclarado obrigatorio apenas quando a categoria e {@code AMBOS}
     * @param hoje          data de referencia da derivacao de situacao (B-D9)
     */
    @Transactional
    public Lancamento registrar(UUID ambienteId, UUID usuarioId, Dados dados, LocalDate hoje) {

        Categoria categoria = exigirCategoria(ambienteId, dados.categoriaId());
        exigirContaNoAmbiente(ambienteId, dados.contaId());

        // Transferencia so nasce em par, pela porta de TransferenciaServico.
        //
        // Um lancamento avulso em TRANSFERENCIA e exatamente a meia
        // transferencia que a V11 existe para impedir: dinheiro sai de uma conta
        // sem entrar em nenhuma, o par fica nulo, e nada denuncia isso depois —
        // nenhum saldo isolado parece errado.
        //
        // A guarda esta no POST e NAO no PUT de proposito: editar uma perna
        // existente e legitimo (ate propaga para a outra), e o par continua
        // intacto porque a categoria nao muda.
        if (CodigoSistemico.TRANSFERENCIA.name().equals(categoria.getCodigo())) {
            throw new OperacaoNaoPermitida(
                "Transferencia nao se lanca avulsa: ela e um par de lancamentos e"
                    + " precisa de conta de origem e de destino."
                    + " Use POST /api/transferencias.");
        }

        TipoLancamento tipo = resolverTipo(categoria, dados.tipoDeclarado());

        Lancamento novo = new Lancamento(
            categoria,
            dados.contaId(),
            tipo,
            dados.valor(),
            // dataCompetencia ausente copia dataCaixa (F14): no gasto do dia a
            // dia as duas coincidem, e obrigar a digitar duas vezes a mesma
            // data e o tipo de atrito que faz a pessoa parar de lancar.
            Optional.ofNullable(dados.dataCompetencia()).orElse(dados.dataCaixa()),
            dados.dataCaixa(),
            usuarioId,
            hoje);

        novo.classificarEm(buscarSubcategoria(ambienteId, dados.subcategoriaId()));
        novo.descrever(dados.descricao());
        novo.observar(dados.observacao());
        novo.atribuirA(dados.responsavelId());
        novo.pagarPor(resolverFormaDePagamento(
            dados.contaId(), dados.formaPagamento(), tipo, categoria));

        return lancamentos.save(novo);
    }

    /**
     * Substitui o conteudo de um lancamento.
     *
     * <p>A situacao volta a derivar da data, <b>a menos que</b> o corpo a
     * declare. E a razao de B-D9 nao ser gatilho: corrigir "o boleto de amanha
     * ja foi debitado" e legitimo, e uma regra imposta pelo banco nao teria
     * como abrir essa excecao.</p>
     */
    @Transactional
    public Lancamento atualizar(UUID ambienteId, UUID id, Dados dados, LocalDate hoje) {

        Lancamento l = exigir(ambienteId, id);
        Categoria categoria = exigirCategoria(ambienteId, dados.categoriaId());
        exigirContaNoAmbiente(ambienteId, dados.contaId());

        // Uma perna de transferencia nao muda de categoria. Sair de
        // TRANSFERENCIA deixaria um par ligado por lancamento_par_id com
        // classificacoes diferentes em cada lado, e o mapa de gastos passaria a
        // contar metade de um movimento que nao e gasto (B-D15).
        if (l.ehPernaDeTransferencia() && !categoria.getId().equals(l.getCategoriaId())) {
            throw new OperacaoNaoPermitida(
                "Este lancamento e uma perna de transferencia e nao muda de categoria."
                    + " Exclua a transferencia e refaca, se ela estiver errada.");
        }

        // Reclassificar antes de tudo: ele zera a subcategoria antiga, que
        // pertencia a categoria anterior e nao sobreviveria a troca.
        if (!categoria.getId().equals(l.getCategoriaId())) {
            l.reclassificar(categoria);
        }

        l.moverPara(dados.contaId());
        l.alterarValor(dados.valor());
        l.ajustarCompetencia(
            Optional.ofNullable(dados.dataCompetencia()).orElse(dados.dataCaixa()));
        l.reagendar(dados.dataCaixa(), hoje);

        if (dados.situacao() != null) {
            l.corrigirSituacao(dados.situacao());
        }

        l.classificarEm(buscarSubcategoria(ambienteId, dados.subcategoriaId()));
        l.descrever(dados.descricao());
        l.observar(dados.observacao());
        l.atribuirA(dados.responsavelId());

        // Sem derivacao de padrao aqui, ao contrario do POST — e a diferenca e
        // proposital. No PUT a tela mostra o campo ja preenchido com o valor
        // atual, entao mandar vazio e um ato: a pessoa esta LIMPANDO. Reaplicar
        // o padrao seria desfazer, no servidor, o que ela acabou de fazer.
        l.pagarPor(conferirFormaAceita(dados.contaId(), dados.formaPagamento(), l.getTipo()));

        propagarParaOPar(l);

        return l;
    }

    /**
     * Exclui de verdade — o lancamento e a unica entidade do modelo com
     * exclusao fisica (F16).
     *
     * <p>Pode, porque nada aponta para ele: apagar um lancamento nao deixa
     * nenhuma outra linha orfa, e o saldo simplesmente volta a ser a soma do
     * que restou. Apagar uma conta ou uma categoria apagaria o passado de
     * outras linhas — por isso aquelas encerram e arquivam.</p>
     *
     * <p>O rastro nao se perde: o gatilho {@code tg_lancamento_auditoria}
     * grava a linha inteira em {@code registro_auditoria} antes de ela sumir,
     * com autor e canal (F26 / B-D6).</p>
     */
    @Transactional
    public void excluir(UUID ambienteId, UUID id) {
        lancamentos.delete(exigir(ambienteId, id));
    }

    // =========================================================================
    // Resolucao e guardas
    // =========================================================================

    private TipoLancamento resolverTipo(Categoria categoria, TipoLancamento declarado) {
        Optional<TipoLancamento> imposto = categoria.getTipo().sentidoUnico();

        if (imposto.isPresent()) {
            // Declarar o oposto do que a categoria impoe nao e ignorado em
            // silencio: seria gravar algo diferente do que a pessoa pediu.
            if (declarado != null && declarado != imposto.get()) {
                throw new OperacaoNaoPermitida(
                    "Categoria '" + categoria.getNome() + "' e do tipo "
                        + categoria.getTipo() + " e nao aceita lancamento de " + declarado);
            }
            return imposto.get();
        }

        if (declarado == null) {
            throw new OperacaoNaoPermitida(
                "Categoria '" + categoria.getNome() + "' aceita os dois sentidos:"
                    + " informe tipo ENTRADA ou SAIDA");
        }
        return declarado;
    }

    /**
     * A forma do lancamento novo: a informada, ou a padrao da conta para aquele
     * sentido.
     *
     * <p>A regra pedida em 27/07/2026 foi "se a pessoa nao indicar, salva
     * debito". Duas correcoes apareceram ao desenhar, e as duas viraram
     * codigo:</p>
     *
     * <ul>
     *   <li>Debito <b>literal</b> quebraria na carteira, que so aceita
     *       {@code DINHEIRO}: gravaria nela uma forma que a lista da propria
     *       conta recusa. Por isso o padrao e POR CONTA.</li>
     *   <li>Entrada tambem tem "como o dinheiro se moveu" — o salario e
     *       CREDITADO. Por isso sao DOIS padroes por conta, um de cada
     *       sentido.</li>
     * </ul>
     *
     * <h4>A ausencia que sobrou</h4>
     *
     * <p><b>Categoria sistemica nao recebe padrao.</b> Saldo de abertura e um
     * lancamento em {@code AJUSTE} (A13) e transferencia e um par em
     * {@code TRANSFERENCIA}: nenhum dos dois se moveu por pix, boleto ou coisa
     * nenhuma — o dinheiro so trocou de lugar. Sem esta guarda, o saldo inicial
     * de toda conta nova apareceria no extrato como "pago no debito", que e
     * lixo visivel logo na primeira tela que a pessoa abre.</p>
     *
     * <p>E e ela tambem que deixa as duas pernas de uma transferencia nascerem
     * com forma nula sem nenhum caso especial: a categoria delas e sistemica.</p>
     */
    private FormaPagamento resolverFormaDePagamento(UUID contaId, FormaPagamento informada,
                                                    TipoLancamento tipo, Categoria categoria) {
        if (informada != null) {
            return conferirFormaAceita(contaId, informada, tipo);
        }
        if (categoria.isSistemica()) {
            return null;
        }

        Optional<ContaFormaPagamento> padrao = tipo == TipoLancamento.SAIDA
            ? formasDePagamento.findByContaIdAndPadraoSaidaTrue(contaId)
            : formasDePagamento.findByContaIdAndPadraoEntradaTrue(contaId);

        return padrao.map(ContaFormaPagamento::getForma).orElse(null);
    }

    /**
     * Recusa forma impossivel com a frase que a tela consegue exibir.
     *
     * <p>Duas perguntas, e elas sao diferentes: a conta aceita esta forma? e
     * esta forma serve a este sentido? Uma conta corrente que aceita boleto E
     * credito em conta passaria na primeira e falharia na segunda ao tentar
     * "salario pago no boleto".</p>
     *
     * <p>As duas ja sao chaves compostas no banco —
     * {@code fk_lancamento_forma_da_conta} e
     * {@code fk_lancamento_forma_sentido} — e sao elas que mandam. O que se
     * ganha aqui e dizer QUAIS formas cabem, em vez de "uma restricao de
     * integridade falhou".</p>
     */
    private FormaPagamento conferirFormaAceita(UUID contaId, FormaPagamento forma,
                                               TipoLancamento tipo) {
        if (forma == null) {
            return null;
        }

        List<FormaPagamento> daConta = formasDePagamento.findByContaId(contaId).stream()
            .map(ContaFormaPagamento::getForma)
            .toList();

        if (!daConta.contains(forma)) {
            throw new OperacaoNaoPermitida(daConta.isEmpty()
                ? "Esta conta ainda nao tem formas de pagamento cadastradas."
                    + " Cadastre-as na tela de contas antes de usar " + forma + "."
                : "Esta conta nao aceita " + forma + ". Formas aceitas: " + daConta);
        }

        if (!forma.aceita(tipo)) {
            List<FormaPagamento> servem = daConta.stream().filter(f -> f.aceita(tipo)).toList();
            throw new OperacaoNaoPermitida(
                forma + " nao serve para lancamento de " + tipo
                    + ". Nesta conta servem: " + servem);
        }
        return forma;
    }

    /**
     * Mantem as duas pernas da transferencia dizendo a mesma coisa (F16).
     *
     * <p>Propaga o que <b>precisa</b> ser igual nos dois lados: valor, data de
     * caixa e situacao. Se um lado virasse 100 e o outro continuasse 10,
     * noventa reais apareceriam do nada no patrimonio — em silencio, porque
     * nenhum saldo isolado pareceria errado.</p>
     *
     * <p>O que NAO propaga, e de proposito: a conta e a descricao. A conta e o
     * que distingue as pernas, e corrigir "saiu do Nubank, nao do Itau" e uma
     * correcao de um lado so. A descricao pode legitimamente diferir ("saque" de
     * um lado, "dinheiro para a feira" do outro).</p>
     *
     * <p>A outra metade de F16 — apagar uma perna apaga a outra — nao esta
     * aqui: e {@code ON DELETE CASCADE} no banco. Regra de integridade cumprida
     * pelo banco nao tem como ser esquecida por um caminho de codigo novo.</p>
     */
    private void propagarParaOPar(Lancamento l) {
        if (!l.ehPernaDeTransferencia()) {
            return;
        }
        lancamentos.findById(l.getLancamentoParId()).ifPresent(par -> {
            par.alterarValor(l.getValor());
            par.reagendar(l.getDataCaixa(), l.getDataCaixa());
            par.corrigirSituacao(l.getSituacao());
            par.ajustarCompetencia(l.getDataCompetencia());
        });
    }

    /**
     * Confere o vinculo de B-D2 antes de ir ao banco.
     *
     * <p>A chave composta {@code (ambiente_id, conta_id) -> conta_ambiente} ja
     * recusaria a linha. O que se ganha aqui e a <b>frase</b>: a constraint
     * diria apenas que uma restricao falhou, e a tela nao teria o que
     * mostrar.</p>
     */
    private void exigirContaNoAmbiente(UUID ambienteId, UUID contaId) {
        if (vinculos.findById(new ContaAmbiente.Chave(contaId, ambienteId)).isEmpty()) {
            throw new OperacaoNaoPermitida(
                "Conta nao pertence ao ambiente ativo. Compartilhe a conta com este"
                    + " ambiente ou troque de ambiente antes de lancar.");
        }
    }

    private Categoria exigirCategoria(UUID ambienteId, UUID categoriaId) {
        return categorias.findById(categoriaId)
            .filter(c -> c.getAmbienteId().equals(ambienteId))
            .orElseThrow(() -> new RecursoNaoEncontrado("Categoria nao encontrada"));
    }

    private Subcategoria buscarSubcategoria(UUID ambienteId, UUID subcategoriaId) {
        if (subcategoriaId == null) {
            return null;
        }
        return subcategorias.findById(subcategoriaId)
            .filter(s -> s.getAmbienteId().equals(ambienteId))
            .orElseThrow(() -> new RecursoNaoEncontrado("Subcategoria nao encontrada"));
    }

    /** Mesmo recorte de B-D21: id de outro ambiente e 404. */
    private Lancamento exigir(UUID ambienteId, UUID id) {
        return lancamentos.findById(id)
            .filter(l -> l.getAmbienteId().equals(ambienteId))
            .orElseThrow(() -> new RecursoNaoEncontrado("Lancamento nao encontrado"));
    }

    // =========================================================================

    /**
     * Resolve conta, categoria e subcategoria da lista inteira em tres
     * consultas — nao tres por linha.
     */
    private List<Item> comNomes(List<Lancamento> lista) {
        if (lista.isEmpty()) {
            return List.of();
        }

        Map<UUID, Conta> porConta =
            indexar(contas.findAllById(idsDe(lista, Lancamento::getContaId)), Conta::getId);

        Map<UUID, Categoria> porCategoria =
            indexar(categorias.findAllById(idsDe(lista, Lancamento::getCategoriaId)),
                    Categoria::getId);

        Map<UUID, Subcategoria> porSubcategoria =
            indexar(subcategorias.findAllById(idsDe(lista, Lancamento::getSubcategoriaId)),
                    Subcategoria::getId);

        return lista.stream()
            .map(l -> new Item(
                l,
                porConta.get(l.getContaId()),
                porCategoria.get(l.getCategoriaId()),
                l.getSubcategoriaId() == null ? null : porSubcategoria.get(l.getSubcategoriaId())))
            .toList();
    }

    /** Ids distintos, sem nulos — subcategoria e opcional (F11). */
    private static Set<UUID> idsDe(List<Lancamento> lista, Function<Lancamento, UUID> campo) {
        return lista.stream()
            .map(campo)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static <T> Map<UUID, T> indexar(List<T> itens, Function<T, UUID> chave) {
        return itens.stream().collect(Collectors.toMap(chave, Function.identity()));
    }

    /**
     * O que o corpo do POST e do PUT carregam.
     *
     * <p>Um record em vez de doze parametros: com quatro UUIDs seguidos na
     * assinatura, trocar dois de lugar compila e grava o lancamento na conta
     * errada.</p>
     */
    public record Dados(
        UUID contaId,
        UUID categoriaId,
        UUID subcategoriaId,
        TipoLancamento tipoDeclarado,
        BigDecimal valor,
        LocalDate dataCaixa,
        LocalDate dataCompetencia,
        String descricao,
        String observacao,
        UUID responsavelId,
        SituacaoLancamento situacao,

        /**
         * Como foi pago (V11). Nulo no POST faz cair no padrao da conta; nulo
         * no PUT limpa o campo. Ver {@code resolverFormaDePagamento}.
         */
        FormaPagamento formaPagamento
    ) {}

    /** Um lancamento com o que a T-08 precisa mostrar junto dele. */
    public record Item(Lancamento lancamento, Conta conta,
                       Categoria categoria, Subcategoria subcategoria) {
    }
}
