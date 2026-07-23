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
    // Ajudantes
    // -------------------------------------------------------------------------

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
}
