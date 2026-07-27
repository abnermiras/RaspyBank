package com.raspybank.lancamento.servico;

import com.raspybank.lancamento.dominio.Categoria;
import com.raspybank.lancamento.dominio.Conta;
import com.raspybank.lancamento.dominio.ContaAmbiente;
import com.raspybank.lancamento.dominio.Lancamento;
import com.raspybank.lancamento.dominio.SituacaoLancamento;
import com.raspybank.lancamento.dominio.Subcategoria;
import com.raspybank.lancamento.dominio.TipoLancamento;
import com.raspybank.lancamento.repositorio.CategoriaRepositorio;
import com.raspybank.lancamento.repositorio.ContaAmbienteRepositorio;
import com.raspybank.lancamento.repositorio.ContaRepositorio;
import com.raspybank.lancamento.repositorio.LancamentoRepositorio;
import com.raspybank.lancamento.repositorio.SubcategoriaRepositorio;
import com.raspybank.shared.erro.OperacaoNaoPermitida;
import com.raspybank.shared.erro.RecursoNaoEncontrado;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Casos de uso do lancamento (T-08).
 *
 * <h3>O que este servico NAO valida</h3>
 *
 * <p>Quase nada, e e o ponto. As tres regras mais importantes ja sao
 * impossibilidades estruturais no banco (V10): a conta tem que estar visivel
 * no ambiente (B-D2), a categoria tem que ser do mesmo ambiente (F9), a
 * subcategoria tem que pertencer a categoria (F11). O valor positivo e CHECK.
 * O sentido aceito pela categoria e a entidade quem confere.</p>
 *
 * <p>O que sobra aqui e <b>tradução</b>: transformar id em objeto, derivar o
 * que o formulario nao pergunta, e trocar um erro de constraint por uma frase
 * que a tela consegue exibir. Um servico que revalida o que o banco ja garante
 * cria uma segunda verdade — e a segunda verdade e sempre a que fica para
 * tras.</p>
 *
 * <h3>As duas derivacoes</h3>
 *
 * <ul>
 *   <li><b>Situacao</b> vem da data de caixa (B-D9). Nao ha campo no
 *       formulario.</li>
 *   <li><b>Tipo</b> vem da categoria (F12). So quando ela e {@code AMBOS} — as
 *       tres sistemicas — o corpo precisa declarar.</li>
 * </ul>
 */
@Service
public class LancamentoServico {

    private final LancamentoRepositorio lancamentos;
    private final CategoriaRepositorio categorias;
    private final SubcategoriaRepositorio subcategorias;
    private final ContaRepositorio contas;
    private final ContaAmbienteRepositorio vinculos;

    public LancamentoServico(LancamentoRepositorio lancamentos,
                             CategoriaRepositorio categorias,
                             SubcategoriaRepositorio subcategorias,
                             ContaRepositorio contas,
                             ContaAmbienteRepositorio vinculos) {
        this.lancamentos = lancamentos;
        this.categorias = categorias;
        this.subcategorias = subcategorias;
        this.contas = contas;
        this.vinculos = vinculos;
    }

    // =========================================================================
    // Leitura
    // =========================================================================

    /**
     * O extrato de um mes, com conta e classificacao ja resolvidas.
     *
     * <p>Os nomes sao buscados em lote — uma consulta para todas as contas do
     * resultado, uma para todas as categorias, uma para todas as
     * subcategorias. Resolver dentro do laco daria o mesmo JSON e uma consulta
     * por linha.</p>
     *
     * <p><b>Nada de nome congelado</b> (B-D4 / R8): o nome exibido vem sempre
     * da categoria atual. Renomear "Mercado" para "Mercado e feira" reescreve
     * o passado inteiro, e isso e o comportamento desejado — o lancamento
     * aponta para uma categoria, nao para um texto.</p>
     */
    @Transactional(readOnly = true)
    public List<Item> listar(UUID ambienteId, YearMonth mes, UUID contaId,
                             UUID categoriaId, SituacaoLancamento situacao) {

        List<Lancamento> encontrados = lancamentos.buscar(
            ambienteId, mes.atDay(1), mes.atEndOfMonth(), contaId, categoriaId, situacao);

        return comNomes(encontrados);
    }

    @Transactional(readOnly = true)
    public Item buscar(UUID ambienteId, UUID id) {
        return comNomes(List.of(exigir(ambienteId, id))).get(0);
    }

    // =========================================================================
    // Escrita
    // =========================================================================

    /**
     * Registra um lancamento novo.
     *
     * @param tipoDeclarado obrigatorio apenas quando a categoria e {@code AMBOS}
     * @param hoje          data de referencia da derivacao de situacao (B-D9)
     */
    @Transactional
    public Lancamento registrar(UUID ambienteId, UUID usuarioId, Dados dados, LocalDate hoje) {

        Categoria categoria = exigirCategoria(ambienteId, dados.categoriaId());
        exigirContaNoAmbiente(ambienteId, dados.contaId());

        TipoLancamento tipo = resolverTipo(categoria, dados.tipoDeclarado());

        Lancamento novo = new Lancamento(
            categoria,
            dados.contaId(),
            tipo,
            dados.valor(),
            // dataCompetencia ausente copia dataCaixa (F14): no gasto do dia a
            // dia as duas coincidem, e obrigar a digitar duas vezes a mesma
            // data e o tipo de atrito que faz a pessoa parar de lancar.
            Optional.ofNullable(dados.dataCompetencia()).orElse(dados.dataCaixa()),
            dados.dataCaixa(),
            usuarioId,
            hoje);

        novo.classificarEm(buscarSubcategoria(ambienteId, dados.subcategoriaId()));
        novo.descrever(dados.descricao());
        novo.observar(dados.observacao());
        novo.atribuirA(dados.responsavelId());

        return lancamentos.save(novo);
    }

    /**
     * Substitui o conteudo de um lancamento.
     *
     * <p>A situacao volta a derivar da data, <b>a menos que</b> o corpo a
     * declare. E a razao de B-D9 nao ser gatilho: corrigir "o boleto de amanha
     * ja foi debitado" e legitimo, e uma regra imposta pelo banco nao teria
     * como abrir essa excecao.</p>
     */
    @Transactional
    public Lancamento atualizar(UUID ambienteId, UUID id, Dados dados, LocalDate hoje) {

        Lancamento l = exigir(ambienteId, id);
        Categoria categoria = exigirCategoria(ambienteId, dados.categoriaId());
        exigirContaNoAmbiente(ambienteId, dados.contaId());

        // Reclassificar antes de tudo: ele zera a subcategoria antiga, que
        // pertencia a categoria anterior e nao sobreviveria a troca.
        if (!categoria.getId().equals(l.getCategoriaId())) {
            l.reclassificar(categoria);
        }

        l.moverPara(dados.contaId());
        l.alterarValor(dados.valor());
        l.ajustarCompetencia(
            Optional.ofNullable(dados.dataCompetencia()).orElse(dados.dataCaixa()));
        l.reagendar(dados.dataCaixa(), hoje);

        if (dados.situacao() != null) {
            l.corrigirSituacao(dados.situacao());
        }

        l.classificarEm(buscarSubcategoria(ambienteId, dados.subcategoriaId()));
        l.descrever(dados.descricao());
        l.observar(dados.observacao());
        l.atribuirA(dados.responsavelId());

        return l;
    }

    /**
     * Exclui de verdade — o lancamento e a unica entidade do modelo com
     * exclusao fisica (F16).
     *
     * <p>Pode, porque nada aponta para ele: apagar um lancamento nao deixa
     * nenhuma outra linha orfa, e o saldo simplesmente volta a ser a soma do
     * que restou. Apagar uma conta ou uma categoria apagaria o passado de
     * outras linhas — por isso aquelas encerram e arquivam.</p>
     *
     * <p>O rastro nao se perde: o gatilho {@code tg_lancamento_auditoria}
     * grava a linha inteira em {@code registro_auditoria} antes de ela sumir,
     * com autor e canal (F26 / B-D6).</p>
     */
    @Transactional
    public void excluir(UUID ambienteId, UUID id) {
        lancamentos.delete(exigir(ambienteId, id));
    }

    // =========================================================================
    // Resolucao e guardas
    // =========================================================================

    private TipoLancamento resolverTipo(Categoria categoria, TipoLancamento declarado) {
        Optional<TipoLancamento> imposto = categoria.getTipo().sentidoUnico();

        if (imposto.isPresent()) {
            // Declarar o oposto do que a categoria impoe nao e ignorado em
            // silencio: seria gravar algo diferente do que a pessoa pediu.
            if (declarado != null && declarado != imposto.get()) {
                throw new OperacaoNaoPermitida(
                    "Categoria '" + categoria.getNome() + "' e do tipo "
                        + categoria.getTipo() + " e nao aceita lancamento de " + declarado);
            }
            return imposto.get();
        }

        if (declarado == null) {
            throw new OperacaoNaoPermitida(
                "Categoria '" + categoria.getNome() + "' aceita os dois sentidos:"
                    + " informe tipo ENTRADA ou SAIDA");
        }
        return declarado;
    }

    /**
     * Confere o vinculo de B-D2 antes de ir ao banco.
     *
     * <p>A chave composta {@code (ambiente_id, conta_id) -> conta_ambiente} ja
     * recusaria a linha. O que se ganha aqui e a <b>frase</b>: a constraint
     * diria apenas que uma restricao falhou, e a tela nao teria o que
     * mostrar.</p>
     */
    private void exigirContaNoAmbiente(UUID ambienteId, UUID contaId) {
        if (vinculos.findById(new ContaAmbiente.Chave(contaId, ambienteId)).isEmpty()) {
            throw new OperacaoNaoPermitida(
                "Conta nao pertence ao ambiente ativo. Compartilhe a conta com este"
                    + " ambiente ou troque de ambiente antes de lancar.");
        }
    }

    private Categoria exigirCategoria(UUID ambienteId, UUID categoriaId) {
        return categorias.findById(categoriaId)
            .filter(c -> c.getAmbienteId().equals(ambienteId))
            .orElseThrow(() -> new RecursoNaoEncontrado("Categoria nao encontrada"));
    }

    private Subcategoria buscarSubcategoria(UUID ambienteId, UUID subcategoriaId) {
        if (subcategoriaId == null) {
            return null;
        }
        return subcategorias.findById(subcategoriaId)
            .filter(s -> s.getAmbienteId().equals(ambienteId))
            .orElseThrow(() -> new RecursoNaoEncontrado("Subcategoria nao encontrada"));
    }

    /** Mesmo recorte de B-D21: id de outro ambiente e 404. */
    private Lancamento exigir(UUID ambienteId, UUID id) {
        return lancamentos.findById(id)
            .filter(l -> l.getAmbienteId().equals(ambienteId))
            .orElseThrow(() -> new RecursoNaoEncontrado("Lancamento nao encontrado"));
    }

    // =========================================================================

    /**
     * Resolve conta, categoria e subcategoria da lista inteira em tres
     * consultas — nao tres por linha.
     */
    private List<Item> comNomes(List<Lancamento> lista) {
        if (lista.isEmpty()) {
            return List.of();
        }

        Map<UUID, Conta> porConta =
            indexar(contas.findAllById(idsDe(lista, Lancamento::getContaId)), Conta::getId);

        Map<UUID, Categoria> porCategoria =
            indexar(categorias.findAllById(idsDe(lista, Lancamento::getCategoriaId)),
                    Categoria::getId);

        Map<UUID, Subcategoria> porSubcategoria =
            indexar(subcategorias.findAllById(idsDe(lista, Lancamento::getSubcategoriaId)),
                    Subcategoria::getId);

        return lista.stream()
            .map(l -> new Item(
                l,
                porConta.get(l.getContaId()),
                porCategoria.get(l.getCategoriaId()),
                l.getSubcategoriaId() == null ? null : porSubcategoria.get(l.getSubcategoriaId())))
            .toList();
    }

    /** Ids distintos, sem nulos — subcategoria e opcional (F11). */
    private static Set<UUID> idsDe(List<Lancamento> lista, Function<Lancamento, UUID> campo) {
        return lista.stream()
            .map(campo)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static <T> Map<UUID, T> indexar(List<T> itens, Function<T, UUID> chave) {
        return itens.stream().collect(Collectors.toMap(chave, Function.identity()));
    }

    /**
     * O que o corpo do POST e do PUT carregam.
     *
     * <p>Um record em vez de doze parametros: com quatro UUIDs seguidos na
     * assinatura, trocar dois de lugar compila e grava o lancamento na conta
     * errada.</p>
     */
    public record Dados(
        UUID contaId,
        UUID categoriaId,
        UUID subcategoriaId,
        TipoLancamento tipoDeclarado,
        BigDecimal valor,
        LocalDate dataCaixa,
        LocalDate dataCompetencia,
        String descricao,
        String observacao,
        UUID responsavelId,
        SituacaoLancamento situacao
    ) {}

    /** Um lancamento com o que a T-08 precisa mostrar junto dele. */
    public record Item(Lancamento lancamento, Conta conta,
                       Categoria categoria, Subcategoria subcategoria) {
    }
}
