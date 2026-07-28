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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fumaca de RLS das tabelas de dominio — o mesmo teste que o
 * {@link RowLevelSecurityTest} faz na fundacao, agora sobre o que a V10 criou.
 *
 * <p>Mesmo desenho e pelas mesmas razoes (B-C2): conexoes JDBC cruas, fora do
 * pool da aplicacao, porque o objeto sob teste sao as POLITICAS, nao o codigo
 * Java. Se o aspecto quebrar, quem acusa e o teste de fluxo HTTP; se uma
 * politica quebrar, e este arquivo.</p>
 *
 * <p>Alem do isolamento, aqui se verificam tres coisas que a V10 decidiu e que
 * nao existiriam sem ela: as chaves compostas que transformam regra de negocio
 * em impossibilidade estrutural, o gatilho de auditoria lendo o canal (B-D6), e
 * as categorias sistemicas nascendo com o ambiente (B-D16) — uma promessa que
 * F13 fazia desde a Fase 2 e que nenhuma migracao cumpria.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DominioRlsTest extends IntegracaoTest {

    private UUID usuarioA;
    private UUID usuarioB;
    private UUID ambienteA;
    private UUID ambienteB;
    private UUID contaA;
    private UUID contaB;

    // -------------------------------------------------------------------------
    // Preparo
    // -------------------------------------------------------------------------

    private static Connection comoApp() throws SQLException {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), PostgresDeTeste.USUARIO_APP, PostgresDeTeste.SENHA_APP);
    }

    private static Connection comoDono() throws SQLException {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void assumirIdentidade(Connection c, UUID usuarioId) throws SQLException {
        definirVariavel(c, "raspybank.usuario_id",
            usuarioId == null ? "" : usuarioId.toString());
    }

    private static void definirVariavel(Connection c, String chave, String valor)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config(?, ?, false)")) {
            ps.setString(1, chave);
            ps.setString(2, valor);
            ps.execute();
        }
    }

    private static UUID cadastrar(Connection c, String nome, String email) throws SQLException {
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

    private static UUID criarConta(Connection c, UUID ambiente, String nome) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT app_criar_conta(?, ?::text, ?::text)")) {
            ps.setObject(1, ambiente);
            ps.setString(2, nome);
            ps.setString(3, "ATIVO");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    /** Localiza uma sistemica pelo codigo, no ambiente informado. */
    private static UUID sistemica(Connection c, UUID ambiente, String codigo) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM categoria WHERE ambiente_id = ? AND codigo = ?::text")) {
            ps.setObject(1, ambiente);
            ps.setString(2, codigo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1, UUID.class) : null;
            }
        }
    }

    @BeforeAll
    void prepararDoisMundos() throws SQLException {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        try (Connection c = comoApp()) {
            usuarioA  = cadastrar(c, "Alice Dominio", "alice-dom-" + sufixo + "@teste.local");
            ambienteA = criarAmbienteInicial(c, usuarioA, "Casa da Alice");

            usuarioB  = cadastrar(c, "Bruno Dominio", "bruno-dom-" + sufixo + "@teste.local");
            ambienteB = criarAmbienteInicial(c, usuarioB, "Casa do Bruno");

            // app_criar_conta le a identidade da sessao — cada um cria a sua.
            assumirIdentidade(c, usuarioA);
            contaA = criarConta(c, ambienteA, "Corrente da Alice");

            assumirIdentidade(c, usuarioB);
            contaB = criarConta(c, ambienteB, "Corrente do Bruno");
        }
    }

    // -------------------------------------------------------------------------
    // As sistemicas — B-D13, B-D16
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Ambiente novo nasce com as TRES sistemicas — a promessa de F13 enfim cumprida")
    void ambienteNasceComAsSistemicas() throws SQLException {
        try (Connection c = comoApp()) {
            assumirIdentidade(c, usuarioA);

            List<String> codigos = new ArrayList<>();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                     "SELECT codigo FROM categoria WHERE sistemica ORDER BY codigo")) {
                while (rs.next()) {
                    codigos.add(rs.getString(1));
                }
            }

            assertEquals(List.of("AJUSTE", "NAO_CLASSIFICADO", "PAGAMENTO_FATURA", "TRANSFERENCIA"),
                         codigos,
                "A V5 criava ambiente sem categoria nenhuma; a V10 devia ter consertado (B-D16)");
        }
    }

    @Test
    @DisplayName("`sistemica` e `entra_no_mapa` sao perguntas diferentes (B-D15)")
    void naoClassificadoEhSistemicaMasEntraNoMapa() throws SQLException {
        try (Connection c = comoApp()) {
            assumirIdentidade(c, usuarioA);

            // O ponto exato onde uma flag so daria a resposta errada: as tres
            // sao sistemicas, e uma delas E gasto. Escondê-la faria o total do
            // mapa mentir para baixo, em silencio.
            assertEquals(true,  entraNoMapa(c, "NAO_CLASSIFICADO"),
                "Gasto sem rotulo continua sendo gasto");
            assertEquals(false, entraNoMapa(c, "TRANSFERENCIA"),
                "Mover dinheiro entre contas proprias nao e gasto");
            assertEquals(false, entraNoMapa(c, "AJUSTE"),
                "Ajuste e correcao contabil, nao despesa");
            assertEquals(false, entraNoMapa(c, "PAGAMENTO_FATURA"),
                "Os gastos do cartao ja entraram um a um: contar o pagamento"
                    + " somaria a mesma despesa duas vezes (B-D59)");
        }
    }

    private boolean entraNoMapa(Connection c, String codigo) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT entra_no_mapa FROM categoria WHERE codigo = ?::text")) {
            ps.setString(1, codigo);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Isolamento
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Sem identidade, nenhuma tabela de dominio devolve linha")
    void semIdentidadeNadaVisivel() throws SQLException {
        try (Connection c = comoApp(); Statement s = c.createStatement()) {
            for (String tabela : List.of(
                    "categoria", "subcategoria", "conta", "conta_ambiente", "lancamento")) {
                try (ResultSet rs = s.executeQuery("SELECT count(*) FROM " + tabela)) {
                    rs.next();
                    assertEquals(0, rs.getInt(1),
                        "Sessao anonima enxergou linhas em " + tabela);
                }
            }
        }
    }

    @Test
    @DisplayName("Cada um enxerga so as proprias categorias — inclusive as sistemicas, que sao copias")
    void categoriaDoOutroInvisivel() throws SQLException {
        try (Connection c = comoApp()) {
            assumirIdentidade(c, usuarioA);
            assertEquals(4, contar(c, "SELECT count(*) FROM categoria"),
                "Alice deveria ver apenas as sistemicas DELA");

            // F9: sistemicas sao COPIADAS por ambiente, nao compartilhadas.
            // Se fossem compartilhadas, este id seria o mesmo dos dois lados.
            UUID transfA = sistemica(c, ambienteA, "TRANSFERENCIA");
            assumirIdentidade(c, usuarioB);
            UUID transfB = sistemica(c, ambienteB, "TRANSFERENCIA");

            assertTrue(transfA != null && transfB != null,
                "Os dois ambientes deveriam ter a sua propria TRANSFERENCIA");
            assertTrue(!transfA.equals(transfB),
                "Sistemica compartilhada entre ambientes violaria F9");
        }
    }

    @Test
    @DisplayName("Conta so aparece para quem tem vinculo — nem pelo id direto")
    void contaDoOutroInvisivel() throws SQLException {
        try (Connection c = comoApp()) {
            assumirIdentidade(c, usuarioA);

            // Contem/nao-contem, e nao igualdade de lista: outros testes desta
            // classe criam contas para a Alice, e a ordem de execucao do JUnit
            // nao e garantida. Asserir a lista exata amarraria este teste a
            // quem mais mexe no mesmo usuario — ele passaria ou falharia por
            // um motivo que nao e o dele.
            List<UUID> visiveis = idsDe(c, "SELECT id FROM conta");
            assertTrue(visiveis.contains(contaA), "Alice nao enxergou a propria conta");
            assertTrue(!visiveis.contains(contaB), "Alice enxergou a conta do Bruno");

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM conta WHERE id = ?")) {
                ps.setObject(1, contaB);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertEquals(0, rs.getInt(1),
                        "Alice localizou a conta do Bruno conhecendo o UUID");
                }
            }
        }
    }

    @Test
    @DisplayName("Sequestro de conta alheia pelo vinculo e recusado (o furo que o WITH CHECK duplo fecha)")
    void naoSeVinculaContaAlheia() throws SQLException {
        try (Connection c = comoApp()) {
            assumirIdentidade(c, usuarioA);

            // Conferir so o ambiente_id protegeria UM lado do vinculo. Alice
            // esta no ambiente dela, entao essa metade passa; o que a barra e a
            // segunda condicao, sobre a conta. Sem ela, bastaria ter o UUID do
            // Bruno para enxergar a conta dele e todo o historico.
            assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO conta_ambiente (conta_id, ambiente_id) VALUES (?, ?)")) {
                    ps.setObject(1, contaB);
                    ps.setObject(2, ambienteA);
                    ps.executeUpdate();
                }
            }, "Alice anexou a conta do Bruno ao ambiente dela");
        }
    }

    @Test
    @DisplayName("app_criar_conta recusa ambiente de que o usuario nao participa")
    void portaEstreitaConfereOVinculo() throws SQLException {
        try (Connection c = comoApp()) {
            assumirIdentidade(c, usuarioA);

            // SECURITY DEFINER ignora politicas: quem escreve a funcao assume a
            // responsabilidade que o banco deixou de ter. Sem esta checagem, a
            // porta estreita seria o buraco que o RLS existe para fechar.
            assertThrows(SQLException.class,
                () -> criarConta(c, ambienteB, "Conta intrusa"),
                "A porta estreita aceitou ambiente alheio");
        }
    }

    // -------------------------------------------------------------------------
    // As chaves compostas — B-D2 e F11
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("B-D2: lancamento nao aceita conta que nao pertence ao seu ambiente")
    void lancamentoRecusaContaDeOutroAmbiente() throws SQLException {
        try (Connection c = comoDono()) {
            // Como PROPRIETARIO de proposito: o RLS nao entra na jogada aqui.
            // O que esta sob teste e a restricao ESTRUTURAL — ela precisa valer
            // mesmo para quem atravessa as politicas.
            UUID categoria = sistemica(c, ambienteA, "NAO_CLASSIFICADO");

            SQLException erro = assertThrows(SQLException.class,
                () -> inserirLancamento(c, ambienteA, contaB, categoria, usuarioA),
                "A chave composta deveria recusar conta de outro ambiente");

            // 23503 = foreign_key_violation
            assertEquals("23503", erro.getSQLState(),
                "Esperava violacao de chave estrangeira, veio: " + erro.getMessage());
        }
    }

    @Test
    @DisplayName("F11: subcategoria de outra categoria nao classifica o lancamento")
    void lancamentoRecusaSubcategoriaDeOutraCategoria() throws SQLException {
        try (Connection c = comoDono()) {
            UUID catAjuste = sistemica(c, ambienteA, "AJUSTE");
            UUID catNaoCla = sistemica(c, ambienteA, "NAO_CLASSIFICADO");

            UUID subDoAjuste;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO subcategoria (ambiente_id, categoria_id, nome) "
                  + "VALUES (?, ?, ?::text) RETURNING id")) {
                ps.setObject(1, ambienteA);
                ps.setObject(2, catAjuste);
                ps.setString(3, "Sub do ajuste " + UUID.randomUUID());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    subDoAjuste = rs.getObject(1, UUID.class);
                }
            }

            // "Alimentacao > Combustivel" nao existe: o par tem que bater.
            SQLException erro = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO lancamento (ambiente_id, conta_id, categoria_id, "
                      + "subcategoria_id, tipo, situacao, valor, data_competencia, "
                      + "data_caixa, criado_por) "
                      + "VALUES (?, ?, ?, ?, 'SAIDA', 'REALIZADO', 10.00, ?, ?, ?)")) {
                    ps.setObject(1, ambienteA);
                    ps.setObject(2, contaA);
                    ps.setObject(3, catNaoCla);      // categoria X
                    ps.setObject(4, subDoAjuste);    // subcategoria de Y
                    ps.setObject(5, LocalDate.now());
                    ps.setObject(6, LocalDate.now());
                    ps.setObject(7, usuarioA);
                    ps.executeUpdate();
                }
            }, "A FK composta de F11 deveria recusar o par");

            assertEquals("23503", erro.getSQLState(),
                "Esperava violacao de chave estrangeira, veio: " + erro.getMessage());
        }
    }

    @Test
    @DisplayName("Valor negativo e recusado: o sinal e responsabilidade de `tipo`")
    void valorNegativoRecusado() throws SQLException {
        try (Connection c = comoDono()) {
            UUID categoria = sistemica(c, ambienteA, "NAO_CLASSIFICADO");
            SQLException erro = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO lancamento (ambiente_id, conta_id, categoria_id, tipo, "
                      + "situacao, valor, data_competencia, data_caixa, criado_por) "
                      + "VALUES (?, ?, ?, 'SAIDA', 'REALIZADO', -50.00, ?, ?, ?)")) {
                    ps.setObject(1, ambienteA);
                    ps.setObject(2, contaA);
                    ps.setObject(3, categoria);
                    ps.setObject(4, LocalDate.now());
                    ps.setObject(5, LocalDate.now());
                    ps.setObject(6, usuarioA);
                    ps.executeUpdate();
                }
            });
            // 23514 = check_violation. Guardar negativo abriria duas
            // representacoes para a mesma saida.
            assertEquals("23514", erro.getSQLState(),
                "Esperava violacao de CHECK, veio: " + erro.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Auditoria por gatilho com canal — F26 / B-D6
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("O gatilho grava o canal do contexto (B-D6) — o que resolveu o I-05")
    void auditoriaGravaOCanalDoContexto() throws SQLException {
        try (Connection c = comoApp()) {
            assumirIdentidade(c, usuarioA);
            definirVariavel(c, "raspybank.canal", "TELEGRAM");

            UUID conta = criarConta(c, ambienteA, "Conta via bot " + UUID.randomUUID());

            String canal;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT canal FROM registro_auditoria "
                  + "WHERE entidade = 'Conta' AND entidade_id = ?")) {
                ps.setObject(1, conta);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    canal = rs.getString(1);
                }
            }

            // Era exatamente isto que o gatilho nao sabia fazer, e o motivo de a
            // V2 ter registrado auditoria por servico. O canal no contexto do
            // RLS dissolveu o conflito em vez de arbitra-lo.
            assertEquals("TELEGRAM", canal,
                "O gatilho deveria ter lido raspybank.canal");
        }
    }

    @Test
    @DisplayName("Alteracao SEM canal na sessao se denuncia: DESCONHECIDO e autor nulo")
    void alteracaoExternaSeDenuncia() throws SQLException {
        try (Connection c = comoDono()) {
            // Proprietario, sem variavel de sessao nenhuma: e o retrato de
            // alguem mexendo por psql. E a virtude que so o gatilho tem —
            // auditoria por servico nunca acusaria isto.
            UUID conta = criarContaComoDono(c, ambienteA, "Conta por fora " + UUID.randomUUID());

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT canal, usuario_id FROM registro_auditoria "
                  + "WHERE entidade = 'Conta' AND entidade_id = ?")) {
                ps.setObject(1, conta);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertEquals("DESCONHECIDO", rs.getString(1),
                        "Sem canal na sessao, o registro deveria dizer DESCONHECIDO");
                    assertNull(rs.getObject("usuario_id"),
                        "Sem identidade na sessao, o autor deveria ser nulo");
                }
            }
        }
    }

    private UUID criarContaComoDono(Connection c, UUID ambiente, String nome) throws SQLException {
        UUID id;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO conta (nome, natureza) VALUES (?::text, 'ATIVO') RETURNING id")) {
            ps.setString(1, nome);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                id = rs.getObject(1, UUID.class);
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO conta_ambiente (conta_id, ambiente_id) VALUES (?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, ambiente);
            ps.executeUpdate();
        }
        return id;
    }

    // -------------------------------------------------------------------------
    // Outbox — F28
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Lancamento publica evento no outbox, na mesma transacao (F28)")
    void lancamentoAlimentaOOutbox() throws SQLException {
        try (Connection c = comoDono()) {
            UUID categoria = sistemica(c, ambienteA, "NAO_CLASSIFICADO");
            UUID lancamento = inserirLancamento(c, ambienteA, contaA, categoria, usuarioA);

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT tipo_evento, agregado, ambiente_id, payload ->> 'valor' "
                  + "FROM outbox WHERE agregado_id = ?")) {
                ps.setObject(1, lancamento);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "Nenhum evento publicado para o lancamento");
                    assertEquals("LancamentoRegistrado", rs.getString(1));
                    assertEquals("Lancamento", rs.getString(2));
                    assertEquals(ambienteA, rs.getObject(3, UUID.class));
                    // Payload autocontido: quem consome nao precisa consultar a
                    // tabela de origem para saber o que aconteceu.
                    assertTrue(rs.getString(4).startsWith("123"),
                        "O payload deveria trazer o valor do lancamento");
                }
            }
        }
    }

    @Test
    @DisplayName("Lancamento do outro ambiente e invisivel, com ou sem conta compartilhada")
    void lancamentoDoOutroInvisivel() throws SQLException {
        UUID doBruno;
        try (Connection c = comoDono()) {
            UUID categoria = sistemica(c, ambienteB, "NAO_CLASSIFICADO");
            doBruno = inserirLancamento(c, ambienteB, contaB, categoria, usuarioB);
        }

        try (Connection c = comoApp()) {
            assumirIdentidade(c, usuarioA);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM lancamento WHERE id = ?")) {
                ps.setObject(1, doBruno);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertEquals(0, rs.getInt(1),
                        "Alice enxergou lancamento do ambiente do Bruno");
                }
            }
        }
    }

    // -------------------------------------------------------------------------

    private UUID inserirLancamento(Connection c, UUID ambiente, UUID conta,
                                   UUID categoria, UUID autor) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO lancamento (ambiente_id, conta_id, categoria_id, tipo, "
              + "situacao, valor, descricao, data_competencia, data_caixa, criado_por) "
              + "VALUES (?, ?, ?, 'SAIDA', 'REALIZADO', 123.45, 'Teste', ?, ?, ?) "
              + "RETURNING id")) {
            ps.setObject(1, ambiente);
            ps.setObject(2, conta);
            ps.setObject(3, categoria);
            ps.setObject(4, LocalDate.now());
            ps.setObject(5, LocalDate.now());
            ps.setObject(6, autor);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private long contar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

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
