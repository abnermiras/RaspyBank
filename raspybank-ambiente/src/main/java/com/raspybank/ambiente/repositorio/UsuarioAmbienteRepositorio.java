package com.raspybank.ambiente.repositorio;

import com.raspybank.ambiente.dominio.UsuarioAmbiente;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UsuarioAmbienteRepositorio
        extends JpaRepository<UsuarioAmbiente, UsuarioAmbiente.Chave> {

    List<UsuarioAmbiente> findByUsuarioId(UUID usuarioId);
}
