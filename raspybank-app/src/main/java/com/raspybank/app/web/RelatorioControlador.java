package com.raspybank.app.web;

import com.raspybank.ambiente.servico.AmbienteServico;
import com.raspybank.app.servico.ExtratoCompletoMontador;
import com.raspybank.lancamento.servico.MapaDeGastosServico;
import com.raspybank.shared.contexto.ContextoRequisicao;
import com.raspybank.shared.erro.OperacaoNaoPermitida;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * Os dois relatorios: o mapa de gastos (T-07, {@code docs/api.md} §6) e o
 * extrato completo em {@code .xlsx} (T-10, §6c).
 *
 * <p>Eles dividem o caminho e nao dividem mais nada — e a diferenca esta na
 * primeira linha de cada um. O mapa e uma <b>tela</b>: le o ambiente ativo e
 * responde JSON. O extrato e um <b>arquivo</b>: ignora o ambiente ativo de
 * proposito (B-D117), atravessa todos os ambientes da pessoa e responde bytes.
 * Quem for acrescentar um terceiro endpoint aqui decide primeiro de qual dos
 * dois lados ele esta.</p>
 *
 * <p>O mapa e a tela que motivou o projeto: "um quadro com os gastos somados por
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

    private static final Logger log = LoggerFactory.getLogger(RelatorioControlador.class);

    /**
     * O teto da faixa do extrato, em meses de calendario (B-D116).
     *
     * <p>Ele e o que torna a entrega sincrona defensavel: limita o pior caso a
     * milhares de linhas. Mexer neste numero e mexer em B-D116, e a frase de
     * recusa cita "12 meses" por extenso — as duas coisas andam juntas.</p>
     */
    private static final int MESES_DE_TETO = 12;

    private static final String TIPO_XLSX =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final MapaDeGastosServico mapa;
    private final AmbienteServico ambientes;
    private final ExtratoCompletoMontador extratos;

    public RelatorioControlador(MapaDeGastosServico mapa, AmbienteServico ambientes,
                                ExtratoCompletoMontador extratos) {
        this.mapa = mapa;
        this.ambientes = ambientes;
        this.extratos = extratos;
    }

    /**
     * @param ano ano civil (B-D11). Ausente, o corrente — a tela abre no ano
     *            em que a pessoa esta, que e o que ela quer ver em 11 dos 12
     *            meses.
     */
    @GetMapping("/mapa-de-gastos")
    public MapaResposta mapaDeGastos(@RequestParam(required = false) Integer ano,
                                     @RequestParam(required = false) FiltroDeConta contas) {

        UUID ambienteId = ambienteAtivo();
        int anoAlvo = ano != null ? ano : LocalDate.now().getYear();

        return MapaResposta.de(
            mapa.montar(ambienteId, anoAlvo, (contas == null ? FiltroDeConta.TODAS : contas).soCartao()),
            resumoDoAmbiente(ambienteId));
    }

    // =========================================================================
    // O extrato completo em .xlsx — a T-10 (B-D116 a B-D119)
    // =========================================================================

    /**
     * O extrato completo, em {@code .xlsx} — o unico endpoint binario do
     * projeto, e a unica leitura que <b>atravessa ambientes</b>.
     *
     * <h3>B-D117, e por que ele nao contradiz "o ambiente e implicito"</h3>
     *
     * <p>Nenhum parametro de ambiente entra aqui — e tambem nao se le o
     * ambiente ativo da sessao, como {@code mapaDeGastos} faz. Nao e furo na
     * regra: a regra diz que quem escolhe o ambiente e o token, nunca o
     * cliente, e continua valendo. O arquivo simplesmente nao e uma tela; ele e
     * o retrato da pessoa, e a separacao das vidas se mantem <b>dentro</b> dele,
     * uma aba por ambiente. Quem decide o que entra e {@code app_usuario_id()}
     * no banco (B-D117).</p>
     *
     * <h3>A ordem das linhas deste metodo e o contrato</h3>
     *
     * <p>Valida-se <b>tudo</b> antes de tocar na resposta. Passado o primeiro
     * byte, a resposta esta comprometida: o status ja foi para o fio, e
     * {@code setStatus(400)} vira chamada sem efeito — o cliente receberia um
     * {@code 200} com um {@code .xlsx} truncado, que o navegador salva como se
     * fosse um arquivo bom. E o modo de falha proprio de um endpoint binario, e
     * a defesa contra ele e trivial desde que ninguem inverta a ordem: primeiro
     * as datas, depois a faixa, e so entao {@code getOutputStream()}.</p>
     *
     * <h3>O nome do arquivo diz a faixa</h3>
     *
     * <p>{@code extrato-2026-01-01-a-2026-12-31.xlsx}. E a unica coisa que
     * sobrevive ao download: tres meses depois, na pasta de downloads, e o nome
     * que diz o que aquele arquivo cobre. Sai so em {@code filename=} porque e
     * ASCII por construcao — nasce de duas datas ISO, e nome de ambiente nao
     * entra nele. Se um dia entrar, acrescente a forma {@code filename*=UTF-8''}
     * ao lado, que o cliente ja prefere quando as duas estao presentes.</p>
     *
     * @param inicio primeiro dia da faixa, {@code AAAA-MM-DD}, inclusive.
     *               Ausente, {@code fim} menos 12 meses
     * @param fim    ultimo dia da faixa, inclusive. Ausente, hoje — de modo que
     *               sem parametro nenhum o padrao sao os ultimos 12 meses, no
     *               mesmo espirito do {@code ano} ausente no mapa de gastos
     */
    @GetMapping("/extrato.xlsx")
    public void extratoCompleto(@RequestParam(required = false) String inicio,
                                @RequestParam(required = false) String fim,
                                HttpServletResponse resposta) throws IOException {

        // ---- 1. Nada de resposta ainda: so entrada. -------------------------
        LocalDate ate = dia("fim", fim, LocalDate.now());
        LocalDate de = dia("inicio", inicio, ate.minusMonths(MESES_DE_TETO));
        conferirFaixa(de, ate);

        // ---- 2. Agora sim, os cabecalhos e os bytes. ------------------------
        resposta.setContentType(TIPO_XLSX);
        resposta.setHeader(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + nomeDoArquivo(de, ate) + "\"");

        try {
            extratos.montar(de, ate, resposta.getOutputStream());
        } catch (IOException | RuntimeException e) {
            if (resposta.isCommitted()) {
                // Tarde demais para virar JSON de erro: o 200 e os primeiros
                // bytes ja sairam. Deixar a excecao subir e o menos pior — o
                // container derruba a conexao sem fechar o "chunked", e o
                // cliente ve falha de rede em vez de um arquivo aparentemente
                // completo. O .xlsx e um zip: truncado, nenhuma leitora o abre,
                // entao ninguem confere a vida financeira contra meio arquivo.
                log.error("Extrato completo falhou com a resposta ja comprometida ({} a {})",
                    de, ate, e);
            } else {
                // Ainda da: limpa o Content-Type binario e o anexo, para o
                // TratadorGlobalDeErros escrever o JSON de erro numa resposta
                // limpa. Sem o reset, o navegador baixaria o JSON como .xlsx.
                resposta.reset();
            }
            throw e;
        }
    }

    // =========================================================================

    /**
     * Uma data da query, ou o padrao quando ela nao veio.
     *
     * <p>{@code String} e nao {@code LocalDate} de proposito: com o tipo
     * declarado, o Spring rejeita {@code ?inicio=ontem} com
     * {@code MethodArgumentTypeMismatchException}, que hoje cai na captura
     * geral e vira <b>500</b>. Erro de quem pediu respondido como erro nosso e
     * exatamente o defeito do I-12, e aqui ele ainda apagaria o nome do
     * parametro culpado.</p>
     */
    private static LocalDate dia(String campo, String valor, LocalDate padrao) {

        if (valor == null || valor.isBlank()) {
            return padrao;
        }
        try {
            return LocalDate.parse(valor.trim());
        } catch (DateTimeParseException e) {
            throw new EntradaInvalida(
                "Data inválida em \"" + campo + "\". Use o formato AAAA-MM-DD, como 2026-01-31.",
                campo);
        }
    }

    /**
     * As duas recusas da faixa — e as duas acontecem antes do primeiro byte.
     *
     * <h3>Doze meses de calendario, nunca 365 dias</h3>
     *
     * <p>{@code inicio.plusMonths(12)} e o teto, e o mes de calendario e o que
     * a pessoa quis dizer: quem pede "de 01/03/2023 a 29/02/2024" pediu doze
     * meses, e sao 366 dias. Uma conta em dias recusaria esse pedido em ano
     * bissexto e aceitaria em ano comum — a mesma faixa, duas respostas,
     * dependendo do calendario. {@code plusMonths} tambem resolve sozinho o 31
     * que nao existe no mes de destino (31/01 + 1 mes = 28/02).</p>
     *
     * <p>O teto e inclusive: doze meses exatos passam. Ele nao e capricho — e o
     * que sustenta B-D116, porque limita o pior caso a milhares de linhas e e
     * por isso que este endpoint pode ser sincrono.</p>
     *
     * <h3>A frase diz o limite</h3>
     *
     * <p>"Periodo invalido" mandaria a pessoa tentar de novo as cegas. A frase
     * daqui e a mesma que a T-10 usa, palavra por palavra, para nao existirem
     * duas redacoes do mesmo erro.</p>
     */
    private static void conferirFaixa(LocalDate inicio, LocalDate fim) {

        if (fim.isBefore(inicio)) {
            throw new EntradaInvalida("A data final não pode ser anterior à inicial.", "inicio", "fim");
        }
        if (fim.isAfter(inicio.plusMonths(MESES_DE_TETO))) {
            throw new EntradaInvalida(
                "O período não pode passar de 12 meses. Ajuste a data inicial ou a final e tente de novo.",
                "inicio", "fim");
        }
    }

    /** {@code extrato-2026-01-01-a-2026-12-31.xlsx} — ISO, que ordena sozinho na pasta. */
    private static String nomeDoArquivo(LocalDate inicio, LocalDate fim) {
        return "extrato-" + inicio + "-a-" + fim + ".xlsx";
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

    /**
     * O recorte de conta do mapa (B-D54).
     *
     * <p>Filtro no topo, e nao um terceiro numero por celula: B-D10 separou
     * realizado de previsto com esforco, e um terceiro em doze colunas viraria
     * sopa. Trocar a lente responde "quanto do meu mercado foi no cartao" sem
     * poluir nada.</p>
     */
    public enum FiltroDeConta {

        TODAS(null),

        /** So o que passou por cartao — todo lancamento com fatura. */
        CARTAO(Boolean.TRUE),

        SEM_CARTAO(Boolean.FALSE);

        private final Boolean soCartao;

        FiltroDeConta(Boolean soCartao) {
            this.soCartao = soCartao;
        }

        Boolean soCartao() {
            return soCartao;
        }
    }

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
