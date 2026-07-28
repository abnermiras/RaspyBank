package com.raspybank.lancamento.repositorio;

import com.raspybank.lancamento.dominio.CartaoEmitido;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Acesso aos plasticos e virtuais de cada contrato. */
public interface CartaoEmitidoRepositorio extends JpaRepository<CartaoEmitido, UUID> {

    List<CartaoEmitido> findByCartaoIdOrderByCriadoEm(UUID cartaoId);

    /** Uma consulta para a tela inteira, nao uma por cartao. */
    List<CartaoEmitido> findByCartaoIdIn(Collection<UUID> cartaoIds);
}
