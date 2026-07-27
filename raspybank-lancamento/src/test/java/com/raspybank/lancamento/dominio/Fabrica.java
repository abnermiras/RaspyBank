package com.raspybank.lancamento.dominio;

import java.lang.reflect.Field;
import java.util.UUID;

/**
 * Fixtures das entidades para os testes puros.
 *
 * <h3>Por que usa reflexao</h3>
 *
 * <p>Duas coisas que o teste precisa nao tem caminho publico na producao, e
 * isso e deliberado: o {@code id} vem do banco ({@code DEFAULT uuidv7()}) e a
 * categoria sistemica nasce da funcao {@code fn_criar_categorias_sistemicas},
 * nunca da aplicacao.</p>
 *
 * <p>A alternativa seria abrir construtores publicos so para o teste, o que
 * criaria na producao exatamente o caminho que o modelo fecha de proposito —
 * uma sistemica sem cadeado, ou um id escolhido a mao fragmentando o indice.
 * Entre poluir o dominio e concentrar a feiura num arquivo de teste, a feiura
 * fica aqui.</p>
 *
 * <p><b>Isto nao substitui teste com banco.</b> O que estas fixtures cobrem
 * sao as regras que vivem no codigo Java. As que vivem no schema — chaves
 * compostas, politicas de RLS, CHECKs — sao verificadas por
 * {@code DominioRlsTest}, contra um Postgres de verdade.</p>
 */
final class Fabrica {

    private Fabrica() {
    }

    static final UUID AMBIENTE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID OUTRO_AMBIENTE = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    static final UUID CONTA = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    static final UUID USUARIO = UUID.fromString("00000000-0000-0000-0000-0000000000e1");

    /** Categoria comum, ja "persistida": com id, como viria do banco. */
    static Categoria categoria(String nome, TipoCategoria tipo) {
        return categoria(AMBIENTE, nome, tipo);
    }

    static Categoria categoria(UUID ambienteId, String nome, TipoCategoria tipo) {
        Categoria c = new Categoria(ambienteId, nome, tipo);
        definir(c, "id", UUID.randomUUID());
        return c;
    }

    /** Sistemica, como a funcao do banco a criaria: com codigo e com cadeado. */
    static Categoria sistemica(CodigoSistemico codigo, boolean entraNoMapa) {
        Categoria c = new Categoria(AMBIENTE, codigo.name(), TipoCategoria.AMBOS);
        definir(c, "id", UUID.randomUUID());
        definir(c, "codigo", codigo.name());
        definir(c, "sistemica", true);
        definir(c, "entraNoMapa", entraNoMapa);
        return c;
    }

    static Subcategoria subcategoria(Categoria mae, String nome) {
        Subcategoria s = new Subcategoria(mae, nome);
        definir(s, "id", UUID.randomUUID());
        return s;
    }

    private static void definir(Object alvo, String campo, Object valor) {
        try {
            Field f = alvo.getClass().getDeclaredField(campo);
            f.setAccessible(true);
            f.set(alvo, valor);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                "Campo '" + campo + "' mudou de nome em " + alvo.getClass().getSimpleName()
                    + " — atualize a fixture", e);
        }
    }
}
