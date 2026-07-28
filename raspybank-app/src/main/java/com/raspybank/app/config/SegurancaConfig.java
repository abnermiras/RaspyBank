package com.raspybank.app.config;

import com.raspybank.app.seguranca.FiltroAutenticacaoJwt;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration
public class SegurancaConfig {

    private final FiltroAutenticacaoJwt filtroJwt;

    public SegurancaConfig(FiltroAutenticacaoJwt filtroJwt) {
        this.filtroJwt = filtroJwt;
    }

    /**
     * BCrypt com fator 12.
     *
     * <p>O fator controla quantas rodadas o algoritmo executa, em escala
     * exponencial: 12 custa quatro vezes mais que 10. O objetivo e que
     * verificar uma senha leve algumas centenas de milissegundos — imperceptivel
     * para quem faz login, proibitivo para quem tenta milhoes de combinacoes.</p>
     *
     * <p>Note que o hash gerado comeca com {@code $2a$12$...}, ou seja, o
     * proprio hash carrega o algoritmo e o custo usados. E isso que permite
     * trocar de algoritmo depois sem migracao: o sistema reconhece hashes
     * antigos, valida normalmente e regrava com o novo no proximo login.</p>
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain cadeia(HttpSecurity http) throws Exception {
        http
            // CSRF desligado porque nao usamos cookie de sessao para
            // autenticar: o token vai no cabecalho Authorization, e o
            // navegador nao o envia automaticamente. Sem envio automatico,
            // nao ha ataque de CSRF a prevenir.
            .csrf(csrf -> csrf.disable())

            // Sem sessao no servidor. Cada requisicao se identifica sozinha
            // pelo token — e o que permite escalar horizontalmente depois.
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            .authorizeHttpRequests(reg -> reg
                .requestMatchers(
                    "/api/auth/**",
                    "/api/diagnostico",
                    "/actuator/health",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html").permitAll()

                // As telas. Sao publicas por natureza — a tela de login
                // precisa carregar ANTES de haver token. Nenhum dado sai
                // daqui; o que elas fazem e chamar a API, essa sim autenticada.
                //
                // As rotas internas da SPA aparecem aqui porque recarregar a
                // pagina em /contas manda o caminho ao servidor; quem devolve
                // o index.html nesses casos e o SpaControlador. A lista e a
                // mesma dele, e as duas precisam andar juntas.
                .requestMatchers(
                    "/",
                    "/index.html",
                    "/favicon.ico",
                    "/entrar",
                    "/mapa",
                    "/lancamentos",
                    "/categorias",
                    "/contas", "/cartoes").permitAll()

                // ATENCAO — unico curinga do arquivo, e ele e deliberado.
                //
                // A regra original desta classe era listar arquivo por
                // arquivo, sem curinga, porque "/**.js" liberaria tambem o que
                // ainda nao existe. A regra continua valendo; o que mudou foi
                // o alvo. O Vite gera nomes com hash de conteudo
                // (index-DkTzU8dD.css) que MUDAM a cada build, entao lista
                // explicita e impossivel de manter: ela ficaria errada no
                // primeiro build seguinte, e o sintoma seria a tela em branco.
                //
                // O motivo de isto ser seguro e que /assets/ nao e um espaco
                // aberto: nenhum controlador mora la. Todo endpoint deste
                // sistema nasce sob /api/, e um controlador novo continua
                // nascendo protegido, exatamente como a regra queria. O que
                // /assets/ contem e apenas a saida do build do frontend.
                .requestMatchers("/assets/**").permitAll()
                // Tudo o mais exige autenticacao. Endpoint novo nasce
                // protegido por padrao — o inverso seria pedir para esquecer.
                .anyRequest().authenticated())

            // Sem tela de login e sem redirecionamento: uma API responde 401.
            .exceptionHandling(e -> e.authenticationEntryPoint(
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

            .addFilterBefore(filtroJwt, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
