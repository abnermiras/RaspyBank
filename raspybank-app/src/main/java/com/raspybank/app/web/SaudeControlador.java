package com.raspybank.app.web;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Endpoint de diagnostico da fundacao.
 *
 * <p>Existe para provar, sem ambiguidade, que a pilha inteira esta de pe:
 * aplicacao no ar, conexao com o banco funcionando, migracoes aplicadas e —
 * o mais importante — conectando com o usuario CORRETO.</p>
 *
 * <p>A verificacao do usuario de banco nao e curiosidade. Se a aplicacao
 * estiver conectando como proprietario, todo o Row Level Security da migracao
 * V3 esta desligado, silenciosamente e sem erro nenhum. Este endpoint torna
 * isso visivel.</p>
 */
@RestController
@RequestMapping("/api/diagnostico")
public class SaudeControlador {

    private final JdbcTemplate jdbc;

    public SaudeControlador(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Map<String, Object> diagnostico() {
        Map<String, Object> resposta = new LinkedHashMap<>();

        resposta.put("aplicacao", "RaspyBank");
        resposta.put("versao", "1.0.0-SNAPSHOT");

        String usuarioBanco = jdbc.queryForObject("SELECT current_user", String.class);
        resposta.put("usuarioBanco", usuarioBanco);

        // A aplicacao TEM que estar como raspybank_app. Qualquer outra coisa
        // significa RLS desativado.
        resposta.put("rlsAtivo", "raspybank_app".equals(usuarioBanco));

        resposta.put("versaoBanco",
            jdbc.queryForObject("SELECT current_setting('server_version')", String.class));

        Integer migracoes = jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        resposta.put("migracoesAplicadas", migracoes);

        return resposta;
    }
}
