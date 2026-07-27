package com.raspybank.app.web;

import com.raspybank.lancamento.dominio.Categoria;
import com.raspybank.lancamento.dominio.Subcategoria;
import com.raspybank.lancamento.dominio.TipoCategoria;
import com.raspybank.lancamento.servico.CategoriaServico;
import com.raspybank.shared.contexto.ContextoRequisicao;
import com.raspybank.shared.erro.OperacaoNaoPermitida;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Categorias e subcategorias — a tela T-04, contrato em {@code docs/api.md} §3.
 *
 * <h3>O ambiente e implicito</h3>
 *
 * <p>Nenhum endpoint recebe {@code ambienteId} no corpo nem na URL: ele vem do
 * token, via {@link ContextoRequisicao}. Recebe-lo do cliente convidaria a
 * enviar o de outro ambiente, e a checagem passaria a depender de o servidor
 * lembrar de conferir — exatamente o que o principio P4 proibe.</p>
 *
 * <h3>Por que os dois recursos vivem no mesmo controlador</h3>
 *
 * <p>Subcategoria nao tem vida propria: ela nasce dentro de uma categoria
 * ({@code POST /api/categorias/{id}/subcategorias}) e some com ela. Separar em
 * dois controladores duplicaria o mesmo preambulo de ambiente para atender uma
 * simetria de URL que ninguem pediu.</p>
 *
 * <h3>Nada aqui exclui nada</h3>
 *
 * <p>Nao existe {@code DELETE} (B-D4 / R8): arquivar tira do seletor e mantem
 * o historico nomeado. E por isso que o lancamento guarda so o id da
 * categoria — o id sempre resolve.</p>
 */
@RestController
public class CategoriaControlador {

    private final CategoriaServico categorias;

    public CategoriaControlador(CategoriaServico categorias) {
        this.categorias = categorias;
    }

    // =========================================================================
    // Categorias
    // =========================================================================

    @GetMapping("/api/categorias")
    public Map<String, Object> listar(
            @RequestParam(defaultValue = "false") boolean incluirArquivadas) {

        List<CategoriaResposta> lista = categorias.arvore(ambienteAtivo(), incluirArquivadas)
            .stream()
            .map(CategoriaResposta::de)
            .toList();

        return Map.of("categorias", lista);
    }

    @PostMapping("/api/categorias")
    public ResponseEntity<CategoriaResposta> criar(@Valid @RequestBody PedidoCategoria pedido) {
        Categoria c = categorias.criar(ambienteAtivo(), pedido.nome().trim(), pedido.tipo());
        return ResponseEntity.status(HttpStatus.CREATED).body(CategoriaResposta.de(c, List.of()));
    }

    @PutMapping("/api/categorias/{id}")
    public CategoriaResposta atualizar(@PathVariable UUID id,
                                       @Valid @RequestBody PedidoCategoria pedido) {
        Categoria c = categorias.atualizar(ambienteAtivo(), id, pedido.nome().trim(), pedido.tipo());
        return CategoriaResposta.de(c, List.of());
    }

    @PostMapping("/api/categorias/{id}/arquivar")
    public CategoriaResposta arquivar(@PathVariable UUID id) {
        return CategoriaResposta.de(categorias.arquivar(ambienteAtivo(), id), List.of());
    }

    @PostMapping("/api/categorias/{id}/desarquivar")
    public CategoriaResposta desarquivar(@PathVariable UUID id) {
        return CategoriaResposta.de(categorias.desarquivar(ambienteAtivo(), id), List.of());
    }

    // =========================================================================
    // Subcategorias
    // =========================================================================

    @PostMapping("/api/categorias/{id}/subcategorias")
    public ResponseEntity<SubcategoriaResposta> criarSubcategoria(
            @PathVariable UUID id, @Valid @RequestBody PedidoSubcategoria pedido) {

        Subcategoria s = categorias.criarSubcategoria(ambienteAtivo(), id, pedido.nome().trim());
        return ResponseEntity.status(HttpStatus.CREATED).body(SubcategoriaResposta.de(s));
    }

    @PutMapping("/api/subcategorias/{id}")
    public SubcategoriaResposta renomearSubcategoria(
            @PathVariable UUID id, @Valid @RequestBody PedidoSubcategoria pedido) {

        return SubcategoriaResposta.de(
            categorias.renomearSubcategoria(ambienteAtivo(), id, pedido.nome().trim()));
    }

    @PostMapping("/api/subcategorias/{id}/arquivar")
    public SubcategoriaResposta arquivarSubcategoria(@PathVariable UUID id) {
        return SubcategoriaResposta.de(categorias.arquivarSubcategoria(ambienteAtivo(), id));
    }

    @PostMapping("/api/subcategorias/{id}/desarquivar")
    public SubcategoriaResposta desarquivarSubcategoria(@PathVariable UUID id) {
        return SubcategoriaResposta.de(categorias.desarquivarSubcategoria(ambienteAtivo(), id));
    }

    // =========================================================================

    /**
     * O ambiente da sessao.
     *
     * <p>O filtro JWT ja garantiu a autenticacao, mas nem todo token carrega
     * ambiente: um emitido antes da claim existir, ou o de uma sessao que
     * ainda nao escolheu. Sem ambiente nao ha o que listar nem onde criar, e
     * a resposta honesta e 403 com o caminho da correcao — nao um 500.</p>
     */
    private static UUID ambienteAtivo() {
        return ContextoRequisicao.ambienteId().orElseThrow(() -> new OperacaoNaoPermitida(
            "Sessao sem ambiente ativo. Selecione um em POST /api/sessao/ambiente"));
    }

    // =========================================================================
    // Contrato de entrada e saida
    // =========================================================================

    public record PedidoCategoria(
        @NotBlank(message = "nome e obrigatorio")
        @Size(max = 60, message = "nome deve ter no maximo 60 caracteres")
        String nome,

        @NotNull(message = "tipo e obrigatorio: ENTRADA, SAIDA ou AMBOS")
        TipoCategoria tipo
    ) {}

    public record PedidoSubcategoria(
        @NotBlank(message = "nome e obrigatorio")
        @Size(max = 60, message = "nome deve ter no maximo 60 caracteres")
        String nome
    ) {}

    /**
     * {@code sistemica} e {@code entraNoMapa} viajam separados de proposito
     * (B-D15): a tela usa o primeiro para desenhar o cadeado e o segundo para
     * explicar a ausencia no mapa. Sao perguntas diferentes, e uma flag so
     * respondendo as duas foi o defeito que o I-01 ensinou a evitar.
     */
    public record CategoriaResposta(
        UUID id,
        String codigo,
        String nome,
        TipoCategoria tipo,
        boolean sistemica,
        boolean entraNoMapa,
        OffsetDateTime arquivadaEm,
        List<SubcategoriaResposta> subcategorias
    ) {
        static CategoriaResposta de(CategoriaServico.Ramo ramo) {
            return de(ramo.categoria(), ramo.subcategorias());
        }

        static CategoriaResposta de(Categoria c, List<Subcategoria> subs) {
            return new CategoriaResposta(
                c.getId(), c.getCodigo(), c.getNome(), c.getTipo(),
                c.isSistemica(), c.isEntraNoMapa(), c.getArquivadaEm(),
                subs.stream().map(SubcategoriaResposta::de).toList());
        }
    }

    public record SubcategoriaResposta(UUID id, String nome, OffsetDateTime arquivadaEm) {
        static SubcategoriaResposta de(Subcategoria s) {
            return new SubcategoriaResposta(s.getId(), s.getNome(), s.getArquivadaEm());
        }
    }
}
