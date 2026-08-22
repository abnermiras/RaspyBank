package com.raspybank.app.servico;

import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Escreve uma planilha {@code .xlsx} em streaming, direto num
 * {@link OutputStream}.
 *
 * <h3>O que esta classe NAO sabe</h3>
 *
 * <p>Nao sabe o que e dinheiro, o que e lancamento, o que e ambiente. Recebe
 * abas, cabecalhos e celulas ja tipadas — texto, numero, data — e escreve. E
 * essa ignorancia que justifica ela existir separada em vez de virar tres
 * metodos privados do montador: o dia em que houver um segundo relatorio, ou em
 * que o transporte virar fila, esta classe nao muda uma linha.</p>
 *
 * <h3>Por que streaming, e nao "monta e devolve os bytes"</h3>
 *
 * <p>O alvo e um Raspberry Pi 4 com {@code -XX:+UseSerialGC} e heap de ~1,1 GB.
 * O {@code fastexcel} escreve cada linha no fluxo e nao mantem a planilha em
 * memoria — desde que ninguem peca estilo sobre uma linha ja despachada. Por
 * isso o estilo aqui e sempre por celula, na hora em que ela e escrita, e
 * {@code freezePane} e {@code width} sao chamados antes do primeiro
 * {@code flush()}: depois dele o preambulo da aba ja foi para o fluxo.</p>
 *
 * <h3>A unica opiniao do escritor</h3>
 *
 * <p>Aba <b>com</b> cabecalho ganha linha de titulo em negrito, painel
 * congelado e {@code AutoFilter}. Aba <b>sem</b> cabecalho — a capa, por
 * exemplo — nao ganha nenhum dos tres, porque filtro sem coluna nomeada nao
 * filtra nada e painel congelado sem tabela e ruido. E a regra inteira; nao ha
 * bandeira a passar.</p>
 *
 * <h3>A regra do nome de aba mora aqui, e ele deixou de descartar em silencio</h3>
 *
 * <p>O que <b>pode</b> virar nome de aba e propriedade do formato, e portanto
 * desta classe: 31 caracteres, sem {@code [ ] : * ? / \}, sem apostrofo nas
 * pontas, sem nada que o XML nao aceite, e unico sem distinguir caixa. Quem
 * escolhe o nome — de onde ele vem, o que fazer quando dois colidem — continua
 * sendo de quem chama; ver {@link #nomeDeAbaSaneado(String)}.</p>
 *
 * <p>A separacao nasceu de um defeito. O {@code fastexcel} <b>descarta</b> em
 * silencio qualquer caractere que o XML nao aceite — meio par surrogate, por
 * exemplo, que e o que sobra de um emoji cortado ao meio nos 31 caracteres.
 * Quem chamava desempatava sobre uma string que nao era a que chegava no
 * arquivo: dois nomes diferentes ali viravam o mesmo {@code <sheet name="…">}
 * aqui, e uma aba levava a outra embora sem nenhum aviso. Por isso
 * {@link #escrever} agora <b>recusa</b> o que nao pode escrever fielmente, em
 * vez de escrever outra coisa: se o nome que chega nao e identico ao que
 * {@code nomeDeAbaSaneado} devolveria, ou se dois nomes colidem, o arquivo nem
 * comeca. Errar alto antes do primeiro byte e melhor que entregar um
 * {@code .xlsx} com um ambiente a menos.</p>
 */
@Component
public class EscritorXlsx {

    /** Depois de tantas linhas, o que ja foi escrito vai para o fluxo e sai da memoria. */
    private static final int LINHAS_POR_DESPACHO = 500;

    private static final String APLICACAO = "RaspyBank";

    /**
     * O teto do formato para nome de aba.
     *
     * <p>Contado em {@code char} do Java, e nao em ponto de codigo: o limite do
     * OOXML e de caracteres, mas leitoras contam unidades UTF-16, e das duas
     * medidas esta e a menor. Um nome que passa aqui passa nas duas.</p>
     */
    public static final int LIMITE_NOME_DE_ABA = 31;

    /** Proibidos dentro de um nome de aba pelo proprio formato. */
    private static final String PROIBIDOS_EM_NOME_DE_ABA = "[\\[\\]:*?/\\\\]";

    // =========================================================================
    // O que se entrega ao escritor
    // =========================================================================

    /**
     * Uma celula tipada.
     *
     * <p>Tipada e nao {@code String} porque e exatamente essa a diferenca entre
     * uma planilha que soma e uma que nao soma: numero escrito como texto nao
     * entra em {@code SOMA()}, e data escrita como texto ordena alfabeticamente
     * — 01/12 antes de 02/01.</p>
     */
    public sealed interface Celula {

        /** Ausencia. Nao escreve nada — celula vazia mesmo, sem placeholder. */
        Celula VAZIA = new Vazia();

        record Vazia() implements Celula {}

        record Texto(String valor) implements Celula {}

        /**
         * @param formato mascara de exibicao do Excel ({@code "#,##0.00"}). Quem
         *                chama e que sabe o que aquele numero significa; o
         *                escritor so a repassa
         */
        record Numero(BigDecimal valor, String formato) implements Celula {}

        record Data(LocalDate valor, String formato) implements Celula {}

        static Celula texto(String valor) {
            return valor == null || valor.isBlank() ? VAZIA : new Texto(valor);
        }

        static Celula numero(BigDecimal valor, String formato) {
            return valor == null ? VAZIA : new Numero(valor, formato);
        }

        static Celula data(LocalDate valor, String formato) {
            return valor == null ? VAZIA : new Data(valor, formato);
        }
    }

    /** Recebe uma linha por vez. */
    @FunctionalInterface
    public interface Destino {
        void linha(List<Celula> celulas) throws IOException;
    }

    /**
     * Produz as linhas de uma aba, uma por vez.
     *
     * <p>Callback e nao {@code List<List<Celula>>} de proposito: e o que permite
     * a quem chama gerar a linha no momento em que ela e escrita, sem montar o
     * conteudo inteiro do arquivo antes de comecar.</p>
     */
    @FunctionalInterface
    public interface Linhas {
        void produzir(Destino destino) throws IOException;
    }

    /**
     * @param nome      ja passado por {@link #nomeDeAbaSaneado(String)} e unico
     *                  entre as abas, sem distinguir caixa. O escritor nao
     *                  inventa nome nem resolve colisao — quem sabe de onde o
     *                  nome veio e que sabe como desempatar —, mas <b>confere</b>
     *                  as duas coisas e recusa a planilha inteira se faltarem
     * @param cabecalho vazio para aba de texto corrido; preenchido para tabela
     * @param larguras  largura de cada coluna, na mesma ordem. Pode ser vazia
     */
    public record Aba(String nome, List<String> cabecalho, List<Double> larguras, Linhas linhas) {

        /** Tabela: cabecalho em negrito, painel congelado e AutoFilter. */
        public static Aba tabela(String nome, List<String> cabecalho,
                                 List<Double> larguras, Linhas linhas) {
            return new Aba(nome, List.copyOf(cabecalho), List.copyOf(larguras), linhas);
        }

        /** Texto corrido: sem cabecalho, sem filtro, sem painel congelado. */
        public static Aba texto(String nome, double largura, Linhas linhas) {
            return new Aba(nome, List.of(), List.of(largura), linhas);
        }
    }

    // =========================================================================

    /**
     * Escreve as abas, na ordem em que vierem, e fecha a planilha.
     *
     * <p>Nao fecha o {@code saida}: quem o abriu e que sabe se ele pode ser
     * fechado — numa resposta HTTP, nao pode.</p>
     */
    public void escrever(OutputStream saida, List<Aba> abas) throws IOException {

        if (abas.isEmpty()) {
            // O fastexcel recusa planilha sem aba, e com razao: o arquivo nao
            // abriria. Quem chama e que sabe qual aba faltou.
            throw new IllegalArgumentException("Uma planilha precisa de pelo menos uma aba");
        }

        conferirNomes(abas);

        Workbook planilha = new Workbook(saida, APLICACAO, null);
        for (Aba aba : abas) {
            escreverAba(planilha, aba);
        }
        planilha.finish();
    }

    // =========================================================================
    // O nome de aba: o que o formato aceita, e a conferencia de que aceita
    // =========================================================================

    /**
     * Confere todos os nomes <b>antes</b> do primeiro byte.
     *
     * <p>Antes, e nao aba a aba, porque a resposta HTTP ja esta aberta: estourar
     * na quarta aba entregaria meio arquivo com cabecalho de {@code .xlsx} e
     * corpo truncado, que e pior de diagnosticar do que um 500 limpo.</p>
     */
    private static void conferirNomes(List<Aba> abas) {

        Set<String> usados = new HashSet<>();
        for (Aba aba : abas) {
            String nome = aba.nome();

            if (nome == null || nome.isBlank()) {
                throw new IllegalArgumentException("Aba sem nome");
            }

            // O nome tem de ser identico ao que o formato consegue gravar. Se
            // for so parecido, o que sai no arquivo nao e o que quem chamou
            // acha que saiu — e foi exatamente assim que duas abas viraram uma.
            String saneado = nomeDeAbaSaneado(nome);
            if (!nome.equals(saneado)) {
                throw new IllegalArgumentException(
                    "Nome de aba que o formato nao grava como veio: \"" + nome
                        + "\" seria escrito como \"" + saneado
                        + "\". Sanee com EscritorXlsx.nomeDeAbaSaneado antes de desempatar.");
            }

            if (!usados.add(nome.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                    "Duas abas com o nome \"" + nome + "\" (o formato ignora a caixa):"
                        + " o arquivo nao abriria, ou uma aba levaria a outra em silencio");
            }
        }
    }

    /**
     * O nome bruto reduzido ao que o formato realmente grava.
     *
     * <p>Idempotente de proposito: {@code sanear(sanear(x))} e {@code sanear(x)}.
     * E o que permite a {@link #conferirNomes} decidir por igualdade — e o que
     * permite a quem chama guardar o resultado num conjunto de desempate sabendo
     * que aquela string, e nao outra, e a que vai para o arquivo.</p>
     *
     * <p>Na ordem: o que o XML nao aceita e o que o formato proibe viram espaco,
     * espacos colapsam, corta-se em {@value #LIMITE_NOME_DE_ABA} <b>sem partir
     * par surrogate</b>, e o apostrofo das pontas sai (algumas leitoras recusam
     * a pasta por causa dele).</p>
     *
     * <p>Pode devolver vazio — "///" nao tem nada que sobreviva. Vazio nao e
     * nome de aba valido, e {@link #escrever} o recusa: inventar um substituto
     * aqui seria o escritor batizando aba, que e decisao de quem chama.</p>
     */
    public static String nomeDeAbaSaneado(String bruto) {

        String limpo = semOQueOXmlNaoAceita(bruto == null ? "" : bruto)
            .replaceAll(PROIBIDOS_EM_NOME_DE_ABA, " ")
            .replaceAll("\\s+", " ")
            .trim();

        limpo = cortadoParaNomeDeAba(limpo, LIMITE_NOME_DE_ABA);

        // Depois do corte, e nao antes: o corte pode ter deixado um apostrofo
        // novo na ponta. Espaco e apostrofo saem na MESMA passagem porque
        // "'' 'Casa" precisaria de duas se saissem em passagens separadas — e
        // duas passagens e o mesmo que dizer que o metodo nao e idempotente.
        return limpo.replaceAll("^[\\s']+|[\\s']+$", "");
    }

    /**
     * Corta em {@code limite} {@code char}, recuando quando o corte cairia no
     * meio de um par surrogate.
     *
     * <p>Um emoji sao dois {@code char} em Java. Metade de um par nao e
     * caractere XML valido: o {@code fastexcel} o descarta sem avisar, e o nome
     * que chega no arquivo fica um caractere menor do que quem cortou
     * imaginava.</p>
     */
    public static String cortadoParaNomeDeAba(String nome, int limite) {

        if (nome.length() <= limite) {
            return nome.trim();
        }
        // Se o ultimo char que caberia e um high surrogate, o par comeca aqui e
        // termina fora: leva-se o par inteiro embora, nao a metade dele.
        int fim = Character.isHighSurrogate(nome.charAt(limite - 1)) ? limite - 1 : limite;
        return nome.substring(0, fim).trim();
    }

    /**
     * Descarta o que o XML 1.0 nao admite, trocando por espaco.
     *
     * <p>Sao os caracteres de controle, os nao-caracteres e — o caso que custou
     * caro — o <b>surrogate solto</b>, que aparece sempre que alguem corta uma
     * string Java no meio de um emoji. Percorre por ponto de codigo: um par bem
     * formado passa inteiro, um surrogate sem par cai na peneira.</p>
     *
     * <p>Espaco e nao remocao para que dois nomes que so diferiam num caractere
     * descartado continuem visivelmente parecidos — e, quando ainda assim
     * colidirem, quem desempata agora ve a colisao, porque ve o texto final.</p>
     */
    private static String semOQueOXmlNaoAceita(String texto) {

        StringBuilder saida = new StringBuilder(texto.length());
        int i = 0;
        while (i < texto.length()) {
            int ponto = texto.codePointAt(i);
            int passo = Character.charCount(ponto);

            // codePointAt devolve o proprio surrogate quando ele esta sozinho —
            // e o intervalo D800..DFFF nao passa em validoEmXml.
            if (validoEmXml(ponto)) {
                saida.appendCodePoint(ponto);
            } else {
                saida.append(' ');
            }
            i += passo;
        }
        return saida.toString();
    }

    /** Char permitido pela producao {@code Char} do XML 1.0. */
    private static boolean validoEmXml(int ponto) {
        return ponto == 0x9 || ponto == 0xA || ponto == 0xD
            || (ponto >= 0x20 && ponto <= 0xD7FF)
            || (ponto >= 0xE000 && ponto <= 0xFFFD)
            || (ponto >= 0x10000 && ponto <= 0x10FFFF);
    }

    // =========================================================================

    private void escreverAba(Workbook planilha, Aba aba) throws IOException {

        Worksheet folha = planilha.newWorksheet(aba.nome());

        // Antes de qualquer linha: depois do primeiro despacho o preambulo da
        // aba ja esta no fluxo e estas duas chamadas nao teriam mais efeito.
        for (int coluna = 0; coluna < aba.larguras().size(); coluna++) {
            folha.width(coluna, aba.larguras().get(coluna));
        }

        boolean temCabecalho = !aba.cabecalho().isEmpty();
        if (temCabecalho) {
            folha.freezePane(0, 1);
            for (int coluna = 0; coluna < aba.cabecalho().size(); coluna++) {
                folha.value(0, coluna, aba.cabecalho().get(coluna));
                folha.style(0, coluna).bold().set();
            }
        }

        // Array de um elemento porque o lambda precisa mexer no contador, e
        // variavel local capturada tem de ser efetivamente final.
        int[] proxima = { temCabecalho ? 1 : 0 };

        aba.linhas().produzir(celulas -> {
            escreverLinha(folha, proxima[0]++, celulas);
            if (proxima[0] % LINHAS_POR_DESPACHO == 0) {
                folha.flush();
            }
        });

        if (temCabecalho) {
            // Sobre o cabecalho ate a ultima linha escrita. Numa aba vazia, so o
            // cabecalho — e o filtro continua valido, mostrando que nao ha nada
            // ali em vez de sugerir que a aba veio quebrada.
            folha.setAutoFilter(0, 0,
                Math.max(0, proxima[0] - 1), aba.cabecalho().size() - 1);
        }

        folha.finish();
    }

    private void escreverLinha(Worksheet folha, int linha, List<Celula> celulas) {
        for (int coluna = 0; coluna < celulas.size(); coluna++) {
            escreverCelula(folha, linha, coluna, celulas.get(coluna));
        }
    }

    private void escreverCelula(Worksheet folha, int linha, int coluna, Celula celula) {
        switch (celula) {
            case Celula.Vazia ignorada -> {
                // Nada. Celula ausente e celula ausente.
            }
            case Celula.Texto t -> folha.value(linha, coluna, t.valor());
            case Celula.Numero n -> {
                folha.value(linha, coluna, n.valor());
                folha.style(linha, coluna).format(n.formato()).set();
            }
            case Celula.Data d -> {
                folha.value(linha, coluna, d.valor());
                folha.style(linha, coluna).format(d.formato()).set();
            }
        }
    }
}
