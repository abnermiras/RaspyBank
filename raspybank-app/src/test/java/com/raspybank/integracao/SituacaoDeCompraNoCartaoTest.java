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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A situacao de uma compra de cartao responde a FATURA, nao a data (I-29).
 *
 * <h3>O defeito que este teste guarda (09/08/2026)</h3>
 *
 * <p>Uma fatura de 282,00 foi fechada a mao e paga por inteiro, e as seis
 * compras dentro dela continuaram {@code PREVISTO}. A fatura dizia QUITADA no
 * cabecalho e "o dinheiro ainda vai sair" em cada linha.</p>
 *
 * <p><b>Causa:</b> a data de caixa de uma compra e o vencimento da fatura
 * (F14), e a situacao derivava so da data (B-D9). Nem {@code fechar} nem
 * {@code pagar} tocavam nela, e {@code realizarPrevistosVencidos} filtrava so
 * por data, sem olhar fatura nenhuma.</p>
 *
 * <p>O erro acontecia nos <b>dois</b> sentidos, e por isso este teste tem duas
 * metades: fatura paga antes do vencimento mantinha as compras previstas, e
 * fatura vencida e nao paga virava tudo para realizado no dia do vencimento —
 * afirmando um gasto que nao houve.</p>
 *
 * <h3>A regra</h3>
 *
 * <p>Compra de cartao e {@code REALIZADO} se, e somente se, a fatura estiver
 * FECHADA e QUITADA. Em qualquer outro caso, {@code PREVISTO} — independente da
 * data. O resto do sistema continua derivando da data (B-D9 intacto), e e o que
 * o {@code SituacaoVencidaTest} guarda.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SituacaoDeCompraNoCartaoTest extends IntegracaoTest {

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
    private static String vencimento;

    // =========================================================================
    // Metade 1 — fatura paga: so realiza depois de FECHADA
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Compra em fatura aberta nasce PREVISTO, com a data de caixa no vencimento")
    void compraNasceP1revista() {
        autenticar();

        bancoId = criarConta("Nubank");
        categoriaId = criarCategoria("Mercado");

        ResponseEntity<Map> cartao = post("/api/cartoes", Map.of(
            "contaBancoId", bancoId,
            "nome", "UltraVioleta",
            "limite", "10000.00",
            "diaVencimento", 18,
            "finalDoCartao", "4352"));

        assertEquals(HttpStatus.CREATED, cartao.getStatusCode());
        cartaoId = String.valueOf(cartao.getBody().get("id"));
        plasticoId = String.valueOf(
            ((List<Map<String, Object>>) cartao.getBody().get("emitidos")).get(0).get("id"));

        ResponseEntity<Map> compra = post("/api/lancamentos", corpoDaCompra("100.00"));
        assertEquals(HttpStatus.CREATED, compra.getStatusCode());

        compraId = String.valueOf(compra.getBody().get("id"));
        faturaId = String.valueOf(compra.getBody().get("faturaId"));
        vencimento = String.valueOf(compra.getBody().get("dataCaixa"));
        assertNotNull(faturaId);

        assertNotEquals(LocalDate.now().toString(), vencimento,
            "A data de caixa da compra e o VENCIMENTO (F14), nao hoje —"
                + " se elas coincidissem o teste nao provaria nada");

        assertEquals("PREVISTO", situacaoDaCompra());
        assertEquals("ABERTA", faturaAtual().get("ciclo"));
    }

    @Test
    @Order(2)
    @DisplayName("Pagar por inteiro a fatura ABERTA nao realiza nada — a regra e fechada E quitada")
    void antecipacaoNaoRealiza() {
        ResponseEntity<Map> r = post("/api/faturas/" + faturaId + "/pagamentos", Map.of(
            "contaOrigemId", bancoId,
            "valor", "100.00",
            "dataCaixa", LocalDate.now().toString(),
            "formaPagamento", "PIX"));

        assertEquals(HttpStatus.CREATED, r.getStatusCode());

        Map<String, Object> fatura = faturaAtual();
        assertEquals("QUITADA", fatura.get("quitacao"));
        assertEquals("ABERTA", fatura.get("ciclo"));

        // Esta e a assercao que separa a regra escolhida da alternativa que foi
        // recusada. Com "quitada sozinha", esta compra ja seria REALIZADO — e a
        // proxima compra a cair nesta fatura desfaria a quitacao e a mandaria de
        // volta para previsto, fazendo a fatura piscar.
        assertEquals("PREVISTO", situacaoDaCompra(),
            "Fatura aberta nao realiza compra, nem paga por inteiro (antecipacao, B-D57)");
    }

    @Test
    @Order(3)
    @DisplayName("Fechar a fatura ja quitada realiza as compras, sem esperar o vencimento")
    void fecharQuitadaRealiza() {
        assertEquals(HttpStatus.OK,
            post("/api/faturas/" + faturaId + "/fechar", Map.of()).getStatusCode());

        // O sintoma relatado era exatamente este campo continuar em PREVISTO
        // ate o dia do vencimento, com a fatura quitada.
        assertEquals("REALIZADO", situacaoDaCompra());

        assertNotEquals(LocalDate.now().toString(), vencimento,
            "O vencimento continua no futuro — quem realizou foi a fatura, nao o calendario");
    }

    @Test
    @Order(4)
    @DisplayName("Reabrir devolve para PREVISTO, e fechar de novo devolve para REALIZADO")
    void reabrirEFecharVaiEVolta() {
        assertEquals(HttpStatus.OK,
            post("/api/faturas/" + faturaId + "/reabrir", Map.of()).getStatusCode());
        assertEquals("PREVISTO", situacaoDaCompra(),
            "Reabrir uma fatura paga desfaz metade da condicao (B-D50)");

        assertEquals(HttpStatus.OK,
            post("/api/faturas/" + faturaId + "/fechar", Map.of()).getStatusCode());
        assertEquals("REALIZADO", situacaoDaCompra(),
            "E a regra e de mao dupla: volta sozinha, sem ninguem corrigir nada");
    }

    // =========================================================================
    // Metade 2 — fatura nao paga: nao realiza NEM depois de vencida
    // =========================================================================

    @Test
    @Order(5)
    @DisplayName("Compra de fatura NAO quitada volta a PREVISTO mesmo com a data de caixa vencida")
    void vencidaSemPagamentoNaoRealiza() {
        // Uma compra nova cai na fatura seguinte, que esta aberta e sem pagamento.
        ResponseEntity<Map> compra = post("/api/lancamentos", corpoDaCompra("70.00"));
        assertEquals(HttpStatus.CREATED, compra.getStatusCode());

        String segundaId = String.valueOf(compra.getBody().get("id"));
        String segundaFatura = String.valueOf(compra.getBody().get("faturaId"));
        assertNotEquals(faturaId, segundaFatura,
            "A primeira fatura foi fechada, entao a compra nova vai para a proxima");

        // Puxa a data de caixa para ONTEM. Sem a correcao do I-29 este e o
        // estado em que realizarPrevistosVencidos viraria a compra para
        // REALIZADO na proxima leitura — afirmando um gasto que ninguem pagou.
        Map<String, Object> corpo = corpoDaCompra("70.00");
        corpo.put("dataCaixa", LocalDate.now().minusDays(1).toString());
        assertEquals(HttpStatus.OK, put("/api/lancamentos/" + segundaId, corpo).getStatusCode());

        assertEquals("NADA_PAGO", faturaPorId(segundaFatura).get("quitacao"));
        assertEquals("PREVISTO", situacaoNaFatura(segundaFatura, segundaId),
            "Fatura nao quitada mantem a compra prevista, por mais vencida que a data esteja");
    }

    @Test
    @Order(6)
    @DisplayName("O pagamento em si continua derivando da data — as duas pernas dele")
    void oPagamentoSegueADataENaoAFatura() {
        // A perna de SAIDA vive na conta corrente e a de ENTRADA na conta do
        // cartao, e as DUAS carregam fatura_id (B-D59). Se o recorte do I-29
        // pegasse qualquer uma, o pagamento ficaria congelado junto com as
        // compras — e um pagamento feito hoje apareceria como previsto.
        List<Map<String, Object>> linhas = lancamentosDa(faturaId);

        Map<String, Object> entradaDoPagamento = linhas.stream()
            .filter(l -> "ENTRADA".equals(l.get("tipo")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("A perna de entrada do pagamento sumiu"));

        assertEquals("REALIZADO", entradaDoPagamento.get("situacao"),
            "Pago hoje e realizado hoje — a data manda, porque isto nao e compra");
    }

    // =========================================================================
    // Ajudantes
    // =========================================================================

    private String situacaoDaCompra() {
        return situacaoNaFatura(faturaId, compraId);
    }

    private String situacaoNaFatura(String fatura, String lancamentoId) {
        return lancamentosDa(fatura).stream()
            .filter(l -> lancamentoId.equals(String.valueOf(l.get("id"))))
            .map(l -> String.valueOf(l.get("situacao")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Lancamento nao esta no extrato da fatura"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> lancamentosDa(String fatura) {
        ResponseEntity<Map> r = get("/api/faturas/" + fatura + "/lancamentos");
        assertEquals(HttpStatus.OK, r.getStatusCode());
        return (List<Map<String, Object>>) r.getBody().get("lancamentos");
    }

    private Map<String, Object> faturaAtual() {
        return faturaPorId(faturaId);
    }

    private Map<String, Object> faturaPorId(String id) {
        ResponseEntity<Map> r = get("/api/faturas/" + id);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        return r.getBody();
    }

    private Map<String, Object> corpoDaCompra(String valor) {
        Map<String, Object> corpo = new HashMap<>();
        // Banco + plastico: e assim que a tela manda (B-D61).
        corpo.put("contaId", bancoId);
        corpo.put("cartaoEmitidoId", plasticoId);
        corpo.put("categoriaId", categoriaId);
        corpo.put("valor", valor);
        corpo.put("dataCaixa", LocalDate.now().toString());
        corpo.put("dataCompetencia", LocalDate.now().toString());
        corpo.put("descricao", "compra de teste");
        return corpo;
    }

    private void autenticar() {
        String email = "situacao-" + UUID.randomUUID().toString().substring(0, 8) + "@teste.local";
        assertEquals(HttpStatus.CREATED, postSemToken("/api/auth/cadastro",
            Map.of("nome", "Situacao Teste", "email", email, "senha", SENHA)).getStatusCode());
        token = (String) postSemToken("/api/auth/login",
            Map.of("email", email, "senha", SENHA)).getBody().get("tokenAcesso");
    }

    private String criarConta(String nome) {
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("nome", nome);
        corpo.put("natureza", "ATIVO");
        corpo.put("formasPagamento", List.of("DEBITO", "PIX"));
        corpo.put("padraoSaida", "DEBITO");

        ResponseEntity<Map> r = post("/api/contas", corpo);
        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        return String.valueOf(r.getBody().get("id"));
    }

    private String criarCategoria(String nome) {
        ResponseEntity<Map> r = post("/api/categorias", Map.of("nome", nome, "tipo", "SAIDA"));
        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        return String.valueOf(r.getBody().get("id"));
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
