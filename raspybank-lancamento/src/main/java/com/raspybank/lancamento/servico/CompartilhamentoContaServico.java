package com.raspybank.lancamento.servico;

import com.raspybank.lancamento.dominio.Conta;
import com.raspybank.lancamento.dominio.ContaAmbiente;
import com.raspybank.lancamento.dominio.ContaConvite;
import com.raspybank.lancamento.dominio.NaturezaConta;
import com.raspybank.lancamento.dominio.TipoCartaoEmitido;
import com.raspybank.lancamento.repositorio.ContaAmbienteRepositorio;
import com.raspybank.lancamento.repositorio.ContaConviteRepositorio;
import com.raspybank.lancamento.repositorio.ContaRepositorio;
import com.raspybank.shared.erro.ConflitoDeEstado;
import com.raspybank.shared.erro.OperacaoNaoPermitida;
import com.raspybank.shared.erro.RecursoNaoEncontrado;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * O segundo modo de compartilhar: a CONTA (§4k, api.md §2d).
 *
 * <p>Diferente do §4j em uma coisa que muda tudo: <b>ela trabalha no ambiente
 * dela</b>. As categorias sao dela, o mapa de gastos e dela, e o saldo e da
 * conta — dos dois. A regra que resume o modo inteiro (B-D85): <i>o saldo
 * atravessa ambientes, a classificacao nao</i>. E a separacao do mapa nao pediu
 * filtro novo: ele ja recorta por ambiente, e o lancamento dela tem categoria do
 * ambiente dela. Cai da estrutura.</p>
 *
 * <h3>As tres operacoes que passam pelo banco por impasse, e nao por gosto</h3>
 *
 * <p><b>Aceitar</b> ({@code app_aceitar_convite_de_conta}): ela grava um vinculo
 * entre uma conta que nao e dela e um ambiente que e, e {@code pol_ca_vincular}
 * exige conta propria dos dois lados justamente para impedir captura de conta
 * alheia (B-D18). O que separa aceite de captura e o convite, e por isso a
 * funcao le o convite em vez de receber a conta.</p>
 *
 * <p><b>Revogar</b> ({@code app_revogar_conta_compartilhada}): o dono precisa
 * encerrar uma linha que ele <b>nao pode ver</b> — {@code pol_ca_leitura} mostra
 * a cada um so o proprio lado do vinculo, e isso e deliberado (B-D90).</p>
 *
 * <p><b>Saber com quem dividiu</b> ({@code app_compartilhamentos_da_conta}):
 * pelo mesmo motivo, e devolvendo a pessoa e nunca o ambiente dela.</p>
 *
 * <p>Convidar, cancelar e recusar <b>nao</b> ganharam funcao: as politicas
 * {@code pol_convite_criar} e {@code pol_convite_apagar} ja dizem a regra, e
 * pelo criterio B-D19 funcao sem impasse nao se justifica.</p>
 */
@Service
public class CompartilhamentoContaServico {

    private final ContaRepositorio contas;
    private final ContaAmbienteRepositorio vinculos;
    private final ContaConviteRepositorio convites;

    @PersistenceContext
    private EntityManager em;

    public CompartilhamentoContaServico(ContaRepositorio contas,
                                        ContaAmbienteRepositorio vinculos,
                                        ContaConviteRepositorio convites) {
        this.contas = contas;
        this.vinculos = vinculos;
        this.convites = convites;
    }

    /** Uma pessoa do outro lado do compartilhamento. */
    public record Pessoa(UUID usuarioId, String nome, String email) {}

    /**
     * Um compartilhamento como a lista de §2d mostra.
     *
     * <p><b>Nao existe campo de ambiente</b>, de proposito: em qual ambiente
     * dela a conta entrou e organizacao da vida dela, e B-D90 ja recusou expor
     * isso ao dono quando recusou que ele escolhesse.</p>
     */
    public record Compartilhamento(UUID usuarioId, String nome, String email, boolean pendente) {}

    /**
     * Um convite esperando resposta, do ponto de vista de quem o recebeu.
     *
     * <p>{@code plastico} nulo significa convite de CONTA; preenchido, convite de
     * PLASTICO (B-D106). A tela precisa da diferenca: aceitar um plastico nao da
     * a conta do cartao inteira, e deixar isso ambiguo faria ela aceitar pensando
     * ter recebido outra coisa.</p>
     */
    public record ConvitePendente(UUID id, UUID contaId, String contaNome,
                                  NaturezaConta natureza, Pessoa de,
                                  Plastico plastico) {}

    /** O cartao emitido oferecido — nome de quem esta no plastico e os quatro finais. */
    public record Plastico(UUID id, String titular, TipoCartaoEmitido tipo, String finalDoCartao) {}

    // =========================================================================
    // O lado do dono
    // =========================================================================

    /**
     * Quem ja tem a conta e quem ainda nao respondeu, numa lista so.
     *
     * <p>Uma consulta, e nao duas mais uma por pessoa. O nome de quem foi
     * convidado <b>nao</b> sai por leitura direta: {@code pol_usuario_leitura} e
     * "eu, e quem divide ambiente comigo", e quem esta sendo convidado, por
     * definicao, ainda nao divide nada — a lista mostraria um uuid. E o impasse
     * de {@code app_usuario_por_email} pelo avesso, e a funcao o resolve.</p>
     */
    @Transactional(readOnly = true)
    public List<Compartilhamento> listar(UUID ambienteId, UUID contaId) {
        exigirPropria(ambienteId, contaId);
        return compartilhamentos(contaId);
    }

    /**
     * Convida por e-mail. Cria um convite PENDENTE, nao um acesso (B-D90).
     *
     * <p>E-mail nao cadastrado responde 404 dizendo isso — o mesmo oraculo de
     * enumeracao aceito em B-D81, e pela mesma razao: responder "ok" para um
     * e-mail digitado errado esconderia o erro mais comum de todos.</p>
     *
     * <p>As recusas de 409 nao sao zelo: cada uma corresponde a um estado em que
     * a tela mostraria algo que nao aconteceu — convite repetido, pessoa que ja
     * recebeu a conta, conta sua, conta encerrada.</p>
     *
     * <p><b>Ter acesso ao ambiente NAO e recusa</b>, e a ausencia dessa recusa e
     * uma decisao: ver a conta de dentro do ambiente do dono e uma coisa, ter a
     * conta no proprio ambiente e outra. Ver o comentario no corpo.</p>
     */
    @Transactional
    public void compartilhar(UUID ambienteId, UUID contaId, String email, UUID usuarioId) {
        Conta conta = exigirPropria(ambienteId, contaId);

        if (conta.getEncerradaEm() != null) {
            throw new ConflitoDeEstado(
                "Conta encerrada nao se compartilha. Reabra a conta antes.");
        }

        // Cartao nao se divide inteiro (B-D106). A V17 permitia, e o uso mostrou
        // que estava errado: dividir um cartao entregava os dez plasticos, quando
        // o que se quer dividir e UM adicional dentro do contrato. O caminho e
        // outro, e a frase diz qual.
        if (ehContaDeCartao(contaId)) {
            throw new OperacaoNaoPermitida(
                "Cartao de credito nao se divide inteiro. Divida um cartao emitido —"
                    + " o adicional ou o virtual — pela tela de cartoes.");
        }

        UUID convidado = usuarioPorEmail(email);
        if (convidado == null) {
            throw new RecursoNaoEncontrado("Nenhum usuario cadastrado com este e-mail");
        }
        if (convidado.equals(usuarioId)) {
            throw new ConflitoDeEstado("Esta conta ja e sua");
        }
        for (Compartilhamento c : compartilhamentos(contaId)) {
            if (!c.usuarioId().equals(convidado)) {
                continue;
            }
            throw new ConflitoDeEstado(c.pendente()
                ? "Esta pessoa ja tem um convite pendente para esta conta"
                : "Esta pessoa ja recebeu esta conta");
        }

        // NAO existe recusa para "esta pessoa ja tem acesso ao ambiente".
        //
        // A primeira versao deste servico recusava, e era um erro conceitual meu:
        // confundia VER a conta com TER a conta. Quem entra no seu ambiente
        // trabalha DENTRO dele, com as SUAS categorias e no SEU mapa; a conta
        // dividida aparece no ambiente DELA, com as categorias dela e no mapa
        // dela. Sao coisas diferentes, e o §4k abre dizendo que o segundo modo e
        // "complementar" ao primeiro — a recusa proibia exatamente isso.
        //
        // As duas concessoes convivem e sao INDEPENDENTES: revogar a conta nao
        // tira o ambiente, e remover o acesso ao ambiente nao tira a conta. Foram
        // dois atos, em momentos diferentes, com significados diferentes.

        convites.save(new ContaConvite(contaId, convidado));
        em.flush();
    }

    /**
     * Um metodo para tres coisas, no idioma de B-D77: o dono cancela um convite
     * pendente, o dono revoga um acesso ativo, e qualquer um sai da conta que
     * recebeu.
     *
     * <p>A ordem das tentativas importa. O convite pendente vem primeiro porque
     * e o unico estado que <b>desaparece</b>; se ele existir, e ele que o pedido
     * quis dizer.</p>
     */
    @Transactional
    public void remover(UUID ambienteId, UUID contaId, UUID usuarioAlvo, UUID quemPede) {

        boolean euMesmo = usuarioAlvo.equals(quemPede);

        // Sair. Nao exige ser dono — e o unico caminho que a convidada tem, e
        // pol_ca_sair ja o autoriza no banco. Aqui basta o vinculo dela, que ela
        // enxerga porque esta no ambiente dela.
        if (euMesmo) {
            List<ContaAmbiente> meus = vinculos.findByContaId(contaId).stream()
                .filter(v -> !v.isOrigem() && v.estaAtivo())
                .toList();

            if (meus.isEmpty()) {
                // Nada emprestado para devolver: ou a conta e dele, ou nunca foi
                // dele. As duas respondem 404 pelo mesmo motivo de B-D25.
                throw new RecursoNaoEncontrado("Voce nao recebeu esta conta");
            }
            meus.forEach(v -> v.encerrar(OffsetDateTime.now()));
            em.flush();
            return;
        }

        exigirPropria(ambienteId, contaId);

        Optional<ContaConvite> pendente = convites.findByContaIdAndConvidadoId(contaId, usuarioAlvo);
        if (pendente.isPresent()) {
            convites.delete(pendente.get());
            em.flush();
            return;
        }

        // Revogacao LOGICA (B-D93), e por funcao: a linha dela vive num ambiente
        // que o dono nao pode ver.
        int encerrados = (int) em.createNativeQuery(
                "SELECT app_revogar_conta_compartilhada(:conta, :usuario)")
            .setParameter("conta", contaId)
            .setParameter("usuario", usuarioAlvo)
            .getSingleResult();

        if (encerrados == 0) {
            throw new RecursoNaoEncontrado("Esta pessoa nao tem acesso a esta conta");
        }
    }

    // =========================================================================
    // O PLASTICO — a unidade do cartao (B-D106)
    // =========================================================================

    /** Com quem este plastico esta dividido, aceitos e pendentes. */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Compartilhamento> listarDoPlastico(UUID emitidoId) {
        List<Object[]> linhas = em.createNativeQuery(
                "SELECT usuario_id, nome, email, pendente"
                    + " FROM app_compartilhamentos_do_plastico(:emitido)")
            .setParameter("emitido", emitidoId)
            .getResultList();

        return linhas.stream()
            .map(l -> new Compartilhamento(
                (UUID) l[0], (String) l[1], (String) l[2], (Boolean) l[3]))
            .toList();
    }

    /**
     * Divide UM plastico — o adicional em nome dela, dentro da fatura dele.
     *
     * <p>A conta do convite e a conta do CARTAO, e nao o banco: e nela que o
     * lancamento de cartao mora, e e o vinculo dela que o aceite vai criar. O
     * banco continua sendo dele e invisivel para ela.</p>
     *
     * <p>O par (plastico, conta) e conferido pelo banco —
     * {@code fk_convite_plastico_do_cartao} — para que nao exista convite
     * oferecendo o plastico de um cartao e o vinculo de outro.</p>
     */
    @Transactional
    public void compartilharPlastico(UUID cartaoId, UUID emitidoId, String email, UUID usuarioId) {
        if (!contaEhPropria(cartaoId)) {
            throw new OperacaoNaoPermitida(
                "Quem abriu o contrato do cartao e que decide com quem cada cartao"
                    + " emitido e dividido.");
        }

        UUID convidado = usuarioPorEmail(email);
        if (convidado == null) {
            throw new RecursoNaoEncontrado("Nenhum usuario cadastrado com este e-mail");
        }
        if (convidado.equals(usuarioId)) {
            throw new ConflitoDeEstado("Este cartao ja e seu");
        }
        for (Compartilhamento c : listarDoPlastico(emitidoId)) {
            if (!c.usuarioId().equals(convidado)) {
                continue;
            }
            throw new ConflitoDeEstado(c.pendente()
                ? "Esta pessoa ja tem um convite pendente para este cartao"
                : "Esta pessoa ja usa este cartao");
        }

        convites.save(new ContaConvite(cartaoId, convidado, emitidoId));
        em.flush();
    }

    /**
     * Tira alguem de um plastico — ou sai dele.
     *
     * <p>Um caminho para os dois significados, no idioma de B-D77, e a funcao do
     * banco aceita as duas chamadas. O motivo de ser funcao e o de sempre: a linha
     * a encerrar esta num ambiente que o dono nao pode ver (B-D90).</p>
     *
     * <p>Ela leva junto o vinculo da conta do cartao <b>quando nao sobra nenhum
     * plastico</b> daquele cartao para a pessoa — senao ela ficaria com um cartao
     * na tela sem meio de pagamento nenhum, e com leitura do contrato inteiro.</p>
     */
    @Transactional
    public void removerDoPlastico(UUID emitidoId, UUID usuarioAlvo) {
        int encerrados = (int) em.createNativeQuery(
                "SELECT app_revogar_plastico_compartilhado(:emitido, :usuario)")
            .setParameter("emitido", emitidoId)
            .setParameter("usuario", usuarioAlvo)
            .getSingleResult();

        if (encerrados > 0) {
            em.flush();
            return;
        }

        // Nada encerrado: ou nunca teve, ou e um convite pendente esperando.
        Optional<ContaConvite> pendente =
            convites.findByCartaoEmitidoIdAndConvidadoId(emitidoId, usuarioAlvo);

        if (pendente.isEmpty()) {
            throw new RecursoNaoEncontrado("Esta pessoa nao usa este cartao");
        }
        convites.delete(pendente.get());
        em.flush();
    }

    // =========================================================================
    // O lado de quem recebe
    // =========================================================================

    /**
     * Os convites que esperam esta pessoa.
     *
     * <p>Tudo por uma funcao, e o motivo e o mais direto da lista: <b>antes do
     * aceite a conta e invisivel para quem foi convidado</b> —
     * {@code pol_conta_leitura} pede vinculo, e o vinculo e exatamente o que o
     * aceite vai criar. Montar isto com o repositorio devolveria o convite sem o
     * nome da conta e sem quem a ofereceu, e um convite assim ninguem aceita.</p>
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<ConvitePendente> pendentesPara(UUID usuarioId) {
        List<Object[]> linhas = em.createNativeQuery("""
                SELECT convite_id, conta_id, conta_nome, natureza,
                       dono_id, dono_nome, dono_email,
                       emitido_id, emitido_titular, emitido_tipo, emitido_final
                  FROM app_convites_de_conta_pendentes()
                """)
            .getResultList();

        return linhas.stream()
            .map(l -> new ConvitePendente(
                (UUID) l[0],
                (UUID) l[1],
                (String) l[2],
                NaturezaConta.valueOf((String) l[3]),
                new Pessoa((UUID) l[4], (String) l[5], (String) l[6]),
                l[7] == null ? null : new Plastico(
                    (UUID) l[7],
                    (String) l[8],
                    TipoCartaoEmitido.valueOf((String) l[9]),
                    (String) l[10])))
            .toList();
    }

    /**
     * Aceita, no ambiente que ELA escolheu (B-D90).
     *
     * <p>O {@code ambienteId} nao tem valor padrao e nao poderia ter: cair no
     * ambiente ativo mandaria a conta domestica para o PJ sem aviso, e os gastos
     * iriam para o mapa errado ate alguem notar — e notar e dificil, porque nada
     * avisa.</p>
     *
     * @return a conta aceita
     */
    @Transactional
    public UUID aceitar(UUID conviteId, UUID ambienteId) {
        // Qual funcao chamar depende do que o convite oferece, e a pergunta e
        // feita ao convite e nao a quem chama: a tela nao decide isso.
        boolean dePlastico = convites.findById(conviteId)
            .map(ContaConvite::ehDePlastico)
            .orElse(false);

        String funcao = dePlastico
            ? "app_aceitar_convite_de_plastico"
            : "app_aceitar_convite_de_conta";

        try {
            UUID contaId = (UUID) em.createNativeQuery(
                    "SELECT " + funcao + "(:convite, :ambiente)")
                .setParameter("convite", conviteId)
                .setParameter("ambiente", ambienteId)
                .getSingleResult();
            em.flush();
            return contaId;
        } catch (RuntimeException e) {
            // A funcao levanta excecao para convite alheio e para ambiente que
            // nao e dela. As duas respondem 404 pelo idioma de B-D25: distinguir
            // "nao existe" de "nao e seu" transformaria a API num oraculo sobre
            // quais ids existem.
            throw new RecursoNaoEncontrado("Convite ou ambiente nao encontrado");
        }
    }

    @Transactional
    public void recusar(UUID conviteId, UUID usuarioId) {
        ContaConvite c = convites.findById(conviteId)
            .orElseThrow(() -> new RecursoNaoEncontrado("Convite nao encontrado"));

        // pol_convite_leitura tambem deixa o DONO ler o convite que enviou, e
        // recusar por ele nao seria recusar — seria cancelar em nome de outro.
        if (!c.getConvidadoId().equals(usuarioId)) {
            throw new RecursoNaoEncontrado("Convite nao encontrado");
        }

        convites.delete(c);
        em.flush();
    }

    // =========================================================================
    // Perguntas ao banco que a politica, sozinha, nao responde
    // =========================================================================

    /** Com quem o dono dividiu a conta — a pessoa, nunca o ambiente (B-D90). */
    @SuppressWarnings("unchecked")
    public List<Compartilhamento> compartilhamentos(UUID contaId) {
        List<Object[]> linhas = em.createNativeQuery(
                "SELECT usuario_id, nome, email, pendente FROM app_compartilhamentos_da_conta(:conta)")
            .setParameter("conta", contaId)
            .getResultList();

        return linhas.stream()
            .map(l -> new Compartilhamento(
                (UUID) l[0], (String) l[1], (String) l[2], (Boolean) l[3]))
            .toList();
    }

    /** Quem abriu a conta. Alimenta o "compartilhada comigo por X" da tela. */
    public Pessoa donoDaConta(UUID contaId) {
        Object[] l = (Object[]) em.createNativeQuery(
                "SELECT usuario_id, nome, email FROM app_dono_da_conta(:conta)")
            .setParameter("conta", contaId)
            .getSingleResult();

        return new Pessoa((UUID) l[0], (String) l[1], (String) l[2]);
    }

    private UUID usuarioPorEmail(String email) {
        return (UUID) em.createNativeQuery("SELECT app_usuario_por_email(:email)")
            .setParameter("email", email)
            .getSingleResult();
    }

    /**
     * A conta existe neste ambiente, e nasceu nele (B-D92/B-D95).
     *
     * <p>Duas recusas diferentes de proposito: conta que nao aparece no ambiente
     * e <b>404</b> (B-D25), conta emprestada e <b>403</b> com frase. A segunda
     * so acontece com quem esta vendo a conta na tela, e para essa pessoa
     * "inexistente" seria mentira.</p>
     *
     * <p>O banco repete a regra em {@code pol_convite_criar} e no porteiro das
     * funcoes; a checagem aqui existe para a recusa virar frase em vez de
     * violacao de politica.</p>
     */
    private Conta exigirPropria(UUID ambienteId, UUID contaId) {
        ContaAmbiente vinculo = vinculos
            .findById(new ContaAmbiente.Chave(contaId, ambienteId))
            .filter(ContaAmbiente::estaAtivo)
            .orElseThrow(() -> new RecursoNaoEncontrado("Conta nao encontrada"));

        if (!vinculo.isOrigem()) {
            throw new OperacaoNaoPermitida(
                "Esta conta foi compartilhada com voce. Quem a abriu decide quem mais a recebe.");
        }

        // Nascer aqui nao basta: repartir acesso e PORTA, e a porta e do dono do
        // ambiente (B-D91). Quem entrou no ambiente por convite (V15) usa a conta
        // a vontade — B-D76 — mas nao a passa adiante, senao o acesso se espalha
        // sem o dono saber e ele so enxergaria a lista final, nunca a cadeia.
        //
        // A pergunta e feita com o MESMO predicado do porteiro das funcoes. Sem
        // ela, o convidado do ambiente chegaria em app_compartilhamentos_da_conta
        // e levaria uma excecao de banco em vez de uma frase.
        if (!contaEhPropria(contaId)) {
            throw new OperacaoNaoPermitida(
                "Voce tem acesso a este ambiente, mas quem abriu esta conta e que decide"
                    + " com quem ela e dividida.");
        }

        return contas.findById(contaId)
            .orElseThrow(() -> new RecursoNaoEncontrado("Conta nao encontrada"));
    }

    private boolean ehContaDeCartao(UUID contaId) {
        return (Boolean) em.createNativeQuery(
                "SELECT EXISTS (SELECT 1 FROM cartao WHERE conta_id = :conta)")
            .setParameter("conta", contaId)
            .getSingleResult();
    }

    private boolean contaEhPropria(UUID contaId) {
        return (Boolean) em.createNativeQuery(
                "SELECT :conta IN (SELECT app_contas_proprias())")
            .setParameter("conta", contaId)
            .getSingleResult();
    }
}
