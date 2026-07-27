package com.raspybank.lancamento.dominio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O sentido aceito por uma categoria (F12).
 *
 * <p>Esta e uma das poucas regras do modulo que o banco NAO garante: os dois
 * CHECKs existem, mas cada um olha a sua tabela, e nenhuma restricao
 * relaciona {@code categoria.tipo} com {@code lancamento.tipo}. Por isso a
 * regra vive no codigo — e por isso tem teste.</p>
 */
@DisplayName("TipoCategoria — que sentido cada categoria aceita")
class TipoCategoriaTest {

    @Test
    @DisplayName("AMBOS aceita os dois sentidos — e para isso que existe")
    void ambosAceitaTudo() {
        assertTrue(TipoCategoria.AMBOS.aceita(TipoLancamento.ENTRADA));
        assertTrue(TipoCategoria.AMBOS.aceita(TipoLancamento.SAIDA));
    }

    @Test
    @DisplayName("SAIDA nao classifica entrada, e vice-versa")
    void tipoEspecificoRecusaOOutro() {
        // Sem isso, "Salario" apareceria no seletor de um gasto e o relatorio
        // somaria receita na coluna de despesa.
        assertTrue(TipoCategoria.SAIDA.aceita(TipoLancamento.SAIDA));
        assertFalse(TipoCategoria.SAIDA.aceita(TipoLancamento.ENTRADA));

        assertTrue(TipoCategoria.ENTRADA.aceita(TipoLancamento.ENTRADA));
        assertFalse(TipoCategoria.ENTRADA.aceita(TipoLancamento.SAIDA));
    }
}
