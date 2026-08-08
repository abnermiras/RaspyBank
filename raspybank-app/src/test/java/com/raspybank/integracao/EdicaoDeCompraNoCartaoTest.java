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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Editar uma compra no cartao nao pode tira-la do total da fatura.
 *
 * <h3>O defeito que este teste guarda (08/08/2026)</h3>
 *
 * <p>Uma compra de 116,76 no UltraVioleta foi editada para 119,97. A lista da
 * fatura passou a mostrar 119,97, <b>e o total a pagar caiu 119,97</b> — o valor
 * inteiro, nao a diferenca. A compra tinha sumido da conta do cartao sem sumir
 * da fatura.</p>
 *
 * <p><b>Causa:</b> a tela manda o BANCO e o plastico (B-D61), e so o POST
 * traduzia isso na conta do cartao. O PUT gravava o banco cru em
 * {@code conta_id}. Como {@code app_total_da_fatura} soma por
 * {@code fatura_id AND conta_id = cartao} — o filtro de conta existe para nao
 * contar a perna de saida do pagamento (B-D59) — a compra saia do total; e como
 * {@code app_extrato_da_fatura} filtra so por {@code fatura_id}, ela continuava
 * na lista. A fatura mentia sem dar sinal.</p>
 *
 * <p>Por isso a asserção que importa aqui e a do TOTAL, e nao a do valor do
 * lancamento: era exatamente o valor do lancamento que estava certo enquanto o
 * total estava errado.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EdicaoDeCompraNoCartaoTest extends IntegracaoTest {

    @Autowired
    private TestRestTemplate http;

    private static final String SENHA = "senha-com-mais-de-10";

    private static String token;
    private static String bancoId;
    private static String categoriaId;
    private static String cartaoId;
    private static String plasticoId;

    private static String compraId;
    private static String faturaId;

    /**
     * O vencimento da fatura, que e onde o POST poe a data de caixa (F14). O
     * formulario de edicao reenvia o valor que ele mostra — e o que ele mostra e
     * este, nao "hoje".
     */
    private static String dataCaixaDaCompra;

    @Test
    @Order(1)
    @DisplayName("Uma compra de 116,76 no cartao entra inteira no total da fatura")
    void compraEntraNoTotal() {
        autenticar();

        bancoId = criarConta("Nubank", List.of("DEBITO", "PIX"), "DEBITO");
        categoriaId = criarCategoria("Eletronicos", "SAIDA");

        ResponseEntity<Map> cartao = post("/api/cartoes", Map.of(
            "contaBancoId", bancoId,
            "nome", "UltraVioleta",
            "limite", "10000.00",
            "diaVencimento", 15,
            "finalDoCartao", "9911"));

        assertEquals(HttpStatus.CREATED, cartao.getStatusCode());
        cartaoId = String.valueOf(cartao.getBody().get("id"));
        plasticoId = String.valueOf(
            ((List<Map<String, Object>>) cartao.getBody().get("emitidos")).get(0).get("id"));

        ResponseEntity<Map> compra = post("/api/lancamentos",
            corpoDaCompra("116.76", LocalDate.now().toString()));
        assertEquals(HttpStatus.CREATED, compra.getStatusCode());

        compraId = String.valueOf(compra.getBody().get("id"));
        faturaId = String.valueOf(compra.getBody().get("faturaId"));
        dataCaixaDaCompra = String.valueOf(compra.getBody().get("dataCaixa"));
        assertNotNull(faturaId);

        assertEquals("116.76", faturaPorId(faturaId).get("total"),
            "O POST sempre esteve correto — e a linha de base do teste");
    }

    @Test
    @Order(2)
    @DisplayName("Corrigir o valor para 119,97 move o TOTAL junto, e nao so a lista (bug 08/08/2026)")
    void editarOValorMoveOTotal() {
        Map<String, Object> corpo = corpoDaCompra("119.97", dataCaixaDaCompra);

        ResponseEntity<Map> r = put("/api/lancamentos/" + compraId, corpo);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals("119.97", r.getBody().get("valor"));

        // O sintoma visivel era este numero ficar em 0.00 enquanto o de cima
        // dizia 119.97.
        assertEquals("119.97", faturaPorId(faturaId).get("total"),
            "A compra editada tem de continuar sendo somada na fatura");

        // E a razao de fundo: ela continua morando na conta do CARTAO. O extrato
        // mostra o banco (B-D61), entao quem responde de verdade e o total.
        assertEquals("Nubank", ((Map<?, ?>) r.getBody().get("conta")).get("nome"));
        assertEquals("9911", ((Map<?, ?>) r.getBody().get("cartao")).get("finalDoCartao"),
            "O PUT tambem passou a gravar o plastico, que antes ele ignorava");
    }

    @Test
    @Order(3)
    @DisplayName("O limite consumido acompanha a edicao — e a mesma soma, pela outra porta")
    void limiteAcompanha() {
        ResponseEntity<Map> r = get("/api/cartoes/" + cartaoId);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals("119.97", r.getBody().get("limiteConsumido"));
    }

    @Test
    @Order(4)
    @DisplayName("Tirar a compra do cartao sem tirar da fatura e RECUSADO, nao aceito em silencio")
    void tirarDoCartaoSemTirarDaFaturaERecusado() {
        // O corpo sem cartaoEmitidoId e exatamente o que produzia a corrupcao:
        // conta_id vira o banco e a fatura fica apontando para o cartao. Agora a
        // guarda recusa em vez de gravar uma fatura que mente.
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("contaId", bancoId);
        corpo.put("categoriaId", categoriaId);
        corpo.put("valor", "119.97");
        corpo.put("dataCaixa", dataCaixaDaCompra);
        corpo.put("descricao", "meu relogio samsung");
        corpo.put("formaPagamento", "PIX");

        assertEquals(HttpStatus.FORBIDDEN,
            put("/api/lancamentos/" + compraId, corpo).getStatusCode());

        // E o dado nao se mexeu: a recusa e a transacao inteira voltando.
        assertEquals("119.97", faturaPorId(faturaId).get("total"));
    }

    // =========================================================================
    // Ajudantes
    // =========================================================================

    private Map<String, Object> corpoDaCompra(String valor, String dataCaixa) {
        Map<String, Object> corpo = new HashMap<>();
        // Banco + plastico: e assim que a tela manda, nos DOIS verbos (B-D61).
        corpo.put("contaId", bancoId);
        corpo.put("cartaoEmitidoId", plasticoId);
        corpo.put("categoriaId", categoriaId);
        corpo.put("valor", valor);
        corpo.put("dataCaixa", dataCaixa);
        corpo.put("dataCompetencia", LocalDate.now().toString());
        corpo.put("descricao", "meu relogio samsung");
        return corpo;
    }

    private void autenticar() {
        String email = "edicao-" + UUID.randomUUID().toString().substring(0, 8) + "@teste.local";
        assertEquals(HttpStatus.CREATED, postSemToken("/api/auth/cadastro",
            Map.of("nome", "Edicao Teste", "email", email, "senha", SENHA)).getStatusCode());
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

    private Map<String, Object> faturaPorId(String id) {
        ResponseEntity<Map> r = get("/api/faturas/" + id);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        return r.getBody();
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

    private ResponseEntity<Map> post(String caminho, Map<String, ?> corpo) {
        return http.postForEntity(caminho, new HttpEntity<>(corpo, cabecalhos()), Map.class);
    }

    private ResponseEntity<Map> postSemToken(String caminho, Map<String, ?> corpo) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(caminho, new HttpEntity<>(corpo, h), Map.class);
    }

    private ResponseEntity<Map> put(String caminho, Map<String, ?> corpo) {
        return http.exchange(caminho, HttpMethod.PUT,
            new HttpEntity<>(corpo, cabecalhos()), Map.class);
    }
}
