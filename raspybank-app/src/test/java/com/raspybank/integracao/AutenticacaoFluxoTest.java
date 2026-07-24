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

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * O circuito completo, pela porta da frente: HTTP -> filtro JWT -> contexto ->
 * aspecto RLS -> banco. Item (3) do plano do Bloco C (I-10).
 *
 * <p>Um unico cenario encadeado (dai o {@code @TestMethodOrder}): cadastro,
 * login, endpoint protegido, rotacao de token e deteccao de reuso. Os metodos
 * compartilham estado porque a HISTORIA e o que esta sob teste — cada etapa so
 * faz sentido depois da anterior, exatamente como no uso real.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AutenticacaoFluxoTest extends IntegracaoTest {

    @Autowired
    private TestRestTemplate http;

    private static final String SENHA = "senha-com-mais-de-10";
    private static String email;
    private static String usuarioId;
    private static String ambienteId;
    private static String tokenAcesso;
    private static String tokenRenovacao;
    private static String tokenAntigo;

    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("Cadastro cria usuario E primeiro ambiente, atomicamente (A12)")
    void cadastro() {
        email = "fluxo-" + UUID.randomUUID().toString().substring(0, 8) + "@teste.local";

        ResponseEntity<Map> resposta = postJson("/api/auth/cadastro",
            Map.of("nome", "Fluxo Completo Teste", "email", email, "senha", SENHA));

        assertEquals(HttpStatus.CREATED, resposta.getStatusCode());
        usuarioId  = String.valueOf(resposta.getBody().get("usuarioId"));
        ambienteId = String.valueOf(resposta.getBody().get("ambienteId"));
        assertNotNull(UUID.fromString(usuarioId));
        assertNotNull(UUID.fromString(ambienteId));
    }

    @Test
    @Order(2)
    @DisplayName("Sem token, endpoint protegido responde 401")
    void semTokenRespondeNaoAutorizado() {
        ResponseEntity<String> resposta =
            http.getForEntity("/api/perfil", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resposta.getStatusCode());
    }

    @Test
    @Order(3)
    @DisplayName("Login devolve os dois tokens e o ambiente do cadastro")
    void login() {
        ResponseEntity<Map> resposta = postJson("/api/auth/login",
            Map.of("email", email, "senha", SENHA));

        assertEquals(HttpStatus.OK, resposta.getStatusCode());
        tokenAcesso    = (String) resposta.getBody().get("tokenAcesso");
        tokenRenovacao = (String) resposta.getBody().get("tokenRenovacao");
        assertNotNull(tokenAcesso);
        assertNotNull(tokenRenovacao);
        assertEquals(ambienteId, resposta.getBody().get("ambienteId"),
            "O ambiente do token deveria ser o criado no cadastro");
    }

    @Test
    @Order(4)
    @DisplayName("Falha de login e indistinguivel: e-mail inexistente == senha errada (B-A8)")
    void falhasDeLoginSaoIndistinguiveis() {
        ResponseEntity<String> emailInexistente = postJsonBruto("/api/auth/login",
            Map.of("email", "nao-existe-" + email, "senha", SENHA));
        ResponseEntity<String> senhaErrada = postJsonBruto("/api/auth/login",
            Map.of("email", email, "senha", "senha-errada-longa"));

        assertEquals(HttpStatus.UNAUTHORIZED, emailInexistente.getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, senhaErrada.getStatusCode());
        assertEquals(emailInexistente.getBody(), senhaErrada.getBody(),
            "Corpos diferentes viram um verificador de cadastro");
    }

    @Test
    @Order(5)
    @DisplayName("Com token, o perfil devolve o proprio usuario e APENAS os ambientes dele")
    void perfilComToken() {
        ResponseEntity<Map> resposta = getAutenticado("/api/perfil", tokenAcesso);

        assertEquals(HttpStatus.OK, resposta.getStatusCode());
        assertEquals(usuarioId, String.valueOf(resposta.getBody().get("usuarioId")));

        var ambientes = (java.util.List<Map<String, Object>>) resposta.getBody().get("ambientes");
        assertEquals(1, ambientes.size(),
            "Recem-cadastrado deveria enxergar exatamente um ambiente — o dele");
        assertEquals(ambienteId, String.valueOf(ambientes.get(0).get("id")));
    }

    @Test
    @Order(6)
    @DisplayName("Renovacao rotaciona: devolve token novo e diferente")
    void renovacaoRotaciona() {
        ResponseEntity<Map> resposta = postJson("/api/auth/renovar",
            Map.of("tokenRenovacao", tokenRenovacao));

        assertEquals(HttpStatus.OK, resposta.getStatusCode());
        String novo = (String) resposta.getBody().get("tokenRenovacao");
        assertNotNull(novo);
        assertNotEquals(tokenRenovacao, novo, "Rotacao deveria emitir token diferente");

        // Guarda o par: o ANTIGO sera reapresentado no proximo teste.
        tokenAntigo = tokenRenovacao;
        tokenRenovacao = novo;
    }

    @Test
    @Order(7)
    @DisplayName("Reuso de token ja usado revoga a FAMILIA inteira — inclusive o token novo")
    void reusoRevogaFamiliaInteira() {
        // O token antigo ja foi trocado. Reapresenta-lo e o sinal de roubo (ou
        // retry de rede — indistinguiveis, e o sistema assume o pior).
        ResponseEntity<Map> reuso = postJson("/api/auth/renovar",
            Map.of("tokenRenovacao", tokenAntigo));
        assertEquals(HttpStatus.UNAUTHORIZED, reuso.getStatusCode());

        // A consequencia: o token NOVO, que era valido, morreu junto.
        ResponseEntity<Map> comONovo = postJson("/api/auth/renovar",
            Map.of("tokenRenovacao", tokenRenovacao));
        assertEquals(HttpStatus.UNAUTHORIZED, comONovo.getStatusCode(),
            "A familia inteira deveria ter sido revogada no reuso");
    }

    // -------------------------------------------------------------------------
    // Contrato de erro (I-12) — o que as telas vao consumir
    // -------------------------------------------------------------------------

    @Test
    @Order(8)
    @DisplayName("Cadastrar o mesmo e-mail de novo responde 409 com corpo estavel — nao 500 (I-12)")
    void cadastroDuplicadoRespondeConflito() {
        ResponseEntity<Map> resposta = postJson("/api/auth/cadastro",
            Map.of("nome", "Impostor Do Fluxo", "email", email, "senha", SENHA));

        assertEquals(HttpStatus.CONFLICT, resposta.getStatusCode());
        assertEquals("Ja existe uma conta com este e-mail", resposta.getBody().get("erro"),
            "O corpo do 409 e contrato do frontend");
    }

    @Test
    @Order(9)
    @DisplayName("Validacao responde 400 com o mapa de campos — o formulario marca o lugar certo")
    void validacaoRespondeCampos() {
        ResponseEntity<Map> resposta = postJson("/api/auth/cadastro",
            Map.of("nome", "Sem Senha Valida", "email", "curta@teste.local", "senha", "curta"));

        assertEquals(HttpStatus.BAD_REQUEST, resposta.getStatusCode());
        assertEquals("Dados invalidos", resposta.getBody().get("erro"));
        var campos = (Map<String, String>) resposta.getBody().get("campos");
        assertNotNull(campos.get("senha"), "O campo com problema deveria estar apontado");
    }

    // -------------------------------------------------------------------------
    // Logout por dispositivo (I-14)
    // -------------------------------------------------------------------------

    private static String acessoA, renovacaoA;
    private static String acessoB, renovacaoB;

    @Test
    @Order(10)
    @DisplayName("/logout derruba SO o dispositivo atual: a outra sessao sobrevive (I-14)")
    void logoutDerrubaSomenteODispositivo() {
        Map<String, Object> sessaoA = corpoDoLogin();
        acessoA    = (String) sessaoA.get("tokenAcesso");
        renovacaoA = (String) sessaoA.get("tokenRenovacao");

        Map<String, Object> sessaoB = corpoDoLogin();
        acessoB    = (String) sessaoB.get("tokenAcesso");
        renovacaoB = (String) sessaoB.get("tokenRenovacao");

        ResponseEntity<Map> logout = postAutenticado("/api/auth/logout", acessoA);
        assertEquals(HttpStatus.OK, logout.getStatusCode());

        ResponseEntity<Map> renovarA = postJson("/api/auth/renovar",
            Map.of("tokenRenovacao", renovacaoA));
        assertEquals(HttpStatus.UNAUTHORIZED, renovarA.getStatusCode(),
            "A familia do dispositivo A deveria estar revogada");

        ResponseEntity<Map> renovarB = postJson("/api/auth/renovar",
            Map.of("tokenRenovacao", renovacaoB));
        assertEquals(HttpStatus.OK, renovarB.getStatusCode(),
            "O dispositivo B nao fez logout — a sessao dele deveria seguir viva");

        acessoB    = (String) renovarB.getBody().get("tokenAcesso");
        renovacaoB = (String) renovarB.getBody().get("tokenRenovacao");
    }

    @Test
    @Order(11)
    @DisplayName("/logout-todos derruba o que restou, em todos os dispositivos")
    void logoutDeTodosDerrubaTudo() {
        ResponseEntity<Map> logout = postAutenticado("/api/auth/logout-todos", acessoB);
        assertEquals(HttpStatus.OK, logout.getStatusCode());

        ResponseEntity<Map> renovarB = postJson("/api/auth/renovar",
            Map.of("tokenRenovacao", renovacaoB));
        assertEquals(HttpStatus.UNAUTHORIZED, renovarB.getStatusCode());
    }

    // -------------------------------------------------------------------------
    // Troca e preservacao de ambiente (I-15) — a base do seletor de ambiente
    // -------------------------------------------------------------------------

    private static String acessoC, renovacaoC;
    private static String segundoAmbienteId;

    @Test
    @Order(12)
    @DisplayName("Troca de ambiente emite token novo com o outro ambiente — apos conferir o vinculo")
    void trocaDeAmbiente() throws Exception {
        Map<String, Object> sessaoC = corpoDoLogin();
        acessoC    = (String) sessaoC.get("tokenAcesso");
        renovacaoC = (String) sessaoC.get("tokenRenovacao");

        segundoAmbienteId = criarSegundoAmbienteComoOwner();

        ResponseEntity<Map> troca = postAutenticado("/api/sessao/ambiente", acessoC,
            Map.of("ambienteId", segundoAmbienteId));
        assertEquals(HttpStatus.OK, troca.getStatusCode());
        assertEquals(segundoAmbienteId, troca.getBody().get("ambienteId"));

        // A prova de que o token novo carrega o ambiente novo: o perfil le o
        // ambiente do CONTEXTO, que vem do token.
        ResponseEntity<Map> perfil = getAutenticado("/api/perfil",
            (String) troca.getBody().get("tokenAcesso"));
        assertEquals(segundoAmbienteId,
            String.valueOf(perfil.getBody().get("ambienteAtual")));
    }

    @Test
    @Order(13)
    @DisplayName("Troca para ambiente alheio (ou inexistente) e recusada; sem token, nem entra")
    void trocaParaAmbienteAlheioRecusada() {
        ResponseEntity<Map> alheio = postAutenticado("/api/sessao/ambiente", acessoC,
            Map.of("ambienteId", UUID.randomUUID().toString()));
        assertEquals(HttpStatus.FORBIDDEN, alheio.getStatusCode());

        ResponseEntity<Map> semToken = postJson("/api/sessao/ambiente",
            Map.of("ambienteId", segundoAmbienteId));
        assertEquals(HttpStatus.UNAUTHORIZED, semToken.getStatusCode(),
            "/api/sessao/** deveria nascer protegido por padrao");
    }

    @Test
    @Order(14)
    @DisplayName("Renovacao PRESERVA o ambiente declarado; sem declaracao, volta ao primeiro (I-15)")
    void renovacaoPreservaAmbiente() {
        ResponseEntity<Map> preservando = postJson("/api/auth/renovar",
            Map.of("tokenRenovacao", renovacaoC, "ambienteId", segundoAmbienteId));
        assertEquals(HttpStatus.OK, preservando.getStatusCode());
        assertEquals(segundoAmbienteId, preservando.getBody().get("ambienteId"),
            "Quem operava no segundo ambiente deveria CONTINUAR nele apos renovar");

        // Sem declarar ambiente, o fallback documentado e o primeiro (o do
        // cadastro — auth_ambientes_do_usuario ordena por criado_em).
        String renovacaoNova = (String) preservando.getBody().get("tokenRenovacao");
        ResponseEntity<Map> semDeclarar = postJson("/api/auth/renovar",
            Map.of("tokenRenovacao", renovacaoNova));
        assertEquals(HttpStatus.OK, semDeclarar.getStatusCode());
        assertEquals(ambienteId, semDeclarar.getBody().get("ambienteId"));
    }

    // -------------------------------------------------------------------------
    // A corrida do I-11
    // -------------------------------------------------------------------------

    @Test
    @Order(15)
    @DisplayName("Duas renovacoes SIMULTANEAS com o mesmo token: exatamente uma vence (I-11)")
    void renovacoesSimultaneasSoUmaVence() throws Exception {
        String token = (String) corpoDoLogin().get("tokenRenovacao");

        // O latch solta as duas requisicoes juntas; o banco arbitra quem leva
        // o UPDATE. Antes da correcao, ambas passavam pela checagem de uso e
        // ambas ganhavam token novo.
        var largada = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<HttpStatus> tentativa = () -> {
            largada.await();
            return (HttpStatus) postJson("/api/auth/renovar",
                Map.of("tokenRenovacao", token)).getStatusCode();
        };

        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var primeira = executor.submit(tentativa);
            var segunda  = executor.submit(tentativa);
            largada.countDown();

            var statuses = java.util.List.of(primeira.get(), segunda.get());
            long sucessos = statuses.stream().filter(HttpStatus.OK::equals).count();
            assertEquals(1, sucessos,
                "Exatamente uma requisicao deveria vencer a corrida; veio: " + statuses);
        }
    }

    // -------------------------------------------------------------------------
    // Ajudantes
    // -------------------------------------------------------------------------

    /** Login com o usuario do cenario; devolve o corpo com os dois tokens. */
    private Map<String, Object> corpoDoLogin() {
        ResponseEntity<Map> resposta = postJson("/api/auth/login",
            Map.of("email", email, "senha", SENHA));
        assertEquals(HttpStatus.OK, resposta.getStatusCode());
        return resposta.getBody();
    }

    /**
     * Segundo ambiente criado como OWNER, direto no banco. Nao ha atalho: a
     * porta auth_criar_ambiente_inicial recusa quem ja tem ambiente (de
     * proposito), e o endpoint de criar ambiente ainda nao existe. Quando ele
     * nascer, este ajudante deve morrer em favor da chamada HTTP.
     */
    private String criarSegundoAmbienteComoOwner() throws Exception {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             java.sql.Statement s = c.createStatement()) {

            var rs = s.executeQuery("INSERT INTO ambiente (nome) "
                + "VALUES ('Segundo Ambiente do Fluxo') RETURNING id");
            rs.next();
            String novoAmbiente = rs.getString(1);

            s.executeUpdate("INSERT INTO usuario_ambiente (usuario_id, ambiente_id) "
                + "VALUES ('" + usuarioId + "', '" + novoAmbiente + "')");
            return novoAmbiente;
        }
    }

    private ResponseEntity<Map> postJson(String caminho, Map<String, String> corpo) {
        HttpHeaders cabecalhos = new HttpHeaders();
        cabecalhos.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(caminho, new HttpEntity<>(corpo, cabecalhos), Map.class);
    }

    private ResponseEntity<String> postJsonBruto(String caminho, Map<String, String> corpo) {
        HttpHeaders cabecalhos = new HttpHeaders();
        cabecalhos.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(caminho, new HttpEntity<>(corpo, cabecalhos), String.class);
    }

    private ResponseEntity<Map> getAutenticado(String caminho, String token) {
        HttpHeaders cabecalhos = new HttpHeaders();
        cabecalhos.setBearerAuth(token);
        return http.exchange(caminho, HttpMethod.GET,
            new HttpEntity<>(cabecalhos), Map.class);
    }

    private ResponseEntity<Map> postAutenticado(String caminho, String token) {
        return postAutenticado(caminho, token, Map.of());
    }

    private ResponseEntity<Map> postAutenticado(String caminho, String token,
                                                Map<String, String> corpo) {
        HttpHeaders cabecalhos = new HttpHeaders();
        cabecalhos.setContentType(MediaType.APPLICATION_JSON);
        cabecalhos.setBearerAuth(token);
        return http.postForEntity(caminho, new HttpEntity<>(corpo, cabecalhos), Map.class);
    }
}
