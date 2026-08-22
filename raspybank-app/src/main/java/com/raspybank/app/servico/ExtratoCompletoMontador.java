package com.raspybank.app.servico;

import com.raspybank.ambiente.dominio.Ambiente;
import com.raspybank.ambiente.servico.AmbienteServico;
import com.raspybank.app.web.FormaPagamentoControlador;
import com.raspybank.lancamento.dominio.FormaPagamento;
import com.raspybank.lancamento.dominio.SituacaoLancamento;
import com.raspybank.lancamento.dominio.TipoCartaoEmitido;
import com.raspybank.lancamento.dominio.TipoLancamento;
import com.raspybank.lancamento.servico.SituacaoServico;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Monta o extrato completo em {@code .xlsx} — a T-10 (B-D116 a B-D119).
 *
 * <h3>Por que esta classe vive no modulo de montagem</h3>
 *
 * <p>Ela precisa de {@link AmbienteServico} para nomear as abas e de
 * {@link SituacaoServico} para acertar o que ja aconteceu — e
 * {@code raspybank-lancamento} nao pode importar o contexto de ambiente. A
 * costura entre contextos e o que {@code raspybank-app} existe para fazer, e e
 * o mesmo motivo de {@code RelatorioControlador} e de {@code OnboardingServico}
 * morarem aqui.</p>
 *
 * <p>Nao ha modulo {@code raspybank-relatorio}: sem fila nao ha ciclo de vida de
 * relatorio (pedido, estado, expiracao), e um modulo contendo so um escritor
 * seria camada tecnica — o oposto da divisao por contexto de negocio.</p>
 *
 * <h3>O arquivo atravessa ambientes, e e a unica leitura que atravessa</h3>
 *
 * <p>B-D117. B-D111 — o escopo segue o ambiente ativo — continua valendo para
 * toda tela; o arquivo e o retrato da pessoa e nao o da tela aberta. A
 * separacao das vidas se mantem <b>dentro</b> do arquivo, uma aba por
 * ambiente.</p>
 *
 * <h3>A parte cara, e por que ela e chamada uma vez por ambiente</h3>
 *
 * <p>{@code SituacaoServico.sincronizar} escreve. Sobre {@code lancamento}
 * existem dois gatilhos {@code AFTER ... FOR EACH ROW} desde a V10 — auditoria,
 * que grava {@code to_jsonb(OLD)} <b>e</b> {@code to_jsonb(NEW)}, e outbox, que
 * grava a linha inteira. Cada linha que muda de situacao custa dois INSERTs
 * alem do UPDATE. Chamar por conta, ou por fatura, multiplicaria isso pelo
 * numero de contas — e e a unica forma real de deixar este relatorio lento. Uma
 * chamada por ambiente, e a guarda {@code AND l.situacao <> :alvo} do
 * repositorio mantem o custo perto de zero em regime.</p>
 *
 * <h3>Nenhum total e escrito no arquivo</h3>
 *
 * <p>P1. O lancamento e a fonte unica; a soma quem faz e o Excel, com a coluna
 * de valor que este montador escreve <b>com sinal</b>. Escrever um total aqui
 * criaria um numero para reconciliar com as linhas logo acima dele.</p>
 */
@Service
public class ExtratoCompletoMontador {

    // =========================================================================
    // A aba, desempacotada da T-08 (B-D119)
    // =========================================================================

    /**
     * As colunas, na ordem.
     *
     * <p>Sao as da T-08, com as tres celulas que empacotam mais de um fato
     * abertas em colunas proprias: a etiqueta {@code previsto} sai de dentro de
     * Descricao e vira <b>Situacao</b>, a etiqueta {@code 3/12} sai de dentro de
     * Pago com e vira <b>Parcela</b>, e o {@code +}/{@code −} sai de dentro de
     * Valor e vira <b>Tipo</b>. Mais <b>Quem</b>, que a tela nao tem porque a
     * tela nunca mostra linha alheia.</p>
     *
     * <p>A etiqueta {@code transferencia} da tela nao virou coluna: e
     * redundante, porque a categoria sistemica {@code TRANSFERENCIA} ja isola
     * essas linhas no filtro da coluna Categoria.</p>
     */
    private static final List<String> CABECALHO = List.of(
        "Data", "Descrição", "Situação", "Categoria", "Subcategoria",
        "Conta", "Pago com", "Parcela", "Tipo", "Valor", "Quem");

    private static final List<Double> LARGURAS = List.of(
        12.0, 42.0, 12.0, 22.0, 22.0, 24.0, 26.0, 9.0, 10.0, 14.0, 20.0);

    /** Duas casas e separador de milhar; o sinal vem do numero, nao da mascara. */
    private static final String FORMATO_DINHEIRO = "#,##0.00";

    /** Data de tela e data local (B-D114): sem hora, sem fuso. */
    private static final String FORMATO_DATA = "dd/mm/yyyy";

    private static final String NOME_DA_CAPA = "Sobre este arquivo";

    /**
     * Quando nada do nome do ambiente sobrevive ao saneamento.
     *
     * <p>Um ambiente chamado "///" existe: o campo aceita, e a aba dele nao pode
     * simplesmente sumir. O desempate cuida do segundo "///".</p>
     */
    private static final String NOME_DE_ABA_SEM_NOME = "Ambiente";

    private static final DateTimeFormatter DIA =
        DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final DateTimeFormatter INSTANTE =
        DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm");

    // =========================================================================

    private final AmbienteServico ambientes;
    private final SituacaoServico situacoes;
    private final EscritorXlsx escritor;

    @PersistenceContext
    private EntityManager em;

    public ExtratoCompletoMontador(AmbienteServico ambientes,
                                   SituacaoServico situacoes,
                                   EscritorXlsx escritor) {
        this.ambientes = ambientes;
        this.situacoes = situacoes;
        this.escritor = escritor;
    }

    /**
     * Escreve o extrato completo do usuario da sessao no fluxo recebido.
     *
     * <p>O fluxo e escrito e nao fechado — numa resposta HTTP quem fecha e o
     * container. O teto de 12 meses nao e conferido aqui: e validacao de
     * entrada, e mora na borda HTTP, junto das outras mensagens de erro do
     * projeto.</p>
     *
     * <p>Transacao de leitura, e ela cobre tambem a escrita no fluxo. E o preco
     * de nao materializar o arquivo: a conexao fica presa enquanto os bytes
     * saem. Se o cliente desistir no meio, a transacao cai — e nao ha o que
     * lamentar, porque nada foi gravado por esta transacao.
     * {@code sincronizar} e {@code REQUIRES_NEW} e ja commitou por si.</p>
     *
     * @param inicio primeiro dia da faixa, inclusive, por {@code data_caixa}
     * @param fim    ultimo dia da faixa, inclusive
     * @param saida  para onde a planilha e escrita
     */
    @Transactional(readOnly = true)
    public void montar(LocalDate inicio, LocalDate fim, OutputStream saida) throws IOException {

        List<Ambiente> meus = ambientes.listarDoUsuario().stream()
            // Ordem estavel: sem ela, duas geracoes identicas trocariam as abas
            // de lugar e o sufixo de desempate cairia num ambiente diferente.
            .sorted(Comparator
                .comparing(Ambiente::getNome, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(a -> a.getId().toString()))
            .toList();

        // Uma vez por ambiente. Ver o javadoc da classe antes de mover isto
        // para dentro de um laco de conta ou de fatura.
        //
        // ESTE LACO SO FUNCIONA SOB READ COMMITTED, e isso e uma premissa que
        // nada no projeto declara: nenhum arquivo configura nivel de
        // isolamento, entao vale o padrao do PostgreSQL, que e READ COMMITTED.
        //
        // A cadeia importa. Esta transacao (readOnly) foi aberta ANTES do laco;
        // cada `sincronizar` e REQUIRES_NEW, abre transacao propria e commita;
        // e o SELECT de `consultar` acontece DEPOIS, dentro da transacao de
        // fora. Sob READ COMMITTED cada statement pega um snapshot novo, entao
        // aquele SELECT enxerga o que os commits internos gravaram.
        //
        // Sob REPEATABLE READ o snapshot seria o da abertura da transacao de
        // fora, e a sincronizacao inteira seria DESCARTADA EM SILENCIO: o
        // arquivo sairia com situacao velha — previsto vencido ainda como
        // previsto —, sem erro, sem log e sem teste vermelho. Quem mexer em
        // isolamento globalmente precisa reler este laco.
        LocalDate hoje = LocalDate.now();
        for (Ambiente ambiente : meus) {
            situacoes.sincronizar(ambiente.getId(), hoje);
        }

        // Uma consulta so, para todos os ambientes e todas as contas.
        Map<UUID, List<Linha>> porAba = agrupar(consultar(inicio, fim));

        List<Aba> abas = planejarAbas(meus, porAba);

        List<EscritorXlsx.Aba> paraEscrever = new ArrayList<>();
        paraEscrever.add(capa(inicio, fim, abas));
        for (Aba aba : abas) {
            List<Linha> linhas = porAba.getOrDefault(aba.ambienteId(), List.of());
            paraEscrever.add(EscritorXlsx.Aba.tabela(
                aba.nome(), CABECALHO, LARGURAS,
                destino -> {
                    for (Linha linha : linhas) {
                        destino.linha(celulasDe(linha));
                    }
                }));
        }

        escritor.escrever(saida, paraEscrever);
    }

    // =========================================================================
    // Leitura
    // =========================================================================

    /**
     * Uma chamada a {@code app_extrato_completo}, e nao uma por conta.
     *
     * <p>A funcao e {@code SECURITY DEFINER} porque a politica de
     * {@code lancamento} corretamente esconde a linha da outra pessoa numa conta
     * ou num plastico dividido — e sem ela a soma do arquivo divergiria da T-05,
     * na mesma conta. Nao ha porteiro a escrever: os dois parametros sao datas e
     * nao carregam autorizacao nenhuma; tudo o mais deriva de
     * {@code app_usuario_id()}.</p>
     *
     * <p>A mascara de B-D89/B-D109 chega pronta do banco: {@code descricao},
     * {@code categoria_nome} e {@code subcategoria_nome} vem nulas na linha
     * alheia. O que a aplicacao nunca recebeu, ela nao vaza — e por isso aqui
     * nao ha um {@code if} sobre {@code meu}.</p>
     */
    @SuppressWarnings("unchecked")
    private List<Linha> consultar(LocalDate inicio, LocalDate fim) {

        List<Object[]> cruas = em.createNativeQuery("""
                SELECT ambiente_da_aba, meu, data_caixa, descricao, situacao,
                       categoria_nome, subcategoria_nome, conta_nome,
                       cartao_nome, plastico_tipo, plastico_final, forma_pagamento,
                       parcela_numero, parcela_total, tipo, valor, quem_nome
                  FROM app_extrato_completo(:inicio, :fim)
                """)
            .setParameter("inicio", inicio)
            .setParameter("fim", fim)
            .getResultList();

        // Sem reordenar: a funcao ja devolve data_caixa DESC, criado_em DESC, e
        // o segundo criterio existe para que duas geracoes identicas produzam
        // arquivos identicos. Ordenar de novo aqui perderia o desempate.
        return cruas.stream().map(ExtratoCompletoMontador::linhaDe).toList();
    }

    private static Linha linhaDe(Object[] c) {
        return new Linha(
            (UUID) c[0],
            (Boolean) c[1],
            ((java.sql.Date) c[2]).toLocalDate(),
            (String) c[3],
            enumDe(SituacaoLancamento.class, (String) c[4]),
            (String) c[5],
            (String) c[6],
            (String) c[7],
            (String) c[8],
            enumDe(TipoCartaoEmitido.class, (String) c[9]),
            (String) c[10],
            enumDe(FormaPagamento.class, (String) c[11]),
            c[12] == null ? null : ((Number) c[12]).intValue(),
            c[13] == null ? null : ((Number) c[13]).intValue(),
            enumDe(TipoLancamento.class, (String) c[14]),
            (BigDecimal) c[15],
            (String) c[16]);
    }

    /**
     * P2: o valor no banco e o {@code name()} do enum, sem conversor.
     *
     * <p>Por isso {@code valueOf} direto: um valor que o Java nao conhece
     * estoura aqui, alto, em vez de virar celula em branco no meio de um arquivo
     * que a pessoa vai conferir contra o extrato do banco.</p>
     */
    private static <E extends Enum<E>> E enumDe(Class<E> tipo, String valor) {
        return valor == null ? null : Enum.valueOf(tipo, valor);
    }

    private static Map<UUID, List<Linha>> agrupar(List<Linha> linhas) {
        // LinkedHashMap preserva a ordem de chegada dentro de cada aba, que e a
        // ordem que a funcao ja garantiu.
        Map<UUID, List<Linha>> porAba = new LinkedHashMap<>();
        for (Linha linha : linhas) {
            porAba.computeIfAbsent(linha.ambienteDaAba(), k -> new ArrayList<>()).add(linha);
        }
        return porAba;
    }

    // =========================================================================
    // As celulas de uma linha
    // =========================================================================

    private static List<EscritorXlsx.Celula> celulasDe(Linha l) {
        return List.of(
            EscritorXlsx.Celula.data(l.data(), FORMATO_DATA),
            // Nulo na linha alheia, e fica vazio mesmo. Placeholder inventado
            // aqui ("(oculto)", "—") seria texto que ninguem consegue filtrar e
            // que sugere um dado que nao existe. Quem da sentido a linha
            // mascarada e a coluna Quem.
            EscritorXlsx.Celula.texto(l.descricao()),
            EscritorXlsx.Celula.texto(nome(l.situacao())),
            EscritorXlsx.Celula.texto(l.categoria()),
            EscritorXlsx.Celula.texto(l.subcategoria()),
            EscritorXlsx.Celula.texto(l.conta()),
            EscritorXlsx.Celula.texto(pagoCom(l)),
            EscritorXlsx.Celula.texto(parcela(l)),
            EscritorXlsx.Celula.texto(nome(l.tipo())),
            EscritorXlsx.Celula.numero(comSinal(l), FORMATO_DINHEIRO),
            EscritorXlsx.Celula.texto(l.quem()));
    }

    /**
     * O valor com sinal — <b>uma</b> coluna, e numero (B-D118).
     *
     * <p>O banco guarda positivo ({@code ck_lancamento_valor}) e a V22 devolve
     * positivo de proposito, para nao criar uma segunda representacao da mesma
     * saida. O sinal e derivado do {@code tipo} aqui, na hora de escrever a
     * celula, exatamente como {@code LinhaDoExtrato.valorComSinal()} faz para a
     * tela.</p>
     *
     * <p>Uma coluna de positivos somaria para um numero sem significado, e e
     * para somar que a planilha existe. Duas colunas de dinheiro so convidariam
     * a somar a errada; com a coluna Tipo ao lado, o valor absoluto se recupera
     * com {@code ABS()}.</p>
     */
    private static BigDecimal comSinal(Linha l) {
        if (l.valor() == null) {
            return null;
        }
        BigDecimal valor = l.valor().setScale(2, RoundingMode.HALF_UP);
        return l.tipo() == TipoLancamento.ENTRADA ? valor : valor.negate();
    }

    /**
     * "Pago com", montado aqui e nao no banco.
     *
     * <p>A V22 devolve os tres campos crus — nome do contrato, tipo do plastico
     * e final — porque montar a frase e formatacao, e formatacao e do Java.
     * Espelha a T-08: {@code fisico ····1234}, ou o rotulo da forma quando nao
     * houve cartao.</p>
     *
     * <p>O nome do cartao so entra quando diz algo que a coluna Conta ja nao
     * diga. Hoje ele nunca diz: {@code CartaoServico.alterar} mantem o nome da
     * conta igual ao do cartao de proposito, porque "sao a mesma coisa para quem
     * olha a T-05". A comparacao existe para o dia em que deixarem de ser.</p>
     *
     * <p>Vazio na perna de entrada do pagamento de fatura, que nao tem plastico
     * nem forma (B-D59) — e a tela mostra {@code —} no mesmo caso. Ali quem
     * explica a linha e a descricao, nao a coluna.</p>
     */
    private static String pagoCom(Linha l) {

        if (l.plasticoTipo() != null) {
            String plastico = (l.plasticoTipo() == TipoCartaoEmitido.FISICO ? "físico" : "virtual")
                + (l.plasticoFinal() == null ? "" : " ····" + l.plasticoFinal());

            return l.cartaoNome() == null || l.cartaoNome().equals(l.conta())
                ? plastico
                : l.cartaoNome() + " · " + plastico;
        }

        return l.formaPagamento() == null
            ? null
            : FormaPagamentoControlador.Item.nomeDe(l.formaPagamento());
    }

    /**
     * {@code 3/12}, e vazio quando nao e parcelada.
     *
     * <p>{@code comoParcela} recusa total menor que 2, entao a presenca do total
     * ja e a resposta — nao ha parcela "1/1" a filtrar.</p>
     */
    private static String parcela(Linha l) {
        return l.parcelaTotal() == null ? null : l.parcelaNumero() + "/" + l.parcelaTotal();
    }

    private static String nome(Enum<?> valor) {
        return valor == null ? null : valor.name();
    }

    // =========================================================================
    // As abas
    // =========================================================================

    /**
     * Uma aba por ambiente, inclusive os que nao tiveram lancamento na faixa.
     *
     * <p>A aba vazia e informacao: ela diz "neste ambiente nao houve nada no
     * periodo", que e diferente de "esqueci este ambiente".</p>
     */
    private static List<Aba> planejarAbas(List<Ambiente> meus, Map<UUID, List<Linha>> porAba) {

        Map<UUID, String> nomes = new LinkedHashMap<>();
        for (Ambiente ambiente : meus) {
            nomes.put(ambiente.getId(), ambiente.getNome());
        }

        // Uniao, e nao so a lista de ambientes: se a funcao devolvesse uma aba
        // que a listagem nao conhece, dropar as linhas em silencio seria perder
        // dinheiro do arquivo. As duas derivam de app_ambientes_do_usuario(),
        // entao isto nao deve acontecer — e por isso mesmo tem de fazer barulho
        // no nome da aba em vez de sumir.
        Set<UUID> todos = new LinkedHashSet<>(nomes.keySet());
        todos.addAll(porAba.keySet());

        Set<String> usados = new HashSet<>();
        usados.add(chave(NOME_DA_CAPA));

        List<Aba> abas = new ArrayList<>();
        for (UUID ambienteId : todos) {
            String bruto = nomes.getOrDefault(ambienteId,
                "Ambiente " + ambienteId.toString().substring(0, 8));
            abas.add(new Aba(ambienteId, unico(saneado(bruto), usados)));
        }
        return abas;
    }

    /**
     * O nome do ambiente cabendo num nome de aba.
     *
     * <p>A regra do que cabe — 31 caracteres, sem {@code [ ] : * ? / \}, sem
     * apostrofo nas pontas, sem nada que o XML recuse — e do formato, e por isso
     * mora no {@link EscritorXlsx}. Aqui fica so a parte que e deste relatorio:
     * o nome de quem ficou sem nenhum.</p>
     *
     * <p><b>Este metodo devolve o texto final</b>, identico ao que vai ser
     * gravado — e e por isso que ele roda ANTES do desempate. Enquanto o corte
     * era feito aqui e o descarte la, o {@code unico} comparava uma string que o
     * arquivo nunca veria: um emoji partido nos 31 {@code char} deixava meio par
     * surrogate na ponta, o escritor o descartava em silencio por nao ser XML
     * valido, e dois nomes diferentes no desempate viravam a mesma aba no
     * arquivo — com um ambiente inteiro sumindo do extrato sem aviso nenhum.</p>
     */
    private static String saneado(String nome) {
        String limpo = EscritorXlsx.nomeDeAbaSaneado(nome);
        return limpo.isEmpty() ? NOME_DE_ABA_SEM_NOME : limpo;
    }

    /**
     * Sufixo numerico quando dois nomes colidem <b>depois</b> do corte.
     *
     * <p>Dois ambientes chamados "Financas da familia Amaral de Sao Paulo" e
     * "Financas da familia Amaral de Curitiba" viram o mesmo nome aos 31
     * caracteres. Sem o sufixo, a segunda aba seria recusada pelo formato — ou,
     * pior, silenciosamente sobrescreveria a primeira.</p>
     */
    private static String unico(String base, Set<String> usados) {

        String candidato = base;
        int proximo = 2;

        while (!usados.add(chave(candidato))) {
            String sufixo = " (" + proximo++ + ")";
            // Pelo escritor, e nao por substring: abrir espaco para o sufixo e
            // outro corte, e um corte por char parte o par surrogate do mesmo
            // jeito que o dos 31 partia.
            String cabe = EscritorXlsx.cortadoParaNomeDeAba(
                base, EscritorXlsx.LIMITE_NOME_DE_ABA - sufixo.length());
            candidato = cabe + sufixo;
        }
        return candidato;
    }

    /** O formato compara nome de aba sem distinguir caixa. */
    private static String chave(String nome) {
        return nome.toLowerCase(Locale.ROOT);
    }

    // =========================================================================
    // A capa
    // =========================================================================

    /**
     * "Sobre este arquivo" — tres avisos, e nada de enfeite.
     *
     * <p>Os dois ultimos existem porque, sem eles, o arquivo parece quebrado
     * para quem o abre: a linha mascarada parece dado corrompido, e o total que
     * nao bate com o Mapa de Gastos parece erro de conta. Os dois estao certos,
     * e a capa e o unico lugar onde cabe dizer por que.</p>
     */
    private static EscritorXlsx.Aba capa(LocalDate inicio, LocalDate fim, List<Aba> abas) {

        List<String> avisos = List.of(
            "Extrato completo — RaspyBank",
            "",
            "Faixa pedida: " + DIA.format(inicio) + " a " + DIA.format(fim)
                + "  (por data de caixa, os dois dias inclusive)",
            "Gerado em: " + INSTANTE.format(LocalDateTime.now()),
            "Uma aba por ambiente: " + abas.stream().map(Aba::nome).reduce((a, b) -> a + ", " + b)
                .orElse("nenhum"),
            "Aba sem nenhuma linha quer dizer que não houve lançamento naquele ambiente no período.",
            "",
            "POR QUE EXISTEM LINHAS COM DESCRIÇÃO E CATEGORIA VAZIAS",
            "",
            "Elas não estão quebradas. São lançamentos de OUTRA PESSOA, numa conta ou num cartão",
            "dividido com você: descrição, categoria e subcategoria não saem do banco (B-D89/B-D109),",
            "porque texto livre é onde as pessoas escrevem o que não pretendiam dividir.",
            "A coluna Quem diz de quem é a linha. Data, Conta, Pago com, Tipo e Valor aparecem",
            "sempre — é o que faz a soma do arquivo fechar com o extrato do banco.",
            "",
            "POR QUE O TOTAL NÃO BATE COM O MAPA DE GASTOS",
            "",
            "Também é esperado, e as duas contas estão certas: elas respondem a perguntas diferentes.",
            "Aqui entra tudo o que moveu dinheiro. O Mapa de Gastos deixa de fora as categorias",
            "sistêmicas Transferência, Ajuste de saldo e Pagamento de fatura — porque nenhuma delas",
            "é gasto novo — e separa o que já aconteceu do que ainda vai acontecer.",
            "",
            "Para reproduzir o número do Mapa nesta planilha:",
            "  1. filtre FORA, na coluna Categoria, Transferência, Ajuste de saldo e Pagamento de fatura;",
            "  2. filtre FORA, na coluna Situação, o valor PREVISTO;",
            "  3. olhe uma aba de cada vez — o Mapa é sempre de um ambiente só.",
            "",
            "O filtro da linha 1 de cada aba já está ligado para isso.");

        return EscritorXlsx.Aba.texto(NOME_DA_CAPA, 100.0, destino -> {
            for (String aviso : avisos) {
                destino.linha(List.of(EscritorXlsx.Celula.texto(aviso)));
            }
        });
    }

    // =========================================================================

    /** Um ambiente e o nome ja saneado e unico da aba dele. */
    private record Aba(UUID ambienteId, String nome) {}

    /**
     * Uma linha como a V22 devolve.
     *
     * <p>{@code meu} e {@code ambienteDaAba} sao de montagem e nao viram coluna
     * do arquivo: a primeira diz por que a linha veio mascarada, a segunda diz
     * em que aba ela entra.</p>
     */
    private record Linha(
        UUID ambienteDaAba,
        boolean meu,
        LocalDate data,
        String descricao,
        SituacaoLancamento situacao,
        String categoria,
        String subcategoria,
        String conta,
        String cartaoNome,
        TipoCartaoEmitido plasticoTipo,
        String plasticoFinal,
        FormaPagamento formaPagamento,
        Integer parcelaNumero,
        Integer parcelaTotal,
        TipoLancamento tipo,
        BigDecimal valor,
        String quem
    ) {}
}
