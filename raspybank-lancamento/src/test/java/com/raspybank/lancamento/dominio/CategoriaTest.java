package com.raspybank.lancamento.dominio;

import com.raspybank.shared.erro.OperacaoNaoPermitida;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Os cadeados de F10 e as consequencias de B-D3/B-D4.
 */
@DisplayName("Categoria — cadeado da sistemica e ciclo de vida")
class CategoriaTest {

    private static final OffsetDateTime AGORA = OffsetDateTime.parse("2026-07-26T12:00:00Z");

    @Test
    @DisplayName("Categoria criada pela aplicacao nasce comum, sem codigo e dentro do mapa")
    void comumNasceEditavel() {
        Categoria c = new Categoria(Fabrica.AMBIENTE, "Mercado", TipoCategoria.SAIDA);

        assertFalse(c.isSistemica());
        assertNull(c.getCodigo());
        assertTrue(c.isEntraNoMapa());
        assertFalse(c.estaArquivada());
    }

    @Test
    @DisplayName("Renomear troca o texto e nada mais (B-D3)")
    void renomearTrocaSoOTexto() {
        // A prova de que renomear nao cria categoria nova: o id nao muda.
        // E o que sustenta o agrupamento do mapa por id — se o rename criasse
        // outra, o total da categoria se partiria em duas linhas.
        Categoria c = Fabrica.categoria("Transporte", TipoCategoria.SAIDA);
        var idAntes = c.getId();

        c.renomear("Transporte urbano");

        assertEquals("Transporte urbano", c.getNome());
        assertEquals(idAntes, c.getId());
    }

    @Test
    @DisplayName("Arquivar e reversivel, e nao apaga nada (B-D4)")
    void arquivarEDesarquivar() {
        Categoria c = Fabrica.categoria("Lazer", TipoCategoria.SAIDA);

        c.arquivar(AGORA);
        assertTrue(c.estaArquivada());
        assertEquals(AGORA, c.getArquivadaEm());

        c.desarquivar();
        assertFalse(c.estaArquivada());
    }

    @Test
    @DisplayName("Sistemica recusa rename, mudanca de tipo e arquivamento (F10)")
    void sistemicaTemCadeado() {
        Categoria transf = Fabrica.sistemica(CodigoSistemico.TRANSFERENCIA, false);

        assertThrows(OperacaoNaoPermitida.class, () -> transf.renomear("Movimentacao"));
        assertThrows(OperacaoNaoPermitida.class, () -> transf.mudarTipo(TipoCategoria.SAIDA));
        assertThrows(OperacaoNaoPermitida.class, () -> transf.arquivar(AGORA));
        assertThrows(OperacaoNaoPermitida.class, transf::desarquivar);
    }

    @Test
    @DisplayName("Sistemica nao recebe subcategoria")
    void sistemicaNaoRecebeSubcategoria() {
        Categoria ajuste = Fabrica.sistemica(CodigoSistemico.AJUSTE, false);

        assertThrows(OperacaoNaoPermitida.class, () -> new Subcategoria(ajuste, "Manual"));
    }

    @Test
    @DisplayName("NAO_CLASSIFICADO e sistemica E entra no mapa — as duas flags sao independentes (B-D15)")
    void naoClassificadoEntraNoMapa() {
        // Este e o caso que quase se perdeu: usar 'sistemica' para responder as
        // duas perguntas faria os gastos vindos do Telegram sumirem do total,
        // em silencio. O teste existe para que a tentacao nao volte.
        Categoria naoClassificado = Fabrica.sistemica(CodigoSistemico.NAO_CLASSIFICADO, true);
        Categoria transferencia = Fabrica.sistemica(CodigoSistemico.TRANSFERENCIA, false);

        assertTrue(naoClassificado.isSistemica());
        assertTrue(naoClassificado.isEntraNoMapa());

        assertTrue(transferencia.isSistemica());
        assertFalse(transferencia.isEntraNoMapa());
    }

    @Test
    @DisplayName("Subcategoria herda o ambiente da categoria mae, nunca de parametro")
    void subcategoriaHerdaAmbiente() {
        Categoria mae = Fabrica.categoria("Alimentacao", TipoCategoria.SAIDA);
        Subcategoria sub = new Subcategoria(mae, "Feira");

        assertEquals(mae.getAmbienteId(), sub.getAmbienteId());
        assertEquals(mae.getId(), sub.getCategoriaId());
    }
}
