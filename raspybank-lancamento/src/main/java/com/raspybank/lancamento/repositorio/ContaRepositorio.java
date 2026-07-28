package com.raspybank.lancamento.repositorio;

import com.raspybank.lancamento.dominio.Conta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Acesso a dados de Conta.
 *
 * <p><b>Nao existe metodo de criacao aqui, e nao e esquecimento.</b> Conta
 * nasce pela porta estreita {@code app_criar_conta(...)}, que grava a conta e
 * o vinculo na mesma transacao — um {@code save()} direto gravaria uma linha
 * que nem quem a criou consegue ler depois, porque a politica de RLS so
 * enxerga conta ja vinculada. O detalhe esta em
 * {@link com.raspybank.lancamento.dominio.Conta} e em
 * {@code docs/security-definer.md}.</p>
 *
 * <p>Leitura e alteracao passam normalmente por aqui: depois de vinculada, a
 * politica enxerga a conta.</p>
 */
public interface ContaRepositorio extends JpaRepository<Conta, UUID> {

    /**
     * Contas visiveis no ambiente informado.
     *
     * <p>Precisa de consulta escrita a mao porque {@code conta} nao tem
     * coluna de ambiente (R7): a resposta esta no vinculo. Um metodo derivado
     * do nome nao alcanca outra tabela.</p>
     *
     * <p>A subconsulta nao substitui a RLS — ela recorta, dentro do que a
     * politica ja liberou, o ambiente ativo da sessao.</p>
     */
    @Query("""
        SELECT c FROM Conta c
         WHERE c.id IN (
               SELECT ca.contaId FROM ContaAmbiente ca WHERE ca.ambienteId = :ambienteId)
         ORDER BY c.nome
        """)
    List<Conta> doAmbiente(@Param("ambienteId") UUID ambienteId);

    /** O que o seletor da T-08 usa: conta encerrada nao recebe lancamento novo (F7). */
    @Query("""
        SELECT c FROM Conta c
         WHERE c.encerradaEm IS NULL
           AND c.id IN (
               SELECT ca.contaId FROM ContaAmbiente ca WHERE ca.ambienteId = :ambienteId)
         ORDER BY c.nome
        """)
    List<Conta> ativasDoAmbiente(@Param("ambienteId") UUID ambienteId);

    /**
     * As contas BANCARIAS: as mesmas de cima, tirando as que sao cartao.
     *
     * <p>O cartao e uma conta {@code PASSIVO} (B-D47) porque a divida dele e
     * saldo, e saldo e soma de lancamentos. Mas nos testes de negocio de
     * 28/07/2026 ficou claro que <b>mostrar o cartao na tela de contas
     * confunde</b> — palavras dele: "tratar o cartao de credito como um banco
     * confunde" (B-D62).</p>
     *
     * <p>Nao e a mesma coisa que esconder a divida: ela continua no patrimonio
     * (F6) e aparece inteira na tela de cartoes. O que sai e o cartao fingindo
     * ser um lugar onde se guarda dinheiro.</p>
     *
     * <p>O recorte fica no repositorio, e nao em cada tela, pelo motivo de
     * sempre: quatro telas lembram, a quinta esquece.</p>
     */
    @Query("""
        SELECT c FROM Conta c
         WHERE c.id IN (
               SELECT ca.contaId FROM ContaAmbiente ca WHERE ca.ambienteId = :ambienteId)
           AND NOT EXISTS (SELECT 1 FROM Cartao k WHERE k.contaId = c.id)
           AND (:incluirEncerradas = TRUE OR c.encerradaEm IS NULL)
         ORDER BY c.nome
        """)
    List<Conta> bancariasDoAmbiente(@Param("ambienteId") UUID ambienteId,
                                    @Param("incluirEncerradas") boolean incluirEncerradas);
}
