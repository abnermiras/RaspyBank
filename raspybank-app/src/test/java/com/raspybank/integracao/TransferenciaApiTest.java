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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Transferencia entre contas proprias — os dois lancamentos ligados de F2.
 *
 * <p>F2 prometia "dois lancamentos ligados" e F16 prometia "transferencia
 * propaga para o par" desde o modelo logico. Ate a V11 nenhuma migracao tinha
 * criado a coluna que expressa o vinculo: a promessa estava no documento e nao
 * no schema.</p>
 *
 * <h3>O que este teste guarda</h3>
 *
 * <p>Que dinheiro nao aparece nem some. Toda transferencia deixa o
 * <b>patrimonio total inalterado</b>, e nenhuma operacao — excluir, editar,
 * reclassificar — consegue quebrar isso deixando meia transferencia para tras.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TransferenciaApiTest extends IntegracaoTest {

    @Autowired
    private TestRestTemplate http;

    private static final String SENHA = "senha-com-mais-de-10";

    private static String token;

    private static String nubankId;
    private static String carteiraId;
    private static String itauId;

    private static String pernaSaidaId;
    private static String pernaEntradaId;

    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Transferir cria DUAS pernas, amarradas uma na outra")
    void transferirCriaOPar() {
        autenticar();

        nubankId = criarConta("Nubank", "1000.00");
        carteiraId = criarConta("Carteira", null);
        itauId = criarConta("Itau", "500.00");

        ResponseEntity<Map> r = post("/api/transferencias", Map.of(
            "contaOrigemId", nubankId,
            "contaDestinoId", itauId,
            "valor", "10.00",
            "dataCaixa", LocalDate.now().toString()));

        assertEquals(HttpStatus.CREATED, r.getStatusCode());

        Map<String, Object> saida = (Map<String, Object>) r.getBody().get("saida");
        Map<String, Object> entrada = (Map<String, Object>) r.getBody().get("entrada");

        pernaSaidaId = String.valueOf(saida.get("id"));
        pernaEntradaId = String.valueOf(entrada.get("id"));

        assertNotEquals(pernaSaidaId, pernaEntradaId);
        assertEquals("10.00", saida.get("valor"));
        assertEquals("10.00", entrada.get("valor"));

        // O vinculo e MUTUO. Um ponteiro so criaria uma perna "principal", e o
        // dominio nao tem isso.
        assertEquals(pernaEntradaId, String.valueOf(saida.get("lancamentoParId")));
        assertEquals(pernaSaidaId, String.valueOf(entrada.get("lancamentoParId")));
    }

    @Test
    @Order(2)
    @DisplayName("O dinheiro saiu de uma e entrou na outra; o patrimonio nao mudou")
    void oDinheiroSoTrocouDeLugar() {
        assertEquals("990.00", saldoDe(nubankId));
        assertEquals("510.00", saldoDe(itauId));

        // 1000 + 500 antes, 990 + 510 depois. E o ponto inteiro da operacao.
        assertEquals("1500.00", patrimonioDe(nubankId, itauId));
    }

    @Test
    @Order(3)
    @DisplayName("As duas pernas nascem na categoria sistemica Transferencia, sem forma de pagamento")
    void asPernasNascemSistemicasESemForma() {
        Map<String, Object> saida = lancamentoPorId(pernaSaidaId);
        Map<String, Object> entrada = lancamentoPorId(pernaEntradaId);

        assertEquals("Transferência", nomeDaCategoria(saida));
        assertEquals("Transferência", nomeDaCategoria(entrada));
        assertEquals("SAIDA", saida.get("tipo"));
        assertEquals("ENTRADA", entrada.get("tipo"));

        // Sem nenhum caso especial no codigo: categoria sistemica nao recebe
        // forma padrao, e transferencia e sistemica. O dinheiro nao se moveu por
        // pix nem boleto — so trocou de lugar.
        assertNull(saida.get("formaPagamento"));
        assertNull(entrada.get("formaPagamento"));
    }

    @Test
    @Order(4)
    @DisplayName("Sem descricao, cada perna ganha o nome da OUTRA conta")
    void descricaoPadraoDizOOutroLado() {
        assertEquals("Transferencia para Itau", lancamentoPorId(pernaSaidaId).get("descricao"));
        assertEquals("Transferencia de Nubank", lancamentoPorId(pernaEntradaId).get("descricao"));
    }

    // =========================================================================
    // Saque e uma transferencia, e nada mais
    // =========================================================================

    @Test
    @Order(5)
    @DisplayName("Saque e transferir para a carteira — sem categoria nem forma propria")
    void saqueEUmaTransferencia() {
        // Nao existe categoria SAQUE nem forma de pagamento SAQUE. Um segundo
        // nome para o mesmo evento obrigaria todo relatorio futuro a conhecer os
        // dois, e esquecer um viraria numero errado em silencio.
        ResponseEntity<Map> r = post("/api/transferencias", Map.of(
            "contaOrigemId", nubankId,
            "contaDestinoId", carteiraId,
            "valor", "100.00",
            "dataCaixa", LocalDate.now().toString(),
            "descricao", "Saque no caixa"));

        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        assertEquals("890.00", saldoDe(nubankId));
        assertEquals("100.00", saldoDe(carteiraId));
    }

    // =========================================================================
    // As recusas
    // =========================================================================

    @Test
    @Order(6)
    @DisplayName("Origem igual a destino e recusada: nao move dinheiro nenhum")
    void origemIgualDestinoRecusada() {
        ResponseEntity<Map> r = post("/api/transferencias", Map.of(
            "contaOrigemId", nubankId,
            "contaDestinoId", nubankId,
            "valor", "10.00",
            "dataCaixa", LocalDate.now().toString()));

        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
    }

    @Test
    @Order(7)
    @DisplayName("Conta de outro ambiente responde 404, dizendo qual lado (B-D25)")
    void contaInexistenteResponde404() {
        ResponseEntity<Map> r = post("/api/transferencias", Map.of(
            "contaOrigemId", nubankId,
            "contaDestinoId", UUID.randomUUID().toString(),
            "valor", "10.00",
            "dataCaixa", LocalDate.now().toString()));

        assertEquals(HttpStatus.NOT_FOUND, r.getStatusCode());
        assertTrue(String.valueOf(r.getBody().get("erro")).contains("destino"),
            "Numa operacao com duas contas, saber qual falhou e metade do conserto");
    }

    @Test
    @Order(8)
    @DisplayName("Conta encerrada e recusada antes de o banco reclamar")
    void contaEncerradaRecusada() {
        String zerada = criarConta("Conta Para Encerrar", null);
        assertEquals(HttpStatus.OK,
            post("/api/contas/" + zerada + "/encerrar", Map.of()).getStatusCode());

        ResponseEntity<Map> r = post("/api/transferencias", Map.of(
            "contaOrigemId", nubankId,
            "contaDestinoId", zerada,
            "valor", "10.00",
            "dataCaixa", LocalDate.now().toString()));

        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
        assertTrue(String.valueOf(r.getBody().get("erro")).toLowerCase().contains("encerrada"));
    }

    @Test
    @Order(9)
    @DisplayName("Valor com casas demais responde 400 antes de criar meia transferencia")
    void valorInvalidoResponde400() {
        ResponseEntity<Map> r = post("/api/transferencias", Map.of(
            "contaOrigemId", nubankId,
            "contaDestinoId", itauId,
            "valor", "10.005",
            "dataCaixa", LocalDate.now().toString()));

        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertNotNull(((Map<String, String>) r.getBody().get("campos")).get("valor"));
    }

    // =========================================================================
    // F16 — as duas metades da propagacao
    // =========================================================================

    @Test
    @Order(10)
    @DisplayName("Editar o valor de uma perna muda a OUTRA junto (F16)")
    void editarPropagaParaOPar() {
        // Se um lado virasse 50 e o outro continuasse 10, quarenta reais
        // apareceriam do nada no patrimonio — em silencio, porque nenhum saldo
        // isolado pareceria errado.
        ResponseEntity<Map> r = put("/api/lancamentos/" + pernaSaidaId, Map.of(
            "contaId", nubankId,
            "categoriaId", categoriaDe(lancamentoPorId(pernaSaidaId)),
            "valor", "50.00",
            "dataCaixa", LocalDate.now().toString(),
            "descricao", "Transferencia corrigida"));

        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals("50.00", r.getBody().get("valor"));
        assertEquals("50.00", lancamentoPorId(pernaEntradaId).get("valor"),
            "A outra perna precisa ter mudado junto");

        // 1000 - 100 (saque) - 50, e 500 + 50.
        assertEquals("850.00", saldoDe(nubankId));
        assertEquals("550.00", saldoDe(itauId));
        assertEquals("1500.00", patrimonioDe(nubankId, itauId, carteiraId));
    }

    @Test
    @Order(11)
    @DisplayName("Uma perna de transferencia NAO muda de categoria")
    void pernaNaoTrocaDeCategoria() {
        // Sair de TRANSFERENCIA deixaria o par com classificacoes diferentes em
        // cada lado, e o mapa de gastos contaria como despesa metade de um
        // movimento que nao e gasto (B-D15).
        String mercado = criarCategoria("Mercado", "SAIDA");

        ResponseEntity<Map> r = put("/api/lancamentos/" + pernaSaidaId, Map.of(
            "contaId", nubankId,
            "categoriaId", mercado,
            "valor", "50.00",
            "dataCaixa", LocalDate.now().toString()));

        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
        assertTrue(String.valueOf(r.getBody().get("erro")).toLowerCase().contains("transferencia"));
    }

    @Test
    @Order(12)
    @DisplayName("Excluir uma perna exclui a outra — cumprido pelo BANCO, nao por codigo")
    void excluirUmaPernaExcluiAsDuas() {
        // ON DELETE CASCADE em fk_lancamento_par. Regra de integridade cumprida
        // pelo banco nao tem como ser esquecida por um caminho de codigo novo —
        // e este e o caso em que esquecer faz dinheiro aparecer do nada.
        assertEquals(HttpStatus.NO_CONTENT, http.exchange(
            "/api/lancamentos/" + pernaSaidaId, HttpMethod.DELETE,
            new HttpEntity<>(cabecalhos()), Void.class).getStatusCode());

        assertNull(lancamentoPorId(pernaSaidaId), "A perna excluida sumiu");
        assertNull(lancamentoPorId(pernaEntradaId), "E a outra foi junto");

        // Os saldos voltaram ao que eram antes daquela transferencia.
        assertEquals("900.00", saldoDe(nubankId));
        assertEquals("500.00", saldoDe(itauId));
        assertEquals("1500.00", patrimonioDe(nubankId, itauId, carteiraId));
    }

    @Test
    @Order(13)
    @DisplayName("Lancamento avulso na categoria Transferencia e recusado: ela so nasce em par")
    void naoSeLancaTransferenciaAvulsa() {
        // A T-08 tirou Transferencia do seletor de categoria, mas a tela nao e
        // a cerca: o bot do Telegram e um curl chegam por fora. Um lancamento
        // avulso aqui seria meia transferencia — dinheiro saindo de uma conta
        // sem entrar em nenhuma, com o par nulo, e nenhum saldo isolado
        // parecendo errado.
        String transferenciaId = idDaCategoriaSistemica("TRANSFERENCIA");

        ResponseEntity<Map> r = post("/api/lancamentos", Map.of(
            "contaId", nubankId,
            "categoriaId", transferenciaId,
            "tipo", "SAIDA",
            "valor", "10.00",
            "dataCaixa", LocalDate.now().toString()));

        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
        assertTrue(String.valueOf(r.getBody().get("erro")).contains("/api/transferencias"),
            "A recusa diz o caminho certo: " + r.getBody().get("erro"));
    }

    @Test
    @Order(14)
    @DisplayName("Mas AJUSTE continua lancavel: encerrar conta com saldo manda ajustar")
    void ajusteContinuaLancavel() {
        // A guarda e so de TRANSFERENCIA. "Ajuste de saldo" e caminho legitimo —
        // a mensagem de encerrar conta com saldo aponta para ele — e "Nao
        // classificado" e o destino do bot do Telegram.
        ResponseEntity<Map> r = post("/api/lancamentos", Map.of(
            "contaId", itauId,
            "categoriaId", idDaCategoriaSistemica("AJUSTE"),
            "tipo", "ENTRADA",
            "valor", "1.00",
            "dataCaixa", LocalDate.now().toString(),
            "descricao", "Ajuste de centavos"));

        assertEquals(HttpStatus.CREATED, r.getStatusCode());
    }

    @Test
    @Order(15)
    @DisplayName("Transferencia nao entra no mapa de gastos (B-D15)")
    void transferenciaNaoInflaOMapa() {
        // O saque de 100 reais e uma saida da conta corrente. Se ele contasse
        // como gasto, o mapa mostraria uma despesa que nunca existiu.
        ResponseEntity<Map> r = get("/api/relatorios/mapa-de-gastos?ano=" + LocalDate.now().getYear());
        assertEquals(HttpStatus.OK, r.getStatusCode());

        assertTrue(r.getBody().toString().indexOf("Transferência") < 0,
            "A categoria Transferencia nasce com entraNoMapa = false");
    }

    @Test
    @Order(16)
    @DisplayName("Sem token, transferir responde 401")
    void semTokenNaoEntra() {
        assertEquals(HttpStatus.UNAUTHORIZED, http.postForEntity(
            "/api/transferencias", new HttpEntity<>(Map.of()), String.class).getStatusCode());
    }

    // =========================================================================
    // Ajudantes
    // =========================================================================

    private void autenticar() {
        String email = "transf-" + UUID.randomUUID().toString().substring(0, 8) + "@teste.local";

        ResponseEntity<Map> cadastro = postSemToken("/api/auth/cadastro",
            Map.of("nome", "Transferencia Teste", "email", email, "senha", SENHA));
        assertEquals(HttpStatus.CREATED, cadastro.getStatusCode());

        ResponseEntity<Map> login = postSemToken("/api/auth/login",
            Map.of("email", email, "senha", SENHA));
        token = (String) login.getBody().get("tokenAcesso");
    }

    private String criarConta(String nome, String saldoInicial) {
        Map<String, Object> corpo = new java.util.HashMap<>();
        corpo.put("nome", nome);
        corpo.put("natureza", "ATIVO");
        if (saldoInicial != null) {
            corpo.put("saldoInicial", saldoInicial);
        }
        ResponseEntity<Map> r = post("/api/contas", corpo);
        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        return String.valueOf(r.getBody().get("id"));
    }

    @SuppressWarnings("unchecked")
    private String idDaCategoriaSistemica(String codigo) {
        ResponseEntity<Map> r = get("/api/categorias");
        return ((List<Map<String, Object>>) r.getBody().get("categorias")).stream()
            .filter(c -> codigo.equals(c.get("codigo")))
            .map(c -> String.valueOf(c.get("id")))
            .findFirst()
            .orElseThrow();
    }

    private String criarCategoria(String nome, String tipo) {
        ResponseEntity<Map> r = post("/api/categorias", Map.of("nome", nome, "tipo", tipo));
        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        return String.valueOf(r.getBody().get("id"));
    }

    @SuppressWarnings("unchecked")
    private String saldoDe(String contaId) {
        ResponseEntity<Map> r = get("/api/contas");
        return ((List<Map<String, Object>>) r.getBody().get("contas")).stream()
            .filter(c -> contaId.equals(String.valueOf(c.get("id"))))
            .map(c -> String.valueOf(c.get("saldo")))
            .findFirst()
            .orElseThrow();
    }

    /** A soma dos saldos, em centavos inteiros — o numero que nunca pode mudar. */
    @SuppressWarnings("unchecked")
    private String patrimonioDe(String... contaIds) {
        ResponseEntity<Map> r = get("/api/contas");
        List<String> ids = List.of(contaIds);

        long centavos = ((List<Map<String, Object>>) r.getBody().get("contas")).stream()
            .filter(c -> ids.contains(String.valueOf(c.get("id"))))
            .mapToLong(c -> new java.math.BigDecimal(String.valueOf(c.get("saldo")))
                .movePointRight(2).longValueExact())
            .sum();

        return java.math.BigDecimal.valueOf(centavos).movePointLeft(2).toPlainString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> lancamentoPorId(String id) {
        ResponseEntity<Map> r = get("/api/lancamentos?mes=" + YearMonth.now());
        assertEquals(HttpStatus.OK, r.getStatusCode());

        return ((List<Map<String, Object>>) r.getBody().get("lancamentos")).stream()
            .filter(l -> id.equals(String.valueOf(l.get("id"))))
            .findFirst()
            .orElse(null);
    }

    private static String nomeDaCategoria(Map<String, Object> lancamento) {
        return String.valueOf(((Map<?, ?>) lancamento.get("categoria")).get("nome"));
    }

    private static String categoriaDe(Map<String, Object> lancamento) {
        return String.valueOf(((Map<?, ?>) lancamento.get("categoria")).get("id"));
    }

    private HttpHeaders cabecalhos() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        return h;
    }

    private ResponseEntity<Map> get(String caminho) {
        return http.exchange(caminho, HttpMethod.GET, new HttpEntity<>(cabecalhos()), Map.class);
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
