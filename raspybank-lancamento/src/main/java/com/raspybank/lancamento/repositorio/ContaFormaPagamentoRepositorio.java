package com.raspybank.lancamento.repositorio;

import com.raspybank.lancamento.dominio.ContaFormaPagamento;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Acesso a lista de formas de pagamento de cada conta.
 *
 * <p>Nao ha filtro por {@code ambienteId} aqui, e a ausencia e deliberada: esta
 * tabela nao tem a coluna, pela mesma razao de {@code conta} (R7). Quem recorta
 * e {@code pol_cfp_conta}, que pergunta a {@code app_contas_do_usuario()} — e o
 * servico confere o vinculo conta/ambiente antes de chegar aqui, como B-D21
 * exige.</p>
 */
public interface ContaFormaPagamentoRepositorio
        extends JpaRepository<ContaFormaPagamento, ContaFormaPagamento.Chave> {

    List<ContaFormaPagamento> findByContaId(UUID contaId);

    /** Uma consulta para a tela inteira, nao uma por conta. */
    List<ContaFormaPagamento> findByContaIdIn(Collection<UUID> contaIds);

    /**
     * A padrao de saida da conta, quando existe.
     *
     * <p>Devolve no maximo uma linha por causa do indice parcial
     * {@code ux_cfp_padrao_saida}; a garantia e do banco, nao deste metodo.</p>
     */
    Optional<ContaFormaPagamento> findByContaIdAndPadraoSaidaTrue(UUID contaId);

    /** Idem para entrada, guardada por {@code ux_cfp_padrao_entrada}. */
    Optional<ContaFormaPagamento> findByContaIdAndPadraoEntradaTrue(UUID contaId);
}
