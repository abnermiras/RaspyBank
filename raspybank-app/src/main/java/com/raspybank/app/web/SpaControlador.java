package com.raspybank.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Entrega o {@code index.html} nos endereços internos da SPA.
 *
 * <p>O problema que ele resolve: as rotas do React ({@code /mapa},
 * {@code /contas}...) só existem no navegador. Enquanto a pessoa navega pela
 * tela, nada é pedido ao servidor. Mas recarregar a página em
 * {@code /contas} — ou abrir um favorito — manda o caminho para o Spring, que
 * não tem controlador nenhum ali e responderia 401 pela regra do
 * {@code anyRequest().authenticated()}. Voltar a SPA nesse caso não é
 * exceção: é o comportamento normal de aplicação de página única.
 *
 * <p><strong>Por que a lista é explícita.</strong> O caminho fácil seria um
 * curinga devolvendo {@code index.html} para tudo o que não casasse com
 * {@code /api/**}. Isso funcionaria e esconderia todo erro de digitação: um
 * pedido a {@code /api/lancamentoss} — com o "s" a mais — passaria a devolver
 * a tela em vez de 404, e o defeito só apareceria como "a tela não carrega os
 * dados", muito longe da causa. Com a lista explícita, endereço desconhecido
 * continua sendo 404, que é a verdade.
 *
 * <p>Rota nova no React exige uma linha aqui. É de propósito: são duas
 * declarações do mesmo fato, e a segunda quebra ruidosamente na primeira
 * recarga de página — que é quando ainda é barato consertar.
 */
@Controller
public class SpaControlador {

    @GetMapping({
        "/entrar",
        "/mapa",
        "/lancamentos",
        "/categorias",
        "/contas",
        "/cartoes",
        "/perfil"
    })
    public String telas() {
        // "forward" e não "redirect": o endereço na barra continua sendo o que
        // a pessoa pediu, e é o React quem decide o que desenhar a partir dele.
        // Um redirecionamento para "/" jogaria a pessoa fora da página que ela
        // tentou abrir, que é justamente o que se quer evitar.
        return "forward:/index.html";
    }
}
