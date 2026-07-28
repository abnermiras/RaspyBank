package com.raspybank.lancamento.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Quais formas de pagamento uma conta aceita, e qual delas e a padrao.
 *
 * <p>A lista e POR CONTA porque foi assim que o caso real apareceu: a carteira
 * so aceita {@code DINHEIRO}, uma conta digital so {@code PIX}, a corrente
 * aceita quase tudo. Lista global faria o seletor da T-08 oferecer "desconto em
 * folha" ao lancar um gasto na carteira.</p>
 *
 * <h3>O padrao existe para nao mentir</h3>
 *
 * <p>A regra pedida foi "se a pessoa nao indicar, salva debito". Debito literal
 * quebraria na carteira: gravaria nela uma forma que a propria lista dela
 * recusa, e em silencio. O padrao POR CONTA e a mesma regra sem a excecao — na
 * corrente marca-se {@code DEBITO}, na carteira {@code DINHEIRO}, e o
 * comportamento pedido acontece nas duas.</p>
 *
 * <p>No maximo uma padrao por conta, garantido pelo indice parcial
 * {@code ux_cfp_padrao}. Nenhuma tambem e valido: aceitar tres formas sem ter
 * preferencia e legitimo, e ai o campo simplesmente nao se preenche sozinho.</p>
 *
 * <h3>Remover uma forma da lista pode ser recusado</h3>
 *
 * <p>{@code fk_lancamento_forma_pagamento} e {@code ON DELETE RESTRICT}: se
 * algum lancamento da conta ja usou aquela forma, a remocao falha. E
 * deliberado — recusar da a chance de reclassificar, enquanto apagar em
 * silencio destruiria justamente o dado que esta feature veio registrar.</p>
 */
@Entity
@Table(name = "conta_forma_pagamento")
@IdClass(ContaFormaPagamento.Chave.class)
public class ContaFormaPagamento {

    @Id
    @Column(name = "conta_id")
    private UUID contaId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "forma")
    private FormaPagamento forma;

    /**
     * Assumida quando um lancamento de SAIDA desta conta nao informa forma.
     * No maximo uma por conta ({@code ux_cfp_padrao_saida}).
     */
    @Column(name = "padrao_saida", nullable = false)
    private boolean padraoSaida;

    /**
     * Idem para ENTRADA. Dois padroes e nao um porque entrada tambem tem "como
     * o dinheiro se moveu": o salario e CREDITADO, e um padrao unico de saida
     * deixaria toda entrada em branco para sempre.
     */
    @Column(name = "padrao_entrada", nullable = false)
    private boolean padraoEntrada;

    @Column(name = "criado_em", insertable = false, updatable = false)
    private OffsetDateTime criadoEm;

    protected ContaFormaPagamento() {
    }

    public ContaFormaPagamento(UUID contaId, FormaPagamento forma) {
        this.contaId = Objects.requireNonNull(contaId, "contaId e obrigatorio");
        this.forma = Objects.requireNonNull(forma, "forma e obrigatoria");
    }

    /**
     * Marca ou desmarca como padrao de um dos sentidos.
     *
     * <p>Quem chama precisa desmarcar a antiga <b>antes</b> de marcar a nova:
     * {@code ux_cfp_padrao_saida} e {@code ux_cfp_padrao_entrada} sao indices
     * unicos parciais verificados a cada comando, e duas verdadeiras no meio do
     * caminho falham. Ver {@code ContaServico.gravarFormas}.</p>
     */
    public void definirPadrao(TipoLancamento sentido, boolean padrao) {
        if (sentido == TipoLancamento.SAIDA) {
            this.padraoSaida = padrao;
        } else {
            this.padraoEntrada = padrao;
        }
    }

    /** E a padrao deste sentido? */
    public boolean ehPadraoDe(TipoLancamento sentido) {
        return sentido == TipoLancamento.SAIDA ? padraoSaida : padraoEntrada;
    }

    public UUID getContaId()            { return contaId; }
    public FormaPagamento getForma()    { return forma; }
    public boolean isPadraoSaida()      { return padraoSaida; }
    public boolean isPadraoEntrada()    { return padraoEntrada; }
    public OffsetDateTime getCriadoEm() { return criadoEm; }

    /** Chave composta. Exigida pelo JPA quando a primaria tem mais de uma coluna. */
    public static class Chave implements Serializable {
        private UUID contaId;
        private FormaPagamento forma;

        public Chave() {
        }

        public Chave(UUID contaId, FormaPagamento forma) {
            this.contaId = contaId;
            this.forma = forma;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Chave outra)) return false;
            return Objects.equals(contaId, outra.contaId)
                && forma == outra.forma;
        }

        @Override
        public int hashCode() {
            return Objects.hash(contaId, forma);
        }
    }
}
