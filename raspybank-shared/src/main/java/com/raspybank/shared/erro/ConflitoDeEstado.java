package com.raspybank.shared.erro;

/**
 * A operacao e legitima, mas o estado atual do dado a impede. Vira
 * <b>409</b> na API.
 *
 * <h3>A diferenca para {@link OperacaoNaoPermitida}</h3>
 *
 * <p>O 403 diz "isto voce nunca pode fazer" — renomear uma categoria
 * sistemica sera proibido amanha tambem. O 409 diz "isto voce nao pode fazer
 * <i>agora</i>": encerrar uma conta com saldo passa a funcionar assim que o
 * dinheiro sair dela.</p>
 *
 * <p>A distincao importa para a tela: no 403 ela desabilita o botao; no 409
 * ela mostra o que fazer antes de tentar de novo.</p>
 *
 * <p>O 409 vindo do banco (violacao de unicidade, SQLSTATE 23505) continua
 * sendo traduzido a parte no {@code TratadorGlobalDeErros} — ali o conflito e
 * detectado pela constraint, nao decidido pelo codigo.</p>
 */
public class ConflitoDeEstado extends RuntimeException {

    public ConflitoDeEstado(String mensagem) {
        super(mensagem);
    }
}
