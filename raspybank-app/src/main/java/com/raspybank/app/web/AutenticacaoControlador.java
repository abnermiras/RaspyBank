package com.raspybank.app.web;

import com.raspybank.ambiente.servico.AmbienteServico;
import com.raspybank.app.servico.OnboardingServico;
import com.raspybank.auditoria.servico.AuditoriaServico;
import com.raspybank.identidade.servico.AutenticacaoServico;
import com.raspybank.identidade.servico.JwtServico;
import com.raspybank.shared.contexto.ContextoRequisicao;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletRequest;
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
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("erro", "Credenciais invalidas"));
        }

        UUID usuarioId = usuario.get();

        // O ambiente entra no token. Isso significa que o vinculo foi
        // verificado no momento da emissao — existe um instante unico e
        // auditavel em que a permissao foi conferida.
        UUID ambienteId = primeiroAmbienteDe(usuarioId);

        String acesso = jwt.emitirAcesso(usuarioId, ambienteId);
        String renovacao = autenticacao.emitirRenovacaoNova(
            usuarioId, requisicao.getHeader("User-Agent"));

        auditoria.registrarAutenticacao(
            usuarioId, "WEB", "CRIACAO", "{\"evento\":\"login\"}");

        return ResponseEntity.ok(Map.of(
            "tokenAcesso",    acesso,
            "tokenRenovacao", renovacao,
            "ambienteId",     String.valueOf(ambienteId),
            "expiraEmSegundos", 900));
    }

    // -------------------------------------------------------------------------
    @PostMapping("/renovar")
    public ResponseEntity<?> renovar(@Valid @RequestBody PedidoRenovacao pedido,
                                     HttpServletRequest requisicao) {

        var resultado = autenticacao.renovar(
            pedido.tokenRenovacao(), requisicao.getHeader("User-Agent"));

        if (resultado.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("erro", "Token de renovacao invalido"));
        }

        UUID usuarioId = resultado.get().usuarioId();
        UUID ambienteId = primeiroAmbienteDe(usuarioId);

        return ResponseEntity.ok(Map.of(
            "tokenAcesso",    jwt.emitirAcesso(usuarioId, ambienteId),
            "tokenRenovacao", resultado.get().novoTokenRenovacao(),
            "expiraEmSegundos", 900));
    }

    // -------------------------------------------------------------------------
    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        return ContextoRequisicao.usuarioId()
            .map(id -> {
                autenticacao.encerrarSessoes(id);
                return ResponseEntity.ok(Map.of("mensagem", "Sessoes encerradas"));
            })
            .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("erro", "Nao autenticado")));
    }

    // -------------------------------------------------------------------------
    private UUID primeiroAmbienteDe(UUID usuarioId) {
        // Executado antes de haver identidade na sessao, entao o RLS ainda
        // nao esta ativo para este usuario. Consulta direta pelo vinculo.
        var lista = ambientes.listarDoUsuarioSemContexto(usuarioId);
        if (lista.isEmpty()) {
            return null;
        }
        Object primeiro = lista.get(0);
        return (primeiro instanceof UUID u) ? u : UUID.fromString(primeiro.toString());
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
        String senha
    ) {}

    public record PedidoLogin(
        @NotBlank @Email String email,
        @NotBlank String senha
    ) {}

    public record PedidoRenovacao(
        @NotBlank String tokenRenovacao
    ) {}
}
