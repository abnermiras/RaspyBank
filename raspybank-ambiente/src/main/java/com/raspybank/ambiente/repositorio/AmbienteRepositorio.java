package com.raspybank.ambiente.repositorio;

import com.raspybank.ambiente.dominio.Ambiente;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Acesso a dados de Ambiente.
 *
 * <p>Novamente sem filtro explicito: a politica {@code pol_ambiente_vinculado}
 * ja restringe o resultado aos ambientes do usuario da sessao. Um
 * {@code findAll()} devolve exatamente os ambientes a que a pessoa pertence —
 * o que e, por acaso, o resultado que a aplicacao quase sempre quer.</p>
 */
public interface AmbienteRepositorio extends JpaRepository<Ambiente, UUID> {
}
