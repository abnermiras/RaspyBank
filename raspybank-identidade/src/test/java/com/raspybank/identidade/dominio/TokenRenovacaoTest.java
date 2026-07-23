package com.raspybank.identidade.dominio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regra de dominio testada como codigo PURO: sem Spring, sem banco, sem
 * container — instancia o objeto, pergunta, confere.
 *
 * <p>Este arquivo e o PADRAO do Bloco C, item (2), estabelecido de proposito
 * ANTES do primeiro servico de conta: toda regra de negocio nova (validade de
 * token, calculo de fatura, geracao de parcela, horizonte de recorrencia) deve
 * nascer numa classe que permita um teste com esta cara. Se a regra nova nao
 * der para testar assim, ela esta no lugar errado — provavelmente enredada em
 * servico ou em SQL.</p>
 *
 * <p>Roda em milissegundos no {@code make test}, sem Docker. E por isso que a
 * fatia mais grossa da piramide de testes deve ser desta camada.</p>
 */
@DisplayName("TokenRenovacao — regras de validade")
class TokenRenovacaoTest {

    private static final OffsetDateTime AGORA = OffsetDateTime.parse("2026-07-23T12:00:00Z");

    private TokenRenovacao token(OffsetDateTime expiraEm, OffsetDateTime tetoEm) {
        return new TokenRenovacao(UUID.randomUUID(), "hash", UUID.randomUUID(),
                                  expiraEm, tetoEm, "agente-teste");
    }

    @Test
    @DisplayName("Valido quando: nao usado, nao revogado, antes do prazo e antes do teto")
    void validoDentroDosPrazos() {
        TokenRenovacao t = token(AGORA.plusDays(30), AGORA.plusDays(90));
        assertTrue(t.estaValido(AGORA));
    }

    @Test
    @DisplayName("Expirado nao vale, mesmo com teto folgado")
    void expiradoNaoVale() {
        TokenRenovacao t = token(AGORA.minusMinutes(1), AGORA.plusDays(90));
        assertFalse(t.estaValido(AGORA));
    }

    @Test
    @DisplayName("Teto vencido nao vale, mesmo com prazo proprio folgado — e o que forca a senha de novo")
    void tetoVencidoNaoVale() {
        TokenRenovacao t = token(AGORA.plusDays(30), AGORA.minusMinutes(1));
        assertFalse(t.estaValido(AGORA));
    }

    @Test
    @DisplayName("Token ja usado morre na rotacao")
    void usadoNaoVale() {
        TokenRenovacao t = token(AGORA.plusDays(30), AGORA.plusDays(90));
        t.marcarUsado();
        assertFalse(t.estaValido(AGORA.plusSeconds(1)));
        assertTrue(t.jaFoiUsado());
    }

    @Test
    @DisplayName("Token revogado nao vale, e revogar de novo nao altera a data original")
    void revogadoNaoVale() {
        TokenRenovacao t = token(AGORA.plusDays(30), AGORA.plusDays(90));
        t.revogar();
        assertFalse(t.estaValido(AGORA.plusSeconds(1)));
        t.revogar(); // idempotente: a primeira data e a que fica
        assertFalse(t.estaValido(AGORA.plusSeconds(1)));
    }

    @Test
    @DisplayName("No instante exato do vencimento, o token ja nao vale (isBefore, nao isEqual)")
    void bordaExataDoVencimento() {
        TokenRenovacao t = token(AGORA, AGORA.plusDays(60));
        assertFalse(t.estaValido(AGORA),
            "agora == expira_em deveria ser invalido: o contrato e ANTES do prazo");
    }
}
