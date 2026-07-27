package com.raspybank.app.web;

import com.raspybank.lancamento.dominio.Categoria;
import com.raspybank.lancamento.dominio.Conta;
import com.raspybank.lancamento.dominio.Lancamento;
import com.raspybank.lancamento.dominio.SituacaoLancamento;
import com.raspybank.lancamento.dominio.Subcategoria;
import com.raspybank.lancamento.dominio.TipoLancamento;
import com.raspybank.lancamento.servico.LancamentoServico;
import com.raspybank.shared.contexto.ContextoRequisicao;
import com.raspybank.shared.erro.OperacaoNaoPermitida;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lancamentos — a tela T-08, contrato em {@code docs/api.md} §5.
 *
 * <h3>O formulario tem seis campos, e as ausencias sao decisao</h3>
 *
 * <ul>
 *   <li><b>Sem {@code ambienteId}</b> — vem da sessao (B-D2). O lancamento
 *       nasce no ambiente ativo, inclusive em conta compartilhada, e e isso
 *       que responde "de quem e o gasto".</li>
 *   <li><b>Sem {@code situacao}</b> — deriva de {@code dataCaixa} (B-D9). O
 *       {@code PUT} aceita, porque corrigir depois e legitimo.</li>
 *   <li><b>Sem {@code tipo}</b> no caso comum — deriva da categoria (F12).
 *       Escolher "Mercado" ja diz que e saida; perguntar de novo seria pedir a
 *       mesma informacao duas vezes. So categoria {@code AMBOS} exige o
 *       campo.</li>
 * </ul>
 *
 * <h3>Este e o unico recurso com DELETE de verdade</h3>
 *
 * <p>F16: lancamento se exclui, categoria se arquiva, conta se encerra. A
 * diferenca nao e de gosto — apagar um lancamento nao deixa nenhuma linha
 * orfa, e apagar uma categoria apagaria o passado de todas as que apontam
 * para ela. O rastro fica na auditoria, gravada por gatilho (F26 / B-D6).</p>
 */
@RestController
@RequestMapping("/api/lancamentos")
public class LancamentoControlador {

    private final LancamentoServico lancamentos;

    public LancamentoControlador(LancamentoServico lancamentos) {
        this.lancamentos = lancamentos;
    }

    /**
     * O extrato de um mes.
     *
     * <p>O mes e obrigatorio de proposito: sem recorte, a consulta cresce para
     * sempre e a tela que a consome fica lenta anos depois de alguem ter
     * escrito este metodo.</p>
     */
    @GetMapping
    public Map<String, Object> listar(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth mes,
            @RequestParam(required = false) UUID contaId,
            @RequestParam(required = false) UUID categoriaId,
            @RequestParam(required = false) SituacaoLancamento situacao) {

        List<LancamentoResposta> lista =
            lancamentos.listar(ambienteAtivo(), mes, contaId, categoriaId, situacao)
                .stream()
                .map(LancamentoResposta::de)
                .toList();

        return Map.of("lancamentos", lista);
    }

    @GetMapping("/{id}")
    public LancamentoResposta buscar(@PathVariable UUID id) {
        return LancamentoResposta.de(lancamentos.buscar(ambienteAtivo(), id));
    }

    @PostMapping
    public ResponseEntity<LancamentoResposta> registrar(@Valid @RequestBody Pedido pedido) {

        UUID usuarioId = ContextoRequisicao.usuarioId().orElseThrow();

        Lancamento novo = lancamentos.registrar(
            ambienteAtivo(), usuarioId, pedido.paraDados(), LocalDate.now());

        return ResponseEntity.status(HttpStatus.CREATED).body(
            LancamentoResposta.de(lancamentos.buscar(ambienteAtivo(), novo.getId())));
    }

    @PutMapping("/{id}")
    public LancamentoResposta atualizar(@PathVariable UUID id,
                                        @Valid @RequestBody Pedido pedido) {

        lancamentos.atualizar(ambienteAtivo(), id, pedido.paraDados(), LocalDate.now());
        return LancamentoResposta.de(lancamentos.buscar(ambienteAtivo(), id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> excluir(@PathVariable UUID id) {
        lancamentos.excluir(ambienteAtivo(), id);
        return ResponseEntity.noContent().build();
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

    public record Pedido(
        @NotNull(message = "contaId e obrigatorio")
        UUID contaId,

        @NotNull(message = "categoriaId e obrigatorio")
        UUID categoriaId,

        UUID subcategoriaId,

        /** So obrigatorio quando a categoria e AMBOS — quem cobra e o servico. */
        TipoLancamento tipo,

        /** Dinheiro e string (F1): numero em JSON viraria double no JavaScript. */
        @NotNull(message = "valor e obrigatorio")
        @Pattern(regexp = "\\d{1,13}(\\.\\d{1,2})?",
                 message = "valor deve ser positivo, com ate duas casas, como \"380.00\"")
        String valor,

        @NotNull(message = "dataCaixa e obrigatoria")
        LocalDate dataCaixa,

        /** Ausente, copia dataCaixa (F14). */
        LocalDate dataCompetencia,

        @Size(max = 200, message = "descricao deve ter no maximo 200 caracteres")
        String descricao,

        String observacao,

        UUID responsavelId,

        /** Aceito so no PUT; no POST a situacao deriva da data (B-D9). */
        SituacaoLancamento situacao
    ) {
        LancamentoServico.Dados paraDados() {
            return new LancamentoServico.Dados(
                contaId, categoriaId, subcategoriaId, tipo,
                new BigDecimal(valor), dataCaixa, dataCompetencia,
                descricao, observacao, responsavelId, situacao);
        }
    }

    /**
     * Categoria e subcategoria viajam como objeto {@code {id, nome}} resolvido
     * na hora — nao existe nome congelado no lancamento (B-D4 / R8). Renomear
     * a categoria reescreve o rotulo do passado inteiro, e e isso que se
     * espera: o lancamento aponta para uma categoria, nao para um texto.
     */
    public record LancamentoResposta(
        UUID id,
        String descricao,
        String observacao,
        String valor,
        TipoLancamento tipo,
        SituacaoLancamento situacao,
        LocalDate dataCaixa,
        LocalDate dataCompetencia,
        Referencia conta,
        Referencia categoria,
        Referencia subcategoria,
        UUID responsavelId,
        OffsetDateTime criadoEm
    ) {
        static LancamentoResposta de(LancamentoServico.Item item) {
            Lancamento l = item.lancamento();
            return new LancamentoResposta(
                l.getId(), l.getDescricao(), l.getObservacao(),
                l.getValor().setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString(),
                l.getTipo(), l.getSituacao(),
                l.getDataCaixa(), l.getDataCompetencia(),
                referencia(item.conta()),
                referencia(item.categoria()),
                referencia(item.subcategoria()),
                l.getResponsavelId(), l.getCriadoEm());
        }

        private static Referencia referencia(Conta c) {
            return c == null ? null : new Referencia(c.getId(), c.getNome());
        }

        private static Referencia referencia(Categoria c) {
            return c == null ? null : new Referencia(c.getId(), c.getNome());
        }

        private static Referencia referencia(Subcategoria s) {
            return s == null ? null : new Referencia(s.getId(), s.getNome());
        }
    }

    public record Referencia(UUID id, String nome) {}
}
