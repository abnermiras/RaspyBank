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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O nome da aba, que o usuario escreve e o formato limita.
 *
 * <p>O campo {@code nome} do ambiente aceita 60 caracteres e qualquer texto; um
 * nome de aba no {@code .xlsx} aceita 31 e recusa {@code [ ] : * ? / \}. Entre
 * os dois ha um saneamento, e saneamento e onde nasce a colisao: dois nomes
 * diferentes podem virar o mesmo depois do corte, e o formato compara nomes de
 * aba <b>sem distinguir caixa</b>.</p>
 *
 * <h3>Por que a asserção que importa e a CONTAGEM</h3>
 *
 * <p>Duas abas com o mesmo nome nao dao erro visivel: ou o arquivo nao abre, ou
 * uma sobrescreve a outra em silencio — e quem baixou fica com um ambiente
 * inteiro a menos sem nenhum aviso. Por isso cada teste daqui confere que o
 * numero de abas continua sendo <b>a capa mais um por ambiente</b>.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExtratoCompletoNomeDeAbaTest extends IntegracaoTest {

    @Autowired
    private TestRestTemplate http;

    private static final String SENHA = "senha-com-mais-de-10";

    /** Proibidos pelo formato dentro de um nome de aba. */
    private static final String PROIBIDOS = "[]:*?/\\";

    private static final LocalDate HOJE = LocalDate.now();

    private static String token;
    private static int ambientes;

    @Test
    @Order(1)
    @DisplayName("Nome com [ ] : * ? / \\ vira aba legivel, e o arquivo continua abrindo")
    void caracteresProibidosSaemDoNomeDaAba() {
        autenticar();

        criarAmbiente("Casa/Trabalho: 2026 *tudo* [x]?");

        LeitoraXlsx.Planilha planilha = baixar();
        conferirFormaDosNomes(planilha);

        assertTrue(planilha.nomes().stream().anyMatch(n -> n.startsWith("Casa Trabalho")),
            "O ambiente com caractere proibido nao virou aba nenhuma: " + planilha.nomes());
    }

    @Test
    @Order(2)
    @DisplayName("Nome que se esvazia no saneamento ainda vira aba — duas vezes, sem colidir")
    void nomeQueSeEsvaziaAindaViraAba() {
        // "///" some inteiro no saneamento. Um nome vazio nao e nome de aba
        // valido, e dois deles seriam o MESMO nome vazio.
        criarAmbiente("///");
        criarAmbiente("[]");

        conferirFormaDosNomes(baixar());
    }

    @Test
    @Order(3)
    @DisplayName("Dois ambientes que so diferem na caixa nao viram uma aba so")
    void nomesQueSoDiferemNaCaixaNaoColidem() {
        // O formato .xlsx compara nome de aba SEM distinguir caixa: "Vida" e
        // "VIDA" sao o mesmo nome para ele. Um desempate que compare com equals
        // passaria aqui e produziria um arquivo que nao abre.
        criarAmbiente("Vida");
        criarAmbiente("VIDA");

        LeitoraXlsx.Planilha planilha = baixar();
        conferirFormaDosNomes(planilha);

        assertEquals(2, planilha.nomes().stream()
                .filter(n -> n.toLowerCase(Locale.ROOT).startsWith("vida"))
                .count(),
            "\"Vida\" e \"VIDA\" viraram menos de duas abas: " + planilha.nomes());
    }

    @Test
    @Order(4)
    @DisplayName("Um ambiente chamado \"Sobre este arquivo\" nao rouba o lugar da capa")
    void ambienteComONomeDaCapaNaoSobrescreveACapa() {
        // A capa e uma aba como as outras, e o nome dela nao e reservado em
        // lugar nenhum do sistema: nada impede a pessoa de batizar um ambiente
        // assim. Se o desempate nao contar a capa, o ambiente sobrescreve os
        // avisos — inclusive o que explica a linha mascarada.
        criarAmbiente("Sobre este arquivo");

        LeitoraXlsx.Planilha planilha = baixar();
        conferirFormaDosNomes(planilha);

        assertEquals("Sobre este arquivo", planilha.nomes().get(0),
            "A capa deixou de ser a primeira aba");

        LeitoraXlsx.Aba capa = planilha.abas().get(0);
        assertTrue(capa.linhas().stream()
                .flatMap(List::stream)
                .anyMatch(c -> c.comoTexto() != null && c.comoTexto().contains("DESCRIÇÃO")),
            "A primeira aba nao e mais a capa: o ambiente de mesmo nome tomou o"
                + " lugar dos avisos, e a explicacao da linha mascarada sumiu");
    }

    @Test
    @Order(5)
    @DisplayName("Nome de 60 caracteres cabe nos 31 da aba sem perder o ambiente")
    void nomeNoLimiteDoCampoCabeNaAba() {
        // 60 e o maximo que POST /api/ambientes aceita. E o pior caso real.
        criarAmbiente("a".repeat(60));
        criarAmbiente("a".repeat(59) + "b");

        conferirFormaDosNomes(baixar());
    }

    @Test
    @Order(6)
    @DisplayName("Nome com emoji e acento sobrevive ao corte sem quebrar o arquivo")
    void nomeComEmojiEAcentoNaoQuebraOArquivo() {
        // Um emoji e um par surrogate em Java: cortar em 31 CHARS pode partir o
        // par ao meio e produzir XML invalido — o arquivo inteiro deixa de abrir
        // por causa do nome de uma aba.
        criarAmbiente("Viagem 🏖️ para a praia com a família toda em 2026");

        conferirFormaDosNomes(baixar());
    }

    @Test
    @Order(7)
    @DisplayName("Emoji exatamente no corte dos 31 nao parte o par ao meio")
    void emojiNaJuncaoDoCorteNaoQuebraOArquivo() {
        // Um emoji ocupa DOIS chars em Java. Aqui os 30 primeiros sao comuns e o
        // par comeca no char 30 — entao um substring(0, 31) leva metade dele.
        // Meio par surrogate nao e caractere XML valido, e o arquivo INTEIRO
        // deixa de abrir por causa do nome de uma aba. Nao e hipotese: e o modo
        // classico de quebrar corte de string em Java.
        String trinta = "Ferias no litoral em familia e";
        assertEquals(30, trinta.length(), "O cenario depende do tamanho exato");

        criarAmbiente(trinta + "\uD83C\uDFD6 na praia");

        LeitoraXlsx.Planilha planilha = baixar();
        conferirFormaDosNomes(planilha);

        for (String nome : planilha.nomes()) {
            assertFalse(temSurrogateSolto(nome),
                "A aba \"" + nome + "\" terminou com meio emoji: o par foi partido"
                    + " no corte dos 31 caracteres");
        }
    }

    @Test
    @Order(8)
    @DisplayName("Dois ambientes que so diferem no que o escritor descarta nao viram uma aba so")
    void colisaoDepoisDoQueOEscritorDescartaNaoPodeAcontecer() {
        // O teste anterior deixou "Ferias no litoral em familia e🏖 na praia", que
        // e cortado em 31 chars e fica com METADE do emoji na ponta. O escritor
        // descarta esse meio caractere — ele nao e XML valido —, e a aba nasce
        // com os 30 chars que sobraram.
        //
        // Este ambiente tem exatamente esses 30 caracteres. Os dois nomes sao
        // DIFERENTES quando o desempate os compara, e IGUAIS quando chegam no
        // arquivo: o desempate confere um texto que nao e o que vai ser gravado.
        //
        // Duas abas de mesmo nome nao avisam. Ou o arquivo nao abre, ou um
        // ambiente inteiro some do extrato de quem baixou.
        criarAmbiente("Ferias no litoral em familia e");

        conferirFormaDosNomes(baixar());
    }

    /** Um surrogate sem o par e o que faz o XML — e o arquivo inteiro — cair. */
    private static boolean temSurrogateSolto(String texto) {
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (Character.isHighSurrogate(c)
                && (i + 1 >= texto.length() || !Character.isLowSurrogate(texto.charAt(i + 1)))) {
                return true;
            }
            if (Character.isLowSurrogate(c)
                && (i == 0 || !Character.isHighSurrogate(texto.charAt(i - 1)))) {
                return true;
            }
        }
        return false;
    }

    // =========================================================================

    /**
     * O contrato do nome de aba, conferido no arquivo inteiro de uma vez.
     *
     * <p>Nao ha "o nome esperado" para comparar — o saneamento tem liberdade de
     * escolher. O que ele nao tem liberdade de fazer e produzir nome vazio,
     * maior que 31, com caractere proibido, repetido sem distinguir caixa, ou
     * <b>a menos</b>.</p>
     */
    private void conferirFormaDosNomes(LeitoraXlsx.Planilha planilha) {

        assertEquals(ambientes + 1, planilha.nomes().size(),
            "O arquivo tem " + planilha.nomes().size() + " abas para " + ambientes
                + " ambientes mais a capa. Duas abas colidiram e uma sumiu, levando"
                + " um ambiente inteiro com ela: " + planilha.nomes());

        Set<String> vistos = new HashSet<>();
        for (String nome : planilha.nomes()) {

            assertFalse(nome.isBlank(), "Aba com nome vazio: " + planilha.nomes());

            assertTrue(nome.length() <= 31,
                "Aba com " + nome.length() + " caracteres (\"" + nome + "\"): o formato"
                    + " admite 31, e a leitora recusa o arquivo inteiro");

            for (char proibido : PROIBIDOS.toCharArray()) {
                assertFalse(nome.indexOf(proibido) >= 0,
                    "Aba com o caractere proibido '" + proibido + "': \"" + nome + "\"");
            }

            assertTrue(vistos.add(nome.toLowerCase(Locale.ROOT)),
                "Duas abas com o mesmo nome (o formato ignora a caixa): \"" + nome
                    + "\" em " + planilha.nomes());
        }
    }

    private LeitoraXlsx.Planilha baixar() {
        ResponseEntity<byte[]> r = http.exchange(
            "/api/relatorios/extrato.xlsx?inicio=" + HOJE.minusMonths(1) + "&fim=" + HOJE,
            HttpMethod.GET, new HttpEntity<>(cabecalhos()), byte[].class);

        assertEquals(HttpStatus.OK, r.getStatusCode(),
            "O extrato nao veio: " + new String(r.getBody() == null ? new byte[0] : r.getBody()));
        return LeitoraXlsx.ler(r.getBody());
    }

    private void criarAmbiente(String nome) {
        ResponseEntity<Map> r = http.postForEntity("/api/ambientes",
            new HttpEntity<>(Map.of("nome", nome), cabecalhos()), Map.class);

        assertEquals(HttpStatus.CREATED, r.getStatusCode(),
            "Ambiente \"" + nome + "\" nao foi criado: " + r.getBody());
        ambientes++;
    }

    private void autenticar() {
        if (token != null) {
            return;
        }
        String email = "zelia-aba-" + UUID.randomUUID().toString().substring(0, 8)
            + "@teste.local";

        assertEquals(HttpStatus.CREATED, http.postForEntity("/api/auth/cadastro",
            new HttpEntity<>(Map.of("nome", "Zelia", "email", email, "senha", SENHA), json()),
            Map.class).getStatusCode());

        token = String.valueOf(http.postForEntity("/api/auth/login",
            new HttpEntity<>(Map.of("email", email, "senha", SENHA), json()),
            Map.class).getBody().get("tokenAcesso"));

        // O que o cadastro ja criou: "Financas de Zelia".
        ambientes = 1;
    }

    private static HttpHeaders json() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private static HttpHeaders cabecalhos() {
        HttpHeaders h = json();
        h.setBearerAuth(token);
        return h;
    }
}
