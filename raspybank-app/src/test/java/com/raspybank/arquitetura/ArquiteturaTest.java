package com.raspybank.arquitetura;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * As fronteiras entre modulos, verificadas pelo build.
 *
 * <p><b>Por que isto existe.</b> A decisao #02 escolheu monolito modular
 * extraivel: um unico artefato, mas com fronteiras de microservico. O problema
 * conhecido dessa escolha e que a fronteira e invisivel — nada impede alguem
 * de escrever um import que atravessa contextos, e no dia seguinte o modulo
 * nao pode mais ser extraido.</p>
 *
 * <p>Estes testes tornam a fronteira obrigatoria: violacao quebra o build, na
 * hora, dizendo exatamente qual classe importou o que nao devia. E a diferenca
 * entre uma fronteira real e uma fronteira que existe so no diagrama.</p>
 *
 * <p><b>Ao criar um novo contexto</b> (lancamento, cartao, classificacao),
 * acrescente-o na constante CONTEXTOS e adicione o teste correspondente.</p>
 */
@DisplayName("Fronteiras entre modulos")
class ArquiteturaTest {

    private static final String SHARED     = "com.raspybank.shared..";
    private static final String APP        = "com.raspybank.app..";
    private static final String IDENTIDADE = "com.raspybank.identidade..";
    private static final String AMBIENTE   = "com.raspybank.ambiente..";
    private static final String AUDITORIA  = "com.raspybank.auditoria..";

    private static JavaClasses classes;

    @BeforeAll
    static void importar() {
        classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.raspybank");
    }

    // =========================================================================
    // Regra central: contextos nao se conhecem
    // =========================================================================
    // Contextos se comunicam por EVENTOS, atraves da tabela outbox. Quando um
    // precisa referenciar outro, guarda o IDENTIFICADOR, nunca o objeto.
    // E isso que permite extrair um deles para outro processo na Fase 8.

    @Test
    @DisplayName("Identidade nao conhece outros contextos")
    void identidadeIsolada() {
        noClasses()
            .that().resideInAPackage(IDENTIDADE)
            .should().dependOnClassesThat().resideInAnyPackage(AMBIENTE, AUDITORIA, APP)
            .check(classes);
    }

    @Test
    @DisplayName("Ambiente nao conhece outros contextos")
    void ambienteIsolado() {
        noClasses()
            .that().resideInAPackage(AMBIENTE)
            .should().dependOnClassesThat().resideInAnyPackage(IDENTIDADE, AUDITORIA, APP)
            .check(classes);
    }

    @Test
    @DisplayName("Auditoria nao conhece outros contextos")
    void auditoriaIsolada() {
        noClasses()
            .that().resideInAPackage(AUDITORIA)
            .should().dependOnClassesThat().resideInAnyPackage(IDENTIDADE, AMBIENTE, APP)
            .check(classes);
    }

    // =========================================================================
    // Hierarquia de dependencia
    // =========================================================================

    @Test
    @DisplayName("Shared nao conhece nenhum contexto de negocio")
    void sharedNaoConheceContextos() {
        // shared e a base da piramide: todos dependem dele, ele nao depende de
        // ninguem. Se algo em shared precisar saber o que e uma Fatura, esta
        // no modulo errado.
        noClasses()
            .that().resideInAPackage(SHARED)
            .should().dependOnClassesThat()
            .resideInAnyPackage(IDENTIDADE, AMBIENTE, AUDITORIA, APP)
            .check(classes);
    }

    @Test
    @DisplayName("Nenhum contexto depende do modulo de montagem")
    void contextosNaoConhecemApp() {
        // A seta aponta sempre para o app, nunca do app para fora.
        noClasses()
            .that().resideOutsideOfPackage(APP)
            .should().dependOnClassesThat().resideInAPackage(APP)
            .check(classes);
    }

    // =========================================================================
    // Organizacao interna de cada contexto
    // =========================================================================

    @Test
    @DisplayName("Entidades ficam no pacote dominio")
    void entidadesNoPacoteCorreto() {
        classes()
            .that().areAnnotatedWith(jakarta.persistence.Entity.class)
            .should().resideInAPackage("..dominio..")
            .check(classes);
    }

    @Test
    @DisplayName("Repositorios ficam no pacote repositorio")
    void repositoriosNoPacoteCorreto() {
        classes()
            .that().areAssignableTo(org.springframework.data.repository.Repository.class)
            .and().areInterfaces()
            .should().resideInAPackage("..repositorio..")
            .check(classes);
    }

    @Test
    @DisplayName("Controladores nao acessam repositorios diretamente")
    void controladorNaoUsaRepositorio() {
        // Toda escrita precisa passar pela camada de servico, porque e la que
        // a auditoria e o outbox sao gravados, na mesma transacao do dado.
        // Controlador que chama repositorio direto contorna os dois.
        noClasses()
            .that().resideInAPackage("..web..")
            .should().dependOnClassesThat().resideInAPackage("..repositorio..")
            .check(classes);
    }
}
