package com.raspybank.integracao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O mapa de gastos — fatia 4, contrato em {@code docs/api.md} §6.
 *
 * <p>Ao contrario das outras classes de API, esta <b>nao</b> e um cenario
 * encadeado: o quadro e uma leitura pura, entao o cenario e montado uma vez no
 * {@code @BeforeAll} e cada teste faz uma pergunta independente sobre ele. Um
 * teste que falha aqui aponta para a regra que quebrou, nao para a etapa
 * anterior.</p>
 *
 * <h3>O cenario</h3>
 *
 * <p>Ano fixo de 2026 — datas passadas, entao tudo nasce REALIZADO, e a
 * derivacao de B-D9 nao muda o resultado conforme o dia em que a suite roda.
 * O previsto entra por correcao explicita no PUT.</p>
 */
class MapaDeGastosApiTest extends IntegracaoTest {

    @Autowired
    private TestRestTemplate http;

    private static final String SENHA = "senha-com-mais-de-10";
    private static final int ANO = 2026;

    private static String token;
    private static String contaId;
    private static String mercadoId;
    private static String feiraId;
    private static String salarioId;
    private static String carteiraId;

    // =========================================================================

    @Test
    @DisplayName("Cada celula traz DOIS numeros, e o total nunca vem somado (B-D10)")
    void duasColunasPorCelula() {
        Map<String, Object> mapa = mapa(ANO);

        var mercado = categoriaDoBloco(mapa, "saidas", "Mercado");
        var janeiro = celula(mercado, 1);

        assertEquals("450.00", janeiro.get("realizado"));
        assertEquals("0.00", janeiro.get("previsto"),
            "Zero explicito, nunca ausente — a tela nao deveria adivinhar buraco");

        // Fevereiro tem previsto: e o que prova que os dois numeros vivem
        // separados ate a tela.
        var fevereiro = celula(mercado, 2);
        assertEquals("200.00", fevereiro.get("realizado"));
        assertEquals("300.00", fevereiro.get("previsto"));
    }

    @Test
    @DisplayName("Doze celulas sempre, mesmo nos meses sem lancamento nenhum")
    void dozeCelulasSempre() {
        var mercado = categoriaDoBloco(mapa(ANO), "saidas", "Mercado");
        var celulas = (List<Map<String, Object>>) mercado.get("celulas");

        assertEquals(12, celulas.size());
        for (int m = 1; m <= 12; m++) {
            assertEquals(m, celulas.get(m - 1).get("mes"), "Os meses vem em ordem, de 1 a 12");
        }
        assertEquals("0.00", celula(mercado, 7).get("realizado"),
            "Julho nao teve gasto, e mesmo assim tem celula");
    }

    @Test
    @DisplayName("Subcategorias vem aninhadas, com a linha '(sem subcategoria)' por ultimo (F11)")
    void subcategoriasAninhadas() {
        var mercado = categoriaDoBloco(mapa(ANO), "saidas", "Mercado");
        var subs = (List<Map<String, Object>>) mercado.get("subcategorias");

        assertEquals(2, subs.size());
        assertEquals("Feira", subs.get(0).get("nome"));
        assertNotNull(subs.get(0).get("subcategoriaId"));

        assertEquals("(sem subcategoria)", subs.get(1).get("nome"),
            "O resto vai por ultimo: resto no meio da lista parece categoria propria");
        assertNull(subs.get(1).get("subcategoriaId"));

        // A soma das subcategorias fecha com a categoria — em janeiro,
        // 120 de feira + 330 sem rotulo = 450.
        assertEquals("120.00", celula(subs.get(0), 1).get("realizado"));
        assertEquals("330.00", celula(subs.get(1), 1).get("realizado"));
    }

    @Test
    @DisplayName("Transferencia fica FORA do mapa; a categoria comum fica dentro (B-D14/B-D15)")
    void transferenciaNaoEGasto() {
        // Mover dinheiro entre bolsos proprios nao e gasto. Se entrasse, o
        // total do mes mentiria para cima toda vez que alguem transferisse.
        Map<String, Object> mapa = mapa(ANO);

        var nomes = ((List<Map<String, Object>>) bloco(mapa, "saidas").get("categorias"))
            .stream().map(c -> String.valueOf(c.get("nome"))).toList();

        assertTrue(nomes.contains("Mercado"));
        assertTrue(nomes.stream().noneMatch(n -> n.contains("Transfer")),
            "A transferencia de 1.000,00 nao deveria aparecer: " + nomes);
    }

    @Test
    @DisplayName("Entradas e saidas sao blocos separados, e o saldo e a diferenca (B-D12)")
    void tresBlocos() {
        Map<String, Object> mapa = mapa(ANO);

        var salario = categoriaDoBloco(mapa, "entradas", "Salario");
        assertEquals("5000.00", celula(salario, 1).get("realizado"));

        // Janeiro: 5000 de entrada, 450 de saida.
        var saldoJaneiro = ((List<Map<String, Object>>)
            ((Map<String, Object>) mapa.get("saldo")).get("porMes")).get(0);

        assertEquals("4550.00", saldoJaneiro.get("realizado"),
            "A pergunta que a familia faz nao e 'quanto gastei', e 'sobrou ou faltou'");
    }

    @Test
    @DisplayName("O saldo fica negativo quando se gasta mais do que entra")
    void saldoNegativo() {
        // Fevereiro: nenhuma entrada, 200 de saida realizada.
        var saldoFevereiro = ((List<Map<String, Object>>)
            ((Map<String, Object>) mapa(ANO).get("saldo")).get("porMes")).get(1);

        assertEquals("-200.00", saldoFevereiro.get("realizado"),
            "E o unico lugar do mapa onde numero negativo aparece");
        assertEquals("-300.00", saldoFevereiro.get("previsto"));
    }

    @Test
    @DisplayName("O total do ano fecha com a soma dos meses")
    void totaisFecham() {
        var saidas = bloco(mapa(ANO), "saidas");
        var total = (Map<String, Object>) saidas.get("total");

        // 450 (jan) + 200 (fev) = 650 realizados em saidas.
        assertEquals("650.00", total.get("realizado"));
        assertEquals("300.00", total.get("previsto"));

        var mercado = categoriaDoBloco(mapa(ANO), "saidas", "Mercado");
        assertEquals("650.00", ((Map<String, Object>) mercado.get("total")).get("realizado"));
    }

    @Test
    @DisplayName("Ano sem lancamento devolve o quadro vazio, nao um erro")
    void anoVazio() {
        Map<String, Object> mapa = mapa(1999);

        assertEquals(1999, mapa.get("ano"));
        assertTrue(((List<?>) bloco(mapa, "saidas").get("categorias")).isEmpty());

        // Mesmo vazio, os doze meses do saldo estao la: a tela desenha a
        // tabela igual, so com zeros.
        var porMes = (List<Map<String, Object>>)
            ((Map<String, Object>) mapa.get("saldo")).get("porMes");
        assertEquals(12, porMes.size());
        assertEquals("0.00", porMes.get(0).get("realizado"));
    }

    @Test
    @DisplayName("Sem ano, responde o ano corrente; sem token, 401")
    void padraoEProtecao() {
        montarCenario();

        ResponseEntity<Map> semAno = http.exchange("/api/relatorios/mapa-de-gastos",
            HttpMethod.GET, new HttpEntity<>(cabecalhos(token)), Map.class);
        assertEquals(HttpStatus.OK, semAno.getStatusCode());
        assertEquals(LocalDate.now().getYear(), semAno.getBody().get("ano"),
            "A tela abre no ano em que a pessoa esta");

        ResponseEntity<String> semToken =
            http.getForEntity("/api/relatorios/mapa-de-gastos?ano=" + ANO, String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, semToken.getStatusCode());
    }

    @Test
    @DisplayName("O quadro diz de qual ambiente ele e")
    void identificaOAmbiente() {
        var ambiente = (Map<String, Object>) mapa(ANO).get("ambiente");

        assertNotNull(ambiente.get("id"));
        assertNotNull(ambiente.get("nome"),
            "O nome vem do contexto de ambiente, costurado no modulo de montagem");
    }

    // =========================================================================
    // O cenario
    // =========================================================================

    /**
     * Monta o cenario uma vez, na primeira chamada.
     *
     * <p>Tudo pela API — nenhum INSERT direto. Um cenario montado por SQL
     * provaria que a consulta soma, e nao que o sistema inteiro produz o
     * quadro certo a partir do que a pessoa digitou.</p>
     */
    private synchronized void montarCenario() {
        if (token != null) {
            return;
        }

        String email = "mapa-" + UUID.randomUUID().toString().substring(0, 8) + "@teste.local";
        postJson("/api/auth/cadastro",
            Map.of("nome", "Mapa Teste", "email", email, "senha", SENHA));
        token = (String) postJson("/api/auth/login",
            Map.of("email", email, "senha", SENHA)).getBody().get("tokenAcesso");

        contaId = criar("/api/contas", Map.of("nome", "Conta Corrente", "natureza", "ATIVO"));
        carteiraId = criar("/api/contas", Map.of("nome", "Carteira", "natureza", "ATIVO"));
        mercadoId = criar("/api/categorias", Map.of("nome", "Mercado", "tipo", "SAIDA"));
        salarioId = criar("/api/categorias", Map.of("nome", "Salario", "tipo", "ENTRADA"));
        feiraId = criar("/api/categorias/" + mercadoId + "/subcategorias",
            Map.of("nome", "Feira"));

        // Janeiro: 120 na feira + 330 sem subcategoria = 450 de mercado.
        lancar(mercadoId, feiraId, "120.00", "2026-01-10", null);
        lancar(mercadoId, null, "330.00", "2026-01-15", null);

        // Janeiro: 5.000 de entrada.
        lancar(salarioId, null, "5000.00", "2026-01-05", null);

        // Fevereiro: 200 realizados e 300 marcados como previstos. A data e
        // passada, entao a situacao vem por correcao explicita — e o teste de
        // "dois numeros" fica independente do dia em que a suite roda.
        lancar(mercadoId, null, "200.00", "2026-02-08", null);
        lancar(mercadoId, null, "300.00", "2026-02-20", "PREVISTO");

        // Transferencia de 1.000: entra_no_mapa = false, nao deve aparecer.
        //
        // Feita pelo endpoint de transferencia, e nao por um POST de lancamento
        // na categoria TRANSFERENCIA — que passou a ser recusado com 403 na V11.
        // O cenario ficou mais honesto de quebra: agora as DUAS pernas existem,
        // e o mapa precisa ignorar as duas.
        transferir(contaId, carteiraId, "1000.00", "2026-03-01");
    }

    private Map<String, Object> mapa(int ano) {
        montarCenario();
        ResponseEntity<Map> r = http.exchange("/api/relatorios/mapa-de-gastos?ano=" + ano,
            HttpMethod.GET, new HttpEntity<>(cabecalhos(token)), Map.class);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        return r.getBody();
    }

    /** As duas pernas numa transacao so — a unica porta que cria transferencia. */
    private void transferir(String origemId, String destinoId, String valor, String data) {
        ResponseEntity<Map> r = postAutenticado("/api/transferencias", Map.of(
            "contaOrigemId", origemId,
            "contaDestinoId", destinoId,
            "valor", valor,
            "dataCaixa", data));

        assertEquals(HttpStatus.CREATED, r.getStatusCode(),
            "Falhou ao transferir: " + r.getBody());
    }

    private void lancar(String categoriaId, String subcategoriaId, String valor,
                        String data, String situacao) {
        lancar(categoriaId, subcategoriaId, valor, data, situacao, null);
    }

    private void lancar(String categoriaId, String subcategoriaId, String valor,
                        String data, String situacao, String tipo) {

        Map<String, Object> corpo = new HashMap<>();
        corpo.put("contaId", contaId);
        corpo.put("categoriaId", categoriaId);
        corpo.put("subcategoriaId", subcategoriaId);
        corpo.put("valor", valor);
        corpo.put("dataCaixa", data);
        if (tipo != null) {
            corpo.put("tipo", tipo);
        }

        ResponseEntity<Map> criado = postAutenticado("/api/lancamentos", corpo);
        assertEquals(HttpStatus.CREATED, criado.getStatusCode(),
            "Falhou ao lancar: " + criado.getBody());

        if (situacao != null) {
            corpo.put("situacao", situacao);
            http.exchange("/api/lancamentos/" + criado.getBody().get("id"), HttpMethod.PUT,
                new HttpEntity<>(corpo, cabecalhos(token)), Map.class);
        }
    }

    // =========================================================================
    // Navegacao no JSON
    // =========================================================================

    @SuppressWarnings("unchecked")
    private Map<String, Object> bloco(Map<String, Object> mapa, String nome) {
        return (Map<String, Object>) mapa.get(nome);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> categoriaDoBloco(Map<String, Object> mapa,
                                                 String nomeDoBloco, String nomeDaCategoria) {
        var categorias = (List<Map<String, Object>>) bloco(mapa, nomeDoBloco).get("categorias");
        return categorias.stream()
            .filter(c -> nomeDaCategoria.equals(c.get("nome")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "Categoria '" + nomeDaCategoria + "' nao encontrada em " + nomeDoBloco));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> celula(Map<String, Object> linha, int mes) {
        return ((List<Map<String, Object>>) linha.get("celulas")).get(mes - 1);
    }

    // =========================================================================

    private String criar(String caminho, Map<String, String> corpo) {
        ResponseEntity<Map> r = postAutenticado(caminho, corpo);
        assertEquals(HttpStatus.CREATED, r.getStatusCode(), "Falhou ao criar em " + caminho);
        return String.valueOf(r.getBody().get("id"));
    }

    @SuppressWarnings("unchecked")

    private static HttpHeaders cabecalhos(String bearer) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(bearer);
        return h;
    }

    private ResponseEntity<Map> postJson(String caminho, Map<String, String> corpo) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(caminho, new HttpEntity<>(corpo, h), Map.class);
    }

    private <T> ResponseEntity<Map> postAutenticado(String caminho, Map<String, T> corpo) {
        return http.postForEntity(caminho, new HttpEntity<>(corpo, cabecalhos(token)), Map.class);
    }
}
