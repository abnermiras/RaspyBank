package com.raspybank.lancamento.dominio;

/**
 * A fatura ainda recebe lancamento, ou ja fechou?
 *
 * <p>Uma das TRES perguntas independentes que substituem o enum unico de estado
 * (B-D58). As outras sao {@link QuitacaoFatura} e o booleano de vencida.</p>
 *
 * <p><b>Por que tres e nao um.</b> Uma fatura {@code ABERTA} pode estar
 * parcialmente paga — e o caso da antecipacao (B-D57), em que se paga antes do
 * fechamento para liberar limite. Num enum unico esse caso nao teria nome, ou
 * ganharia um {@code ABERTA_COM_ANTECIPACAO} que existe so para tapar buraco de
 * desenho. E o mesmo erro que B-D15 ja custou uma vez, quando "sistemica" e
 * "entra no mapa" fingiam ser uma pergunta so.</p>
 *
 * <p>Nao existe coluna para isto (F19): deriva de {@code fechada_em}.</p>
 */
public enum CicloFatura {

    /** Ainda recebe lancamento novo. */
    ABERTA,

    /** Fechada: lancamento novo vai para a proxima, mesmo com data antiga. */
    FECHADA
}
