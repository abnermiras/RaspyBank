package com.raspybank.app.web;

import com.raspybank.ambiente.servico.AmbienteServico;
import com.raspybank.identidade.servico.JwtServico;
import com.raspybank.shared.contexto.ContextoRequisicao;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Operacoes sobre a sessao JA autenticada — por isso vive fora de
 * {@code /api/auth/**}: aquele prefixo e liberado no Spring Security, e este
 * controlador depende da regra oposta, endpoint protegido por padrao.
 *
 * <h3>Troca de ambiente (I-15)</h3>
 * O ambiente vive dentro do token de acesso, entao "trocar de ambiente" e
 * emitir um NOVO token com o outro ambiente — depois de conferir o vinculo.
 * O token anterior continua valido ate expirar (JWT nao se revoga), mas
 * carrega o ambiente antigo: nenhum dos dois da acesso a nada que a pessoa
 * ja nao pudesse ver. O token de renovacao nao muda: a troca nao mexe na
 * sessao, so no recorte de dados.
 */
@RestController
@RequestMapping("/api/sessao")
public class SessaoControlador {

    private final AmbienteServico ambientes;
    private final JwtServico jwt;

    public SessaoControlador(AmbienteServico ambientes, JwtServico jwt) {
        this.ambientes = ambientes;
        this.jwt = jwt;
    }

    @PostMapping("/ambiente")
    public ResponseEntity<?> trocarAmbiente(@Valid @RequestBody PedidoTroca pedido) {

        // O filtro JWT ja garantiu a autenticacao; aqui a identidade existe.
        UUID usuarioId = ContextoRequisicao.usuarioId().orElseThrow();

        // A checagem roda com RLS ativo: a pessoa so enxerga os proprios
        // vinculos, entao um ambiente alheio e simplesmente invisivel.
        if (!ambientes.usuarioPertence(usuarioId, pedido.ambienteId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("erro", "Ambiente inexistente ou sem vinculo com o usuario"));
        }

        String token = jwt.emitirAcesso(
            usuarioId,
            pedido.ambienteId(),
            ContextoRequisicao.familiaId().orElse(null));

        return ResponseEntity.ok(Map.of(
            "tokenAcesso",      token,
            "ambienteId",       pedido.ambienteId().toString(),
            "expiraEmSegundos", 900));
    }

    public record PedidoTroca(
        @NotNull(message = "ambienteId e obrigatorio")
        UUID ambienteId
    ) {}
}
