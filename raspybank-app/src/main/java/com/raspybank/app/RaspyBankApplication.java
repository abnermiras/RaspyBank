package com.raspybank.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.autoconfigure.domain.EntityScan;

/**
 * Ponto de entrada da aplicacao.
 *
 * <p>Esta classe vive em {@code com.raspybank.app}, mas as entidades e
 * repositorios vivem em {@code com.raspybank.identidade},
 * {@code com.raspybank.ambiente} e assim por diante. Como o Spring Boot
 * varre por padrao apenas o pacote desta classe e seus subpacotes, e preciso
 * declarar explicitamente onde procurar.</p>
 *
 * <p>Isso e consequencia direta da organizacao por contexto de negocio. Vale
 * a pena: em troca de tres anotacoes, cada contexto fica isolado e pode ser
 * extraido para um servico proprio na Fase 8.</p>
 */
@SpringBootApplication(scanBasePackages = "com.raspybank")
@EntityScan(basePackages = "com.raspybank")
@EnableJpaRepositories(basePackages = "com.raspybank")
public class RaspyBankApplication {

    public static void main(String[] args) {
        SpringApplication.run(RaspyBankApplication.class, args);
    }
}
