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

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A API de lancamentos pela porta da frente — fatia 3, contrato em
 * {@code docs/api.md} §5.
 *
 * <p>E aqui que as tres chaves compostas da V10 finalmente entram em uso pela
 * porta da frente, e que as duas derivacoes (situacao pela data, tipo pela
 * categoria) se tornam visiveis para quem consome a API.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LancamentoApiTest extends IntegracaoTest {

    @Autowired
    private TestRestTemplate http;

    private static final String SENHA = "senha-com-mais-de-10";
    private static final LocalDate HOJE = LocalDate.now();
    private static final String MES = HOJE.toString().substring(0, 7);

    private static String email;
    private static String usuarioId;
    private static String ambienteId;
    private static String token;

    private static String contaId;
    private static String mercadoId;
    private static String feiraId;
    private static String salarioId;
    private static String ajusteId;
    private static String gastoId;
    private static String previstoId;
    private static String mesDoPrevisto;

    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Preparo: usuario, conta e categorias — tudo pela API")
    void preparar() {
        autenticar();

        contaId = criar("/api/contas",
            Map.of("nome", "Conta Corrente", "natureza", "ATIVO"));

        mercadoId = criar("/api/categorias", Map.of("nome", "Mercado", "tipo", "SAIDA"));
        salarioId = criar("/api/categorias", Map.of("nome", "Salario", "tipo", "ENTRADA"));
        feiraId = criar("/api/categorias/" + mercadoId + "/subcategorias",
            Map.of("nome", "Feira"));

        ajusteId = idDaSistemica("AJUSTE");
        assertNotNull(ajusteId);
    }

    @Test
    @Order(2)
    @DisplayName("POST sem tipo e sem situacao: os dois derivam (B-D9 / F12)")
    void osDoisCamposQueOFormularioNaoTem() {
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("contaId", contaId);
        corpo.put("categoriaId", mercadoId);
        corpo.put("valor", "380.00");
        corpo.put("dataCaixa", HOJE.toString());
        corpo.put("descricao", "Mercado do mes");

        ResponseEntity<Map> r = postAutenticado("/api/lancamentos", corpo);

        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        assertEquals("SAIDA", r.getBody().get("tipo"),
            "Escolher 'Mercado' ja diz que e saida — perguntar de novo seria pedir duas vezes");
        assertEquals("REALIZADO", r.getBody().get("situacao"),
            "Data de hoje nasce realizada: e o gasto que a pessoa mais digita");
        assertEquals(HOJE.toString(), r.getBody().get("dataCompetencia"),
            "dataCompetencia ausente copia dataCaixa (F14)");

        gastoId = String.valueOf(r.getBody().get("id"));
    }

    @Test
    @Order(3)
    @DisplayName("Categoria e subcategoria vem como objeto resolvido, nunca texto congelado (B-D4)")
    void classificacaoVemResolvida() {
        ResponseEntity<Map> r = put("/api/lancamentos/" + gastoId, corpoBase(m -> {
            m.put("subcategoriaId", feiraId);
            m.put("descricao", "Mercado do mes");
        }));

        assertEquals(HttpStatus.OK, r.getStatusCode());

        var categoria = (Map<String, Object>) r.getBody().get("categoria");
        var subcategoria = (Map<String, Object>) r.getBody().get("subcategoria");
        var conta = (Map<String, Object>) r.getBody().get("conta");

        assertEquals("Mercado", categoria.get("nome"));
        assertEquals("Feira", subcategoria.get("nome"));
        assertEquals("Conta Corrente", conta.get("nome"));
    }

    @Test
    @Order(4)
    @DisplayName("Renomear a categoria reescreve o rotulo do lancamento antigo (B-D3)")
    void renomearCategoriaAlcancaOPassado() {
        // A prova de que nao ha copia congelada: o lancamento guarda o id, e o
        // nome vem sempre da categoria atual.
        put("/api/categorias/" + mercadoId, Map.of("nome", "Mercado e feira", "tipo", "SAIDA"));

        var categoria = (Map<String, Object>) buscar(gastoId).get("categoria");
        assertEquals("Mercado e feira", categoria.get("nome"));
    }

    @Test
    @Order(5)
    @DisplayName("Data futura nasce PREVISTO, e reagendar para tras devolve a REALIZADO")
    void situacaoSegueADataNosDoisSentidos() {
        LocalDate futuro = HOJE.plusDays(10);

        Map<String, Object> corpo = new HashMap<>();
        corpo.put("contaId", contaId);
        corpo.put("categoriaId", mercadoId);
        corpo.put("valor", "120.00");
        corpo.put("dataCaixa", futuro.toString());
        corpo.put("descricao", "Compra agendada");

        ResponseEntity<Map> criado = postAutenticado("/api/lancamentos", corpo);
        assertEquals("PREVISTO", criado.getBody().get("situacao"));

        // Este fica PREVISTO ate o fim da classe: os testes 12 e 13 dependem de
        // existir um agendado para provar o filtro e a divergencia dos saldos.
        previstoId = String.valueOf(criado.getBody().get("id"));

        // O mes do previsto e guardado em vez de assumido: dez dias a frente
        // caem no mes seguinte sempre que a suite roda depois do dia 21, e
        // fixar "o mes de hoje" faria o teste 12 falhar um terco do ano.
        mesDoPrevisto = futuro.toString().substring(0, 7);

        // Um segundo, so para provar a volta.
        corpo.put("descricao", "Compra que foi antecipada");
        String outro = String.valueOf(
            postAutenticado("/api/lancamentos", corpo).getBody().get("id"));

        corpo.put("dataCaixa", HOJE.toString());
        ResponseEntity<Map> reagendado = put("/api/lancamentos/" + outro, corpo);
        assertEquals("REALIZADO", reagendado.getBody().get("situacao"));
    }

    @Test
    @Order(6)
    @DisplayName("PUT com situacao explicita vence a derivacao — e por isso que nao e gatilho")
    void correcaoExplicitaVenceADerivacao() {
        // O boleto agendado para amanha que ja foi debitado hoje existe. Regra
        // que o banco impoe e regra que o usuario nao consegue contrariar.
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("contaId", contaId);
        corpo.put("categoriaId", mercadoId);
        corpo.put("valor", "90.00");
        corpo.put("dataCaixa", HOJE.plusDays(1).toString());

        String id = String.valueOf(postAutenticado("/api/lancamentos", corpo).getBody().get("id"));

        corpo.put("situacao", "REALIZADO");
        ResponseEntity<Map> corrigido = put("/api/lancamentos/" + id, corpo);

        assertEquals("REALIZADO", corrigido.getBody().get("situacao"));
        assertEquals(HOJE.plusDays(1).toString(), corrigido.getBody().get("dataCaixa"),
            "A data continua no futuro; quem mudou foi so a situacao");
    }

    @Test
    @Order(7)
    @DisplayName("Categoria de ENTRADA nao aceita saida, e o 403 explica (F12)")
    void categoriaRecusaSentidoErrado() {
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("contaId", contaId);
        corpo.put("categoriaId", salarioId);
        corpo.put("tipo", "SAIDA");
        corpo.put("valor", "50.00");
        corpo.put("dataCaixa", HOJE.toString());

        ResponseEntity<Map> r = postAutenticado("/api/lancamentos", corpo);

        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
        assertTrue(String.valueOf(r.getBody().get("erro")).contains("ENTRADA"),
            "A mensagem diz qual e o tipo da categoria: " + r.getBody().get("erro"));
    }

    @Test
    @Order(8)
    @DisplayName("Categoria AMBOS exige tipo no corpo — ela realmente nao sabe o sentido")
    void categoriaAmbosExigeTipo() {
        Map<String, Object> semTipo = new HashMap<>();
        semTipo.put("contaId", contaId);
        semTipo.put("categoriaId", ajusteId);
        semTipo.put("valor", "200.00");
        semTipo.put("dataCaixa", HOJE.toString());

        ResponseEntity<Map> recusado = postAutenticado("/api/lancamentos", semTipo);
        assertEquals(HttpStatus.FORBIDDEN, recusado.getStatusCode());
        assertTrue(String.valueOf(recusado.getBody().get("erro")).contains("dois sentidos"));

        semTipo.put("tipo", "ENTRADA");
        ResponseEntity<Map> aceito = postAutenticado("/api/lancamentos", semTipo);
        assertEquals(HttpStatus.CREATED, aceito.getStatusCode());
        assertEquals("ENTRADA", aceito.getBody().get("tipo"));
    }

    @Test
    @Order(9)
    @DisplayName("Subcategoria de outra categoria e recusada (F11)")
    void subcategoriaPrecisaSerDaCategoria() {
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("contaId", contaId);
        corpo.put("categoriaId", salarioId);
        corpo.put("subcategoriaId", feiraId);  // Feira e de Mercado
        corpo.put("valor", "5000.00");
        corpo.put("dataCaixa", HOJE.toString());

        ResponseEntity<Map> r = postAutenticado("/api/lancamentos", corpo);
        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
    }

    @Test
    @Order(10)
    @DisplayName("Conta de outro ambiente responde 403 explicando o caminho (B-D2)")
    void contaForaDoAmbienteRecusada() throws Exception {
        String contaAlheia = criarContaSoltaComoOwner();

        Map<String, Object> corpo = new HashMap<>();
        corpo.put("contaId", contaAlheia);
        corpo.put("categoriaId", mercadoId);
        corpo.put("valor", "10.00");
        corpo.put("dataCaixa", HOJE.toString());

        ResponseEntity<Map> r = postAutenticado("/api/lancamentos", corpo);

        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
        assertTrue(String.valueOf(r.getBody().get("erro")).contains("ambiente"),
            "A chave composta recusaria, mas sem frase — o 403 e a traducao dela");
    }

    @Test
    @Order(11)
    @DisplayName("Valor negativo responde 400 antes de chegar ao banco (F1)")
    void valorPrecisaSerPositivo() {
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("contaId", contaId);
        corpo.put("categoriaId", mercadoId);
        corpo.put("valor", "-50.00");
        corpo.put("dataCaixa", HOJE.toString());

        ResponseEntity<Map> r = postAutenticado("/api/lancamentos", corpo);

        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        var campos = (Map<String, String>) r.getBody().get("campos");
        assertNotNull(campos.get("valor"), "O sinal vem do tipo, nunca do valor");
    }

    @Test
    @Order(12)
    @DisplayName("O extrato do mes filtra por conta, categoria e situacao")
    void extratoComFiltros() {
        var doMes = listar("?mes=" + MES);
        // Tres, e nao quatro: os lancamentos com data futura podem cair no mes
        // seguinte, dependendo do dia em que a suite roda.
        assertTrue(doMes.size() >= 3, "Os lancamentos de hoje deveriam estar no mes corrente");

        var soPrevistos = listar("?mes=" + mesDoPrevisto + "&situacao=PREVISTO");
        assertTrue(soPrevistos.stream().anyMatch(l -> previstoId.equals(String.valueOf(l.get("id")))),
            "O agendado do teste 5 deveria estar aqui");
        assertTrue(soPrevistos.stream().allMatch(l -> "PREVISTO".equals(l.get("situacao"))));

        var todosDoMesDoPrevisto = listar("?mes=" + mesDoPrevisto);
        assertTrue(soPrevistos.size() <= todosDoMesDoPrevisto.size(),
            "O filtro recorta, nunca acrescenta");

        var soSalario = listar("?mes=" + MES + "&categoriaId=" + salarioId);
        assertTrue(soSalario.isEmpty(), "Nenhum lancamento de salario foi criado com sucesso");

        var daConta = listar("?mes=" + MES + "&contaId=" + contaId);
        assertEquals(doMes.size(), daConta.size(), "Todos usam a mesma conta");

        var outroMes = listar("?mes=" + HOJE.minusYears(1).toString().substring(0, 7));
        assertTrue(outroMes.isEmpty());
    }

    @Test
    @Order(13)
    @DisplayName("O saldo da conta e a soma dos lancamentos — nunca uma coluna (P1)")
    void saldoRefleteOsLancamentos() {
        // A ligacao entre as fatias 2 e 3: nada foi gravado em conta, e mesmo
        // assim o saldo mudou. Ele e soma, sempre.
        ResponseEntity<Map> conta = http.exchange("/api/contas", HttpMethod.GET,
            new HttpEntity<>(cabecalhos(token)), Map.class);
        var contas = (List<Map<String, Object>>) conta.getBody().get("contas");

        var saldo = new java.math.BigDecimal(String.valueOf(contas.get(0).get("saldo")));
        var comPrevistos =
            new java.math.BigDecimal(String.valueOf(contas.get(0).get("saldoComPrevistos")));

        // A diferenca exata entre os dois numeros e o previsto do teste 5: uma
        // saida de 120,00 ainda nao realizada. E por isso que eles nao podem
        // ser um numero so (B-D26).
        assertEquals(0, saldo.subtract(comPrevistos).compareTo(new java.math.BigDecimal("120.00")),
            "saldo " + saldo + " menos saldoComPrevistos " + comPrevistos + " deveria dar 120.00");
    }

    @Test
    @Order(14)
    @DisplayName("DELETE exclui de verdade (F16) e a auditoria guarda o rastro")
    void exclusaoDeixaRastro() throws Exception {
        ResponseEntity<Void> apagado = http.exchange("/api/lancamentos/" + gastoId,
            HttpMethod.DELETE, new HttpEntity<>(cabecalhos(token)), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, apagado.getStatusCode());

        ResponseEntity<Map> sumiu = http.exchange("/api/lancamentos/" + gastoId,
            HttpMethod.GET, new HttpEntity<>(cabecalhos(token)), Map.class);
        assertEquals(HttpStatus.NOT_FOUND, sumiu.getStatusCode());

        // Lancamento e a UNICA entidade com exclusao fisica, e pode ser porque
        // nada aponta para ele. O rastro nao se perde: o gatilho gravou a linha
        // inteira antes de ela sumir, com autor e canal.
        Map<String, String> auditoria = ultimaAuditoriaDe(gastoId);
        assertEquals("EXCLUSAO", auditoria.get("operacao"));
        assertEquals("WEB", auditoria.get("canal"));
        assertEquals(usuarioId, auditoria.get("autor"),
            "O gatilho le o autor do contexto RLS (F26 / B-D6)");
    }

    @Test
    @Order(15)
    @DisplayName("Lancamento de outro ambiente responde 404; sem token, 401")
    void recorteEProtecao() throws Exception {
        String segundo = criarSegundoAmbienteComoOwner();
        String tokenNoSegundo = (String) postAutenticado("/api/sessao/ambiente",
            Map.of("ambienteId", segundo)).getBody().get("tokenAcesso");

        String algum = String.valueOf(listar("?mes=" + MES).get(0).get("id"));

        ResponseEntity<Map> outroAmbiente = http.exchange("/api/lancamentos/" + algum,
            HttpMethod.GET, new HttpEntity<>(cabecalhos(tokenNoSegundo)), Map.class);
        assertEquals(HttpStatus.NOT_FOUND, outroAmbiente.getStatusCode());

        ResponseEntity<String> semToken =
            http.getForEntity("/api/lancamentos?mes=" + MES, String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, semToken.getStatusCode());
    }

    // =========================================================================
    // Ajudantes
    // =========================================================================

    private void autenticar() {
        email = "lancamento-" + UUID.randomUUID().toString().substring(0, 8) + "@teste.local";

        ResponseEntity<Map> cadastro = postJson("/api/auth/cadastro",
            Map.of("nome", "Lancamento Teste", "email", email, "senha", SENHA));
        assertEquals(HttpStatus.CREATED, cadastro.getStatusCode());
        usuarioId = String.valueOf(cadastro.getBody().get("usuarioId"));

        ResponseEntity<Map> login = postJson("/api/auth/login",
            Map.of("email", email, "senha", SENHA));
        token = (String) login.getBody().get("tokenAcesso");
        ambienteId = String.valueOf(login.getBody().get("ambienteId"));
    }

    private String criar(String caminho, Map<String, String> corpo) {
        ResponseEntity<Map> r = postAutenticado(caminho, corpo);
        assertEquals(HttpStatus.CREATED, r.getStatusCode(), "Falhou ao criar em " + caminho);
        return String.valueOf(r.getBody().get("id"));
    }

    @SuppressWarnings("unchecked")
    private String idDaSistemica(String codigo) {
        ResponseEntity<Map> r = http.exchange("/api/categorias", HttpMethod.GET,
            new HttpEntity<>(cabecalhos(token)), Map.class);
        var lista = (List<Map<String, Object>>) r.getBody().get("categorias");
        return lista.stream()
            .filter(c -> codigo.equals(c.get("codigo")))
            .map(c -> String.valueOf(c.get("id")))
            .findFirst()
            .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listar(String query) {
        ResponseEntity<Map> r = http.exchange("/api/lancamentos" + query, HttpMethod.GET,
            new HttpEntity<>(cabecalhos(token)), Map.class);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        return (List<Map<String, Object>>) r.getBody().get("lancamentos");
    }

    private Map<String, Object> buscar(String id) {
        ResponseEntity<Map> r = http.exchange("/api/lancamentos/" + id, HttpMethod.GET,
            new HttpEntity<>(cabecalhos(token)), Map.class);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        return r.getBody();
    }

    /** O corpo padrao do gasto, para os testes que so mudam um campo. */
    private Map<String, Object> corpoBase(java.util.function.Consumer<Map<String, Object>> ajuste) {
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("contaId", contaId);
        corpo.put("categoriaId", mercadoId);
        corpo.put("valor", "380.00");
        corpo.put("dataCaixa", HOJE.toString());
        ajuste.accept(corpo);
        return corpo;
    }

    /** Conta criada fora do ambiente do usuario — para provar a guarda de B-D2. */
    private String criarContaSoltaComoOwner() throws Exception {
        try (java.sql.Connection c = conexaoOwner();
             java.sql.Statement s = c.createStatement()) {

            var rs = s.executeQuery("INSERT INTO conta (nome, natureza) "
                + "VALUES ('Conta de Terceiro', 'ATIVO') RETURNING id");
            rs.next();
            return rs.getString(1);
        }
    }

    private String criarSegundoAmbienteComoOwner() throws Exception {
        try (java.sql.Connection c = conexaoOwner();
             java.sql.Statement s = c.createStatement()) {

            var rs = s.executeQuery("INSERT INTO ambiente (nome) "
                + "VALUES ('Ambiente Secundario Lancamentos') RETURNING id");
            rs.next();
            String novo = rs.getString(1);

            s.executeUpdate("INSERT INTO usuario_ambiente (usuario_id, ambiente_id) "
                + "VALUES ('" + usuarioId + "', '" + novo + "')");
            return novo;
        }
    }

    private Map<String, String> ultimaAuditoriaDe(String lancamentoId) throws Exception {
        try (java.sql.Connection c = conexaoOwner();
             java.sql.Statement s = c.createStatement()) {

            var rs = s.executeQuery(
                "SELECT operacao, canal, usuario_id FROM registro_auditoria "
                    + " WHERE entidade = 'Lancamento' AND entidade_id = '" + lancamentoId + "'"
                    + " ORDER BY ocorrido_em DESC LIMIT 1");

            assertTrue(rs.next(), "A exclusao deveria ter deixado registro de auditoria");
            Map<String, String> m = new HashMap<>();
            m.put("operacao", rs.getString(1));
            m.put("canal", rs.getString(2));
            m.put("autor", rs.getString(3));
            return m;
        }
    }

    private java.sql.Connection conexaoOwner() throws java.sql.SQLException {
        return java.sql.DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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

    private <T> ResponseEntity<Map> postAutenticado(String caminho, Map<String, T> corpo) {
        return http.postForEntity(caminho, new HttpEntity<>(corpo, cabecalhos(token)), Map.class);
    }

    private <T> ResponseEntity<Map> put(String caminho, Map<String, T> corpo) {
        return http.exchange(caminho, HttpMethod.PUT,
            new HttpEntity<>(corpo, cabecalhos(token)), Map.class);
    }
}
