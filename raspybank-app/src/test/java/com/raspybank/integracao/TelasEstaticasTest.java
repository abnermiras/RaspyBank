package com.raspybank.integracao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * As telas sao servidas sem token — e SO elas.
 *
 * <p>Liberar arquivos no Spring Security e o tipo de mudanca que, feita
 * com um curinga distraido, abre o que nao devia. Este teste e a cerca: de
 * um lado, a tela de login precisa carregar antes de existir token; do
 * outro, a API continua exigindo autenticacao. Se alguem trocar a lista
 * explicita da {@code SegurancaConfig} por {@code /**}, a segunda metade
 * daqui e que vai acusar.</p>
 */
class TelasEstaticasTest extends IntegracaoTest {

    @Autowired
    private TestRestTemplate http;

    @Test
    @DisplayName("A raiz devolve a tela de login, sem token nenhum")
    void raizServeAsTelas() {
        ResponseEntity<String> resposta = http.getForEntity("/", String.class);

        assertEquals(HttpStatus.OK, resposta.getStatusCode());
        assertTrue(resposta.getBody().contains("RaspyBank"),
            "A raiz deveria devolver o index.html do prototipo");
    }

    @Test
    @DisplayName("Folha de estilo e script tambem sao publicos — a tela nao carrega sem eles")
    void recursosDaTelaSaoPublicos() {
        assertEquals(HttpStatus.OK, http.getForEntity("/estilo.css", String.class).getStatusCode());
        assertEquals(HttpStatus.OK, http.getForEntity("/app.js", String.class).getStatusCode());
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
    @DisplayName("Caminho fora da lista responde 401, nem chega a dizer se existe")
    void caminhoNaoLiberadoNemChegaAoDisco() {
        // Consequencia boa da lista explicita: arquivo novo em static/ NASCE
        // protegido, e a resposta nao revela se ele existe ou nao.
        assertEquals(HttpStatus.UNAUTHORIZED,
            http.getForEntity("/nao-existe.js", String.class).getStatusCode());
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
