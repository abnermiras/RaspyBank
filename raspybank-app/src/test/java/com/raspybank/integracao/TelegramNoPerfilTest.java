package com.raspybank.integracao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * O caminho de escrita do Telegram no perfil (B-D115), atacado de fora.
 *
 * <p>Nao repete o caminho feliz — {@code PerfilApiTest} ja o cobre. Aqui esta
 * a segunda operacao: limpar depois de gravar, gravar o que ja e de outro,
 * gravar duas vezes ao mesmo tempo, e mexer na linha alheia por baixo do HTTP.</p>
 *
 * <p>Os testes de politica usam conexoes JDBC cruas como {@code raspybank_app},
 * no mesmo desenho do {@link DominioRlsTest} (B-C2): o objeto sob teste e a
 * politica, nao o Java que a invoca. Rodar como proprietario testaria um
 * sistema com RLS desligado.</p>
 */
class TelegramNoPerfilTest extends IntegracaoTest {

    @Autowired
    private TestRestTemplate http;

    private static final String SENHA = "senha-com-mais-de-10";

    // =========================================================================
    // 1. Limpar o campo — o caso que o indice parcial existe para permitir
    // =========================================================================

    /**
     * O motivo do {@code NULLIF} da V18, agora pela porta do perfil: se o
     * branco virasse {@code ''}, a SEGUNDA pessoa a limpar bateria em
     * duplicidade num campo que ninguem preencheu.
     */
    @Test
    @DisplayName("Duas contas limpam o Telegram em sequencia sem colidir, e o banco grava NULL")
    void duasContasLimpamSemColidir() {
        Conta a = novaConta("tg-limpa-a");
        Conta b = novaConta("tg-limpa-b");

        assertEquals(HttpStatus.OK, put(a, "aaa_" + curto()).getStatusCode());
        assertEquals(HttpStatus.OK, put(b, "bbb_" + curto()).getStatusCode());

        ResponseEntity<Map> limpaA = put(a, "");
        assertEquals(HttpStatus.OK, limpaA.getStatusCode(), "A primeira limpeza passa");

        ResponseEntity<Map> limpaB = put(b, "");
        assertEquals(HttpStatus.OK, limpaB.getStatusCode(),
            "A SEGUNDA limpeza tambem passa — se virasse string vazia, seria 409 aqui");

        // E o que ficou no banco e NULL, nao ''. A distincao e o indice parcial
        // inteiro: '' seria valor real para ele.
        assertNull(telegramNoBanco(a.id), "Vazio tem de virar NULL, nunca ''");
        assertNull(telegramNoBanco(b.id), "Vazio tem de virar NULL, nunca ''");
    }

    @Test
    @DisplayName("A resposta do PUT ja traz o valor novo, inclusive quando o valor novo e nenhum")
    void aRespostaRefleteAEscrita() {
        Conta a = novaConta("tg-reflete");
        String valor = "reflete_" + curto();

        ResponseEntity<Map> grava = put(a, valor);
        assertEquals(HttpStatus.OK, grava.getStatusCode());
        assertEquals(valor, grava.getBody().get("telegramId"),
            "A tela nao recarrega o perfil depois de salvar — a resposta tem de ser a verdade");
        assertEquals(valor, get(a).getBody().get("telegramId"), "E a verdade tem de estar gravada");

        ResponseEntity<Map> limpa = put(a, "");
        assertEquals(HttpStatus.OK, limpa.getStatusCode());
        assertTrue(limpa.getBody().containsKey("telegramId"),
            "A chave nao pode sumir quando o valor e nulo — a tela le eu.telegramId");
        assertNull(limpa.getBody().get("telegramId"),
            "Limpar e o caso que um contexto de persistencia aberto mascararia");
        assertNull(get(a).getBody().get("telegramId"));
    }

    // =========================================================================
    // 2. 409 de verdade
    // =========================================================================

    @Test
    @DisplayName("Telegram que ja e de outra conta responde 409 com {erro} e nao altera nada")
    void telegramDeOutraContaConflita() {
        Conta dona = novaConta("tg-dona");
        Conta invasora = novaConta("tg-invasora");

        String valor = "disputado_" + curto();
        assertEquals(HttpStatus.OK, put(dona, valor).getStatusCode());

        String meuValor = "meu_" + curto();
        assertEquals(HttpStatus.OK, put(invasora, meuValor).getStatusCode());

        ResponseEntity<Map> conflito = put(invasora, valor);

        assertEquals(HttpStatus.CONFLICT, conflito.getStatusCode());
        assertNotNull(conflito.getBody().get("erro"),
            "O contrato de erro e {\"erro\": <frase exibivel>}");
        assertFalse(conflito.getBody().containsKey("campos"),
            "campos so aparece em validacao (400), nao em conflito");

        // Nenhum dos dois lados se moveu.
        assertEquals(valor, telegramNoBanco(dona.id), "A conta dona do valor nao pode ser afetada");
        assertEquals(meuValor, telegramNoBanco(invasora.id),
            "O 409 nao pode deixar a linha de quem tentou pela metade");

        // E a sessao continua utilizavel depois do erro.
        assertEquals(HttpStatus.OK, put(invasora, "depois_" + curto()).getStatusCode(),
            "Um 409 nao pode envenenar as requisicoes seguintes");
    }

    @Test
    @DisplayName("Gravar o MESMO valor duas vezes nao conflita consigo mesmo")
    void idempotenteContraSiMesmo() {
        Conta a = novaConta("tg-idem");
        String valor = "idem_" + curto();

        assertEquals(HttpStatus.OK, put(a, valor).getStatusCode());

        ResponseEntity<Map> denovo = put(a, valor);
        assertEquals(HttpStatus.OK, denovo.getStatusCode(),
            "O indice unico nao pode disparar contra a propria linha");
        assertEquals(valor, denovo.getBody().get("telegramId"));

        // E limpar duas vezes seguidas tambem e inofensivo.
        assertEquals(HttpStatus.OK, put(a, "").getStatusCode());
        assertEquals(HttpStatus.OK, put(a, "").getStatusCode());
        assertNull(telegramNoBanco(a.id));
    }

    // =========================================================================
    // 3. Concorrencia — o caminho feliz sequencial ja passava
    // =========================================================================

    @Test
    @DisplayName("Duas contas disputando o mesmo Telegram ao mesmo tempo: uma grava, a outra recebe 409")
    void disputaSimultaneaTemUmVencedorSo() throws Exception {
        Conta a = novaConta("tg-corrida-a");
        Conta b = novaConta("tg-corrida-b");
        String disputado = "corrida_" + curto();

        List<ResponseEntity<Map>> respostas = emParalelo(
            () -> put(a, disputado),
            () -> put(b, disputado));

        long ok = respostas.stream().filter(r -> r.getStatusCode() == HttpStatus.OK).count();
        long conflitos = respostas.stream()
            .filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).count();

        assertEquals(1, ok, "Exatamente uma das duas pode ficar com o valor: " + resumo(respostas));
        assertEquals(1, conflitos,
            "A perdedora recebe 409, nao 500 — o erro e previsto: " + resumo(respostas));

        String deA = telegramNoBanco(a.id);
        String deB = telegramNoBanco(b.id);
        assertTrue(disputado.equals(deA) ^ disputado.equals(deB),
            "So uma linha pode terminar com o valor. A=" + deA + " B=" + deB);
    }

    @Test
    @DisplayName("A mesma conta gravando dois valores ao mesmo tempo termina em um deles, nunca em 500")
    void escritasSimultaneasNaMesmaLinha() throws Exception {
        Conta a = novaConta("tg-corrida-mesma");
        String um = "par_um_" + curto();
        String dois = "par_dois_" + curto();

        List<ResponseEntity<Map>> respostas = emParalelo(
            () -> put(a, um),
            () -> put(a, dois));

        respostas.forEach(r -> assertTrue(r.getStatusCode().is2xxSuccessful(),
            "Escrever na propria linha nao pode falhar: " + resumo(respostas)));

        String gravado = telegramNoBanco(a.id);
        assertTrue(um.equals(gravado) || dois.equals(gravado),
            "O que ficou gravado tem de ser um dos dois valores enviados, e foi: " + gravado);
    }

    // =========================================================================
    // 4. RLS na direcao errada — mexer na linha de outra pessoa
    // =========================================================================

    /**
     * Nao basta nao haver endpoint: a politica tem de tornar impossivel.
     * Aqui o UPDATE e cru, como {@code raspybank_app}, com a identidade de A na
     * sessao e o id de B no WHERE.
     */
    @Test
    @DisplayName("pol_usuario_escrita: com a identidade de A, o UPDATE na linha de B nao alcanca linha nenhuma")
    void naoSeAlteraOTelegramDeOutraPessoa() throws SQLException {
        Conta a = novaConta("tg-rls-a");
        Conta b = novaConta("tg-rls-b");

        String deB = "sodob_" + curto();
        assertEquals(HttpStatus.OK, put(b, deB).getStatusCode());

        try (Connection c = comoApp()) {
            assumirIdentidade(c, a.id);

            int alteradas = atualizarTelegram(c, b.id, "roubado_" + curto());
            assertEquals(0, alteradas,
                "A politica de escrita e id = app_usuario_id(): a linha de B nao esta no alcance de A");

            // Sem identidade nenhuma na sessao, tambem nao.
            assumirIdentidade(c, null);
            assertEquals(0, atualizarTelegram(c, b.id, "anonimo_" + curto()),
                "Sessao sem identidade nao escreve em usuario nenhum");
        }

        assertEquals(deB, telegramNoBanco(b.id), "E o valor de B continua o de B");
    }

    /**
     * O caso que importa mais que o do estranho: {@code pol_usuario_leitura}
     * (V15) deixa quem divide ambiente comigo LER minha linha cadastral, e o
     * telegram esta entre as colunas concedidas. Ver nao pode virar escrever.
     */
    @Test
    @DisplayName("Quem divide ambiente comigo LE meu Telegram, e ainda assim nao consegue troca-lo")
    void coMembroVeMasNaoEscreve() throws SQLException {
        Conta dona = novaConta("tg-comembro-dona");
        Conta convidada = novaConta("tg-comembro-conv");

        String meu = "sominha_" + curto();
        assertEquals(HttpStatus.OK, put(dona, meu).getStatusCode());

        vincularAoAmbienteDe(convidada.id, dona.id);

        try (Connection c = comoApp()) {
            assumirIdentidade(c, convidada.id);

            // Primeiro a metade que a V15 concede de proposito: a linha e visivel.
            assertEquals(meu, lerTelegram(c, dona.id),
                "Sem esta leitura, o 0 abaixo seria invisibilidade, nao politica de escrita");

            // E agora a metade que importa.
            assertEquals(0, atualizarTelegram(c, dona.id, "tomei_" + curto()),
                "pol_usuario_escrita e id = app_usuario_id(): ler nao autoriza escrever");
        }

        assertEquals(meu, telegramNoBanco(dona.id));
    }

    @Test
    @DisplayName("Sem token, PUT /api/perfil/telegram responde 401")
    void semTokenNaoEntra() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> r = http.exchange("/api/perfil/telegram", HttpMethod.PUT,
            new HttpEntity<>(Map.of("telegramId", "qualquer"), h), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, r.getStatusCode());
    }

    // =========================================================================
    // 5. A entrada como a pessoa realmente digita
    // =========================================================================

    @Test
    @DisplayName("So espacos responde 400 com o campo apontado — o @Pattern nao aceita espaco")
    void soEspacosRecusado() {
        Conta a = novaConta("tg-espacos");

        ResponseEntity<Map> r = put(a, "   ");

        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode(),
            "Comportamento REAL de hoje: '   ' nao limpa, e recusado");
        assertNotNull(((Map<String, String>) r.getBody().get("campos")).get("telegramId"));
    }

    /**
     * Valor legitimo colado com espaco em volta. O servico faz {@code trim()},
     * mas o {@code @Pattern} roda ANTES dele — o trim do Java nunca e alcancado
     * por esta porta. Hoje quem cola do Telegram recebe 400.
     */
    @Test
    @DisplayName("Valor valido com espaco em volta responde 400: o trim do servico nao e alcancado pelo HTTP")
    void valorColadoComEspacoRecusado() {
        Conta a = novaConta("tg-colado");

        ResponseEntity<Map> r = put(a, " abner_teste ");

        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode(),
            "Comportamento REAL de hoje — anotado, nao aprovado");
        assertNull(telegramNoBanco(a.id));
    }

    @Test
    @DisplayName("Corpo sem o campo telegramId LIMPA o valor gravado")
    void campoAusenteLimpa() {
        Conta a = novaConta("tg-ausente");
        assertEquals(HttpStatus.OK, put(a, "presente_" + curto()).getStatusCode());

        ResponseEntity<Map> r = http.exchange("/api/perfil/telegram", HttpMethod.PUT,
            new HttpEntity<>(Map.of(), cabecalhos(a.token)), Map.class);

        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertNull(r.getBody().get("telegramId"),
            "Comportamento REAL: corpo {} apaga o vinculo, sem o campo ter sido enviado");
        assertNull(telegramNoBanco(a.id));
    }

    @Test
    @DisplayName("Fronteira de tamanho: 63 entra, 65 nao; e '@' mais 63 fecha os 64")
    void fronteiraDeTamanho() {
        Conta a = novaConta("tg-tamanho");

        String c63 = "a".repeat(62) + "1";
        assertEquals(HttpStatus.OK, put(a, c63).getStatusCode(), "63 caracteres entram");

        String c64 = "b".repeat(63) + "2";
        assertEquals(HttpStatus.BAD_REQUEST, put(a, c64).getStatusCode(),
            "Comportamento REAL: 64 caracteres SEM @ sao recusados, apesar de @Size(max = 64) "
          + "e do maxLength=64 do campo da tela — o @Pattern para em 63");

        String comArroba = "@" + "c".repeat(63);
        assertEquals(64, comArroba.length());
        assertEquals(HttpStatus.OK, put(a, comArroba).getStatusCode(),
            "Com @ na frente, 64 caracteres entram — os dois limites nao coincidem");

        assertEquals(HttpStatus.BAD_REQUEST, put(a, "d".repeat(65)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, put(a, "ab").getStatusCode(),
            "Menos de 3 caracteres nao e nome nem id");
    }

    // =========================================================================
    // 6. O que o indice unico NAO pega
    // =========================================================================

    /**
     * O contrato promete que duas contas nao apontam para o mesmo destino, e o
     * unico guardiao disso e {@code ux_usuario_telegram}, que compara texto
     * literal. No Telegram, {@code @Fulano}, {@code fulano} e {@code FULANO}
     * sao a MESMA conta — e o indice ve tres valores diferentes.
     */
    @Test
    @DisplayName("Tres contas nao podem ficar com o mesmo Telegram escrito de tres jeitos")
    void aliasDeCaixaEArrobaNaoDriblaOConflito() {
        Conta a = novaConta("tg-alias-a");
        Conta b = novaConta("tg-alias-b");
        Conta c = novaConta("tg-alias-c");

        String base = "alias" + curto();
        assertEquals(HttpStatus.OK, put(a, base).getStatusCode());

        // Os dois disfarces sao medidos antes de qualquer assercao, para o
        // relatorio dizer quais passaram, e nao so o primeiro que falhou.
        HttpStatus caixaAlta = (HttpStatus) put(b, base.toUpperCase()).getStatusCode();
        HttpStatus comArroba = (HttpStatus) put(c, "@" + base).getStatusCode();

        assertEquals(
            HttpStatus.CONFLICT + " / " + HttpStatus.CONFLICT,
            caixaAlta + " / " + comArroba,
            "'" + base + "', '" + base.toUpperCase() + "' e '@" + base + "' sao a MESMA conta de "
          + "Telegram (o @ e enfeite, e o Telegram ignora caixa). ux_usuario_telegram compara "
          + "texto literal, entao tres contas do RaspyBank ficam apontando para o mesmo destino "
          + "— exatamente o que docs/api.md diz que o 409 impede. Status obtidos "
          + "(caixa alta / com @)");
    }

    /**
     * O "vazio vira NULL" e invariante de negocio e mora em DOIS lugares de
     * codigo (o {@code NULLIF} da V18 e o {@code trim/isEmpty} do
     * {@code UsuarioServico}) e em NENHUM lugar do banco. Este teste faz o que
     * qualquer caminho de escrita futuro — bot, importacao, correcao manual —
     * pode fazer sem querer, e mostra que nada o impede.
     */
    @Test
    @DisplayName("O banco recusa string vazia em telegram_id")
    void bancoRecusaTelegramVazio() throws SQLException {
        Conta a = novaConta("tg-vazio-a");
        Conta b = novaConta("tg-vazio-b");

        try (Connection c = comoApp()) {
            assumirIdentidade(c, a.id);
            try {
                atualizarTelegram(c, a.id, "");
                fail("O banco aceitou telegram_id = '' — nenhum CHECK impede, so o Java. "
                   + "A segunda conta a gravar '' colide com a primeira, que e exatamente "
                   + "o defeito que o indice parcial existe para evitar.");
            } catch (SQLException esperado) {
                assertTrue(String.valueOf(esperado.getMessage()).toLowerCase().contains("telegram"),
                    "A recusa tem de vir do banco, e nomear a coluna: " + esperado.getMessage());
            } finally {
                limparNoBanco(a.id);
                limparNoBanco(b.id);
            }
        }
    }

    // =========================================================================
    // Ferramentas
    // =========================================================================

    private record Conta(UUID id, String email, String token) {}

    private Conta novaConta(String prefixo) {
        String email = prefixo + "-" + curto() + "@teste.local";

        ResponseEntity<Map> cadastro = semToken("/api/auth/cadastro",
            Map.of("nome", "Telegram Teste", "email", email, "senha", SENHA));
        assertEquals(HttpStatus.CREATED, cadastro.getStatusCode());
        UUID id = UUID.fromString(String.valueOf(cadastro.getBody().get("usuarioId")));

        ResponseEntity<Map> login = semToken("/api/auth/login",
            Map.of("email", email, "senha", SENHA));
        assertEquals(HttpStatus.OK, login.getStatusCode());

        return new Conta(id, email, (String) login.getBody().get("tokenAcesso"));
    }

    private static String curto() {
        return UUID.randomUUID().toString().substring(0, 8).replace("-", "");
    }

    private static HttpHeaders cabecalhos(String bearer) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(bearer);
        return h;
    }

    private ResponseEntity<Map> semToken(String caminho, Map<String, ?> corpo) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(caminho, new HttpEntity<>(corpo, h), Map.class);
    }

    /** HashMap e nao Map.of porque o valor pode ser nulo. */
    private ResponseEntity<Map> put(Conta quem, String telegramId) {
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("telegramId", telegramId);
        return http.exchange("/api/perfil/telegram", HttpMethod.PUT,
            new HttpEntity<>(corpo, cabecalhos(quem.token)), Map.class);
    }

    private ResponseEntity<Map> get(Conta quem) {
        return http.exchange("/api/perfil", HttpMethod.GET,
            new HttpEntity<>(cabecalhos(quem.token)), Map.class);
    }

    private static String resumo(List<ResponseEntity<Map>> respostas) {
        StringBuilder sb = new StringBuilder();
        respostas.forEach(r -> sb.append(r.getStatusCode()).append(' ').append(r.getBody())
                                 .append(" | "));
        return sb.toString();
    }

    /** Dispara as tarefas no mesmo instante, nao em sequencia rapida. */
    @SafeVarargs
    private static List<ResponseEntity<Map>> emParalelo(Callable<ResponseEntity<Map>>... tarefas)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tarefas.length);
        CyclicBarrier largada = new CyclicBarrier(tarefas.length);
        try {
            List<Future<ResponseEntity<Map>>> futuros = new ArrayList<>();
            for (Callable<ResponseEntity<Map>> tarefa : tarefas) {
                futuros.add(pool.submit(() -> {
                    largada.await(10, TimeUnit.SECONDS);
                    return tarefa.call();
                }));
            }
            List<ResponseEntity<Map>> respostas = new ArrayList<>();
            for (Future<ResponseEntity<Map>> f : futuros) {
                respostas.add(f.get(30, TimeUnit.SECONDS));
            }
            return Collections.unmodifiableList(respostas);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ JDBC

    private static Connection comoApp() throws SQLException {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), PostgresDeTeste.USUARIO_APP, PostgresDeTeste.SENHA_APP);
    }

    private static Connection comoDono() throws SQLException {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void assumirIdentidade(Connection c, UUID usuarioId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config(?, ?, false)")) {
            ps.setString(1, "raspybank.usuario_id");
            ps.setString(2, usuarioId == null ? "" : usuarioId.toString());
            ps.execute();
        }
    }

    private static int atualizarTelegram(Connection c, UUID alvo, String valor)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE usuario SET telegram_id = ? WHERE id = ?")) {
            ps.setString(1, valor);
            ps.setObject(2, alvo);
            return ps.executeUpdate();
        }
    }

    /** Le sem passar por politica nenhuma: aqui interessa o que ESTA gravado. */
    private static String telegramNoBanco(UUID usuarioId) {
        try (Connection c = comoDono();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT telegram_id FROM usuario WHERE id = ?")) {
            ps.setObject(1, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "usuario " + usuarioId + " deveria existir");
                return rs.getString(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String lerTelegram(Connection c, UUID alvo) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT telegram_id FROM usuario WHERE id = ?")) {
            ps.setObject(1, alvo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Poe o convidado dentro do ambiente do dono, direto na tabela de vinculo. */
    private static void vincularAoAmbienteDe(UUID convidado, UUID dono) {
        try (Connection c = comoDono();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO usuario_ambiente (usuario_id, ambiente_id, dono) "
               + "SELECT ?, ua.ambiente_id, false FROM usuario_ambiente ua "
               + " WHERE ua.usuario_id = ? AND ua.dono")) {
            ps.setObject(1, convidado);
            ps.setObject(2, dono);
            assertEquals(1, ps.executeUpdate(), "o vinculo de teste tem de existir");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void limparNoBanco(UUID usuarioId) {
        try (Connection c = comoDono();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE usuario SET telegram_id = NULL WHERE id = ?")) {
            ps.setObject(1, usuarioId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
