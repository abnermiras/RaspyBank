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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cartao dividido POR PLASTICO (V19) — §4n, B-D106 a B-D110.
 *
 * <p>Este teste substituiu a versao da V17, que guardava um modelo errado:
 * dividir "o cartao" entregava os dez plasticos. A frase dele que reorganizou
 * tudo: <i>"tenho um contrato com limite total de 30 mil; crio um adicional em
 * nome da Luciana que eu posso opcionalmente dizer que tem 1.000 dentro dos meus
 * 30.000. A questao do compartilhamento e poder dar para ela, la no meio de
 * pagamento, a possibilidade de apontar este cartao adicional que esta em nome
 * dela porem DENTRO DA MINHA FATURA"</i>.</p>
 *
 * <h3>O que este teste guarda</h3>
 *
 * <ul>
 *   <li>que dividir a CONTA do cartao e recusado, apontando o caminho (B-D106);</li>
 *   <li>que ela recebe UM plastico e ve UM plastico — nao os outros;</li>
 *   <li>que a compra dela entra na fatura DELE, e o total do contrato soma
 *       (B-D87);</li>
 *   <li>que o extrato dela mostra as compras daquele plastico, <b>de todos</b>,
 *       sem a descricao nem a categoria alheias (B-D109/B-D110);</li>
 *   <li>que o total que ela ve e o do plastico, marcado
 *       {@code MEUS_PLASTICOS} — o do contrato e de quem paga (B-D107);</li>
 *   <li>que ela nao paga, nao fecha, nao reabre e nao emite
 *       (B-D107/B-D108/B-D101);</li>
 *   <li>que revogar o plastico tira o cartao inteiro da vista dela, porque nao
 *       sobrou nenhum.</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CartaoCompartilhadoApiTest extends IntegracaoTest {

    @Autowired
    private TestRestTemplate http;

    private static final String SENHA = "senha-com-mais-de-10";

    private static String tokenDono;
    private static String tokenConvidada;

    private static UUID convidadaId;
    private static String emailDela;
    private static String ambienteDaConvidada;

    private static String ambienteDoDono;
    private static String bancoDoDono;
    private static String bancoDela;

    private static String cartao;

    /** O fisico dele — o plastico que ela NAO recebe. */
    private static String fisicoDoDono;

    /** O virtual "Assinaturas", com 1.000 dentro dos 30.000 — o dividido. */
    private static String virtualCompartilhado;

    private static String faturaAtual;

    // =========================================================================
    // A unidade e o plastico (B-D106)
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Dividir a CONTA do cartao e recusado, e a frase aponta o caminho (B-D106)")
    void contaDeCartaoNaoSeDivide() {
        prepararCenario();

        ResponseEntity<Map> r = post(tokenDono,
            "/api/contas/" + cartao + "/compartilhamentos",
            Map.of("email", emailDela));

        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
        assertTrue(String.valueOf(r.getBody().get("erro")).toLowerCase().contains("emitido"),
            "A recusa nao diz por onde dividir: " + r.getBody().get("erro"));
    }

    @Test
    @Order(2)
    @DisplayName("Dividir UM plastico: ela recebe o virtual e NAO ve o fisico dele")
    void dividirUmPlastico() {
        assertEquals(HttpStatus.CREATED, post(tokenDono,
            "/api/cartoes/" + cartao + "/emitidos/" + virtualCompartilhado + "/compartilhamentos",
            Map.of("email", emailDela)).getStatusCode());

        // O convite diz O QUE esta sendo oferecido: sem isso ela aceitaria
        // pensando ter recebido a conta do cartao inteira.
        Map<String, Object> convite = (Map<String, Object>) ((List<?>)
            get(tokenConvidada, "/api/convites").getBody().get("convites")).get(0);

        Map<String, Object> plastico = (Map<String, Object>) convite.get("plastico");
        assertNotNull(plastico, "O convite chegou sem dizer que e de um plastico");
        assertEquals("Assinaturas", plastico.get("titular"));
        assertEquals("VIRTUAL", plastico.get("tipo"));

        assertEquals(HttpStatus.CREATED, post(tokenConvidada,
            "/api/convites/" + convite.get("id") + "/aceitar",
            Map.of("ambienteId", ambienteDaConvidada)).getStatusCode());

        // O cartao aparece para ela — com UM plastico.
        Map<String, Object> dela = cartaoNaListaDe(tokenConvidada);
        assertEquals("Black", dela.get("nome"));
        assertEquals(false, dela.get("origem"));
        assertEquals("Abner", dela.get("recebidoDe"));

        List<Map<String, Object>> emitidosDela =
            (List<Map<String, Object>>) dela.get("emitidos");

        assertEquals(1, emitidosDela.size(),
            "Ela recebeu um plastico e enxergou " + emitidosDela.size()
                + ". Era exatamente o defeito da V17");
        assertEquals(virtualCompartilhado, emitidosDela.get(0).get("id"));

        // O limite dela e o do PLASTICO — os 1.000 dentro dos 30.000 (B-D110).
        assertEquals("1000.00", emitidosDela.get(0).get("limiteEfetivo"));

        // E o dono continua vendo os dois.
        assertEquals(2, ((List<?>) cartaoNaListaDe(tokenDono).get("emitidos")).size());
    }

    // =========================================================================
    // A compra dela entra na fatura DELE
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("Ela compra no plastico dela, e a compra entra na fatura do contrato dele")
    void aCompraDelaEntraNaFaturaDele() {
        // Ela envia a conta do CARTAO, e nao o banco: o banco do contrato e uma
        // conta dele, invisivel para ela — exigirContaNoAmbiente recusaria.
        assertEquals(HttpStatus.CREATED, post(tokenConvidada, "/api/lancamentos", Map.of(
            "contaId", cartao,
            "cartaoEmitidoId", virtualCompartilhado,
            "categoriaId", categoriaPropria(tokenConvidada, "Estudos dela"),
            "valor", "50.00",
            "dataCaixa", hoje(),
            "descricao", "youtube")).getStatusCode());

        // Ele compra no MESMO plastico virtual — e o caso do cartao de assinaturas
        // que os dois usam.
        assertEquals(HttpStatus.CREATED, post(tokenDono, "/api/lancamentos", Map.of(
            "contaId", bancoDoDono,
            "cartaoEmitidoId", virtualCompartilhado,
            "categoriaId", categoriaPropria(tokenDono, "Trabalho dele"),
            "valor", "100.00",
            "dataCaixa", hoje(),
            "descricao", "claude")).getStatusCode());

        // E no fisico dele, que ela nao ve.
        assertEquals(HttpStatus.CREATED, post(tokenDono, "/api/lancamentos", Map.of(
            "contaId", bancoDoDono,
            "cartaoEmitidoId", fisicoDoDono,
            "categoriaId", categoriaPropria(tokenDono, "Ferramentas"),
            "valor", "900.00",
            "dataCaixa", hoje(),
            "descricao", "furadeira")).getStatusCode());

        faturaAtual = idDaFaturaComTotal();

        // O total DELE e do contrato: 50 + 100 + 900.
        assertEquals("1050.00", totalDaFatura(tokenDono));
        assertEquals("CONTRATO", escopo(tokenDono));
    }

    @Test
    @Order(4)
    @DisplayName("O total que ELA ve e o do plastico, marcado MEUS_PLASTICOS (B-D110)")
    void oTotalDelaEhDoPlastico() {
        // 50 dela + 100 dele NO VIRTUAL. A furadeira de 900 no fisico dele nao
        // entra: ela nao tem aquele plastico.
        assertEquals("150.00", totalDaFatura(tokenConvidada),
            "O total dela somou plastico que nao e dela — o volume de gastos dele vazou");

        assertEquals("MEUS_PLASTICOS", escopo(tokenConvidada),
            "Sem o marcador, a tela mostraria este numero como se fosse o da fatura");
    }

    @Test
    @Order(5)
    @DisplayName("O extrato dela tem as compras DAQUELE plastico, de todos, e nada dos outros")
    void oExtratoRecortaPorPlastico() {
        List<Map<String, Object>> linhas = extratoDaFatura(tokenConvidada);

        assertEquals(2, linhas.size(),
            "O extrato dela deveria ter as duas compras do virtual, e veio com "
                + linhas.size());

        Map<String, Object> dele = linhas.stream()
            .filter(l -> Boolean.FALSE.equals(l.get("meu")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "A compra DELE no plastico compartilhado nao apareceu para ela —"
                    + " e o cartao de assinaturas que os dois usam"));

        assertEquals("100.00", dele.get("valor"));
        assertEquals("Abner", ((Map<String, Object>) dele.get("quem")).get("nome"));

        // B-D109, confirmado: a descricao continua fora. Ele considerou reverter
        // e manteve.
        assertNull(dele.get("descricao"),
            "\"claude\" vazou: B-D89 foi CONFIRMADO no cartao (B-D109)");
        assertNull(dele.get("categoria"),
            "A categoria dele vazou — e a classificacao e de cada um");

        // E a furadeira do fisico dele nao esta em lugar nenhum do extrato dela.
        assertFalse(linhas.stream().anyMatch(l -> "900.00".equals(l.get("valor"))),
            "A compra do plastico que ela nao tem apareceu no extrato dela (B-D110)");
    }

    @Test
    @Order(6)
    @DisplayName("O consumido POR plastico e o numero das mini faturas (dele) e da tela dela")
    void consumidoPorPlastico() {
        Map<String, Object> virtualParaEla = ((List<Map<String, Object>>)
            cartaoNaListaDe(tokenConvidada).get("emitidos")).get(0);

        // 50 + 100: o que aquele plastico consumiu, dos dois.
        assertEquals("150.00", virtualParaEla.get("consumido"));
        assertEquals("1000.00", virtualParaEla.get("limiteEfetivo"));

        // Do lado dele, cada plastico com o proprio numero — as "mini faturas".
        List<Map<String, Object>> dele = (List<Map<String, Object>>)
            cartaoNaListaDe(tokenDono).get("emitidos");

        Map<String, Object> fisico = dele.stream()
            .filter(e -> fisicoDoDono.equals(e.get("id")))
            .findFirst().orElseThrow();

        assertEquals("900.00", fisico.get("consumido"));
        // Sem limite proprio, o efetivo e o do contrato.
        assertEquals("30000.00", fisico.get("limiteEfetivo"));
    }

    // =========================================================================
    // O que ela NAO pode (B-D107, B-D108, B-D101)
    // =========================================================================

    @Test
    @Order(7)
    @DisplayName("Ela nao paga a fatura (403): quem paga e o dono do contrato (B-D107)")
    void elaNaoPagaAFatura() {
        ResponseEntity<Map> r = post(tokenConvidada,
            "/api/faturas/" + faturaAtual + "/pagamentos", Map.of(
                "contaOrigemId", bancoDela,
                "valor", "150.00",
                "dataCaixa", hoje()));

        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());

        // E ele paga, da conta dele.
        assertEquals(HttpStatus.CREATED, post(tokenDono,
            "/api/faturas/" + faturaAtual + "/pagamentos", Map.of(
                "contaOrigemId", bancoDoDono,
                "valor", "1050.00",
                "dataCaixa", hoje())).getStatusCode());

        assertEquals("QUITADA", quitacao(tokenDono));
    }

    @Test
    @Order(8)
    @DisplayName("Ela nao fecha nem reabre a fatura (403) — B-D108 revogou o B-D100")
    void elaNaoFechaNemReabre() {
        assertEquals(HttpStatus.FORBIDDEN,
            post(tokenConvidada, "/api/faturas/" + faturaAtual + "/fechar", Map.of())
                .getStatusCode());

        // O dono fecha.
        assertEquals(HttpStatus.OK,
            post(tokenDono, "/api/faturas/" + faturaAtual + "/fechar", Map.of())
                .getStatusCode());

        assertEquals(HttpStatus.FORBIDDEN,
            post(tokenConvidada, "/api/faturas/" + faturaAtual + "/reabrir", Map.of())
                .getStatusCode());

        assertEquals(HttpStatus.OK,
            post(tokenDono, "/api/faturas/" + faturaAtual + "/reabrir", Map.of())
                .getStatusCode());
    }

    @Test
    @Order(9)
    @DisplayName("Ela nao emite plastico, nao muda o limite e nao encerra o cartao (403)")
    void elaNaoMexeNoContrato() {
        assertEquals(HttpStatus.FORBIDDEN,
            post(tokenConvidada, "/api/cartoes/" + cartao + "/emitidos", Map.of(
                "nomeTitular", "Ela mesma", "tipo", "VIRTUAL", "finalDoCartao", "9999"))
                .getStatusCode());

        assertEquals(HttpStatus.FORBIDDEN,
            put(tokenConvidada, "/api/cartoes/" + cartao, Map.of(
                "nome", "Meu agora", "limite", "99999.00")).getStatusCode());

        assertEquals(HttpStatus.FORBIDDEN,
            post(tokenConvidada, "/api/cartoes/" + cartao + "/encerrar", Map.of())
                .getStatusCode());

        assertEquals("30000.00", cartaoNaListaDe(tokenDono).get("limite"));
    }

    @Test
    @Order(10)
    @DisplayName("Ela nao repassa o plastico a um terceiro (403) — a porta e do contrato")
    void elaNaoRepassa() {
        String terceiro = "marina-plastico-" + UUID.randomUUID().toString().substring(0, 8)
            + "@teste.local";
        cadastrarEEntrar("Marina", terceiro);

        assertEquals(HttpStatus.FORBIDDEN, post(tokenConvidada,
            "/api/cartoes/" + cartao + "/emitidos/" + virtualCompartilhado + "/compartilhamentos",
            Map.of("email", terceiro)).getStatusCode());
    }

    // =========================================================================
    // Revogar
    // =========================================================================

    @Test
    @Order(11)
    @DisplayName("Revogar o plastico tira o CARTAO da vista dela — nao sobrou nenhum")
    void revogarTiraOCartaoInteiro() {
        assertEquals(HttpStatus.NO_CONTENT, delete(tokenDono,
            "/api/cartoes/" + cartao + "/emitidos/" + virtualCompartilhado
                + "/compartilhamentos/" + convidadaId).getStatusCode());

        assertTrue(((List<?>) get(tokenConvidada, "/api/cartoes").getBody().get("cartoes"))
                .isEmpty(),
            "O cartao continuou na tela dela sem nenhum meio de pagamento");

        // E as compras dela ficam na fatura dele: aquele dinheiro entrou de
        // verdade, e o total nao pode mudar por causa de uma revogacao.
        assertEquals("1050.00", totalDaFatura(tokenDono),
            "O total da fatura mudou na revogacao — a compra dela evaporou");
    }

    @Test
    @Order(12)
    @DisplayName("Revogar quem nao usa o plastico responde 404")
    void revogarDuasVezes() {
        assertEquals(HttpStatus.NOT_FOUND, delete(tokenDono,
            "/api/cartoes/" + cartao + "/emitidos/" + virtualCompartilhado
                + "/compartilhamentos/" + convidadaId).getStatusCode());
    }

    // =========================================================================
    // O caso que ELE achou usando (B-D111)
    // =========================================================================

    @Test
    @Order(13)
    @DisplayName("Com os DOIS acessos, o ambiente dela mostra so o plastico dividido (B-D111)")
    void oEscopoSegueOAmbienteAtivo() {
        // Ele divide o virtual de novo — o teste 11 revogou.
        assertEquals(HttpStatus.CREATED, post(tokenDono,
            "/api/cartoes/" + cartao + "/emitidos/" + virtualCompartilhado + "/compartilhamentos",
            Map.of("email", emailDela)).getStatusCode());

        String convite = (String) ((List<Map<String, Object>>)
            get(tokenConvidada, "/api/convites").getBody().get("convites")).get(0).get("id");

        assertEquals(HttpStatus.CREATED, post(tokenConvidada,
            "/api/convites/" + convite + "/aceitar",
            Map.of("ambienteId", ambienteDaConvidada)).getStatusCode());

        // E AGORA o que ele fez sem perceber: ela tambem entra no ambiente dele
        // (V15). Por B-D76 isso da acesso a tudo que e dinheiro la dentro — e foi
        // isso, e nao o compartilhamento de plastico, que fez ela ver os tres.
        assertEquals(HttpStatus.CREATED, post(tokenDono,
            "/api/ambientes/" + ambienteDoDono + "/acessos",
            Map.of("email", emailDela)).getStatusCode());

        // No ambiente DELA: um plastico. O acesso ao ambiente dele nao vaza para
        // ca, e era exatamente o defeito.
        List<Map<String, Object>> noAmbienteDela =
            (List<Map<String, Object>>) cartaoNaListaDe(tokenConvidada).get("emitidos");

        assertEquals(1, noAmbienteDela.size(),
            "No ambiente dela apareceram " + noAmbienteDela.size() + " plasticos."
                + " O escopo tem de seguir o ambiente ativo, nao a soma dos acessos");
        assertEquals(virtualCompartilhado, noAmbienteDela.get(0).get("id"));

        // No ambiente DELE: os tres, porque la ela e membro e B-D76 vale inteiro.
        String tokenNoAmbienteDele = (String) post(tokenConvidada,
            "/api/sessao/ambiente", Map.of("ambienteId", ambienteDoDono))
            .getBody().get("tokenAcesso");

        Map<String, Object> cartaoLaDentro = ((List<Map<String, Object>>)
            get(tokenNoAmbienteDele, "/api/cartoes").getBody().get("cartoes")).stream()
            .filter(c -> cartao.equals(c.get("id")))
            .findFirst().orElseThrow();

        // Os DOIS deste cenario (o fisico dele e o virtual dividido). Dentro do
        // ambiente dele ela ve tudo, e isso e B-D76 inteiro — a senha dele.
        assertEquals(2, ((List<?>) cartaoLaDentro.get("emitidos")).size(),
            "Dentro do ambiente dele ela deveria ver os dois — e a senha dele (B-D76)");

        // E o extrato da fatura segue a mesma regra: recortado no ambiente dela,
        // inteiro no dele.
        assertEquals(2, extratoDaFatura(tokenConvidada).size(),
            "O extrato no ambiente dela trouxe linhas de plastico que nao e dela");

        // Cinco: as tres compras MAIS as duas pernas do pagamento, que tambem
        // carregam a fatura (B-D59). As pernas nao tem plastico, e e por isso que
        // o extrato dela nao as ve — pagamento e movimento do contrato, e por
        // B-D107 ela nao paga.
        ResponseEntity<Map> inteiro = get(tokenNoAmbienteDele,
            "/api/faturas/" + faturaAtual + "/lancamentos");
        assertEquals(5, ((List<?>) inteiro.getBody().get("lancamentos")).size(),
            "No ambiente dele o extrato deveria vir inteiro");
    }

    @Test
    @Order(14)
    @DisplayName("O banco do cartao dividido tem NOME para ela (B-D112)")
    void oBancoTemNome() {
        assertEquals("Nubank dele", cartaoNaListaDe(tokenConvidada).get("banco") == null
            ? null
            : ((Map<String, Object>) cartaoNaListaDe(tokenConvidada).get("banco")).get("nome"),
            "Sem o nome do banco, o seletor de conta dela nao tem como agrupar o cartao");
    }

    // =========================================================================
    // Cenario
    // =========================================================================

    private void prepararCenario() {
        if (cartao != null) {
            return;
        }

        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        String emailDono = "abner-plastico-" + sufixo + "@teste.local";
        emailDela = "luciana-plastico-" + sufixo + "@teste.local";

        tokenDono      = cadastrarEEntrar("Abner", emailDono);
        tokenConvidada = cadastrarEEntrar("Luciana", emailDela);

        ambienteDoDono = String.valueOf(get(tokenDono, "/api/perfil").getBody().get("ambienteAtual"));

        Map perfilDela = get(tokenConvidada, "/api/perfil").getBody();
        convidadaId         = UUID.fromString(String.valueOf(perfilDela.get("usuarioId")));
        ambienteDaConvidada = String.valueOf(perfilDela.get("ambienteAtual"));

        bancoDoDono = criarBanco(tokenDono, "Nubank dele");
        bancoDela   = criarBanco(tokenConvidada, "C6 dela");

        // O contrato: 30 mil, como no exemplo dele.
        ResponseEntity<Map> criado = post(tokenDono, "/api/cartoes", Map.of(
            "contaBancoId", bancoDoDono,
            "nome", "Black",
            "limite", "30000.00",
            "diaVencimento", 15,
            "finalDoCartao", "1234"));
        assertEquals(HttpStatus.CREATED, criado.getStatusCode(),
            "Cenario nao subiu: " + criado.getBody());

        cartao = (String) criado.getBody().get("id");
        fisicoDoDono = (String) ((List<Map<String, Object>>)
            criado.getBody().get("emitidos")).get(0).get("id");

        // O virtual de assinaturas, com 1.000 dentro dos 30.000.
        Map<String, Object> comVirtual = post(tokenDono, "/api/cartoes/" + cartao + "/emitidos",
            Map.of("nomeTitular", "Assinaturas",
                   "tipo", "VIRTUAL",
                   "finalDoCartao", "5678",
                   "limiteProprio", "1000.00")).getBody();

        virtualCompartilhado = ((List<Map<String, Object>>) comVirtual.get("emitidos")).stream()
            .filter(e -> "Assinaturas".equals(e.get("nomeTitular")))
            .map(e -> (String) e.get("id"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("O virtual nao foi criado"));
    }

    private String criarBanco(String token, String nome) {
        return (String) post(token, "/api/contas", Map.of(
            "nome", nome,
            "natureza", "ATIVO",
            "saldoInicial", "5000.00",
            "formasPagamento", List.of("DEBITO", "PIX"),
            "padraoSaida", "DEBITO")).getBody().get("id");
    }

    private String categoriaPropria(String token, String nome) {
        return (String) post(token, "/api/categorias",
            Map.of("nome", nome, "tipo", "SAIDA")).getBody().get("id");
    }

    private Map<String, Object> cartaoNaListaDe(String token) {
        return ((List<Map<String, Object>>) get(token, "/api/cartoes").getBody().get("cartoes"))
            .stream()
            .filter(c -> cartao.equals(c.get("id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("O cartao nao apareceu na lista"));
    }

    private String idDaFaturaComTotal() {
        int ano = java.time.LocalDate.now().getYear();
        return ((List<Map<String, Object>>)
            get(tokenDono, "/api/cartoes/" + cartao + "/faturas?ano=" + ano)
                .getBody().get("faturas")).stream()
            .filter(f -> !"0.00".equals(f.get("total")))
            .map(f -> (String) f.get("id"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Nenhuma fatura recebeu as compras"));
    }

    private Map<String, Object> fatura(String token) {
        return get(token, "/api/faturas/" + faturaAtual).getBody();
    }

    private String totalDaFatura(String token) { return (String) fatura(token).get("total"); }
    private String quitacao(String token)      { return (String) fatura(token).get("quitacao"); }
    private String escopo(String token)        { return (String) fatura(token).get("escopoDoTotal"); }

    private List<Map<String, Object>> extratoDaFatura(String token) {
        ResponseEntity<Map> r = get(token, "/api/faturas/" + faturaAtual + "/lancamentos");
        assertEquals(HttpStatus.OK, r.getStatusCode());
        return (List<Map<String, Object>>) r.getBody().get("lancamentos");
    }

    private static String hoje() {
        return java.time.LocalDate.now().toString();
    }

    private String cadastrarEEntrar(String nome, String email) {
        assertEquals(HttpStatus.CREATED, postSemToken("/api/auth/cadastro",
            Map.of("nome", nome, "email", email, "senha", SENHA)).getStatusCode());

        return (String) postSemToken("/api/auth/login",
            Map.of("email", email, "senha", SENHA)).getBody().get("tokenAcesso");
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

    private ResponseEntity<Map> put(String token, String caminho, Map<String, ?> corpo) {
        return http.exchange(caminho, HttpMethod.PUT,
            new HttpEntity<>(corpo, cabecalhos(token)), Map.class);
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
