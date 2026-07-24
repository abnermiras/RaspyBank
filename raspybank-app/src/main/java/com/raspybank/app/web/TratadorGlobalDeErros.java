package com.raspybank.app.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * O contrato de erro da API — pre-requisito das telas (I-12).
 *
 * <p><b>O contrato:</b> todo erro responde JSON com a chave {@code erro}
 * (frase curta, exibivel ao usuario) e, apenas na validacao, {@code campos}
 * (mapa campo → mensagem, para o formulario marcar o lugar certo). Nenhum
 * erro vaza stacktrace, SQL ou nome de classe: o que o cliente nao pode
 * consertar, ele nao precisa ler.</p>
 *
 * <ul>
 *   <li><b>400</b> — corpo invalido ou validacao de campos</li>
 *   <li><b>404</b> — caminho ou recurso inexistente</li>
 *   <li><b>409</b> — conflito com estado existente (e-mail ja cadastrado)</li>
 *   <li><b>500</b> — erro nosso; o detalhe vai para o log, nunca para fora</li>
 * </ul>
 *
 * <p>Os 401 de autenticacao NAO passam por aqui de proposito: os
 * controladores os constroem a mao porque a uniformidade dos corpos e uma
 * decisao de seguranca (B-A8), nao de conveniencia.</p>
 */
@RestControllerAdvice
public class TratadorGlobalDeErros {

    private static final Logger log = LoggerFactory.getLogger(TratadorGlobalDeErros.class);

    /** unique_violation do Postgres. */
    private static final String SQLSTATE_DUPLICATA = "23505";

    // -------------------------------------------------------------------------
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validacao(MethodArgumentNotValidException e) {
        // LinkedHashMap preserva a ordem dos campos do record — o formulario
        // recebe os erros na ordem em que os campos aparecem na tela.
        Map<String, String> campos = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(
            erro -> campos.putIfAbsent(erro.getField(), erro.getDefaultMessage()));

        return ResponseEntity.badRequest().body(Map.of(
            "erro",   "Dados invalidos",
            "campos", campos));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> corpoIlegivel(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of(
            "erro", "Corpo da requisicao invalido ou ausente"));
    }

    /**
     * Caminho que nao existe e <b>404</b>, nao 500.
     *
     * <p>Sem este tratador, a captura geral de {@code Exception} abaixo
     * engolia a falta de recurso como erro interno — e a primeira execucao
     * real provou o estrago: o navegador pede {@code /favicon.ico} sozinho,
     * o arquivo nao existe, e o log enchia de pilha de excecao como se a
     * aplicacao tivesse quebrado. Recurso ausente e resposta normal de um
     * servidor web; nada aqui merece nivel ERROR.</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> recursoInexistente(NoResourceFoundException e) {
        log.debug("Recurso estatico inexistente: {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
            "erro", "Recurso nao encontrado"));
    }

    // -------------------------------------------------------------------------
    /**
     * Apanha qualquer excecao e procura, na cadeia de causas, uma violacao de
     * unicidade do Postgres. Encontrando, vira 409; senao, 500.
     *
     * <p>A caca na cadeia de causas existe porque a mesma violacao chega
     * embrulhada de jeitos diferentes conforme o caminho: {@code
     * PersistenceException} no SQL nativo do cadastro, {@code
     * DataAccessException} nos repositorios. O que nao varia e o {@code
     * SQLException} la no fundo, com o SQLSTATE e o nome da constraint.</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> geral(Exception e) {

        SQLException sql = causaSql(e);
        if (sql != null && SQLSTATE_DUPLICATA.equals(sql.getSQLState())) {
            String mensagem = String.valueOf(sql.getMessage());
            if (mensagem.contains("ux_usuario_email")) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "erro", "Ja existe uma conta com este e-mail"));
            }
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "erro", "Registro duplicado"));
        }

        // Daqui para baixo o problema e nosso. O log guarda a historia
        // completa; o cliente recebe o minimo.
        log.error("Erro nao tratado na requisicao", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
            "erro", "Erro interno. Tente novamente; se persistir, avise o administrador."));
    }

    private static SQLException causaSql(Throwable topo) {
        for (Throwable t = topo; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }
}
