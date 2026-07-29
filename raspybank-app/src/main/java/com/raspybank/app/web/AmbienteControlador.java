package com.raspybank.app.web;

import com.raspybank.ambiente.dominio.Ambiente;
import com.raspybank.ambiente.servico.AmbienteServico;
import com.raspybank.shared.contexto.ContextoRequisicao;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ambientes — a tela de perfil, contrato em {@code docs/api.md} §2b.
 *
 * <h3>Por que este recurso nasceu tarde</h3>
 *
 * <p>Ate a V14, ambiente so nascia de um jeito: automaticamente, no cadastro
 * (A12). O seletor da casca mostrava a lista e ficava <b>desabilitado com um
 * ambiente so</b> — nao por opcao, mas porque nao havia como criar o segundo.
 * O Abner esbarrou nisso usando o sistema.</p>
 *
 * <h3>O que este recurso NAO faz</h3>
 *
 * <p>Nao apaga, nao renomeia e nao convida ninguem. Apagar tem consequencia
 * grande demais para entrar sem desenho — inclusive porque toda chave
 * estrangeira que aponta para {@code ambiente} e {@code RESTRICT}, e porque um
 * ambiente compartilhado apagado levaria junto os dados de outra pessoa.
 * Convidar e o I-08, que vem no compartilhamento.</p>
 */
@RestController
@RequestMapping("/api/ambientes")
public class AmbienteControlador {

    private final AmbienteServico ambientes;

    public AmbienteControlador(AmbienteServico ambientes) {
        this.ambientes = ambientes;
    }

    /**
     * Os ambientes da pessoa, com qual esta ativo na sessao.
     *
     * <p>O ativo vem junto de proposito: a tela precisa marcar um deles, e
     * descobrir isso numa segunda chamada seria dois caminhos de leitura para
     * a mesma pergunta.</p>
     */
    @GetMapping
    public Map<String, Object> listar() {
        List<AmbienteResposta> lista = ambientes.listarDoUsuario().stream()
            .map(AmbienteResposta::de)
            .toList();

        return Map.of(
            "ambientes", lista,
            "ambienteAtual", ContextoRequisicao.ambienteId().map(UUID::toString).orElse(""));
    }

    /**
     * Cria um ambiente vazio, com o nome escolhido.
     *
     * <p>Vazio significa <b>so as sistemicas</b> (F13/B-D14): sem kit inicial de
     * categorias, sem conta nenhuma. A funcao {@code app_criar_ambiente} da V14
     * cuida disso na mesma transacao — ambiente sem as sistemicas nao receberia
     * transferencia nem ajuste de saldo, e quebraria na primeira operacao.</p>
     *
     * <p><b>Nao troca a sessao para o ambiente novo.</b> Criar e entrar sao
     * coisas diferentes: quem esta no meio de um lancamento na Casa nao deveria
     * ser jogado para o ambiente recem-criado sem pedir. A tela oferece a troca
     * pelo seletor que ja existe.</p>
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> criar(@Valid @RequestBody Pedido pedido) {
        ambientes.criar(pedido.nome().trim());
        return ResponseEntity.status(HttpStatus.CREATED).body(listar());
    }

    // =========================================================================

    public record Pedido(
        @NotBlank(message = "nome e obrigatorio")
        @Size(max = 60, message = "nome deve ter no maximo 60 caracteres")
        String nome
    ) {}

    public record AmbienteResposta(UUID id, String nome, OffsetDateTime criadoEm) {
        static AmbienteResposta de(Ambiente a) {
            return new AmbienteResposta(a.getId(), a.getNome(), a.getCriadoEm());
        }
    }
}
