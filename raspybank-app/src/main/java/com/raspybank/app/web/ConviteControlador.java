package com.raspybank.app.web;

import com.raspybank.lancamento.dominio.NaturezaConta;
import com.raspybank.lancamento.servico.CompartilhamentoContaServico;
import com.raspybank.shared.contexto.ContextoRequisicao;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Convites de conta compartilhada, do lado de quem recebe — §2d.
 *
 * <h3>Por que existe um aceite aqui, e nao existe no ambiente</h3>
 *
 * <p>Compartilhar ambiente e imediato, sem aceite (B-D80). Compartilhar conta
 * tem aceite por um motivo que nao e cerimonia: <b>ela escolhe em qual ambiente
 * dela a conta vai aparecer</b> (B-D90), e ninguem pode adivinhar essa escolha.
 * Cair no ambiente ativo mandaria a conta domestica para o PJ sem aviso, e os
 * gastos iriam para o mapa errado ate alguem notar — e notar e dificil, porque
 * nada avisa. O dono escolher entre os ambientes dela tambem nao serve: exporia
 * como ela organiza a propria vida, o que a conta compartilhada nao pedia.</p>
 *
 * <h3>Por que fora de {@code /api/contas}</h3>
 *
 * <p>Um convite pendente <b>nao e uma conta</b>: ela ainda nao a enxerga, e nao
 * enxerga por politica — {@code pol_conta_leitura} pede o vinculo, e o vinculo e
 * exatamente o que o aceite vai criar. Pendura-lo em {@code /api/contas/{id}}
 * exigiria um caminho que responde sobre uma conta que a pessoa nao tem, e essa
 * excecao contaminaria todos os outros.</p>
 *
 * <p>Este e tambem o unico controlador de conta que <b>nao</b> depende do
 * ambiente ativo: o convite e da pessoa, nao do ambiente onde ela esta.</p>
 */
@RestController
@RequestMapping("/api/convites")
public class ConviteControlador {

    private final CompartilhamentoContaServico compartilhamentos;

    public ConviteControlador(CompartilhamentoContaServico compartilhamentos) {
        this.compartilhamentos = compartilhamentos;
    }

    @GetMapping
    public Map<String, Object> listar() {
        UUID usuarioId = ContextoRequisicao.usuarioId().orElseThrow();

        return Map.of("convites",
            compartilhamentos.pendentesPara(usuarioId).stream()
                .map(ConviteResposta::de)
                .toList());
    }

    /**
     * Aceita o convite, no ambiente escolhido.
     *
     * <p>Responde <b>404</b> tanto para convite que nao e seu quanto para
     * ambiente que nao e seu — ou onde voce nao e dono. E o idioma de B-D25, que
     * ja valia no §2c: distinguir "nao existe" de "nao e seu" transformaria a API
     * num oraculo sobre quais ids existem.</p>
     *
     * <p>Aceitar dentro de um ambiente <b>recebido emprestado</b> (§2c) e
     * recusado junto: espalharia a conta para o dono daquele ambiente, que nao
     * participou de nada disto.</p>
     */
    @PostMapping("/{id}/aceitar")
    public ResponseEntity<Map<String, Object>> aceitar(@PathVariable UUID id,
                                                       @Valid @RequestBody PedidoAceite pedido) {
        UUID contaId = compartilhamentos.aceitar(id, pedido.ambienteId());

        // O corpo devolve so o id: a conta ja esta no ambiente ESCOLHIDO por
        // ela, que pode nao ser o ambiente ativo da sessao — devolver o resumo
        // completo aqui exigiria montar a resposta de um ambiente em que a
        // pessoa nao esta, e a tela recarrega a lista de contas de qualquer
        // jeito depois de trocar para lá.
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
            .body(Map.of("contaId", contaId, "ambienteId", pedido.ambienteId()));
    }

    /** Recusa. O convite desaparece (B-D94); a trilha fica na auditoria. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> recusar(@PathVariable UUID id) {
        UUID usuarioId = ContextoRequisicao.usuarioId().orElseThrow();
        compartilhamentos.recusar(id, usuarioId);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================

    /**
     * O ambiente de destino e obrigatorio, e a obrigatoriedade e a decisao.
     *
     * <p>Um valor padrao — o ambiente ativo — seria a escolha silenciosa que
     * B-D90 recusou.</p>
     */
    public record PedidoAceite(
        @NotNull(message = "ambienteId e obrigatorio: escolha em qual ambiente a conta vai aparecer")
        UUID ambienteId
    ) {}

    /**
     * Um convite pendente.
     *
     * <p>{@code plastico} nulo = convite de CONTA; preenchido = convite de
     * PLASTICO (B-D106). A tela precisa da diferenca em palavras, porque o que se
     * recebe e diferente: a conta vem com saldo, extrato e formas de pagamento; o
     * plastico vem como meio de pagamento dentro da fatura de outra pessoa.</p>
     */
    public record ConviteResposta(UUID id, ContaResumo conta, PessoaResumo de,
                                  PlasticoResumo plastico) {

        static ConviteResposta de(CompartilhamentoContaServico.ConvitePendente c) {
            return new ConviteResposta(
                c.id(),
                new ContaResumo(c.contaId(), c.contaNome(), c.natureza()),
                new PessoaResumo(c.de().nome(), c.de().email()),
                c.plastico() == null ? null : new PlasticoResumo(
                    c.plastico().id(),
                    c.plastico().titular(),
                    c.plastico().tipo().name(),
                    c.plastico().finalDoCartao()));
        }
    }

    /** O cartao emitido oferecido: o nome no plastico e os quatro finais. */
    public record PlasticoResumo(UUID id, String titular, String tipo, String finalDoCartao) {}

    public record ContaResumo(UUID id, String nome, NaturezaConta natureza) {}

    /** Sem {@code usuarioId}: para aceitar ou recusar, quem convidou basta ter nome. */
    public record PessoaResumo(String nome, String email) {}
}
