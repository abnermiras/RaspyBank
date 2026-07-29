package com.raspybank.app.web;

import com.raspybank.ambiente.dominio.Ambiente;
import com.raspybank.ambiente.servico.AmbienteServico;
import com.raspybank.shared.contexto.ContextoRequisicao;
import com.raspybank.identidade.dominio.Usuario;
import com.raspybank.identidade.servico.UsuarioServico;
import com.raspybank.identidade.servico.AutenticacaoServico;
import com.raspybank.shared.erro.OperacaoNaoPermitida;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.UUID;
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

    private final UsuarioServico usuarios;
    private final AutenticacaoServico autenticacao;

    public PerfilControlador(AmbienteServico ambientes,
                             UsuarioServico usuarios,
                             AutenticacaoServico autenticacao) {
        this.ambientes = ambientes;
        this.usuarios = usuarios;
        this.autenticacao = autenticacao;
    }

    /**
     * Troca o nome de exibicao. So o nome.
     *
     * <p>O e-mail NAO se altera aqui, e a ausencia e decisao: ele e o login.
     * Troca-lo muda a identidade de entrada, e sem recuperacao de senha
     * funcionando (ainda nao existe) um e-mail digitado errado tranca a pessoa
     * para fora da propria conta, sem volta.</p>
     */
    @PutMapping("/nome")
    public Map<String, Object> alterarNome(@Valid @RequestBody PedidoNome pedido) {
        UUID usuarioId = ContextoRequisicao.usuarioId().orElseThrow();

        usuarios.renomear(usuarioId, pedido.nome().trim());
        return perfil();
    }

    /**
     * Troca a senha, exigindo a atual.
     *
     * <p>Responde <b>403</b> quando a atual nao confere, e a mensagem nao diz
     * mais que isso — mesmo cuidado do login.</p>
     *
     * <p><b>As outras sessoes caem.</b> Se a troca aconteceu porque a senha
     * vazou, deixar as antigas vivas manteria o invasor dentro. Quem trocou
     * continua nesta.</p>
     */
    @PutMapping("/senha")
    public ResponseEntity<Void> trocarSenha(@Valid @RequestBody PedidoSenha pedido) {
        UUID usuarioId = ContextoRequisicao.usuarioId().orElseThrow();

        boolean trocou = autenticacao.trocarSenha(
            usuarioId, usuarios.buscar(usuarioId).getEmail(),
            pedido.senhaAtual(), pedido.senhaNova());

        if (!trocou) {
            throw new OperacaoNaoPermitida("Senha atual incorreta");
        }
        return ResponseEntity.noContent().build();
    }

    public record PedidoNome(
        @NotBlank(message = "nome e obrigatorio")
        @Size(max = 120, message = "nome deve ter no maximo 120 caracteres")
        String nome
    ) {}

    public record PedidoSenha(
        @NotBlank(message = "senhaAtual e obrigatoria")
        String senhaAtual,

        @NotBlank(message = "senhaNova e obrigatoria")
        @Size(min = 10, message = "a senha nova deve ter ao menos 10 caracteres")
        String senhaNova
    ) {}

    @GetMapping
    public Map<String, Object> perfil() {
        Map<String, Object> resposta = new LinkedHashMap<>();

        UUID usuarioId = ContextoRequisicao.usuarioId().orElseThrow();
        Usuario eu = usuarios.buscar(usuarioId);
        resposta.put("nome", eu.getNome());
        resposta.put("email", eu.getEmail());
        resposta.put("criadoEm", eu.getCriadoEm());

        resposta.put("usuarioId", ContextoRequisicao.usuarioId().orElse(null));
        resposta.put("ambienteAtual", ContextoRequisicao.ambienteId().orElse(null));
        resposta.put("canal", ContextoRequisicao.canal().name());

        List<Map<String, Object>> lista = ambientes.listarDoUsuario().stream()
            .map(this::resumir)
            .toList();

        resposta.put("ambientes", lista);
        return resposta;
    }

    /**
     * O resumo NAO devolve mais um campo de status.
     *
     * <p>A V10 derrubou {@code ambiente.status} (decisao B-D5, inconsistencia
     * I-01): a coluna nunca foi gravada por ninguem e concorria com
     * {@code excluido_em}. Alem disso o campo aqui seria sempre o mesmo valor,
     * porque {@code app_ambientes_do_usuario()} ja filtra os ambientes
     * excluidos — quem chega nesta lista esta ativo por construcao.</p>
     */
    private Map<String, Object> resumir(Ambiente a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("nome", a.getNome());
        return m;
    }
}
