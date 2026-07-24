package com.raspybank.shared.contexto;

import java.util.Optional;
import java.util.UUID;

/**
 * Guarda quem esta executando a operacao atual e por qual canal.
 *
 * <p><b>Como funciona:</b> os dados ficam em {@link ThreadLocal}, ou seja,
 * cada thread enxerga apenas os proprios valores. Como o Spring MVC atende
 * cada requisicao em uma thread, isso equivale a "o contexto desta
 * requisicao".</p>
 *
 * <p><b>Por que existe:</b> o identificador do usuario precisa chegar ate o
 * banco para que o Row Level Security funcione, e ate a auditoria para que ela
 * registre o autor. Passar esses valores como parametro em toda assinatura de
 * metodo poluiria o codigo inteiro e, mais grave, dependeria de o
 * desenvolvedor lembrar — exatamente o que o principio P4 proibe.</p>
 *
 * <p><b>Cuidado obrigatorio:</b> como o servidor reaproveita threads de um
 * pool, deixar de limpar o contexto faz a proxima requisicao herdar a
 * identidade da anterior. Isso seria um vazamento entre usuarios. Por isso o
 * {@link #limpar()} roda sempre em bloco {@code finally}.</p>
 */
public final class ContextoRequisicao {

    private static final ThreadLocal<UUID>  USUARIO  = new ThreadLocal<>();
    private static final ThreadLocal<UUID>  AMBIENTE = new ThreadLocal<>();
    private static final ThreadLocal<UUID>  FAMILIA  = new ThreadLocal<>();
    private static final ThreadLocal<Canal> CANAL    = new ThreadLocal<>();

    private ContextoRequisicao() {
        // Classe utilitaria: nao deve ser instanciada.
    }

    public static void definir(UUID usuarioId, UUID ambienteId,
                               UUID familiaId, Canal canal) {
        USUARIO.set(usuarioId);
        AMBIENTE.set(ambienteId);
        FAMILIA.set(familiaId);
        CANAL.set(canal);
    }

    /** Usuario autenticado, se houver. Vazio em endpoints publicos. */
    public static Optional<UUID> usuarioId() {
        return Optional.ofNullable(USUARIO.get());
    }

    /** Ambiente em que o usuario esta operando, se ja selecionado. */
    public static Optional<UUID> ambienteId() {
        return Optional.ofNullable(AMBIENTE.get());
    }

    /**
     * Familia do token de renovacao desta sessao — na pratica, "este
     * dispositivo". Vazio em tokens antigos, emitidos antes da claim existir.
     */
    public static Optional<UUID> familiaId() {
        return Optional.ofNullable(FAMILIA.get());
    }

    public static Canal canal() {
        Canal c = CANAL.get();
        return c != null ? c : Canal.SISTEMA;
    }

    /**
     * Limpa o contexto. <b>Obrigatorio</b> ao fim de toda requisicao,
     * em bloco {@code finally}.
     */
    public static void limpar() {
        USUARIO.remove();
        AMBIENTE.remove();
        FAMILIA.remove();
        CANAL.remove();
    }
}
