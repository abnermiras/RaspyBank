package com.raspybank.app.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Define a ordem do interceptador de transacao do Spring.
 *
 * <p>Por padrao, esse interceptador tem a menor precedencia possivel, o que
 * significa que ele e sempre o mais interno — nenhum aspecto consegue rodar
 * dentro da transacao.</p>
 *
 * <p>O {@code ConfiguradorSessaoRls} precisa exatamente disso: executar o
 * {@code set_config} depois do BEGIN, porque o valor e local a transacao.
 * Fixando a ordem da transacao em 0, qualquer aspecto com ordem maior
 * (o nosso usa 10) passa a executar dentro dela.</p>
 *
 * <p>Sem esta classe, as politicas de Row Level Security nunca receberiam a
 * identidade do usuario e nenhuma consulta devolveria linha alguma. Falharia
 * de forma segura, mas falharia.</p>
 */
@Configuration
@EnableTransactionManagement(order = 0)
public class ConfiguracaoTransacional {
}
