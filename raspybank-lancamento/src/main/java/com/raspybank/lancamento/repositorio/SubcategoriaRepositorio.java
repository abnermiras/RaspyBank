package com.raspybank.lancamento.repositorio;

import com.raspybank.lancamento.dominio.Subcategoria;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Acesso a dados de Subcategoria.
 *
 * <p>A T-04 mostra os dois niveis juntos e o {@code GET /api/categorias}
 * devolve aninhado. O aninhamento se monta aqui, com <b>uma</b> consulta
 * pelas categorias ja carregadas — nao com uma consulta por categoria.</p>
 *
 * <p>E por isso que {@link com.raspybank.lancamento.dominio.Categoria} nao
 * tem colecao {@code @OneToMany}: o mapeamento resolveria o aninhamento
 * automaticamente e cobraria o preco em toda leitura de categoria,
 * inclusive nas do relatorio, que nao usam subcategoria nenhuma.</p>
 */
public interface SubcategoriaRepositorio extends JpaRepository<Subcategoria, UUID> {

    /** Uma consulta para todas as categorias da tela, evitando o N+1. */
    List<Subcategoria> findByCategoriaIdInOrderByNome(Collection<UUID> categoriaIds);

    List<Subcategoria> findByCategoriaIdAndArquivadaEmIsNullOrderByNome(UUID categoriaId);
}
