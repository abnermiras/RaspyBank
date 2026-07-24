package com.raspybank.identidade.servico;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Emite e valida o token de acesso.
 *
 * <h3>O que e um JWT, em uma frase</h3>
 * Um pequeno JSON com o identificador de quem esta conectado e uma data de
 * validade, acompanhado de uma assinatura criptografica. Qualquer um consegue
 * LER o conteudo — ele nao e criptografado, apenas codificado. Mas ninguem
 * consegue ALTERAR sem invalidar a assinatura.
 *
 * <p>A consequencia pratica disso e importante: <b>nunca coloque segredo
 * dentro de um token</b>. Ele carrega identificadores, nunca senha, nunca
 * dado financeiro.</p>
 *
 * <h3>Por que vale a pena</h3>
 * O servidor nao precisa consultar o banco a cada requisicao para saber quem
 * esta falando: a assinatura ja prova. Em troca, um token emitido nao pode
 * ser cancelado antes de expirar — e exatamente por isso ele dura 15 minutos.
 * O que dura 30 dias e o token de renovacao, esse sim revogavel.
 */
@Service
public class JwtServico {

    private static final String CLAIM_AMBIENTE = "amb";
    private static final String CLAIM_FAMILIA  = "fam";
    private static final String CLAIM_TIPO     = "typ";
    private static final String TIPO_ACESSO    = "acesso";

    private final SecretKey chave;
    private final int minutosValidade;

    public JwtServico(
            @Value("${raspybank.jwt.segredo}") String segredo,
            @Value("${raspybank.jwt.minutos-acesso:15}") int minutosValidade) {

        // HMAC-SHA256 exige pelo menos 256 bits, ou seja, 32 bytes.
        // Falhar aqui, na inicializacao, e muito melhor do que descobrir em
        // producao que o segredo era fraco demais.
        if (segredo == null || segredo.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                "raspybank.jwt.segredo precisa ter no minimo 32 caracteres. "
              + "Gere com: openssl rand -hex 32");
        }
        this.chave = Keys.hmacShaKeyFor(segredo.getBytes(StandardCharsets.UTF_8));
        this.minutosValidade = minutosValidade;
    }

    /**
     * Emite um token de acesso.
     *
     * @param ambienteId ambiente em que a pessoa esta operando; pode ser nulo
     *                   logo apos o cadastro, antes de haver ambiente
     * @param familiaId  familia do token de renovacao que sustenta esta sessao.
     *                   E o que permite "sair deste dispositivo" revogar
     *                   somente a cadeia certa (I-14)
     */
    public String emitirAcesso(UUID usuarioId, UUID ambienteId, UUID familiaId) {
        Instant agora = Instant.now();
        Instant fim = agora.plus(minutosValidade, ChronoUnit.MINUTES);

        var construtor = Jwts.builder()
            .subject(usuarioId.toString())
            .claim(CLAIM_TIPO, TIPO_ACESSO)
            .issuedAt(Date.from(agora))
            .expiration(Date.from(fim));

        if (ambienteId != null) {
            construtor.claim(CLAIM_AMBIENTE, ambienteId.toString());
        }
        if (familiaId != null) {
            construtor.claim(CLAIM_FAMILIA, familiaId.toString());
        }

        return construtor.signWith(chave).compact();
    }

    /**
     * Valida a assinatura e o prazo, devolvendo o conteudo.
     *
     * <p>Devolve vazio em qualquer falha — assinatura invalida, token expirado,
     * formato corrompido. O chamador nao precisa distinguir os casos, e nao
     * deve: informar ao cliente QUAL foi o problema ajuda quem esta atacando.</p>
     */
    public Optional<ConteudoToken> validar(String token) {
        try {
            Claims claims = Jwts.parser()
                .verifyWith(chave)
                .build()
                .parseSignedClaims(token)
                .getPayload();

            if (!TIPO_ACESSO.equals(claims.get(CLAIM_TIPO, String.class))) {
                return Optional.empty();
            }

            UUID usuarioId = UUID.fromString(claims.getSubject());

            String amb = claims.get(CLAIM_AMBIENTE, String.class);
            UUID ambienteId = (amb != null) ? UUID.fromString(amb) : null;

            String fam = claims.get(CLAIM_FAMILIA, String.class);
            UUID familiaId = (fam != null) ? UUID.fromString(fam) : null;

            return Optional.of(new ConteudoToken(usuarioId, ambienteId, familiaId));

        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Dados extraidos de um token valido. */
    public record ConteudoToken(UUID usuarioId, UUID ambienteId, UUID familiaId) {
    }
}
