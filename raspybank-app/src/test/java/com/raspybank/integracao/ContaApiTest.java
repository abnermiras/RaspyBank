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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A API de contas pela porta da frente — fatia 2, contrato em
 * {@code docs/api.md} §4.
 *
 * <p>O que este teste guarda, alem dos codigos HTTP: que <b>nao existe coluna
 * de saldo</b> (P1). Todo numero conferido aqui e soma de lancamento
 * calculada na hora — inclusive o saldo inicial, que e um lancamento na
 * categoria sistemica AJUSTE e nao um campo magico (A13).</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ContaApiTest extends IntegracaoTest {

    @Autowired
    private TestRestTemplate http;

    private static final String SENHA = "senha-com-mais-de-10";

    private static String email;
    private static String usuarioId;
    private static String ambienteId;
    private static String token;

    private static String correnteId;
    private static String cartaoId;

    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Ambiente novo nao tem conta nenhuma")
    void comecaVazio() {
        autenticar();
        assertTrue(listar(false).isEmpty(),
            "Conta e sempre criacao explicita — nada nasce junto com o ambiente");
    }

    @Test
    @Order(2)
    @DisplayName("Criar conta sem saldo inicial devolve 201 com saldo zero")
    void criarContaSimples() {
        ResponseEntity<Map> r = postAutenticado("/api/contas",
            Map.of("nome", "Conta Corrente", "natureza", "ATIVO"));

        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        assertEquals("ATIVO", r.getBody().get("natureza"));
        assertEquals("0.00", r.getBody().get("saldo"), "Dinheiro trafega como string, com duas casas");
        assertEquals("0.00", r.getBody().get("saldoComPrevistos"));
        assertNull(r.getBody().get("encerradaEm"));

        correnteId = String.valueOf(r.getBody().get("id"));
        assertNotNull(UUID.fromString(correnteId));
    }

    @Test
    @Order(3)
    @DisplayName("A conta criada aparece com o ambiente em que e visivel (R7)")
    void mostraOsAmbientesDaConta() {
        // A conta nao tem coluna de ambiente: a visibilidade e N:N via
        // conta_ambiente. Mostrar isso na tela e o que torna B-D2 compreensivel
        // quando o gasto de uma conta conjunta cai num ambiente so.
        Map<String, Object> conta = acharPorId(listar(false), correnteId);
        var ambientesDaConta = (List<Map<String, Object>>) conta.get("ambientes");

        assertEquals(1, ambientesDaConta.size());
        assertEquals(ambienteId, String.valueOf(ambientesDaConta.get(0).get("id")));
        assertNotNull(ambientesDaConta.get(0).get("nome"), "O nome vem do contexto de ambiente");
    }

    @Test
    @Order(4)
    @DisplayName("Saldo inicial vira LANCAMENTO em AJUSTE, nao campo de conta (A13/P1)")
    void saldoInicialViraLancamento() {
        ResponseEntity<Map> r = postAutenticado("/api/contas",
            Map.of("nome", "Poupanca", "natureza", "ATIVO", "saldoInicial", "3000.00"));

        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        assertEquals("3000.00", r.getBody().get("saldo"));

        // A prova de que nao ha campo magico: existe um lancamento de verdade,
        // na categoria sistemica AJUSTE, e o saldo e a soma dele.
        Map<String, Object> lancamento = umLancamentoDaConta(String.valueOf(r.getBody().get("id")));
        assertEquals("AJUSTE", lancamento.get("codigo_categoria"));
        assertEquals("Saldo inicial", lancamento.get("descricao"));
        assertEquals("ENTRADA", lancamento.get("tipo"));
        assertEquals("3000.00", lancamento.get("valor"));
    }

    @Test
    @Order(5)
    @DisplayName("Saldo inicial negativo abre a conta devendo, com valor positivo no banco (F1)")
    void saldoInicialNegativo() {
        ResponseEntity<Map> r = postAutenticado("/api/contas",
            Map.of("nome", "Cartao Nubank", "natureza", "PASSIVO", "saldoInicial", "-450.00"));

        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        assertEquals("-450.00", r.getBody().get("saldo"));
        cartaoId = String.valueOf(r.getBody().get("id"));

        // O sinal e responsabilidade do TIPO; o valor gravado nunca e negativo.
        Map<String, Object> lancamento = umLancamentoDaConta(cartaoId);
        assertEquals("SAIDA", lancamento.get("tipo"));
        assertEquals("450.00", lancamento.get("valor"),
            "Guardar negativo abriria duas representacoes para a mesma saida");
    }

    @Test
    @Order(6)
    @DisplayName("Saldo com casas demais responde 400 antes de chegar ao banco")
    void saldoInicialInvalido() {
        ResponseEntity<Map> r = postAutenticado("/api/contas",
            Map.of("nome", "Conta Errada", "natureza", "ATIVO", "saldoInicial", "10.005"));

        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        var campos = (Map<String, String>) r.getBody().get("campos");
        assertNotNull(campos.get("saldoInicial"),
            "numeric(15,2) arredondaria em silencio — melhor recusar");
    }

    @Test
    @Order(7)
    @DisplayName("Renomear mantem o id e o saldo")
    void renomear() {
        ResponseEntity<Map> r = put("/api/contas/" + correnteId,
            Map.of("nome", "Conta Corrente Itau"));

        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals("Conta Corrente Itau", r.getBody().get("nome"));
        assertEquals(correnteId, String.valueOf(r.getBody().get("id")));
        assertEquals("0.00", r.getBody().get("saldo"));
    }

    @Test
    @Order(8)
    @DisplayName("Encerrar conta COM saldo responde 409 e diz o que fazer antes")
    void encerrarComSaldoConflita() {
        // Dinheiro nao evapora: encerrar com saldo faria o patrimonio cair sem
        // que nenhuma saida tivesse sido registrada.
        ResponseEntity<Map> r = postAutenticado("/api/contas/" + cartaoId + "/encerrar", Map.of());

        assertEquals(HttpStatus.CONFLICT, r.getStatusCode());
        String erro = String.valueOf(r.getBody().get("erro"));
        assertTrue(erro.contains("-450.00"), "A mensagem mostra o saldo que impede: " + erro);
        assertTrue(erro.toLowerCase().contains("transfira") || erro.toLowerCase().contains("ajuste"),
            "O 409 diz o caminho, nao so recusa: " + erro);
    }

    @Test
    @Order(9)
    @DisplayName("Encerrar conta zerada funciona, some do padrao e volta com incluirEncerradas")
    void encerrarEReabrir() {
        ResponseEntity<Map> encerrar =
            postAutenticado("/api/contas/" + correnteId + "/encerrar", Map.of());
        assertEquals(HttpStatus.OK, encerrar.getStatusCode());
        assertNotNull(encerrar.getBody().get("encerradaEm"));

        assertNull(acharPorId(listar(false), correnteId),
            "Encerrada deveria sumir dos seletores (F7)");
        assertNotNull(acharPorId(listar(true), correnteId),
            "E continuar existindo, com o historico inteiro");

        ResponseEntity<Map> reabrir =
            postAutenticado("/api/contas/" + correnteId + "/reabrir", Map.of());
        assertEquals(HttpStatus.OK, reabrir.getStatusCode());
        assertNull(reabrir.getBody().get("encerradaEm"));
    }

    @Test
    @Order(10)
    @DisplayName("Conta de OUTRO ambiente do mesmo usuario responde 404 (B-D21)")
    void outroAmbienteNaoEnxerga() throws Exception {
        String segundo = criarSegundoAmbienteComoOwner();

        ResponseEntity<Map> troca = postAutenticado("/api/sessao/ambiente",
            Map.of("ambienteId", segundo));
        assertEquals(HttpStatus.OK, troca.getStatusCode());
        String tokenNoSegundo = (String) troca.getBody().get("tokenAcesso");

        ResponseEntity<Map> tentativa = http.exchange(
            "/api/contas/" + correnteId, HttpMethod.PUT,
            new HttpEntity<>(Map.of("nome", "Sequestrada"), cabecalhos(tokenNoSegundo)),
            Map.class);
        assertEquals(HttpStatus.NOT_FOUND, tentativa.getStatusCode());

        // E a listagem do segundo ambiente nao mostra as contas do primeiro:
        // sao os VINCULOS que respondem, nao a identidade do usuario.
        ResponseEntity<Map> lista = http.exchange("/api/contas", HttpMethod.GET,
            new HttpEntity<>(cabecalhos(tokenNoSegundo)), Map.class);
        var contas = (List<Map<String, Object>>) lista.getBody().get("contas");
        assertTrue(contas.isEmpty(), "Ambiente novo, sem vinculo de conta nenhum");
    }

    @Test
    @Order(11)
    @DisplayName("Sem token, a API de contas responde 401")
    void semTokenNaoEntra() {
        ResponseEntity<String> r = http.getForEntity("/api/contas", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, r.getStatusCode());
    }

    // =========================================================================
    // Ajudantes
    // =========================================================================

    private void autenticar() {
        email = "conta-" + UUID.randomUUID().toString().substring(0, 8) + "@teste.local";

        ResponseEntity<Map> cadastro = postJson("/api/auth/cadastro",
            Map.of("nome", "Conta Teste", "email", email, "senha", SENHA));
        assertEquals(HttpStatus.CREATED, cadastro.getStatusCode());
        usuarioId = String.valueOf(cadastro.getBody().get("usuarioId"));

        ResponseEntity<Map> login = postJson("/api/auth/login",
            Map.of("email", email, "senha", SENHA));
        token = (String) login.getBody().get("tokenAcesso");
        ambienteId = String.valueOf(login.getBody().get("ambienteId"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listar(boolean incluirEncerradas) {
        ResponseEntity<Map> r = http.exchange(
            "/api/contas?incluirEncerradas=" + incluirEncerradas,
            HttpMethod.GET, new HttpEntity<>(cabecalhos(token)), Map.class);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        return (List<Map<String, Object>>) r.getBody().get("contas");
    }

    private Map<String, Object> acharPorId(List<Map<String, Object>> lista, String id) {
        return lista.stream()
            .filter(c -> id.equals(String.valueOf(c.get("id"))))
            .findFirst()
            .orElse(null);
    }

    /**
     * O unico lancamento de uma conta, lido como OWNER.
     *
     * <p>Direto no banco de proposito: o endpoint de lancamento e a fatia 3 e
     * ainda nao existe. Quando existir, este ajudante deve morrer em favor da
     * chamada HTTP — a leitura por fora nao prova que a API expoe o dado.</p>
     */
    private Map<String, Object> umLancamentoDaConta(String contaId) throws RuntimeException {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             java.sql.Statement s = c.createStatement()) {

            var rs = s.executeQuery(
                "SELECT cat.codigo, l.descricao, l.tipo, l.valor, l.situacao "
                    + "  FROM lancamento l JOIN categoria cat ON cat.id = l.categoria_id "
                    + " WHERE l.conta_id = '" + contaId + "'");

            assertTrue(rs.next(), "Deveria existir exatamente um lancamento de abertura");
            Map<String, Object> m = new HashMap<>();
            m.put("codigo_categoria", rs.getString(1));
            m.put("descricao", rs.getString(2));
            m.put("tipo", rs.getString(3));
            m.put("valor", rs.getBigDecimal(4).toPlainString());
            m.put("situacao", rs.getString(5));
            return m;
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private String criarSegundoAmbienteComoOwner() throws Exception {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             java.sql.Statement s = c.createStatement()) {

            var rs = s.executeQuery("INSERT INTO ambiente (nome) "
                + "VALUES ('Ambiente Secundario Contas') RETURNING id");
            rs.next();
            String novo = rs.getString(1);

            s.executeUpdate("INSERT INTO usuario_ambiente (usuario_id, ambiente_id) "
                + "VALUES ('" + usuarioId + "', '" + novo + "')");
            return novo;
        }
    }

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

    private ResponseEntity<Map> postAutenticado(String caminho, Map<String, String> corpo) {
        return http.postForEntity(caminho, new HttpEntity<>(corpo, cabecalhos(token)), Map.class);
    }

    private ResponseEntity<Map> put(String caminho, Map<String, String> corpo) {
        return http.exchange(caminho, HttpMethod.PUT,
            new HttpEntity<>(corpo, cabecalhos(token)), Map.class);
    }
}
