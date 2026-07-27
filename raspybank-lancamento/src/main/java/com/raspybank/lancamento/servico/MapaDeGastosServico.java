package com.raspybank.lancamento.servico;

import com.raspybank.lancamento.dominio.LinhaDoMapa;
import com.raspybank.lancamento.dominio.TipoLancamento;
import com.raspybank.lancamento.repositorio.LancamentoRepositorio;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * O quadro central (T-07): quanto entrou, quanto saiu e o que sobrou, mês a
 * mês, num ano civil.
 *
 * <h3>Nada aqui e persistido</h3>
 *
 * <p>Principio P1, sem excecao: o mapa e recalculado a cada chamada, a partir
 * dos lancamentos. Se um dia isso doer, a resposta e cache com invalidacao
 * explicita — nunca uma coluna de total, que seria uma segunda verdade
 * esperando divergir (R1).</p>
 *
 * <h3>Uma consulta, o resto em memoria</h3>
 *
 * <p>O banco devolve celulas cruas — {@link LinhaDoMapa} — e esta classe as
 * organiza. Parece trabalho que o SQL poderia fazer, e poderia: com
 * {@code GROUPING SETS} sairiam os totais junto. Nao vale a pena. O volume e
 * de dezenas de linhas por ano, o custo em memoria e desprezivel, e o codigo
 * que monta a arvore aqui e legivel por qualquer pessoa — enquanto o SQL
 * equivalente so seria legivel por quem escreve SQL analitico.</p>
 *
 * <h3>Doze celulas sempre</h3>
 *
 * <p>Mesmo zeradas. A tabela da tela tem doze colunas de qualquer jeito;
 * devolver esparso empurraria para o frontend a tarefa de descobrir buraco, e
 * e exatamente ai que nasce coluna desalinhada.</p>
 */
@Service
public class MapaDeGastosServico {

    private static final int MESES = 12;

    /** O rotulo da linha que agrupa o que F11 permitiu deixar sem segundo nivel. */
    public static final String SEM_SUBCATEGORIA = "(sem subcategoria)";

    private final LancamentoRepositorio lancamentos;

    public MapaDeGastosServico(LancamentoRepositorio lancamentos) {
        this.lancamentos = lancamentos;
    }

    @Transactional(readOnly = true)
    public Mapa montar(UUID ambienteId, int ano) {

        List<LinhaDoMapa> linhas = lancamentos.mapaDoAno(ambienteId, ano);

        Bloco saidas = bloco(linhas, TipoLancamento.SAIDA);
        Bloco entradas = bloco(linhas, TipoLancamento.ENTRADA);

        return new Mapa(ano, saidas, entradas, saldo(entradas, saidas));
    }

    // =========================================================================

    /** Um bloco (saidas ou entradas) com as categorias, as subcategorias e os totais. */
    private Bloco bloco(List<LinhaDoMapa> todas, TipoLancamento tipo) {

        List<LinhaDoMapa> doBloco = todas.stream().filter(l -> l.tipo() == tipo).toList();

        // LinkedHashMap para a ordem ser estavel entre duas chamadas iguais —
        // ordem arbitraria faria as linhas da tabela dancarem a cada F5.
        Map<UUID, List<LinhaDoMapa>> porCategoria = new LinkedHashMap<>();
        for (LinhaDoMapa l : doBloco) {
            porCategoria.computeIfAbsent(l.categoriaId(), k -> new ArrayList<>()).add(l);
        }

        List<LinhaCategoria> categorias = porCategoria.values().stream()
            .map(this::linhaDeCategoria)
            .sorted(Comparator.comparing(LinhaCategoria::nome, String.CASE_INSENSITIVE_ORDER))
            .toList();

        List<Celula> totaisPorMes = somarCelulas(
            categorias.stream().map(LinhaCategoria::celulas).toList());

        return new Bloco(categorias, totaisPorMes, totalDe(totaisPorMes));
    }

    private LinhaCategoria linhaDeCategoria(List<LinhaDoMapa> daCategoria) {
        LinhaDoMapa qualquer = daCategoria.get(0);

        Map<UUID, List<LinhaDoMapa>> porSubcategoria = new LinkedHashMap<>();
        for (LinhaDoMapa l : daCategoria) {
            porSubcategoria.computeIfAbsent(l.subcategoriaId(), k -> new ArrayList<>()).add(l);
        }

        List<LinhaSubcategoria> subcategorias = porSubcategoria.entrySet().stream()
            .map(e -> {
                List<Celula> celulasDaSub = celulasDe(e.getValue());
                return new LinhaSubcategoria(
                    e.getKey(),
                    e.getKey() == null ? SEM_SUBCATEGORIA : e.getValue().get(0).subcategoriaNome(),
                    celulasDaSub,
                    totalDe(celulasDaSub));
            })
            // A linha "(sem subcategoria)" vai por ultimo: ela e o resto, e
            // resto no meio da lista parece categoria propria.
            .sorted(Comparator
                .comparing((LinhaSubcategoria s) -> s.subcategoriaId() == null)
                .thenComparing(LinhaSubcategoria::nome, String.CASE_INSENSITIVE_ORDER))
            .toList();

        List<Celula> celulas = celulasDe(daCategoria);

        return new LinhaCategoria(
            qualquer.categoriaId(), qualquer.categoriaNome(),
            celulas, totalDe(celulas), subcategorias);
    }

    /** Doze celulas, preenchendo com zero os meses sem lancamento. */
    private List<Celula> celulasDe(List<LinhaDoMapa> linhas) {
        BigDecimal[] realizado = new BigDecimal[MESES + 1];
        BigDecimal[] previsto = new BigDecimal[MESES + 1];

        for (int m = 1; m <= MESES; m++) {
            realizado[m] = BigDecimal.ZERO;
            previsto[m] = BigDecimal.ZERO;
        }
        for (LinhaDoMapa l : linhas) {
            realizado[l.mes()] = realizado[l.mes()].add(l.realizado());
            previsto[l.mes()] = previsto[l.mes()].add(l.previsto());
        }

        return IntStream.rangeClosed(1, MESES)
            .mapToObj(m -> new Celula(m, realizado[m], previsto[m]))
            .toList();
    }

    /** Soma vertical: varias listas de doze celulas viram uma. */
    private List<Celula> somarCelulas(List<List<Celula>> listas) {
        return IntStream.rangeClosed(1, MESES)
            .mapToObj(m -> new Celula(m,
                somar(listas, m, Celula::realizado),
                somar(listas, m, Celula::previsto)))
            .toList();
    }

    private BigDecimal somar(List<List<Celula>> listas, int mes,
                             java.util.function.Function<Celula, BigDecimal> campo) {
        return listas.stream()
            .map(l -> campo.apply(l.get(mes - 1)))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Valor totalDe(List<Celula> celulas) {
        return new Valor(
            celulas.stream().map(Celula::realizado).reduce(BigDecimal.ZERO, BigDecimal::add),
            celulas.stream().map(Celula::previsto).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /**
     * A linha que responde a pergunta que a familia realmente faz (B-D12).
     *
     * <p>Nao e "quanto gastei", e "sobrou ou faltou". Entradas menos saidas,
     * mes a mes — e o unico lugar do mapa onde numero negativo aparece.</p>
     */
    private Saldo saldo(Bloco entradas, Bloco saidas) {
        List<Celula> porMes = IntStream.rangeClosed(1, MESES)
            .mapToObj(m -> {
                Celula e = entradas.totaisPorMes().get(m - 1);
                Celula s = saidas.totaisPorMes().get(m - 1);
                return new Celula(m,
                    e.realizado().subtract(s.realizado()),
                    e.previsto().subtract(s.previsto()));
            })
            .toList();

        return new Saldo(porMes, totalDe(porMes));
    }

    // =========================================================================
    // A forma do quadro
    // =========================================================================

    public record Mapa(int ano, Bloco saidas, Bloco entradas, Saldo saldo) {}

    public record Bloco(List<LinhaCategoria> categorias,
                        List<Celula> totaisPorMes,
                        Valor total) {}

    public record LinhaCategoria(UUID categoriaId, String nome,
                                 List<Celula> celulas, Valor total,
                                 List<LinhaSubcategoria> subcategorias) {}

    public record LinhaSubcategoria(UUID subcategoriaId, String nome,
                                    List<Celula> celulas, Valor total) {}

    public record Celula(int mes, BigDecimal realizado, BigDecimal previsto) {}

    public record Valor(BigDecimal realizado, BigDecimal previsto) {}

    public record Saldo(List<Celula> porMes, Valor total) {}
}
