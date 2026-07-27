package com.raspybank.lancamento.dominio;

import com.raspybank.shared.erro.OperacaoNaoPermitida;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * As regras do lancamento que vivem no codigo.
 *
 * <p>As que vivem no schema — as tres chaves compostas, o CHECK do valor, as
 * politicas de RLS — sao verificadas contra um Postgres de verdade em
 * {@code DominioRlsTest}. Aqui ficam as que o banco nao alcanca, e as que
 * existem para dar mensagem melhor antes da ida ao banco.</p>
 */
@DisplayName("Lancamento — regras de registro e correcao")
class LancamentoTest {

    private static final LocalDate HOJE = LocalDate.parse("2026-07-26");
    private static final BigDecimal CEM = new BigDecimal("100.00");

    private Lancamento gasto(Categoria categoria, LocalDate dataCaixa) {
        return new Lancamento(categoria, Fabrica.CONTA, TipoLancamento.SAIDA,
                              CEM, dataCaixa, dataCaixa, Fabrica.USUARIO, HOJE);
    }

    @Test
    @DisplayName("Nasce com o ambiente da categoria, nunca de parametro solto")
    void herdaAmbienteDaCategoria() {
        // Receber ambienteId separado abriria a chance de informar um que nao
        // e o da categoria. A chave composta do banco recusaria, mas so depois
        // da viagem — e com uma mensagem que ninguem entende.
        Categoria mercado = Fabrica.categoria("Mercado", TipoCategoria.SAIDA);

        Lancamento l = gasto(mercado, HOJE);

        assertEquals(mercado.getAmbienteId(), l.getAmbienteId());
        assertEquals(mercado.getId(), l.getCategoriaId());
    }

    @Test
    @DisplayName("A situacao vem da data, sem campo no formulario (B-D9)")
    void situacaoDerivaDaData() {
        Categoria mercado = Fabrica.categoria("Mercado", TipoCategoria.SAIDA);

        assertEquals(SituacaoLancamento.REALIZADO, gasto(mercado, HOJE).getSituacao());
        assertEquals(SituacaoLancamento.PREVISTO, gasto(mercado, HOJE.plusMonths(1)).getSituacao());
    }

    @Test
    @DisplayName("Reagendar para o futuro devolve o lancamento a PREVISTO")
    void reagendarRederivaASituacao() {
        Categoria mercado = Fabrica.categoria("Mercado", TipoCategoria.SAIDA);
        Lancamento l = gasto(mercado, HOJE);

        l.reagendar(HOJE.plusDays(10), HOJE);

        assertEquals(SituacaoLancamento.PREVISTO, l.getSituacao());
    }

    @Test
    @DisplayName("A situacao pode ser fixada contra a derivacao — e por isso que nao e gatilho")
    void correcaoExplicitaVence() {
        // O boleto agendado para amanha que ja foi debitado hoje existe. Uma
        // regra que o banco impoe e uma regra que o usuario nao consegue
        // contrariar quando tem razao.
        Categoria mercado = Fabrica.categoria("Mercado", TipoCategoria.SAIDA);
        Lancamento l = gasto(mercado, HOJE.plusDays(1));
        assertEquals(SituacaoLancamento.PREVISTO, l.getSituacao());

        l.corrigirSituacao(SituacaoLancamento.REALIZADO);

        assertEquals(SituacaoLancamento.REALIZADO, l.getSituacao());
    }

    @Test
    @DisplayName("Categoria de ENTRADA nao classifica uma saida (F12)")
    void categoriaRecusaSentidoErrado() {
        Categoria salario = Fabrica.categoria("Salario", TipoCategoria.ENTRADA);

        assertThrows(OperacaoNaoPermitida.class, () -> gasto(salario, HOJE));
    }

    @Test
    @DisplayName("Sistemica classifica os dois sentidos, porque e AMBOS")
    void sistemicaAceitaOsDoisSentidos() {
        Categoria ajuste = Fabrica.sistemica(CodigoSistemico.AJUSTE, false);

        assertEquals(SituacaoLancamento.REALIZADO, gasto(ajuste, HOJE).getSituacao());
    }

    @Test
    @DisplayName("Categoria arquivada nao classifica lancamento novo (B-D4)")
    void arquivadaSaiDoSeletor() {
        Categoria antiga = Fabrica.categoria("Cinema", TipoCategoria.SAIDA);
        antiga.arquivar(java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z"));

        assertThrows(OperacaoNaoPermitida.class, () -> gasto(antiga, HOJE));
    }

    @Test
    @DisplayName("Valor negativo ou zero e recusado — o sinal vem do tipo (F1)")
    void valorPrecisaSerPositivo() {
        Categoria mercado = Fabrica.categoria("Mercado", TipoCategoria.SAIDA);

        assertThrows(OperacaoNaoPermitida.class, () ->
            new Lancamento(mercado, Fabrica.CONTA, TipoLancamento.SAIDA,
                           new BigDecimal("-100.00"), HOJE, HOJE, Fabrica.USUARIO, HOJE));

        assertThrows(OperacaoNaoPermitida.class, () ->
            new Lancamento(mercado, Fabrica.CONTA, TipoLancamento.SAIDA,
                           BigDecimal.ZERO, HOJE, HOJE, Fabrica.USUARIO, HOJE));
    }

    @Test
    @DisplayName("Valor com tres casas e recusado em vez de arredondado em silencio")
    void valorComCasasDemaisFalha() {
        // numeric(15,2) transformaria 10,005 em 10,01 sem avisar ninguem.
        Categoria mercado = Fabrica.categoria("Mercado", TipoCategoria.SAIDA);

        assertThrows(OperacaoNaoPermitida.class, () ->
            new Lancamento(mercado, Fabrica.CONTA, TipoLancamento.SAIDA,
                           new BigDecimal("10.005"), HOJE, HOJE, Fabrica.USUARIO, HOJE));
    }

    @Test
    @DisplayName("Zeros a mais nao contam como casa decimal: 100,5000 e valido")
    void zerosAMaisSaoAceitos() {
        Categoria mercado = Fabrica.categoria("Mercado", TipoCategoria.SAIDA);

        Lancamento l = new Lancamento(mercado, Fabrica.CONTA, TipoLancamento.SAIDA,
                                      new BigDecimal("100.5000"), HOJE, HOJE, Fabrica.USUARIO, HOJE);

        assertEquals(0, new BigDecimal("100.50").compareTo(l.getValor()));
    }

    @Test
    @DisplayName("Subcategoria de outra categoria e recusada (F11)")
    void subcategoriaPrecisaSerDaCategoria() {
        Categoria alimentacao = Fabrica.categoria("Alimentacao", TipoCategoria.SAIDA);
        Categoria transporte = Fabrica.categoria("Transporte", TipoCategoria.SAIDA);
        Subcategoria combustivel = Fabrica.subcategoria(transporte, "Combustivel");

        Lancamento l = gasto(alimentacao, HOJE);

        assertThrows(OperacaoNaoPermitida.class, () -> l.classificarEm(combustivel));
    }

    @Test
    @DisplayName("Reclassificar zera a subcategoria, porque ela era da categoria antiga")
    void reclassificarZeraSubcategoria() {
        Categoria alimentacao = Fabrica.categoria("Alimentacao", TipoCategoria.SAIDA);
        Categoria transporte = Fabrica.categoria("Transporte", TipoCategoria.SAIDA);
        Subcategoria feira = Fabrica.subcategoria(alimentacao, "Feira");

        Lancamento l = gasto(alimentacao, HOJE);
        l.classificarEm(feira);
        assertEquals(feira.getId(), l.getSubcategoriaId());

        l.reclassificar(transporte);

        assertEquals(transporte.getId(), l.getCategoriaId());
        assertNull(l.getSubcategoriaId());
    }

    @Test
    @DisplayName("Reclassificar para categoria de outro ambiente e recusado (F9)")
    void reclassificarCruzandoAmbienteFalha() {
        Categoria minha = Fabrica.categoria("Mercado", TipoCategoria.SAIDA);
        Categoria alheia = Fabrica.categoria(Fabrica.OUTRO_AMBIENTE, "Mercado", TipoCategoria.SAIDA);

        Lancamento l = gasto(minha, HOJE);

        assertThrows(OperacaoNaoPermitida.class, () -> l.reclassificar(alheia));
    }
}
