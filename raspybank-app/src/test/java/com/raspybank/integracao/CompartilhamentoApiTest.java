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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compartilhamento de ambiente (V15) pela porta da frente — §4j, B-D74 a B-D84.
 *
 * <p>A frase que orienta tudo: <i>"e como se eu desse a minha senha para a
 * pessoa, mas ao inves de dar minha senha dei meu acesso"</i>.</p>
 *
 * <h3>O que este teste guarda, alem dos codigos HTTP</h3>
 *
 * <ul>
 *   <li>que conceder e UMA LINHA (B-D74): sem migracao de dados, a convidada
 *       ve as contas e categorias do dono na hora;</li>
 *   <li>que a acao da convidada fica carimbada com o nome DELA (B-D82);</li>
 *   <li>que a conta compartilhada nao escapa para o ambiente pessoal da
 *       convidada (B-D78 — e o que fecha o I-23);</li>
 *   <li>que a revogacao vale AGORA, mesmo com o token vivo (B-D83), e que a
 *       renovacao devolve a pessoa a um ambiente que e dela (B-D84: as
 *       sessoes nao caem).</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CompartilhamentoApiTest extends IntegracaoTest {

    @Autowired
    private TestRestTemplate http;

    private static final String SENHA = "senha-com-mais-de-10";

    private static String emailDono;
    private static String emailConvidada;
    private static String emailTerceiro;

    private static UUID donoId;
    private static UUID convidadaId;

    private static String tokenDono;
    private static String tokenConvidada;
    private static String tokenTerceiro;
    private static String renovacaoConvidada;

    private static String ambienteDoDono;
    private static String ambienteDaConvidada;

    /** Token da convidada JA DENTRO do ambiente compartilhado — o de B-D83. */
    private static String tokenConvidadaNoCompartilhado;

    // =========================================================================
    // A concessao
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Dono concede acesso por e-mail: imediato, sem aceite (B-D80)")
    void donoConcedeAcesso() {
        cadastrarTodoMundo();

        ResponseEntity<Map> r = post(tokenDono,
            "/api/ambientes/" + ambienteDoDono + "/acessos",
            Map.of("email", emailConvidada));

        assertEquals(HttpStatus.CREATED, r.getStatusCode(), () -> String.valueOf(r.getBody()));

        List<Map<String, Object>> acessos = (List<Map<String, Object>>) r.getBody().get("acessos");
        assertEquals(2, acessos.size());

        // O dono vem marcado, e vem primeiro.
        assertEquals(true, acessos.get(0).get("dono"));
        assertEquals(emailDono, acessos.get(0).get("email"));
        assertEquals(false, acessos.get(1).get("dono"));
        assertEquals(emailConvidada, acessos.get(1).get("email"));
    }

    @Test
    @Order(2)
    @DisplayName("O ambiente aparece na lista da convidada, marcado como nao-dono")
    void ambienteApareceNaListaDaConvidada() {
        ResponseEntity<Map> r = get(tokenConvidada, "/api/ambientes");
        List<Map<String, Object>> lista = (List<Map<String, Object>>) r.getBody().get("ambientes");

        assertEquals(2, lista.size(), "O dela + o compartilhado");

        Map<String, Object> compartilhado = lista.stream()
            .filter(a -> ambienteDoDono.equals(String.valueOf(a.get("id"))))
            .findFirst().orElseThrow();
        assertEquals(false, compartilhado.get("dono"));

        Map<String, Object> proprio = lista.stream()
            .filter(a -> ambienteDaConvidada.equals(String.valueOf(a.get("id"))))
            .findFirst().orElseThrow();
        assertEquals(true, proprio.get("dono"));
    }

    @Test
    @Order(3)
    @DisplayName("Convidada entra pelo seletor de sempre e opera o DINHEIRO (B-D74/B-D76)")
    void convidadaOperaODinheiro() {
        // Troca de sessao — o mesmo POST /api/sessao/ambiente de sempre.
        ResponseEntity<Map> troca = post(tokenConvidada,
            "/api/sessao/ambiente", Map.of("ambienteId", ambienteDoDono));
        assertEquals(HttpStatus.OK, troca.getStatusCode());
        tokenConvidadaNoCompartilhado = (String) troca.getBody().get("tokenAcesso");

        // Ela ve as categorias do dono — as sistemicas que nasceram com o
        // ambiente. Nenhuma migracao de dados aconteceu: e a linha do vinculo
        // fazendo as politicas responderem certo.
        ResponseEntity<Map> cats = get(tokenConvidadaNoCompartilhado, "/api/categorias");
        assertEquals(HttpStatus.OK, cats.getStatusCode());
        assertFalse(((List<?>) cats.getBody().get("categorias")).isEmpty());

        // E cria uma conta DENTRO do ambiente dele: criar conta e dinheiro.
        ResponseEntity<Map> conta = post(tokenConvidadaNoCompartilhado, "/api/contas",
            Map.of("nome", "Conta da Casa", "natureza", "ATIVO"));
        assertEquals(HttpStatus.CREATED, conta.getStatusCode());

        // O dono ve a conta que ela criou.
        ResponseEntity<Map> contasDoDono = get(tokenDono, "/api/contas");
        assertTrue(((List<Map<String, Object>>) contasDoDono.getBody().get("contas")).stream()
            .anyMatch(m -> "Conta da Casa".equals(m.get("nome"))));
    }

    @Test
    @Order(4)
    @DisplayName("A acao da convidada nasce carimbada com o nome DELA (B-D82) — zero codigo")
    void auditoriaCarimbaAConvidada() throws SQLException {
        try (Connection c = comoProprietario();
             PreparedStatement ps = c.prepareStatement("""
                 SELECT usuario_id FROM registro_auditoria
                  WHERE entidade = 'Conta' AND operacao = 'CRIACAO'
                  ORDER BY ocorrido_em DESC LIMIT 1""");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(convidadaId, rs.getObject(1, UUID.class),
                "A auditoria deveria apontar a convidada, nao o dono do ambiente");
        }
    }

    // =========================================================================
    // A porta e so do dono
    // =========================================================================

    @Test
    @Order(5)
    @DisplayName("Convidada nao convida (403): porta nao e dinheiro (B-D76)")
    void convidadaNaoConvida() {
        ResponseEntity<Map> r = post(tokenConvidadaNoCompartilhado,
            "/api/ambientes/" + ambienteDoDono + "/acessos",
            Map.of("email", emailTerceiro));
        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
    }

    @Test
    @Order(6)
    @DisplayName("Convidada nao remove o dono (403)")
    void convidadaNaoRemoveODono() {
        ResponseEntity<Map> r = delete(tokenConvidadaNoCompartilhado,
            "/api/ambientes/" + ambienteDoDono + "/acessos/" + donoId);
        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
    }

    @Test
    @Order(7)
    @DisplayName("Dono nao sai do proprio ambiente (403) — senao sobra ambiente orfao (B-D77)")
    void donoNaoSai() {
        ResponseEntity<Map> r = delete(tokenDono,
            "/api/ambientes/" + ambienteDoDono + "/acessos/" + donoId);
        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
    }

    // =========================================================================
    // Os erros do contrato (§2c)
    // =========================================================================

    @Test
    @Order(8)
    @DisplayName("E-mail nao cadastrado responde 404 dizendo isso (B-D81, oraculo aceito)")
    void emailNaoCadastrado() {
        ResponseEntity<Map> r = post(tokenDono,
            "/api/ambientes/" + ambienteDoDono + "/acessos",
            Map.of("email", "ninguem-" + UUID.randomUUID() + "@teste.local"));
        assertEquals(HttpStatus.NOT_FOUND, r.getStatusCode());
    }

    @Test
    @Order(9)
    @DisplayName("E-mail malformado responde 400 com o campo apontado")
    void emailMalformado() {
        ResponseEntity<Map> r = post(tokenDono,
            "/api/ambientes/" + ambienteDoDono + "/acessos",
            Map.of("email", "isto-nao-e-um-email"));
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
    }

    @Test
    @Order(10)
    @DisplayName("Conceder a quem ja tem responde 409")
    void concederDeNovo() {
        ResponseEntity<Map> r = post(tokenDono,
            "/api/ambientes/" + ambienteDoDono + "/acessos",
            Map.of("email", emailConvidada));
        assertEquals(HttpStatus.CONFLICT, r.getStatusCode());
    }

    @Test
    @Order(11)
    @DisplayName("Terceiro nao ve os acessos: ambiente alheio e inexistente (B-D25)")
    void terceiroNaoVe() {
        ResponseEntity<Map> r = get(tokenTerceiro,
            "/api/ambientes/" + ambienteDoDono + "/acessos");
        assertEquals(HttpStatus.NOT_FOUND, r.getStatusCode());
    }

    // =========================================================================
    // B-D78 — a conta nao escapa (e o que fecha o I-23)
    // =========================================================================

    @Test
    @Order(12)
    @DisplayName("Convidada NAO leva a conta compartilhada para o ambiente pessoal dela (B-D78)")
    void contaNaoEscapaParaOAmbientePessoal() throws SQLException {
        // Direto no banco, como a aplicacao conecta, com a identidade dela:
        // e o pior caso — nem endpoint precisa existir para o furo existir.
        UUID contaDoAmbienteCompartilhado;
        try (Connection c = comoProprietario();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT conta_id FROM conta_ambiente WHERE ambiente_id = ? LIMIT 1")) {
            ps.setObject(1, UUID.fromString(ambienteDoDono));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                contaDoAmbienteCompartilhado = rs.getObject(1, UUID.class);
            }
        }

        try (Connection c = comoApp()) {
            assumirIdentidade(c, convidadaId);
            SQLException erro = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO conta_ambiente (conta_id, ambiente_id) VALUES (?, ?)")) {
                    ps.setObject(1, contaDoAmbienteCompartilhado);
                    ps.setObject(2, UUID.fromString(ambienteDaConvidada));
                    ps.executeUpdate();
                }
            }, "A conta compartilhada escapou para o ambiente pessoal da convidada");
            // 42501 = violacao de politica de RLS.
            assertEquals("42501", erro.getSQLState());
        }
    }

    @Test
    @Order(13)
    @DisplayName("Nem o vinculo do dono se apaga por baixo: DELETE direto atinge 0 linhas (B-D77)")
    void vinculoDoDonoIrremovivelNoBanco() throws SQLException {
        try (Connection c = comoApp()) {
            assumirIdentidade(c, convidadaId);
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM usuario_ambiente WHERE usuario_id = ? AND ambiente_id = ?")) {
                ps.setObject(1, donoId);
                ps.setObject(2, UUID.fromString(ambienteDoDono));
                assertEquals(0, ps.executeUpdate(),
                    "A politica deveria tornar a linha do dono invisivel ao DELETE");
            }
        }
    }

    // =========================================================================
    // B-D83/B-D84 — a revogacao com a pessoa DENTRO
    // =========================================================================

    @Test
    @Order(14)
    @DisplayName("Revogacao vale AGORA: o token vivo leva 403 com frase, nao tela vazia (B-D83)")
    void revogacaoValeAgora() {
        // O dono revoga com a convidada trabalhando la dentro.
        ResponseEntity<Map> revoga = delete(tokenDono,
            "/api/ambientes/" + ambienteDoDono + "/acessos/" + convidadaId);
        assertEquals(HttpStatus.NO_CONTENT, revoga.getStatusCode());

        // O JWT dela vale mais quinze minutos e carrega aquele ambiente. A RLS
        // ja nao devolve nada — mas a resposta nao pode ser o silencio.
        ResponseEntity<Map> r = get(tokenConvidadaNoCompartilhado, "/api/contas");
        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
        assertEquals("SEM_ACESSO_AO_AMBIENTE", r.getBody().get("motivo"),
            "O marcador que a tela le para voltar a um ambiente proprio");
        assertTrue(String.valueOf(r.getBody().get("erro")).length() > 0,
            "403 com FRASE, nao silencio");
    }

    @Test
    @Order(15)
    @DisplayName("As sessoes dela NAO caem (B-D84): a renovacao segue viva e volta para o ambiente dela")
    void revogarNaoDerrubaASessao() {
        // A rota de fuga e a renovacao: o token de renovacao dela continua
        // valido — revogar acesso nao e revogar sessao — e o servidor troca em
        // silencio para um ambiente que e dela (ambienteParaRenovacao).
        ResponseEntity<Map> renova = postSemToken("/api/auth/renovar", Map.of(
            "tokenRenovacao", renovacaoConvidada,
            "ambienteId", ambienteDoDono));
        assertEquals(HttpStatus.OK, renova.getStatusCode(),
            "Revogar o acesso nao pode derrubar a sessao (B-D84)");

        String ambienteNovo = String.valueOf(renova.getBody().get("ambienteId"));
        assertNotEquals(ambienteDoDono, ambienteNovo,
            "A renovacao nao pode devolver o ambiente revogado");
        assertEquals(ambienteDaConvidada, ambienteNovo);

        // E do ambiente compartilhado ela nao ve mais nada: a lista dela volta
        // a ter so o proprio ambiente.
        String tokenNovo = (String) renova.getBody().get("tokenAcesso");
        ResponseEntity<Map> ambientes = get(tokenNovo, "/api/ambientes");
        assertEquals(1, ((List<?>) ambientes.getBody().get("ambientes")).size());
    }

    @Test
    @Order(16)
    @DisplayName("Convidada sai sozinha: qualquer um remove a si mesmo (B-D77)")
    void convidadaSaiSozinha() {
        // Re-concede para poder sair.
        assertEquals(HttpStatus.CREATED, post(tokenDono,
            "/api/ambientes/" + ambienteDoDono + "/acessos",
            Map.of("email", emailConvidada)).getStatusCode());

        ResponseEntity<Map> r = delete(tokenConvidada,
            "/api/ambientes/" + ambienteDoDono + "/acessos/" + convidadaId);
        assertEquals(HttpStatus.NO_CONTENT, r.getStatusCode());

        // A lista do dono volta a ter uma pessoa so.
        ResponseEntity<Map> acessos = get(tokenDono,
            "/api/ambientes/" + ambienteDoDono + "/acessos");
        assertEquals(1, ((List<?>) acessos.getBody().get("acessos")).size());
    }

    // =========================================================================

    private void cadastrarTodoMundo() {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        emailDono      = "dono-" + sufixo + "@teste.local";
        emailConvidada = "convidada-" + sufixo + "@teste.local";
        emailTerceiro  = "terceiro-" + sufixo + "@teste.local";

        tokenDono      = cadastrarEEntrar("Dono Teste", emailDono);
        tokenConvidada = cadastrarEEntrar("Convidada Teste", emailConvidada);
        tokenTerceiro  = cadastrarEEntrar("Terceiro Teste", emailTerceiro);

        Map perfilDono = get(tokenDono, "/api/perfil").getBody();
        donoId = UUID.fromString(String.valueOf(perfilDono.get("usuarioId")));
        ambienteDoDono = String.valueOf(perfilDono.get("ambienteAtual"));

        Map perfilConvidada = get(tokenConvidada, "/api/perfil").getBody();
        convidadaId = UUID.fromString(String.valueOf(perfilConvidada.get("usuarioId")));
        ambienteDaConvidada = String.valueOf(perfilConvidada.get("ambienteAtual"));

        // O dono tem uma conta no ambiente — e o que a convidada deve passar
        // a ver, e o que B-D78 impede de escapar.
        assertEquals(HttpStatus.CREATED, post(tokenDono, "/api/contas",
            Map.of("nome", "Conta Conjunta", "natureza", "ATIVO")).getStatusCode());
    }

    private String cadastrarEEntrar(String nome, String email) {
        assertEquals(HttpStatus.CREATED, postSemToken("/api/auth/cadastro",
            Map.of("nome", nome, "email", email, "senha", SENHA)).getStatusCode());

        Map corpo = postSemToken("/api/auth/login",
            Map.of("email", email, "senha", SENHA)).getBody();

        if (email.equals(emailConvidada)) {
            renovacaoConvidada = (String) corpo.get("tokenRenovacao");
        }
        return (String) corpo.get("tokenAcesso");
    }

    private Connection comoProprietario() throws SQLException {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private Connection comoApp() throws SQLException {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), PostgresDeTeste.USUARIO_APP, PostgresDeTeste.SENHA_APP);
    }

    private void assumirIdentidade(Connection c, UUID usuario) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT set_config('raspybank.usuario_id', ?, false)")) {
            ps.setString(1, usuario.toString());
            ps.executeQuery().close();
        }
    }

    private static HttpHeaders cabecalhos(String bearer) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(bearer);
        return h;
    }

    private ResponseEntity<Map> get(String token, String caminho) {
        return http.exchange(caminho, HttpMethod.GET,
            new HttpEntity<>(cabecalhos(token)), Map.class);
    }

    private ResponseEntity<Map> post(String token, String caminho, Map<String, ?> corpo) {
        return http.postForEntity(caminho, new HttpEntity<>(corpo, cabecalhos(token)), Map.class);
    }

    private ResponseEntity<Map> delete(String token, String caminho) {
        return http.exchange(caminho, HttpMethod.DELETE,
            new HttpEntity<>(cabecalhos(token)), Map.class);
    }

    private ResponseEntity<Map> postSemToken(String caminho, Map<String, ?> corpo) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(caminho, new HttpEntity<>(corpo, h), Map.class);
    }
}
