package com.raspybank.app.seguranca;

import com.raspybank.identidade.servico.JwtServico;
import com.raspybank.shared.contexto.Canal;
import com.raspybank.shared.contexto.ContextoRequisicao;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * A peca que fecha o circuito.
 *
 * <p>Ate agora existiam as pontas soltas: o {@code ContextoRequisicao} sabia
 * guardar quem esta conectado, o aspecto de RLS sabia informar isso ao
 * Postgres, e as politicas do banco sabiam filtrar. Faltava alguem para
 * dizer QUEM e a pessoa. E este filtro.</p>
 *
 * <p>O caminho completo, a cada requisicao:</p>
 * <pre>
 *   cabecalho Authorization
 *     -> este filtro valida a assinatura do token
 *     -> ContextoRequisicao recebe usuario e ambiente
 *     -> aspecto executa set_config na transacao
 *     -> politica do Postgres filtra as linhas
 * </pre>
 *
 * <p>{@code OncePerRequestFilter} garante execucao unica por requisicao —
 * sem isso, encaminhamentos internos fariam o filtro rodar de novo.</p>
 */
@Component
public class FiltroAutenticacaoJwt extends OncePerRequestFilter {

    private static final String CABECALHO = "Authorization";
    private static final String PREFIXO   = "Bearer ";
    private static final String CANAL     = "X-Canal";

    private final JwtServico jwt;

    public FiltroAutenticacaoJwt(JwtServico jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao,
                                    HttpServletResponse resposta,
                                    FilterChain cadeia)
            throws ServletException, IOException {
        try {
            extrairToken(requisicao).flatMap(jwt::validar).ifPresent(conteudo -> {

                ContextoRequisicao.definir(
                    conteudo.usuarioId(),
                    conteudo.ambienteId(),
                    canalDe(requisicao));

                // O Spring Security precisa da sua propria representacao para
                // que as regras de autorizacao funcionem.
                var autenticacao = new UsernamePasswordAuthenticationToken(
                    conteudo.usuarioId(), null, List.of());
                SecurityContextHolder.getContext().setAuthentication(autenticacao);
            });

            cadeia.doFilter(requisicao, resposta);

        } finally {
            // OBRIGATORIO. O servidor reaproveita threads de um pool: sem esta
            // limpeza, a proxima requisicao atendida por esta thread herdaria
            // a identidade da anterior — um vazamento entre usuarios.
            ContextoRequisicao.limpar();
            SecurityContextHolder.clearContext();
        }
    }

    private java.util.Optional<String> extrairToken(HttpServletRequest requisicao) {
        String cabecalho = requisicao.getHeader(CABECALHO);
        if (cabecalho != null && cabecalho.startsWith(PREFIXO)) {
            return java.util.Optional.of(cabecalho.substring(PREFIXO.length()).trim());
        }
        return java.util.Optional.empty();
    }

    private Canal canalDe(HttpServletRequest requisicao) {
        String valor = requisicao.getHeader(CANAL);
        if ("telegram".equalsIgnoreCase(valor)) {
            return Canal.TELEGRAM;
        }
        return Canal.WEB;
    }
}
