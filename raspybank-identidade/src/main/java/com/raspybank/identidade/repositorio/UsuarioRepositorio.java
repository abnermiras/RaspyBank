package com.raspybank.identidade.repositorio;

import com.raspybank.identidade.dominio.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

/**
 * Acesso a dados de Usuario.
 *
 * <p>Nao ha filtro por ambiente aqui, e isso e proposital: a politica de Row
 * Level Security {@code pol_usuario_proprio} ja garante que cada um so enxerga
 * a si mesmo. Um {@code findAll()} executado por esta aplicacao devolve, no
 * maximo, uma linha.</p>
 */
public interface UsuarioRepositorio extends JpaRepository<Usuario, UUID> {

    /** Busca ignorando maiusculas e minusculas, igual ao indice ux_usuario_email. */
    @Query("SELECT u FROM Usuario u WHERE lower(u.email) = lower(:email)")
    Optional<Usuario> buscarPorEmail(String email);

    Optional<Usuario> findByTelegramId(String telegramId);
}
