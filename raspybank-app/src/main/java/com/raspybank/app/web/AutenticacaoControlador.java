package com.raspybank.app.web;

import com.raspybank.ambiente.servico.AmbienteServico;
import com.raspybank.app.servico.OnboardingServico;
import com.raspybank.auditoria.servico.AuditoriaServico;
import com.raspybank.identidade.servico.AutenticacaoServico;
import com.raspybank.identidade.servico.AutenticacaoServico.Renovacao;
import com.raspybank.identidade.servico.JwtServico;
import com.raspybank.shared.contexto.Canal;
import com.raspybank.shared.contexto.ContextoRequisicao;
import com.raspybank.shared.contexto.Operacao;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AutenticacaoControlador {

    private final OnboardingServico onboarding;
    private final AutenticacaoServico autenticacao;
    private final AmbienteServico ambientes;
    private final AuditoriaServico auditoria;
    private final JwtServico jwt;

    public AutenticacaoControlador(OnboardingServico onboarding,
                                   AutenticacaoServico autenticacao,
                                   AmbienteServico ambientes,
                                   AuditoriaServico auditoria,
                                   JwtServico jwt) {
        this.onboarding = onboarding;
        this.autenticacao = autenticacao;
        this.ambientes = ambientes;
        this.auditoria = auditoria;
        this.jwt = jwt;
    }

    // -------------------------------------------------------------------------
    @PostMapping("/cadastro")
    public ResponseEntity<Map<String, Object>> cadastrar(
            @Valid @RequestBody PedidoCadastro pedido) {

        var resultado = onboarding.cadastrar(
            pedido.nome(), pedido.email(), pedido.senha());

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "usuarioId",  resultado.usuarioId(),
            "ambienteId", resultado.ambienteId(),
            "mensagem",   "Cadastro realizado. Faca login para continuar."));
    }

    // -------------------------------------------------------------------------
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody PedidoLogin pedido,
                                   HttpServletRequest requisicao) {

        Optional<UUID> usuario = autenticacao.autenticar(pedido.email(), pedido.senha());

        if (usuario.isEmpty()) {
            // Mensagem deliberadamente vaga: dizer "esse e-mail nao existe"
            // transformaria o login num verificador de cadastro.
            return naoAutorizado();
        }

        UUID usuarioId = usuario.get();

        // O ambiente entra no token. Isso significa que o vinculo foi
        // verificado no momento da emissao — existe um instante unico e
        // auditavel em que a permissao foi conferida.
        UUID ambienteId = primeiroAmbienteDe(usuarioId);

        var sessao = autenticacao.emitirRenovacaoNova(
            usuarioId, requisicao.getHeader("User-Agent"));

        auditoria.registrarAutenticacao(
            usuarioId, Canal.WEB, Operacao.ACESSO, "{\"evento\":\"login\"}");

        return ResponseEntity.ok(Map.of(
            "tokenAcesso",    jwt.emitirAcesso(usuarioId, ambienteId, sessao.familiaId()),
            "tokenRenovacao", sessao.tokenRenovacao(),
            "ambienteId",     String.valueOf(ambienteId),
            "expiraEmSegundos", 900));
    }

    // -------------------------------------------------------------------------
    @PostMapping("/renovar")
    public ResponseEntity<?> renovar(@Valid @RequestBody PedidoRenovacao pedido,
                                     HttpServletRequest requisicao) {

        Renovacao resultado = autenticacao.renovar(
            pedido.tokenRenovacao(), requisicao.getHeader("User-Agent"));

        return switch (resultado) {

            case Renovacao.Sucesso ok -> {
                UUID ambienteId = ambienteParaRenovacao(ok.usuarioId(), pedido.ambienteId());
                yield ResponseEntity.ok(Map.of(
                    "tokenAcesso",    jwt.emitirAcesso(ok.usuarioId(), ambienteId, ok.familiaId()),
                    "tokenRenovacao", ok.novoTokenRenovacao(),
                    "ambienteId",     String.valueOf(ambienteId),
                    "expiraEmSegundos", 900));
            }

            case Renovacao.ReusoDetectada reuso -> {
                // O evento de seguranca que a trilha existe para guardar
                // (I-03, opcao b). A RESPOSTA, porem, e identica a de token
                // invalido: contar ao portador que a deteccao disparou seria
                // avisar o ladrao de que foi percebido.
                auditoria.registrarAutenticacao(
                    reuso.usuarioId(), Canal.WEB, Operacao.ACESSO,
                    "{\"evento\":\"reuso_token_renovacao\",\"familiaRevogada\":\""
                        + reuso.familiaId() + "\"}");
                yield naoAutorizado();
            }

            case Renovacao.Invalida ignorada -> naoAutorizado();
        };
    }

    // -------------------------------------------------------------------------
    /**
     * Sai DESTE dispositivo: revoga apenas a familia de tokens que sustenta a
     * sessao atual. Tokens emitidos antes da claim de familia existir caem no
     * comportamento antigo — todas as sessoes — que e o lado seguro do erro.
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        return ContextoRequisicao.usuarioId()
            .map(usuarioId -> {
                ContextoRequisicao.familiaId().ifPresentOrElse(
                    autenticacao::encerrarSessaoDaFamilia,
                    () -> autenticacao.encerrarSessoes(usuarioId));
                return ResponseEntity.ok(Map.of("mensagem", "Sessao encerrada"));
            })
            .orElseGet(AutenticacaoControlador::naoAutorizado);
    }

    /** Sai de TODOS os dispositivos: revoga todas as familias da pessoa. */
    @PostMapping("/logout-todos")
    public ResponseEntity<?> logoutDeTodos() {
        return ContextoRequisicao.usuarioId()
            .map(usuarioId -> {
                autenticacao.encerrarSessoes(usuarioId);
                return ResponseEntity.ok(Map.of("mensagem", "Todas as sessoes encerradas"));
            })
            .orElseGet(AutenticacaoControlador::naoAutorizado);
    }

    // -------------------------------------------------------------------------

    /**
     * Ambiente que o token renovado deve carregar (I-15): o que o cliente
     * declarou estar usando, desde que o vinculo exista; senao, o primeiro.
     *
     * <p>O fallback e deliberado — a renovacao NUNCA falha por causa de
     * ambiente. Se a pessoa perdeu o vinculo (saiu de um ambiente
     * compartilhado, por exemplo), a sessao continua viva num ambiente que
     * ainda e dela, e a resposta informa qual. Falhar aqui queimaria o token
     * rotacionado e derrubaria a sessao por um problema que nao e de
     * credencial. A troca EXPLICITA, essa sim recusa: {@code /api/sessao/ambiente}.</p>
     *
     * <p>A checagem usa a porta sem contexto porque, na renovacao, ainda nao
     * ha identidade na sessao do banco — o RLS devolveria lista vazia e todo
     * ambiente pareceria alheio.</p>
     */
    private UUID ambienteParaRenovacao(UUID usuarioId, UUID ambienteDeclarado) {
        List<UUID> vinculados = ambientes.listarDoUsuarioSemContexto(usuarioId);
        if (vinculados.isEmpty()) {
            throw new IllegalStateException(
                "Usuario " + usuarioId + " sem ambiente algum — estado impossivel"
                + " por construcao (A12: cadastro cria o primeiro atomicamente).");
        }
        if (ambienteDeclarado != null && vinculados.contains(ambienteDeclarado)) {
            return ambienteDeclarado;
        }
        return vinculados.get(0);
    }

    private UUID primeiroAmbienteDe(UUID usuarioId) {
        // Executado antes de haver identidade na sessao, entao o RLS ainda
        // nao esta ativo para este usuario. Consulta direta pelo vinculo.
        List<UUID> vinculados = ambientes.listarDoUsuarioSemContexto(usuarioId);
        if (vinculados.isEmpty()) {
            // I-02: usuario sem ambiente nao pode receber um token esquisito
            // com ambiente nulo — melhor um erro claro e barulhento.
            throw new IllegalStateException(
                "Usuario " + usuarioId + " sem ambiente algum — estado impossivel"
                + " por construcao (A12: cadastro cria o primeiro atomicamente).");
        }
        return vinculados.get(0);
    }

    /**
     * A resposta 401 e UNICA, construida num lugar so: token invalido, reuso
     * detectado e sessao ausente respondem exatamente o mesmo corpo. Qualquer
     * diferenca entre eles viraria um oraculo para quem ataca.
     */
    private static ResponseEntity<Map<String, String>> naoAutorizado() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of("erro", "Credenciais invalidas"));
    }

    // -------------------------------------------------------------------------
    // Contratos de entrada. Records: imutaveis e concisos.
    // As anotacoes de validacao rodam antes de o metodo ser chamado.
    // -------------------------------------------------------------------------

    public record PedidoCadastro(
        @NotBlank(message = "Nome e obrigatorio")
        @Size(max = 120)
        String nome,

        @NotBlank @Email(message = "E-mail invalido")
        String email,

        @NotBlank
        @Size(min = 10, message = "A senha precisa ter ao menos 10 caracteres")
        @Size(max = 72, message = "A senha pode ter no maximo 72 caracteres")
        String senha
    ) {}

    public record PedidoLogin(
        @NotBlank @Email String email,
        @NotBlank String senha
    ) {}

    /**
     * @param ambienteId opcional: o ambiente em que o cliente esta operando,
     *                   para o token renovado preservar (I-15)
     */
    public record PedidoRenovacao(
        @NotBlank String tokenRenovacao,
        UUID ambienteId
    ) {}
}
