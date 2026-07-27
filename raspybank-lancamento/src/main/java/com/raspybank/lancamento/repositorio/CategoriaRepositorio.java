package com.raspybank.lancamento.repositorio;

import com.raspybank.lancamento.dominio.Categoria;
import com.raspybank.lancamento.dominio.CodigoSistemico;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Acesso a dados de Categoria.
 *
 * <h3>Por que aqui o ambiente aparece no metodo, e em Ambiente nao aparecia</h3>
 *
 * <p>Nos repositorios de identidade e ambiente, um {@code findAll()} bastava:
 * a politica de RLS ja devolvia exatamente o que a pessoa podia ver. Aqui
 * nao basta, e a diferenca importa.</p>
 *
 * <p>O tenant e o USUARIO (A08/R7), e uma pessoa pode pertencer a varios
 * ambientes — o pessoal, o da casa, o do freelance. A politica
 * {@code pol_categoria_ambiente} devolve as categorias de <b>todos</b> eles.
 * Isso e correto do ponto de vista de seguranca e errado do ponto de vista
 * da tela: a T-04 mostra as categorias do ambiente ATIVO, e so.</p>
 *
 * <p><b>A regra que vale para todo este modulo:</b> a RLS decide o que voce
 * <i>pode</i> ver; o {@code ambienteId} decide o que voce <i>quer</i> ver
 * agora. Esquecer o segundo nao vaza dado de terceiro — vaza o seu proprio
 * dado de outro ambiente para dentro da tela errada, que e um defeito de
 * produto e nao de seguranca. Nenhum dos dois pode faltar.</p>
 */
public interface CategoriaRepositorio extends JpaRepository<Categoria, UUID> {

    /** Todas do ambiente, arquivadas inclusive — {@code ?incluirArquivadas=true}. */
    List<Categoria> findByAmbienteIdOrderByNome(UUID ambienteId);

    /** O padrao da T-04 e do seletor da T-08: so as ativas. */
    List<Categoria> findByAmbienteIdAndArquivadaEmIsNullOrderByNome(UUID ambienteId);

    /**
     * Encontra uma sistemica pelo codigo (F10).
     *
     * <p>E o caminho que o servico do Telegram vai usar para achar
     * {@code NAO_CLASSIFICADO}, e o de transferencia para achar
     * {@code TRANSFERENCIA}. Nunca buscar sistemica por nome: nome e
     * apresentacao, codigo e identidade.</p>
     *
     * <p>Recebe o enum e nao a String para que um codigo inexistente nao
     * compile — ver {@link CodigoSistemico}.</p>
     */
    default Optional<Categoria> buscarSistemica(UUID ambienteId, CodigoSistemico codigo) {
        return findByAmbienteIdAndCodigo(ambienteId, codigo.name());
    }

    Optional<Categoria> findByAmbienteIdAndCodigo(UUID ambienteId, String codigo);
}
