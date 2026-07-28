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
 * Forma de pagamento (V11) pela porta da frente.
 *
 * <p>Nasceu do primeiro teste de negocio do sistema em alpha, em 27/07/2026: um
 * gasto de "gasolina, R$ 10" ficou registrado sem que desse para saber se foi
 * debito, pix ou boleto. O dado nao estava errado — nunca tinha sido capturado,
 * e isso nao se recupera depois.</p>
 *
 * <h3>O que este teste guarda, em uma frase</h3>
 *
 * <p>Que a forma de pagamento <b>explica</b> a movimentacao sem <b>alterar</b>
 * nada: a lista e por conta, o padrao e por sentido, e nenhum saldo muda por
 * causa dela.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FormaPagamentoApiTest extends IntegracaoTest {

    @Autowired
    private TestRestTemplate http;

    private static final String SENHA = "senha-com-mais-de-10";

    private static String token;

    private static String correnteId;   // DEBITO, PIX, BOLETO, CREDITO_EM_CONTA
    private static String carteiraId;   // so DINHEIRO
    private static String transporteId; // categoria SAIDA
    private static String salarioId;    // categoria ENTRADA

    private static String gastoNoBoletoId;

    // =========================================================================
    // O vocabulario vem do servidor
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("GET /api/formas-pagamento devolve a lista com os sentidos de cada forma")
    void vocabularioVemDoServidor() {
        autenticar();

        ResponseEntity<Map> r = get("/api/formas-pagamento");
        assertEquals(HttpStatus.OK, r.getStatusCode());

        List<Map<String, Object>> formas =
            (List<Map<String, Object>>) r.getBody().get("formasPagamento");

        assertEquals(8, formas.size(), "Oito formas, conferidas contra a tabela forma_pagamento");

        // Este endpoint existe para a tela NAO reescrever a regra de sentido em
        // JavaScript. Se ele parar de mandar os sentidos, o seletor volta a
        // oferecer boleto para salario.
        assertEquals(List.of("SAIDA"), sentidosDe(formas, "BOLETO"));
        assertEquals(List.of("ENTRADA"), sentidosDe(formas, "CREDITO_EM_CONTA"));
        assertEquals(List.of("ENTRADA", "SAIDA"), sentidosDe(formas, "PIX").stream().sorted().toList());

        assertEquals("Crédito em conta", nomeDe(formas, "CREDITO_EM_CONTA"),
            "O rotulo vem do servidor, para nao existir uma segunda lista de nomes");
    }

    // =========================================================================
    // A lista vive na conta
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("Conta criada SEM formas continua valida — o campo e opcional")
    void contaSemFormas() {
        ResponseEntity<Map> r = post("/api/contas",
            Map.of("nome", "Conta Antiga", "natureza", "ATIVO"));

        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        assertTrue(formasDe(r.getBody()).isEmpty());
        assertNull(r.getBody().get("padraoSaida"));
        assertNull(r.getBody().get("padraoEntrada"));
    }

    @Test
    @Order(3)
    @DisplayName("Conta com lista e DOIS padroes devolve os tres")
    void contaComDoisPadroes() {
        ResponseEntity<Map> r = post("/api/contas", Map.of(
            "nome", "Conta Corrente",
            "natureza", "ATIVO",
            "formasPagamento", List.of("DEBITO", "PIX", "BOLETO", "CREDITO_EM_CONTA"),
            "padraoSaida", "DEBITO",
            "padraoEntrada", "CREDITO_EM_CONTA"));

        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        assertEquals(4, formasDe(r.getBody()).size());
        assertEquals("DEBITO", r.getBody().get("padraoSaida"));
        assertEquals("CREDITO_EM_CONTA", r.getBody().get("padraoEntrada"));

        correnteId = String.valueOf(r.getBody().get("id"));
    }

    @Test
    @Order(4)
    @DisplayName("A carteira aceita so DINHEIRO — e por isso que a lista e por conta")
    void carteiraSoDinheiro() {
        ResponseEntity<Map> r = post("/api/contas", Map.of(
            "nome", "Carteira",
            "natureza", "ATIVO",
            "formasPagamento", List.of("DINHEIRO"),
            "padraoSaida", "DINHEIRO",
            "padraoEntrada", "DINHEIRO"));

        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        assertEquals(List.of("DINHEIRO"), formasDe(r.getBody()));
        assertEquals("DINHEIRO", r.getBody().get("padraoSaida"));
        assertEquals("DINHEIRO", r.getBody().get("padraoEntrada"),
            "DINHEIRO serve aos dois sentidos, entao pode ser padrao dos dois");

        carteiraId = String.valueOf(r.getBody().get("id"));
    }

    @Test
    @Order(5)
    @DisplayName("Padrao fora da lista e recusado")
    void padraoForaDaListaRecusado() {
        ResponseEntity<Map> r = post("/api/contas", Map.of(
            "nome", "Conta Incoerente",
            "natureza", "ATIVO",
            "formasPagamento", List.of("PIX"),
            "padraoSaida", "BOLETO"));

        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
        assertTrue(String.valueOf(r.getBody().get("erro")).contains("BOLETO"));
    }

    @Test
    @Order(6)
    @DisplayName("Padrao de SAIDA com forma que so serve a ENTRADA e recusado")
    void padraoComSentidoErradoRecusado() {
        // CREDITO_EM_CONTA e como o salario chega. Nao da para pagar gasolina
        // com ela, entao ela nao pode ser o padrao de saida de conta nenhuma.
        ResponseEntity<Map> r = post("/api/contas", Map.of(
            "nome", "Conta Ao Contrario",
            "natureza", "ATIVO",
            "formasPagamento", List.of("CREDITO_EM_CONTA"),
            "padraoSaida", "CREDITO_EM_CONTA"));

        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
        String erro = String.valueOf(r.getBody().get("erro"));
        assertTrue(erro.contains("CREDITO_EM_CONTA") && erro.contains("SAIDA"),
            "A mensagem diz a forma e o sentido: " + erro);
    }

    // =========================================================================
    // O padrao por sentido
    // =========================================================================

    @Test
    @Order(7)
    @DisplayName("Saida sem forma informada assume o padrao de SAIDA da conta")
    void saidaAssumeOPadraoDeSaida() {
        transporteId = criarCategoria("Transporte", "SAIDA");
        salarioId = criarCategoria("Salario", "ENTRADA");

        ResponseEntity<Map> r = post("/api/lancamentos", Map.of(
            "contaId", correnteId,
            "categoriaId", transporteId,
            "valor", "10.00",
            "dataCaixa", LocalDate.now().toString(),
            "descricao", "Gasolina"));

        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        assertEquals("DEBITO", r.getBody().get("formaPagamento"));
    }

    @Test
    @Order(8)
    @DisplayName("ENTRADA assume o padrao de ENTRADA: o salario e CREDITADO")
    void entradaAssumeOPadraoDeEntrada() {
        // A primeira versao desta funcionalidade recusava forma em ENTRADA, com
        // o argumento de que "salario nao e pago no debito". O argumento estava
        // certo e o alvo errado: a pergunta util e como o dinheiro SE MOVEU, e
        // ela tem resposta nos dois sentidos.
        ResponseEntity<Map> r = post("/api/lancamentos", Map.of(
            "contaId", correnteId,
            "categoriaId", salarioId,
            "valor", "5000.00",
            "dataCaixa", LocalDate.now().toString(),
            "descricao", "Salario do mes"));

        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        assertEquals("CREDITO_EM_CONTA", r.getBody().get("formaPagamento"));
    }

    @Test
    @Order(9)
    @DisplayName("O padrao e POR CONTA: o mesmo gasto na carteira vira DINHEIRO, nao DEBITO")
    void oPadraoEPorConta() {
        // Este e o teste que justifica o desenho. A regra pedida foi "se nao
        // indicar, salva debito"; debito literal gravaria na carteira uma forma
        // que a lista dela recusa, e em silencio.
        ResponseEntity<Map> r = post("/api/lancamentos", Map.of(
            "contaId", carteiraId,
            "categoriaId", transporteId,
            "valor", "10.00",
            "dataCaixa", LocalDate.now().toString(),
            "descricao", "Gasolina em especie"));

        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        assertEquals("DINHEIRO", r.getBody().get("formaPagamento"));
    }

    @Test
    @Order(10)
    @DisplayName("Saldo inicial NAO vira 'pago no debito' — a guarda da categoria sistemica")
    void saldoInicialNaoRecebePadrao() {
        // Sem esta guarda, o lancamento de abertura de toda conta nova
        // apareceria como pago no debito: lixo visivel na primeira tela que a
        // pessoa abre, e que ninguem digitou.
        ResponseEntity<Map> conta = post("/api/contas", Map.of(
            "nome", "Poupanca",
            "natureza", "ATIVO",
            "saldoInicial", "3000.00",
            "formasPagamento", List.of("DEBITO", "CREDITO_EM_CONTA"),
            "padraoSaida", "DEBITO",
            "padraoEntrada", "CREDITO_EM_CONTA"));

        assertEquals(HttpStatus.CREATED, conta.getStatusCode());
        assertEquals("3000.00", conta.getBody().get("saldo"));

        Map<String, Object> abertura = umLancamento(
            String.valueOf(conta.getBody().get("id")), "Saldo inicial");

        assertNotNull(abertura, "O saldo inicial e um lancamento de verdade (A13)");
        assertNull(abertura.get("formaPagamento"),
            "Saldo de abertura nao se moveu de forma alguma");
    }

    @Test
    @Order(11)
    @DisplayName("Conta sem padrao daquele sentido grava nulo, sem escolher sozinha")
    void semPadraoGravaNulo() {
        ResponseEntity<Map> conta = post("/api/contas", Map.of(
            "nome", "Conta Sem Preferencia",
            "natureza", "ATIVO",
            "formasPagamento", List.of("PIX", "BOLETO")));
        assertEquals(HttpStatus.CREATED, conta.getStatusCode());

        ResponseEntity<Map> r = post("/api/lancamentos", Map.of(
            "contaId", String.valueOf(conta.getBody().get("id")),
            "categoriaId", transporteId,
            "valor", "40.00",
            "dataCaixa", LocalDate.now().toString(),
            "descricao", "Sem dizer como"));

        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        assertNull(r.getBody().get("formaPagamento"),
            "Sem padrao, 'nao sei' e melhor resposta que um palpite");
    }

    // =========================================================================
    // As duas perguntas que a forma precisa passar
    // =========================================================================

    @Test
    @Order(12)
    @DisplayName("Forma que a conta nao aceita e recusada, dizendo quais ela aceita")
    void formaForaDaListaDaConta() {
        ResponseEntity<Map> r = post("/api/lancamentos", Map.of(
            "contaId", carteiraId,
            "categoriaId", transporteId,
            "valor", "10.00",
            "dataCaixa", LocalDate.now().toString(),
            "formaPagamento", "BOLETO"));

        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
        String erro = String.valueOf(r.getBody().get("erro"));
        assertTrue(erro.contains("BOLETO") && erro.contains("DINHEIRO"), erro);
    }

    @Test
    @Order(13)
    @DisplayName("Forma que a conta aceita mas que NAO serve ao sentido tambem e recusada")
    void formaComSentidoErrado() {
        // A conta corrente aceita BOLETO e aceita CREDITO_EM_CONTA. A primeira
        // pergunta ("a conta aceita?") passa nas duas trocas abaixo; quem barra
        // e a segunda ("serve a este sentido?").
        ResponseEntity<Map> salarioNoBoleto = post("/api/lancamentos", Map.of(
            "contaId", correnteId,
            "categoriaId", salarioId,
            "valor", "5000.00",
            "dataCaixa", LocalDate.now().toString(),
            "formaPagamento", "BOLETO"));

        assertEquals(HttpStatus.FORBIDDEN, salarioNoBoleto.getStatusCode());
        assertTrue(String.valueOf(salarioNoBoleto.getBody().get("erro")).contains("ENTRADA"),
            "Salario pago no boleto nao existe: " + salarioNoBoleto.getBody().get("erro"));

        ResponseEntity<Map> gastoNoCredito = post("/api/lancamentos", Map.of(
            "contaId", correnteId,
            "categoriaId", transporteId,
            "valor", "10.00",
            "dataCaixa", LocalDate.now().toString(),
            "formaPagamento", "CREDITO_EM_CONTA"));

        assertEquals(HttpStatus.FORBIDDEN, gastoNoCredito.getStatusCode());
        assertTrue(String.valueOf(gastoNoCredito.getBody().get("erro")).contains("SAIDA"));
    }

    @Test
    @Order(14)
    @DisplayName("Forma informada explicitamente vence o padrao")
    void formaInformadaVenceOPadrao() {
        ResponseEntity<Map> r = post("/api/lancamentos", Map.of(
            "contaId", correnteId,
            "categoriaId", transporteId,
            "valor", "120.00",
            "dataCaixa", LocalDate.now().toString(),
            "descricao", "Conta de luz",
            "formaPagamento", "BOLETO"));

        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        assertEquals("BOLETO", r.getBody().get("formaPagamento"));

        gastoNoBoletoId = String.valueOf(r.getBody().get("id"));
    }

    // =========================================================================
    // Alterar a lista depois
    // =========================================================================

    @Test
    @Order(15)
    @DisplayName("Trocar o padrao de saida funciona — o indice parcial nao atrapalha")
    void trocarOPadrao() {
        // Marcar PIX antes de desmarcar DEBITO deixaria duas linhas padrao ao
        // mesmo tempo, e ux_cfp_padrao_saida recusaria. Este teste guarda a
        // ordem dos tres passos de gravarFormas.
        ResponseEntity<Map> r = put("/api/contas/" + correnteId + "/formas-pagamento",
            Map.of("formas", List.of("DEBITO", "PIX", "BOLETO", "CREDITO_EM_CONTA"),
                   "padraoSaida", "PIX",
                   "padraoEntrada", "CREDITO_EM_CONTA"));

        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals("PIX", r.getBody().get("padraoSaida"));
        assertEquals("CREDITO_EM_CONTA", r.getBody().get("padraoEntrada"));
        assertEquals(4, formasDe(r.getBody()).size(), "A lista nao mudou, so o padrao");
    }

    @Test
    @Order(16)
    @DisplayName("Remover forma que algum lancamento usa responde 409 e diz quantos")
    void removerFormaEmUsoConflita() {
        // A alternativa seria apagar a forma dos lancamentos antigos —
        // destruindo em silencio exatamente o dado que a V11 veio registrar.
        ResponseEntity<Map> r = put("/api/contas/" + correnteId + "/formas-pagamento",
            Map.of("formas", List.of("DEBITO", "PIX", "CREDITO_EM_CONTA"),
                   "padraoSaida", "PIX",
                   "padraoEntrada", "CREDITO_EM_CONTA"));

        assertEquals(HttpStatus.CONFLICT, r.getStatusCode());
        String erro = String.valueOf(r.getBody().get("erro"));
        assertTrue(erro.contains("BOLETO"), erro);
        assertTrue(erro.contains("1"), "Diz quantos lancamentos a usam: " + erro);
    }

    @Test
    @Order(17)
    @DisplayName("Remover forma NAO usada funciona")
    void removerFormaLivreFunciona() {
        ResponseEntity<Map> r = put("/api/contas/" + carteiraId + "/formas-pagamento",
            Map.of("formas", List.of("DINHEIRO", "PIX"), "padraoSaida", "DINHEIRO"));
        assertEquals(HttpStatus.OK, r.getStatusCode());

        ResponseEntity<Map> volta = put("/api/contas/" + carteiraId + "/formas-pagamento",
            Map.of("formas", List.of("DINHEIRO"),
                   "padraoSaida", "DINHEIRO",
                   "padraoEntrada", "DINHEIRO"));

        assertEquals(HttpStatus.OK, volta.getStatusCode());
        assertEquals(List.of("DINHEIRO"), formasDe(volta.getBody()),
            "PIX saiu porque nenhum lancamento da carteira o usava");
    }

    // =========================================================================
    // Edicao
    // =========================================================================

    @Test
    @Order(18)
    @DisplayName("PUT com forma vazia LIMPA o campo, sem reaplicar o padrao")
    void putComFormaVaziaLimpa() {
        // Diferente do POST de proposito: no PUT a tela ja mostra o valor atual,
        // entao mandar vazio e um ato. Reaplicar o padrao desfaria, no servidor,
        // o que a pessoa acabou de fazer.
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("contaId", correnteId);
        corpo.put("categoriaId", transporteId);
        corpo.put("valor", "120.00");
        corpo.put("dataCaixa", LocalDate.now().toString());
        corpo.put("descricao", "Conta de luz");
        corpo.put("formaPagamento", null);

        ResponseEntity<Map> r = put("/api/lancamentos/" + gastoNoBoletoId, corpo);

        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertNull(r.getBody().get("formaPagamento"));
    }

    @Test
    @Order(19)
    @DisplayName("O extrato mostra a forma de cada lancamento")
    void oExtratoMostraAForma() {
        List<Map<String, Object>> extrato = extratoDoMes();

        assertFalse(extrato.isEmpty());
        assertTrue(extrato.stream().anyMatch(l -> "DEBITO".equals(l.get("formaPagamento"))));
        assertTrue(extrato.stream().anyMatch(l -> "DINHEIRO".equals(l.get("formaPagamento"))));
        assertTrue(extrato.stream().anyMatch(l -> "CREDITO_EM_CONTA".equals(l.get("formaPagamento"))));
        assertTrue(extrato.stream().anyMatch(l -> l.get("formaPagamento") == null));
    }

    @Test
    @Order(20)
    @DisplayName("Nada disso mexeu no saldo: forma de pagamento explica, nao calcula")
    void osSaldosNaoMudaram() {
        // A garantia mais importante do arquivo. Se um dia alguem fizer a forma
        // de pagamento entrar em alguma soma, este teste cai.
        Map<String, Object> corrente = contaPorId(correnteId);

        // 5000 de salario, menos 10 de gasolina, menos 120 de luz.
        assertEquals("4870.00", corrente.get("saldo"),
            "O saldo continua sendo a soma dos lancamentos, e so isso (P1)");
    }

    @Test
    @Order(21)
    @DisplayName("Sem token, os dois endpoints novos respondem 401")
    void semTokenNaoEntra() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            http.getForEntity("/api/formas-pagamento", String.class).getStatusCode());

        assertEquals(HttpStatus.UNAUTHORIZED, http.exchange(
            "/api/contas/" + correnteId + "/formas-pagamento", HttpMethod.PUT,
            new HttpEntity<>(Map.of("formas", List.of("PIX"))), String.class).getStatusCode());
    }

    // =========================================================================
    // Ajudantes
    // =========================================================================

    private void autenticar() {
        String email = "forma-" + UUID.randomUUID().toString().substring(0, 8) + "@teste.local";

        ResponseEntity<Map> cadastro = postSemToken("/api/auth/cadastro",
            Map.of("nome", "Forma Teste", "email", email, "senha", SENHA));
        assertEquals(HttpStatus.CREATED, cadastro.getStatusCode());

        ResponseEntity<Map> login = postSemToken("/api/auth/login",
            Map.of("email", email, "senha", SENHA));
        token = (String) login.getBody().get("tokenAcesso");
    }

    private String criarCategoria(String nome, String tipo) {
        ResponseEntity<Map> r = post("/api/categorias", Map.of("nome", nome, "tipo", tipo));
        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        return String.valueOf(r.getBody().get("id"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> sentidosDe(List<Map<String, Object>> formas, String valor) {
        return (List<String>) formas.stream()
            .filter(f -> valor.equals(f.get("valor")))
            .findFirst().orElseThrow()
            .get("sentidos");
    }

    private static String nomeDe(List<Map<String, Object>> formas, String valor) {
        return String.valueOf(formas.stream()
            .filter(f -> valor.equals(f.get("valor")))
            .findFirst().orElseThrow()
            .get("nome"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> formasDe(Map corpo) {
        return (List<String>) corpo.get("formasPagamento");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extratoDoMes() {
        ResponseEntity<Map> r = get("/api/lancamentos?mes=" + YearMonth.now());
        assertEquals(HttpStatus.OK, r.getStatusCode());
        return (List<Map<String, Object>>) r.getBody().get("lancamentos");
    }

    private Map<String, Object> umLancamento(String contaId, String descricao) {
        return extratoDoMes().stream()
            .filter(l -> descricao.equals(l.get("descricao")))
            .filter(l -> contaId.equals(String.valueOf(((Map<?, ?>) l.get("conta")).get("id"))))
            .findFirst()
            .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> contaPorId(String id) {
        ResponseEntity<Map> r = get("/api/contas");
        assertEquals(HttpStatus.OK, r.getStatusCode());

        return ((List<Map<String, Object>>) r.getBody().get("contas")).stream()
            .filter(c -> id.equals(String.valueOf(c.get("id"))))
            .findFirst()
            .orElseThrow();
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

    private ResponseEntity<Map> put(String caminho, Map<String, ?> corpo) {
        return http.exchange(caminho, HttpMethod.PUT,
            new HttpEntity<>(corpo, cabecalhos()), Map.class);
    }
}
