package com.raspybank.ambiente.servico;

import com.raspybank.ambiente.dominio.Ambiente;
import com.raspybank.ambiente.dominio.UsuarioAmbiente;
import com.raspybank.ambiente.repositorio.AmbienteRepositorio;
import com.raspybank.ambiente.repositorio.UsuarioAmbienteRepositorio;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AmbienteServico {

    private final AmbienteRepositorio ambientes;
    private final UsuarioAmbienteRepositorio vinculos;

    @PersistenceContext
    private EntityManager em;

    public AmbienteServico(AmbienteRepositorio ambientes,
                           UsuarioAmbienteRepositorio vinculos) {
        this.ambientes = ambientes;
        this.vinculos = vinculos;
    }

    /**
     * Cria o primeiro ambiente da pessoa, no momento do cadastro.
     *
     * <p>E o unico ambiente criado automaticamente. Do segundo em diante, a
     * criacao e sempre explicita. A excecao existe porque, no cadastro, nao
     * ha ninguem para pedir: a pessoa ainda nao tem tela nenhuma disponivel.</p>
     *
     * <p>Usa SQL direto porque, neste instante, a sessao ainda nao tem
     * identidade definida — as politicas de Row Level Security recusariam
     * tanto a insercao do ambiente quanto a do vinculo. E a mesma situacao do
     * cadastro de usuario, e a mesma solucao.</p>
     */
    @Transactional
    public UUID criarInicial(UUID usuarioId, String nome) {

        Object id = em.createNativeQuery(
                "SELECT auth_criar_ambiente_inicial(:usuario, :nome)")
            .setParameter("usuario", usuarioId)
            .setParameter("nome", nome)
            .getSingleResult();

        return (UUID) id;
    }

    /**
     * Ambientes da pessoa.
     *
     * <p>Depende do Row Level Security estar com a identidade definida: sem
     * isso devolve lista vazia, que e o comportamento seguro.</p>
     */
    @Transactional(readOnly = true)
    public List<Ambiente> listarDoUsuario() {
        return ambientes.findAll();
    }

    /**
     * Ambientes de um usuario, consultados SEM depender do contexto de sessao.
     *
     * <p>Usado no login: naquele instante a identidade ainda nao foi definida
     * na sessao do banco, entao as politicas de Row Level Security devolveriam
     * lista vazia. Aqui a filtragem e explicita, pelo parametro.</p>
     *
     * <p>Uso restrito ao fluxo de autenticacao. Em qualquer outro lugar, use
     * {@link #listarDoUsuario()} e deixe o banco filtrar.</p>
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Object> listarDoUsuarioSemContexto(UUID usuarioId) {
        return em.createNativeQuery(
                "SELECT auth_ambientes_do_usuario(:usuario)")
            .setParameter("usuario", usuarioId)
            .getResultList();
    }

    @Transactional(readOnly = true)
    public boolean usuarioPertence(UUID usuarioId, UUID ambienteId) {
        return vinculos.findByUsuarioId(usuarioId).stream()
            .anyMatch(v -> v.getAmbienteId().equals(ambienteId));
    }
}
