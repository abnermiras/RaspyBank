package com.raspybank.lancamento.dominio;

/**
 * As categorias que o CODIGO precisa encontrar sozinho (F10 / B-D13).
 *
 * <p>Uma categoria sistemica e criada junto com o ambiente, pela funcao
 * {@code fn_criar_categorias_sistemicas}, e nao pode ser apagada nem
 * renomeada para outra coisa — porque alguma rotina depende de acha-la.</p>
 *
 * <p><b>Por que a busca e por codigo e nao por id nem por nome.</b> Por id
 * seria impossivel: categorias sao COPIADAS por ambiente (F9), entao cada
 * casa tem a sua "Transferencia", com id proprio — nao ha id unico para o
 * codigo guardar. Por nome seria fragil: nome e apresentacao, casa com
 * acento e maiuscula, e um dia vira traducao. O codigo e o unico
 * identificador estavel entre ambientes.</p>
 *
 * <p>Sistemica nao se renomeia nem se arquiva (F10): as duas operacoes
 * respondem 403, e a guarda mora em {@link Categoria}.</p>
 *
 * <p><b>Sistemica nao e o mesmo que fora do mapa</b> (B-D15). Sao duas
 * perguntas: {@code sistemica} responde "pode editar?", {@code entra_no_mapa}
 * responde "conta como gasto?". {@code NAO_CLASSIFICADO} e sistemica e conta
 * como gasto — usar uma flag para as duas coisas faria o total do relatorio
 * mentir para baixo, em silencio.</p>
 *
 * <p>Contrato: {@code name()} == valor gravado na coluna {@code codigo}.</p>
 */
public enum CodigoSistemico {

    /**
     * Os dois lados de uma transferencia entre contas proprias.
     * F2 exige categoria nos dois lancamentos ligados; sem esta, nao haveria
     * destino valido. Fora do mapa: mover dinheiro entre bolsos proprios nao
     * e gasto (B-D14).
     */
    TRANSFERENCIA,

    /**
     * Saldo de abertura e correcoes (A13). Como nao existe coluna de saldo
     * (P1), abrir uma conta com 1.200 reais e registrar um lancamento nesta
     * categoria. Fora do mapa pelo mesmo motivo: nao e dinheiro gasto.
     */
    AJUSTE,

    /**
     * Destino de quem ainda nao classificou. F11 torna a subcategoria
     * opcional, mas a categoria e obrigatoria — sem esta, "gastei 50 no
     * mercado" pelo Telegram nao teria onde cair.
     * <b>Entra no mapa</b>: e gasto de verdade, so falta o rotulo.
     */
    NAO_CLASSIFICADO,

    /**
     * O dinheiro saindo da conta para cobrir a fatura do cartao (V12).
     *
     * <p>Estava reservada desde B-D13, para "quando o cartao chegar". Chegou.</p>
     *
     * <p><b>Fora do mapa</b>, e errar aqui dobra o mes inteiro em silencio: os
     * gastos do cartao ja entraram no mapa UM A UM, quando cada compra foi
     * lancada. O pagamento e o dinheiro saindo para cobrir aqueles gastos —
     * conta-lo de novo somaria a mesma despesa duas vezes (B-D59).</p>
     *
     * <p>Continua aparecendo no EXTRATO da conta pagadora, e isso e deliberado:
     * o dinheiro saiu de la, e omitir seria mentir sobre o saldo. "Nao entra no
     * mapa" e "nao existe" sao coisas diferentes.</p>
     */
    PAGAMENTO_FATURA
}
