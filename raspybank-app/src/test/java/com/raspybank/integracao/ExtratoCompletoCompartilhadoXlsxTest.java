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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fronteira de dados do {@code .xlsx} — a metade que ninguem testa.
 *
 * <p>Todo cenario de visibilidade tem duas metades, e o arquivo torna a segunda
 * mais perigosa que a tela: uma linha vazada num JSON some quando a tela nao a
 * desenha; uma linha vazada num {@code .xlsx} fica no disco da pessoa, com
 * valor e data, para sempre.</p>
 *
 * <h3>As tres perguntas</h3>
 *
 * <ol>
 *   <li><b>A estranha.</b> Mariana nao divide ambiente, conta nem plastico com
 *       ninguem. Nenhuma celula do arquivo dela e de outra pessoa, e nenhuma
 *       celula do arquivo dos outros e dela — conferido celula a celula, e nao
 *       por contagem de linha, porque contagem confere quando duas linhas
 *       trocam de lugar.</li>
 *   <li><b>A mascara, nas duas metades.</b> Mascarar de menos vaza o texto
 *       livre; mascarar demais some com a linha e o arquivo deixa de fechar com
 *       o extrato do banco. As duas sao defeito, e por isso a linha alheia e
 *       conferida coluna por coluna: Data, Conta, Pago com, Tipo, Valor e
 *       <b>Quem</b> preenchidos; Descricao, Categoria e Subcategoria
 *       vazias.</li>
 *   <li><b>O recorte por plastico</b> (B-D106/B-D110). E o furo que a V22
 *       fechou, e o unico deste arquivo que a tela ja tinha aprendido a evitar:
 *       aceitar um plastico vincula a CONTA do contrato ao ambiente de quem
 *       recebeu, e sem o recorte esse vinculo entregaria as compras dos OUTROS
 *       plasticos — mascaradas, mas com valor e data, que e o volume de gastos
 *       da outra pessoa.</li>
 * </ol>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExtratoCompletoCompartilhadoXlsxTest extends IntegracaoTest {

    @Autowired
    private TestRestTemplate http;

    private static final String SENHA = "senha-com-mais-de-10";

    private static final int DATA = 0;
    private static final int DESCRICAO = 1;
    private static final int SITUACAO = 2;
    private static final int CATEGORIA = 3;
    private static final int SUBCATEGORIA = 4;
    private static final int CONTA = 5;
    private static final int PAGO_COM = 6;
    private static final int PARCELA = 7;
    private static final int TIPO = 8;
    private static final int VALOR = 9;
    private static final int QUEM = 10;

    private static final LocalDate HOJE = LocalDate.now();
    private static final LocalDate INICIO = HOJE.minusMonths(6);
    private static final LocalDate FIM = HOJE.plusMonths(6);

    private static String tokenDono;
    private static String tokenConvidada;
    private static String tokenEstranha;

    private static String emailDela;
    private static String ambienteDela;

    private static final String ABA_DELE = "Financas de Abner";
    private static final String ABA_DELA = "Financas de Luciana";
    private static final String ABA_DA_ESTRANHA = "Financas de Mariana";

    private static final String CONTA_CONJUNTA = "Conta conjunta do extrato";

    private static String contaConjunta;
    private static String cartao;
    private static String bancoDele;
    private static String fisicoDele;
    private static String virtualDividido;

    private static boolean pronto;

    // =========================================================================
    // 1. A estranha
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Sem vinculo nenhum, nada de uma pessoa aparece no arquivo da outra")
    void oArquivoDaEstranhaNaoTemLinhaDeNinguem() {
        prepararCenario();

        // Ela ve o proprio ambiente, e so ele.
        LeitoraXlsx.Planilha dela = baixar(tokenEstranha, INICIO, FIM);
        assertEquals(List.of("Sobre este arquivo", ABA_DA_ESTRANHA), dela.nomes(),
            "O arquivo da estranha ganhou aba de ambiente que nao e dela");

        // Celula a celula, nas duas direcoes. Contagem de linha nao serve: ela
        // continua batendo quando uma linha alheia entra e uma propria some.
        List<String> deleNoArquivoDela = celulasQueContem(dela,
            List.of("Mercado dele", "Presente para o Abner", "Abner", "Luciana",
                    "claude", "furadeira", "youtube", CONTA_CONJUNTA, "Cartao Black"));
        assertEquals(List.of(), deleNoArquivoDela,
            "O arquivo da estranha traz dado de quem ela nao conhece");

        // E o contrario: nada dela nos arquivos dos outros.
        for (String token : List.of(tokenDono, tokenConvidada)) {
            List<String> vazamento = celulasQueContem(baixar(token, INICIO, FIM),
                List.of("Segredo da Mariana", "Mariana", "777.77", "Conta so da Mariana"));
            assertEquals(List.of(), vazamento,
                "Linha da estranha apareceu no arquivo de quem nao tem vinculo com ela");
        }
    }

    // =========================================================================
    // 2. A mascara, nas duas metades
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("A linha alheia na conta dividida entra com Valor, Conta e Quem, e sem texto livre")
    void aLinhaAlheiaEntraMascarada() {
        LeitoraXlsx.Aba aba = baixar(tokenDono, INICIO, FIM).aba(ABA_DELE);

        List<LeitoraXlsx.Celula> dela = aba.corpo().stream()
            .filter(l -> "Luciana".equals(l.get(QUEM).comoTexto()))
            .filter(l -> CONTA_CONJUNTA.equals(l.get(CONTA).comoTexto()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "A linha DELA sumiu do arquivo dele. Mascarar demais e tao defeito"
                    + " quanto mascarar de menos: sem ela a soma do arquivo nao fecha"
                    + " com o extrato do banco, que e a razao de o arquivo existir"));

        // A metade que tem de aparecer.
        assertEquals(HOJE, dela.get(DATA).comoData());
        assertEquals(CONTA_CONJUNTA, dela.get(CONTA).comoTexto());
        assertEquals("Débito", dela.get(PAGO_COM).comoTexto(),
            "A forma de pagamento da linha alheia sumiu");
        assertEquals("SAIDA", dela.get(TIPO).comoTexto());
        assertEquals(0, new BigDecimal("-240.00").compareTo(dela.get(VALOR).numero()),
            "O valor da linha alheia veio errado: " + dela.get(VALOR));
        assertEquals("Luciana", dela.get(QUEM).comoTexto(),
            "Sem a coluna Quem, a linha mascarada parece dado corrompido — e a unica"
                + " coluna do arquivo que existe por causa da mascara");
        assertEquals("REALIZADO", dela.get(SITUACAO).comoTexto());

        // A metade que NAO pode aparecer.
        assertTrue(dela.get(DESCRICAO).vazia(),
            "\"Presente para o Abner\" vazou: texto livre e onde as pessoas escrevem"
                + " o que nao pretendiam dividir (B-D89)");
        assertTrue(dela.get(CATEGORIA).vazia(),
            "A categoria dela vazou — a classificacao e de cada um (B-D85)");
        assertTrue(dela.get(SUBCATEGORIA).vazia(),
            "A subcategoria dela vazou");
    }

    @Test
    @Order(3)
    @DisplayName("A linha PROPRIA continua inteira ao lado da mascarada — a mascara nao pega geral")
    void aLinhaPropriaContinuaInteira() {
        LeitoraXlsx.Aba aba = baixar(tokenDono, INICIO, FIM).aba(ABA_DELE);

        List<LeitoraXlsx.Celula> dele = aba.corpo().stream()
            .filter(l -> "Mercado dele".equals(l.get(DESCRICAO).comoTexto()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("O lancamento dele sumiu do arquivo dele"));

        assertEquals("Mercado dele", dele.get(DESCRICAO).comoTexto());
        assertEquals("Mercado do Abner", dele.get(CATEGORIA).comoTexto());
        assertEquals("Abner", dele.get(QUEM).comoTexto());
    }

    @Test
    @Order(4)
    @DisplayName("Do lado dela a mascara inverte: o dele e que vem sem descricao e sem categoria")
    void aMascaraValeNosDoisSentidos() {
        LeitoraXlsx.Aba aba = baixar(tokenConvidada, INICIO, FIM).aba(ABA_DELA);

        List<LeitoraXlsx.Celula> dele = aba.corpo().stream()
            .filter(l -> "Abner".equals(l.get(QUEM).comoTexto()))
            .filter(l -> CONTA_CONJUNTA.equals(l.get(CONTA).comoTexto()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "A linha DELE nao chegou na aba dela — a conta e dividida, e o saldo"
                    + " atravessa ambientes (B-D87)"));

        assertTrue(dele.get(DESCRICAO).vazia(), "\"Mercado dele\" vazou para o arquivo dela");
        assertTrue(dele.get(CATEGORIA).vazia(), "A categoria dele vazou para o arquivo dela");
        assertEquals("Abner", dele.get(QUEM).comoTexto());

        // E o dela continua inteiro na aba dela.
        List<LeitoraXlsx.Celula> proprio = aba.corpo().stream()
            .filter(l -> "Presente para o Abner".equals(l.get(DESCRICAO).comoTexto()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("O lancamento dela sumiu do arquivo dela"));
        assertEquals("Presentes dela", proprio.get(CATEGORIA).comoTexto());
    }

    @Test
    @Order(5)
    @DisplayName("A linha alheia entra na aba do ambiente DELE, e nao numa aba do ambiente dela")
    void aLinhaAlheiaCaiNaAbaCerta() {
        LeitoraXlsx.Planilha dele = baixar(tokenDono, INICIO, FIM);

        assertEquals(List.of("Sobre este arquivo", ABA_DELE), dele.nomes(),
            "O arquivo dele ganhou aba do ambiente dela: o arquivo e o retrato da"
                + " pessoa, e ele nao tem vinculo com o ambiente dela");
    }

    // =========================================================================
    // 3. O recorte por plastico — B-D106 / B-D110
    // =========================================================================

    @Test
    @Order(6)
    @DisplayName("Quem recebeu UM plastico ve as compras daquele plastico, de todos")
    void oPlasticoDivididoTrazAsComprasDosDois() {
        LeitoraXlsx.Aba aba = baixar(tokenConvidada, INICIO, FIM).aba(ABA_DELA);

        List<LeitoraXlsx.Celula> dela = aba.corpo().stream()
            .filter(l -> "youtube".equals(l.get(DESCRICAO).comoTexto()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("A compra dela no plastico dela sumiu"));
        assertEquals(0, new BigDecimal("-50.00").compareTo(dela.get(VALOR).numero()));

        List<LeitoraXlsx.Celula> dele = aba.corpo().stream()
            .filter(l -> "Abner".equals(l.get(QUEM).comoTexto()))
            .filter(l -> valorEh(l, "-100.00"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "A compra DELE no plastico que os dois usam nao apareceu para ela —"
                    + " e o cartao de assinaturas compartilhado (B-D110)"));

        assertTrue(dele.get(DESCRICAO).vazia(), "\"claude\" vazou no arquivo dela");
        assertTrue(dele.get(CATEGORIA).vazia(), "A categoria dele vazou no arquivo dela");
    }

    @Test
    @Order(7)
    @DisplayName("As compras do OUTRO plastico do mesmo cartao nao entram, nem mascaradas (B-D110)")
    void oOutroPlasticoNaoEntraNemMascarado() {
        LeitoraXlsx.Planilha dela = baixar(tokenConvidada, INICIO, FIM);

        // A furadeira, de 900, foi no fisico DELE — o plastico que ela nao
        // recebeu. Aceitar um plastico vincula a CONTA do contrato ao ambiente
        // dela; sem o recorte, esse vinculo entregaria as compras dos dez.
        assertEquals(List.of(), celulasQueContem(dela, List.of("furadeira", "900.00", "-900.00")),
            "A compra do plastico que ela NAO recebeu apareceu no arquivo dela."
                + " Mascarada ou nao, o valor e a data sao o volume de gastos dele"
                + " — e exatamente o que B-D106 tirou da tela e B-D110 recusou");

        // E a soma da aba dela nao pode conter os 900 escondidos em lugar nenhum.
        for (List<LeitoraXlsx.Celula> linha : dela.aba(ABA_DELA).corpo()) {
            assertFalse(valorEh(linha, "-900.00"),
                "Linha de 900 no arquivo dela: " + linha);
        }
    }

    @Test
    @Order(8)
    @DisplayName("O dono continua vendo os dois plasticos, e o recorte nao virou censura para ele")
    void oDonoVeOsDoisPlasticos() {
        LeitoraXlsx.Aba aba = baixar(tokenDono, INICIO, FIM).aba(ABA_DELE);

        assertNotNull(porDescricao(aba, "furadeira"), "A compra dele no fisico sumiu do arquivo dele");
        assertNotNull(porDescricao(aba, "claude"), "A compra dele no virtual sumiu do arquivo dele");

        List<LeitoraXlsx.Celula> dela = aba.corpo().stream()
            .filter(l -> "Luciana".equals(l.get(QUEM).comoTexto()))
            .filter(l -> valorEh(l, "-50.00"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "A compra DELA no plastico dele nao chegou ao arquivo dele — e ele que"
                    + " paga a fatura (B-D107), entao ela tem de estar la"));

        assertTrue(dela.get(DESCRICAO).vazia(), "\"youtube\" vazou para o arquivo dele");
        assertEquals("Luciana", dela.get(QUEM).comoTexto());
        assertNotNull(dela.get(PAGO_COM).comoTexto(),
            "A linha dela no cartao dele veio sem dizer por qual plastico passou");
    }

    // =========================================================================
    // 4. O extrato fecha
    // =========================================================================

    @Test
    @Order(9)
    @DisplayName("A soma da coluna Valor da conta dividida bate com o saldo que o sistema calcula")
    void aSomaDaColunaValorFechaComOSaldo() {
        // Todos os lancamentos desta conta sao REALIZADO e estao dentro da faixa,
        // entao a soma com sinal tem de dar exatamente o saldo — que vem de
        // app_saldo_da_conta e atravessa ambientes (B-D87).
        BigDecimal soma = somaDaConta(baixar(tokenDono, INICIO, FIM), CONTA_CONJUNTA);

        assertEquals(0, new BigDecimal(saldoDaContaConjunta()).compareTo(soma),
            "A soma da coluna Valor (" + soma + ") nao fecha com o saldo da conta ("
                + saldoDaContaConjunta() + "). O arquivo existe para conferir a vida"
                + " financeira contra o extrato do banco; se ele nao fecha com o"
                + " proprio sistema, nao fecha com nada");

        // E do lado dela o mesmo numero: e a mesma conta, e o saldo e um so.
        BigDecimal somaDela = somaDaConta(baixar(tokenConvidada, INICIO, FIM), CONTA_CONJUNTA);
        assertEquals(0, soma.compareTo(somaDela),
            "Os dois arquivos somam numeros diferentes para a MESMA conta: "
                + soma + " e " + somaDela);
    }

    // =========================================================================
    // 5. A segunda operacao — o que se perde e o que fica
    // =========================================================================

    @Test
    @Order(10)
    @DisplayName("Com acesso ao AMBIENTE dele, o arquivo dela ganha a aba dele — e nenhuma linha sai duas vezes")
    void comAcessoAoAmbienteAsLinhasNaoDuplicam() {
        // B-D76: entrar no ambiente dele e como ter a senha dele. Agora as
        // linhas dele chegam por DUAS portas ao mesmo tempo — o ambiente e o
        // plastico dividido —, e e onde uma consulta escrita com OR entregaria a
        // mesma compra duas vezes. Dinheiro contado duas vezes num arquivo feito
        // para somar e o pior defeito possivel deste endpoint.
        assertEquals(HttpStatus.CREATED, post(tokenDono,
            "/api/ambientes/" + ambienteDele() + "/acessos",
            Map.of("email", emailDela)).getStatusCode());

        LeitoraXlsx.Planilha dela = baixar(tokenConvidada, INICIO, FIM);

        assertEquals(List.of("Sobre este arquivo", ABA_DELE, ABA_DELA), dela.nomes(),
            "O arquivo dela nao ganhou a aba do ambiente dele");

        for (String descricao : List.of("claude", "furadeira", "Mercado dele",
                                        "youtube", "Presente para o Abner")) {
            assertEquals(1, quantasLinhasCom(dela, descricao),
                "\"" + descricao + "\" aparece " + quantasLinhasCom(dela, descricao)
                    + " vezes no arquivo dela. As linhas dele chegam por duas portas"
                    + " (o ambiente e o plastico) e uma delas duplicou");
        }

        // E dentro da aba DELE ela ve tudo, sem mascara — e a senha dele.
        LeitoraXlsx.Aba abaDele = dela.aba(ABA_DELE);
        assertNotNull(porDescricao(abaDele, "furadeira"),
            "Dentro do ambiente dele ela deveria ver o fisico tambem (B-D76)");
        assertEquals("Ferramentas dele", porDescricao(abaDele, "furadeira").get(CATEGORIA).comoTexto(),
            "A linha dele veio mascarada dentro do PROPRIO ambiente dele");

        // Devolve o cenario ao estado anterior: o acesso ao ambiente e o que os
        // testes seguintes NAO podem ter.
        assertEquals(HttpStatus.NO_CONTENT, delete(tokenDono,
            "/api/ambientes/" + ambienteDele() + "/acessos/" + usuarioDela())
            .getStatusCode());

        assertEquals(List.of("Sobre este arquivo", ABA_DELA),
            baixar(tokenConvidada, INICIO, FIM).nomes(),
            "Removido o acesso, a aba do ambiente dele continua no arquivo dela");
    }

    @Test
    @Order(11)
    @DisplayName("Revogado o plastico, a compra DELE some do arquivo dela — e a DELA fica")
    void revogarOPlasticoTiraALinhaAlheiaENaoADela() {
        assertEquals(HttpStatus.NO_CONTENT, delete(tokenDono,
            "/api/cartoes/" + cartao + "/emitidos/" + virtualDividido
                + "/compartilhamentos/" + usuarioDela()).getStatusCode());

        LeitoraXlsx.Planilha dela = baixar(tokenConvidada, INICIO, FIM);

        // A compra dele vem mascarada, entao nao se procura por descricao: o que
        // a identifica e o plastico em "Pago com" mais o nome em "Quem".
        assertEquals(0, linhasNoPlasticoDividido(dela, "Abner"),
            "A compra dele no plastico continuou no arquivo dela depois da revogacao."
                + " Revogar tem de valer para o arquivo tambem, senao a proxima"
                + " geracao entrega o que ja nao pode ser visto");

        // E a compra DELA fica: aquele dinheiro saiu de verdade, e o arquivo e o
        // historico dela. Sumir com ela seria reescrever o passado.
        assertNotNull(porDescricao(dela.aba(ABA_DELA), "youtube"),
            "A compra DELA sumiu do proprio arquivo dela quando o plastico foi"
                + " revogado — o dinheiro dela evaporou do historico");

        // Do lado dele, nada muda: ele paga a fatura inteira.
        LeitoraXlsx.Aba abaDele = baixar(tokenDono, INICIO, FIM).aba(ABA_DELE);
        assertNotNull(porDescricao(abaDele, "furadeira"));
        assertTrue(abaDele.corpo().stream().anyMatch(l -> valorEh(l, "-50.00")),
            "A compra dela sumiu do arquivo dele na revogacao — e ele que paga (B-D107)");
    }

    @Test
    @Order(12)
    @DisplayName("Recompartilhar o plastico traz a linha de volta, e uma vez so")
    void recompartilharNaoDuplica() {
        assertEquals(HttpStatus.CREATED, post(tokenDono,
            "/api/cartoes/" + cartao + "/emitidos/" + virtualDividido + "/compartilhamentos",
            Map.of("email", emailDela)).getStatusCode());
        aceitarConvite(tokenConvidada);

        LeitoraXlsx.Planilha dela = baixar(tokenConvidada, INICIO, FIM);

        assertEquals(1, linhasNoPlasticoDividido(dela, "Abner"),
            "Depois de revogar e reconvidar, a compra dele aparece "
                + linhasNoPlasticoDividido(dela, "Abner")
                + " vez(es) no arquivo dela: o segundo vinculo virou uma segunda"
                + " linha, ou nao voltou nenhuma");

        assertEquals(1, quantasLinhasCom(dela, "youtube"),
            "A compra dela duplicou depois do segundo aceite");
    }

    @Test
    @Order(13)
    @DisplayName("Revogada a conta dividida, o lancamento DELA continua no arquivo dela (B-D93)")
    void revogarAContaNaoApagaOHistoricoDela() {
        assertEquals(HttpStatus.NO_CONTENT, delete(tokenDono,
            "/api/contas/" + contaConjunta + "/compartilhamentos/" + usuarioDela())
            .getStatusCode());

        LeitoraXlsx.Aba dela = baixar(tokenConvidada, INICIO, FIM).aba(ABA_DELA);

        List<LeitoraXlsx.Celula> proprio = porDescricao(dela, "Presente para o Abner");
        assertNotNull(proprio,
            "O lancamento DELA na conta dividida sumiu do arquivo dela quando o"
                + " acesso foi revogado. Aquele dinheiro saiu de verdade, e o"
                + " lancamento e dela (B-D93) — some do arquivo e o historico dela"
                + " passa a ter um buraco que nada explica");

        assertEquals(CONTA_CONJUNTA, proprio.get(CONTA).comoTexto(),
            "A linha ficou, mas sem dizer de que conta saiu");

        // E o que era DELE some, porque o vinculo acabou.
        assertNull(porDescricao(dela, "Mercado dele"));
        assertEquals(List.of(), celulasQueContem(baixar(tokenConvidada, INICIO, FIM),
            List.of("Mercado dele")),
            "A linha dele na conta revogada continuou no arquivo dela");

        // Do lado dele, o lancamento dela fica — o saldo nao pode mudar por
        // causa de uma revogacao.
        LeitoraXlsx.Aba abaDele = baixar(tokenDono, INICIO, FIM).aba(ABA_DELE);
        assertTrue(abaDele.corpo().stream().anyMatch(l -> valorEh(l, "-240.00")),
            "O gasto dela sumiu do arquivo dele na revogacao: o saldo do arquivo"
                + " deixou de bater com o extrato do banco");
    }

    @Test
    @Order(14)
    @DisplayName("O pagamento da fatura entra com as DUAS pernas no arquivo do dono e em nenhuma do plastico")
    void oPagamentoDaFaturaEntraSoParaQuemPaga() {
        String fatura = faturaComTotal();

        assertEquals(HttpStatus.CREATED, post(tokenDono,
            "/api/faturas/" + fatura + "/pagamentos", Map.of(
                "contaOrigemId", bancoDele,
                "valor", "500.00",
                "dataCaixa", HOJE.toString(),
                "formaPagamento", "DEBITO")).getStatusCode());

        LeitoraXlsx.Aba abaDele = baixar(tokenDono, INICIO, FIM).aba(ABA_DELE);

        List<List<LeitoraXlsx.Celula>> pernas = abaDele.corpo().stream()
            .filter(l -> "Pagamento de fatura".equals(l.get(CATEGORIA).comoTexto()))
            .toList();

        assertEquals(2, pernas.size(),
            "O pagamento da fatura entrou com " + pernas.size() + " perna(s) no"
                + " arquivo. Uma so faria o dinheiro sair do banco sem abater a"
                + " divida do cartao, e a soma do arquivo deixaria de ser zero"
                + " para um movimento que so troca dinheiro de lugar");

        assertEquals(0, pernas.get(0).get(VALOR).numero()
                .add(pernas.get(1).get(VALOR).numero()).signum(),
            "As duas pernas do pagamento nao se anulam: " + pernas.get(0).get(VALOR)
                + " e " + pernas.get(1).get(VALOR));

        // E do lado dela, nada: ela nao paga a fatura (B-D107), e as pernas do
        // pagamento nao tem plastico (B-D59) — o dinheiro dela e o das compras
        // dela. Ver o pagamento seria ver o volume da divida dele.
        // Na aba, e nao no arquivo inteiro: a capa cita "Pagamento de fatura"
        // de proposito, ensinando a filtrar essa categoria fora.
        LeitoraXlsx.Aba abaDela = baixar(tokenConvidada, INICIO, FIM).aba(ABA_DELA);
        assertFalse(abaDela.corpo().stream()
                .anyMatch(l -> l.size() > CATEGORIA
                    && "Pagamento de fatura".equals(l.get(CATEGORIA).comoTexto())),
            "O pagamento da fatura dele apareceu no arquivo de quem so recebeu um"
                + " plastico. Ela nao paga a fatura, e o valor do pagamento e o"
                + " tamanho da divida dele");

        assertFalse(abaDela.corpo().stream().anyMatch(l -> valorEh(l, "-500.00")
                || valorEh(l, "500.00")),
            "O valor do pagamento da fatura dele apareceu no arquivo dela");
    }

    // =========================================================================
    // Cenario
    // =========================================================================

    private void prepararCenario() {
        if (pronto) {
            return;
        }
        pronto = true;

        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        String emailDele = "abner-xlsxc-" + sufixo + "@teste.local";
        emailDela = "luciana-xlsxc-" + sufixo + "@teste.local";
        String emailEstranha = "mariana-xlsxc-" + sufixo + "@teste.local";

        tokenDono = cadastrarEEntrar("Abner", emailDele);
        tokenConvidada = cadastrarEEntrar("Luciana", emailDela);
        tokenEstranha = cadastrarEEntrar("Mariana", emailEstranha);

        ambienteDela = String.valueOf(get(tokenConvidada, "/api/perfil").getBody().get("ambienteAtual"));

        // ---- A estranha, que nao divide nada com ninguem ---------------------
        String contaDela = criarConta(tokenEstranha, "Conta so da Mariana", "1000.00");
        lancar(tokenEstranha, contaDela, criarCategoria(tokenEstranha, "Coisas dela", "SAIDA"),
            "777.77", HOJE, "Segredo da Mariana");

        // ---- A conta conjunta ------------------------------------------------
        contaConjunta = criarConta(tokenDono, CONTA_CONJUNTA, "1000.00");
        lancar(tokenDono, contaConjunta, criarCategoria(tokenDono, "Mercado do Abner", "SAIDA"),
            "100.00", HOJE, "Mercado dele");

        assertEquals(HttpStatus.CREATED, post(tokenDono,
            "/api/contas/" + contaConjunta + "/compartilhamentos",
            Map.of("email", emailDela)).getStatusCode());
        aceitarConvite(tokenConvidada);

        lancar(tokenConvidada, contaConjunta, criarCategoria(tokenConvidada, "Presentes dela", "SAIDA"),
            "240.00", HOJE, "Presente para o Abner");

        // ---- O cartao com DOIS plasticos, um so dividido ---------------------
        bancoDele = criarConta(tokenDono, "Banco do cartao dele", "0.00");
        ResponseEntity<Map> criado = post(tokenDono, "/api/cartoes", Map.of(
            "contaBancoId", bancoDele,
            "nome", "Cartao Black",
            "limite", "30000.00",
            "diaVencimento", 15,
            "finalDoCartao", "1234"));
        assertEquals(HttpStatus.CREATED, criado.getStatusCode(),
            "Cenario nao subiu (cartao): " + criado.getBody());

        cartao = String.valueOf(criado.getBody().get("id"));
        fisicoDele = String.valueOf(((List<Map<String, Object>>)
            criado.getBody().get("emitidos")).get(0).get("id"));

        Map<String, Object> comVirtual = post(tokenDono, "/api/cartoes/" + cartao + "/emitidos",
            Map.of("nomeTitular", "Assinaturas", "tipo", "VIRTUAL",
                   "finalDoCartao", "5678", "limiteProprio", "1000.00")).getBody();

        virtualDividido = ((List<Map<String, Object>>) comVirtual.get("emitidos")).stream()
            .filter(e -> "Assinaturas".equals(e.get("nomeTitular")))
            .map(e -> String.valueOf(e.get("id")))
            .findFirst().orElseThrow();

        assertEquals(HttpStatus.CREATED, post(tokenDono,
            "/api/cartoes/" + cartao + "/emitidos/" + virtualDividido + "/compartilhamentos",
            Map.of("email", emailDela)).getStatusCode());
        aceitarConvite(tokenConvidada);

        // As tres compras: a dela no virtual, a dele no virtual, e a dele no
        // FISICO — que e a que nao pode chegar ao arquivo dela.
        comprar(tokenConvidada, cartao, virtualDividido,
            criarCategoria(tokenConvidada, "Estudos dela", "SAIDA"), "50.00", "youtube");
        comprar(tokenDono, bancoDele, virtualDividido,
            criarCategoria(tokenDono, "Trabalho dele", "SAIDA"), "100.00", "claude");
        comprar(tokenDono, bancoDele, fisicoDele,
            criarCategoria(tokenDono, "Ferramentas dele", "SAIDA"), "900.00", "furadeira");
    }

    // =========================================================================
    // Ferramentas
    // =========================================================================

    private LeitoraXlsx.Planilha baixar(String token, LocalDate inicio, LocalDate fim) {
        ResponseEntity<byte[]> r = http.exchange(
            "/api/relatorios/extrato.xlsx?inicio=" + inicio + "&fim=" + fim,
            HttpMethod.GET, new HttpEntity<>(cabecalhos(token)), byte[].class);

        assertEquals(HttpStatus.OK, r.getStatusCode(),
            "O extrato nao veio: " + new String(r.getBody() == null ? new byte[0] : r.getBody()));
        return LeitoraXlsx.ler(r.getBody());
    }

    /**
     * Toda celula do arquivo que contenha qualquer um dos textos proibidos.
     *
     * <p>Varredura celula a celula, e nao contagem de linha: contagem continua
     * batendo quando uma linha alheia entra e uma propria sai.</p>
     */
    private static List<String> celulasQueContem(LeitoraXlsx.Planilha planilha,
                                                 List<String> proibidos) {
        List<String> achados = new ArrayList<>();
        for (LeitoraXlsx.Aba aba : planilha.abas()) {
            for (int i = 0; i < aba.linhas().size(); i++) {
                for (LeitoraXlsx.Celula celula : aba.linhas().get(i)) {
                    String texto = celula.tipo() == LeitoraXlsx.Tipo.VAZIA
                        ? "" : String.valueOf(celula.texto());
                    for (String proibido : proibidos) {
                        if (texto.contains(proibido)) {
                            achados.add(aba.nome() + " linha " + (i + 1) + ": " + celula);
                        }
                    }
                }
            }
        }
        return achados;
    }

    private static boolean valorEh(List<LeitoraXlsx.Celula> linha, String esperado) {
        if (linha.size() <= VALOR) {
            return false;
        }
        LeitoraXlsx.Celula celula = linha.get(VALOR);
        return celula.tipo() == LeitoraXlsx.Tipo.NUMERO
            && new BigDecimal(esperado).compareTo(celula.numero()) == 0;
    }

    private static List<LeitoraXlsx.Celula> porDescricao(LeitoraXlsx.Aba aba, String descricao) {
        return aba.corpo().stream()
            .filter(l -> l.size() > DESCRICAO && descricao.equals(l.get(DESCRICAO).comoTexto()))
            .findFirst().orElse(null);
    }

    /** A soma com sinal de todas as linhas de uma conta, em todas as abas. */
    private static BigDecimal somaDaConta(LeitoraXlsx.Planilha planilha, String conta) {
        BigDecimal soma = BigDecimal.ZERO;
        for (LeitoraXlsx.Aba aba : planilha.abas()) {
            for (List<LeitoraXlsx.Celula> linha : aba.corpo()) {
                if (linha.size() > VALOR
                    && conta.equals(linha.get(CONTA).comoTexto())
                    && linha.get(VALOR).tipo() == LeitoraXlsx.Tipo.NUMERO) {
                    soma = soma.add(linha.get(VALOR).numero());
                }
            }
        }
        return soma;
    }

    private static int quantasLinhasCom(LeitoraXlsx.Planilha planilha, String descricao) {
        int total = 0;
        for (LeitoraXlsx.Aba aba : planilha.abas()) {
            for (List<LeitoraXlsx.Celula> linha : aba.corpo()) {
                if (linha.size() > DESCRICAO && descricao.equals(linha.get(DESCRICAO).comoTexto())) {
                    total++;
                }
            }
        }
        return total;
    }

    /**
     * As linhas de uma pessoa no plastico dividido, em todo o arquivo.
     *
     * <p>Nao se conta por descricao: a compra alheia vem <b>mascarada</b>, e
     * procurar pelo texto acharia zero mesmo quando a linha esta la com valor e
     * data. O que a identifica e o par "Pago com" + "Quem".</p>
     */
    private static int linhasNoPlasticoDividido(LeitoraXlsx.Planilha planilha, String quem) {
        int total = 0;
        for (LeitoraXlsx.Aba aba : planilha.abas()) {
            for (List<LeitoraXlsx.Celula> linha : aba.corpo()) {
                if (linha.size() <= QUEM) {
                    continue;
                }
                String pagoCom = linha.get(PAGO_COM).comoTexto();
                if (pagoCom != null && pagoCom.contains("5678")
                    && quem.equals(linha.get(QUEM).comoTexto())) {
                    total++;
                }
            }
        }
        return total;
    }

    /** A fatura que recebeu as compras — a unica com total diferente de zero. */
    private String faturaComTotal() {
        List<Map<String, Object>> faturas = (List<Map<String, Object>>)
            get(tokenDono, "/api/cartoes/" + cartao + "/faturas?ano=" + HOJE.getYear())
                .getBody().get("faturas");

        return faturas.stream()
            .filter(f -> !"0.00".equals(f.get("total")))
            .map(f -> String.valueOf(f.get("id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Nenhuma fatura recebeu as compras"));
    }

    private String ambienteDele() {
        return String.valueOf(get(tokenDono, "/api/perfil").getBody().get("ambienteAtual"));
    }

    private String usuarioDela() {
        return String.valueOf(get(tokenConvidada, "/api/perfil").getBody().get("usuarioId"));
    }

    private String saldoDaContaConjunta() {
        return ((List<Map<String, Object>>) get(tokenDono, "/api/contas").getBody().get("contas"))
            .stream()
            .filter(c -> contaConjunta.equals(c.get("id")))
            .map(c -> String.valueOf(c.get("saldo")))
            .findFirst().orElseThrow();
    }

    // =========================================================================

    private void aceitarConvite(String token) {
        List<Map<String, Object>> convites = (List<Map<String, Object>>)
            get(token, "/api/convites").getBody().get("convites");
        assertFalse(convites.isEmpty(), "Nenhum convite pendente para aceitar");

        ResponseEntity<Map> r = post(token,
            "/api/convites/" + convites.get(0).get("id") + "/aceitar",
            Map.of("ambienteId", ambienteDela));
        assertEquals(HttpStatus.CREATED, r.getStatusCode(), "Aceite falhou: " + r.getBody());
    }

    private String criarConta(String token, String nome, String saldoInicial) {
        ResponseEntity<Map> r = post(token, "/api/contas", Map.of(
            "nome", nome,
            "natureza", "ATIVO",
            "saldoInicial", saldoInicial,
            "formasPagamento", List.of("DEBITO", "PIX", "CREDITO_EM_CONTA"),
            "padraoSaida", "DEBITO",
            "padraoEntrada", "CREDITO_EM_CONTA"));
        assertEquals(HttpStatus.CREATED, r.getStatusCode(), "Conta nao criada: " + r.getBody());
        return String.valueOf(r.getBody().get("id"));
    }

    private String criarCategoria(String token, String nome, String tipo) {
        ResponseEntity<Map> r = post(token, "/api/categorias", Map.of("nome", nome, "tipo", tipo));
        assertEquals(HttpStatus.CREATED, r.getStatusCode(),
            "Categoria \"" + nome + "\" nao foi criada: " + r.getBody());
        return String.valueOf(r.getBody().get("id"));
    }

    private void lancar(String token, String contaId, String categoriaId,
                        String valor, LocalDate data, String descricao) {
        ResponseEntity<Map> r = post(token, "/api/lancamentos", Map.of(
            "contaId", contaId,
            "categoriaId", categoriaId,
            "valor", valor,
            "dataCaixa", data.toString(),
            "descricao", descricao,
            "formaPagamento", "DEBITO"));
        assertEquals(HttpStatus.CREATED, r.getStatusCode(),
            "Lancamento \"" + descricao + "\" nao entrou: " + r.getBody());
    }

    private void comprar(String token, String contaId, String plasticoId,
                         String categoriaId, String valor, String descricao) {
        ResponseEntity<Map> r = post(token, "/api/lancamentos", Map.of(
            "contaId", contaId,
            "cartaoEmitidoId", plasticoId,
            "categoriaId", categoriaId,
            "valor", valor,
            "dataCaixa", HOJE.toString(),
            "descricao", descricao));
        assertEquals(HttpStatus.CREATED, r.getStatusCode(),
            "Compra \"" + descricao + "\" nao entrou: " + r.getBody());
    }

    private String cadastrarEEntrar(String nome, String email) {
        assertEquals(HttpStatus.CREATED, postSemToken("/api/auth/cadastro",
            Map.of("nome", nome, "email", email, "senha", SENHA)).getStatusCode());
        return String.valueOf(postSemToken("/api/auth/login",
            Map.of("email", email, "senha", SENHA)).getBody().get("tokenAcesso"));
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
