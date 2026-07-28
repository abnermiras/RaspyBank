package com.raspybank.lancamento.repositorio;

import com.raspybank.lancamento.dominio.Fatura;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Acesso aos ciclos de cada contrato. */
public interface FaturaRepositorio extends JpaRepository<Fatura, UUID> {

    List<Fatura> findByCartaoIdOrderByVencimento(UUID cartaoId);

    @Query("""
        SELECT f FROM Fatura f
         WHERE f.cartaoId = :cartaoId
           AND f.mesReferencia BETWEEN :inicio AND :fim
         ORDER BY f.vencimento
        """)
    List<Fatura> doAno(@Param("cartaoId") UUID cartaoId,
                       @Param("inicio") LocalDate inicio,
                       @Param("fim") LocalDate fim);

    Optional<Fatura> findByCartaoIdAndMesReferencia(UUID cartaoId, LocalDate mesReferencia);

    /**
     * A fatura que deve receber uma compra feita nesta data.
     *
     * <p>A primeira ABERTA cujo fechamento ainda nao passou. As duas condicoes
     * juntas produzem a regra que o Abner enunciou — <i>"fatura fechada,
     * lancamento vai para o proximo"</i> — inclusive quando a data da compra e
     * anterior ao fechamento: se a fatura daquele ciclo foi fechada na mao, ela
     * sai do resultado e a compra cai na seguinte.</p>
     */
    @Query("""
        SELECT f FROM Fatura f
         WHERE f.cartaoId = :cartaoId
           AND f.fechadaEm IS NULL
           AND f.fechamentoPrevisto >= :dataDaCompra
         ORDER BY f.vencimento
         LIMIT 1
        """)
    Optional<Fatura> abertaPara(@Param("cartaoId") UUID cartaoId,
                                @Param("dataDaCompra") LocalDate dataDaCompra);

    /** A ultima gerada, para saber de onde continuar ao estender o horizonte. */
    Optional<Fatura> findFirstByCartaoIdOrderByMesReferenciaDesc(UUID cartaoId);

    /** As faturas ainda abertas cujo fechamento previsto ja passou. */
    @Query("""
        SELECT f FROM Fatura f
         WHERE f.cartaoId IN :cartaoIds
           AND f.fechadaEm IS NULL
           AND f.fechamentoPrevisto <= :hoje
        """)
    List<Fatura> vencidasParaFechar(@Param("cartaoIds") List<UUID> cartaoIds,
                                    @Param("hoje") LocalDate hoje);
}
