package com.raspybank.identidade.repositorio;

import com.raspybank.identidade.dominio.TokenRenovacao;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface TokenRenovacaoRepositorio extends JpaRepository<TokenRenovacao, UUID> {

    Optional<TokenRenovacao> findByTokenHash(String tokenHash);

    /**
     * Reivindica o uso do token de forma ATOMICA: so a primeira requisicao
     * que chegar leva o 1; qualquer outra recebe 0.
     *
     * <p>E a correcao da inconsistencia I-11: o par leitura + {@code
     * marcarUsado()} na entidade abria uma janela em que duas requisicoes
     * simultaneas com o MESMO token passavam ambas pela checagem e ambas
     * recebiam tokens novos — exatamente o cenario que a deteccao de reuso
     * existe para pegar. O WHERE com {@code usado_em IS NULL} faz o proprio
     * banco arbitrar a corrida: o UPDATE e serializado pela trava de linha.</p>
     *
     * <p>{@code clearAutomatically} descarta o cache de primeiro nivel para a
     * entidade nao esconder o valor recem-gravado.</p>
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE TokenRenovacao t SET t.usadoEm = :agora "
         + "WHERE t.tokenHash = :tokenHash AND t.usadoEm IS NULL")
    int marcarUsadoSeInedito(@Param("tokenHash") String tokenHash,
                             @Param("agora") OffsetDateTime agora);

    /**
     * Revoga a familia inteira de tokens.
     *
     * <p>Acionado quando um token ja usado reaparece — o que significa que
     * alguem tem uma copia. Como nao ha como saber quem e o legitimo, o
     * sistema derruba todas as sessoes daquela cadeia e obriga novo login.</p>
     */
    @Modifying
    @Query("UPDATE TokenRenovacao t SET t.revogadoEm = :agora "
         + "WHERE t.familiaId = :familiaId AND t.revogadoEm IS NULL")
    int revogarFamilia(@Param("familiaId") UUID familiaId,
                       @Param("agora") OffsetDateTime agora);

    @Modifying
    @Query("UPDATE TokenRenovacao t SET t.revogadoEm = :agora "
         + "WHERE t.usuarioId = :usuarioId AND t.revogadoEm IS NULL")
    int revogarTodosDoUsuario(@Param("usuarioId") UUID usuarioId,
                              @Param("agora") OffsetDateTime agora);
}
