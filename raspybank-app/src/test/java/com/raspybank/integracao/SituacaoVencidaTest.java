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
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * O previsto que venceu vira realizado sozinho.
 *
 * <p>O defeito que isto guarda: {@code derivarDe} (B-D9) so rodava na criacao e
 * na edicao. A conta de luz lancada para 05/08 nascia PREVISTO corretamente, e
 * em 06/08 continuava PREVISTO — o saldo realizado ignorava aquele valor para
 * sempre, e nada denunciava.</p>
 *
 * <p>A virada e de MAO UNICA, e o teste da ida e volta prova por que: se ela
 * tambem desfizesse correcoes manuais, brigaria para sempre com
 * {@code corrigirSituacao}.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SituacaoVencidaTest extends IntegracaoTest {

    @Autowired
    private TestRestTemplate http;

    private static final String SENHA = "senha-com-mais-de-10";

    private static String token;
    private static String contaId;
    private static String categoriaId;
    private static String vencidoId;

    @Test
    @Order(1)
    @DisplayName("Um previsto com data JA PASSADA vira realizado ao abrir o extrato")
    void previstoVencidoViraRealizado() {
        autenticar();

        contaId = criar("/api/contas", Map.of("nome", "Corrente", "natureza", "ATIVO"));
        categoriaId = criar("/api/categorias", Map.of("nome", "Casa", "tipo", "SAIDA"));

        // Nasce no futuro, entao nasce PREVISTO pela regra normal de B-D9.
        LocalDate amanha = LocalDate.now().plusDays(1);
        ResponseEntity<Map> criado = post("/api/lancamentos", Map.of(
            "contaId", contaId,
            "categoriaId", categoriaId,
            "valor", "250.00",
            "dataCaixa", amanha.toString(),
            "descricao", "Luz"));

        assertEquals(HttpStatus.CREATED, criado.getStatusCode());
        assertEquals("PREVISTO", criado.getBody().get("situacao"));
        vencidoId = String.valueOf(criado.getBody().get("id"));

        // Agora o tempo "passa": reagenda para ontem. Sem a virada, ele
        // continuaria PREVISTO — que era exatamente o defeito.
        Map<String, Object> corpo = new HashMap<>(Map.of(
            "contaId", contaId,
            "categoriaId", categoriaId,
            "valor", "250.00",
            "dataCaixa", LocalDate.now().minusDays(1).toString(),
            "descricao", "Luz"));
        corpo.put("situacao", "PREVISTO");   // fixa contra a derivacao, de proposito

        assertEquals(HttpStatus.OK, put("/api/lancamentos/" + vencidoId, corpo).getStatusCode());

        // A leitura do extrato e o gatilho da virada.
        assertEquals("REALIZADO", doExtrato(vencidoId).get("situacao"),
            "Data de caixa no passado com situacao PREVISTO e um estado que o"
                + " calendario ja desmentiu");
    }

    @Test
    @Order(2)
    @DisplayName("E o saldo REALIZADO passa a contar o valor virado")
    void oSaldoPassaAContar() {
        // A consequencia que importa: o numero que a pessoa confere contra o
        // extrato do banco. Antes da virada este saldo seria 0,00.
        assertEquals("-250.00", saldoDe(contaId));
    }

    @Test
    @Order(3)
    @DisplayName("Previsto FUTURO nao e tocado — a virada so olha o que venceu")
    void previstoFuturoFicaEmPaz() {
        ResponseEntity<Map> r = post("/api/lancamentos", Map.of(
            "contaId", contaId,
            "categoriaId", categoriaId,
            "valor", "80.00",
            "dataCaixa", LocalDate.now().plusDays(10).toString(),
            "descricao", "Internet do mes que vem"));

        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        String futuroId = String.valueOf(r.getBody().get("id"));

        assertEquals("PREVISTO", doExtrato(futuroId, LocalDate.now().plusDays(10)).get("situacao"));
        assertEquals("-250.00", saldoDe(contaId),
            "Previsto e agenda, nao dinheiro: nao entra no saldo realizado");
    }

    @Test
    @Order(4)
    @DisplayName("Nao paguei? A correcao e mudar a DATA, e ai ele volta a previsto")
    void naoPagueiEntaoReagendo() {
        // Este teste existe para documentar o caminho certo. Marcar de volta
        // como PREVISTO brigaria com a virada para sempre; reagendar para frente
        // resolve pela regra normal de B-D9, sem briga nenhuma.
        ResponseEntity<Map> r = put("/api/lancamentos/" + vencidoId, Map.of(
            "contaId", contaId,
            "categoriaId", categoriaId,
            "valor", "250.00",
            "dataCaixa", LocalDate.now().plusDays(7).toString(),
            "descricao", "Luz — reagendada, nao paguei no vencimento"));

        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals("PREVISTO", doExtrato(vencidoId, LocalDate.now().plusDays(7)).get("situacao"));
        assertEquals("0.00", saldoDe(contaId), "Saiu do saldo realizado junto");
    }

    // =========================================================================

    private void autenticar() {
        String email = "vencido-" + UUID.randomUUID().toString().substring(0, 8) + "@teste.local";
        assertEquals(HttpStatus.CREATED, postSemToken("/api/auth/cadastro",
            Map.of("nome", "Vencido Teste", "email", email, "senha", SENHA)).getStatusCode());
        token = (String) postSemToken("/api/auth/login",
            Map.of("email", email, "senha", SENHA)).getBody().get("tokenAcesso");
    }

    private String criar(String caminho, Map<String, ?> corpo) {
        ResponseEntity<Map> r = post(caminho, corpo);
        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        return String.valueOf(r.getBody().get("id"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> doExtrato(String id) {
        return doExtrato(id, LocalDate.now());
    }

    /**
     * O extrato do mes DAQUELA data, e nao o de hoje.
     *
     * <p>A primeira versao usava sempre {@code YearMonth.now()} e quebrou: um
     * lancamento para daqui a dez dias cai no mes que vem quando hoje e dia 28.
     * E a mesma armadilha ja registrada no projeto — data relativa a hoje em
     * teste de API atravessa a virada de mes.</p>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> doExtrato(String id, LocalDate quando) {
        ResponseEntity<Map> r = http.exchange("/api/lancamentos?mes=" + YearMonth.from(quando),
            HttpMethod.GET, new HttpEntity<>(cabecalhos()), Map.class);
        assertEquals(HttpStatus.OK, r.getStatusCode());

        Map<String, Object> achado =
            ((List<Map<String, Object>>) r.getBody().get("lancamentos")).stream()
                .filter(l -> id.equals(String.valueOf(l.get("id"))))
                .findFirst()
                .orElse(null);

        assertNotNull(achado, "Lancamento " + id + " deveria estar no extrato do mes");
        return achado;
    }

    @SuppressWarnings("unchecked")
    private String saldoDe(String id) {
        ResponseEntity<Map> r = http.exchange("/api/contas", HttpMethod.GET,
            new HttpEntity<>(cabecalhos()), Map.class);
        return ((List<Map<String, Object>>) r.getBody().get("contas")).stream()
            .filter(c -> id.equals(String.valueOf(c.get("id"))))
            .map(c -> String.valueOf(c.get("saldo")))
            .findFirst()
            .orElseThrow();
    }

    private HttpHeaders cabecalhos() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        return h;
    }

    private ResponseEntity<Map> postSemToken(String caminho, Map<String, ?> corpo) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(caminho, new HttpEntity<>(corpo, h), Map.class);
    }

    private ResponseEntity<Map> post(String caminho, Map<String, ?> corpo) {
        return http.postForEntity(caminho, new HttpEntity<>(corpo, cabecalhos()), Map.class);
    }

    private ResponseEntity<Map> put(String caminho, Map<String, ?> corpo) {
        return http.exchange(caminho, HttpMethod.PUT,
            new HttpEntity<>(corpo, cabecalhos()), Map.class);
    }
}
