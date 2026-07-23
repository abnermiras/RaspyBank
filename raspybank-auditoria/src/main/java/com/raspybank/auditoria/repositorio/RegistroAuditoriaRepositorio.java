package com.raspybank.auditoria.repositorio;

import com.raspybank.auditoria.dominio.RegistroAuditoria;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RegistroAuditoriaRepositorio
        extends JpaRepository<RegistroAuditoria, UUID> {
}
