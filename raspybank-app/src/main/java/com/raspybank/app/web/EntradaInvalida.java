package com.raspybank.app.web;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parametro de entrada invalido — vira <b>400</b> no
 * {@link TratadorGlobalDeErros}, no formato de erro do projeto (B-T1).
 *
 * <h3>Por que existe, se ja ha validacao de corpo</h3>
 *
 * <p>{@code MethodArgumentNotValidException} cobre o corpo anotado com
 * {@code jakarta.validation}. Parametro de <b>query</b> nao passa por ali:
 * hoje, um {@code ?mes=abacaxi} vira 500 na captura geral, o que e mentira —
 * o erro e de quem pediu, nao nosso. Esta excecao e o caminho de dizer isso em
 * 400, sem que nenhum controlador monte resposta de erro a mao.</p>
 *
 * <h3>Por que aqui e nao em {@code raspybank-shared}</h3>
 *
 * <p>Validar o que chegou pela query e assunto da borda HTTP: o contexto de
 * negocio nunca ve uma string de query, ele ve um {@code LocalDate}. As
 * excecoes de {@code shared} existem para o dominio falar com a borda, e esta
 * anda no sentido contrario.</p>
 *
 * <h3>A mensagem, e o campo com a MESMA mensagem</h3>
 *
 * <p>{@code lerErro} no cliente ({@code cliente.js}) prefere a primeira
 * mensagem de {@code campos} a {@code erro} — foi feito para o formulario
 * marcar o lugar certo. Entao o valor de cada campo aqui e a frase exibivel
 * inteira, e nao um resumo: um resumo apareceria na tela no lugar da frase boa,
 * que e justamente o oposto do que se quis.</p>
 */
public class EntradaInvalida extends RuntimeException {

    private final Map<String, String> campos;

    /**
     * @param mensagem frase exibivel; vai para {@code erro} e para cada campo
     * @param campos   nomes dos parametros culpados, na ordem em que aparecem
     *                 na tela
     */
    public EntradaInvalida(String mensagem, String... campos) {
        super(mensagem);
        // LinkedHashMap: a ordem e a da tela, e e a primeira entrada que o
        // cliente exibe.
        Map<String, String> mapa = new LinkedHashMap<>();
        for (String campo : campos) {
            mapa.put(campo, mensagem);
        }
        // Nao Map.copyOf: aquele nao preserva ordem, e a ordem e o contrato
        // com o cliente, que exibe a PRIMEIRA entrada.
        this.campos = Collections.unmodifiableMap(mapa);
    }

    public Map<String, String> getCampos() {
        return campos;
    }
}
