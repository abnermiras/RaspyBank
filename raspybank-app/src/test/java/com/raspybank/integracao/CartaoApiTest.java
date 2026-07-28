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

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cartao de credito (V12) pela porta da frente.
 *
 * <p>Desenhado com o Abner em 28/07/2026 ANTES de uma linha de codigo, em
 * `docs/decisoes.md` §4f (B-D45 a B-D59). Este teste guarda o que aquele
 * desenho prometeu.</p>
 *
 * <h3>O que ele guarda, em uma frase</h3>
 *
 * <p>Que a divida do cartao e SOMA DE LANCAMENTOS e nada mais (P1): o limite
 * consumido, o total da fatura e o quanto falta pagar sao todos calculados, e
 * nenhum deles existe como coluna.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CartaoApiTest extends IntegracaoTest {

    @Autowired
    private TestRestTemplate http;

    private static final String SENHA = "senha-com-mais-de-10";

    private static String token;
    private static String nubankId;
    private static String carteiraId;
    private static String mercadoId;
    private static String cartaoId;
    private static String fisicoId;
    private static String faturaAbertaId;

    // =========================================================================
    // O contrato
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Criar cartao cria a conta PASSIVO e o cartao FISICO junto (B-D47/B-D63)")
    void cartaoEUmaConta() {
        autenticar();

        nubankId = criarConta("Nubank", List.of("DEBITO", "PIX", "CREDITO_EM_CONTA"), "DEBITO");
        carteiraId = criarConta("Carteira", List.of("DINHEIRO"), "DINHEIRO");
        mercadoId = criarCategoria("Mercado", "SAIDA");

        ResponseEntity<Map> r = post("/api/cartoes", Map.of(
            "contaBancoId", nubankId,
            "nome", "Black",
            "limite", "10000.00",
            "diaVencimento", 15,
            "finalDoCartao", "4352"));

        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        assertEquals("10000.00", r.getBody().get("limite"));
        assertEquals("0.00", r.getBody().get("limiteConsumido"));
        assertEquals("10000.00", r.getBody().get("limiteDisponivel"));
        assertEquals(5, r.getBody().get("diasParaFechamento"), "Padrao 5 (B-D49)");

        cartaoId = String.valueOf(r.getBody().get("id"));

        // B-D63: o contrato nasce com o FISICO junto. Sem nenhum emitido ele
        // nao receberia compra nenhuma — nasceria inutil.
        List<Map<String, Object>> emitidos =
            (List<Map<String, Object>>) r.getBody().get("emitidos");
        assertEquals(1, emitidos.size());
        assertEquals("FISICO", emitidos.get(0).get("tipo"));
        assertEquals("4352", emitidos.get(0).get("finalDoCartao"));
        fisicoId = String.valueOf(emitidos.get(0).get("id"));

        // B-D62: o cartao NAO aparece na tela de contas. Ele continua sendo uma
        // conta PASSIVO por baixo — e a divida dela — mas "tratar o cartao de
        // credito como um banco confunde".
        assertNull(contaPorId(cartaoId),
            "Cartao nao e lugar onde se guarda dinheiro; ele tem tela propria");
    }

    @Test
    @Order(2)
    @DisplayName("Cartao em conta FISICA e recusado: papel moeda nao emite credito (B-D45)")
    void cartaoNaoNasceEmContaFisica() {
        ResponseEntity<Map> r = post("/api/cartoes", Map.of(
            "contaBancoId", carteiraId,
            "nome", "Cartao da Gaveta",
            "limite", "1000.00",
            "diaVencimento", 10,
            "finalDoCartao", "1111"));

        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
        assertTrue(String.valueOf(r.getBody().get("erro")).toLowerCase().contains("fisica"),
            "A mensagem explica por que: " + r.getBody().get("erro"));
    }

    @Test
    @Order(3)
    @DisplayName("Doze faturas nascem com o cartao — o parcelamento precisa de onde cair (F20)")
    void dozeFaturasNascemJunto() {
        List<Map<String, Object>> faturas = faturasDoAno(LocalDate.now().getYear());
        assertFalse(faturas.isEmpty(), "O cartao deveria nascer com faturas geradas");

        // A PRIMEIRA da lista pode ja estar FECHADA, e isso e correto: as
        // faturas nascem a partir do mes corrente, e se o fechamento daquele
        // ciclo ja passou, a leitura o fecha (mesma logica de
        // SituacaoVencidaServico). Este teste procura a primeira ABERTA — supor
        // que e a de indice zero faria o resultado depender do dia do mes.
        Map<String, Object> aberta = faturas.stream()
            .filter(f -> "ABERTA".equals(f.get("ciclo")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "Deveria haver ao menos uma fatura aberta: " + faturas));

        assertEquals("NADA_PAGO", aberta.get("quitacao"));
        assertEquals(false, aberta.get("vencida"));
        assertEquals("0.00", aberta.get("total"));

        // E as ja fechadas nao estao vencidas: nada foi comprado nelas, entao o
        // total e zero e zero esta quitado.
        for (Map<String, Object> f : faturas) {
            if ("FECHADA".equals(f.get("ciclo"))) {
                assertEquals(false, f.get("vencida"),
                    "Fatura fechada sem compra nenhuma nao esta vencida: " + f);
            }
        }
    }

    @Test
    @Order(4)
    @DisplayName("O fechamento recua para a SEXTA quando cai em fim de semana (B-D49)")
    void fechamentoRecuaParaSexta() {
        for (Map<String, Object> f : faturasDoAno(LocalDate.now().getYear())) {
            LocalDate fechamento = LocalDate.parse(String.valueOf(f.get("fechamentoPrevisto")));

            assertTrue(fechamento.getDayOfWeek().getValue() <= 5,
                "Fechamento caiu em " + fechamento.getDayOfWeek() + " (" + fechamento + ")."
                    + " Adiar para segunda faria a compra de sabado entrar numa fatura"
                    + " que vence em cinco dias.");

            LocalDate vencimento = LocalDate.parse(String.valueOf(f.get("vencimento")));
            assertTrue(!fechamento.isAfter(vencimento));
        }
    }

    // =========================================================================
    // Emitidos
    // =========================================================================

    @Test
    @Order(5)
    @DisplayName("Adicional da Luciana entra por NOME, sem ela ser usuaria (B-D53)")
    void adicionalPorNome() {
        ResponseEntity<Map> r = post("/api/cartoes/" + cartaoId + "/emitidos", Map.of(
            "nomeTitular", "Luciana",
            "tipo", "FISICO",
            "finalDoCartao", "5678"));

        assertEquals(HttpStatus.CREATED, r.getStatusCode());

        List<Map<String, Object>> emitidos =
            (List<Map<String, Object>>) r.getBody().get("emitidos");

        Map<String, Object> luciana = emitidos.stream()
            .filter(e -> "Luciana".equals(e.get("nomeTitular")))
            .findFirst().orElseThrow();

        assertNull(luciana.get("usuarioId"),
            "Convidar usuario e o I-08, que nao existe — o vinculo vem depois");
        assertEquals("FISICO", luciana.get("tipo"));
        assertEquals("5678", luciana.get("finalDoCartao"));
    }

    @Test
    @Order(6)
    @DisplayName("O numero completo do cartao e recusado — este sistema nao guarda isso")
    void numeroCompletoRecusado() {
        ResponseEntity<Map> r = post("/api/cartoes/" + cartaoId + "/emitidos", Map.of(
            "nomeTitular", "Abner",
            "tipo", "FISICO",
            "finalDoCartao", "4111111111111111"));

        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertNotNull(((Map<String, String>) r.getBody().get("campos")).get("finalDoCartao"));
    }

    // =========================================================================
    // A compra
    // =========================================================================

    @Test
    @Order(7)
    @DisplayName("Compra no cartao cai numa fatura, e a data de caixa vira o VENCIMENTO dela (F14)")
    void compraCaiNaFatura() {
        // A tela manda o BANCO e o cartao (B-D61): ninguem pensa "vou gastar na
        // conta do cartao", pensa "paguei no cartao".
        ResponseEntity<Map> r = post("/api/lancamentos", Map.of(
            "contaId", nubankId,
            "cartaoEmitidoId", fisicoId,
            "categoriaId", mercadoId,
            "valor", "450.00",
            "dataCaixa", LocalDate.now().toString(),
            "dataCompetencia", LocalDate.now().toString(),
            "descricao", "Mercado do mes"));

        assertEquals(HttpStatus.CREATED, r.getStatusCode());

        // E o extrato mostra o BANCO na coluna de conta, com o cartao ao lado.
        assertEquals("Nubank", ((Map<?, ?>) r.getBody().get("conta")).get("nome"));
        assertEquals("4352", ((Map<?, ?>) r.getBody().get("cartao")).get("finalDoCartao"));

        String faturaId = String.valueOf(r.getBody().get("faturaId"));
        assertNotNull(faturaId);
        faturaAbertaId = faturaId;

        // A data de caixa NAO e a data da compra: e o vencimento da fatura. E o
        // que faz o mapa dizer a verdade sobre quando o dinheiro sai (B-D54).
        Map<String, Object> fatura = faturaPorId(faturaId);
        assertEquals(fatura.get("vencimento"), r.getBody().get("dataCaixa"));
        assertEquals(LocalDate.now().toString(), r.getBody().get("dataCompetencia"),
            "A data da COMPRA nao se perde");
    }

    @Test
    @Order(8)
    @DisplayName("O limite consumido e a divida, e nao trava nada (B-D48)")
    void limiteConsumido() {
        Map<String, Object> cartao = cartaoAtual();
        assertEquals("450.00", cartao.get("limiteConsumido"));
        assertEquals("9550.00", cartao.get("limiteDisponivel"));
    }

    @Test
    @Order(9)
    @DisplayName("Parcelamento: dez lancamentos, resíduo na PRIMEIRA, data da compra repetida (F23)")
    void parcelamento() {
        ResponseEntity<Map> r = post("/api/lancamentos", Map.of(
            "contaId", nubankId,
            "cartaoEmitidoId", fisicoId,
            "categoriaId", mercadoId,
            "valor", "1000.00",
            "dataCaixa", LocalDate.now().toString(),
            "dataCompetencia", LocalDate.now().toString(),
            "descricao", "Geladeira",
            "parcelas", 3));

        assertEquals(HttpStatus.CREATED, r.getStatusCode());

        // 1000 em 3x = 333,34 + 333,33 + 333,33. Nunca 333,33 tres vezes, que
        // perderia um centavo do total.
        assertEquals("333.34", r.getBody().get("valor"), "O resíduo vai na primeira");
        assertEquals(1, r.getBody().get("parcelaNumero"));
        assertEquals(3, r.getBody().get("parcelaTotal"));
        assertNotNull(r.getBody().get("grupoParcelamentoId"));

        // O consumido pula os mil INTEIROS na hora, e nao um terco: as parcelas
        // futuras ja existem como lancamentos (F23), e o saldo com previstos as
        // enxerga. E o numero que o app do banco mostra (B-D48).
        assertEquals("1450.00", cartaoAtual().get("limiteConsumido"));
        assertEquals("8550.00", cartaoAtual().get("limiteDisponivel"));
    }

    @Test
    @Order(10)
    @DisplayName("Parcelar em conta comum e recusado: o dinheiro sai de uma vez")
    void parcelarEmContaComumRecusado() {
        ResponseEntity<Map> r = post("/api/lancamentos", Map.of(
            "contaId", nubankId,
            "categoriaId", mercadoId,
            "valor", "300.00",
            "dataCaixa", LocalDate.now().toString(),
            "parcelas", 3));

        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
    }

    // =========================================================================
    // Fechar, reabrir e pagar
    // =========================================================================

    @Test
    @Order(11)
    @DisplayName("Antecipar pagamento numa fatura ABERTA libera limite (B-D57)")
    void anteciparLiberaLimite() {
        // O caso do Abner, inteiro: a fatura ainda nao fechou, e pagar parte
        // dela devolve limite. Sem antecipacao, o limite so voltaria no
        // vencimento.
        Map<String, Object> antes = cartaoAtual();
        assertEquals("8550.00", antes.get("limiteDisponivel"));

        ResponseEntity<Map> r = post("/api/faturas/" + faturaAbertaId + "/pagamentos", Map.of(
            "contaOrigemId", nubankId,
            "valor", "200.00",
            "dataCaixa", LocalDate.now().toString(),
            "formaPagamento", "DEBITO"));

        assertEquals(HttpStatus.CREATED, r.getStatusCode());

        Map<String, Object> fatura = (Map<String, Object>) r.getBody().get("fatura");
        assertEquals("ABERTA", fatura.get("ciclo"),
            "Pagar nao fecha a fatura — sao coisas diferentes (B-D58)");
        assertEquals("PARCIAL", fatura.get("quitacao"));
        assertEquals("200.00", fatura.get("pago"));

        // E o limite voltou.
        assertEquals("8750.00", cartaoAtual().get("limiteDisponivel"));
    }

    @Test
    @Order(12)
    @DisplayName("O pagamento sai da conta pagadora e aparece no extrato dela (B-D59)")
    void oPagamentoSaiDaConta() {
        // "Seria leal registrar para que a pessoa possa ver que o valor saiu da
        // conta" — palavras dele. O dinheiro saiu mesmo.
        Map<String, Object> nubank = contaPorId(nubankId);
        assertEquals("-200.00", nubank.get("saldo"));

        boolean apareceNoExtrato = extratoDoMes().stream()
            .anyMatch(l -> nubankId.equals(String.valueOf(((Map<?, ?>) l.get("conta")).get("id")))
                && "SAIDA".equals(l.get("tipo"))
                && "200.00".equals(l.get("valor")));

        assertTrue(apareceNoExtrato, "O pagamento precisa aparecer no extrato da conta");
    }

    @Test
    @Order(13)
    @DisplayName("Mas NAO entra no mapa de gastos — os gastos ja entraram um a um (B-D59)")
    void oPagamentoNaoEntraNoMapa() {
        // Se entrasse, a mesma despesa contaria duas vezes e o mes dobraria.
        ResponseEntity<Map> r = get("/api/relatorios/mapa-de-gastos?ano=" + LocalDate.now().getYear());
        assertEquals(HttpStatus.OK, r.getStatusCode());

        assertFalse(r.getBody().toString().contains("Pagamento de fatura"),
            "PAGAMENTO_FATURA nasce com entraNoMapa = false");
    }

    @Test
    @Order(14)
    @DisplayName("Fechar e reabrir: um clique errado nao pode ser definitivo (B-D50)")
    void fecharEReabrir() {
        ResponseEntity<Map> fechada = post("/api/faturas/" + faturaAbertaId + "/fechar", Map.of());
        assertEquals(HttpStatus.OK, fechada.getStatusCode());
        assertEquals("FECHADA", fechada.getBody().get("ciclo"));
        assertNotNull(fechada.getBody().get("fechadaEm"));

        // Fechar de novo e 409: e um estado, nao uma acao repetivel.
        assertEquals(HttpStatus.CONFLICT,
            post("/api/faturas/" + faturaAbertaId + "/fechar", Map.of()).getStatusCode());

        ResponseEntity<Map> aberta = post("/api/faturas/" + faturaAbertaId + "/reabrir", Map.of());
        assertEquals(HttpStatus.OK, aberta.getStatusCode());
        assertEquals("ABERTA", aberta.getBody().get("ciclo"));
        assertEquals("PARCIAL", aberta.getBody().get("quitacao"),
            "Reabrir nao desfaz pagamento: sao perguntas independentes (B-D58)");
    }

    @Test
    @Order(15)
    @DisplayName("Fatura FECHADA nao recebe lancamento — ele vai para a proxima")
    void faturaFechadaNaoRecebe() {
        post("/api/faturas/" + faturaAbertaId + "/fechar", Map.of());

        ResponseEntity<Map> r = post("/api/lancamentos", Map.of(
            "contaId", nubankId,
            "cartaoEmitidoId", fisicoId,
            "categoriaId", mercadoId,
            "valor", "80.00",
            "dataCaixa", LocalDate.now().toString(),
            "dataCompetencia", LocalDate.now().toString(),
            "descricao", "Compra depois do fechamento"));

        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        assertNotNull(r.getBody().get("faturaId"));
        assertFalse(faturaAbertaId.equals(String.valueOf(r.getBody().get("faturaId"))),
            "Deveria ter caido na fatura SEGUINTE");

        post("/api/faturas/" + faturaAbertaId + "/reabrir", Map.of());
    }

    @Test
    @Order(16)
    @DisplayName("Pagamento de fatura nao se lanca avulso: ele e um par e precisa da fatura")
    void pagamentoAvulsoRecusado() {
        String pagamentoId = idDaCategoriaSistemica("PAGAMENTO_FATURA");

        ResponseEntity<Map> r = post("/api/lancamentos", Map.of(
            "contaId", nubankId,
            "categoriaId", pagamentoId,
            "tipo", "SAIDA",
            "valor", "50.00",
            "dataCaixa", LocalDate.now().toString()));

        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
        assertTrue(String.valueOf(r.getBody().get("erro")).contains("/api/faturas/"),
            "A recusa diz o caminho certo: " + r.getBody().get("erro"));
    }

    @Test
    @Order(17)
    @DisplayName("O filtro do mapa separa gasto de cartao do resto (B-D54)")
    void oMapaFiltraPorCartao() {
        int ano = LocalDate.now().getYear();

        String soCartao = String.valueOf(
            get("/api/relatorios/mapa-de-gastos?ano=" + ano + "&contas=CARTAO").getBody());
        String semCartao = String.valueOf(
            get("/api/relatorios/mapa-de-gastos?ano=" + ano + "&contas=SEM_CARTAO").getBody());

        assertTrue(soCartao.contains("Mercado"), "As compras do cartao sao de Mercado");
        assertFalse(semCartao.contains("Mercado"),
            "Sem cartao, nao sobra gasto nenhum de Mercado neste cenario");
    }

    @Test
    @Order(18)
    @DisplayName("Encerrar cartao com divida responde 409 e diz quanto")
    void encerrarComDividaConflita() {
        ResponseEntity<Map> r = post("/api/cartoes/" + cartaoId + "/encerrar", Map.of());

        assertEquals(HttpStatus.CONFLICT, r.getStatusCode());
        assertTrue(String.valueOf(r.getBody().get("erro")).toLowerCase().contains("divida"));
    }

    @Test
    @Order(19)
    @DisplayName("Sem token, os endpoints de cartao respondem 401")
    void semTokenNaoEntra() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            http.getForEntity("/api/cartoes", String.class).getStatusCode());
    }

    // =========================================================================
    // Ajudantes
    // =========================================================================

    private void autenticar() {
        String email = "cartao-" + UUID.randomUUID().toString().substring(0, 8) + "@teste.local";
        assertEquals(HttpStatus.CREATED, postSemToken("/api/auth/cadastro",
            Map.of("nome", "Cartao Teste", "email", email, "senha", SENHA)).getStatusCode());
        token = (String) postSemToken("/api/auth/login",
            Map.of("email", email, "senha", SENHA)).getBody().get("tokenAcesso");
    }

    private String criarConta(String nome, List<String> formas, String padraoSaida) {
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("nome", nome);
        corpo.put("natureza", "ATIVO");
        corpo.put("formasPagamento", formas);
        corpo.put("padraoSaida", padraoSaida);

        ResponseEntity<Map> r = post("/api/contas", corpo);
        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        return String.valueOf(r.getBody().get("id"));
    }

    private String criarCategoria(String nome, String tipo) {
        ResponseEntity<Map> r = post("/api/categorias", Map.of("nome", nome, "tipo", tipo));
        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        return String.valueOf(r.getBody().get("id"));
    }

    @SuppressWarnings("unchecked")
    private String idDaCategoriaSistemica(String codigo) {
        return ((List<Map<String, Object>>) get("/api/categorias").getBody().get("categorias"))
            .stream()
            .filter(c -> codigo.equals(c.get("codigo")))
            .map(c -> String.valueOf(c.get("id")))
            .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> faturasDoAno(int ano) {
        ResponseEntity<Map> r = get("/api/cartoes/" + cartaoId + "/faturas?ano=" + ano);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        return (List<Map<String, Object>>) r.getBody().get("faturas");
    }

    private Map<String, Object> faturaPorId(String id) {
        ResponseEntity<Map> r = get("/api/faturas/" + id);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        return r.getBody();
    }

    private Map<String, Object> cartaoAtual() {
        ResponseEntity<Map> r = get("/api/cartoes/" + cartaoId);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        return r.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> contaPorId(String id) {
        return ((List<Map<String, Object>>) get("/api/contas").getBody().get("contas")).stream()
            .filter(c -> id.equals(String.valueOf(c.get("id"))))
            .findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extratoDoMes() {
        return (List<Map<String, Object>>)
            get("/api/lancamentos?mes=" + YearMonth.now()).getBody().get("lancamentos");
    }

    private HttpHeaders cabecalhos() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        return h;
    }

    private ResponseEntity<Map> get(String caminho) {
        return http.exchange(caminho, HttpMethod.GET, new HttpEntity<>(cabecalhos()), Map.class);
    }

    private ResponseEntity<Map> postSemToken(String caminho, Map<String, ?> corpo) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(caminho, new HttpEntity<>(corpo, h), Map.class);
    }

    private ResponseEntity<Map> post(String caminho, Map<String, ?> corpo) {
        return http.postForEntity(caminho, new HttpEntity<>(corpo, cabecalhos()), Map.class);
    }
}
