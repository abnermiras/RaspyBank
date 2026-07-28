package com.raspybank.app.web;

import com.raspybank.lancamento.dominio.CicloFatura;
import com.raspybank.lancamento.dominio.Fatura;
import com.raspybank.lancamento.dominio.FormaPagamento;
import com.raspybank.lancamento.dominio.QuitacaoFatura;
import com.raspybank.lancamento.servico.FaturaServico;
import com.raspybank.lancamento.servico.LancamentoServico;
import com.raspybank.shared.contexto.ContextoRequisicao;
import com.raspybank.shared.erro.OperacaoNaoPermitida;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Faturas — contrato em {@code docs/api.md} §6b.
 *
 * <h3>O estado sao TRES campos, e nao um enum</h3>
 *
 * <p>B-D58: {@code ciclo}, {@code quitacao} e {@code vencida} sao perguntas
 * independentes. Uma fatura ABERTA pode estar parcialmente paga — e o caso da
 * antecipacao para liberar limite (B-D57) — e num enum unico esse caso nao teria
 * nome. A tela compoe o rotulo; este controlador nao escolhe por ela.</p>
 *
 * <h3>Nenhum deles e coluna</h3>
 *
 * <p>F19: a fatura guarda so {@code fechada_em}. Total, pago e a pagar sao somas
 * de lancamentos calculadas na hora (P1) — total guardado divergiria no primeiro
 * estorno, e a divergencia nao daria sinal.</p>
 */
@RestController
@RequestMapping("/api")
public class FaturaControlador {

    private final FaturaServico faturas;

    public FaturaControlador(FaturaServico faturas) {
        this.faturas = faturas;
    }

    @GetMapping("/cartoes/{cartaoId}/faturas")
    public Map<String, Object> listar(@PathVariable UUID cartaoId,
                                      @RequestParam int ano) {

        List<FaturaResposta> lista =
            faturas.listar(ambienteAtivo(), cartaoId, ano, LocalDate.now())
                .stream()
                .map(FaturaResposta::de)
                .toList();

        return Map.of("faturas", lista);
    }

    @GetMapping("/faturas/{id}")
    public FaturaResposta buscar(@PathVariable UUID id) {
        return FaturaResposta.de(faturas.buscar(ambienteAtivo(), id, LocalDate.now()));
    }

    /**
     * Os lancamentos cobrados nesta fatura.
     *
     * <p>Recurso proprio e nao um campo na resposta da fatura: a lista de doze
     * faturas do ano nao deveria carregar os lancamentos de todas elas, e quem
     * abre uma fatura quer justamente esse detalhe.</p>
     */
    @GetMapping("/faturas/{id}/lancamentos")
    public Map<String, Object> lancamentos(@PathVariable UUID id) {
        return Map.of("lancamentos",
            faturas.lancamentosDa(ambienteAtivo(), id).stream()
                .map(GastoDaFatura::de)
                .toList());
    }

    @PostMapping("/faturas/{id}/fechar")
    public FaturaResposta fechar(@PathVariable UUID id) {
        faturas.fechar(ambienteAtivo(), id);
        return FaturaResposta.de(faturas.buscar(ambienteAtivo(), id, LocalDate.now()));
    }

    /**
     * Reabre — revisao de F21 (B-D50).
     *
     * <p>Existe porque fechar sem querer nao pode ser irreversivel, e porque
     * cada banco tem sua regra de fechamento.</p>
     */
    @PostMapping("/faturas/{id}/reabrir")
    public FaturaResposta reabrir(@PathVariable UUID id) {
        faturas.reabrir(ambienteAtivo(), id);
        return FaturaResposta.de(faturas.buscar(ambienteAtivo(), id, LocalDate.now()));
    }

    /**
     * Paga a fatura — total ou em parte, aberta ou fechada.
     *
     * <p>Responde <b>201</b>, porque cria dois lancamentos. E a fatura vem junto
     * na resposta com os numeros ja recalculados: quem paga quer ver o quanto
     * sobrou, e obrigar a tela a fazer uma segunda chamada para descobrir seria
     * atrito sem motivo.</p>
     */
    @PostMapping("/faturas/{id}/pagamentos")
    public ResponseEntity<Map<String, Object>> pagar(@PathVariable UUID id,
                                                     @Valid @RequestBody PedidoPagamento pedido) {

        UUID usuarioId = ContextoRequisicao.usuarioId().orElseThrow();

        FaturaServico.Par par = faturas.pagar(
            ambienteAtivo(), usuarioId, id, pedido.contaOrigemId(),
            new BigDecimal(pedido.valor()), pedido.dataCaixa(),
            pedido.formaPagamento(), LocalDate.now());

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "saida", Map.of("id", par.saida().getId(),
                            "contaId", par.saida().getContaId()),
            "entrada", Map.of("id", par.entrada().getId(),
                              "contaId", par.entrada().getContaId()),
            "fatura", FaturaResposta.de(
                faturas.buscar(ambienteAtivo(), id, LocalDate.now()))));
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

    public record PedidoPagamento(
        @NotNull(message = "contaOrigemId e obrigatorio")
        UUID contaOrigemId,

        /** Dinheiro e string (F1). Parcial e legitimo — ver B-D52. */
        @NotNull(message = "valor e obrigatorio")
        @Pattern(regexp = "\\d{1,13}(\\.\\d{1,2})?",
                 message = "valor deve ser positivo, com ate duas casas, como \"2500.00\"")
        String valor,

        @NotNull(message = "dataCaixa e obrigatoria")
        LocalDate dataCaixa,

        /**
         * Opcional, e validada contra a lista da conta PAGADORA (V11). Debito e
         * boleto sao caminhos diferentes para o mesmo pagamento, e a pessoa
         * pediu para poder registrar qual foi.
         */
        FormaPagamento formaPagamento
    ) {}

    /**
     * Um gasto no extrato da fatura.
     *
     * <p>Traz {@code cartao} porque uma fatura mistura o fisico, os virtuais e
     * os adicionais — e sem dono, ela e uma pilha de gastos. Pedido dele nos
     * testes de negocio: <i>"vai mostrar os gastos de cada cartao virtual, de
     * cada cartao fisico, no mesmo mes"</i>.</p>
     *
     * <p>Traz categoria e subcategoria pelo mesmo motivo de sempre (B-D4/R8):
     * resolvidas na hora, nunca congeladas no lancamento.</p>
     */
    public record GastoDaFatura(
        UUID id,
        String descricao,
        String valor,
        String tipo,
        LocalDate dataCompetencia,
        Referencia categoria,
        Referencia subcategoria,
        Cartao cartao,
        Short parcelaNumero,
        Short parcelaTotal
    ) {
        static GastoDaFatura de(LancamentoServico.Item item) {
            var l = item.lancamento();
            return new GastoDaFatura(
                l.getId(),
                l.getDescricao(),
                l.getValor().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                l.getTipo().name(),
                l.getDataCompetencia(),
                item.categoria() == null ? null
                    : new Referencia(item.categoria().getId(), item.categoria().getNome()),
                item.subcategoria() == null ? null
                    : new Referencia(item.subcategoria().getId(), item.subcategoria().getNome()),
                item.cartaoEmitido() == null ? null : new Cartao(
                    item.cartaoEmitido().getId(),
                    item.cartaoEmitido().getNomeTitular(),
                    item.cartaoEmitido().getTipo().name(),
                    item.cartaoEmitido().getFinalDoCartao()),
                l.getParcelaNumero(),
                l.getParcelaTotal());
        }
    }

    public record Referencia(UUID id, String nome) {}

    public record Cartao(UUID id, String nomeTitular, String tipo, String finalDoCartao) {}

    public record FaturaResposta(
        UUID id,
        UUID cartaoId,
        String mesReferencia,
        LocalDate vencimento,
        LocalDate fechamentoPrevisto,
        OffsetDateTime fechadaEm,
        String total,
        String pago,
        String aPagar,
        CicloFatura ciclo,
        QuitacaoFatura quitacao,
        boolean vencida
    ) {
        static FaturaResposta de(FaturaServico.Item item) {
            Fatura f = item.fatura();
            return new FaturaResposta(
                f.getId(), f.getCartaoId(), f.getMes().toString(),
                f.getVencimento(), f.getFechamentoPrevisto(), f.getFechadaEm(),
                dinheiro(item.numeros().total()),
                dinheiro(item.numeros().pago()),
                dinheiro(item.numeros().aPagar()),
                item.ciclo(), item.quitacao(), item.vencida());
        }

        private static String dinheiro(BigDecimal valor) {
            return valor.setScale(2, RoundingMode.HALF_UP).toPlainString();
        }
    }
}
