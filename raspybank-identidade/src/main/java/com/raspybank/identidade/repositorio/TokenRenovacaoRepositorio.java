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
