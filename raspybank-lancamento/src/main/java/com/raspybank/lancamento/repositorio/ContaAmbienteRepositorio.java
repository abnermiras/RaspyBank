package com.raspybank.lancamento.repositorio;

import com.raspybank.lancamento.dominio.ContaAmbiente;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Acesso ao vinculo conta x ambiente.
 *
 * <p>Serve a dois casos: compartilhar uma conta propria num segundo ambiente
 * seu, e deixar de compartilhar. Criar conta nova nao passa por aqui — passa
 * pela porta estreita, ver {@link ContaRepositorio}.</p>
 *
 * <p>Um {@code save()} de vinculo e legitimo e continua protegido: o
 * {@code WITH CHECK} de {@code pol_ca_ambiente} confere os <b>dois</b> lados,
 * entao vincular conta que nao se enxerga falha no banco, nao aqui (B-D18).</p>
 */
public interface ContaAmbienteRepositorio
        extends JpaRepository<ContaAmbiente, ContaAmbiente.Chave> {

    List<ContaAmbiente> findByContaId(UUID contaId);

    /** Uma consulta para a tela inteira: em que ambientes cada conta aparece. */
    List<ContaAmbiente> findByContaIdIn(Collection<UUID> contaIds);

    List<ContaAmbiente> findByAmbienteId(UUID ambienteId);
}
