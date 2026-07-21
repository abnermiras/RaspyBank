package com.raspybank.shared.persistencia;

import com.raspybank.shared.contexto.ContextoRequisicao;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Informa ao Postgres quem esta conectado, no inicio de cada transacao.
 *
 * <p>Sem isto, as politicas de Row Level Security criadas na migracao V3 nao
 * teriam como saber quem e o usuario, e nenhuma linha seria visivel — o que,
 * convem notar, e o comportamento seguro por padrao: na duvida, o banco nao
 * mostra nada.</p>
 *
 * <h3>Por que um aspecto, e nao uma chamada em cada servico</h3>
 * O principio P4 diz que o filtro nunca pode depender de o desenvolvedor
 * lembrar de escreve-lo. Um aspecto intercepta todos os metodos transacionais
 * automaticamente: nao ha como esquecer, porque nao ha nada a escrever.
 *
 * <h3>Por que a ordem importa</h3>
 * O comando precisa rodar DENTRO da transacao, depois do BEGIN. Por isso a
 * classe {@code ConfiguracaoTransacional} define a ordem do interceptador de
 * transacao do Spring como 0, e este aspecto usa 10: numero maior significa
 * menor precedencia, ou seja, executa mais para dentro.
 *
 * <h3>Por que set_config e nao SET LOCAL</h3>
 * {@code SET LOCAL} nao aceita parametro vinculado — exigiria concatenar o
 * UUID direto na string SQL. {@code set_config()} e uma funcao comum e aceita
 * parametro, eliminando a possibilidade de injecao.
 *
 * <p>O terceiro argumento {@code true} significa LOCAL: o valor vale ate o fim
 * da transacao e desaparece quando a conexao volta para o pool. Sem isso, a
 * proxima requisicao herdaria a identidade da anterior.</p>
 */
@Aspect
@Component
@Order(10)
public class ConfiguradorSessaoRls {

    @PersistenceContext
    private EntityManager entityManager;

    @Around("@annotation(org.springframework.transaction.annotation.Transactional)"
          + " || @within(org.springframework.transaction.annotation.Transactional)")
    public Object aplicarContexto(ProceedingJoinPoint ponto) throws Throwable {

        ContextoRequisicao.usuarioId().ifPresent(this::definirUsuario);
        ContextoRequisicao.ambienteId().ifPresent(this::definirAmbiente);

        return ponto.proceed();
    }

    private void definirUsuario(UUID usuarioId) {
        definir("raspybank.usuario_id", usuarioId);
    }

    private void definirAmbiente(UUID ambienteId) {
        definir("raspybank.ambiente_id", ambienteId);
    }

    private void definir(String chave, UUID valor) {
        entityManager
            .createNativeQuery("SELECT set_config(:chave, :valor, true)")
            .setParameter("chave", chave)
            .setParameter("valor", valor.toString())
            .getSingleResult();
    }
}
