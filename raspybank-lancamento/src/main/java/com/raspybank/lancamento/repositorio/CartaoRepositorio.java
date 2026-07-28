package com.raspybank.lancamento.repositorio;

import com.raspybank.lancamento.dominio.Cartao;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Acesso ao contrato de cartao.
 *
 * <p>O cartao E uma conta (B-D47), entao {@code pol_cartao_conta} ja o recorta
 * por {@code app_contas_do_usuario()}. O filtro por ambiente continua sendo
 * responsabilidade de quem consulta, pelo motivo de B-D21: a RLS decide o que
 * voce PODE ver, o ambiente decide o que voce QUER ver agora.</p>
 */
public interface CartaoRepositorio extends JpaRepository<Cartao, UUID> {

    /**
     * Os cartoes visiveis num ambiente, via o vinculo da conta.
     *
     * <p>O JOIN e com {@code ContaAmbiente} e nao com uma coluna de ambiente
     * porque cartao, sendo conta, tambem nao tem essa coluna (R7).</p>
     */
    @Query("""
        SELECT c FROM Cartao c
         WHERE c.contaId IN (
               SELECT ca.contaId FROM ContaAmbiente ca WHERE ca.ambienteId = :ambienteId)
         ORDER BY c.nome
        """)
    List<Cartao> doAmbiente(@Param("ambienteId") UUID ambienteId);

    @Query("""
        SELECT c FROM Cartao c
         WHERE c.contaId IN (
               SELECT ca.contaId FROM ContaAmbiente ca WHERE ca.ambienteId = :ambienteId)
           AND c.encerradoEm IS NULL
         ORDER BY c.nome
        """)
    List<Cartao> ativosDoAmbiente(@Param("ambienteId") UUID ambienteId);

    /** Existe cartao apontando para esta conta como banco? Guarda o encerramento. */
    long countByContaBancoId(UUID contaBancoId);
}
