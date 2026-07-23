package com.raspybank.auditoria.servico;

import com.raspybank.auditoria.dominio.RegistroAuditoria;
import com.raspybank.auditoria.repositorio.RegistroAuditoriaRepositorio;
import com.raspybank.shared.contexto.Canal;
import com.raspybank.shared.contexto.Operacao;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Grava os registros de auditoria.
 *
 * <p>A escrita acontece na MESMA transacao da operacao auditada. Isso e
 * proposital: se a operacao for desfeita, a auditoria e desfeita junto. Nao
 * queremos registro de algo que nao aconteceu.</p>
 *
 * <p><b>Contrato do Bloco A (23/07/2026):</b> as assinaturas publicas exigem
 * os enums {@link Canal} e {@link Operacao} — literal de texto nao compila
 * mais. Este servico e o UNICO ponto do sistema onde enum vira texto
 * (via {@code name()}), na borda com o banco. A entidade e a funcao SQL
 * continuam recebendo texto porque as colunas sao {@code text} com CHECK;
 * como {@code name()} e identico ao valor do CHECK por construcao, a
 * gravacao correta e garantida pelo compilador + convencao, nao por
 * disciplina de quem chama.</p>
 */
@Service
public class AuditoriaServico {

    private final RegistroAuditoriaRepositorio repositorio;

    @PersistenceContext
    private EntityManager em;

    public AuditoriaServico(RegistroAuditoriaRepositorio repositorio) {
        this.repositorio = repositorio;
    }

    @Transactional
    public void registrar(UUID ambienteId, UUID usuarioId, Canal canal,
                          String entidade, UUID entidadeId, Operacao operacao,
                          String estadoAnterior, String estadoNovo) {

        // Conversao enum -> texto acontece AQUI, e somente aqui.
        repositorio.save(new RegistroAuditoria(
            ambienteId, usuarioId, canal.name(), entidade, entidadeId,
            operacao.name(), estadoAnterior, estadoNovo));
    }

    /**
     * Registra eventos de autenticacao — cadastro e login.
     *
     * <p>Grava por SQL direto pelo mesmo motivo do cadastro: no instante do
     * login a sessao ainda nao tem identidade, e a politica de Row Level
     * Security recusaria a insercao.</p>
     *
     * <p>Roda em transacao PROPRIA ({@code REQUIRES_NEW}) para que o registro
     * de uma tentativa de login sobreviva mesmo que a operacao principal
     * termine em erro. Tentativa fracassada e justamente o que mais interessa
     * auditar.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrarAutenticacao(UUID usuarioId, Canal canal,
                                      Operacao operacao, String detalhe) {

        em.createNativeQuery(
                "SELECT auth_registrar_evento(:usuario, :canal, :operacao, CAST(:detalhe AS jsonb))")
            .setParameter("usuario", usuarioId)
            .setParameter("canal", canal.name())
            .setParameter("operacao", operacao.name())
            .setParameter("detalhe", detalhe)
            .getSingleResult();
    }
}