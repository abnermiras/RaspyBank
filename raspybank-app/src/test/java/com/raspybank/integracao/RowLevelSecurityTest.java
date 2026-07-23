package com.raspybank.integracao;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O teste de fumaca do Row Level Security — item (1) do plano do Bloco C
 * (inconsistencia I-10): dois usuarios, cada um cego para os dados do outro.
 *
 * <p><b>Por que JDBC cru, e nao os servicos da aplicacao:</b> o objeto sob
 * teste aqui e o BANCO — as politicas da V3/V8, nao o codigo Java. Conexoes
 * abertas na mao, fora do pool da aplicacao, deixam o teste controlar
 * exatamente o que a politica enxerga: {@code set_config} explicito, escopo de
 * sessao, sem aspecto nem ThreadLocal no meio. Se um dia o aspecto quebrar, e
 * o fluxo HTTP que deve acusar; se uma POLITICA quebrar, e este arquivo.</p>
 *
 * <p>Os dois usuarios sao criados pelas mesmas funcoes SECURITY DEFINER que o
 * cadastro real usa, conectado como {@code raspybank_app} — o caminho de
 * producao, nao um INSERT de owner que atravessaria tudo.</p>
 */
// PER_CLASS permite o @BeforeAll NAO-estatico abaixo. Importa porque o
// JUnit so cria a instancia (e com ela o contexto Spring — e o Flyway!)
// antes de callbacks de instancia. Um @BeforeAll estatico rodaria antes das
// migracoes quando esta classe for a primeira da suite, e as funcoes
// auth_* ainda nao existiriam no banco.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RowLevelSecurityTest extends IntegracaoTest {

    private UUID usuarioA;
    private UUID usuarioB;
    private UUID ambienteA;
    private UUID ambienteB;

    private static Connection comoApp() throws SQLException {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), PostgresDeTeste.USUARIO_APP, PostgresDeTeste.SENHA_APP);
    }

    /** Define a identidade da SESSAO (terceiro argumento false), como no psql manual. */
    private static void assumirIdentidade(Connection c, UUID usuarioId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT set_config('raspybank.usuario_id', ?, false)")) {
            ps.setString(1, usuarioId == null ? "" : usuarioId.toString());
            ps.execute();
        }
    }

    private static UUID cadastrar(Connection c, String nome, String email) throws SQLException {
        // Os casts ::text existem porque o driver JDBC declara String como
        // varchar, e a resolucao de sobrecarga do Postgres nao encontra a
        // funcao (text, text, text) sem eles. O Hibernate faz o mesmo por baixo.
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT auth_cadastrar_usuario(?::text, ?::text, ?::text)")) {
            ps.setString(1, nome);
            ps.setString(2, email);
            ps.setString(3, "$2a$12$hash-de-teste-nao-verificavel");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private static UUID criarAmbienteInicial(Connection c, UUID usuario, String nome)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT auth_criar_ambiente_inicial(?, ?::text)")) {
            ps.setObject(1, usuario);
            ps.setString(2, nome);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    @BeforeAll
    void criarDoisUsuarios() throws SQLException {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        try (Connection c = comoApp()) {
            usuarioA  = cadastrar(c, "Alice Teste", "alice-" + sufixo + "@teste.local");
            ambienteA = criarAmbienteInicial(c, usuarioA, "Ambiente da Alice");
            usuarioB  = cadastrar(c, "Bruno Teste", "bruno-" + sufixo + "@teste.local");
            ambienteB = criarAmbienteInicial(c, usuarioB, "Ambiente do Bruno");
        }
    }

    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Sem identidade na sessao, nada e visivel — nem com SELECT sem WHERE")
    void semIdentidadeNadaVisivel() throws SQLException {
        try (Connection c = comoApp(); Statement s = c.createStatement()) {
            for (String tabela : List.of("usuario", "ambiente", "usuario_ambiente")) {
                try (ResultSet rs = s.executeQuery(
                        "SELECT count(*) FROM " + tabela)) {
                    rs.next();
                    assertEquals(0, rs.getInt(1),
                        "Sessao anonima enxergou linhas em " + tabela);
                }
            }
        }
    }

    @Test
    @DisplayName("Cada usuario enxerga apenas a si mesmo em usuario")
    void usuarioEnxergaApenasASiMesmo() throws SQLException {
        try (Connection c = comoApp()) {
            assumirIdentidade(c, usuarioA);
            List<UUID> visiveis = idsDe(c, "SELECT id FROM usuario");
            assertEquals(List.of(usuarioA), visiveis,
                "Alice deveria enxergar exatamente uma linha: ela mesma");
        }
    }

    @Test
    @DisplayName("Cada usuario enxerga apenas os proprios ambientes — o do outro nao existe")
    void ambienteDoOutroInvisivel() throws SQLException {
        try (Connection c = comoApp()) {
            assumirIdentidade(c, usuarioA);
            assertEquals(List.of(ambienteA), idsDe(c, "SELECT id FROM ambiente"));

            assumirIdentidade(c, usuarioB);
            assertEquals(List.of(ambienteB), idsDe(c, "SELECT id FROM ambiente"));
        }
    }

    @Test
    @DisplayName("Nem por chave: buscar o ambiente do outro pelo id devolve zero linhas")
    void buscaDiretaPorChaveAlheiaDevolveNada() throws SQLException {
        try (Connection c = comoApp()) {
            assumirIdentidade(c, usuarioA);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM ambiente WHERE id = ?")) {
                ps.setObject(1, ambienteB);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertEquals(0, rs.getInt(1),
                        "Alice localizou o ambiente do Bruno conhecendo o UUID");
                }
            }
        }
    }

    @Test
    @DisplayName("Escrever no ambiente alheio e recusado pelo WITH CHECK")
    void escritaEmAmbienteAlheioRecusada() throws SQLException {
        try (Connection c = comoApp()) {
            assumirIdentidade(c, usuarioA);
            assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO usuario_ambiente (usuario_id, ambiente_id) VALUES (?, ?)")) {
                    ps.setObject(1, usuarioA);
                    ps.setObject(2, ambienteB); // auto-convite para o ambiente do Bruno
                    ps.executeUpdate();
                }
            }, "Alice conseguiu se vincular ao ambiente do Bruno");
        }
    }

    @Test
    @DisplayName("senha_hash esta fora do alcance da aplicacao (V8) — ate para o proprio usuario")
    void senhaHashInacessivel() throws SQLException {
        try (Connection c = comoApp()) {
            assumirIdentidade(c, usuarioA);
            SQLException erro = assertThrows(SQLException.class, () -> {
                try (Statement s = c.createStatement();
                     ResultSet rs = s.executeQuery("SELECT senha_hash FROM usuario")) {
                    rs.next();
                }
            });
            // 42501 = insufficient_privilege. E o resultado ESPERADO da V8.
            assertEquals("42501", erro.getSQLState(),
                "Esperava permissao negada, veio: " + erro.getMessage());
        }
    }

    @Test
    @DisplayName("O proprietario atravessa tudo — a prova de que a app JAMAIS pode conectar como owner")
    void proprietarioAtravessaRls() throws SQLException {
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM usuario")) {
            rs.next();
            assertTrue(rs.getInt(1) >= 2,
                "O owner deveria enxergar todos os usuarios, sem filtro");
        }
    }

    // -------------------------------------------------------------------------

    private List<UUID> idsDe(Connection c, String sql) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                ids.add(rs.getObject(1, UUID.class));
            }
        }
        return ids;
    }
}
