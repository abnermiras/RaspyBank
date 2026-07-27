package com.raspybank.lancamento.dominio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A regra B-D9 como codigo PURO — padrao B-C3: sem Spring, sem banco, sem
 * container. Instancia, pergunta, confere.
 *
 * <p>Repare que {@code hoje} entra como parametro em todos os casos. E o que
 * permite testar a virada do ano sem esperar dezembro, e o que impede que o
 * build passe hoje e falhe amanha por depender do relogio da maquina.</p>
 */
@DisplayName("SituacaoLancamento — a situacao deriva da data de caixa (B-D9)")
class SituacaoLancamentoTest {

    private static final LocalDate HOJE = LocalDate.parse("2026-07-26");

    @Test
    @DisplayName("Data no passado nasce REALIZADO")
    void passadoNasceRealizado() {
        assertEquals(SituacaoLancamento.REALIZADO,
            SituacaoLancamento.derivarDe(HOJE.minusDays(1), HOJE));
    }

    @Test
    @DisplayName("Data de HOJE nasce REALIZADO — e o caso mais comum do formulario")
    void hojeNasceRealizado() {
        // O limite fica em hoje, e nao em ontem, de proposito: "paguei o
        // mercado agora" e o lancamento que a pessoa mais digita. Exigir
        // confirmacao dele seria exatamente o atrito que B-D9 removeu.
        assertEquals(SituacaoLancamento.REALIZADO,
            SituacaoLancamento.derivarDe(HOJE, HOJE));
    }

    @Test
    @DisplayName("Data no futuro nasce PREVISTO")
    void futuroNascePrevisto() {
        assertEquals(SituacaoLancamento.PREVISTO,
            SituacaoLancamento.derivarDe(HOJE.plusDays(1), HOJE));
    }

    @Test
    @DisplayName("A virada do ano nao muda a regra")
    void viradaDeAno() {
        LocalDate ultimoDia = LocalDate.parse("2026-12-31");
        assertEquals(SituacaoLancamento.REALIZADO,
            SituacaoLancamento.derivarDe(ultimoDia, ultimoDia));
        assertEquals(SituacaoLancamento.PREVISTO,
            SituacaoLancamento.derivarDe(LocalDate.parse("2027-01-01"), ultimoDia));
    }

    @Test
    @DisplayName("Data nula e erro de programacao, nao situacao padrao")
    void dataNulaFalha() {
        // Devolver REALIZADO no lugar de falhar esconderia o defeito e
        // gravaria um lancamento com data errada no quadro central.
        assertThrows(NullPointerException.class,
            () -> SituacaoLancamento.derivarDe(null, HOJE));
    }
}
