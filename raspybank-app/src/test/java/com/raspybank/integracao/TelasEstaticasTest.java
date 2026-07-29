package com.raspybank.integracao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * As telas sao servidas sem token — e SO elas.
 *
 * <p>Liberar arquivos no Spring Security e o tipo de mudanca que, feita com um
 * curinga distraido, abre o que nao devia. Este teste e a cerca: de um lado, a
 * tela de login precisa carregar antes de existir token; do outro, a API
 * continua exigindo autenticacao.</p>
 *
 * <p><strong>Reescrito em 27/07/2026</strong>, quando a SPA substituiu o
 * prototipo. Ele testava {@code /estilo.css} e {@code /app.js}, arquivos
 * escritos a mao que deixaram de existir. O que a SPA serve agora tem nome com
 * hash de conteudo, que muda a cada build — por isso o teste descobre o nome
 * lendo o {@code index.html} em vez de trazer um literal, que ficaria errado no
 * build seguinte.</p>
 */
class TelasEstaticasTest extends IntegracaoTest {

    /** Casa com src="/assets/index-XXXX.js" e href="/assets/index-XXXX.css". */
    private static final Pattern REFERENCIA_A_ASSET =
        Pattern.compile("(?:src|href)=\"(/assets/[^\"]+)\"");

    @Autowired
    private TestRestTemplate http;

    @Test
    @DisplayName("A raiz devolve a SPA, sem token nenhum")
    void raizServeAsTelas() {
        ResponseEntity<String> resposta = http.getForEntity("/", String.class);

        assertEquals(HttpStatus.OK, resposta.getStatusCode());
        assertTrue(resposta.getBody().contains("RaspyBank"),
            "A raiz deveria devolver o index.html da SPA");
        assertTrue(resposta.getBody().contains("id=\"raiz\""),
            "O index.html da SPA precisa trazer o ponto de montagem do React");
    }

    @Test
    @DisplayName("Os assets que o index.html referencia sao publicos — sem eles a tela nao carrega")
    void assetsDaTelaSaoPublicos() {
        String indice = http.getForEntity("/", String.class).getBody();

        Matcher achado = REFERENCIA_A_ASSET.matcher(indice);
        int conferidos = 0;
        while (achado.find()) {
            String caminho = achado.group(1);
            assertEquals(HttpStatus.OK, http.getForEntity(caminho, String.class).getStatusCode(),
                "O index.html referencia " + caminho + ", que precisa carregar sem token");
            conferidos++;
        }

        // Sem esta guarda o teste passaria de graca caso a regex parasse de
        // casar — e um teste que nao verifica nada e pior que teste nenhum,
        // porque da a impressao de cobertura.
        assertTrue(conferidos >= 2,
            "Esperava ao menos o script e a folha de estilo; encontrei " + conferidos);
    }

    @Test
    @DisplayName("Rota interna da SPA devolve o index.html: recarregar a pagina nao pode dar 401")
    void rotaDaSpaDevolveAsTelas() {
        // O React resolve /contas no navegador, mas recarregar a pagina manda o
        // caminho ao servidor. Sem o SpaControlador isto responderia 401 pela
        // regra do anyRequest().authenticated(), e o favorito da pessoa
        // quebraria em silencio.
        for (String rota : new String[] { "/entrar", "/mapa", "/lancamentos", "/categorias", "/contas", "/cartoes", "/perfil" }) {
            ResponseEntity<String> resposta = http.getForEntity(rota, String.class);
            assertEquals(HttpStatus.OK, resposta.getStatusCode(),
                "A rota " + rota + " deveria devolver a SPA");
            assertTrue(resposta.getBody().contains("id=\"raiz\""),
                "A rota " + rota + " deveria devolver o index.html, nao outra coisa");
        }
    }

    @Test
    @DisplayName("Recurso publico que nao existe responde 404 discreto, nao 500 com pilha no log")
    void recursoPublicoInexistenteResponde404() {
        // O navegador pede /favicon.ico sozinho em toda visita. Antes do
        // tratador de NoResourceFoundException, a captura geral de Exception
        // transformava isso em 500 e enchia o log de pilha — o defeito
        // apareceu na primeira vez que a tela abriu num navegador de verdade.
        assertEquals(HttpStatus.NOT_FOUND,
            http.getForEntity("/favicon.ico", String.class).getStatusCode());
    }

    @Test
    @DisplayName("Asset inexistente responde 404, nao a tela: erro de digitacao precisa aparecer")
    void assetInexistenteResponde404() {
        // /assets/** e liberado, mas liberado nao significa que exista. Se isto
        // devolvesse o index.html, um caminho de script errado viraria "tela em
        // branco sem erro", que e das falhas mais caras de diagnosticar.
        assertEquals(HttpStatus.NOT_FOUND,
            http.getForEntity("/assets/nao-existe.js", String.class).getStatusCode());
    }

    @Test
    @DisplayName("Caminho fora da lista responde 401, nem chega a dizer se existe")
    void caminhoNaoLiberadoNemChegaAoDisco() {
        // A lista de rotas da SPA e explicita justamente para isto: endereco
        // que nao e rota conhecida nem asset continua protegido, e a resposta
        // nao revela se ele existe.
        assertEquals(HttpStatus.UNAUTHORIZED,
            http.getForEntity("/nao-existe.js", String.class).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
            http.getForEntity("/rota-que-o-react-nao-tem", String.class).getStatusCode());
    }

    @Test
    @DisplayName("Liberar as telas NAO liberou a API: endpoint protegido segue em 401")
    void apiContinuaProtegida() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            http.getForEntity("/api/perfil", String.class).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
            http.getForEntity("/api/sessao/ambiente", String.class).getStatusCode());
    }
}
