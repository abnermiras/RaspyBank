package com.raspybank.integracao;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * O banco dos testes de integracao: o ambiente real em miniatura.
 *
 * <p>Tres decisoes deliberadas aqui (Bloco C):</p>
 *
 * <ol>
 *   <li><b>A MESMA imagem da infra</b> ({@code postgres:18.4}), nunca uma
 *       generica. Testar contra outra versao e testar outro banco.</li>
 *   <li><b>O MESMO script de init</b> ({@code infra/postgres/init/01-app-user.sh}),
 *       copiado do repositorio, nao reescrito. Se o script real quebrar, o
 *       teste quebra — que e exatamente o que se quer de um teste.</li>
 *   <li><b>Dois usuarios de banco</b>, como em producao: {@code raspybank_owner}
 *       (migracoes) e {@code raspybank_app} (aplicacao, sujeito a RLS). Um teste
 *       que rodasse tudo como owner estaria testando um sistema com o RLS
 *       silenciosamente desligado — o exato defeito que ele existe para pegar.</li>
 * </ol>
 *
 * <p><b>Padrao singleton, e nao {@code @Testcontainers}/{@code @Container}:</b>
 * a anotacao derruba e recria o container a cada classe de teste. Um campo
 * estatico iniciado uma unica vez e compartilhado por toda a suite corta o
 * tempo total drasticamente. O Ryuk (container auxiliar do Testcontainers)
 * remove tudo ao fim da JVM — nao ha vazamento.</p>
 */
final class PostgresDeTeste {

    static final String USUARIO_APP = "raspybank_app";
    static final String SENHA_APP   = "app-somente-teste";

    static final PostgreSQLContainer<?> CONTAINER = criar();

    private PostgresDeTeste() {
    }

    private static PostgreSQLContainer<?> criar() {
        PostgreSQLContainer<?> c = new PostgreSQLContainer<>(
                DockerImageName.parse("postgres:18.4"))
            .withDatabaseName("raspybank")
            .withUsername("raspybank_owner")
            .withPassword("owner-somente-teste")
            // Lidas pelo script de init, como no compose real.
            .withEnv("APP_DB_USER", USUARIO_APP)
            .withEnv("APP_DB_PASSWORD", SENHA_APP)
            // O caminho e relativo ao modulo raspybank-app, onde o surefire roda.
            .withCopyFileToContainer(
                MountableFile.forHostPath("../infra/postgres/init/01-app-user.sh", 0755),
                "/docker-entrypoint-initdb.d/01-app-user.sh");

        c.start();
        return c;
    }
}
