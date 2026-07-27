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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A API de categorias e subcategorias pela porta da frente — fatia 1,
 * contrato em {@code docs/api.md} §3.
 *
 * <p>Cenario unico encadeado, como o {@code AutenticacaoFluxoTest}: cada etapa
 * so faz sentido depois da anterior, e e a HISTORIA que esta sob teste.</p>
 *
 * <p>O que este teste NAO refaz: as regras puras (cadeado da sistemica, tipo
 * aceito) ja estao cobertas sem Spring em {@code CategoriaTest}, no modulo de
 * lancamento. Aqui interessa que elas <b>cheguem ao cliente</b> com o codigo
 * HTTP certo e o corpo certo — o 403 e o 409 sao contrato do frontend tanto
 * quanto o 200.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CategoriaApiTest extends IntegracaoTest {

    @Autowired
    private TestRestTemplate http;

    private static final String SENHA = "senha-com-mais-de-10";

    private static String email;
    private static String usuarioId;
    private static String ambienteId;
    private static String token;

    private static String mercadoId;
    private static String subFeiraId;
    private static String transferenciaId;

    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Ambiente novo ja nasce com as tres sistemicas, e so com elas (F13 / B-D14)")
    void ambienteNasceComAsTresSistemicas() {
        autenticar();

        List<Map<String, Object>> lista = listar(false);

        assertEquals(3, lista.size(),
            "F13 ao pe da letra: sem kit inicial de categorias editaveis (B-D14)");

        var codigos = lista.stream().map(c -> String.valueOf(c.get("codigo"))).sorted().toList();
        assertEquals(List.of("AJUSTE", "NAO_CLASSIFICADO", "TRANSFERENCIA"), codigos);

        // O par de flags que B-D15 separou, visivel no contrato: a tela usa
        // 'sistemica' para o cadeado e 'entraNoMapa' para explicar a ausencia.
        for (Map<String, Object> c : lista) {
            assertTrue((boolean) c.get("sistemica"));
            boolean esperadoNoMapa = "NAO_CLASSIFICADO".equals(c.get("codigo"));
            assertEquals(esperadoNoMapa, c.get("entraNoMapa"),
                "NAO_CLASSIFICADO e gasto de verdade; transferencia e ajuste nao");
            if ("TRANSFERENCIA".equals(c.get("codigo"))) {
                transferenciaId = String.valueOf(c.get("id"));
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("Criar categoria devolve 201, sem codigo e dentro do mapa")
    void criarCategoria() {
        ResponseEntity<Map> r = postAutenticado("/api/categorias",
            Map.of("nome", "Mercado", "tipo", "SAIDA"));

        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        assertNull(r.getBody().get("codigo"), "Categoria da aplicacao nunca tem codigo");
        assertFalse((boolean) r.getBody().get("sistemica"));
        assertTrue((boolean) r.getBody().get("entraNoMapa"));
        assertNull(r.getBody().get("arquivadaEm"));

        mercadoId = String.valueOf(r.getBody().get("id"));
        assertNotNull(UUID.fromString(mercadoId));
    }

    @Test
    @Order(3)
    @DisplayName("Nome repetido responde 409, ignorando maiusculas")
    void nomeRepetidoConflita() {
        // O indice usa lower(nome): duas linhas identicas no seletor da T-08
        // fariam a pessoa escolher no escuro.
        ResponseEntity<Map> r = postAutenticado("/api/categorias",
            Map.of("nome", "MERCADO", "tipo", "SAIDA"));

        assertEquals(HttpStatus.CONFLICT, r.getStatusCode());
        assertEquals("Ja existe uma categoria ativa com este nome", r.getBody().get("erro"),
            "O corpo do 409 e contrato do frontend");
    }

    @Test
    @Order(4)
    @DisplayName("Renomear troca o texto e MANTEM o id (B-D3)")
    void renomearMantemOId() {
        // E o que sustenta o agrupamento do mapa por id: se o rename criasse
        // categoria nova, o total dela se partiria em duas linhas.
        ResponseEntity<Map> r = put("/api/categorias/" + mercadoId,
            Map.of("nome", "Mercado e feira", "tipo", "SAIDA"));

        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals("Mercado e feira", r.getBody().get("nome"));
        assertEquals(mercadoId, String.valueOf(r.getBody().get("id")));
    }

    @Test
    @Order(5)
    @DisplayName("Sistemica recusa rename e arquivamento com 403 e frase legivel (F10)")
    void sistemicaRespondeProibido() {
        ResponseEntity<Map> rename = put("/api/categorias/" + transferenciaId,
            Map.of("nome", "Movimentacao", "tipo", "AMBOS"));
        assertEquals(HttpStatus.FORBIDDEN, rename.getStatusCode());
        assertTrue(String.valueOf(rename.getBody().get("erro")).contains("sistemica"),
            "A mensagem do 403 e acionavel e vai inteira para o cliente");

        ResponseEntity<Map> arquivar =
            postAutenticado("/api/categorias/" + transferenciaId + "/arquivar", Map.of());
        assertEquals(HttpStatus.FORBIDDEN, arquivar.getStatusCode());

        ResponseEntity<Map> sub =
            postAutenticado("/api/categorias/" + transferenciaId + "/subcategorias",
                Map.of("nome", "Pix"));
        assertEquals(HttpStatus.FORBIDDEN, sub.getStatusCode());
    }

    @Test
    @Order(6)
    @DisplayName("Subcategoria nasce dentro da categoria e volta aninhada numa consulta so")
    void subcategoriaAninhada() {
        ResponseEntity<Map> criada =
            postAutenticado("/api/categorias/" + mercadoId + "/subcategorias",
                Map.of("nome", "Feira"));
        assertEquals(HttpStatus.CREATED, criada.getStatusCode());
        subFeiraId = String.valueOf(criada.getBody().get("id"));

        Map<String, Object> mercado = acharPorId(listar(false), mercadoId);
        var subs = (List<Map<String, Object>>) mercado.get("subcategorias");

        assertEquals(1, subs.size());
        assertEquals("Feira", subs.get(0).get("nome"));
    }

    @Test
    @Order(7)
    @DisplayName("Nao existe caminho para um terceiro nivel (F8)")
    void naoExisteSubSubcategoria() {
        // A ausencia do caminho e a garantia: o que nao tem rota nao vira dado
        // por engano.
        ResponseEntity<Map> r =
            postAutenticado("/api/subcategorias/" + subFeiraId + "/subcategorias",
                Map.of("nome", "Hortifruti"));

        assertEquals(HttpStatus.NOT_FOUND, r.getStatusCode());
    }

    @Test
    @Order(8)
    @DisplayName("Arquivar tira do padrao e mantem visivel com incluirArquivadas=true (B-D4)")
    void arquivarEReversivel() {
        ResponseEntity<Map> arquivar =
            postAutenticado("/api/categorias/" + mercadoId + "/arquivar", Map.of());
        assertEquals(HttpStatus.OK, arquivar.getStatusCode());
        assertNotNull(arquivar.getBody().get("arquivadaEm"));

        assertNull(acharPorId(listar(false), mercadoId),
            "Arquivada deveria sumir do seletor da T-08");
        assertNotNull(acharPorId(listar(true), mercadoId),
            "E continuar existindo para nomear o historico");

        ResponseEntity<Map> voltar =
            postAutenticado("/api/categorias/" + mercadoId + "/desarquivar", Map.of());
        assertEquals(HttpStatus.OK, voltar.getStatusCode());
        assertNull(voltar.getBody().get("arquivadaEm"));
        assertNotNull(acharPorId(listar(false), mercadoId));
    }

    @Test
    @Order(9)
    @DisplayName("Arquivada libera o nome: da para recomecar a contagem com o mesmo rotulo")
    void nomeLiberadoAposArquivar() {
        // O indice e PARCIAL, so entre as ativas. Impedir isso obrigaria a
        // inventar "Mercado 2".
        postAutenticado("/api/categorias/" + mercadoId + "/arquivar", Map.of());

        ResponseEntity<Map> novaComMesmoNome = postAutenticado("/api/categorias",
            Map.of("nome", "Mercado e feira", "tipo", "SAIDA"));
        assertEquals(HttpStatus.CREATED, novaComMesmoNome.getStatusCode());

        postAutenticado("/api/categorias/" + mercadoId + "/desarquivar", Map.of());
    }

    @Test
    @Order(10)
    @DisplayName("Categoria de OUTRO ambiente do mesmo usuario responde 404 (B-D21)")
    void outroAmbienteDoMesmoUsuarioNaoEnxerga() throws Exception {
        // O caso que o RLS nao cobre: o banco libera as linhas de todos os
        // ambientes da pessoa, porque o tenant e o usuario (R7). Quem recorta
        // e o ambiente da sessao — e sem esse recorte a categoria da casa
        // apareceria, editavel, dentro do ambiente do freelance.
        String segundo = criarSegundoAmbienteComoOwner();

        ResponseEntity<Map> troca = postAutenticado("/api/sessao/ambiente",
            Map.of("ambienteId", segundo));
        assertEquals(HttpStatus.OK, troca.getStatusCode());
        String tokenNoSegundo = (String) troca.getBody().get("tokenAcesso");

        ResponseEntity<Map> tentativa = http.exchange(
            "/api/categorias/" + mercadoId, HttpMethod.PUT,
            new HttpEntity<>(Map.of("nome", "Sequestrada", "tipo", "SAIDA"),
                             cabecalhos(tokenNoSegundo)),
            Map.class);

        assertEquals(HttpStatus.NOT_FOUND, tentativa.getStatusCode(),
            "404, nunca 403: distinguir os dois viraria um oraculo de ids");
    }

    @Test
    @Order(11)
    @DisplayName("Sem token, a API de categorias responde 401")
    void semTokenNaoEntra() {
        ResponseEntity<String> r = http.getForEntity("/api/categorias", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, r.getStatusCode(),
            "/api/categorias deveria nascer protegido por padrao");
    }

    @Test
    @Order(12)
    @DisplayName("Nome vazio responde 400 com o campo apontado (I-12)")
    void validacaoApontaOCampo() {
        ResponseEntity<Map> r = postAutenticado("/api/categorias",
            Map.of("nome", "   ", "tipo", "SAIDA"));

        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertEquals("Dados invalidos", r.getBody().get("erro"));
        var campos = (Map<String, String>) r.getBody().get("campos");
        assertNotNull(campos.get("nome"));
    }

    // =========================================================================
    // Ajudantes
    // =========================================================================

    private void autenticar() {
        email = "categoria-" + UUID.randomUUID().toString().substring(0, 8) + "@teste.local";

        ResponseEntity<Map> cadastro = postJson("/api/auth/cadastro",
            Map.of("nome", "Categoria Teste", "email", email, "senha", SENHA));
        assertEquals(HttpStatus.CREATED, cadastro.getStatusCode());
        usuarioId = String.valueOf(cadastro.getBody().get("usuarioId"));

        ResponseEntity<Map> login = postJson("/api/auth/login",
            Map.of("email", email, "senha", SENHA));
        assertEquals(HttpStatus.OK, login.getStatusCode());
        token = (String) login.getBody().get("tokenAcesso");
        ambienteId = String.valueOf(login.getBody().get("ambienteId"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listar(boolean incluirArquivadas) {
        ResponseEntity<Map> r = http.exchange(
            "/api/categorias?incluirArquivadas=" + incluirArquivadas,
            HttpMethod.GET, new HttpEntity<>(cabecalhos(token)), Map.class);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        return (List<Map<String, Object>>) r.getBody().get("categorias");
    }

    private Map<String, Object> acharPorId(List<Map<String, Object>> lista, String id) {
        return lista.stream()
            .filter(c -> id.equals(String.valueOf(c.get("id"))))
            .findFirst()
            .orElse(null);
    }

    /** Segundo ambiente direto no banco: nao existe endpoint de criar ambiente ainda. */
    private String criarSegundoAmbienteComoOwner() throws Exception {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             java.sql.Statement s = c.createStatement()) {

            var rs = s.executeQuery("INSERT INTO ambiente (nome) "
                + "VALUES ('Ambiente Secundario Categorias') RETURNING id");
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
