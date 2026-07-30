package com.raspybank.ambiente.servico;

import com.raspybank.ambiente.dominio.Ambiente;
import com.raspybank.ambiente.dominio.UsuarioAmbiente;
import com.raspybank.ambiente.repositorio.AmbienteRepositorio;
import com.raspybank.ambiente.repositorio.UsuarioAmbienteRepositorio;
import com.raspybank.shared.erro.ConflitoDeEstado;
import com.raspybank.shared.erro.OperacaoNaoPermitida;
import com.raspybank.shared.erro.RecursoNaoEncontrado;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    /**
     * Cria um ambiente ADICIONAL, escolhido por quem esta logado.
     *
     * <p>Passa pela porta estreita {@code app_criar_ambiente} (V14) pelo mesmo
     * impasse de {@code app_criar_conta}: {@code pol_ambiente_vinculado}
     * pergunta se o ambiente esta entre os do usuario, e para um que esta
     * nascendo a resposta e sempre NAO. Nenhuma ordem de INSERT resolve.</p>
     *
     * <p>Diferente de {@link #criarInicial}, que e chamada no cadastro e recusa
     * se o usuario ja tiver ambiente (A12), esta existe justamente para o
     * segundo em diante. A identidade vem da sessao, nao de parametro.</p>
     */
    @Transactional
    public UUID criar(String nome) {
        UUID id = (UUID) em.createNativeQuery("SELECT app_criar_ambiente(:nome)")
            .setParameter("nome", nome)
            .getSingleResult();

        // O flush explicito faz a funcao rodar AGORA. Sem ele o Hibernate
        // poderia adiar a consulta nativa, e quem ler a lista logo depois nao
        // encontraria o ambiente recem-criado.
        em.flush();
        return id;
    }

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
    public List<UUID> listarDoUsuarioSemContexto(UUID usuarioId) {
        return (List<UUID>) em.createNativeQuery(
                "SELECT auth_ambientes_do_usuario(:usuario)", UUID.class)
            .setParameter("usuario", usuarioId)
            .getResultList();
    }

    /**
     * O vinculo ainda existe?
     *
     * <p>Busca por chave primaria, porque B-D83 a faz rodar em TODA requisicao
     * autenticada: e a conferencia de que o ambiente do token ainda pertence a
     * quem o apresenta. Com RLS ativo, vinculo de ambiente alheio (ou de
     * ambiente excluido) e simplesmente invisivel — a resposta segura sai por
     * construcao.</p>
     */
    @Transactional(readOnly = true)
    public boolean usuarioPertence(UUID usuarioId, UUID ambienteId) {
        return vinculos.existsById(new UsuarioAmbiente.Chave(usuarioId, ambienteId));
    }

    /** Ambientes em que a pessoa e DONA — para a tela saber onde ha porta. */
    @Transactional(readOnly = true)
    public Set<UUID> ambientesProprios(UUID usuarioId) {
        return vinculos.findByUsuarioId(usuarioId).stream()
            .filter(UsuarioAmbiente::isDono)
            .map(UsuarioAmbiente::getAmbienteId)
            .collect(Collectors.toSet());
    }

    // =========================================================================
    // Compartilhamento (V15) — §4j, B-D74 a B-D82
    // =========================================================================

    /** Um membro do ambiente, como a lista de acessos mostra (api.md §2c). */
    public record Acesso(UUID usuarioId, String nome, String email, boolean dono) {}

    /**
     * Os acessos do ambiente: quem esta dentro, e quem e o dono.
     *
     * <p>Qualquer membro ve a lista — quem compartilha financas precisa saber
     * com quem compartilha. Ambiente que nao e seu responde como inexistente
     * (B-D25): distinguir "nao existe" de "nao e seu" transformaria a API num
     * oraculo sobre quais ids existem.</p>
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Acesso> listarAcessos(UUID usuarioId, UUID ambienteId) {
        exigirVinculo(usuarioId, ambienteId);

        // Nativa porque Usuario vive no modulo de identidade e este vinculo
        // nao mapeia relacao com ele. O RLS ja libera a linha cadastral dos
        // co-membros (pol_usuario_leitura, V15).
        List<Object[]> linhas = em.createNativeQuery("""
                SELECT ua.usuario_id, u.nome, u.email, ua.dono
                  FROM usuario_ambiente ua
                  JOIN usuario u ON u.id = ua.usuario_id
                 WHERE ua.ambiente_id = :ambiente
                 ORDER BY ua.dono DESC, ua.criado_em
                """)
            .setParameter("ambiente", ambienteId)
            .getResultList();

        return linhas.stream()
            .map(l -> new Acesso((UUID) l[0], (String) l[1], (String) l[2], (Boolean) l[3]))
            .toList();
    }

    /**
     * Concede acesso ao ambiente, por e-mail — imediato, sem aceite (B-D80).
     *
     * <p>So o dono concede (B-D76): conceder e porta, nao dinheiro. O RLS
     * repete a regra no banco ({@code pol_ua_conceder}); a checagem aqui existe
     * para a recusa virar uma frase em vez de uma violacao de politica.</p>
     *
     * <p>E-mail nao cadastrado responde 404 dizendo isso. E um oraculo de
     * enumeracao aceito conscientemente (B-D81): esconder o erro puniria o caso
     * mais comum de todos, o e-mail do proprio conhecido digitado errado.</p>
     */
    @Transactional
    public void concederAcesso(UUID usuarioId, UUID ambienteId, String email) {
        UsuarioAmbiente meu = exigirVinculo(usuarioId, ambienteId);
        if (!meu.isDono()) {
            throw new OperacaoNaoPermitida("So o dono do ambiente concede acesso");
        }

        UUID convidado = (UUID) em.createNativeQuery(
                "SELECT app_usuario_por_email(:email)")
            .setParameter("email", email)
            .getSingleResult();
        if (convidado == null) {
            throw new RecursoNaoEncontrado("Nenhum usuario cadastrado com este e-mail");
        }

        if (vinculos.existsById(new UsuarioAmbiente.Chave(convidado, ambienteId))) {
            throw new ConflitoDeEstado("Esta pessoa ja tem acesso a este ambiente");
        }

        // UMA LINHA (B-D74): inserido o vinculo, o ambiente aparece na lista
        // da pessoa e todas as politicas ja respondem certo. A linha nasce
        // nao-dono, e o gatilho de auditoria carimba quem concedeu.
        vinculos.save(new UsuarioAmbiente(convidado, ambienteId));
        em.flush();
    }

    /**
     * Remove um acesso. B-D77, na ordem em que as recusas fazem sentido:
     * qualquer um remove a si mesmo; o dono remove qualquer um; o dono nao sai
     * — sem essa ultima regra sobraria um ambiente orfao, que ninguem pode
     * mais administrar.
     */
    @Transactional
    public void removerAcesso(UUID usuarioId, UUID ambienteId, UUID usuarioAlvo) {
        UsuarioAmbiente meu = exigirVinculo(usuarioId, ambienteId);

        UsuarioAmbiente alvo = vinculos
            .findById(new UsuarioAmbiente.Chave(usuarioAlvo, ambienteId))
            .orElseThrow(() -> new RecursoNaoEncontrado(
                "Esta pessoa nao tem acesso a este ambiente"));

        boolean euMesmo = usuarioAlvo.equals(usuarioId);
        if (!euMesmo && !meu.isDono()) {
            throw new OperacaoNaoPermitida("So o dono remove o acesso de outra pessoa");
        }
        if (alvo.isDono()) {
            throw new OperacaoNaoPermitida("O dono nao sai do proprio ambiente");
        }

        // A revogacao vale AGORA (B-D83): o token da pessoa pode viver mais
        // quinze minutos, mas a proxima requisicao dele leva 403 com frase.
        // As sessoes dela NAO caem (B-D84) — logoff nao mataria o token de
        // acesso, e derrubaria tambem o ambiente dela, que nao e do assunto.
        vinculos.delete(alvo);
        em.flush();
    }

    /**
     * O vinculo do proprio chamador com o ambiente — ou 404, nos termos de
     * B-D25: ambiente alheio e indistinguivel de ambiente inexistente.
     */
    private UsuarioAmbiente exigirVinculo(UUID usuarioId, UUID ambienteId) {
        return vinculos.findById(new UsuarioAmbiente.Chave(usuarioId, ambienteId))
            .orElseThrow(() -> new RecursoNaoEncontrado("Ambiente inexistente"));
    }
}
