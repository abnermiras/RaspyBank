package com.raspybank.app.seguranca;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * O 401 de quem chegou sem sessao valida — com corpo.
 *
 * <p>Ate aqui a cadeia usava {@code HttpStatusEntryPoint}, que responde 401 com
 * ZERO BYTES. B-T1 promete, para o sistema inteiro, que todo erro devolve
 * {@code {"erro": "<frase exibivel>"}}, e a §6c de {@code docs/api.md} repete a
 * promessa para o extrato: "corpo e JSON de erro, nunca um .xlsx vazio". Corpo
 * vazio nao da a quem chamou nenhuma frase para mostrar — o {@code lerErro} do
 * frontend caia no texto generico de reserva, e num endpoint binario a pessoa
 * ficava com um arquivo de zero byte na pasta de downloads.</p>
 *
 * <h3>Uma frase so para os tres casos</h3>
 *
 * <p>Token ausente, token malformado e token expirado respondem EXATAMENTE a
 * mesma coisa. A diferenca seria util so para quem esta sondando: "expirado"
 * confirma que o token um dia foi valido, "malformado" confirma que a
 * assinatura foi conferida. E a mesma regra de B-A8/B-T2, que ja obriga os 401
 * dos controladores de autenticacao a serem indistinguiveis entre si.</p>
 *
 * <p>A frase mira em quem usa, nao em quem depura: ela diz o que fazer
 * ("Entre novamente"), porque e a unica acao possivel de qualquer um dos tres
 * casos.</p>
 *
 * <h3>Montado a mao, como o 403 do filtro</h3>
 *
 * <p>Este ponto de entrada roda dentro da cadeia de seguranca, antes do MVC:
 * o {@code TratadorGlobalDeErros} nao o alcanca. Dai o JSON constante, pelo
 * mesmo motivo do {@code CORPO_SEM_ACESSO} em {@link FiltroAutenticacaoJwt}.</p>
 */
@Component
public class PontoDeEntradaSemSessao implements AuthenticationEntryPoint {

    /**
     * Constante, e nao string montada: e um corpo fixo, e uma frase que muda
     * por engano vira duas frases diferentes para o mesmo 401.
     */
    static final String CORPO =
        "{\"erro\":\"Sessão expirada ou ausente. Entre novamente.\"}";

    @Override
    public void commence(HttpServletRequest requisicao,
                         HttpServletResponse resposta,
                         AuthenticationException excecao) throws IOException {
        // Sem WWW-Authenticate de proposito: o cabecalho faria o navegador
        // abrir a caixa de usuario e senha dele por cima da nossa tela.
        resposta.setStatus(HttpStatus.UNAUTHORIZED.value());
        resposta.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resposta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resposta.getWriter().write(CORPO);
    }
}
