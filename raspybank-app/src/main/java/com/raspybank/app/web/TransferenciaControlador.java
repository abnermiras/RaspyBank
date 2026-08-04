package com.raspybank.app.web;

import com.raspybank.lancamento.servico.TransferenciaServico;
import com.raspybank.shared.contexto.ContextoRequisicao;
import com.raspybank.shared.erro.OperacaoNaoPermitida;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Transferencia entre contas proprias — contrato em {@code docs/api.md} §5b.
 *
 * <h3>Por que existe um recurso proprio para isto</h3>
 *
 * <p>Uma transferencia sao dois lancamentos (F2), e a primeira perna sozinha ja
 * e um saldo errado. Se a tela fizesse dois {@code POST /api/lancamentos} e o
 * segundo falhasse, ficariam 100 reais tendo saido de uma conta sem terem
 * entrado em nenhuma — e nada denunciaria isso depois. Aqui as duas nascem na
 * mesma transacao ou nenhuma nasce.</p>
 *
 * <p>Nao ha {@code GET} nem {@code DELETE} aqui de proposito. Ler transferencia
 * e ler lancamento: as duas pernas aparecem no extrato da T-08 como os
 * lancamentos que sao. E excluir uma perna ja apaga a outra, por
 * {@code ON DELETE CASCADE} — um {@code DELETE /api/transferencias/{id}} seria
 * um segundo caminho para o mesmo efeito, e segundo caminho e onde as regras
 * divergem.</p>
 */
@RestController
@RequestMapping("/api/transferencias")
public class TransferenciaControlador {

    private final TransferenciaServico transferencias;

    public TransferenciaControlador(TransferenciaServico transferencias) {
        this.transferencias = transferencias;
    }

    /**
     * Responde <b>201</b> com as duas pernas, na ordem saida e entrada.
     *
     * <p>Devolve as duas, e nao uma confirmacao, porque a tela precisa mostrar
     * o que aconteceu dos dois lados — inclusive a situacao derivada, que numa
     * transferencia agendada para o mes que vem nasce {@code PREVISTO} nas
     * duas.</p>
     */
    @PostMapping
    public ResponseEntity<Resposta> transferir(@Valid @RequestBody Pedido pedido) {

        UUID usuarioId = ContextoRequisicao.usuarioId().orElseThrow();

        TransferenciaServico.Par par = transferencias.transferir(
            ambienteAtivo(), usuarioId, pedido.paraDados(), LocalDate.now());

        return ResponseEntity.status(HttpStatus.CREATED).body(Resposta.de(par));
    }

    // =========================================================================

    /** Ver {@code CategoriaControlador#ambienteAtivo}: sem ambiente, 403 com o caminho. */
    private static UUID ambienteAtivo() {
        return ContextoRequisicao.ambienteId().orElseThrow(() -> new OperacaoNaoPermitida(
            "Sessao sem ambiente ativo. Selecione um em POST /api/sessao/ambiente"));
    }

    // =========================================================================
    // Contrato de entrada e saida
    // =========================================================================

    /**
     * Repare no que este corpo NAO tem.
     *
     * <p><b>Sem {@code categoriaId}</b> — e sempre a sistemica
     * {@code TRANSFERENCIA}. Deixar escolher permitiria classificar uma
     * transferencia como "Mercado", e o mapa de gastos passaria a contar como
     * despesa um dinheiro que so trocou de bolso.</p>
     *
     * <p><b>Sem {@code tipo}</b> — a origem e sempre saida e o destino sempre
     * entrada. Nao ha o que perguntar.</p>
     *
     * <p><b>Sem {@code formaPagamento}</b> — as duas pernas nascem sem forma,
     * porque categoria sistemica nao recebe padrao. Quem quiser registrar
     * "transferi por pix" edita a perna depois pelo
     * {@code PUT /api/lancamentos/{id}}.</p>
     */
    public record Pedido(
        @NotNull(message = "contaOrigemId e obrigatorio")
        UUID contaOrigemId,

        @NotNull(message = "contaDestinoId e obrigatorio")
        UUID contaDestinoId,

        /** Dinheiro e string (F1): numero em JSON viraria double no JavaScript. */
        @NotNull(message = "valor e obrigatorio")
        @Pattern(regexp = "\\d{1,13}(\\.\\d{1,2})?",
                 message = "valor deve ser positivo e usar ponto decimal, sem separador de milhar, como \"100.00\"")
        String valor,

        @NotNull(message = "dataCaixa e obrigatoria")
        LocalDate dataCaixa,

        /** Ausente, copia dataCaixa (F14). */
        LocalDate dataCompetencia,

        /**
         * Opcional. Ausente, cada perna ganha uma descricao com o nome da OUTRA
         * conta — que e a informacao mais util que existe aqui.
         */
        @Size(max = 200, message = "descricao deve ter no maximo 200 caracteres")
        String descricao
    ) {
        TransferenciaServico.Dados paraDados() {
            return new TransferenciaServico.Dados(
                contaOrigemId, contaDestinoId, new BigDecimal(valor),
                dataCaixa, dataCompetencia, descricao);
        }
    }

    public record Resposta(Perna saida, Perna entrada) {
        static Resposta de(TransferenciaServico.Par par) {
            return new Resposta(Perna.de(par.saida()), Perna.de(par.entrada()));
        }
    }

    public record Perna(UUID id, UUID contaId, String valor, String situacao,
                        LocalDate dataCaixa, UUID lancamentoParId) {
        static Perna de(com.raspybank.lancamento.dominio.Lancamento l) {
            return new Perna(
                l.getId(), l.getContaId(),
                l.getValor().setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString(),
                l.getSituacao().name(), l.getDataCaixa(), l.getLancamentoParId());
        }
    }
}
