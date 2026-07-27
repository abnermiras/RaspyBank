package com.raspybank.shared.erro;

/**
 * A operacao e valida em forma e proibida por regra de dominio. Vira
 * <b>403</b> na API.
 *
 * <p>Serve hoje aos cadeados das categorias sistemicas (F10): renomear
 * {@code TRANSFERENCIA} e sintaticamente possivel e semanticamente proibido,
 * porque alguma rotina depende de encontrar aquela linha.</p>
 *
 * <p><b>Por que a guarda vive na entidade e nao so no controlador.</b> Um
 * {@code if} no controlador protege um caminho; a guarda no dominio protege
 * todos — inclusive o servico do Telegram, que vai chamar o mesmo objeto sem
 * passar por HTTP nenhum.</p>
 *
 * <h3>Por que esta classe fica no shared</h3>
 *
 * <p>A lista de dependencias globais e curta de proposito, e esta e uma das
 * poucas adicoes que se pagam: a traducao excecao → codigo HTTP e escrita uma
 * vez no {@code TratadorGlobalDeErros}, e todo contexto futuro (cartao,
 * classificacao) a recebe pronta. A alternativa — uma excecao por modulo —
 * faria o tratador crescer um ramo a cada contexto, e cada ramo esquecido
 * viraria um 500 no lugar de um 403.</p>
 *
 * <p>Note que ela nao conhece nenhum contexto: e vocabulario, nao regra.</p>
 */
public class OperacaoNaoPermitida extends RuntimeException {

    public OperacaoNaoPermitida(String mensagem) {
        super(mensagem);
    }
}
