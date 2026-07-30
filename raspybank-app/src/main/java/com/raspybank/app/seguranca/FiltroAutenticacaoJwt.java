package com.raspybank.app.seguranca;

import com.raspybank.ambiente.servico.AmbienteServico;
import com.raspybank.identidade.servico.JwtServico;
import com.raspybank.shared.contexto.Canal;
import com.raspybank.shared.contexto.ContextoRequisicao;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    /**
     * O corpo do 403 de B-D83. {@code motivo} e o marcador ESTAVEL que a tela
     * le para voltar a um ambiente que e dela — a frase de {@code erro} pode
     * mudar; o marcador, nao.
     */
    private static final String CORPO_SEM_ACESSO =
        "{\"erro\":\"Voce nao tem mais acesso a este ambiente\","
        + "\"motivo\":\"SEM_ACESSO_AO_AMBIENTE\"}";

    private final JwtServico jwt;
    private final AmbienteServico ambientes;

    public FiltroAutenticacaoJwt(JwtServico jwt, AmbienteServico ambientes) {
        this.jwt = jwt;
        this.ambientes = ambientes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao,
                                    HttpServletResponse resposta,
                                    FilterChain cadeia)
            throws ServletException, IOException {
        try {
            var conteudo = extrairToken(requisicao).flatMap(jwt::validar).orElse(null);
            if (conteudo != null) {

                ContextoRequisicao.definir(
                    conteudo.usuarioId(),
                    conteudo.ambienteId(),
                    conteudo.familiaId(),
                    canalDe(requisicao));

                // B-D83: o ambiente do token ainda pertence a quem o apresenta?
                //
                // A revogacao de um acesso nao invalida o JWT — ele vale mais
                // quinze minutos, carregando um ambiente que ja nao e da
                // pessoa. O RLS ja corta os dados (nada indevido aparece), mas
                // sem esta conferencia a tela ficaria VAZIA E SEM EXPLICACAO:
                // contas zeradas, mapa em branco, nenhum erro. Uma busca por
                // chave primaria em usuario_ambiente compra a frase que a tela
                // le para voltar a um ambiente proprio.
                //
                // E mais geral que o compartilhamento: qualquer motivo para o
                // vinculo sumir — revogacao hoje, ambiente apagado amanha —
                // tem a mesma resposta clara, sem codigo novo por caso.
                //
                // /api/auth/** fica de fora porque e a rota de FUGA: e o
                // renovar quem emite o token novo num ambiente que e da
                // pessoa (ambienteParaRenovacao ja resolvia isso).
                if (conteudo.ambienteId() != null
                        && !requisicao.getServletPath().startsWith("/api/auth/")
                        && !ambientes.usuarioPertence(conteudo.usuarioId(), conteudo.ambienteId())) {
                    responderSemAcesso(resposta);
                    return;
                }

                // O Spring Security precisa da sua propria representacao para
                // que as regras de autorizacao funcionem.
                var autenticacao = new UsernamePasswordAuthenticationToken(
                    conteudo.usuarioId(), null, List.of());
                SecurityContextHolder.getContext().setAuthentication(autenticacao);
            }

            cadeia.doFilter(requisicao, resposta);

        } finally {
            // OBRIGATORIO. O servidor reaproveita threads de um pool: sem esta
            // limpeza, a proxima requisicao atendida por esta thread herdaria
            // a identidade da anterior — um vazamento entre usuarios.
            ContextoRequisicao.limpar();
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * O 403 e montado A MAO, como os 401 dos controladores de autenticacao
     * (B-A8): este filtro roda antes do MVC, entao o TratadorGlobalDeErros
     * nao o alcancaria.
     */
    private void responderSemAcesso(HttpServletResponse resposta) throws IOException {
        resposta.setStatus(HttpStatus.FORBIDDEN.value());
        resposta.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resposta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resposta.getWriter().write(CORPO_SEM_ACESSO);
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
