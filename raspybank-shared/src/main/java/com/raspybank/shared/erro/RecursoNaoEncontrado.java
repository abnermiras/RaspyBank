package com.raspybank.shared.erro;

/**
 * O recurso pedido nao existe — ou nao existe <i>para quem perguntou</i>.
 * Vira <b>404</b> na API.
 *
 * <h3>Os dois casos que esta excecao junta de proposito</h3>
 *
 * <p>Id inexistente e id que existe mas pertence a outra pessoa respondem a
 * <b>mesma coisa</b>. Distinguir os dois — 404 num caso, 403 no outro —
 * transformaria a API num oraculo: bastaria varrer identificadores e ler o
 * codigo de resposta para descobrir quais existem no sistema inteiro.</p>
 *
 * <p>Na pratica o RLS ja produz o primeiro caso a partir do segundo: uma
 * categoria de outro ambiente simplesmente nao volta da consulta. Esta
 * excecao mantem o mesmo silencio na fronteira entre ambientes do
 * <i>proprio</i> usuario, onde o banco libera e so o {@code ambienteId} da
 * sessao recorta (B-D21).</p>
 */
public class RecursoNaoEncontrado extends RuntimeException {

    public RecursoNaoEncontrado(String mensagem) {
        super(mensagem);
    }
}
