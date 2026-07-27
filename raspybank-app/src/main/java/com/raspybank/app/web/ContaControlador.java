package com.raspybank.app.web;

import com.raspybank.ambiente.dominio.Ambiente;
import com.raspybank.ambiente.servico.AmbienteServico;
import com.raspybank.lancamento.dominio.Conta;
import com.raspybank.lancamento.dominio.NaturezaConta;
import com.raspybank.lancamento.dominio.SaldoDaConta;
import com.raspybank.lancamento.servico.ContaServico;
import com.raspybank.shared.contexto.ContextoRequisicao;
import com.raspybank.shared.erro.OperacaoNaoPermitida;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Contas — a tela T-05, contrato em {@code docs/api.md} §4.
 *
 * <h3>Por que este controlador conhece dois contextos</h3>
 *
 * <p>A resposta mostra em quais ambientes cada conta aparece, com o NOME de
 * cada um. O nome vive no contexto de ambiente, e o modulo de lancamento nao
 * pode importa-lo — o {@code ArquiteturaTest} quebra o build se tentar.</p>
 *
 * <p>Por isso o servico devolve identificadores e a costura acontece aqui: o
 * modulo de montagem e o unico que conhece todos os contextos, e e o lugar
 * legitimo para juntar dois. E a mesma razao de {@code OnboardingServico}
 * existir no app e nao em identidade.</p>
 *
 * <h3>Saldo nunca vem de uma coluna</h3>
 *
 * <p>Principio P1: nao existe {@code conta.saldo}. Os dois numeros da resposta
 * sao somas de lancamentos calculadas na hora. Nao ha o que reconciliar quando
 * o dado nao existe em dois lugares (R1).</p>
 */
@RestController
@RequestMapping("/api/contas")
public class ContaControlador {

    private final ContaServico contas;
    private final AmbienteServico ambientes;

    public ContaControlador(ContaServico contas, AmbienteServico ambientes) {
        this.contas = contas;
        this.ambientes = ambientes;
    }

    @GetMapping
    public Map<String, Object> listar(
            @RequestParam(defaultValue = "false") boolean incluirEncerradas) {

        // Uma consulta para os nomes de TODOS os ambientes da pessoa, usada
        // para resolver os vinculos de todas as contas. O RLS ja garante que
        // so voltem os dela.
        Map<UUID, String> nomes = nomesDosAmbientes();

        List<ContaResposta> lista = contas.listar(ambienteAtivo(), incluirEncerradas)
            .stream()
            .map(r -> ContaResposta.de(r, nomes))
            .toList();

        return Map.of("contas", lista);
    }

    @PostMapping
    public ResponseEntity<ContaResposta> criar(@Valid @RequestBody PedidoConta pedido) {

        UUID usuarioId = ContextoRequisicao.usuarioId().orElseThrow();

        Conta c = contas.criar(
            ambienteAtivo(),
            pedido.nome().trim(),
            pedido.natureza(),
            pedido.saldoInicialComoDecimal(),
            usuarioId,
            LocalDate.now());

        return ResponseEntity.status(HttpStatus.CREATED).body(resposta(c.getId()));
    }

    @PutMapping("/{id}")
    public ContaResposta renomear(@PathVariable UUID id,
                                  @Valid @RequestBody PedidoRenome pedido) {
        contas.renomear(ambienteAtivo(), id, pedido.nome().trim());
        return resposta(id);
    }

    @PostMapping("/{id}/encerrar")
    public ContaResposta encerrar(@PathVariable UUID id) {
        contas.encerrar(ambienteAtivo(), id);
        return resposta(id);
    }

    /**
     * Reabre uma conta encerrada.
     *
     * <p>Nao estava no contrato original; foi acrescentado em 26/07/2026 pelo
     * mesmo motivo do {@code desarquivar} de subcategoria. Encerrar e a
     * alternativa <i>reversivel</i> a exclusao (F7) — sem a volta, um clique
     * errado tiraria a conta dos seletores para sempre, e o unico contorno
     * seria criar outra, partindo o historico em duas.</p>
     */
    @PostMapping("/{id}/reabrir")
    public ContaResposta reabrir(@PathVariable UUID id) {
        contas.reabrir(ambienteAtivo(), id);
        return resposta(id);
    }

    // =========================================================================

    /**
     * A resposta de uma conta so, na MESMA forma da listagem.
     *
     * <p>Le de volta depois de escrever, de proposito: o saldo e calculado, e
     * montar a resposta a partir do que se acabou de enviar significaria a
     * tela receber um numero que o servidor nao conferiu.</p>
     */
    private ContaResposta resposta(UUID id) {
        return ContaResposta.de(contas.resumo(ambienteAtivo(), id), nomesDosAmbientes());
    }

    private Map<UUID, String> nomesDosAmbientes() {
        return ambientes.listarDoUsuario().stream()
            .collect(Collectors.toMap(Ambiente::getId, Ambiente::getNome));
    }

    /** Ver {@code CategoriaControlador#ambienteAtivo}: sem ambiente, 403 com o caminho. */
    private static UUID ambienteAtivo() {
        return ContextoRequisicao.ambienteId().orElseThrow(() -> new OperacaoNaoPermitida(
            "Sessao sem ambiente ativo. Selecione um em POST /api/sessao/ambiente"));
    }

    // =========================================================================
    // Contrato de entrada e saida
    // =========================================================================

    public record PedidoConta(
        @NotBlank(message = "nome e obrigatorio")
        @Size(max = 60, message = "nome deve ter no maximo 60 caracteres")
        String nome,

        @NotNull(message = "natureza e obrigatoria: ATIVO ou PASSIVO")
        NaturezaConta natureza,

        /**
         * Opcional, e <b>string</b> como todo dinheiro no contrato: numero em
         * JSON e {@code double} no JavaScript, e {@code double} para dinheiro
         * e proibido por F1. Aceita negativo — conta PASSIVO ou corrente no
         * vermelho comecam devendo.
         */
        @Pattern(regexp = "-?\\d{1,13}(\\.\\d{1,2})?",
                 message = "saldoInicial deve ser decimal com ate duas casas, como \"3000.00\"")
        String saldoInicial
    ) {
        BigDecimal saldoInicialComoDecimal() {
            return saldoInicial == null || saldoInicial.isBlank()
                ? null
                : new BigDecimal(saldoInicial);
        }
    }

    public record PedidoRenome(
        @NotBlank(message = "nome e obrigatorio")
        @Size(max = 60, message = "nome deve ter no maximo 60 caracteres")
        String nome
    ) {}

    /**
     * Dois saldos, nunca um (mesma razao de B-D10 no mapa): {@code saldo} e o
     * dinheiro que esta la, {@code saldoComPrevistos} inclui o que ja esta
     * agendado. Somar os dois num numero so faria a tela nao ter como separar
     * depois.
     */
    public record ContaResposta(
        UUID id,
        String nome,
        NaturezaConta natureza,
        OffsetDateTime encerradaEm,
        String saldo,
        String saldoComPrevistos,
        List<AmbienteResumo> ambientes
    ) {
        static ContaResposta de(ContaServico.Resumo r, Map<UUID, String> nomes) {
            return de(r.conta(), r.saldo(), r.ambienteIds(), nomes::get);
        }

        static ContaResposta de(Conta c, SaldoDaConta saldo,
                                List<UUID> ambienteIds, Function<UUID, String> nome) {
            return new ContaResposta(
                c.getId(), c.getNome(), c.getNatureza(), c.getEncerradaEm(),
                dinheiro(saldo.realizado()),
                dinheiro(saldo.comPrevistos()),
                ambienteIds.stream()
                    .map(id -> new AmbienteResumo(id, nome.apply(id)))
                    .toList());
        }

        /** Sempre duas casas, sempre string. "1250" e "1250.00" seriam o mesmo dinheiro escrito de dois jeitos. */
        private static String dinheiro(BigDecimal valor) {
            return valor.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
        }
    }

    public record AmbienteResumo(UUID id, String nome) {}
}
