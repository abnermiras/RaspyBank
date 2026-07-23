package com.raspybank.app.web;

import com.raspybank.ambiente.dominio.Ambiente;
import com.raspybank.ambiente.servico.AmbienteServico;
import com.raspybank.shared.contexto.ContextoRequisicao;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoint protegido de demonstracao.
 *
 * <p>Serve para provar o circuito completo: sem token, responde 401; com
 * token, devolve os ambientes da pessoa — e devolve porque o Row Level
 * Security recebeu a identidade e liberou as linhas.</p>
 *
 * <p>Repare que nenhuma linha deste codigo filtra por usuario. O filtro
 * acontece no banco, por politica. E exatamente esse o ponto.</p>
 */
@RestController
@RequestMapping("/api/perfil")
public class PerfilControlador {

    private final AmbienteServico ambientes;

    public PerfilControlador(AmbienteServico ambientes) {
        this.ambientes = ambientes;
    }

    @GetMapping
    public Map<String, Object> perfil() {
        Map<String, Object> resposta = new LinkedHashMap<>();

        resposta.put("usuarioId", ContextoRequisicao.usuarioId().orElse(null));
        resposta.put("ambienteAtual", ContextoRequisicao.ambienteId().orElse(null));
        resposta.put("canal", ContextoRequisicao.canal().name());

        List<Map<String, Object>> lista = ambientes.listarDoUsuario().stream()
            .map(this::resumir)
            .toList();

        resposta.put("ambientes", lista);
        return resposta;
    }

    private Map<String, Object> resumir(Ambiente a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("nome", a.getNome());
        m.put("status", a.getStatus());
        return m;
    }
}
