package com.raspybank.integracao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 *   <li>as tabelas com RLS ligado no banco sao exatamente as inventariadas aqui
 *       — tabela a menos e politica que alguem desligou, tabela a mais e tabela
 *       nova que nasceu sem ninguem conferir se a politica cobre o caso.</li>
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
        "fn_publicar_evento_lancamento",
        // Compartilhamento (V15). Tres irmas de app_ambientes_do_usuario, com
        // a mesma razao de DEFINER (politica consultando tabela com politica),
        // e a funcao estreita do convite: resolver e-mail em id e o impasse de
        // B-D19 na forma pura — a linha do convidado so se tornaria visivel
        // pelo vinculo que a operacao vai criar.
        "app_ambientes_proprios",
        "app_contas_proprias",
        "app_membros_dos_meus_ambientes",
        "app_usuario_por_email",
        // Compartilhamento de CONTA (V16). Duas naturezas diferentes na mesma
        // migracao, e vale distingui-las:
        //
        // app_aceitar_convite_de_conta e o impasse classico — ela insere um
        // vinculo entre uma conta que NAO e dela e um ambiente que e, e o
        // WITH CHECK exige conta propria dos dois lados justamente para impedir
        // captura de conta alheia. O que separa aceite de captura e o convite,
        // e por isso a funcao le o convite em vez de receber a conta.
        //
        // As outras quatro sao a QUARTA excecao de B-D19 e as primeiras em
        // consulta de LEITURA (B-D96). O impasse e de outra forma e igualmente
        // inevitavel: por construcao uma pessoa nao pode ver os lancamentos da
        // outra pela politica, e mesmo assim precisa soma-los, senao os dois
        // veem saldos diferentes na mesma conta. Cada uma tem PORTEIRO na
        // primeira linha — sem ele, DEFINER significaria "leia qualquer conta
        // do sistema, basta ter o UUID".
        "app_aceitar_convite_de_conta",
        "app_compartilhamentos_da_conta",
        // A terceira irma de app_contas_do_usuario, e ela existe porque os dois
        // modos de compartilhar respondem DIFERENTE para "quem renomeia e
        // encerra esta conta?": no ambiente e dinheiro (B-D76), na conta nao
        // (B-D95). A regra que satisfaz as duas e "estar no ambiente onde a
        // conta nasceu".
        "app_contas_nao_emprestadas",
        // Antes do aceite a conta e invisivel para quem foi convidado — a
        // politica pede o vinculo que o aceite vai criar. Sem esta funcao o
        // convite chegaria como "alguem quer dividir algo com voce".
        "app_convites_de_conta_pendentes",
        "app_dono_da_conta",
        "app_extrato_da_conta",
        // Revogar e a irma do aceite, e pelo motivo mais curioso da lista: o
        // dono precisa encerrar uma linha que ele nao pode VER — pol_ca_leitura
        // mostra a cada um so o proprio lado do vinculo, e isso e deliberado
        // (B-D90). A politica autoriza a escrita; nenhum UPDATE comum alcanca a
        // linha.
        "app_revogar_conta_compartilhada",
        "app_saldo_da_conta",
        // Cartao compartilhado (V17). As duas ultimas da quarta excecao, e o
        // sintoma de nao te-las e pior que um saldo divergente: a fatura
        // pareceria menor do que e, e o pagamento sairia curto — alguem
        // descobriria com juros.
        "app_extrato_da_fatura",
        "app_total_da_fatura",
        // O PLASTICO como unidade (V19). O compartilhamento de cartao passou a
        // ser por emitido, e nao pela conta do contrato (B-D106): dividir um
        // cartao entregava os dez plasticos.
        //
        // As duas primeiras sao as irmas de sempre — politica consultando tabela
        // com politica. O aceite e a revogacao repetem os impasses do §4k, com um
        // passo a mais: cartao_emitido_ambiente NAO TEM politica de escrita
        // nenhuma, entao nao existe caminho por fora das funcoes.
        "app_aceitar_convite_de_plastico",
        "app_compartilhamentos_do_plastico",
        "app_emitidos_liberados",
        "app_revogar_plastico_compartilhado",
        "app_total_do_plastico",
        // V20. O nome do banco para quem recebeu um plastico (B-D112) — a conta
        // do banco e de quem abriu o cartao, e ela nao a enxerga. Devolve SO o
        // nome: nao da saldo, nem formas, nem o direito de lancar nela.
        "app_nome_do_banco_do_cartao");

    private static final List<String> TABELAS_COM_RLS = List.of(
        // Fundacao (V3)
        "usuario", "ambiente", "usuario_ambiente", "registro_auditoria", "outbox",
        // Dominio (V10). A regra da casa: tabela nova nasce com RLS ligado.
        // Tabela de dominio sem politica e tabela que qualquer usuario le inteira.
        "categoria", "subcategoria", "conta", "conta_ambiente", "lancamento",
        // Forma de pagamento por conta (V11)
        "conta_forma_pagamento",
        // Cartao de credito (V12). A fatura nao guarda total (P1), mas guarda
        // quais compras caem nela — e isso e extrato de quem gasta.
        "cartao", "cartao_emitido", "fatura",
        // Compartilhamento de conta (V16) e de plastico (V19)
        "conta_convite", "cartao_emitido_ambiente");

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
    @DisplayName("Tabelas com RLS ligado no banco == inventario do repositorio")
    void rlsLigadoNasTabelas() throws SQLException {
        try (Connection c = comoProprietario();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("""
                 SELECT c.relname
                   FROM pg_class c
                   JOIN pg_namespace n ON n.oid = c.relnamespace
                  WHERE n.nspname = 'public'
                    AND c.relkind = 'r'
                    AND c.relrowsecurity
                  ORDER BY c.relname""")) {

            var noBanco = new java.util.ArrayList<String>();
            while (rs.next()) {
                noBanco.add(rs.getString(1));
            }

            assertEquals(
                TABELAS_COM_RLS.stream().sorted().toList(),
                noBanco,
                "Divergencia entre a lista deste teste e o banco. Tabela a menos e "
                + "RLS que alguem desligou; tabela a mais e tabela nova que entrou "
                + "sem passar por aqui — e a segunda e a que some em silencio.");
        }
    }
}
