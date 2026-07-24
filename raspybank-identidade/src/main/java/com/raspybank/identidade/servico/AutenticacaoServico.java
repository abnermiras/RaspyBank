package com.raspybank.identidade.servico;

import com.raspybank.identidade.dominio.StatusUsuario;
import com.raspybank.identidade.dominio.TokenRenovacao;
import com.raspybank.identidade.repositorio.TokenRenovacaoRepositorio;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cadastro, login e renovacao de sessao.
 */
@Service
public class AutenticacaoServico {

    private static final int DIAS_RENOVACAO = 30;
    private static final int DIAS_TETO      = 90;

    /**
     * Logger para diferenciar as causas de falha de login — pendencia do
     * Bloco A. Os tres caminhos (e-mail inexistente, senha errada, status
     * inativo) geram logs distintos em nivel DEBUG, mas a RESPOSTA ao
     * cliente e identica nos tres: Optional vazio. O log e para nos;
     * a uniformidade externa e contra enumeracao de cadastros.
     */
    private static final Logger log = LoggerFactory.getLogger(AutenticacaoServico.class);

    private final TokenRenovacaoRepositorio tokens;
    private final PasswordEncoder codificador;
    private final SecureRandom aleatorio = new SecureRandom();

    @PersistenceContext
    private EntityManager em;

    public AutenticacaoServico(TokenRenovacaoRepositorio tokens,
                               PasswordEncoder codificador) {
        this.tokens = tokens;
        this.codificador = codificador;
    }

    // -------------------------------------------------------------------------
    // Cadastro
    // -------------------------------------------------------------------------

    /**
     * Cria o usuario e devolve o identificador.
     *
     * <p>Usa a funcao {@code auth_cadastrar_usuario} do banco em vez do
     * repositorio JPA. Motivo: a politica de Row Level Security em `usuario`
     * exige identidade na sessao para gravar, e no cadastro ainda nao ha
     * identidade nenhuma. A funcao e SECURITY DEFINER e atravessa esse
     * impasse por uma porta unica e auditavel.</p>
     */
    @Transactional
    public UUID cadastrar(String nome, String email, String senha) {
        String hash = codificador.encode(senha);

        Object id = em.createNativeQuery(
                "SELECT auth_cadastrar_usuario(:nome, :email, :hash)")
            .setParameter("nome", nome)
            .setParameter("email", email)
            .setParameter("hash", hash)
            .getSingleResult();

        return (UUID) id;
    }

    // -------------------------------------------------------------------------
    // Login
    // -------------------------------------------------------------------------

    /**
     * Valida e-mail e senha.
     *
     * <p>Devolve vazio tanto para e-mail inexistente quanto para senha errada,
     * de propósito. Responder "esse e-mail nao existe" transformaria o login
     * num verificador de cadastro: bastaria testar enderecos para descobrir
     * quem usa o sistema.</p>
     *
     * <p>Pelo mesmo motivo, quando o e-mail nao existe o codigo ainda assim
     * roda uma verificacao de senha descartavel. Sem isso, a resposta voltaria
     * visivelmente mais rapido nesse caso, e o tempo de resposta viraria o
     * verificador de cadastro que acabamos de evitar.</p>
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Optional<UUID> autenticar(String email, String senha) {

        List<Object[]> linhas = em.createNativeQuery(
                "SELECT id, senha_hash, status FROM auth_buscar_credenciais(:email)")
            .setParameter("email", email)
            .getResultList();

        if (linhas.isEmpty()) {
            gastarTempoEquivalente(senha);
            // Sem e-mail no log: UUID nao existe neste ramo e endereco de
            // e-mail e dado pessoal que nao deve se espalhar por arquivos
            // de log. A ocorrencia em si ja e o sinal util.
            log.debug("Falha de login: e-mail nao cadastrado.");
            return Optional.empty();
        }

        Object[] linha = linhas.get(0);
        UUID id     = (UUID) linha[0];
        String hash = (String) linha[1];

        // Conversao texto -> enum na BORDA, uma unica vez (contrato do
        // Bloco A). Daqui em diante so o enum circula. Se o banco devolver
        // valor fora do CHECK (impossivel por construcao, mas dados ja
        // estiveram errados antes da V8), valueOf explode com erro claro
        // em vez de falhar silenciosamente numa comparacao de texto.
        StatusUsuario status = StatusUsuario.valueOf((String) linha[2]);

        if (!codificador.matches(senha, hash)) {
            log.debug("Falha de login: senha incorreta para usuario {}.", id);
            return Optional.empty();
        }
        if (status != StatusUsuario.ATIVO) {
            log.debug("Falha de login: usuario {} com status {}.", id, status);
            return Optional.empty();
        }
        return Optional.of(id);
    }

    private void gastarTempoEquivalente(String senha) {
        // Hash descartavel, apenas para consumir tempo semelhante ao do
        // caminho bem-sucedido. O resultado e ignorado de proposito.
        codificador.encode(senha);
    }

    // -------------------------------------------------------------------------
    // Tokens de renovacao
    // -------------------------------------------------------------------------

    /** Emite o primeiro token de uma nova familia. Chamado no login. */
    @Transactional
    public SessaoNova emitirRenovacaoNova(UUID usuarioId, String agenteUsuario) {
        OffsetDateTime agora = OffsetDateTime.now();
        UUID familiaId = UUID.randomUUID();
        String token = gravar(usuarioId, familiaId,
                              agora.plusDays(DIAS_RENOVACAO),
                              agora.plusDays(DIAS_TETO),
                              agenteUsuario);
        return new SessaoNova(token, familiaId);
    }

    /**
     * Troca um token de renovacao por outro.
     *
     * <p>Se o token apresentado JA foi usado, ha duas explicacoes possiveis:
     * alguem copiou o token, ou o cliente repetiu a chamada por falha de rede.
     * Nao ha como distinguir com seguranca, entao o sistema assume o pior e
     * revoga a familia inteira. A pessoa precisa fazer login de novo — um
     * incomodo pequeno perto da alternativa.</p>
     *
     * <p>O "ja foi usado?" e decidido por um UPDATE atomico no banco, nao por
     * leitura seguida de gravacao (I-11): duas requisicoes simultaneas com o
     * mesmo token disputam a linha, exatamente uma vence, e a perdedora cai no
     * ramo de reuso — que revoga a familia. Retry de rede simultaneo paga o
     * mesmo preco do ladrao; e o custo assumido da postura "na duvida, o pior".</p>
     *
     * <p>Os tres desfechos sao tipos distintos porque o chamador precisa
     * distingui-los INTERNAMENTE — reuso detectado merece auditoria — sem que
     * a resposta externa mude: para o cliente, reuso e token invalido devem
     * ser indistinguiveis.</p>
     */
    @Transactional
    public Renovacao renovar(String tokenApresentado, String agenteUsuario) {

        String hash = hashDe(tokenApresentado);
        Optional<TokenRenovacao> encontrado = tokens.findByTokenHash(hash);

        if (encontrado.isEmpty()) {
            return new Renovacao.Invalida();
        }

        TokenRenovacao token = encontrado.get();
        OffsetDateTime agora = OffsetDateTime.now();

        if (tokens.marcarUsadoSeInedito(hash, agora) == 0) {
            tokens.revogarFamilia(token.getFamiliaId(), agora);
            log.warn("Reuso de token de renovacao detectado: familia {} do usuario {} revogada.",
                token.getFamiliaId(), token.getUsuarioId());
            return new Renovacao.ReusoDetectada(token.getUsuarioId(), token.getFamiliaId());
        }

        if (!token.vigente(agora)) {
            return new Renovacao.Invalida();
        }

        // O novo token herda a familia e o TETO do anterior. Herdar o teto e o
        // que impede a rotacao sucessiva de estender o acesso indefinidamente.
        String novo = gravar(
            token.getUsuarioId(),
            token.getFamiliaId(),
            agora.plusDays(DIAS_RENOVACAO),
            token.getTetoEm(),
            agenteUsuario);

        return new Renovacao.Sucesso(token.getUsuarioId(), token.getFamiliaId(), novo);
    }

    /** Encerra a sessao de UM dispositivo: a familia que veio no token. */
    @Transactional
    public void encerrarSessaoDaFamilia(UUID familiaId) {
        tokens.revogarFamilia(familiaId, OffsetDateTime.now());
    }

    /** Encerra TODAS as sessoes da pessoa, em todos os dispositivos. */
    @Transactional
    public void encerrarSessoes(UUID usuarioId) {
        tokens.revogarTodosDoUsuario(usuarioId, OffsetDateTime.now());
    }

    // -------------------------------------------------------------------------

    private String gravar(UUID usuarioId, UUID familiaId,
                          OffsetDateTime expiraEm, OffsetDateTime tetoEm,
                          String agenteUsuario) {

        // 32 bytes de aleatoriedade criptografica. Nao ha estrutura nem
        // significado no token: ele e apenas um segredo grande o bastante
        // para nao ser adivinhado.
        byte[] bruto = new byte[32];
        aleatorio.nextBytes(bruto);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bruto);

        tokens.save(new TokenRenovacao(
            usuarioId, hashDe(token), familiaId, expiraEm, tetoEm, agenteUsuario));

        // Devolvido em texto UMA unica vez. O banco guarda so o hash.
        return token;
    }

    /**
     * SHA-256 simples, e nao BCrypt.
     *
     * <p>A diferenca importa: BCrypt e proposital e caro, porque protege senhas
     * — que sao curtas e adivinhaveis. Este token tem 256 bits de entropia
     * aleatoria; nao ha o que adivinhar, e forca bruta e impraticavel. Usar
     * BCrypt aqui so tornaria cada renovacao lenta sem ganho nenhum.</p>
     */
    private String hashDe(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponivel", e);
        }
    }

    /** Sessao recem-criada no login: o token em texto (unica vez) e sua familia. */
    public record SessaoNova(String tokenRenovacao, UUID familiaId) {
    }

    /**
     * Desfecho da rotacao. Sealed de proposito: quem chama e obrigado pelo
     * compilador a tratar os tres casos — inclusive o de reuso, que exige
     * auditoria (I-03, opcao b).
     */
    public sealed interface Renovacao {

        record Sucesso(UUID usuarioId, UUID familiaId, String novoTokenRenovacao)
            implements Renovacao {
        }

        /** Token ja usado reapresentado; a familia inteira acabou de ser revogada. */
        record ReusoDetectada(UUID usuarioId, UUID familiaId)
            implements Renovacao {
        }

        /** Token desconhecido, expirado, revogado ou alem do teto. */
        record Invalida()
            implements Renovacao {
        }
    }
}