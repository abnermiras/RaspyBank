package com.raspybank.integracao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O que o {@code .xlsx} da T-10 promete <b>dentro</b> do arquivo — B-D117 a
 * B-D119.
 *
 * <p>Nenhuma asserção aqui olha bytes: o arquivo e lido de volta por
 * {@link LeitoraXlsx}, porque tudo o que a T-10 promete e invisivel no zip.
 * "Dinheiro e numero" e "data e data" (B-D118) sao afirmacoes sobre o
 * <b>tipo</b> da celula, e {@code -80.00} aparece igual no XML quando esta
 * gravado como texto — e {@code SOMA()} devolve zero.</p>
 *
 * <h3>O que este teste tenta quebrar</h3>
 *
 * <ul>
 *   <li>as tres colunas <b>desempacotadas</b> (B-D119): {@code PREVISTO} tem de
 *       estar em Situacao e {@code 3/12} em Parcela, e nenhum dos dois pode ter
 *       sobrado dentro de Descricao ou de Pago com — que e onde a T-08 os
 *       guarda;</li>
 *   <li>o <b>sinal</b> e o tipo da celula de Valor: saida negativa, entrada
 *       positiva, e as duas numero;</li>
 *   <li>o <b>recorte da faixa</b>, com um lancamento em cada borda e um em cada
 *       lado de fora;</li>
 *   <li>a <b>estabilidade da ordem</b> entre duas geracoes seguidas, inclusive
 *       para duas linhas do mesmo dia gravadas na <b>mesma transacao</b> — uma
 *       transferencia, cujas pernas nascem com o mesmo {@code criado_em} e por
 *       isso nao tem desempate nenhum;</li>
 *   <li>a <b>aba por ambiente</b>, inclusive a do ambiente que nao teve
 *       lancamento no periodo, e o nome de aba passado dos 31 caracteres do
 *       formato.</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExtratoCompletoXlsxTest extends IntegracaoTest {

    @Autowired
    private TestRestTemplate http;

    private static final String SENHA = "senha-com-mais-de-10";

    // As colunas de B-D119, na ordem. Nomeadas porque um indice cru numa
    // asserção esconde exatamente o defeito que ela procura.
    private static final int DATA = 0;
    private static final int DESCRICAO = 1;
    private static final int SITUACAO = 2;
    private static final int CATEGORIA = 3;
    private static final int SUBCATEGORIA = 4;
    private static final int CONTA = 5;
    private static final int PAGO_COM = 6;
    private static final int PARCELA = 7;
    private static final int TIPO = 8;
    private static final int VALOR = 9;
    private static final int QUEM = 10;

    private static final LocalDate HOJE = LocalDate.now();

    /** Doze meses exatos, com folga dos dois lados para as faturas parceladas. */
    private static final LocalDate INICIO = HOJE.minusMonths(6);
    private static final LocalDate FIM = HOJE.plusMonths(6);

    private static String token;

    private static String abaPessoal;
    private static String abaDaEmpresa;
    private static String abaVazia;

    private static String contaPessoal;
    private static String contaDaEmpresa;
    private static String contaDoCofre;

    private static boolean pronto;

    // =========================================================================
    // A forma do arquivo
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("A primeira aba e a capa, e cada aba de ambiente traz as 11 colunas de B-D119")
    void asColunasSaoAsDaTelaDesempacotada() {
        prepararCenario();

        LeitoraXlsx.Planilha planilha = baixar(INICIO, FIM);

        assertEquals("Sobre este arquivo", planilha.nomes().get(0),
            "A capa nao e a primeira aba — e ela que explica a linha mascarada");

        LeitoraXlsx.Aba aba = planilha.aba(abaPessoal);

        assertEquals(
            List.of("Data", "Descrição", "Situação", "Categoria", "Subcategoria",
                    "Conta", "Pago com", "Parcela", "Tipo", "Valor", "Quem"),
            aba.linhas().get(0).stream().map(LeitoraXlsx.Celula::comoTexto).toList(),
            "O cabecalho mudou de forma. Ele e o contrato do arquivo: quem filtra"
                + " por Situacao ou por Categoria depende da posicao da coluna");
    }

    @Test
    @Order(2)
    @DisplayName("Uma aba por ambiente, e a linha cai na aba do ambiente em que nasceu")
    void umaAbaPorAmbiente() {
        LeitoraXlsx.Planilha planilha = baixar(INICIO, FIM);

        assertEquals(List.of("Sobre este arquivo", abaDaEmpresa, abaPessoal, abaVazia),
            planilha.nomes(),
            "As abas nao sao os ambientes da pessoa, ou trocaram de ordem entre geracoes");

        assertNotNull(linhaPorDescricao(planilha.aba(abaPessoal), "Mercado do mês"),
            "O gasto pessoal nao esta na aba do ambiente pessoal");

        assertNull(linhaPorDescricao(planilha.aba(abaDaEmpresa), "Mercado do mês"),
            "O gasto pessoal vazou para a aba da empresa — a separacao das vidas"
                + " acontece DENTRO do arquivo (B-D117), e ela e a aba");

        assertNotNull(linhaPorDescricao(planilha.aba(abaDaEmpresa), "Nota fiscal do cliente"),
            "A entrada da empresa nao esta na aba da empresa");
    }

    @Test
    @Order(3)
    @DisplayName("Ambiente sem lancamento no periodo vira aba vazia com cabecalho, e nao some")
    void ambienteSemLancamentoViraAbaVazia() {
        LeitoraXlsx.Aba vazia = baixar(INICIO, FIM).aba(abaVazia);

        assertEquals("Data", vazia.texto(0, DATA),
            "A aba do ambiente sem movimento perdeu o cabecalho");

        assertEquals(0, vazia.corpo().size(),
            "A aba do ambiente sem movimento no periodo veio com linha: " + vazia.corpo());
    }

    // =========================================================================
    // B-D118 — dinheiro e numero, data e data
    // =========================================================================

    @Test
    @Order(4)
    @DisplayName("Saida sai negativa e entrada positiva, e as duas sao NUMERO — nao texto")
    void oValorTemSinalEEhNumero() {
        LeitoraXlsx.Aba aba = baixar(INICIO, FIM).aba(abaPessoal);

        LeitoraXlsx.Celula saida = linhaPorDescricao(aba, "Mercado do mês").get(VALOR);
        LeitoraXlsx.Celula entrada = linhaPorDescricao(aba, "Salário do mês").get(VALOR);

        assertEquals(LeitoraXlsx.Tipo.NUMERO, saida.tipo(),
            "Valor gravado como " + saida.tipo() + ": SOMA() devolveria zero, e e para"
                + " somar que a planilha existe (B-D118)");
        assertEquals(LeitoraXlsx.Tipo.NUMERO, entrada.tipo());

        assertEquals(0, new BigDecimal("-1450.22").compareTo(saida.numero()),
            "A saida nao saiu negativa: " + saida);
        assertEquals(0, new BigDecimal("3000.00").compareTo(entrada.numero()),
            "A entrada nao saiu positiva: " + entrada);

        assertEquals("SAIDA", aba.texto(linhaDe(aba, "Mercado do mês"), TIPO));
        assertEquals("ENTRADA", aba.texto(linhaDe(aba, "Salário do mês"), TIPO));
    }

    @Test
    @Order(5)
    @DisplayName("Data e data de verdade, com formato de data — nao texto que ordena por letra")
    void aDataEhData() {
        LeitoraXlsx.Aba aba = baixar(INICIO, FIM).aba(abaPessoal);

        LeitoraXlsx.Celula data = linhaPorDescricao(aba, "Mercado do mês").get(DATA);

        assertEquals(LeitoraXlsx.Tipo.NUMERO, data.tipo(),
            "Data gravada como " + data.tipo() + ": no Excel ela ordenaria"
                + " alfabeticamente, 01/12 antes de 02/01");

        assertTrue(data.formato().toLowerCase().contains("yy"),
            "A celula e numero mas sem formato de data (" + data.formato()
                + "): a pessoa veria 46096 no lugar da data");

        assertEquals(HOJE, data.comoData());
    }

    // =========================================================================
    // B-D119 — as tres celulas que a T-08 empacota
    // =========================================================================

    @Test
    @Order(6)
    @DisplayName("PREVISTO fica na coluna Situacao e 3/12 na coluna Parcela, e em mais lugar nenhum")
    void asColunasSaemDesempacotadas() {
        LeitoraXlsx.Aba aba = baixar(INICIO, FIM).aba(abaPessoal);

        List<LeitoraXlsx.Celula> terceira = aba.corpo().stream()
            .filter(l -> "3/12".equals(l.get(PARCELA).comoTexto()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "Nenhuma linha com Parcela = 3/12. As 12 parcelas do ar condicionado"
                    + " existem desde a compra (F23) e cada uma cai numa fatura"));

        assertEquals("PREVISTO", terceira.get(SITUACAO).comoTexto(),
            "A parcela de uma fatura futura nao esta marcada PREVISTO");

        assertEquals("Ar condicionado", terceira.get(DESCRICAO).comoTexto(),
            "A Descricao nao e so a descricao — a etiqueta da T-08 veio junto");

        String pagoCom = terceira.get(PAGO_COM).comoTexto();
        assertNotNull(pagoCom, "Compra no cartao sem \"Pago com\"");
        assertFalse(pagoCom.contains("3/12"),
            "\"3/12\" continua dentro de Pago com (\"" + pagoCom + "\"): B-D119 tirou"
                + " de la e poe na coluna Parcela, e quem filtra por plastico pega a"
                + " parcela junto");
        assertTrue(pagoCom.startsWith("físico ····"),
            "\"Pago com\" nao descreve o plastico: " + pagoCom);

        assertEquals(0, new BigDecimal("-100.00").compareTo(terceira.get(VALOR).numero()),
            "A parcela nao vale 1200/12 com sinal: " + terceira.get(VALOR));

        // E a etiqueta nao sobrou em lugar nenhum das outras linhas.
        for (List<LeitoraXlsx.Celula> linha : aba.corpo()) {
            String descricao = linha.get(DESCRICAO).comoTexto();
            assertFalse(descricao != null && descricao.toLowerCase().contains("previsto"),
                "A etiqueta \"previsto\" da T-08 sobrou dentro de Descricao: " + descricao);
        }
    }

    @Test
    @Order(7)
    @DisplayName("Categoria e subcategoria saem em colunas separadas, sem o \"›\" da tela")
    void categoriaESubcategoriaSaoDuasColunas() {
        LeitoraXlsx.Aba aba = baixar(INICIO, FIM).aba(abaPessoal);
        List<LeitoraXlsx.Celula> linha = linhaPorDescricao(aba, "Mercado do mês");

        assertEquals("Alimentação", linha.get(CATEGORIA).comoTexto());
        assertEquals("Supermercado", linha.get(SUBCATEGORIA).comoTexto(),
            "A subcategoria nao tem coluna propria, ou veio empacotada na categoria");
        assertEquals("Abner", linha.get(QUEM).comoTexto());
    }

    // =========================================================================
    // O recorte da faixa
    // =========================================================================

    @Test
    @Order(8)
    @DisplayName("A faixa recorta pelas bordas: o dia anterior a inicio e o seguinte a fim ficam fora")
    void aFaixaRecortaNasBordas() {
        LocalDate inicio = HOJE.minusDays(20);
        LocalDate fim = HOJE.minusDays(10);

        LeitoraXlsx.Aba aba = baixar(inicio, fim).aba(abaPessoal);

        assertNotNull(linhaPorDescricao(aba, "Borda de dentro, no inicio"),
            "O lancamento do proprio dia de inicio ficou de fora — a faixa e inclusive");
        assertNotNull(linhaPorDescricao(aba, "Borda de dentro, no fim"),
            "O lancamento do proprio dia de fim ficou de fora — a faixa e inclusive");

        assertNull(linhaPorDescricao(aba, "Um dia antes do inicio"),
            "Entrou lancamento de ANTES da faixa pedida");
        assertNull(linhaPorDescricao(aba, "Um dia depois do fim"),
            "Entrou lancamento de DEPOIS da faixa pedida");

        // E o de hoje, que esta bem fora desta janela estreita, tambem nao entra.
        assertNull(linhaPorDescricao(aba, "Mercado do mês"),
            "A faixa nao recortou: veio lancamento de fora dos dois lados");
    }

    // =========================================================================
    // Ordem e reentrancia
    // =========================================================================

    @Test
    @Order(9)
    @DisplayName("As linhas saem da mais recente para a mais antiga, por data de caixa")
    void aOrdemEhDaMaisRecenteParaAMaisAntiga() {
        LeitoraXlsx.Aba aba = baixar(INICIO, FIM).aba(abaPessoal);

        LocalDate anterior = null;
        for (List<LeitoraXlsx.Celula> linha : aba.corpo()) {
            LocalDate data = linha.get(DATA).comoData();
            if (anterior != null) {
                assertFalse(data.isAfter(anterior),
                    "A aba nao esta em data_caixa DESC: " + anterior + " veio antes de " + data);
            }
            anterior = data;
        }
    }

    @Test
    @Order(10)
    @DisplayName("Duas geracoes seguidas produzem o MESMO arquivo, inclusive no empate do mesmo dia")
    void duasGeracoesProduzemOMesmoArquivo() {
        // As duas pernas da transferencia nascem na MESMA transacao, entao tem o
        // mesmo criado_em: o desempate de app_extrato_completo empata nelas, e e
        // exatamente ai que a ordem pode virar arbitraria.
        List<String> primeira = comoTexto(baixar(INICIO, FIM).aba(abaPessoal));
        List<String> segunda = comoTexto(baixar(INICIO, FIM).aba(abaPessoal));

        assertEquals(primeira, segunda,
            "O mesmo pedido gerou dois arquivos diferentes. Um arquivo que muda de"
                + " forma sozinho e um arquivo que ninguem consegue comparar com o"
                + " do mes passado");
    }

    // =========================================================================
    // Nome de aba: o limite do formato
    // =========================================================================

    @Test
    @Order(11)
    @DisplayName("Nome de ambiente longo cabe em 31 caracteres, e a colisao ganha sufixo")
    void nomeDeAbaCabeNoLimiteDoFormato() {
        // Os dois passam dos 31 e sao IGUAIS ate o corte: sem sufixo, o formato
        // recusaria a segunda aba ou ela sobrescreveria a primeira em silencio.
        criarAmbiente("Financas da familia Amaral de Sao Paulo");
        criarAmbiente("Financas da familia Amaral de Curitiba");

        LeitoraXlsx.Planilha planilha = baixar(INICIO, FIM);

        List<String> longas = planilha.nomes().stream()
            .filter(n -> n.startsWith("Financas da familia Amaral"))
            .toList();

        assertEquals(2, longas.size(),
            "Dois ambientes de nome longo viraram " + longas.size() + " aba(s): "
                + planilha.nomes());

        for (String nome : longas) {
            assertTrue(nome.length() <= 31,
                "Nome de aba com " + nome.length() + " caracteres (\"" + nome
                    + "\"): o formato .xlsx admite 31, e a leitora recusa o arquivo");
        }
        assertNotEquals(longas.get(0), longas.get(1),
            "As duas abas ficaram com o mesmo nome depois do corte — uma some");
    }

    // =========================================================================
    // A capa, e a promessa que ela faz sobre os dados
    // =========================================================================

    @Test
    @Order(12)
    @DisplayName("As categorias que a capa manda filtrar existem com ESSE nome na coluna Categoria")
    void aCapaEnsinaAFiltrarPelosNomesQueOArquivoRealmenteTem() {
        // A capa diz: "filtre FORA, na coluna Categoria, Transferência, Ajuste de
        // saldo e Pagamento de fatura". Se a coluna trouxer outro texto — o
        // codigo da sistemica, por exemplo — a instrucao manda a pessoa procurar
        // no AutoFilter uma opcao que nao esta la, e ela conclui que o arquivo
        // esta errado. O aviso e dado, entao ele e contrato.
        LeitoraXlsx.Planilha planilha = baixar(INICIO, FIM);
        LeitoraXlsx.Aba aba = planilha.aba(abaPessoal);

        List<String> categorias = aba.corpo().stream()
            .map(l -> l.get(CATEGORIA).comoTexto())
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();

        assertTrue(categorias.contains("Transferência"),
            "A transferencia nao aparece como \"Transferência\" na coluna Categoria,"
                + " que e o texto que a capa manda filtrar. Categorias no arquivo: "
                + categorias);

        assertTrue(categorias.contains("Ajuste de saldo"),
            "O saldo de abertura nao aparece como \"Ajuste de saldo\": " + categorias);

        // E as duas pernas da transferencia estao la, com sinais opostos: e o
        // que faz a soma do arquivo nao mudar por causa dela.
        List<List<LeitoraXlsx.Celula>> pernas = aba.corpo().stream()
            .filter(l -> "Transferência".equals(l.get(CATEGORIA).comoTexto()))
            .toList();

        assertEquals(2, pernas.size(),
            "A transferencia entrou com " + pernas.size() + " perna(s). Uma so faria"
                + " dinheiro sair de uma conta sem entrar em nenhuma");

        assertEquals(0, pernas.get(0).get(VALOR).numero()
                .add(pernas.get(1).get(VALOR).numero()).signum(),
            "As duas pernas da transferencia nao se anulam na soma: "
                + pernas.get(0).get(VALOR) + " e " + pernas.get(1).get(VALOR));
    }

    // =========================================================================
    // O texto como a pessoa realmente digita
    // =========================================================================

    @Test
    @Order(13)
    @DisplayName("Descricao com aspas, & e < sai inteira, e o arquivo continua abrindo")
    void descricaoComCaractereDeMarcacaoNaoQuebraOArquivo() {
        // Um "&" ou um "<" nao escapado quebra o XML, e o .xlsx e um zip de XML:
        // uma descricao mal escrita derrubaria o arquivo INTEIRO, nao a linha.
        String bravo = "Conta de luz \"do mês\" & taxa <30%> — 1º/2º";

        lancar(contaPessoal, criarCategoria("Servicos & cia <2026>", "SAIDA"), null,
            "99.90", HOJE, bravo);

        LeitoraXlsx.Aba aba = baixar(INICIO, FIM).aba(abaPessoal);
        List<LeitoraXlsx.Celula> linha = linhaPorDescricao(aba, bravo);

        assertNotNull(linha, "A descricao com & e < nao chegou intacta ao arquivo");
        assertEquals("Servicos & cia <2026>", linha.get(CATEGORIA).comoTexto(),
            "O nome da categoria com marcacao chegou alterado");
    }

    @Test
    @Order(14)
    @DisplayName("Categoria arquivada continua nomeando a linha antiga (B-D4)")
    void categoriaArquivadaContinuaNomeandoOHistorico() {
        // Arquivar tira do seletor e nao apaga o passado — e por isso que nao
        // existe exclusao. Se o extrato deixasse de nomear a categoria arquivada,
        // meses inteiros do arquivo ficariam sem classificacao, sem que nada
        // tivesse sido apagado.
        String categoria = criarCategoria("Categoria que vai sumir", "SAIDA");
        lancar(contaPessoal, categoria, null, "31.00", HOJE, "Antes de arquivar");

        assertEquals(HttpStatus.OK,
            post("/api/categorias/" + categoria + "/arquivar", Map.of()).getStatusCode());

        List<LeitoraXlsx.Celula> linha =
            linhaPorDescricao(baixar(INICIO, FIM).aba(abaPessoal), "Antes de arquivar");

        assertNotNull(linha, "A linha da categoria arquivada sumiu do arquivo");
        assertEquals("Categoria que vai sumir", linha.get(CATEGORIA).comoTexto(),
            "A categoria arquivada deixou de nomear o historico (B-D4)");
    }

    @Test
    @Order(15)
    @DisplayName("Valor no teto de numeric(15,2) sai inteiro, sem arredondar nem virar notacao")
    void valorNoTetoDoTipoSaiInteiro() {
        // O maior valor que o contrato aceita: 13 digitos e duas casas. Um
        // double no caminho perderia os centavos aqui, silenciosamente — e este
        // e o unico lugar onde isso apareceria.
        String teto = "9999999999999.99";
        lancar(contaPessoal, criarCategoria("Heranca", "ENTRADA"), null, teto, HOJE,
            "O teto do tipo");

        LeitoraXlsx.Celula valor =
            linhaPorDescricao(baixar(INICIO, FIM).aba(abaPessoal), "O teto do tipo").get(VALOR);

        assertEquals(LeitoraXlsx.Tipo.NUMERO, valor.tipo());
        assertEquals(0, new BigDecimal(teto).compareTo(valor.numero()),
            "O maior valor possivel chegou ao arquivo como " + valor
                + ": alguem passou por double no caminho");
        assertFalse(valor.texto().toUpperCase().contains("E"),
            "O valor saiu em notacao cientifica (" + valor.texto() + "), que nenhuma"
                + " planilha soma como dinheiro");
    }

    // =========================================================================
    // O ambiente ativo, e a concorrencia
    // =========================================================================

    @Test
    @Order(16)
    @DisplayName("O arquivo e o mesmo em qualquer ambiente ativo — ele nao e uma tela (B-D117)")
    void oArquivoNaoSegueOAmbienteAtivo() {
        // B-D111 diz que TODA tela recorta pelo ambiente ativo; B-D117 diz que
        // este arquivo e a excecao. Sao duas regras contrarias convivendo, e o
        // jeito de a segunda morrer em silencio e alguem "consertar" o extrato
        // para seguir a primeira. Depois disso, quem baixasse de dentro do
        // ambiente errado receberia meio extrato sem nenhum aviso.
        String noPessoal = tudoComoTexto(baixar(INICIO, FIM));

        String tokenAnterior = token;
        token = trocarDeAmbiente(idDoAmbiente(abaDaEmpresa));
        String naEmpresa = tudoComoTexto(baixar(INICIO, FIM));
        token = tokenAnterior;

        assertEquals(noPessoal, naEmpresa,
            "O arquivo mudou quando o ambiente ativo mudou. Ele e o retrato da"
                + " pessoa, nao o da tela aberta (B-D117)");
    }

    @Test
    @Order(17)
    @DisplayName("Tres downloads ao mesmo tempo devolvem tres arquivos completos e iguais")
    void tresDownloadsSimultaneosNaoDerrubamNemDivergem() throws Exception {
        // Este e o unico endpoint do sistema que ESCREVE antes de ler
        // (sincronizar) e depois segura a conexao enquanto os bytes saem. Duas
        // geracoes ao mesmo tempo disputam as mesmas linhas de lancamento e a
        // mesma conexao do pool — e "clicar duas vezes no botao" nao e cenario
        // de laboratorio, e o que a pessoa faz quando acha que travou.
        int quantos = 3;
        ExecutorService executor = Executors.newFixedThreadPool(quantos);
        CountDownLatch largada = new CountDownLatch(1);

        List<Future<String>> corridas = new ArrayList<>();
        for (int i = 0; i < quantos; i++) {
            corridas.add(executor.submit(() -> {
                largada.await();
                return tudoComoTexto(baixar(INICIO, FIM));
            }));
        }
        largada.countDown();

        try {
            String primeiro = corridas.get(0).get(60, TimeUnit.SECONDS);
            for (int i = 1; i < quantos; i++) {
                assertEquals(primeiro, corridas.get(i).get(60, TimeUnit.SECONDS),
                    "Dois downloads simultaneos devolveram arquivos diferentes");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    // =========================================================================
    // Cenario
    // =========================================================================

    private void prepararCenario() {
        if (pronto) {
            return;
        }
        pronto = true;

        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        token = cadastrarEEntrar("Abner", "abner-xlsx-" + sufixo + "@teste.local");

        abaPessoal = "Financas de Abner";
        assertEquals(abaPessoal, nomeDoAmbienteAtivo(),
            "O ambiente inicial mudou de nome e o teste inteiro se apoia nele");

        // ---- Ambiente pessoal ------------------------------------------------
        contaPessoal = criarConta("Conta do dia a dia", "1000.00");
        String alimentacao = criarCategoria("Alimentação", "SAIDA");
        String supermercado = criarSubcategoria(alimentacao, "Supermercado");
        String salario = criarCategoria("Salário", "ENTRADA");

        lancar(contaPessoal, alimentacao, supermercado, "1450.22", HOJE, "Mercado do mês");
        lancar(contaPessoal, salario, null, "3000.00", HOJE, "Salário do mês");

        // As quatro bordas do recorte por faixa (teste 8).
        lancar(contaPessoal, alimentacao, null, "11.00", HOJE.minusDays(21), "Um dia antes do inicio");
        lancar(contaPessoal, alimentacao, null, "12.00", HOJE.minusDays(20), "Borda de dentro, no inicio");
        lancar(contaPessoal, alimentacao, null, "13.00", HOJE.minusDays(10), "Borda de dentro, no fim");
        lancar(contaPessoal, alimentacao, null, "14.00", HOJE.minusDays(9), "Um dia depois do fim");

        // A transferencia: duas linhas, o mesmo dia, a MESMA transacao — o
        // empate sem desempate do teste 10.
        contaDoCofre = criarConta("Cofre");
        ResponseEntity<Map> transferencia = post("/api/transferencias", Map.of(
            "contaOrigemId", contaPessoal,
            "contaDestinoId", contaDoCofre,
            "valor", "200.00",
            "dataCaixa", HOJE.toString(),
            "descricao", "Guardar para a viagem"));
        assertEquals(HttpStatus.CREATED, transferencia.getStatusCode(),
            "Cenario nao subiu (transferencia): " + transferencia.getBody());

        // O cartao, e a compra parcelada em 12 — a parcela 3/12 cai numa fatura
        // futura e por isso nasce PREVISTA.
        ResponseEntity<Map> cartao = post("/api/cartoes", Map.of(
            "contaBancoId", contaPessoal,
            "nome", "Cartao do arquivo",
            "limite", "20000.00",
            "diaVencimento", 15,
            "finalDoCartao", "4477"));
        assertEquals(HttpStatus.CREATED, cartao.getStatusCode(),
            "Cenario nao subiu (cartao): " + cartao.getBody());

        String contaDoCartao = String.valueOf(cartao.getBody().get("id"));
        String plastico = String.valueOf(((List<Map<String, Object>>)
            cartao.getBody().get("emitidos")).get(0).get("id"));

        String casa = criarCategoria("Casa", "SAIDA");
        ResponseEntity<Map> parcelada = post("/api/lancamentos", Map.of(
            "contaId", contaDoCartao,
            "cartaoEmitidoId", plastico,
            "categoriaId", casa,
            "valor", "1200.00",
            "dataCaixa", HOJE.toString(),
            "dataCompetencia", HOJE.toString(),
            "parcelas", 12,
            "descricao", "Ar condicionado"));
        assertEquals(HttpStatus.CREATED, parcelada.getStatusCode(),
            "Cenario nao subiu (compra parcelada): " + parcelada.getBody());

        // ---- Segundo ambiente ------------------------------------------------
        String empresa = criarAmbiente("Empresa");
        abaDaEmpresa = "Empresa";

        String tokenPessoal = token;
        token = trocarDeAmbiente(empresa);

        contaDaEmpresa = criarConta("Conta PJ");
        String receita = criarCategoria("Receita", "ENTRADA");
        lancar(contaDaEmpresa, receita, null, "5000.00", HOJE, "Nota fiscal do cliente");

        // ---- Terceiro ambiente, de proposito sem nada ------------------------
        abaVazia = "Sem movimento";
        criarAmbiente(abaVazia);

        token = tokenPessoal;
    }

    // =========================================================================
    // Ferramentas
    // =========================================================================

    /** O arquivo, lido de volta. O 200 e o Content-Type sao conferidos aqui. */
    private LeitoraXlsx.Planilha baixar(LocalDate inicio, LocalDate fim) {

        ResponseEntity<byte[]> r = http.exchange(
            "/api/relatorios/extrato.xlsx?inicio=" + inicio + "&fim=" + fim,
            HttpMethod.GET, new HttpEntity<>(cabecalhos(token)), byte[].class);

        assertEquals(HttpStatus.OK, r.getStatusCode(),
            "O extrato nao veio: " + new String(r.getBody() == null ? new byte[0] : r.getBody()));

        return LeitoraXlsx.ler(r.getBody());
    }

    private static int linhaDe(LeitoraXlsx.Aba aba, String descricao) {
        for (int i = 1; i < aba.linhas().size(); i++) {
            if (descricao.equals(aba.texto(i, DESCRICAO))) {
                return i;
            }
        }
        throw new AssertionError("Nenhuma linha com Descricao = \"" + descricao + "\"");
    }

    private static List<LeitoraXlsx.Celula> linhaPorDescricao(LeitoraXlsx.Aba aba, String descricao) {
        return aba.corpo().stream()
            .filter(l -> descricao.equals(l.get(DESCRICAO).comoTexto()))
            .findFirst()
            .orElse(null);
    }

    /** O arquivo inteiro em texto, para comparar duas geracoes celula a celula. */
    private static String tudoComoTexto(LeitoraXlsx.Planilha planilha) {
        StringBuilder tudo = new StringBuilder();
        for (LeitoraXlsx.Aba aba : planilha.abas()) {
            // A capa traz a hora da geracao, que muda de proposito entre duas
            // chamadas — comparar o arquivo inteiro acusaria isso como defeito.
            if ("Sobre este arquivo".equals(aba.nome())) {
                tudo.append("[capa]\n");
                continue;
            }
            tudo.append("[").append(aba.nome()).append("]\n");
            for (List<LeitoraXlsx.Celula> linha : aba.linhas()) {
                tudo.append(linha).append("\n");
            }
        }
        return tudo.toString();
    }

    private String idDoAmbiente(String nome) {
        return ((List<Map<String, Object>>) get("/api/perfil").getBody().get("ambientes"))
            .stream()
            .filter(a -> nome.equals(a.get("nome")))
            .map(a -> String.valueOf(a.get("id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Ambiente \"" + nome + "\" nao esta no perfil"));
    }

    /** A aba inteira em texto, para comparar duas geracoes celula a celula. */
    private static List<String> comoTexto(LeitoraXlsx.Aba aba) {
        List<String> linhas = new ArrayList<>();
        for (List<LeitoraXlsx.Celula> linha : aba.linhas()) {
            linhas.add(linha.toString());
        }
        return linhas;
    }

    // =========================================================================

    private String nomeDoAmbienteAtivo() {
        Map perfil = get("/api/perfil").getBody();
        String ativo = String.valueOf(perfil.get("ambienteAtual"));
        return ((List<Map<String, Object>>) perfil.get("ambientes")).stream()
            .filter(a -> ativo.equals(String.valueOf(a.get("id"))))
            .map(a -> String.valueOf(a.get("nome")))
            .findFirst().orElseThrow();
    }

    private String criarAmbiente(String nome) {
        ResponseEntity<Map> r = post("/api/ambientes", Map.of("nome", nome));
        assertEquals(HttpStatus.CREATED, r.getStatusCode(), "Ambiente nao criado: " + r.getBody());
        return ((List<Map<String, Object>>) r.getBody().get("ambientes")).stream()
            .filter(a -> nome.equals(a.get("nome")))
            .map(a -> String.valueOf(a.get("id")))
            .findFirst().orElseThrow();
    }

    private String trocarDeAmbiente(String ambienteId) {
        ResponseEntity<Map> r = post("/api/sessao/ambiente", Map.of("ambienteId", ambienteId));
        assertEquals(HttpStatus.OK, r.getStatusCode(), "Troca de ambiente falhou: " + r.getBody());
        return String.valueOf(r.getBody().get("tokenAcesso"));
    }

    private String criarConta(String nome) {
        return criarConta(nome, "0.00");
    }

    private String criarConta(String nome, String saldoInicial) {
        ResponseEntity<Map> r = post("/api/contas", Map.of(
            "nome", nome,
            "natureza", "ATIVO",
            "saldoInicial", saldoInicial,
            "formasPagamento", List.of("DEBITO", "PIX", "CREDITO_EM_CONTA"),
            "padraoSaida", "DEBITO",
            "padraoEntrada", "CREDITO_EM_CONTA"));
        assertEquals(HttpStatus.CREATED, r.getStatusCode(),
            "Conta \"" + nome + "\" nao foi criada: " + r.getBody());
        return String.valueOf(r.getBody().get("id"));
    }

    private String criarCategoria(String nome, String tipo) {
        ResponseEntity<Map> r = post("/api/categorias", Map.of("nome", nome, "tipo", tipo));
        assertEquals(HttpStatus.CREATED, r.getStatusCode(),
            "Categoria \"" + nome + "\" nao foi criada: " + r.getBody());
        return String.valueOf(r.getBody().get("id"));
    }

    private String criarSubcategoria(String categoriaId, String nome) {
        ResponseEntity<Map> r = post("/api/categorias/" + categoriaId + "/subcategorias",
            Map.of("nome", nome));
        assertEquals(HttpStatus.CREATED, r.getStatusCode(), "Subcategoria: " + r.getBody());
        return String.valueOf(r.getBody().get("id"));
    }

    private void lancar(String contaId, String categoriaId, String subcategoriaId,
                        String valor, LocalDate data, String descricao) {

        java.util.Map<String, Object> corpo = new java.util.HashMap<>(Map.of(
            "contaId", contaId,
            "categoriaId", categoriaId,
            "valor", valor,
            "dataCaixa", data.toString(),
            "descricao", descricao));
        if (subcategoriaId != null) {
            corpo.put("subcategoriaId", subcategoriaId);
        }

        ResponseEntity<Map> r = post("/api/lancamentos", corpo);
        assertEquals(HttpStatus.CREATED, r.getStatusCode(),
            "Lancamento \"" + descricao + "\" nao entrou: " + r.getBody());
    }

    private String cadastrarEEntrar(String nome, String email) {
        assertEquals(HttpStatus.CREATED, postSemToken("/api/auth/cadastro",
            Map.of("nome", nome, "email", email, "senha", SENHA)).getStatusCode());

        return String.valueOf(postSemToken("/api/auth/login",
            Map.of("email", email, "senha", SENHA)).getBody().get("tokenAcesso"));
    }

    private static HttpHeaders cabecalhos(String bearer) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(bearer);
        return h;
    }

    private ResponseEntity<Map> get(String caminho) {
        return http.exchange(caminho, HttpMethod.GET,
            new HttpEntity<>(cabecalhos(token)), Map.class);
    }

    private ResponseEntity<Map> post(String caminho, Map<String, ?> corpo) {
        return http.postForEntity(caminho, new HttpEntity<>(corpo, cabecalhos(token)), Map.class);
    }

    private ResponseEntity<Map> postSemToken(String caminho, Map<String, ?> corpo) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(caminho, new HttpEntity<>(corpo, h), Map.class);
    }
}
