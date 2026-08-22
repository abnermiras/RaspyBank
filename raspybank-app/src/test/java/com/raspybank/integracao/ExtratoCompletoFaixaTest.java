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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A borda do extrato: o teto de 12 meses, a entrada malformada e a ausencia de
 * sessao — §6c de {@code docs/api.md}.
 *
 * <h3>O modo de falha proprio de um endpoint binario</h3>
 *
 * <p>Depois do primeiro byte o status ja foi para o fio, e {@code 400} deixa de
 * ser possivel: o cliente receberia um {@code 200} com um {@code .xlsx}
 * truncado, que o navegador salva como se fosse bom. Por isso toda recusa aqui
 * e conferida <b>duas vezes</b> — o codigo, e o fato de o corpo ser JSON de
 * erro e nao bytes de planilha.</p>
 *
 * <h3>O ano bissexto</h3>
 *
 * <p>{@code 01/03/2023} a {@code 29/02/2024} sao <b>366</b> dias e doze meses
 * de calendario. Uma conta em dias recusa essa faixa e aceita a mesma faixa num
 * ano comum: a mesma pergunta, duas respostas, dependendo do calendario. E o
 * unico lugar onde o teto pode estar certo em 11 dos 12 meses do ano.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExtratoCompletoFaixaTest extends IntegracaoTest {

    @Autowired
    private TestRestTemplate http;

    private static final String SENHA = "senha-com-mais-de-10";

    private static final String TIPO_XLSX =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** Palavra por palavra, como {@code docs/api.md} §6c a publica. */
    private static final String FRASE_DO_TETO =
        "O período não pode passar de 12 meses. Ajuste a data inicial ou a final e tente de novo.";

    private static String token;

    // =========================================================================
    // O teto de 12 meses (B-D116)
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Doze meses exatos passam: o teto e inclusive")
    void dozeMesesExatosPassam() {
        autenticar();

        ResponseEntity<byte[]> r = pedir("2025-01-01", "2026-01-01");

        assertEquals(HttpStatus.OK, r.getStatusCode(),
            "Doze meses exatos foram recusados: " + corpo(r));
        assertNotNull(LeitoraXlsx.ler(r.getBody()));
    }

    @Test
    @Order(2)
    @DisplayName("Um dia alem dos doze meses e 400, e a frase diz o limite")
    void umDiaAlemDoTetoEhRecusado() {
        ResponseEntity<byte[]> r = pedir("2025-01-01", "2026-01-02");

        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode(),
            "Treze meses passaram. O teto e o que sustenta a entrega ser sincrona");
        assertEhJsonDeErro(r);

        Map<String, Object> erro = comoMapa(r);
        assertEquals(FRASE_DO_TETO, erro.get("erro"),
            "A recusa nao diz o limite: mandaria a pessoa tentar de novo as cegas");

        Map<String, Object> campos = (Map<String, Object>) erro.get("campos");
        assertNotNull(campos, "O 400 nao trouxe \"campos\" com o parametro culpado");
        assertEquals(List.of("inicio", "fim"), List.copyOf(campos.keySet()));
        assertEquals(FRASE_DO_TETO, campos.get("inicio"),
            "O campo traz um resumo, e o cliente prefere a mensagem de campos a erro:"
                + " o resumo apareceria na tela no lugar da frase boa");
    }

    @Test
    @Order(3)
    @DisplayName("Ano bissexto: 01/03/2023 a 29/02/2024 sao 366 dias, e PASSAM")
    void anoBissextoPassa() {
        // 366 dias. Uma conta de 365 recusa aqui e aceita a mesma faixa em ano
        // comum — a mesma pergunta com duas respostas.
        ResponseEntity<byte[]> r = pedir("2023-03-01", "2024-02-29");

        assertEquals(HttpStatus.OK, r.getStatusCode(),
            "Doze meses de calendario em ano bissexto foram recusados (" + corpo(r)
                + "). Sao 366 dias, e o teto e em MESES");
    }

    @Test
    @Order(4)
    @DisplayName("Bissexto na outra ponta: 29/02/2024 a 28/02/2025 tambem sao doze meses")
    void bissextoNaBordaDeCimaPassa() {
        // 29/02 + 12 meses = 28/02 do ano seguinte, que nao existe como 29.
        assertEquals(HttpStatus.OK, pedir("2024-02-29", "2025-02-28").getStatusCode(),
            "A faixa que comeca em 29/02 foi recusada — plusMonths resolve o dia que"
                + " nao existe no mes de destino, uma conta em dias nao");

        assertEquals(HttpStatus.BAD_REQUEST, pedir("2024-02-29", "2025-03-01").getStatusCode(),
            "Um dia alem do teto passou na faixa que comeca em 29/02");
    }

    @Test
    @Order(5)
    @DisplayName("A virada de ano nao muda o teto: 31/12 a 31/12 do ano seguinte passa")
    void viradaDeAnoPassa() {
        assertEquals(HttpStatus.OK, pedir("2025-12-31", "2026-12-31").getStatusCode(),
            "Doze meses atravessando a virada de ano foram recusados");
    }

    // =========================================================================
    // Entrada malformada
    // =========================================================================

    @Test
    @Order(6)
    @DisplayName("fim anterior a inicio e 400, nao um arquivo vazio")
    void fimAnteriorAoInicioEhRecusado() {
        ResponseEntity<byte[]> r = pedir("2026-06-30", "2026-06-01");

        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode(),
            "Faixa invertida gerou arquivo. Um .xlsx vazio parece dado nenhum no"
                + " periodo, e a pessoa conclui que nao gastou nada");
        assertEhJsonDeErro(r);
        assertEquals("A data final não pode ser anterior à inicial.", comoMapa(r).get("erro"));
    }

    @Test
    @Order(7)
    @DisplayName("Data que nao e data e 400 NOMEANDO o parametro — nao 500")
    void dataMalformadaEh400ComONomeDoParametro() {
        ResponseEntity<byte[]> r = pedir("abacaxi", "2026-06-01");

        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode(),
            "Erro de quem pediu respondido como erro nosso: e o defeito do I-12");
        assertEhJsonDeErro(r);

        Map<String, Object> erro = comoMapa(r);
        assertTrue(String.valueOf(erro.get("erro")).contains("inicio"),
            "A mensagem nao diz qual parametro esta errado: " + erro.get("erro"));
        assertNotNull(((Map<String, Object>) erro.get("campos")).get("inicio"));
    }

    @Test
    @Order(8)
    @DisplayName("A data no formato da tela (31/01/2026) e 400, e nao vira arquivo de outro periodo")
    void dataNoFormatoBrasileiroEhRecusada() {
        // A pessoa digita como le. O contrato e ISO, e recusar dizendo o formato
        // e o que separa isto de um arquivo silenciosamente errado.
        ResponseEntity<byte[]> r = pedir("31/01/2026", "2026-06-01");

        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode(),
            "A data em dd/MM/aaaa nao foi recusada");
        assertTrue(String.valueOf(comoMapa(r).get("erro")).contains("AAAA-MM-DD"),
            "A recusa nao ensina o formato: " + comoMapa(r).get("erro"));
    }

    @Test
    @Order(9)
    @DisplayName("Sem parametro nenhum, o padrao sao os ultimos doze meses — e nao um 400")
    void semParametroOPadraoSaoDozeMeses() {
        ResponseEntity<byte[]> r = http.exchange("/api/relatorios/extrato.xlsx",
            HttpMethod.GET, new HttpEntity<>(cabecalhos(token)), byte[].class);

        assertEquals(HttpStatus.OK, r.getStatusCode(), "Sem faixa: " + corpo(r));
        assertNotNull(LeitoraXlsx.ler(r.getBody()));
    }

    // =========================================================================
    // A resposta boa, e a sessao
    // =========================================================================

    @Test
    @Order(10)
    @DisplayName("A resposta boa vem como anexo .xlsx, com a faixa no nome do arquivo")
    void aRespostaBoaEhAnexoComAFaixaNoNome() {
        ResponseEntity<byte[]> r = pedir("2026-01-01", "2026-12-31");

        assertEquals(TIPO_XLSX, String.valueOf(r.getHeaders().getContentType()),
            "O Content-Type nao e o de planilha: o navegador abriria como texto");

        String anexo = r.getHeaders().getFirst("Content-Disposition");
        assertNotNull(anexo, "Sem Content-Disposition o arquivo nasce sem nome");
        assertTrue(anexo.contains("attachment"), anexo);
        assertTrue(anexo.contains("extrato-2026-01-01-a-2026-12-31.xlsx"),
            "O nome do arquivo nao diz a faixa, que e a unica coisa que sobrevive"
                + " ao download: " + anexo);
    }

    @Test
    @Order(11)
    @DisplayName("Sem token, 401 com JSON de erro — nunca um .xlsx vazio ou truncado")
    void semSessaoRespondeJsonDeErro() {
        ResponseEntity<byte[]> r = http.exchange(
            "/api/relatorios/extrato.xlsx?inicio=2026-01-01&fim=2026-06-30",
            HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), byte[].class);

        assertEquals(HttpStatus.UNAUTHORIZED, r.getStatusCode());

        assertFalse(TIPO_XLSX.equals(String.valueOf(r.getHeaders().getContentType())),
            "O 401 saiu com Content-Type de planilha: o navegador salvaria o erro"
                + " como .xlsx e a pessoa abriria um arquivo quebrado");

        assertEhJsonDeErro(r);
        assertNotNull(comoMapa(r).get("erro"),
            "O 401 nao traz {\"erro\": ...}. B-T1 diz que TODO erro tem essa forma,"
                + " e §6c promete que este 401 e \"JSON de erro, nunca um .xlsx vazio\"");
    }

    @Test
    @Order(12)
    @DisplayName("Token invalido tambem responde JSON de erro, e nao bytes de planilha")
    void tokenInvalidoNaoGeraArquivo() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth("nao.e.um.token");

        ResponseEntity<byte[]> r = http.exchange(
            "/api/relatorios/extrato.xlsx?inicio=2026-01-01&fim=2026-06-30",
            HttpMethod.GET, new HttpEntity<>(h), byte[].class);

        assertEquals(HttpStatus.UNAUTHORIZED, r.getStatusCode());
        assertEhJsonDeErro(r);
    }

    // =========================================================================
    // Ferramentas
    // =========================================================================

    private ResponseEntity<byte[]> pedir(String inicio, String fim) {
        return http.exchange("/api/relatorios/extrato.xlsx?inicio=" + inicio + "&fim=" + fim,
            HttpMethod.GET, new HttpEntity<>(cabecalhos(token)), byte[].class);
    }

    /**
     * O corpo nao pode ser planilha.
     *
     * <p>Os quatro primeiros bytes de todo {@code .xlsx} sao {@code PK\003\004}
     * — ele e um zip. Conferir isso e o que distingue "recusou" de "mandou meio
     * arquivo com status de erro".</p>
     */
    private static void assertEhJsonDeErro(ResponseEntity<byte[]> r) {
        byte[] corpo = r.getBody() == null ? new byte[0] : r.getBody();

        assertFalse(corpo.length >= 2 && corpo[0] == 'P' && corpo[1] == 'K',
            "O corpo do erro comeca com PK: veio um zip no lugar do JSON");

        assertTrue(corpo.length > 0,
            "O corpo do erro veio VAZIO. Contrato B-T1: todo erro e {\"erro\": ...},"
                + " e §6c promete JSON tambem aqui — corpo vazio nao da a quem"
                + " chamou nenhuma frase para mostrar");

        assertTrue(new String(corpo, StandardCharsets.UTF_8).trim().startsWith("{"),
            "O corpo do erro nao e JSON: " + corpo(r));
    }

    private static Map<String, Object> comoMapa(ResponseEntity<byte[]> r) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(r.getBody(), Map.class);
        } catch (Exception e) {
            throw new AssertionError("O corpo nao e JSON: " + corpo(r), e);
        }
    }

    private static String corpo(ResponseEntity<byte[]> r) {
        byte[] bytes = r.getBody();
        if (bytes == null || bytes.length == 0) {
            return "<corpo vazio>";
        }
        if (bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K') {
            return "<zip de " + bytes.length + " bytes>";
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void autenticar() {
        if (token != null) {
            return;
        }
        String email = "abner-faixa-" + UUID.randomUUID().toString().substring(0, 8)
            + "@teste.local";

        assertEquals(HttpStatus.CREATED, http.postForEntity("/api/auth/cadastro",
            new HttpEntity<>(Map.of("nome", "Abner", "email", email, "senha", SENHA), json()),
            Map.class).getStatusCode());

        token = String.valueOf(http.postForEntity("/api/auth/login",
            new HttpEntity<>(Map.of("email", email, "senha", SENHA), json()),
            Map.class).getBody().get("tokenAcesso"));
    }

    private static HttpHeaders json() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private static HttpHeaders cabecalhos(String bearer) {
        HttpHeaders h = json();
        h.setBearerAuth(bearer);
        return h;
    }
}
