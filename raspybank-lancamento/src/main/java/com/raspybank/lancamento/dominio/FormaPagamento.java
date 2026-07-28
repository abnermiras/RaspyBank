package com.raspybank.lancamento.dominio;

import java.util.Collection;
import java.util.Set;

/**
 * Como o dinheiro se moveu — a resposta para "foi no debito, no pix ou no
 * boleto?", e tambem para "o salario caiu como?".
 *
 * <p>Valores conferidos contra a tabela real {@code forma_pagamento} (V11,
 * 27/07/2026): exatamente estes oito.</p>
 *
 * <h3>Isto nao e uma regra de dinheiro</h3>
 *
 * <p>Todas tem o MESMO efeito patrimonial: o valor se move na data de caixa.
 * Nenhuma muda saldo, situacao (B-D9) ou o mapa de gastos (B-D10). E uma
 * dimensao de ANALISE, irma de {@code responsavelId} (F32) — serve para
 * explicar como o dinheiro se moveu, nunca para calcular quanto.</p>
 *
 * <h3>Por que o nome nao e "forma de pagamento" no sentido estrito</h3>
 *
 * <p>A primeira versao desta classe assumia que so saida tinha forma, e recusava
 * o campo em ENTRADA com o argumento de que "salario nao e pago no debito". O
 * argumento estava certo e o alvo errado: a pergunta util nao e <i>como foi
 * pago</i>, e sim <b>como o dinheiro se moveu</b> — e ela tem resposta nos dois
 * sentidos. Salario e CREDITADO, pix e recebido, deposito e em especie.</p>
 *
 * <h3>Duas armadilhas de vocabulario, resolvidas nos nomes</h3>
 *
 * <p><b>{@code CREDITO_EM_CONTA}, e nao {@code CREDITO}.</b> Em portugues,
 * "credito" significa tanto "cartao de credito" quanto "entrou dinheiro". O
 * segundo sentido ja e o campo {@code tipo}; o nome longo evita que alguem leia
 * o par DEBITO/CREDITO como contabil, quando {@code DEBITO} aqui e o
 * cartao.</p>
 *
 * <p><b>{@code TED}, e nao {@code TRANSFERENCIA}.</b> {@code TRANSFERENCIA} ja
 * e o codigo de uma categoria sistemica (B-D13). A mesma palavra significando
 * duas coisas no mesmo dominio e a receita para alguem ler a errada.</p>
 *
 * <h3>Por que credito de CARTAO nao esta aqui</h3>
 *
 * <p>Compra no cartao de credito nasce como lancamento na conta DO CARTAO
 * (natureza {@code PASSIVO}); quem debita a conta corrente e o pagamento da
 * fatura, outro lancamento. Se ela fosse forma de pagamento da corrente, a
 * mesma compra teria dois caminhos de representacao e as somas passariam a
 * discordar. E a V12.</p>
 */
public enum FormaPagamento {

    /** Cartao de debito. Sai da conta na hora. */
    DEBITO(TipoLancamento.SAIDA),

    /** Serve aos dois sentidos: pix pago e pix recebido. */
    PIX(TipoLancamento.SAIDA, TipoLancamento.ENTRADA),

    /** Como o salario chega. Nao confundir com cartao de credito — ver o javadoc da classe. */
    CREDITO_EM_CONTA(TipoLancamento.ENTRADA),

    BOLETO(TipoLancamento.SAIDA),

    /** Autorizacao permanente: a conta de luz que debita sozinha todo mes. */
    DEBITO_AUTOMATICO(TipoLancamento.SAIDA),

    /** Especie. Na pratica so faz sentido numa conta do tipo carteira. */
    DINHEIRO(TipoLancamento.SAIDA, TipoLancamento.ENTRADA),

    /** Transferencia bancaria classica, nos dois sentidos. */
    TED(TipoLancamento.SAIDA, TipoLancamento.ENTRADA),

    /** Nunca passa pela conta: o valor e retido antes de o salario cair. */
    DESCONTO_EM_FOLHA(TipoLancamento.SAIDA);

    private final Set<TipoLancamento> sentidos;

    FormaPagamento(TipoLancamento... sentidos) {
        this.sentidos = Set.of(sentidos);
    }

    /**
     * Esta forma serve a este sentido?
     *
     * <p>Espelha {@link TipoCategoria#aceita(TipoLancamento)}, que resolve o
     * mesmo tipo de pergunta para a categoria (F12).</p>
     *
     * <p><b>Isto nao e a fonte da verdade</b>, e a diferenca importa. A regra
     * mora nas onze linhas de {@code forma_pagamento_sentido}, e quem a impoe e
     * a chave composta {@code fk_lancamento_forma_sentido}. Este metodo existe
     * para a tela filtrar o seletor e para o servico dar uma frase melhor que
     * "uma restricao de integridade falhou". Se os dois discordarem, o banco
     * ganha — e o defeito e este arquivo.</p>
     */
    public boolean aceita(TipoLancamento tipo) {
        return sentidos.contains(tipo);
    }

    /**
     * Uma conta guarda papel moeda OU dinheiro virtual, nunca os dois.
     *
     * <p>{@code DINHEIRO} e papel moeda, e o unico lugar que guarda papel moeda
     * e um lugar fisico: carteira, bolso, gaveta, cofre. Nenhum deles aceita
     * pix. Do outro lado, o dinheiro de uma conta no Banco do Brasil e virtual —
     * tirar dinheiro de la nao e "pagar em especie", e um <b>saque</b>, que
     * neste sistema e uma transferencia para a conta fisica (B-D39).</p>
     *
     * <p>Por isso {@code DINHEIRO} e mutuamente exclusivo com todo o resto: uma
     * lista com {@code DINHEIRO} e {@code PIX} descreveria uma conta que nao
     * existe, e o seletor da T-08 ofereceria pix para pagar da carteira.</p>
     *
     * <p><b>Esta regra vive no servico e na tela, nao no banco</b>, e a diferenca
     * e deliberada. As outras duas regras de forma — a forma esta na lista da
     * conta, a forma aceita o sentido — sao chaves compostas porque viola-las
     * grava um LANCAMENTO errado. Esta so torna a lista da conta incoerente:
     * nenhum numero fica errado, so uma opcao sem sentido aparece num seletor.
     * Impor no banco custaria um gatilho de nivel de comando, e gatilho e a
     * ferramenta mais cara de manter do arquivo.</p>
     */
    public static boolean listaEhCoerente(Collection<FormaPagamento> formas) {
        return !formas.contains(DINHEIRO) || formas.size() == 1;
    }
}
