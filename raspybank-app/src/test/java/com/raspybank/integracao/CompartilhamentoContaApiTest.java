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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compartilhamento de CONTA (V16) pela porta da frente — §4k, B-D85 a B-D97.
 *
 * <p>O segundo modo, e a diferenca com o §4j e uma so: <b>ela trabalha no
 * ambiente dela</b>. As categorias sao dela, o mapa e dela, e a conta e dos
 * dois. A frase que resume (B-D85): <i>o saldo atravessa ambientes, a
 * classificacao nao</i>.</p>
 *
 * <h3>O que este teste guarda, alem dos codigos HTTP</h3>
 *
 * <ul>
 *   <li>que o aceite <b>escolhe o ambiente</b> (B-D90), e que antes dele a conta
 *       nao aparece para quem foi convidado;</li>
 *   <li>que os dois veem <b>o mesmo saldo</b> depois de ela lancar (B-D87) — o
 *       numero que confere contra o extrato do banco;</li>
 *   <li>que o extrato mostra o lancamento dela <b>sem descricao e sem
 *       categoria</b> (B-D89), e que o recorte vem do banco e nao da tela
 *       (B-D97);</li>
 *   <li>que o mapa de gastos de cada um continua <b>separado</b>, sem filtro
 *       novo (B-D85);</li>
 *   <li><b>o Achado 1</b>: ela nao renomeia, nao encerra, nao repassa e — o pior
 *       dos quatro — nao desvincula a conta do ambiente de quem a criou;</li>
 *   <li><b>o Achado 2</b>: revogar com lancamento dela existindo funciona, e os
 *       lancamentos <b>ficam</b>, porque aquele dinheiro saiu da conta de
 *       verdade (B-D93).</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CompartilhamentoContaApiTest extends IntegracaoTest {

    @Autowired
    private TestRestTemplate http;

    private static final String SENHA = "senha-com-mais-de-10";

    private static String emailDono;
    private static String emailConvidada;
    private static String emailTerceiro;

    private static UUID donoId;
    private static UUID convidadaId;

    private static String tokenDono;
    private static String tokenConvidada;
    private static String tokenTerceiro;

    private static String ambienteDoDono;
    private static String ambienteDaConvidada;

    /** A conta conjunta: nasce no ambiente do dono, com 1.000 de abertura. */
    private static String contaConjunta;

    private static String conviteId;

    /** O lancamento que ela faz na conta dele — o que a revogacao nao apaga. */
    private static String lancamentoDela;

    // =========================================================================
    // O convite
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Compartilhar cria convite PENDENTE, e a conta ainda NAO aparece para ela")
    void compartilharCriaConvitePendente() {
        prepararCenario();

        ResponseEntity<Map> r = post(tokenDono,
            "/api/contas/" + contaConjunta + "/compartilhamentos",
            Map.of("email", emailConvidada));
        assertEquals(HttpStatus.CREATED, r.getStatusCode());

        List<Map<String, Object>> lista = (List<Map<String, Object>>) r.getBody().get("compartilhamentos");
        assertEquals(1, lista.size());
        assertEquals("PENDENTE", lista.get(0).get("situacao"));

        // O nome dela aparece na lista do dono — e isso NAO sai por politica:
        // pol_usuario_leitura e "eu, e quem divide ambiente comigo", e ela nao
        // divide ambiente nenhum com ele. Sem app_compartilhamentos_da_conta a
        // lista mostraria um uuid.
        assertEquals("Luciana", lista.get(0).get("nome"));

        // E a conta continua invisivel para ela: o vinculo e o que o aceite vai
        // criar, e e ele que a politica de leitura exige.
        assertFalse(contasDe(tokenConvidada).stream()
                .anyMatch(c -> contaConjunta.equals(c.get("id"))),
            "A conta apareceu ANTES do aceite — o convite virou acesso, e B-D90 diz que nao");
    }

    @Test
    @Order(2)
    @DisplayName("O convite chega com o nome da conta e de quem ofereceu (a conta e invisivel, a funcao resolve)")
    void conviteChegaCompleto() {
        ResponseEntity<Map> r = get(tokenConvidada, "/api/convites");
        assertEquals(HttpStatus.OK, r.getStatusCode());

        List<Map<String, Object>> convites = (List<Map<String, Object>>) r.getBody().get("convites");
        assertEquals(1, convites.size());

        conviteId = (String) convites.get(0).get("id");

        Map<String, Object> conta = (Map<String, Object>) convites.get(0).get("conta");
        Map<String, Object> de = (Map<String, Object>) convites.get(0).get("de");

        // Sem estes dois nomes o convite seria "alguem quer dividir algo com
        // voce", que e um convite que ninguem aceita.
        assertEquals("Conta Conjunta", conta.get("nome"));
        assertEquals("Abner", de.get("nome"));
    }

    @Test
    @Order(3)
    @DisplayName("Aceitar ESCOLHENDO o ambiente (B-D90): a conta entra no ambiente dela")
    void aceitarEscolhendoOAmbiente() {
        ResponseEntity<Map> r = post(tokenConvidada,
            "/api/convites/" + conviteId + "/aceitar",
            Map.of("ambienteId", ambienteDaConvidada));
        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        assertEquals(contaConjunta, r.getBody().get("contaId"));

        Map<String, Object> conta = contasDe(tokenConvidada).stream()
            .filter(c -> contaConjunta.equals(c.get("id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("A conta aceita nao apareceu na lista dela"));

        // Os tres campos da V16, do lado de quem recebeu.
        assertEquals(false, conta.get("origem"),
            "A conta nao nasceu no ambiente dela — origem tem de ser falsa (B-D92)");
        assertEquals(false, conta.get("podeCompartilhar"),
            "Quem recebe nao repassa (B-D91)");
        assertEquals("Abner", conta.get("recebidaDe"));

        // O saldo dele ja atravessa: 1.000 de abertura menos o cafe de 10.
        assertEquals("990.00", conta.get("saldo"));

        // E o convite desapareceu (B-D94): a verdade agora e o vinculo.
        assertTrue(((List<?>) get(tokenConvidada, "/api/convites").getBody().get("convites")).isEmpty());
    }

    @Test
    @Order(4)
    @DisplayName("O dono ve a conta marcada como compartilhada, e continua dono da porta")
    void donoVeAContaCompartilhada() {
        Map<String, Object> conta = contaDoDono();

        assertEquals(true, conta.get("origem"));
        assertEquals(true, conta.get("podeCompartilhar"));
        assertEquals(true, conta.get("compartilhada"),
            "Sem esta marca, o saldo maior que a soma dos lancamentos visiveis pareceria erro");
        assertNull(conta.get("recebidaDe"));

        List<Map<String, Object>> lista = (List<Map<String, Object>>)
            get(tokenDono, "/api/contas/" + contaConjunta + "/compartilhamentos")
                .getBody().get("compartilhamentos");

        assertEquals(1, lista.size());
        assertEquals("ATIVO", lista.get(0).get("situacao"));

        // O ambiente dela NAO viaja na resposta (B-D90): em qual ambiente ela
        // guardou a conta e organizacao da vida dela.
        assertFalse(lista.get(0).containsKey("ambienteId"),
            "O ambiente dela vazou para o dono — B-D90 recusou expor isso");
    }

    // =========================================================================
    // O saldo atravessa; a classificacao nao (B-D85)
    // =========================================================================

    @Test
    @Order(5)
    @DisplayName("Ela lanca com categoria DELA, e os dois passam a ver o MESMO saldo (B-D87)")
    void elaLancaEOSaldoAtravessa() {
        String categoriaDela = categoriaPropria(tokenConvidada, "Presentes dela");

        ResponseEntity<Map> r = post(tokenConvidada, "/api/lancamentos", Map.of(
            "contaId", contaConjunta,
            "categoriaId", categoriaDela,
            "valor", "250.00",
            "dataCaixa", "2026-07-15",
            "descricao", "presente da Luciana"));
        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        lancamentoDela = (String) r.getBody().get("id");

        // 990 - 250. O dono NAO ve o lancamento dela pela politica — e mesmo
        // assim tem de somar. E o impasse de B-D87, e o motivo da quarta
        // excecao de B-D19.
        assertEquals("740.00", contaDoDono().get("saldo"),
            "O saldo do dono nao somou o gasto dela — os dois veriam numeros diferentes"
                + " na mesma conta, cada um conferindo o proprio contra o mesmo banco");

        assertEquals("740.00", contaDaConvidada().get("saldo"));
    }

    @Test
    @Order(6)
    @DisplayName("O extrato do dono mostra a linha dela SEM descricao e SEM categoria (B-D89)")
    void extratoRecortaOLancamentoAlheio() {
        List<Map<String, Object>> linhas = extrato(tokenDono, "2026-07");

        Map<String, Object> dela = linhas.stream()
            .filter(l -> lancamentoDela.equals(l.get("id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "O lancamento dela nao apareceu no extrato do dono — o extrato nao atravessou"));

        assertEquals(false, dela.get("meu"));
        assertEquals("250.00", dela.get("valor"));
        assertEquals("Luciana", ((Map<String, Object>) dela.get("quem")).get("nome"));

        // O ponto da secao. "presente da Luciana" e exatamente o caso que B-D89
        // usou como exemplo, e ele nao pode estar aqui.
        assertNull(dela.get("descricao"),
            "A descricao do lancamento alheio vazou — e ela e onde as pessoas escrevem"
                + " o que nao pretendiam dividir");
        assertNull(dela.get("categoria"),
            "A categoria do lancamento alheio vazou — a classificacao nao atravessa (B-D85)");

        // E a linha dele continua completa.
        Map<String, Object> dele = linhas.stream()
            .filter(l -> Boolean.TRUE.equals(l.get("meu")))
            .findFirst()
            .orElseThrow();
        assertNotNull(dele.get("categoria"));
    }

    @Test
    @Order(7)
    @DisplayName("O recorte vem do BANCO, nao da tela (B-D97): a funcao nao devolve as colunas")
    void oRecorteVemDoBanco() throws SQLException {
        try (Connection c = comoApp()) {
            assumirIdentidade(c, donoId);

            try (PreparedStatement ps = c.prepareStatement("""
                     SELECT meu, descricao, categoria_id, quem_nome
                       FROM app_extrato_da_conta(?, '2026-07-01', '2026-07-31')
                      WHERE id = ?""")) {
                ps.setObject(1, UUID.fromString(contaConjunta));
                ps.setObject(2, UUID.fromString(lancamentoDela));

                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertFalse(rs.getBoolean("meu"));
                    assertNull(rs.getString("descricao"),
                        "A funcao devolveu a descricao alheia. Filtrar no Java depois seria"
                            + " uma linha de codigo entre o dado e o vazamento");
                    assertNull(rs.getString("categoria_id"));
                    assertEquals("Luciana", rs.getString("quem_nome"));
                }
            }
        }
    }

    @Test
    @Order(8)
    @DisplayName("O mapa de gastos de cada um continua SEPARADO, sem filtro novo (B-D85)")
    void oMapaContinuaSeparado() {
        // A separacao cai da estrutura: o mapa recorta por ambiente, e o
        // lancamento dela tem categoria do ambiente DELA. Nenhuma regra escrita.
        String mapaDoDono = get(tokenDono, "/api/relatorios/mapa-de-gastos?ano=2026").getBody().toString();
        assertFalse(mapaDoDono.contains("250.00"),
            "O gasto dela entrou no mapa do dono — a classificacao atravessou, e nao devia");
    }

    // =========================================================================
    // O ACHADO 1 — a convidada nao toma a conta
    // =========================================================================

    @Test
    @Order(9)
    @DisplayName("Ela nao renomeia nem encerra a conta emprestada (403) — B-D95")
    void elaNaoMexeNoCadastroDaConta() {
        assertEquals(HttpStatus.FORBIDDEN,
            put(tokenConvidada, "/api/contas/" + contaConjunta, Map.of("nome", "Minha agora"))
                .getStatusCode());

        assertEquals(HttpStatus.FORBIDDEN,
            post(tokenConvidada, "/api/contas/" + contaConjunta + "/encerrar", Map.of())
                .getStatusCode());

        assertEquals("Conta Conjunta", contaDoDono().get("nome"),
            "O nome mudou. A conta aparece na tela de duas pessoas, e quem a abriu responde por ela");
    }

    @Test
    @Order(10)
    @DisplayName("Ela nao repassa a conta a um terceiro (403) — B-D91")
    void elaNaoRepassa() {
        ResponseEntity<Map> r = post(tokenConvidada,
            "/api/contas/" + contaConjunta + "/compartilhamentos",
            Map.of("email", emailTerceiro));
        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
    }

    @Test
    @Order(11)
    @DisplayName("Ela nao desvincula a conta do ambiente do DONO — o pior caso do Achado 1")
    void elaNaoDesvinculaDoDono() throws SQLException {
        // Pela API nao ha caminho; o teste vai ao banco porque e la que o furo
        // existia. Sem a coluna origem, app_contas_proprias devolvia a conta
        // DELE para ela — ela e dona do ambiente dela, e a conta esta ligada a
        // ele — e pol_ca_encerrar deixava a linha de origem ser encerrada.
        try (Connection c = comoApp()) {
            assumirIdentidade(c, convidadaId);

            try (PreparedStatement ps = c.prepareStatement("""
                     UPDATE conta_ambiente SET encerrado_em = now()
                      WHERE conta_id = ? AND origem""")) {
                ps.setObject(1, UUID.fromString(contaConjunta));
                assertEquals(0, ps.executeUpdate(),
                    "Ela encerrou o vinculo de ORIGEM: a conta desapareceria para quem a criou");
            }

            // E nem promove o proprio vinculo a origem. Aqui a recusa e mais
            // ruidosa que a de cima, e de proposito: pol_ca_sair alcanca a linha
            // dela pelo USING, entao a barreira e o WITH CHECK — que levanta erro
            // em vez de simplesmente nao encontrar linha.
            try (PreparedStatement ps = c.prepareStatement("""
                     UPDATE conta_ambiente SET origem = true
                      WHERE conta_id = ? AND ambiente_id = ?""")) {
                ps.setObject(1, UUID.fromString(contaConjunta));
                ps.setObject(2, UUID.fromString(ambienteDaConvidada));

                try {
                    ps.executeUpdate();
                    throw new AssertionError(
                        "Ela promoveu o proprio vinculo a origem, e viraria dona da conta alheia");
                } catch (SQLException esperado) {
                    assertTrue(esperado.getMessage().contains("row-level security"),
                        "Falhou por outro motivo: " + esperado.getMessage());
                }
            }
        }

        // O dono continua vendo a conta dele.
        assertNotNull(contaDoDono());
    }

    // =========================================================================
    // Os erros do contrato (§2d)
    // =========================================================================

    @Test
    @Order(12)
    @DisplayName("Compartilhar de novo com a mesma pessoa responde 409")
    void compartilharDeNovo() {
        assertEquals(HttpStatus.CONFLICT,
            post(tokenDono, "/api/contas/" + contaConjunta + "/compartilhamentos",
                Map.of("email", emailConvidada)).getStatusCode());
    }

    @Test
    @Order(13)
    @DisplayName("Compartilhar consigo mesmo responde 409 dizendo que a conta ja e sua")
    void compartilharConsigoMesmo() {
        assertEquals(HttpStatus.CONFLICT,
            post(tokenDono, "/api/contas/" + contaConjunta + "/compartilhamentos",
                Map.of("email", emailDono)).getStatusCode());
    }

    @Test
    @Order(14)
    @DisplayName("Os DOIS acessos convivem: quem esta no ambiente tambem pode receber a conta")
    void osDoisAcessosConvivem() {
        // O terceiro entra no ambiente do dono pelo §2c: dali ele ve a conta, com
        // as categorias do dono e no mapa do dono.
        assertEquals(HttpStatus.CREATED,
            post(tokenDono, "/api/ambientes/" + ambienteDoDono + "/acessos",
                Map.of("email", emailTerceiro)).getStatusCode());

        // E ainda assim recebe a conta no ambiente DELE. Nao e redundancia: ver a
        // conta de dentro do ambiente de outra pessoa e uma coisa; ter a conta no
        // proprio ambiente, com as proprias categorias e no proprio mapa, e outra.
        // O §4k chama o segundo modo de "complementar" ao primeiro.
        assertEquals(HttpStatus.CREATED,
            post(tokenDono, "/api/contas/" + contaConjunta + "/compartilhamentos",
                Map.of("email", emailTerceiro)).getStatusCode());

        String convite = (String) ((List<Map<String, Object>>)
            get(tokenTerceiro, "/api/convites").getBody().get("convites")).get(0).get("id");

        String ambienteDoTerceiro = String.valueOf(
            get(tokenTerceiro, "/api/perfil").getBody().get("ambienteAtual"));

        assertEquals(HttpStatus.CREATED,
            post(tokenTerceiro, "/api/convites/" + convite + "/aceitar",
                Map.of("ambienteId", ambienteDoTerceiro)).getStatusCode());

        // E agora o ponto fino, que o teste existe para guardar: a MESMA conta
        // responde diferente em cada ambiente dele, e as duas respostas estao
        // certas.
        //
        // No ambiente DELE a conta e emprestada: so lanca (B-D95).
        Map<String, Object> noAmbienteDele = contasDe(tokenTerceiro).stream()
            .filter(c -> contaConjunta.equals(c.get("id")))
            .findFirst().orElseThrow();
        assertEquals(false, noAmbienteDele.get("origem"));
        assertEquals("Abner", noAmbienteDele.get("recebidaDe"));

        assertEquals(HttpStatus.FORBIDDEN,
            put(tokenTerceiro, "/api/contas/" + contaConjunta, Map.of("nome", "Minha"))
                .getStatusCode());

        // No ambiente do DONO, onde ele entrou por convite, a mesma conta e
        // dinheiro: renomear vale, porque ali ele esta dentro (B-D76). Trocar a
        // sessao de ambiente muda a resposta, e e assim que deve ser.
        String tokenNoAmbienteDoDono = (String) post(tokenTerceiro,
            "/api/sessao/ambiente", Map.of("ambienteId", ambienteDoDono))
            .getBody().get("tokenAcesso");

        Map<String, Object> noAmbienteDoDono = ((List<Map<String, Object>>)
            get(tokenNoAmbienteDoDono, "/api/contas").getBody().get("contas")).stream()
            .filter(c -> contaConjunta.equals(c.get("id")))
            .findFirst().orElseThrow();
        assertEquals(true, noAmbienteDoDono.get("origem"));
        assertNull(noAmbienteDoDono.get("recebidaDe"));

        // Mas a PORTA continua fechada nos dois lados (B-D91): quem entrou no
        // ambiente usa a conta e nao a passa adiante.
        assertEquals(false, noAmbienteDoDono.get("podeCompartilhar"));
        assertEquals(false, noAmbienteDele.get("podeCompartilhar"));
    }

    @Test
    @Order(15)
    @DisplayName("E-mail nao cadastrado 404 (B-D81); malformado 400")
    void errosDeEmail() {
        assertEquals(HttpStatus.NOT_FOUND,
            post(tokenDono, "/api/contas/" + contaConjunta + "/compartilhamentos",
                Map.of("email", "ninguem-" + UUID.randomUUID() + "@teste.local")).getStatusCode());

        assertEquals(HttpStatus.BAD_REQUEST,
            post(tokenDono, "/api/contas/" + contaConjunta + "/compartilhamentos",
                Map.of("email", "isto-nao-e-um-email")).getStatusCode());
    }

    @Test
    @Order(16)
    @DisplayName("403 para quem ve a conta e nao tem a porta, nos DOIS ambientes")
    void terceiroNaoVeCompartilhamentos() {
        // Depois do teste anterior ele tem a conta nos dois lugares — e em nenhum
        // dos dois ele reparte acesso (B-D91). Do ambiente dele a conta e
        // emprestada; do ambiente do dono ele e convidado. As duas respostas sao
        // 403 com frase, e nao 404: para quem esta vendo a conta na tela,
        // "inexistente" seria mentira.
        assertEquals(HttpStatus.FORBIDDEN,
            get(tokenTerceiro, "/api/contas/" + contaConjunta + "/compartilhamentos").getStatusCode());

        // Dentro do ambiente do dono (onde ele entrou no teste 14) a conta esta
        // na tela dele, e ai a resposta muda: 403 com frase. Para quem esta
        // vendo a conta, "inexistente" seria mentira.
        String tokenNoAmbienteDoDono = (String) post(tokenTerceiro,
            "/api/sessao/ambiente", Map.of("ambienteId", ambienteDoDono))
            .getBody().get("tokenAcesso");

        assertEquals(HttpStatus.FORBIDDEN,
            get(tokenNoAmbienteDoDono, "/api/contas/" + contaConjunta + "/compartilhamentos")
                .getStatusCode());
    }

    @Test
    @Order(17)
    @DisplayName("Aceitar num ambiente que nao e seu responde 404 (B-D25)")
    void aceitarEmAmbienteAlheio() {
        // Convite novo, para o terceiro nao — para ela, num ambiente do DONO.
        assertEquals(HttpStatus.CREATED,
            post(tokenDono, "/api/contas/" + contaSoDoDono() + "/compartilhamentos",
                Map.of("email", emailConvidada)).getStatusCode());

        String convite = (String) ((List<Map<String, Object>>)
            get(tokenConvidada, "/api/convites").getBody().get("convites")).get(0).get("id");

        assertEquals(HttpStatus.NOT_FOUND,
            post(tokenConvidada, "/api/convites/" + convite + "/aceitar",
                Map.of("ambienteId", ambienteDoDono)).getStatusCode());

        // Recusar apaga o convite (B-D94).
        assertEquals(HttpStatus.NO_CONTENT,
            delete(tokenConvidada, "/api/convites/" + convite).getStatusCode());
        assertTrue(((List<?>) get(tokenConvidada, "/api/convites").getBody().get("convites")).isEmpty());
    }

    // =========================================================================
    // O ACHADO 2 — revogar com lancamento dela existindo
    // =========================================================================

    @Test
    @Order(18)
    @DisplayName("O DELETE do vinculo e recusado pelo banco a partir do primeiro lancamento dela")
    void deleteDoVinculoEhRecusado() throws SQLException {
        try (Connection c = comoProprietario();
             PreparedStatement ps = c.prepareStatement("""
                 DELETE FROM conta_ambiente WHERE conta_id = ? AND NOT origem""")) {
            ps.setObject(1, UUID.fromString(contaConjunta));

            try {
                ps.executeUpdate();
                throw new AssertionError(
                    "O DELETE passou. fk_lancamento_conta deveria ser RESTRICT — e e por isso"
                        + " que a revogacao de B-D93 e logica e nao fisica");
            } catch (SQLException esperado) {
                assertTrue(esperado.getMessage().contains("fk_lancamento_conta"),
                    "Falhou por outro motivo: " + esperado.getMessage());
            }
        }
    }

    @Test
    @Order(19)
    @DisplayName("Revogar funciona, os lancamentos dela FICAM, e o saldo do dono nao muda (B-D93)")
    void revogarNaoApagaOLancamento() {
        assertEquals(HttpStatus.NO_CONTENT,
            delete(tokenDono,
                "/api/contas/" + contaConjunta + "/compartilhamentos/" + convidadaId)
                .getStatusCode());

        // Ela perde a conta de vista na hora.
        assertFalse(contasDe(tokenConvidada).stream()
                .anyMatch(c -> contaConjunta.equals(c.get("id"))),
            "A conta continuou aparecendo para ela depois da revogacao");

        // E o saldo do dono continua incluindo o gasto dela — porque aquele
        // dinheiro saiu da conta de verdade. Apagar faria o saldo divergir do
        // extrato do banco, que e o sintoma que P1/R1 existem para evitar.
        assertEquals("740.00", contaDoDono().get("saldo"),
            "O saldo do dono mudou na revogacao: o dinheiro dela evaporou do historico");

        // O lancamento dela continua no extrato da conta, e continua recortado.
        assertTrue(extrato(tokenDono, "2026-07").stream()
                .anyMatch(l -> lancamentoDela.equals(l.get("id"))),
            "O lancamento dela desapareceu do extrato — o saldo e o extrato deixaram de bater");

        // Revogar UMA pessoa nao mexe nas outras: o terceiro, que recebeu a conta
        // no teste 14, continua com ela — e por isso a conta continua marcada como
        // dividida. Cada concessao e um ato proprio.
        List<Map<String, Object>> restantes = (List<Map<String, Object>>)
            get(tokenDono, "/api/contas/" + contaConjunta + "/compartilhamentos")
                .getBody().get("compartilhamentos");

        assertFalse(restantes.stream()
                .anyMatch(c -> convidadaId.toString().equals(c.get("usuarioId"))),
            "A revogada continuou na lista de compartilhamentos");
        assertEquals(1, restantes.size(), "A revogacao de uma pessoa atingiu a outra");
        assertEquals(true, contaDoDono().get("compartilhada"));
    }

    @Test
    @Order(20)
    @DisplayName("Revogar quem nao tem a conta responde 404; e o convite volta a ser possivel")
    void revogarDuasVezes() {
        assertEquals(HttpStatus.NOT_FOUND,
            delete(tokenDono,
                "/api/contas/" + contaConjunta + "/compartilhamentos/" + convidadaId)
                .getStatusCode());

        // Reconvidar depois de revogar: o vinculo antigo esta encerrado, e o
        // aceite o REABRE em vez de inserir outro (ON CONFLICT na funcao) — sem
        // isso, os lancamentos antigos dela ficariam orfaos de vinculo.
        assertEquals(HttpStatus.CREATED,
            post(tokenDono, "/api/contas/" + contaConjunta + "/compartilhamentos",
                Map.of("email", emailConvidada)).getStatusCode());

        String convite = (String) ((List<Map<String, Object>>)
            get(tokenConvidada, "/api/convites").getBody().get("convites")).get(0).get("id");

        assertEquals(HttpStatus.CREATED,
            post(tokenConvidada, "/api/convites/" + convite + "/aceitar",
                Map.of("ambienteId", ambienteDaConvidada)).getStatusCode());

        // E o lancamento antigo dela continua la, no ambiente dela, com a
        // categoria dela.
        assertEquals("740.00", contaDaConvidada().get("saldo"));
    }

    @Test
    @Order(21)
    @DisplayName("Ela sai da conta por conta propria (B-D77 no idioma da conta)")
    void elaSaiSozinha() {
        assertEquals(HttpStatus.NO_CONTENT,
            delete(tokenConvidada,
                "/api/contas/" + contaConjunta + "/compartilhamentos/" + convidadaId)
                .getStatusCode());

        assertFalse(contasDe(tokenConvidada).stream()
            .anyMatch(c -> contaConjunta.equals(c.get("id"))));
    }

    // =========================================================================
    // Cenario
    // =========================================================================

    private void prepararCenario() {
        if (contaConjunta != null) {
            return;
        }

        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        emailDono      = "abner-conta-" + sufixo + "@teste.local";
        emailConvidada = "luciana-conta-" + sufixo + "@teste.local";
        emailTerceiro  = "terceiro-conta-" + sufixo + "@teste.local";

        tokenDono      = cadastrarEEntrar("Abner", emailDono);
        tokenConvidada = cadastrarEEntrar("Luciana", emailConvidada);
        tokenTerceiro  = cadastrarEEntrar("Marina", emailTerceiro);

        Map perfilDono = get(tokenDono, "/api/perfil").getBody();
        donoId         = UUID.fromString(String.valueOf(perfilDono.get("usuarioId")));
        ambienteDoDono = String.valueOf(perfilDono.get("ambienteAtual"));

        Map perfilConvidada = get(tokenConvidada, "/api/perfil").getBody();
        convidadaId         = UUID.fromString(String.valueOf(perfilConvidada.get("usuarioId")));
        ambienteDaConvidada = String.valueOf(perfilConvidada.get("ambienteAtual"));

        // A conta conjunta nasce no ambiente dele, com 1.000 de abertura — o
        // saldo que ela vai ver do outro lado.
        contaConjunta = (String) post(tokenDono, "/api/contas", Map.of(
            "nome", "Conta Conjunta",
            "natureza", "ATIVO",
            "saldoInicial", "1000.00")).getBody().get("id");

        // Um lancamento dele, para o extrato ter as duas linhas. Ele nasce
        // REALIZADO porque a data e passada (B-D9), e por isso entra no saldo.
        assertEquals(HttpStatus.CREATED, post(tokenDono, "/api/lancamentos", Map.of(
            "contaId", contaConjunta,
            "categoriaId", categoriaPropria(tokenDono, "Cafe dele"),
            "valor", "10.00",
            "dataCaixa", "2026-07-10",
            "descricao", "Cafe dele")).getStatusCode());
    }

    /** Uma segunda conta do dono, para o teste de aceite em ambiente alheio. */
    private String contaSoDoDono() {
        return (String) post(tokenDono, "/api/contas", Map.of(
            "nome", "Outra Dele " + UUID.randomUUID().toString().substring(0, 4),
            "natureza", "ATIVO")).getBody().get("id");
    }

    private Map<String, Object> contaDoDono() {
        return contasDe(tokenDono).stream()
            .filter(c -> contaConjunta.equals(c.get("id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("A conta desapareceu para o dono"));
    }

    private Map<String, Object> contaDaConvidada() {
        return contasDe(tokenConvidada).stream()
            .filter(c -> contaConjunta.equals(c.get("id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("A conta desapareceu para a convidada"));
    }

    private List<Map<String, Object>> contasDe(String token) {
        return (List<Map<String, Object>>) get(token, "/api/contas").getBody().get("contas");
    }

    private List<Map<String, Object>> extrato(String token, String mes) {
        ResponseEntity<Map> r = get(token, "/api/contas/" + contaConjunta + "/extrato?mes=" + mes);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        return (List<Map<String, Object>>) r.getBody().get("lancamentos");
    }

    /**
     * Uma categoria de gasto propria, criada no ambiente de quem chama.
     *
     * <p>Nenhuma sistemica serve aqui, e vale registrar por que: TRANSFERENCIA e
     * PAGAMENTO_FATURA recusam lancamento avulso (B-D42), e AJUSTE e
     * {@code AMBOS}, o que tornaria o campo {@code tipo} obrigatorio. Criar uma
     * categoria normal e mais curto que escolher entre as excecoes.</p>
     *
     * <p>E ela e do ambiente de cada um de proposito: e disso que B-D85 trata —
     * a classificacao nao atravessa.</p>
     */
    private String categoriaPropria(String token, String nome) {
        return (String) post(token, "/api/categorias",
            Map.of("nome", nome, "tipo", "SAIDA")).getBody().get("id");
    }

    private String cadastrarEEntrar(String nome, String email) {
        assertEquals(HttpStatus.CREATED, postSemToken("/api/auth/cadastro",
            Map.of("nome", nome, "email", email, "senha", SENHA)).getStatusCode());

        return (String) postSemToken("/api/auth/login",
            Map.of("email", email, "senha", SENHA)).getBody().get("tokenAcesso");
    }

    private Connection comoProprietario() throws SQLException {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private Connection comoApp() throws SQLException {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), PostgresDeTeste.USUARIO_APP, PostgresDeTeste.SENHA_APP);
    }

    private void assumirIdentidade(Connection c, UUID usuario) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT set_config('raspybank.usuario_id', ?, false)")) {
            ps.setString(1, usuario.toString());
            ps.executeQuery().close();
        }
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
