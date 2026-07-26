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
 * Informa ao Postgres quem esta conectado e por onde, no inicio de cada
 * transacao.
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
        definirCanal();

        return ponto.proceed();
    }

    private void definirUsuario(UUID usuarioId) {
        definir("raspybank.usuario_id", usuarioId.toString());
    }

    private void definirAmbiente(UUID ambienteId) {
        definir("raspybank.ambiente_id", ambienteId.toString());
    }

    /**
     * O canal, para o gatilho de auditoria da V10 (decisao B-D6).
     *
     * <h3>Por que isto existe</h3>
     * A V2 registrou auditoria escrita pela camada de servico, com um motivo
     * concreto: so a aplicacao sabe se a operacao veio da web ou do Telegram.
     * A Fase 2 fechou F26 no sentido oposto — gatilho lendo o contexto do RLS.
     * A inconsistencia I-05 ficou registrada meses esperando um vencedor.
     *
     * <p>A resolucao nao escolheu lado: removeu a razao do conflito. Se o
     * canal viaja na mesma transacao em que o usuario ja viajava, o gatilho
     * passa a saber tudo o que o servico sabia — e mantem a virtude que so o
     * gatilho tem, de acusar quem mexeu por fora da aplicacao.</p>
     *
     * <h3>Por que sem {@code ifPresent}</h3>
     * Diferente de usuario e ambiente, o canal nunca esta ausente:
     * {@code ContextoRequisicao.canal()} devolve SISTEMA quando ninguem
     * definiu. Isso e proposital e preserva uma distincao que a V8 criou —
     * rotina interna grava SISTEMA, alteracao feita por fora da aplicacao
     * (psql, migracao) nao passa por aqui e o gatilho grava DESCONHECIDO.
     * Se este metodo pulasse a definicao, as duas viriam iguais e a auditoria
     * perderia justamente o que ela deveria gritar.
     */
    private void definirCanal() {
        definir("raspybank.canal", ContextoRequisicao.canal().name());
    }

    private void definir(String chave, String valor) {
        entityManager
            .createNativeQuery("SELECT set_config(:chave, :valor, true)")
            .setParameter("chave", chave)
            .setParameter("valor", valor)
            .getSingleResult();
    }
}
