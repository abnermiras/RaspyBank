package com.raspybank.app.web;

import com.raspybank.ambiente.dominio.Ambiente;
import com.raspybank.ambiente.servico.AmbienteServico;
import com.raspybank.lancamento.servico.MapaDeGastosServico;
import com.raspybank.shared.contexto.ContextoRequisicao;
import com.raspybank.shared.erro.OperacaoNaoPermitida;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * O mapa de gastos — a tela T-07, contrato em {@code docs/api.md} §6.
 *
 * <p>E a tela que motivou o projeto: "um quadro com os gastos somados por
 * categoria, mes a mes, depois o total; abaixo, um quadro para cada categoria
 * com a sua subcategoria somada". Uma chamada devolve as duas coisas, porque
 * saem da mesma varredura.</p>
 *
 * <h3>Dois numeros por celula, nunca a soma pronta</h3>
 *
 * <p>B-D10: se o servidor mandasse somado, a tela nao teria como cumprir a
 * parte do "deixa claro que ainda nao realizou". Quem separa e o endpoint; a
 * tela so pinta.</p>
 */
@RestController
@RequestMapping("/api/relatorios")
public class RelatorioControlador {

    private final MapaDeGastosServico mapa;
    private final AmbienteServico ambientes;

    public RelatorioControlador(MapaDeGastosServico mapa, AmbienteServico ambientes) {
        this.mapa = mapa;
        this.ambientes = ambientes;
    }

    /**
     * @param ano ano civil (B-D11). Ausente, o corrente — a tela abre no ano
     *            em que a pessoa esta, que e o que ela quer ver em 11 dos 12
     *            meses.
     */
    @GetMapping("/mapa-de-gastos")
    public MapaResposta mapaDeGastos(@RequestParam(required = false) Integer ano) {

        UUID ambienteId = ambienteAtivo();
        int anoAlvo = ano != null ? ano : LocalDate.now().getYear();

        return MapaResposta.de(mapa.montar(ambienteId, anoAlvo), resumoDoAmbiente(ambienteId));
    }

    // =========================================================================

    private Referencia resumoDoAmbiente(UUID ambienteId) {
        // O nome vive no contexto de ambiente, que o modulo de lancamento nao
        // pode importar. A costura acontece aqui, no modulo de montagem — a
        // mesma razao de ContaControlador conhecer os dois.
        return ambientes.listarDoUsuario().stream()
            .filter(a -> a.getId().equals(ambienteId))
            .findFirst()
            .map(a -> new Referencia(a.getId(), a.getNome()))
            .orElseGet(() -> new Referencia(ambienteId, null));
    }

    private static UUID ambienteAtivo() {
        return ContextoRequisicao.ambienteId().orElseThrow(() -> new OperacaoNaoPermitida(
            "Sessao sem ambiente ativo. Selecione um em POST /api/sessao/ambiente"));
    }

    /** Dinheiro e string em todo o contrato (F1): duas casas, sempre. */
    private static String dinheiro(BigDecimal valor) {
        return valor.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    // =========================================================================
    // Contrato de saida
    // =========================================================================

    public record MapaResposta(int ano, Referencia ambiente,
                               BlocoResposta saidas, BlocoResposta entradas,
                               SaldoResposta saldo) {

        static MapaResposta de(MapaDeGastosServico.Mapa m, Referencia ambiente) {
            return new MapaResposta(
                m.ano(), ambiente,
                BlocoResposta.de(m.saidas()),
                BlocoResposta.de(m.entradas()),
                SaldoResposta.de(m.saldo()));
        }
    }

    public record BlocoResposta(List<CategoriaResposta> categorias,
                                List<CelulaResposta> totaisPorMes,
                                ValorResposta total) {

        static BlocoResposta de(MapaDeGastosServico.Bloco b) {
            return new BlocoResposta(
                b.categorias().stream().map(CategoriaResposta::de).toList(),
                CelulaResposta.de(b.totaisPorMes()),
                ValorResposta.de(b.total()));
        }
    }

    public record CategoriaResposta(UUID categoriaId, String nome,
                                    List<CelulaResposta> celulas, ValorResposta total,
                                    List<SubcategoriaResposta> subcategorias) {

        static CategoriaResposta de(MapaDeGastosServico.LinhaCategoria c) {
            return new CategoriaResposta(
                c.categoriaId(), c.nome(),
                CelulaResposta.de(c.celulas()), ValorResposta.de(c.total()),
                c.subcategorias().stream().map(SubcategoriaResposta::de).toList());
        }
    }

    public record SubcategoriaResposta(UUID subcategoriaId, String nome,
                                       List<CelulaResposta> celulas, ValorResposta total) {

        static SubcategoriaResposta de(MapaDeGastosServico.LinhaSubcategoria s) {
            return new SubcategoriaResposta(
                s.subcategoriaId(), s.nome(),
                CelulaResposta.de(s.celulas()), ValorResposta.de(s.total()));
        }
    }

    public record SaldoResposta(List<CelulaResposta> porMes, ValorResposta total) {

        static SaldoResposta de(MapaDeGastosServico.Saldo s) {
            return new SaldoResposta(CelulaResposta.de(s.porMes()), ValorResposta.de(s.total()));
        }
    }

    public record CelulaResposta(int mes, String realizado, String previsto) {

        static List<CelulaResposta> de(List<MapaDeGastosServico.Celula> celulas) {
            return celulas.stream()
                .map(c -> new CelulaResposta(c.mes(), dinheiro(c.realizado()), dinheiro(c.previsto())))
                .toList();
        }
    }

    public record ValorResposta(String realizado, String previsto) {

        static ValorResposta de(MapaDeGastosServico.Valor v) {
            return new ValorResposta(dinheiro(v.realizado()), dinheiro(v.previsto()));
        }
    }

    public record Referencia(UUID id, String nome) {}
}
