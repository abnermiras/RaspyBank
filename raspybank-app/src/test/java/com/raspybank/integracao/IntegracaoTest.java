package com.raspybank.integracao;

import com.raspybank.app.RaspyBankApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base de todo teste de integracao: sobe a aplicacao INTEIRA contra o
 * Postgres de teste.
 *
 * <p>Subir o contexto ja e, por si, o primeiro teste do Bloco C: o Flyway
 * aplica todas as migracoes com o usuario proprietario, e o Hibernate
 * ({@code ddl-auto: validate}) confere cada entidade contra o schema
 * resultante. Migracao quebrada ou entidade divergente = contexto nao sobe =
 * suite inteira falha.</p>
 *
 * <p>As propriedades abaixo tem precedencia sobre o application.yml, entao os
 * placeholders {@code ${JWT_SEGREDO}} etc. nunca chegam a ser resolvidos —
 * os testes nao dependem de nenhuma variavel de ambiente.</p>
 *
 * <p>O contexto e CACHEADO entre as classes de teste que estendem esta base
 * (mesmas propriedades = mesmo contexto), entao o custo de subida e pago uma
 * unica vez pela suite.</p>
 */
// A classe de configuracao e apontada EXPLICITAMENTE porque este pacote
// (integracao) e irmao do pacote da aplicacao (app), nao filho — a busca
// automatica do Spring so sobe na hierarquia e nao a encontraria.
@SpringBootTest(
    classes = RaspyBankApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegracaoTest {

    protected static final org.testcontainers.containers.PostgreSQLContainer<?> POSTGRES =
        PostgresDeTeste.CONTAINER;

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        // Aplicacao conecta como raspybank_app — sujeita ao RLS, como em producao.
        registro.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registro.add("spring.datasource.username", () -> PostgresDeTeste.USUARIO_APP);
        registro.add("spring.datasource.password", () -> PostgresDeTeste.SENHA_APP);

        // Flyway migra como proprietario — unico com privilegio de DDL.
        registro.add("spring.flyway.user",     POSTGRES::getUsername);
        registro.add("spring.flyway.password", POSTGRES::getPassword);

        // Segredo fixo de teste (64 hex). Vale so dentro da JVM de teste.
        registro.add("raspybank.jwt.segredo",
            () -> "cafe0123456789abcdef0123456789abcdef0123456789abcdef0123456789ab");
    }
}
