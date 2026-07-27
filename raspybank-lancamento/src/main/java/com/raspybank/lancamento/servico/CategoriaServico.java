package com.raspybank.lancamento.servico;

import com.raspybank.lancamento.dominio.Categoria;
import com.raspybank.lancamento.dominio.Subcategoria;
import com.raspybank.lancamento.dominio.TipoCategoria;
import com.raspybank.lancamento.repositorio.CategoriaRepositorio;
import com.raspybank.lancamento.repositorio.SubcategoriaRepositorio;
import com.raspybank.shared.erro.RecursoNaoEncontrado;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Casos de uso da classificacao (T-04).
 *
 * <h3>Por que todo metodo recebe {@code ambienteId}</h3>
 *
 * <p>Decisao B-D21. A politica de RLS devolve as categorias de <b>todos</b> os
 * ambientes do usuario, porque o tenant e o usuario (R7). Isso protege contra
 * terceiros e nao contra o proprio: sem o recorte por ambiente, a categoria
 * "Mercado" do ambiente da casa apareceria — e seria editavel — dentro do
 * ambiente do freelance.</p>
 *
 * <p>Por isso {@link #exigir} confere o ambiente antes de devolver qualquer
 * entidade, e o resultado de um id valido em ambiente errado e
 * <b>404</b>, nunca 403: distinguir os dois transformaria a API num oraculo
 * sobre quais ids existem.</p>
 *
 * <h3>Onde estao as regras</h3>
 *
 * <p>Nao estao aqui. O cadeado da sistemica vive em {@link Categoria}, e a
 * unicidade do nome vive no indice parcial {@code ux_categoria_nome}. Este
 * servico orquestra: busca, delega, grava. Um servico que valida por conta
 * propria e um servico que vai divergir da entidade em algum caminho.</p>
 */
@Service
public class CategoriaServico {

    private final CategoriaRepositorio categorias;
    private final SubcategoriaRepositorio subcategorias;

    public CategoriaServico(CategoriaRepositorio categorias,
                            SubcategoriaRepositorio subcategorias) {
        this.categorias = categorias;
        this.subcategorias = subcategorias;
    }

    // =========================================================================
    // Leitura
    // =========================================================================

    /**
     * A arvore completa da T-04, em <b>duas</b> consultas.
     *
     * <p>Uma para as categorias e outra para todas as subcategorias delas — e
     * nao uma por categoria. E o motivo de {@link Categoria} nao ter colecao
     * mapeada: com {@code @OneToMany}, esta economia dependeria de configurar
     * o carregamento certo em cada consulta, e o padrao erraria calado.</p>
     */
    @Transactional(readOnly = true)
    public List<Ramo> arvore(UUID ambienteId, boolean incluirArquivadas) {

        List<Categoria> raizes = incluirArquivadas
            ? categorias.findByAmbienteIdOrderByNome(ambienteId)
            : categorias.findByAmbienteIdAndArquivadaEmIsNullOrderByNome(ambienteId);

        if (raizes.isEmpty()) {
            return List.of();
        }

        List<UUID> ids = raizes.stream().map(Categoria::getId).toList();

        Map<UUID, List<Subcategoria>> porCategoria = new HashMap<>();
        for (Subcategoria s : subcategorias.findByCategoriaIdInOrderByNome(ids)) {
            if (incluirArquivadas || !s.estaArquivada()) {
                porCategoria.computeIfAbsent(s.getCategoriaId(), k -> new ArrayList<>()).add(s);
            }
        }

        return raizes.stream()
            .map(c -> new Ramo(c, porCategoria.getOrDefault(c.getId(), List.of())))
            .toList();
    }

    // =========================================================================
    // Categoria
    // =========================================================================

    /**
     * Cria uma categoria comum.
     *
     * <p>Nome repetido nao e conferido aqui: quem responde e o indice
     * {@code ux_categoria_nome}, que ignora as arquivadas e compara sem
     * maiusculas. Uma checagem previa em Java daria a mesma resposta na maior
     * parte das vezes e abriria uma janela entre a consulta e a gravacao —
     * duas abas do navegador bastariam para atravessa-la.</p>
     */
    @Transactional
    public Categoria criar(UUID ambienteId, String nome, TipoCategoria tipo) {
        return categorias.save(new Categoria(ambienteId, nome, tipo));
    }

    @Transactional
    public Categoria atualizar(UUID ambienteId, UUID id, String nome, TipoCategoria tipo) {
        Categoria c = exigir(ambienteId, id);
        c.renomear(nome);
        c.mudarTipo(tipo);
        return c;
    }

    @Transactional
    public Categoria arquivar(UUID ambienteId, UUID id) {
        Categoria c = exigir(ambienteId, id);
        c.arquivar(OffsetDateTime.now());
        return c;
    }

    @Transactional
    public Categoria desarquivar(UUID ambienteId, UUID id) {
        Categoria c = exigir(ambienteId, id);
        c.desarquivar();
        return c;
    }

    // =========================================================================
    // Subcategoria
    // =========================================================================

    @Transactional
    public Subcategoria criarSubcategoria(UUID ambienteId, UUID categoriaId, String nome) {
        Categoria mae = exigir(ambienteId, categoriaId);
        return subcategorias.save(new Subcategoria(mae, nome));
    }

    @Transactional
    public Subcategoria renomearSubcategoria(UUID ambienteId, UUID id, String nome) {
        Subcategoria s = exigirSubcategoria(ambienteId, id);
        s.renomear(nome);
        return s;
    }

    @Transactional
    public Subcategoria arquivarSubcategoria(UUID ambienteId, UUID id) {
        Subcategoria s = exigirSubcategoria(ambienteId, id);
        s.arquivar(OffsetDateTime.now());
        return s;
    }

    @Transactional
    public Subcategoria desarquivarSubcategoria(UUID ambienteId, UUID id) {
        Subcategoria s = exigirSubcategoria(ambienteId, id);
        s.desarquivar();
        return s;
    }

    // =========================================================================
    // Busca com recorte de ambiente
    // =========================================================================

    /**
     * Busca conferindo o ambiente ativo — o recorte de B-D21.
     *
     * <p>Categoria de outro usuario nem chega aqui: o RLS a esconde e o
     * {@code findById} volta vazio. Categoria de <b>outro ambiente seu</b>
     * chega, e e este {@code if} que a barra.</p>
     */
    private Categoria exigir(UUID ambienteId, UUID id) {
        return categorias.findById(id)
            .filter(c -> c.getAmbienteId().equals(ambienteId))
            .orElseThrow(() -> new RecursoNaoEncontrado("Categoria nao encontrada"));
    }

    private Subcategoria exigirSubcategoria(UUID ambienteId, UUID id) {
        return subcategorias.findById(id)
            .filter(s -> s.getAmbienteId().equals(ambienteId))
            .orElseThrow(() -> new RecursoNaoEncontrado("Subcategoria nao encontrada"));
    }

    /** Uma categoria com as subcategorias dela — o formato que a T-04 desenha. */
    public record Ramo(Categoria categoria, List<Subcategoria> subcategorias) {
    }
}
