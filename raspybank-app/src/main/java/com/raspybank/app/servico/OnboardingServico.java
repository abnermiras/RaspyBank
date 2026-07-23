package com.raspybank.app.servico;

import com.raspybank.ambiente.servico.AmbienteServico;
import com.raspybank.auditoria.servico.AuditoriaServico;
import com.raspybank.identidade.servico.AutenticacaoServico;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Orquestra o cadastro: cria o usuario e o primeiro ambiente.
 *
 * <h3>Por que esta classe vive no modulo de montagem</h3>
 * O cadastro atravessa dois contextos — identidade e ambiente — e o teste de
 * arquitetura proibe que um conheca o outro. O modulo {@code app} e o unico
 * que conhece todos, entao e o lugar legitimo para coordenar os dois.
 *
 * <h3>Por que NAO usamos evento aqui</h3>
 * O padrao outbox existe justamente para desacoplar contextos, e seria
 * tentador aplicar: identidade emite "UsuarioCadastrado", ambiente reage e
 * cria o ambiente inicial.
 *
 * <p>Seria errado. Evento entrega o resultado <i>em algum momento</i>, e aqui
 * a pessoa faz login imediatamente apos o cadastro, esperando encontrar um
 * ambiente pronto. Consistencia eventual nesse ponto nao seria elegancia
 * arquitetural — seria um defeito intermitente, do tipo que so aparece quando
 * o sistema esta sob carga.</p>
 *
 * <p><b>A regra:</b> eventos servem para o que pode acontecer depois.
 * O que precisa estar pronto agora se resolve com orquestracao direta, na
 * mesma transacao.</p>
 */
@Service
public class OnboardingServico {

    private final AutenticacaoServico autenticacao;
    private final AmbienteServico ambientes;
    private final AuditoriaServico auditoria;

    public OnboardingServico(AutenticacaoServico autenticacao,
                             AmbienteServico ambientes,
                             AuditoriaServico auditoria) {
        this.autenticacao = autenticacao;
        this.ambientes = ambientes;
        this.auditoria = auditoria;
    }

    /**
     * Cadastra a pessoa e cria seu primeiro ambiente, tudo ou nada.
     *
     * <p>Se a criacao do ambiente falhar, o usuario tambem nao e gravado.
     * Usuario sem ambiente nenhum e um estado que a aplicacao nao sabe
     * tratar — melhor nao existir.</p>
     */
    @Transactional
    public Resultado cadastrar(String nome, String email, String senha) {

        UUID usuarioId = autenticacao.cadastrar(nome, email, senha);

        UUID ambienteId = ambientes.criarInicial(
            usuarioId, "Financas de " + primeiroNome(nome));

        auditoria.registrarAutenticacao(
            usuarioId, "WEB", "CRIACAO",
            "{\"evento\":\"cadastro\",\"ambienteInicial\":\"" + ambienteId + "\"}");

        return new Resultado(usuarioId, ambienteId);
    }

    private String primeiroNome(String nome) {
        String limpo = nome == null ? "" : nome.trim();
        int espaco = limpo.indexOf(' ');
        return espaco > 0 ? limpo.substring(0, espaco) : limpo;
    }

    public record Resultado(UUID usuarioId, UUID ambienteId) {
    }
}
