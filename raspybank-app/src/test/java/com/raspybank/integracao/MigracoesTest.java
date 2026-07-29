package com.raspybank.integracao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * As migracoes aplicaram, e deixaram o banco no estado que a documentacao
 * promete.
 *
 * <p>A subida do contexto (na classe base) ja provou que o Flyway rodou e que
 * as entidades validam. Aqui conferimos as promessas que NAO falhariam
 * sozinhas — as que quebram em silencio:</p>
 *
 * <ul>
 *   <li>nenhuma migracao com falha registrada;</li>
 *   <li>o inventario de funcoes SECURITY DEFINER ({@code docs/security-definer.md})
 *       confere com o banco real — funcao a mais e furo sem registro, funcao a
 *       menos e documentacao mentindo;</li>
 *   <li>RLS esta LIGADO em todas as tabelas que deveriam te-lo.</li>
 * </ul>
 */
class MigracoesTest extends IntegracaoTest {

    /**
     * O inventario vigente. Criar uma funcao nova exige atualiza-lo AQUI e no doc.
     *
     * <p>Nota: {@code app_usuario_id} NAO esta na lista de proposito. A primeira
     * execucao deste teste (23/07/2026) revelou que ela nunca foi SECURITY
     * DEFINER — a V3 a criou sem a clausula, e nem precisa: {@code current_setting}
     * e legivel por qualquer papel. O documento e que estava errado, e foi
     * corrigido. Primeira captura real do Bloco C.</p>
     */
    private static final List<String> DEFINER_INVENTARIADAS = List.of(
        // Fundacao e autenticacao (V3–V7)
        "app_ambientes_do_usuario",
        "auth_cadastrar_usuario",
        "auth_buscar_credenciais",
        "auth_criar_ambiente_inicial",
        "auth_registrar_evento",
        "auth_ambientes_do_usuario",
        // Dominio (V10). app_criar_conta e a primeira funcao de DOMINIO na
        // lista, e por isso o criterio do inventario foi reescrito em
        // 26/07/2026: o que justifica o furo e o impasse com a politica, nao a
        // camada em que a operacao vive.
        "app_contas_do_usuario",
        "app_criar_conta",
        // V14. Terceira excecao, e do mesmo formato: pol_ambiente_vinculado nao
        // enxerga um ambiente que esta nascendo, porque o vinculo so pode
        // existir depois dele. A identidade vem da SESSAO e nao de parametro —
        // com o usuario como argumento, a funcao viraria "crie um ambiente para
        // fulano".
        "app_criar_ambiente",
        // Gatilhos (V10). Precisam de DEFINER porque o registro que gravam tem
        // de entrar mesmo quando o autor nao tem identidade valida — auditoria
        // recusada pela politica seria o pior resultado possivel.
        "fn_auditar",
        "fn_publicar_evento_lancamento");

    private static final List<String> TABELAS_COM_RLS = List.of(
        // Fundacao (V3)
        "usuario", "ambiente", "usuario_ambiente", "registro_auditoria", "outbox",
        // Dominio (V10). A regra da casa: tabela nova nasce com RLS ligado.
        // Tabela de dominio sem politica e tabela que qualquer usuario le inteira.
        "categoria", "subcategoria", "conta", "conta_ambiente", "lancamento");

    private Connection comoProprietario() throws SQLException {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Test
    @DisplayName("Nenhuma migracao falhou")
    void migracoesSemFalha() throws SQLException {
        try (Connection c = comoProprietario();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT count(*) FROM flyway_schema_history WHERE success = false")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "Existe migracao registrada como falha");
        }
    }

    @Test
    @DisplayName("Funcoes SECURITY DEFINER do banco == inventario do repositorio")
    void inventarioSecurityDefinerConfere() throws SQLException {
        try (Connection c = comoProprietario();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("""
                 SELECT p.proname
                   FROM pg_proc p
                   JOIN pg_namespace n ON n.oid = p.pronamespace
                  WHERE n.nspname = 'public'
                    AND p.prosecdef
                  ORDER BY p.proname""")) {

            var noBanco = new java.util.ArrayList<String>();
            while (rs.next()) {
                noBanco.add(rs.getString(1));
            }

            assertEquals(
                DEFINER_INVENTARIADAS.stream().sorted().toList(),
                noBanco,
                "Divergencia entre docs/security-definer.md e o banco. "
                + "Funcao sem entrada no inventario e divida (regra do documento).");
        }
    }

    @Test
    @DisplayName("RLS ligado nas dez tabelas: cinco da fundacao, cinco do dominio")
    void rlsLigadoNasTabelas() throws SQLException {
        try (Connection c = comoProprietario();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT relrowsecurity FROM pg_class WHERE relname = ?")) {

            for (String tabela : TABELAS_COM_RLS) {
                ps.setString(1, tabela);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "Tabela nao encontrada: " + tabela);
                    assertTrue(rs.getBoolean(1), "RLS DESLIGADO em: " + tabela);
                }
            }
        }
    }
}
