package com.raspybank.app.web;

import com.raspybank.ambiente.servico.AmbienteServico;
import com.raspybank.shared.contexto.ContextoRequisicao;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Acessos de um ambiente — o compartilhamento do §4j, contrato em
 * {@code docs/api.md} §2c.
 *
 * <p>A frase que orienta tudo: <i>"e como se eu desse a minha senha para a
 * pessoa, mas ao inves de dar minha senha dei meu acesso"</i>. Quem recebe ve
 * o ambiente na propria lista, entra pelo seletor de sempre e trabalha DENTRO
 * dele: categorias, contas e mapa sao os do dono, e toda acao fica carimbada
 * com o nome de quem a fez (B-D82, sem uma linha de codigo — o gatilho ja lia
 * o autor da sessao).</p>
 *
 * <h3>O que o convidado pode e o que nao pode</h3>
 *
 * <p>Dinheiro, tudo; porta, nada (B-D76). Convidar, remover acesso, renomear
 * e apagar sao do dono. Este controlador so cuida da porta — o dinheiro ja
 * estava resolvido pelo RLS quando a linha do vinculo entrou (B-D74).</p>
 */
@RestController
@RequestMapping("/api/ambientes/{ambienteId}/acessos")
public class AcessoControlador {

    private final AmbienteServico ambientes;

    public AcessoControlador(AmbienteServico ambientes) {
        this.ambientes = ambientes;
    }

    @GetMapping
    public Map<String, Object> listar(@PathVariable UUID ambienteId) {
        UUID usuarioId = ContextoRequisicao.usuarioId().orElseThrow();
        return Map.of("acessos", ambientes.listarAcessos(usuarioId, ambienteId));
    }

    /**
     * Concede acesso imediato, sem aceite (B-D80): o ambiente aparece na lista
     * da pessoa na hora, e ela sai com um clique se nao quiser.
     *
     * <p>Responde <b>404</b> quando o e-mail nao esta cadastrado — um oraculo
     * de enumeracao aceito conscientemente (B-D81), porque responder "ok" para
     * um e-mail digitado errado esconderia o erro mais comum de todos.</p>
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> conceder(@PathVariable UUID ambienteId,
                                                        @Valid @RequestBody Pedido pedido) {
        UUID usuarioId = ContextoRequisicao.usuarioId().orElseThrow();
        ambientes.concederAcesso(usuarioId, ambienteId, pedido.email().trim());
        return ResponseEntity.status(HttpStatus.CREATED).body(listar(ambienteId));
    }

    /**
     * B-D77: o dono remove qualquer um; qualquer um remove a si mesmo; o dono
     * nao sai. A revogacao vale agora — o token de quem estava dentro leva 403
     * com frase na proxima requisicao (B-D83) — e as sessoes da pessoa nao
     * caem (B-D84).
     */
    @DeleteMapping("/{usuarioId}")
    public ResponseEntity<Void> remover(@PathVariable UUID ambienteId,
                                        @PathVariable UUID usuarioId) {
        UUID quemPede = ContextoRequisicao.usuarioId().orElseThrow();
        ambientes.removerAcesso(quemPede, ambienteId, usuarioId);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================

    public record Pedido(
        @NotBlank(message = "email e obrigatorio")
        @Email(message = "email malformado")
        String email
    ) {}
}
