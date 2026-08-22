package com.raspybank.integracao;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Le de volta o {@code .xlsx} que o {@code EscritorXlsx} produziu — so em
 * teste, e so com o JDK.
 *
 * <h3>Por que ler de volta, e nao conferir bytes</h3>
 *
 * <p>O que a T-10 promete nao esta nos bytes: esta em <b>tipo de celula</b>
 * (numero e nao texto, senao {@code SOMA()} devolve zero), em <b>ordem de
 * linha</b> e em <b>nome de aba</b>. Conferir um zip por substring provaria o
 * contrario do que se quer — {@code "-80.00"} aparece no XML tanto escrito como
 * numero quanto escrito como texto.</p>
 *
 * <h3>Por que sem Apache POI</h3>
 *
 * <p>A escolha do projeto por {@code fastexcel} foi para <b>nao</b> carregar o
 * POI (o alvo e um Pi com {@code -XX:+UseSerialGC}), e o POI nao chega como
 * transitiva de teste — o {@code dependency:tree} do modulo mostra apenas
 * {@code fastexcel} e {@code opczip}. Trazer o POI so para o teste seria
 * acrescentar a maior dependencia do build para conferir tres coisas que
 * {@code java.util.zip} + {@code javax.xml} ja respondem. E o teste continua
 * lendo o formato, nao a biblioteca que o escreveu.</p>
 *
 * <p>Sabe o suficiente do OOXML para o que a T-10 promete: nome e ordem das
 * abas, valor e <b>tipo</b> de cada celula, e o codigo de formato aplicado a
 * ela (que e o que distingue uma data de um numero qualquer).</p>
 */
final class LeitoraXlsx {

    /** O zero do serial de data do Excel — 1900 com o bug do ano bissexto embutido. */
    private static final LocalDate EPOCA = LocalDate.of(1899, 12, 30);

    private LeitoraXlsx() {}

    // =========================================================================

    enum Tipo { VAZIA, TEXTO, NUMERO }

    /**
     * Uma celula como ela esta gravada no arquivo.
     *
     * @param formato codigo de formato do Excel ({@code "dd/mm/yyyy"}), ou
     *                {@code "General"} quando nenhum foi aplicado
     */
    record Celula(Tipo tipo, String texto, BigDecimal numero, String formato) {

        static final Celula VAZIA = new Celula(Tipo.VAZIA, null, null, "General");

        boolean vazia() {
            return tipo == Tipo.VAZIA;
        }

        /** O texto, ou {@code null} quando a celula esta vazia. */
        String comoTexto() {
            return tipo == Tipo.TEXTO ? texto : null;
        }

        /** A data que o serial representa. Explode se a celula nao for numero. */
        LocalDate comoData() {
            if (tipo != Tipo.NUMERO) {
                throw new AssertionError(
                    "A celula nao e data de verdade: veio como " + tipo + " (" + texto + ")");
            }
            return EPOCA.plusDays(numero.longValue());
        }

        @Override
        public String toString() {
            return switch (tipo) {
                case VAZIA  -> "<vazia>";
                case TEXTO  -> "\"" + texto + "\"";
                case NUMERO -> numero + " [" + formato + "]";
            };
        }
    }

    /** Uma aba, com as linhas densas — buraco de linha ou de coluna vira celula vazia. */
    record Aba(String nome, List<List<Celula>> linhas) {

        Celula celula(int linha, int coluna) {
            if (linha >= linhas.size()) {
                return Celula.VAZIA;
            }
            List<Celula> l = linhas.get(linha);
            return coluna >= l.size() ? Celula.VAZIA : l.get(coluna);
        }

        /** O texto da celula, ou {@code null} — o atalho de quase toda asserção. */
        String texto(int linha, int coluna) {
            return celula(linha, coluna).comoTexto();
        }

        /** As linhas de dados, isto e, tudo menos o cabecalho. */
        List<List<Celula>> corpo() {
            return linhas.isEmpty() ? List.of() : linhas.subList(1, linhas.size());
        }
    }

    /** O arquivo inteiro. As abas ficam na ordem em que aparecem no arquivo. */
    record Planilha(List<Aba> abas) {

        List<String> nomes() {
            return abas.stream().map(Aba::nome).toList();
        }

        Aba aba(String nome) {
            return abas.stream()
                .filter(a -> a.nome().equals(nome))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "Aba \"" + nome + "\" nao existe. Existem: " + nomes()));
        }
    }

    // =========================================================================

    static Planilha ler(byte[] arquivo) {

        Map<String, byte[]> partes = descompactar(arquivo);

        List<String> textos = sharedStrings(partes.get("xl/sharedStrings.xml"));
        List<String> formatos = formatos(partes.get("xl/styles.xml"));
        Map<String, String> alvos = relacionamentos(partes.get("xl/_rels/workbook.xml.rels"));

        Document workbook = xml(exigir(partes, "xl/workbook.xml"));
        NodeList folhas = workbook.getElementsByTagName("sheet");

        List<Aba> abas = new ArrayList<>();
        for (int i = 0; i < folhas.getLength(); i++) {
            Element folha = (Element) folhas.item(i);
            String caminho = "xl/" + alvos.get(folha.getAttribute("r:id"));
            abas.add(new Aba(folha.getAttribute("name"),
                linhasDe(xml(exigir(partes, caminho)), textos, formatos)));
        }
        return new Planilha(List.copyOf(abas));
    }

    // =========================================================================

    private static List<List<Celula>> linhasDe(Document folha,
                                               List<String> textos,
                                               List<String> formatos) {

        // Esparso primeiro, denso depois: o OOXML omite linha e coluna vazias,
        // e um teste que contasse celulas presentes leria a coluna errada.
        Map<Integer, Map<Integer, Celula>> esparso = new HashMap<>();
        int ultimaLinha = -1;
        int ultimaColuna = -1;

        NodeList celulas = folha.getElementsByTagName("c");
        for (int i = 0; i < celulas.getLength(); i++) {
            Element c = (Element) celulas.item(i);

            String referencia = c.getAttribute("r");
            int linha = linhaDe(referencia);
            int coluna = colunaDe(referencia);
            ultimaLinha = Math.max(ultimaLinha, linha);
            ultimaColuna = Math.max(ultimaColuna, coluna);

            esparso.computeIfAbsent(linha, k -> new HashMap<>())
                .put(coluna, celulaDe(c, textos, formatos));
        }

        List<List<Celula>> densas = new ArrayList<>();
        for (int linha = 0; linha <= ultimaLinha; linha++) {
            Map<Integer, Celula> daLinha = esparso.getOrDefault(linha, Map.of());
            List<Celula> colunas = new ArrayList<>();
            for (int coluna = 0; coluna <= ultimaColuna; coluna++) {
                colunas.add(daLinha.getOrDefault(coluna, Celula.VAZIA));
            }
            densas.add(List.copyOf(colunas));
        }
        return List.copyOf(densas);
    }

    private static Celula celulaDe(Element c, List<String> textos, List<String> formatos) {

        String tipo = c.getAttribute("t");
        String estilo = c.getAttribute("s");
        String formato = estilo.isEmpty() ? "General" : formatos.get(Integer.parseInt(estilo));

        String bruto = primeiroTexto(c, "v");

        if ("s".equals(tipo)) {
            return new Celula(Tipo.TEXTO, textos.get(Integer.parseInt(bruto)), null, formato);
        }
        if ("inlineStr".equals(tipo)) {
            return new Celula(Tipo.TEXTO, primeiroTexto(c, "t"), null, formato);
        }
        if ("str".equals(tipo)) {
            return new Celula(Tipo.TEXTO, bruto, null, formato);
        }
        // Sem t, ou t="n": numero. E o caso que interessa — e o unico que
        // SOMA() enxerga.
        return bruto == null || bruto.isBlank()
            ? Celula.VAZIA
            : new Celula(Tipo.NUMERO, bruto, new BigDecimal(bruto), formato);
    }

    private static String primeiroTexto(Element pai, String etiqueta) {
        NodeList filhos = pai.getElementsByTagName(etiqueta);
        return filhos.getLength() == 0 ? null : filhos.item(0).getTextContent();
    }

    // =========================================================================

    private static List<String> sharedStrings(byte[] parte) {
        if (parte == null) {
            return List.of();
        }
        NodeList itens = xml(parte).getElementsByTagName("si");
        List<String> textos = new ArrayList<>();
        for (int i = 0; i < itens.getLength(); i++) {
            textos.add(itens.item(i).getTextContent());
        }
        return textos;
    }

    /**
     * O codigo de formato de cada estilo, por indice de {@code cellXfs}.
     *
     * <p>E o que separa uma data de um numero qualquer: as duas sao numero no
     * arquivo, e quem diz que uma e data e o {@code numFmt} aplicado a ela.</p>
     */
    private static List<String> formatos(byte[] parte) {
        Document estilos = xml(parte);

        Map<String, String> porId = new HashMap<>();
        NodeList customizados = estilos.getElementsByTagName("numFmt");
        for (int i = 0; i < customizados.getLength(); i++) {
            Element f = (Element) customizados.item(i);
            porId.put(f.getAttribute("numFmtId"), f.getAttribute("formatCode"));
        }

        List<String> porEstilo = new ArrayList<>();
        NodeList xfs = ((Element) estilos.getElementsByTagName("cellXfs").item(0))
            .getElementsByTagName("xf");
        for (int i = 0; i < xfs.getLength(); i++) {
            String id = ((Element) xfs.item(i)).getAttribute("numFmtId");
            porEstilo.add(porId.getOrDefault(id, "0".equals(id) ? "General" : "builtin:" + id));
        }
        return porEstilo;
    }

    private static Map<String, String> relacionamentos(byte[] parte) {
        Map<String, String> alvos = new LinkedHashMap<>();
        NodeList lista = xml(parte).getElementsByTagName("Relationship");
        for (int i = 0; i < lista.getLength(); i++) {
            Element r = (Element) lista.item(i);
            alvos.put(r.getAttribute("Id"), r.getAttribute("Target"));
        }
        return alvos;
    }

    // =========================================================================

    /**
     * Abre o zip pelo <b>diretorio central</b>, e nao lendo o fluxo de ponta a
     * ponta.
     *
     * <p>Nao e detalhe de conveniencia. O {@code fastexcel} escreve em
     * streaming: os cabecalhos locais saem com tamanho zero e o tamanho real vai
     * num descritor no fim de cada entrada. E legitimo, e o {@code unzip}, o
     * Excel e o LibreOffice leem assim — pelo diretorio central, que e o indice
     * no fim do arquivo. Um {@link java.util.zip.ZipInputStream} recusaria este
     * arquivo <b>valido</b>, e o teste acusaria um defeito que nao existe.</p>
     *
     * <p>O que continua sendo conferido e o que importa: um arquivo truncado
     * nao tem diretorio central, e {@link ZipFile} falha nele — que e
     * exatamente o modo de falha que §6c descreve.</p>
     */
    private static Map<String, byte[]> descompactar(byte[] arquivo) {

        Map<String, byte[]> partes = new LinkedHashMap<>();
        Path temporario = null;
        try {
            temporario = Files.createTempFile("extrato-de-teste-", ".xlsx");
            Files.write(temporario, arquivo);

            try (ZipFile zip = new ZipFile(temporario.toFile())) {
                Enumeration<? extends ZipEntry> entradas = zip.entries();
                while (entradas.hasMoreElements()) {
                    ZipEntry entrada = entradas.nextElement();
                    try (InputStream conteudo = zip.getInputStream(entrada)) {
                        partes.put(entrada.getName(), conteudo.readAllBytes());
                    }
                }
            }
        } catch (Exception e) {
            // Zip truncado e o modo de falha proprio deste endpoint: o arquivo
            // "existe", tem tamanho, e nenhuma leitora o abre.
            throw new AssertionError(
                "O .xlsx nao abre como zip (" + arquivo.length + " bytes): " + e, e);
        } finally {
            if (temporario != null) {
                try {
                    Files.deleteIfExists(temporario);
                } catch (Exception ignorada) {
                    // Arquivo de teste em /tmp: nao ha o que fazer, e nao ha o
                    // que salvar.
                }
            }
        }
        return partes;
    }

    private static byte[] exigir(Map<String, byte[]> partes, String caminho) {
        byte[] parte = partes.get(caminho);
        if (parte == null) {
            throw new AssertionError(
                "O .xlsx nao tem " + caminho + ". Tem: " + partes.keySet());
        }
        return parte;
    }

    private static Document xml(byte[] parte) {
        try {
            DocumentBuilderFactory fabrica = DocumentBuilderFactory.newInstance();
            // Sem namespace: o OOXML usa prefixo em r:id, e getAttribute("r:id")
            // so encontra o atributo com o nome cru.
            fabrica.setNamespaceAware(false);
            fabrica.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder leitor = fabrica.newDocumentBuilder();
            return leitor.parse(new ByteArrayInputStream(parte));
        } catch (Exception e) {
            throw new AssertionError("XML do .xlsx ilegivel: " + e, e);
        }
    }

    // =========================================================================

    /** {@code B12} -> 11, base zero. */
    private static int linhaDe(String referencia) {
        return Integer.parseInt(referencia.replaceAll("[^0-9]", "")) - 1;
    }

    /** {@code AB12} -> 27, base zero. */
    private static int colunaDe(String referencia) {
        String letras = referencia.replaceAll("[^A-Z]", "");
        int coluna = 0;
        for (int i = 0; i < letras.length(); i++) {
            coluna = coluna * 26 + (letras.charAt(i) - 'A' + 1);
        }
        return coluna - 1;
    }
}
