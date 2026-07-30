package com.raspybank.app.web;

import com.raspybank.ambiente.dominio.Ambiente;
import com.raspybank.ambiente.servico.AmbienteServico;
import com.raspybank.lancamento.dominio.Conta;
import com.raspybank.lancamento.dominio.ContaFormaPagamento;
import com.raspybank.lancamento.dominio.FormaPagamento;
import com.raspybank.lancamento.dominio.LinhaDoExtrato;
import com.raspybank.lancamento.dominio.NaturezaConta;
import com.raspybank.lancamento.dominio.SituacaoLancamento;
import com.raspybank.lancamento.dominio.TipoLancamento;
import com.raspybank.lancamento.servico.CompartilhamentoContaServico;
import com.raspybank.lancamento.servico.ContaServico;
import com.raspybank.shared.contexto.ContextoRequisicao;
import com.raspybank.shared.erro.OperacaoNaoPermitida;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    private final CompartilhamentoContaServico compartilhamentos;

    public ContaControlador(ContaServico contas,
                            AmbienteServico ambientes,
                            CompartilhamentoContaServico compartilhamentos) {
        this.contas = contas;
        this.ambientes = ambientes;
        this.compartilhamentos = compartilhamentos;
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
            pedido.formasComoConjunto(),
            pedido.padraoSaida(),
            pedido.padraoEntrada(),
            usuarioId,
            LocalDate.now());

        return ResponseEntity.status(HttpStatus.CREATED).body(resposta(c.getId()));
    }

    /**
     * Substitui as formas de pagamento aceitas pela conta.
     *
     * <p>Endpoint proprio e nao um campo no {@code PUT /{id}}: renomear e
     * redefinir a lista sao operacoes com riscos diferentes. Renomear nunca
     * falha; mexer na lista pode ser recusado com 409 se algum lancamento usa a
     * forma removida, e juntar as duas faria uma recusa dessas impedir tambem a
     * troca de nome, que nao tinha nada a ver.</p>
     */
    @PutMapping("/{id}/formas-pagamento")
    public ContaResposta definirFormasDePagamento(
            @PathVariable UUID id,
            @Valid @RequestBody PedidoFormasDePagamento pedido) {

        contas.definirFormasDePagamento(
            ambienteAtivo(), id, pedido.formasComoConjunto(),
            pedido.padraoSaida(), pedido.padraoEntrada());

        return resposta(id);
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
    // Extrato e compartilhamento (V16) — §2d
    // =========================================================================

    /**
     * O extrato da conta, e e aqui que ela se confere contra o banco.
     *
     * <p><b>Este endpoint atravessa ambientes</b> (B-D87); o extrato do mes
     * ({@code GET /api/lancamentos}) continua sendo o do ambiente e nao
     * atravessa. Sao duas perguntas diferentes, e a que bate com o extrato do
     * banco e esta — sem ela, o saldo da conta compartilhada nunca fecharia com
     * a lista de lancamentos que a tela mostra.</p>
     *
     * <p>A linha alheia vem sem descricao e sem categoria (B-D89), e nao e este
     * controlador que as omite: {@code app_extrato_da_conta} nao devolve essas
     * colunas (B-D97).</p>
     */
    @GetMapping("/{id}/extrato")
    public Map<String, Object> extrato(
            @PathVariable UUID id,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth mes) {

        List<LinhaDoExtrato> linhas = contas.extrato(
            ambienteAtivo(), id, mes.atDay(1), mes.atEndOfMonth());

        return Map.of("lancamentos", linhas.stream().map(LinhaResposta::de).toList());
    }

    @GetMapping("/{id}/compartilhamentos")
    public Map<String, Object> listarCompartilhamentos(@PathVariable UUID id) {
        return Map.of("compartilhamentos",
            compartilhamentos.listar(ambienteAtivo(), id).stream()
                .map(CompartilhamentoResposta::de)
                .toList());
    }

    /**
     * Compartilha a conta com quem tem o e-mail informado (B-D90/B-D91).
     *
     * <p>Cria um convite PENDENTE, e nao um acesso: no ambiente o acesso e
     * imediato (B-D80), aqui ha uma escolha que so quem recebe pode fazer — em
     * qual ambiente dela a conta vai aparecer.</p>
     */
    @PostMapping("/{id}/compartilhamentos")
    public ResponseEntity<Map<String, Object>> compartilhar(
            @PathVariable UUID id,
            @Valid @RequestBody PedidoCompartilhar pedido) {

        UUID usuarioId = ContextoRequisicao.usuarioId().orElseThrow();
        compartilhamentos.compartilhar(ambienteAtivo(), id, pedido.email().trim(), usuarioId);

        return ResponseEntity.status(HttpStatus.CREATED).body(listarCompartilhamentos(id));
    }

    /**
     * Um caminho para tres coisas, no idioma de B-D77: o dono cancela um convite
     * pendente, o dono revoga um acesso ativo, e qualquer um sai da conta que
     * recebeu.
     *
     * <p>A revogacao e <b>logica</b> (B-D93): os lancamentos que ela ja fez ficam
     * onde estao e continuam somando no saldo do dono, porque aquele dinheiro
     * saiu da conta de verdade.</p>
     */
    @DeleteMapping("/{id}/compartilhamentos/{usuarioId}")
    public ResponseEntity<Void> removerCompartilhamento(@PathVariable UUID id,
                                                        @PathVariable UUID usuarioId) {
        UUID quemPede = ContextoRequisicao.usuarioId().orElseThrow();
        compartilhamentos.remover(ambienteAtivo(), id, usuarioId, quemPede);
        return ResponseEntity.noContent().build();
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
        String saldoInicial,

        /**
         * As formas que esta conta aceita (V11). Opcional: conta sem lista
         * nenhuma continua funcionando exatamente como antes desta versao, e as
         * contas criadas antes dela ficaram assim. O que ela perde e so o
         * seletor de forma na T-08.
         */
        List<FormaPagamento> formasPagamento,

        /**
         * Assumidas quando o lancamento nao informa forma. Precisam estar em
         * {@code formasPagamento} e aceitar o sentido correspondente; o servico
         * recusa com 403 se nao estiverem.
         */
        FormaPagamento padraoSaida,
        FormaPagamento padraoEntrada
    ) {
        BigDecimal saldoInicialComoDecimal() {
            return saldoInicial == null || saldoInicial.isBlank()
                ? null
                : new BigDecimal(saldoInicial);
        }

        Set<FormaPagamento> formasComoConjunto() {
            return formasPagamento == null ? Set.of() : Set.copyOf(formasPagamento);
        }
    }

    public record PedidoRenome(
        @NotBlank(message = "nome e obrigatorio")
        @Size(max = 60, message = "nome deve ter no maximo 60 caracteres")
        String nome
    ) {}

    /**
     * O estado desejado da lista inteira, nao um acrescimo.
     *
     * <p>A tela mostra as seis formas com caixas marcadas, entao o que ela
     * manda ja e a resposta completa. Um endpoint de acrescimo exigiria outro
     * de remocao, e desmarcar uma caixa viraria duas chamadas.</p>
     */
    public record PedidoFormasDePagamento(
        @NotNull(message = "formas e obrigatorio: envie [] para nao aceitar nenhuma")
        List<FormaPagamento> formas,

        /** Nulos sao validos: aceitar tres formas sem ter preferencia e legitimo. */
        FormaPagamento padraoSaida,
        FormaPagamento padraoEntrada
    ) {
        Set<FormaPagamento> formasComoConjunto() {
            return Set.copyOf(formas);
        }
    }

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
        List<AmbienteResumo> ambientes,

        /**
         * A conta nasceu neste ambiente (B-D92). Libera renomear, encerrar e
         * mexer nas formas — que sao DINHEIRO, e por isso valem tambem para quem
         * entrou no ambiente por convite (B-D76).
         */
        boolean origem,

        /**
         * Sou dono do ambiente onde a conta nasceu. Libera a PORTA — compartilhar
         * e revogar (B-D91) — e e mais estreito que {@code origem} de proposito:
         * quem recebeu o ambiente usa a conta a vontade, mas nao a passa adiante.
         */
        boolean podeCompartilhar,

        /**
         * Alguem mais tem esta conta. Explica na tela por que o saldo e maior
         * que a soma dos lancamentos visiveis — sem a marca, a conta
         * compartilhada pareceria ter erro de soma.
         */
        boolean compartilhada,

        /** Quem abriu a conta, quando ela veio emprestada. Nulo na propria. */
        String recebidaDe,

        /**
         * As formas que esta conta aceita, e a padrao. Ordenadas pela ordem do
         * enum e nao pela do banco: sem {@code ORDER BY} explicito a lista pode
         * mudar de ordem entre duas chamadas iguais, e a tela ficaria com as
         * caixas dancando sem que nada tivesse mudado.
         */
        List<FormaPagamento> formasPagamento,
        FormaPagamento padraoSaida,
        FormaPagamento padraoEntrada
    ) {
        static ContaResposta de(ContaServico.Resumo r, Map<UUID, String> nomes) {
            return new ContaResposta(
                r.conta().getId(), r.conta().getNome(), r.conta().getNatureza(),
                r.conta().getEncerradaEm(),
                dinheiro(r.saldo().realizado()),
                dinheiro(r.saldo().comPrevistos()),
                r.ambienteIds().stream()
                    .map(id -> new AmbienteResumo(id, nomes.get(id)))
                    .toList(),
                r.origem(),
                r.podeCompartilhar(),
                r.compartilhada(),
                r.recebidaDe(),
                r.formasDePagamento().stream()
                    .map(ContaFormaPagamento::getForma)
                    .sorted()
                    .toList(),
                padrao(r.formasDePagamento(), ContaFormaPagamento::isPadraoSaida),
                padrao(r.formasDePagamento(), ContaFormaPagamento::isPadraoEntrada));
        }

        private static FormaPagamento padrao(List<ContaFormaPagamento> formas,
                                             java.util.function.Predicate<ContaFormaPagamento> ehPadrao) {
            return formas.stream()
                .filter(ehPadrao)
                .map(ContaFormaPagamento::getForma)
                .findFirst()
                .orElse(null);
        }

        /** Sempre duas casas, sempre string. "1250" e "1250.00" seriam o mesmo dinheiro escrito de dois jeitos. */
        private static String dinheiro(BigDecimal valor) {
            return valor.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
        }
    }

    public record AmbienteResumo(UUID id, String nome) {}

    public record PedidoCompartilhar(
        @NotBlank(message = "email e obrigatorio")
        @Email(message = "email malformado")
        String email
    ) {}

    /**
     * Um compartilhamento da conta. <b>Sem campo de ambiente</b>, de proposito:
     * em qual ambiente dela a conta entrou e organizacao da vida dela, e B-D90 ja
     * recusou expor isso ao dono quando recusou que ele escolhesse.
     */
    public record CompartilhamentoResposta(UUID usuarioId, String nome, String email,
                                           String situacao) {

        static CompartilhamentoResposta de(CompartilhamentoContaServico.Compartilhamento c) {
            // Marcador estavel, no espirito de SEM_ACESSO_AO_AMBIENTE (§2c): a
            // tela decide pelo valor, nunca pelo texto.
            return new CompartilhamentoResposta(
                c.usuarioId(), c.nome(), c.email(), c.pendente() ? "PENDENTE" : "ATIVO");
        }
    }

    /**
     * Uma linha do extrato da conta, que pode ser de outro ambiente.
     *
     * <p>{@code descricao} e {@code categoria} vem nulos quando {@code meu} e
     * falso — e nao porque este record os apaga: eles nunca chegaram da funcao do
     * banco (B-D89 via B-D97). O JSON reflete o que a aplicacao recebeu.</p>
     */
    public record LinhaResposta(
        UUID id,
        boolean meu,
        LocalDate data,
        TipoLancamento tipo,
        SituacaoLancamento situacao,
        String valor,
        FormaPagamento formaPagamento,
        String descricao,
        CategoriaResumo categoria,
        Quem quem,
        UUID faturaId,
        Integer parcelaNumero,
        Integer parcelaTotal
    ) {
        static LinhaResposta de(LinhaDoExtrato l) {
            return new LinhaResposta(
                l.id(), l.meu(), l.data(), l.tipo(), l.situacao(),
                l.valor().setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString(),
                l.formaPagamento(),
                l.descricao(),
                l.categoriaId() == null ? null : new CategoriaResumo(l.categoriaId(), l.categoriaNome()),
                new Quem(l.quemNome()),
                l.faturaId(), l.parcelaNumero(), l.parcelaTotal());
        }
    }

    public record CategoriaResumo(UUID id, String nome) {}

    /** O autor do lancamento — o "quem" de B-D89, que vem de {@code criado_por}. */
    public record Quem(String nome) {}
}
