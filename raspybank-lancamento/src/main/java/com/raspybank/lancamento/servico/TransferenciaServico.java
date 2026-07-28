package com.raspybank.lancamento.servico;

import com.raspybank.lancamento.dominio.Categoria;
import com.raspybank.lancamento.dominio.CodigoSistemico;
import com.raspybank.lancamento.dominio.Conta;
import com.raspybank.lancamento.dominio.ContaAmbiente;
import com.raspybank.lancamento.dominio.Lancamento;
import com.raspybank.lancamento.dominio.TipoLancamento;
import com.raspybank.lancamento.repositorio.CategoriaRepositorio;
import com.raspybank.lancamento.repositorio.ContaAmbienteRepositorio;
import com.raspybank.lancamento.repositorio.ContaRepositorio;
import com.raspybank.lancamento.repositorio.LancamentoRepositorio;
import com.raspybank.shared.erro.OperacaoNaoPermitida;
import com.raspybank.shared.erro.RecursoNaoEncontrado;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Transferencia entre contas proprias — os dois lancamentos ligados de F2.
 *
 * <h3>Por que isto e um servico proprio, e nao dois POSTs da T-08</h3>
 *
 * <p>Porque a primeira perna sozinha <b>ja e um saldo errado</b>. Se a tela
 * fizesse dois {@code POST /api/lancamentos} em sequencia e o segundo falhasse,
 * o sistema ficaria com 100 reais tendo saido de uma conta sem terem entrado em
 * lugar nenhum — e nada no banco denunciaria isso. Aqui as duas nascem na mesma
 * transacao ou nenhuma nasce.</p>
 *
 * <p>E a razao de {@code lancamento_par_id} existir: sem o vinculo, apagar uma
 * perna deixaria a outra orfa e o dinheiro apareceria do nada. A V11 criou a
 * coluna que F2 prometia desde o modelo logico e que nenhuma migracao tinha
 * feito.</p>
 *
 * <h3>Saque nao e um caso especial</h3>
 *
 * <p>Sacar 100 reais no caixa e transferir da conta corrente para a carteira.
 * Nao ha codigo aqui para "saque", nao ha categoria sistemica de saque, e nao
 * ha forma de pagamento chamada saque: a categoria {@code TRANSFERENCIA} nas
 * duas pernas ja conta a historia inteira, e o destino ser uma conta de dinheiro
 * em especie e o que a torna um saque. Inventar um segundo nome para o mesmo
 * evento obrigaria todo relatorio futuro a conhecer os dois.</p>
 *
 * <h3>As pernas nascem sem forma de pagamento</h3>
 *
 * <p>E isso cai de graca da regra que ja existia: categoria sistemica nao
 * recebe forma padrao (ver {@code LancamentoServico.resolverFormaDePagamento}).
 * Nao ha nenhum caso especial escrito para isto. Quem quiser registrar "transferi
 * por pix" edita a perna depois pelo {@code PUT /api/lancamentos/{id}}, que
 * aceita mudar a forma — so nao aceita mudar a categoria.</p>
 */
@Service
public class TransferenciaServico {

    private final LancamentoRepositorio lancamentos;
    private final CategoriaRepositorio categorias;
    private final ContaRepositorio contas;
    private final ContaAmbienteRepositorio vinculos;

    @PersistenceContext
    private EntityManager em;

    public TransferenciaServico(LancamentoRepositorio lancamentos,
                                CategoriaRepositorio categorias,
                                ContaRepositorio contas,
                                ContaAmbienteRepositorio vinculos) {
        this.lancamentos = lancamentos;
        this.categorias = categorias;
        this.contas = contas;
        this.vinculos = vinculos;
    }

    /**
     * Cria as duas pernas e as amarra.
     *
     * <p>A ordem tem uma razao: as duas linhas precisam existir antes de
     * apontarem uma para a outra, porque {@code fk_lancamento_par} referencia
     * ids reais. Dai o {@code flush()} no meio — sem ele o Hibernate poderia
     * adiar os INSERTs e o UPDATE do vinculo apontaria para nada.</p>
     *
     * @param hoje data de referencia da derivacao de situacao (B-D9). As duas
     *             pernas usam a MESMA data de caixa, entao nascem com a mesma
     *             situacao — uma transferencia meio prevista e meio realizada
     *             nao existe
     */
    @Transactional
    public Par transferir(UUID ambienteId, UUID usuarioId, Dados dados, LocalDate hoje) {

        if (dados.contaOrigemId().equals(dados.contaDestinoId())) {
            throw new OperacaoNaoPermitida(
                "Origem e destino sao a mesma conta. Transferir de uma conta para"
                    + " ela mesma nao move dinheiro nenhum.");
        }

        Conta origem = exigirContaUtilizavel(ambienteId, dados.contaOrigemId(), "origem");
        Conta destino = exigirContaUtilizavel(ambienteId, dados.contaDestinoId(), "destino");

        Categoria transferencia = categorias
            .buscarSistemica(ambienteId, CodigoSistemico.TRANSFERENCIA)
            .orElseThrow(() -> new IllegalStateException(
                "Ambiente sem a categoria sistemica TRANSFERENCIA —"
                    + " fn_criar_categorias_sistemicas nao rodou"));

        LocalDate competencia = dados.dataCompetencia() == null
            ? dados.dataCaixa()
            : dados.dataCompetencia();

        Lancamento saida = new Lancamento(
            transferencia, origem.getId(), TipoLancamento.SAIDA, dados.valor(),
            competencia, dados.dataCaixa(), usuarioId, hoje);

        Lancamento entrada = new Lancamento(
            transferencia, destino.getId(), TipoLancamento.ENTRADA, dados.valor(),
            competencia, dados.dataCaixa(), usuarioId, hoje);

        // A descricao e a unica coisa que a pessoa escreve, e ela vale para o
        // movimento inteiro. Sem ela, o extrato mostra o nome da outra conta —
        // que ja e a informacao mais util que existe aqui.
        saida.descrever(descricaoOu(dados.descricao(), "Transferencia para " + destino.getNome()));
        entrada.descrever(descricaoOu(dados.descricao(), "Transferencia de " + origem.getNome()));

        lancamentos.save(saida);
        lancamentos.save(entrada);

        // Sem este flush os ids ainda nao existem e o vinculo apontaria para nada.
        em.flush();

        saida.emparelharCom(entrada.getId());
        entrada.emparelharCom(saida.getId());

        return new Par(saida, entrada);
    }

    // =========================================================================

    /**
     * A conta existe neste ambiente e ainda pode receber lancamento?
     *
     * <p>A pergunta de visibilidade e feita ao <b>vinculo</b>, e nao a conta:
     * conta nao tem coluna de ambiente (R7). Id de outro ambiente responde 404
     * e nao 403, por B-D25 — distinguir "nao existe" de "nao e seu"
     * transformaria a API num oraculo de identificadores.</p>
     *
     * <p>Conta encerrada e recusada com uma frase que diz de qual lado esta o
     * problema. A chave composta do banco recusaria tambem, mas so diria que
     * uma restricao falhou — e numa operacao com duas contas, saber qual das
     * duas e metade do trabalho de consertar.</p>
     */
    private Conta exigirContaUtilizavel(UUID ambienteId, UUID contaId, String papel) {
        if (vinculos.findById(new ContaAmbiente.Chave(contaId, ambienteId)).isEmpty()) {
            throw new RecursoNaoEncontrado("Conta de " + papel + " nao encontrada");
        }
        Conta c = contas.findById(contaId).orElseThrow(
            () -> new RecursoNaoEncontrado("Conta de " + papel + " nao encontrada"));

        if (c.estaEncerrada()) {
            throw new OperacaoNaoPermitida(
                "A conta de " + papel + " '" + c.getNome() + "' esta encerrada."
                    + " Reabra a conta antes de movimentar dinheiro nela.");
        }
        return c;
    }

    private static String descricaoOu(String informada, String padrao) {
        return informada == null || informada.isBlank() ? padrao : informada;
    }

    /**
     * O corpo do POST.
     *
     * <p>Um record em vez de seis parametros: com dois UUIDs de conta seguidos
     * na assinatura, trocar os dois de lugar compila e transfere ao contrario.</p>
     */
    public record Dados(
        UUID contaOrigemId,
        UUID contaDestinoId,
        BigDecimal valor,
        LocalDate dataCaixa,
        LocalDate dataCompetencia,
        String descricao
    ) {}

    /** As duas pernas, na ordem em que a tela as mostra. */
    public record Par(Lancamento saida, Lancamento entrada) {}
}
