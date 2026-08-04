package com.raspybank.app.web;

import com.raspybank.lancamento.dominio.Cartao;
import com.raspybank.lancamento.dominio.CartaoEmitido;
import com.raspybank.lancamento.dominio.TipoCartaoEmitido;
import com.raspybank.lancamento.servico.CartaoServico;
import com.raspybank.lancamento.servico.CompartilhamentoContaServico;
import com.raspybank.shared.contexto.ContextoRequisicao;
import com.raspybank.shared.erro.OperacaoNaoPermitida;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
 * Cartoes de credito — a tela T-06, contrato em {@code docs/api.md} §6b.
 *
 * <h3>O cartao e o CONTRATO, nao o plastico</h3>
 *
 * <p>"Black" e "Diamond" no mesmo Nubank sao dois cartoes deste recurso, cada um
 * com limite proprio. Os plasticos e virtuais debaixo de cada um sao os
 * emitidos, e todos consomem o limite do contrato (B-D46).</p>
 *
 * <h3>Os tres numeros do limite nao travam nada</h3>
 *
 * <p>B-D48. Nenhuma compra e recusada por estourar o limite, e
 * {@code disponivel} pode vir NEGATIVO — o que e informacao, nao defeito: o
 * banco de verdade e quem recusa, e o numero existe para bater com o app
 * dele.</p>
 */
@RestController
@RequestMapping("/api/cartoes")
public class CartaoControlador {

    private final CartaoServico cartoes;
    private final CompartilhamentoContaServico compartilhamentos;

    public CartaoControlador(CartaoServico cartoes,
                             CompartilhamentoContaServico compartilhamentos) {
        this.cartoes = cartoes;
        this.compartilhamentos = compartilhamentos;
    }

    @GetMapping
    public Map<String, Object> listar(
            @RequestParam(defaultValue = "false") boolean incluirEncerrados) {

        List<CartaoResposta> lista = cartoes.listar(ambienteAtivo(), incluirEncerrados)
            .stream()
            .map(CartaoResposta::de)
            .toList();

        return Map.of("cartoes", lista);
    }

    @GetMapping("/{id}")
    public CartaoResposta buscar(@PathVariable UUID id) {
        return CartaoResposta.de(cartoes.resumo(ambienteAtivo(), id));
    }

    @PostMapping
    public ResponseEntity<CartaoResposta> criar(@Valid @RequestBody PedidoCartao pedido) {
        Cartao c = cartoes.criar(
            ambienteAtivo(),
            pedido.contaBancoId(),
            pedido.nome().trim(),
            new BigDecimal(pedido.limite()),
            pedido.diaVencimento(),
            pedido.diasParaFechamentoOuPadrao(),
            pedido.nomeTitularOuPadrao(),
            pedido.finalDoCartao(),
            LocalDate.now());

        return ResponseEntity.status(HttpStatus.CREATED)
            .body(CartaoResposta.de(cartoes.resumo(ambienteAtivo(), c.getContaId())));
    }

    @PutMapping("/{id}")
    public CartaoResposta alterar(@PathVariable UUID id,
                                  @Valid @RequestBody PedidoAlteracao pedido) {
        cartoes.alterar(ambienteAtivo(), id, pedido.nome().trim(),
                        new BigDecimal(pedido.limite()));
        return CartaoResposta.de(cartoes.resumo(ambienteAtivo(), id));
    }

    /**
     * Muda o ciclo, e regera SOMENTE as faturas futuras.
     *
     * <p>Endpoint proprio e nao um campo no {@code PUT /{id}}: renomear nunca
     * mexe em fatura nenhuma, e reagendar mexe em varias. Juntar os dois faria
     * uma correcao de nome tocar em ciclos sem que ninguem pedisse.</p>
     */
    @PutMapping("/{id}/ciclo")
    public CartaoResposta reagendarCiclo(@PathVariable UUID id,
                                         @Valid @RequestBody PedidoCiclo pedido) {
        cartoes.reagendarCiclo(ambienteAtivo(), id, pedido.diaVencimento(),
                               pedido.diasParaFechamentoOuPadrao(), LocalDate.now());
        return CartaoResposta.de(cartoes.resumo(ambienteAtivo(), id));
    }

    @PostMapping("/{id}/encerrar")
    public CartaoResposta encerrar(@PathVariable UUID id) {
        cartoes.encerrar(ambienteAtivo(), id);
        return CartaoResposta.de(cartoes.resumo(ambienteAtivo(), id));
    }

    @PostMapping("/{id}/reabrir")
    public CartaoResposta reabrir(@PathVariable UUID id) {
        cartoes.reabrir(ambienteAtivo(), id);
        return CartaoResposta.de(cartoes.resumo(ambienteAtivo(), id));
    }

    // =========================================================================
    // Emitidos
    // =========================================================================

    @PostMapping("/{id}/emitidos")
    public ResponseEntity<CartaoResposta> emitir(@PathVariable UUID id,
                                                 @Valid @RequestBody PedidoEmitido pedido) {
        cartoes.emitir(ambienteAtivo(), id, pedido.nomeTitular().trim(), pedido.tipo(),
                       pedido.finalDoCartao(), pedido.limiteProprioComoDecimal());

        return ResponseEntity.status(HttpStatus.CREATED)
            .body(CartaoResposta.de(cartoes.resumo(ambienteAtivo(), id)));
    }

    // =========================================================================
    // Dividir um PLASTICO (V19) — §2e, B-D106
    // =========================================================================

    /**
     * Com quem este plastico esta dividido.
     *
     * <p>A unidade e o plastico, e nao o cartao (B-D106): dividir o contrato
     * entregaria os dez. Aqui vale o de sempre — a lista mostra a PESSOA, nunca em
     * qual ambiente dela o cartao entrou (B-D90).</p>
     */
    @GetMapping("/{id}/emitidos/{emitidoId}/compartilhamentos")
    public Map<String, Object> listarCompartilhamentos(@PathVariable UUID id,
                                                      @PathVariable UUID emitidoId) {
        return Map.of("compartilhamentos",
            compartilhamentos.listarDoPlastico(emitidoId).stream()
                .map(ContaControlador.CompartilhamentoResposta::de)
                .toList());
    }

    /**
     * Divide ESTE plastico — o adicional em nome dela, dentro da sua fatura.
     *
     * <p>Cria convite pendente: ela escolhe em qual ambiente dela o cartao
     * aparece (B-D90). O que ela recebe e um meio de pagamento e o extrato daquele
     * plastico — nao o contrato, nao o total da fatura, e nao os outros plasticos
     * (B-D110).</p>
     */
    @PostMapping("/{id}/emitidos/{emitidoId}/compartilhamentos")
    public ResponseEntity<Map<String, Object>> compartilharPlastico(
            @PathVariable UUID id,
            @PathVariable UUID emitidoId,
            @Valid @RequestBody ContaControlador.PedidoCompartilhar pedido) {

        UUID usuarioId = ContextoRequisicao.usuarioId().orElseThrow();
        compartilhamentos.compartilharPlastico(id, emitidoId, pedido.email().trim(), usuarioId);

        return ResponseEntity.status(HttpStatus.CREATED)
            .body(listarCompartilhamentos(id, emitidoId));
    }

    /**
     * Tira alguem deste plastico — ou sai dele, no idioma de B-D77.
     *
     * <p>Quando nao sobra nenhum plastico daquele cartao para a pessoa, o cartao
     * inteiro sai da vista dela: sem isso ela ficaria com um cartao na tela sem
     * nenhum meio de pagamento.</p>
     */
    @DeleteMapping("/{id}/emitidos/{emitidoId}/compartilhamentos/{usuarioId}")
    public ResponseEntity<Void> removerCompartilhamento(@PathVariable UUID id,
                                                        @PathVariable UUID emitidoId,
                                                        @PathVariable UUID usuarioId) {
        compartilhamentos.removerDoPlastico(emitidoId, usuarioId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/emitidos/{emitidoId}/cancelar")
    public CartaoResposta cancelarEmitido(@PathVariable UUID id, @PathVariable UUID emitidoId) {
        cartoes.cancelarEmitido(ambienteAtivo(), id, emitidoId);
        return CartaoResposta.de(cartoes.resumo(ambienteAtivo(), id));
    }

    @PostMapping("/{id}/emitidos/{emitidoId}/reativar")
    public CartaoResposta reativarEmitido(@PathVariable UUID id, @PathVariable UUID emitidoId) {
        cartoes.reativarEmitido(ambienteAtivo(), id, emitidoId);
        return CartaoResposta.de(cartoes.resumo(ambienteAtivo(), id));
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

    public record PedidoCartao(
        @NotNull(message = "contaBancoId e obrigatorio")
        UUID contaBancoId,

        @NotBlank(message = "nome e obrigatorio")
        @Size(max = 60, message = "nome deve ter no maximo 60 caracteres")
        String nome,

        /** Dinheiro e string (F1): numero em JSON viraria double no JavaScript. */
        @NotNull(message = "limite e obrigatorio")
        @Pattern(regexp = "\\d{1,13}(\\.\\d{1,2})?",
                 message = "limite deve ser positivo e usar ponto decimal, sem separador de milhar, como \"10000.00\"")
        String limite,

        @NotNull(message = "diaVencimento e obrigatorio")
        Integer diaVencimento,

        /** Opcional, padrao 5 (B-D49). Fica por cartao porque cada banco tem sua regra. */
        Integer diasParaFechamento,

        /**
         * Os quatro ultimos digitos do cartao FISICO, criado junto com o
         * contrato (B-D63).
         *
         * <p>Sem eles o contrato nasceria sem nenhum emitido — e cartao sem
         * emitido nao recebe compra nenhuma, entao nasceria inutil.</p>
         */
        @NotBlank(message = "finalDoCartao e obrigatorio")
        @Pattern(regexp = "\\d{4}", message = "informe apenas os quatro ultimos digitos")
        String finalDoCartao,

        /** Ausente, o titular vira o proprio nome do cartao. */
        String nomeTitular
    ) {
        int diasParaFechamentoOuPadrao() {
            return diasParaFechamento == null ? 5 : diasParaFechamento;
        }

        String nomeTitularOuPadrao() {
            return nomeTitular == null || nomeTitular.isBlank() ? nome.trim() : nomeTitular.trim();
        }
    }

    public record PedidoAlteracao(
        @NotBlank(message = "nome e obrigatorio")
        @Size(max = 60, message = "nome deve ter no maximo 60 caracteres")
        String nome,

        @NotNull(message = "limite e obrigatorio")
        @Pattern(regexp = "\\d{1,13}(\\.\\d{1,2})?",
                 message = "limite deve ser positivo e usar ponto decimal, sem separador de milhar")
        String limite
    ) {}

    public record PedidoCiclo(
        @NotNull(message = "diaVencimento e obrigatorio")
        Integer diaVencimento,
        Integer diasParaFechamento
    ) {
        int diasParaFechamentoOuPadrao() {
            return diasParaFechamento == null ? 5 : diasParaFechamento;
        }
    }

    public record PedidoEmitido(
        @NotBlank(message = "nomeTitular e obrigatorio")
        @Size(max = 60, message = "nomeTitular deve ter no maximo 60 caracteres")
        String nomeTitular,

        @NotNull(message = "tipo e obrigatorio: FISICO ou VIRTUAL")
        TipoCartaoEmitido tipo,

        /** So os QUATRO ultimos: o numero completo nao e guardado por este sistema. */
        @NotBlank(message = "finalDoCartao e obrigatorio")
        @Pattern(regexp = "\\d{4}", message = "informe apenas os quatro ultimos digitos")
        String finalDoCartao,

        @Pattern(regexp = "\\d{1,13}(\\.\\d{1,2})?",
                 message = "limiteProprio deve ser positivo e usar ponto decimal, sem separador de milhar")
        String limiteProprio
    ) {
        BigDecimal limiteProprioComoDecimal() {
            return limiteProprio == null || limiteProprio.isBlank()
                ? null
                : new BigDecimal(limiteProprio);
        }
    }

    /**
     * Os tres numeros do limite viajam juntos e calculados (P1).
     *
     * <p>{@code consumido} inclui PARCELAS FUTURAS: elas ja existem como
     * lancamentos desde a compra (F23), com data no futuro, entao so o saldo com
     * previstos as enxerga (B-D26/B-D48). Comprar 10x de R$ 100 derruba o
     * disponivel em R$ 1.000 na hora — como no app do banco.</p>
     */
    public record CartaoResposta(
        UUID id,
        String nome,
        Referencia banco,
        String limite,
        String limiteConsumido,
        String limiteDisponivel,
        int diaVencimento,
        int diasParaFechamento,
        OffsetDateTime encerradoEm,
        List<EmitidoResposta> emitidos,

        /**
         * O cartao nasceu neste ambiente. Libera o que e dinheiro no contrato —
         * emitir, cancelar emitido, mudar limite, encerrar — que vale tambem para
         * quem entrou no ambiente por convite (§2c).
         */
        boolean origem,

        /** Sou dono do ambiente onde ele nasceu: libera dividir (B-D91). */
        boolean podeCompartilhar,

        /** Outra pessoa tambem compra neste cartao e ve esta fatura. */
        boolean compartilhado,

        /** Quem abriu o contrato, quando o cartao veio dividido. Nulo no proprio. */
        String recebidoDe
    ) {
        static CartaoResposta de(CartaoServico.Resumo r) {
            Cartao c = r.cartao();
            return new CartaoResposta(
                c.getContaId(), c.getNome(),
                new Referencia(c.getContaBancoId(), r.nomeDoBanco()),
                dinheiro(c.getLimite()),
                dinheiro(r.consumido()),
                dinheiro(r.disponivel()),
                c.getDiaVencimento(), c.getDiasParaFechamento(), c.getEncerradoEm(),
                r.emitidos().stream().map(e -> EmitidoResposta.de(r, e)).toList(),
                r.origem(), r.podeCompartilhar(), r.compartilhado(), r.recebidoDe());
        }

        private static String dinheiro(BigDecimal valor) {
            return valor.setScale(2, RoundingMode.HALF_UP).toPlainString();
        }
    }

    /**
     * Um plastico do contrato — e, desde a V19, a UNIDADE do compartilhamento.
     *
     * <p>{@code limiteEfetivo} e o "1.000 dentro dos 30.000" (B-D110): o limite
     * proprio quando existe, o do contrato quando nao. {@code consumido} e o que
     * ESTE plastico gastou, com as parcelas futuras (F23) — e e o numero que
     * transforma a fatura nas "mini faturas" do e-mail do banco.</p>
     */
    public record EmitidoResposta(
        UUID id, String nomeTitular, UUID usuarioId, TipoCartaoEmitido tipo,
        String finalDoCartao, String limiteProprio,
        String limiteEfetivo, String consumido,
        OffsetDateTime canceladoEm
    ) {
        private static String valor(BigDecimal v) {
            return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
        }

        static EmitidoResposta de(CartaoServico.Resumo r, CartaoEmitido e) {
            return new EmitidoResposta(
                e.getId(), e.getNomeTitular(), e.getUsuarioId(), e.getTipo(),
                e.getFinalDoCartao(),
                e.getLimiteProprio() == null
                    ? null
                    : e.getLimiteProprio().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                valor(r.limiteDoPlastico(e)),
                valor(r.consumidoDoPlastico(e)),
                e.getCanceladoEm());
        }
    }

    public record Referencia(UUID id, String nome) {}
}
