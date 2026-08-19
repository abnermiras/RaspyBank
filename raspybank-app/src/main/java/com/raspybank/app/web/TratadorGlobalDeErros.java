package com.raspybank.app.web;

import com.raspybank.shared.erro.ConflitoDeEstado;
import com.raspybank.shared.erro.OperacaoNaoPermitida;
import com.raspybank.shared.erro.RecursoNaoEncontrado;
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
 *   <li><b>403</b> — operacao valida em forma e proibida por regra de dominio</li>
 *   <li><b>404</b> — caminho ou recurso inexistente</li>
 *   <li><b>409</b> — conflito com estado existente (e-mail ou nome ja usado)</li>
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

    /**
     * Regra de dominio violada — <b>403</b>.
     *
     * <p>A mensagem vem da entidade e vai inteira para o cliente, ao contrario
     * do 500. E deliberado: aqui a frase diz o que a pessoa nao pode fazer
     * ("categoria sistemica nao se renomeia"), que e informacao acionavel e
     * nao detalhe interno.</p>
     */
    @ExceptionHandler(OperacaoNaoPermitida.class)
    public ResponseEntity<Map<String, String>> naoPermitida(OperacaoNaoPermitida e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
            "erro", e.getMessage()));
    }

    /**
     * Recurso inexistente ou de outro ambiente — <b>404</b> nos dois casos.
     *
     * <p>Ver {@link RecursoNaoEncontrado}: distinguir "nao existe" de "nao e
     * seu" transformaria a API num oraculo sobre quais ids existem.</p>
     */
    @ExceptionHandler(RecursoNaoEncontrado.class)
    public ResponseEntity<Map<String, String>> naoEncontrado(RecursoNaoEncontrado e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
            "erro", e.getMessage()));
    }

    /**
     * Estado atual impede a operacao — <b>409</b>.
     *
     * <p>Diferente do 403: ali a operacao nunca sera possivel; aqui ela passa
     * a ser assim que o estado mudar. Encerrar conta com saldo funciona depois
     * que o dinheiro sair — e a mensagem diz isso.</p>
     */
    @ExceptionHandler(ConflitoDeEstado.class)
    public ResponseEntity<Map<String, String>> conflito(ConflitoDeEstado e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
            "erro", e.getMessage()));
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
            // O indice do telegram tambem e PARCIAL (so nao-nulos): varias
            // contas sem telegram convivem. Desde a V21 ele e UNICO sobre a forma
            // NORMALIZADA — lower(regexp_replace(btrim(...), '^@', '')) —, entao
            // 'abner', 'ABNER' e '@abner' colidem entre si, que e o ponto: sao a
            // mesma conta de Telegram. A V21 tambem levou a garantia do vazio
            // para o banco (ck_usuario_telegram_identificavel), que antes so
            // existia no Java. Este 409 e o caso real de duas pessoas — ou da
            // mesma pessoa duas vezes — apontando para a mesma conta de Telegram,
            // que faria o bot nao saber para quem lancar.
            //
            // O nome do indice e casado por string aqui: a V21 o recriou com o
            // MESMO nome de proposito, senao esta violacao viraria 500.
            if (mensagem.contains("ux_usuario_telegram")) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "erro", "Este Telegram ja esta ligado a outra conta"));
            }
            // Os dois indices sao PARCIAIS: so valem entre as nao arquivadas.
            // Arquivar "Mercado" e criar outra "Mercado" e legitimo — a
            // primeira segue nomeando o passado, a segunda recomeca a
            // contagem. O conflito so existe entre duas ativas, que deixariam
            // o seletor da T-08 com duas linhas identicas.
            if (mensagem.contains("ux_categoria_nome")) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "erro", "Ja existe uma categoria ativa com este nome"));
            }
            if (mensagem.contains("ux_subcategoria_nome")) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "erro", "Ja existe uma subcategoria ativa com este nome nesta categoria"));
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
