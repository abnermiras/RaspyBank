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
