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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Perfil e ambientes (V14) pela porta da frente.
 *
 * <p>Nasceu de um beco que o uso revelou em 28/07/2026: o seletor de ambiente
 * da casca ficava desabilitado com um ambiente so, e nao havia como criar o
 * segundo. A lista existia; a porta nao.</p>
 *
 * <h3>O que este teste guarda, alem dos codigos HTTP</h3>
 *
 * <p>Que trocar a senha <b>derruba as outras sessoes</b> e que a senha atual e
 * mesmo exigida. Sem as duas coisas, quem sentasse numa maquina com a sessao
 * aberta tomaria a conta — e o dono ficaria de fora, porque a troca expulsa
 * todo mundo.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PerfilApiTest extends IntegracaoTest {

    @Autowired
    private TestRestTemplate http;

    private static final String SENHA = "senha-com-mais-de-10";
    private static final String SENHA_NOVA = "outra-senha-bem-longa";

    private static String email;
    private static String token;
    private static String tokenDeOutroDispositivo;

    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("O perfil traz os dados cadastrais e os ambientes")
    void perfilTrazOsDados() {
        autenticar();

        ResponseEntity<Map> r = get("/api/perfil");
        assertEquals(HttpStatus.OK, r.getStatusCode());

        assertEquals("Perfil Teste", r.getBody().get("nome"));
        assertEquals(email, r.getBody().get("email"));
        assertNotNull(r.getBody().get("criadoEm"));
        assertNotNull(r.getBody().get("ambienteAtual"));

        // A12: o primeiro ambiente nasce junto com o cadastro.
        assertEquals(1, ((List<?>) r.getBody().get("ambientes")).size());
    }

    @Test
    @Order(2)
    @DisplayName("Alterar o nome funciona, e o e-mail continua sendo o mesmo")
    void alterarNome() {
        ResponseEntity<Map> r = put("/api/perfil/nome", Map.of("nome", "Abner Miranda"));

        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals("Abner Miranda", r.getBody().get("nome"));
        assertEquals(email, r.getBody().get("email"),
            "O e-mail e o login e nao se altera por aqui");
    }

    @Test
    @Order(3)
    @DisplayName("Nome vazio responde 400 com o campo apontado")
    void nomeVazioRecusado() {
        ResponseEntity<Map> r = put("/api/perfil/nome", Map.of("nome", "   "));

        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertNotNull(((Map<String, String>) r.getBody().get("campos")).get("nome"));
    }

    // =========================================================================
    // Ambientes
    // =========================================================================

    @Test
    @Order(4)
    @DisplayName("Criar ambiente: nasce vazio, com as sistemicas e SO com elas (F13)")
    void criarAmbiente() {
        ResponseEntity<Map> r = post("/api/ambientes", Map.of("nome", "Freelance"));

        assertEquals(HttpStatus.CREATED, r.getStatusCode());

        List<Map<String, Object>> lista =
            (List<Map<String, Object>>) r.getBody().get("ambientes");
        assertEquals(2, lista.size());
        assertTrue(lista.stream().anyMatch(a -> "Freelance".equals(a.get("nome"))));

        // Criar NAO troca a sessao: quem esta no meio de um lancamento nao
        // deveria ser jogado para o ambiente novo sem pedir.
        String ativo = String.valueOf(r.getBody().get("ambienteAtual"));
        String novo = String.valueOf(lista.stream()
            .filter(a -> "Freelance".equals(a.get("nome")))
            .findFirst().orElseThrow().get("id"));
        assertFalse(ativo.equals(novo), "Criar ambiente nao muda o ambiente ativo");

        // E ele nasce vazio: nenhuma conta, e so as quatro sistemicas.
        ResponseEntity<Map> troca = post("/api/sessao/ambiente", Map.of("ambienteId", novo));
        assertEquals(HttpStatus.OK, troca.getStatusCode());
        String tokenNoNovo = (String) troca.getBody().get("tokenAcesso");

        ResponseEntity<Map> contas = http.exchange("/api/contas", HttpMethod.GET,
            new HttpEntity<>(cabecalhos(tokenNoNovo)), Map.class);
        assertTrue(((List<?>) contas.getBody().get("contas")).isEmpty(),
            "Ambiente novo nao herda conta nenhuma");

        ResponseEntity<Map> cats = http.exchange("/api/categorias", HttpMethod.GET,
            new HttpEntity<>(cabecalhos(tokenNoNovo)), Map.class);
        assertEquals(4, ((List<?>) cats.getBody().get("categorias")).size(),
            "So as quatro sistemicas — sem kit inicial de categorias (B-D14)");
    }

    @Test
    @Order(5)
    @DisplayName("Ambiente sem nome responde 400")
    void ambienteSemNomeRecusado() {
        assertEquals(HttpStatus.BAD_REQUEST,
            post("/api/ambientes", Map.of("nome", "  ")).getStatusCode());
    }

    // =========================================================================
    // Senha
    // =========================================================================

    @Test
    @Order(6)
    @DisplayName("Trocar senha com a atual ERRADA responde 403 e nao troca nada")
    void senhaAtualErradaRecusada() {
        ResponseEntity<Map> r = put("/api/perfil/senha", Map.of(
            "senhaAtual", "essa-nao-e-a-senha",
            "senhaNova", SENHA_NOVA));

        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());

        // E a senha continua a de antes.
        assertEquals(HttpStatus.OK, postSemToken("/api/auth/login",
            Map.of("email", email, "senha", SENHA)).getStatusCode());
    }

    @Test
    @Order(7)
    @DisplayName("Senha nova curta demais responde 400")
    void senhaNovaCurtaRecusada() {
        ResponseEntity<Map> r = put("/api/perfil/senha", Map.of(
            "senhaAtual", SENHA, "senhaNova", "curta"));

        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertNotNull(((Map<String, String>) r.getBody().get("campos")).get("senhaNova"));
    }

    @Test
    @Order(8)
    @DisplayName("Trocar a senha DERRUBA as outras sessoes — e essa e a razao de existir")
    void trocarSenhaDerrubaAsOutrasSessoes() {
        // Um segundo dispositivo, logado antes da troca.
        ResponseEntity<Map> outroLogin = postSemToken("/api/auth/login",
            Map.of("email", email, "senha", SENHA));
        assertEquals(HttpStatus.OK, outroLogin.getStatusCode());
        tokenDeOutroDispositivo = (String) outroLogin.getBody().get("tokenRenovacao");

        ResponseEntity<Void> r = http.exchange("/api/perfil/senha", HttpMethod.PUT,
            new HttpEntity<>(Map.of("senhaAtual", SENHA, "senhaNova", SENHA_NOVA),
                             cabecalhos(token)),
            Void.class);
        assertEquals(HttpStatus.NO_CONTENT, r.getStatusCode());

        // A sessao do outro dispositivo nao renova mais. Se a troca aconteceu
        // porque a senha vazou, deixar a antiga viva manteria o invasor dentro.
        ResponseEntity<Map> renovar = postSemToken("/api/auth/renovar",
            Map.of("tokenRenovacao", tokenDeOutroDispositivo));
        assertEquals(HttpStatus.UNAUTHORIZED, renovar.getStatusCode());
    }

    @Test
    @Order(9)
    @DisplayName("A senha antiga deixa de valer e a nova entra")
    void aSenhaNovaVale() {
        assertEquals(HttpStatus.UNAUTHORIZED, postSemToken("/api/auth/login",
            Map.of("email", email, "senha", SENHA)).getStatusCode());

        assertEquals(HttpStatus.OK, postSemToken("/api/auth/login",
            Map.of("email", email, "senha", SENHA_NOVA)).getStatusCode());
    }

    @Test
    @Order(10)
    @DisplayName("Sem token, perfil e ambientes respondem 401")
    void semTokenNaoEntra() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            http.getForEntity("/api/perfil", String.class).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
            http.getForEntity("/api/ambientes", String.class).getStatusCode());
    }

    // =========================================================================

    private void autenticar() {
        email = "perfil-" + UUID.randomUUID().toString().substring(0, 8) + "@teste.local";

        assertEquals(HttpStatus.CREATED, postSemToken("/api/auth/cadastro",
            Map.of("nome", "Perfil Teste", "email", email, "senha", SENHA)).getStatusCode());

        token = (String) postSemToken("/api/auth/login",
            Map.of("email", email, "senha", SENHA)).getBody().get("tokenAcesso");
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

    private ResponseEntity<Map> postSemToken(String caminho, Map<String, ?> corpo) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(caminho, new HttpEntity<>(corpo, h), Map.class);
    }

    private ResponseEntity<Map> post(String caminho, Map<String, ?> corpo) {
        return http.postForEntity(caminho, new HttpEntity<>(corpo, cabecalhos(token)), Map.class);
    }

    private ResponseEntity<Map> put(String caminho, Map<String, ?> corpo) {
        return http.exchange(caminho, HttpMethod.PUT,
            new HttpEntity<>(corpo, cabecalhos(token)), Map.class);
    }
}
