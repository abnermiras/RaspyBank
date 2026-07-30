package com.raspybank.lancamento.servico;

import com.raspybank.lancamento.dominio.Cartao;
import com.raspybank.lancamento.dominio.CartaoEmitido;
import com.raspybank.lancamento.dominio.Categoria;
import com.raspybank.lancamento.dominio.CodigoSistemico;
import com.raspybank.lancamento.dominio.Conta;
import com.raspybank.lancamento.dominio.ContaAmbiente;
import com.raspybank.lancamento.dominio.ContaFormaPagamento;
import com.raspybank.lancamento.dominio.Fatura;
import com.raspybank.lancamento.dominio.FormaPagamento;
import com.raspybank.lancamento.dominio.Lancamento;
import com.raspybank.lancamento.dominio.SituacaoLancamento;
import com.raspybank.lancamento.dominio.Subcategoria;
import com.raspybank.lancamento.dominio.TipoLancamento;
import com.raspybank.lancamento.repositorio.CartaoEmitidoRepositorio;
import com.raspybank.lancamento.repositorio.CartaoRepositorio;
import com.raspybank.lancamento.repositorio.CategoriaRepositorio;
import com.raspybank.lancamento.repositorio.ContaAmbienteRepositorio;
import com.raspybank.lancamento.repositorio.ContaFormaPagamentoRepositorio;
import com.raspybank.lancamento.repositorio.ContaRepositorio;
import com.raspybank.lancamento.repositorio.FaturaRepositorio;
import com.raspybank.lancamento.repositorio.LancamentoRepositorio;
import com.raspybank.lancamento.repositorio.SubcategoriaRepositorio;
import com.raspybank.shared.erro.OperacaoNaoPermitida;
import com.raspybank.shared.erro.RecursoNaoEncontrado;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
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
    private final ContaFormaPagamentoRepositorio formasDePagamento;
    private final SituacaoVencidaServico vencidos;
    private final CartaoRepositorio cartoes;
    private final CartaoEmitidoRepositorio emitidos;
    private final FaturaRepositorio faturas;
    private final CartaoServico cartaoServico;

    public LancamentoServico(LancamentoRepositorio lancamentos,
                             CategoriaRepositorio categorias,
                             SubcategoriaRepositorio subcategorias,
                             ContaRepositorio contas,
                             ContaAmbienteRepositorio vinculos,
                             ContaFormaPagamentoRepositorio formasDePagamento,
                             SituacaoVencidaServico vencidos,
                             CartaoRepositorio cartoes,
                             CartaoEmitidoRepositorio emitidos,
                             FaturaRepositorio faturas,
                             CartaoServico cartaoServico) {
        this.lancamentos = lancamentos;
        this.categorias = categorias;
        this.subcategorias = subcategorias;
        this.contas = contas;
        this.vinculos = vinculos;
        this.formasDePagamento = formasDePagamento;
        this.vencidos = vencidos;
        this.cartoes = cartoes;
        this.emitidos = emitidos;
        this.faturas = faturas;
        this.cartaoServico = cartaoServico;
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

        // Antes de ler: o previsto que venceu vira realizado. Sem isto, o
        // boleto de 05/08 continuaria PREVISTO em 06/08 e o extrato mostraria
        // uma etiqueta que o calendario ja desmentiu.
        vencidos.realizarVencidos(ambienteId, LocalDate.now());

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

        // A tela manda o BANCO e o cartao; o lancamento mora na conta do cartao.
        Compra compra = resolverContaDaCompra(ambienteId, dados);

        // Transferencia so nasce em par, pela porta de TransferenciaServico.
        //
        // Um lancamento avulso em TRANSFERENCIA e exatamente a meia
        // transferencia que a V11 existe para impedir: dinheiro sai de uma conta
        // sem entrar em nenhuma, o par fica nulo, e nada denuncia isso depois —
        // nenhum saldo isolado parece errado.
        //
        // A guarda esta no POST e NAO no PUT de proposito: editar uma perna
        // existente e legitimo (ate propaga para a outra), e o par continua
        // intacto porque a categoria nao muda.
        if (CodigoSistemico.TRANSFERENCIA.name().equals(categoria.getCodigo())) {
            throw new OperacaoNaoPermitida(
                "Transferencia nao se lanca avulsa: ela e um par de lancamentos e"
                    + " precisa de conta de origem e de destino."
                    + " Use POST /api/transferencias.");
        }

        // Pagamento de fatura, pelo mesmo motivo: tambem e um par, e precisa
        // saber QUAL fatura esta pagando. Avulso, ele abateria uma divida sem
        // dizer de qual ciclo — e a fatura nunca sairia de "nao paga".
        if (CodigoSistemico.PAGAMENTO_FATURA.name().equals(categoria.getCodigo())) {
            throw new OperacaoNaoPermitida(
                "Pagamento de fatura nao se lanca avulso: ele e um par de lancamentos"
                    + " e precisa apontar para a fatura."
                    + " Use POST /api/faturas/{id}/pagamentos.");
        }

        TipoLancamento tipo = resolverTipo(categoria, dados.tipoDeclarado());

        Lancamento novo = new Lancamento(
            categoria,
            compra.contaId(),
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
        novo.pagarPor(resolverFormaDePagamento(
            compra.contaId(), dados.formaPagamento(), tipo, categoria));
        novo.compradoCom(compra.emitidoId());

        Cartao cartao = cartoes.findById(compra.contaId()).orElse(null);
        if (cartao == null) {
            if (dados.parcelas() != null && dados.parcelas() > 1) {
                throw new OperacaoNaoPermitida(
                    "So compra no cartao de credito se parcela. Numa conta comum, o"
                        + " dinheiro sai de uma vez — se ele sai em partes, sao"
                        + " lancamentos diferentes.");
            }
            return lancamentos.save(novo);
        }

        return registrarNoCartao(cartao, novo, dados, hoje);
    }

    /**
     * Uma compra no cartao: escolhe a fatura e, se for parcelada, gera o resto.
     *
     * <h4>A data de caixa vira o vencimento da fatura</h4>
     *
     * <p>F14, e e o que faz o mapa de gastos dizer a verdade (B-D54): a compra
     * de 10/08 cuja fatura vence em 15/09 aparece em SETEMBRO, porque e em
     * setembro que o dinheiro sai do bolso. O regime do sistema e de caixa
     * (P-T2), e misturar competencia aqui faria o total do mes deixar de bater
     * com o extrato do banco.</p>
     *
     * <p>A data da COMPRA nao se perde: fica em {@code dataCompetencia}, e e ela
     * que se repete em todas as parcelas (F23).</p>
     */
    private Lancamento registrarNoCartao(Cartao cartao, Lancamento primeiro,
                                         Dados dados, LocalDate hoje) {

        int parcelas = dados.parcelas() == null ? 1 : dados.parcelas();
        if (parcelas < 1 || parcelas > 99) {
            throw new OperacaoNaoPermitida("Parcelas deve estar entre 1 e 99: " + parcelas);
        }

        Fatura base = resolverFaturaDaCompra(cartao, dados, primeiro.getDataCompetencia());

        if (parcelas == 1) {
            primeiro.cobrarNaFatura(base.getId(), base.getVencimento(), hoje);
            return lancamentos.save(primeiro);
        }

        List<Fatura> ciclos = ciclosAPartirDe(cartao, base, parcelas);
        List<BigDecimal> valores = dividirEmParcelas(dados.valor(), parcelas);
        UUID grupo = UUID.randomUUID();

        // A primeira reaproveita o lancamento ja montado; as outras nascem
        // iguais a ela, trocando so o valor e a fatura.
        primeiro.alterarValor(valores.get(0));
        primeiro.cobrarNaFatura(ciclos.get(0).getId(), ciclos.get(0).getVencimento(), hoje);
        primeiro.comoParcela(grupo, 1, parcelas);
        lancamentos.save(primeiro);

        for (int i = 1; i < parcelas; i++) {
            Fatura ciclo = ciclos.get(i);

            Lancamento parcela = new Lancamento(
                exigirCategoria(primeiro.getAmbienteId(), primeiro.getCategoriaId()),
                primeiro.getContaId(),
                primeiro.getTipo(),
                valores.get(i),
                // A data da COMPRA se repete em todas (F23, palavras dele:
                // "a data da compra repete, muda somente a fatura").
                primeiro.getDataCompetencia(),
                ciclo.getVencimento(),
                primeiro.getCriadoPor(),
                hoje);

            parcela.classificarEm(buscarSubcategoria(
                primeiro.getAmbienteId(), primeiro.getSubcategoriaId()));
            parcela.descrever(primeiro.getDescricao());
            parcela.observar(primeiro.getObservacao());
            parcela.atribuirA(primeiro.getResponsavelId());
            parcela.pagarPor(primeiro.getFormaPagamento());
            parcela.compradoCom(primeiro.getCartaoEmitidoId());
            parcela.comoParcela(grupo, i + 1, parcelas);

            // A fatura ANTES do save, e nao depois: com o cartao preenchido e a
            // fatura ainda nula, o INSERT viola ck_lancamento_cartao_exige_fatura.
            // A ordem invertida passou despercebida na V12 — o cartao ainda nao
            // existia na linha — e a constraint da V13 a denunciou no primeiro
            // parcelamento.
            parcela.cobrarNaFatura(ciclo.getId(), ciclo.getVencimento(), hoje);
            lancamentos.save(parcela);
        }

        return primeiro;
    }

    /**
     * Traduz "banco + cartao" na conta onde o lancamento realmente mora.
     *
     * <p>Esta e a inversao de tela que os testes de negocio de 28/07/2026
     * pediram (B-D61). Ninguem pensa "vou gastar na conta do cartao" — pensa
     * "paguei no cartao". Entao a tela manda {@code contaId} = Nubank e
     * {@code cartaoEmitidoId} = o plastico, e e aqui que isso vira a conta do
     * contrato.</p>
     *
     * <p><b>Por que o armazenamento nao mudou junto.</b> Duas coisas que ele
     * pediu dependem de a divida do cartao ser um saldo proprio: pagamento
     * PARCIAL da fatura e pagar a fatura do Nubank com a conta do C6. Se a
     * compra debitasse o banco direto, a fatura nao teria o que pagar.</p>
     *
     * <p>A conferencia de que o cartao pertence AQUELE banco nao e cerimonia:
     * sem ela, escolher Nubank e um cartao do C6 gravaria a compra no C6 em
     * silencio, e o extrato mostraria um banco que a pessoa nao escolheu.</p>
     */
    private Compra resolverContaDaCompra(UUID ambienteId, Dados dados) {
        if (dados.cartaoEmitidoId() == null) {
            return new Compra(dados.contaId(), null);
        }

        if (dados.formaPagamento() != null) {
            throw new OperacaoNaoPermitida(
                "Escolha o cartao OU a forma de pagamento, nao os dois:"
                    + " o cartao ja e a resposta para como foi pago.");
        }

        CartaoEmitido emitido = emitidos.findById(dados.cartaoEmitidoId())
            .orElseThrow(() -> new RecursoNaoEncontrado("Cartao nao encontrado"));

        if (emitido.estaCancelado()) {
            throw new OperacaoNaoPermitida(
                "O cartao '" + emitido.getNomeTitular() + " ****" + emitido.getFinalDoCartao()
                    + "' esta cancelado e nao recebe compra nova.");
        }

        Cartao cartao = cartoes.findById(emitido.getCartaoId())
            .orElseThrow(() -> new RecursoNaoEncontrado("Cartao nao encontrado"));

        exigirContaNoAmbiente(ambienteId, cartao.getContaId());

        // A conferencia do banco vale para o cartao que nasceu aqui, e SO para
        // ele (§4l). No cartao dividido, o banco do contrato e uma conta de outra
        // pessoa: quem recebeu o cartao nao a enxerga, e a pergunta "de qual
        // banco?" nao tem resposta possivel do lado dela.
        //
        // Nao e a checagem afrouxando — e ela deixando de existir num contexto em
        // que nao significa nada. No caminho normal ela continua inteira, e
        // continua impedindo que escolher Nubank e um cartao do C6 grave a compra
        // no C6 em silencio. A conta do proprio cartao tambem passa: e o modelo
        // cru, e e o que a tela de quem recebeu tem para enviar.
        boolean nasceuAqui = vinculos
            .findById(new ContaAmbiente.Chave(cartao.getContaId(), ambienteId))
            .map(ContaAmbiente::isOrigem)
            .orElse(false);

        boolean contaCoerente = cartao.getContaBancoId().equals(dados.contaId())
            || cartao.getContaId().equals(dados.contaId());

        if (nasceuAqui && !contaCoerente) {
            throw new OperacaoNaoPermitida(
                "O cartao '" + cartao.getNome() + "' nao pertence a conta escolhida."
                    + " Escolha o banco a que ele pertence.");
        }

        return new Compra(cartao.getContaId(), emitido.getId());
    }

    /** A conta onde o lancamento mora, e o plastico que fez a compra. */
    private record Compra(UUID contaId, UUID emitidoId) {}

    /**
     * Qual fatura cobra esta compra.
     *
     * <p>Se o corpo disser explicitamente, e essa — desde que esteja ABERTA.
     * Senao, a primeira aberta cujo fechamento ainda nao passou, o que produz a
     * regra que ele enunciou: <i>"fatura fechada, lancamento vai para o
     * proximo"</i>, inclusive quando a data da compra e anterior ao fechamento.</p>
     */
    private Fatura resolverFaturaDaCompra(Cartao cartao, Dados dados, LocalDate dataDaCompra) {
        if (dados.faturaId() != null) {
            Fatura escolhida = faturas.findById(dados.faturaId())
                .filter(f -> f.getCartaoId().equals(cartao.getContaId()))
                .orElseThrow(() -> new RecursoNaoEncontrado("Fatura nao encontrada"));

            exigirFaturaAberta(escolhida);
            return escolhida;
        }

        return faturas.abertaPara(cartao.getContaId(), dataDaCompra)
            .orElseThrow(() -> new OperacaoNaoPermitida(
                "Nao ha fatura aberta neste cartao para receber a compra."
                    + " Reabra a fatura do ciclo ou aguarde a geracao do proximo."));
    }

    /**
     * Os {@code quantidade} ciclos a partir da fatura base, esticando o horizonte
     * se preciso.
     *
     * <p>Uma compra em 24x passa dos doze ciclos que nascem com o cartao (F20),
     * e aqui e o unico lugar que sabe disso. Gerar sob demanda no meio da compra
     * e escrita como efeito colateral, e por isso ela e explicita e comentada em
     * vez de escondida num {@code getOrCreate}.</p>
     */
    private List<Fatura> ciclosAPartirDe(Cartao cartao, Fatura base, int quantidade) {
        YearMonth ultimo = base.getMes().plusMonths(quantidade - 1L);
        cartaoServico.gerarFaturasAte(cartao, ultimo);

        List<Fatura> todas = faturas.findByCartaoIdOrderByVencimento(cartao.getContaId());
        List<Fatura> escolhidos = new ArrayList<>();

        for (Fatura f : todas) {
            if (!f.getMes().isBefore(base.getMes()) && escolhidos.size() < quantidade) {
                escolhidos.add(f);
            }
        }

        if (escolhidos.size() < quantidade) {
            throw new IllegalStateException(
                "Faltaram faturas para o parcelamento: pedidas " + quantidade
                    + ", disponiveis " + escolhidos.size());
        }
        return escolhidos;
    }

    /**
     * Divide o valor em parcelas de centavos inteiros, com o RESIDUO NA PRIMEIRA.
     *
     * <p>F23. Mil reais em tres vezes da 333,34 + 333,33 + 333,33 — e nunca
     * 333,33 tres vezes, que perderia um centavo do total. A conta e feita em
     * centavos inteiros de propósito: dividir {@code BigDecimal} com escala 2
     * acumularia erro exatamente onde ele e mais visivel.</p>
     *
     * <p>Na primeira e nao na ultima porque a primeira e a que a pessoa confere
     * contra a fatura mais proxima — e porque um centavo a mais daqui a dez
     * meses e um centavo que ninguem lembra de onde veio.</p>
     */
    static List<BigDecimal> dividirEmParcelas(BigDecimal total, int parcelas) {
        long centavos = total.movePointRight(2).longValueExact();
        long base = centavos / parcelas;
        long residuo = centavos - (base * parcelas);

        List<BigDecimal> valores = new ArrayList<>(parcelas);
        for (int i = 0; i < parcelas; i++) {
            long valor = base + (i == 0 ? residuo : 0);
            valores.add(BigDecimal.valueOf(valor).movePointLeft(2));
        }
        return valores;
    }

    private void exigirFaturaAberta(Fatura fatura) {
        if (!fatura.estaAberta()) {
            throw new OperacaoNaoPermitida(
                "A fatura de " + fatura.getMes() + " esta fechada e nao recebe"
                    + " lancamento. Reabra a fatura antes, se for o caso.");
        }
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

        // Uma perna de transferencia nao muda de categoria. Sair de
        // TRANSFERENCIA deixaria um par ligado por lancamento_par_id com
        // classificacoes diferentes em cada lado, e o mapa de gastos passaria a
        // contar metade de um movimento que nao e gasto (B-D15).
        if (l.ehPernaDeTransferencia() && !categoria.getId().equals(l.getCategoriaId())) {
            throw new OperacaoNaoPermitida(
                "Este lancamento e uma perna de transferencia e nao muda de categoria."
                    + " Exclua a transferencia e refaca, se ela estiver errada.");
        }

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

        // Sem derivacao de padrao aqui, ao contrario do POST — e a diferenca e
        // proposital. No PUT a tela mostra o campo ja preenchido com o valor
        // atual, entao mandar vazio e um ato: a pessoa esta LIMPANDO. Reaplicar
        // o padrao seria desfazer, no servidor, o que ela acabou de fazer.
        l.pagarPor(conferirFormaAceita(dados.contaId(), dados.formaPagamento(), l.getTipo()));

        // Mover a compra de ciclo — pedido explicitamente: "o usuario pode pegar
        // um lancamento e editar ele e trocar o mes da fatura".
        //
        // A data de caixa vai junto, sempre: ela E o vencimento da fatura (F14),
        // e separa-las deixaria o extrato dizendo um mes e a fatura dizendo
        // outro. A data da COMPRA nao e tocada aqui — ela e outro campo, e
        // tambem pode mudar, so que por conta propria.
        if (dados.faturaId() != null && !dados.faturaId().equals(l.getFaturaId())) {
            Fatura destino = faturas.findById(dados.faturaId())
                .orElseThrow(() -> new RecursoNaoEncontrado("Fatura nao encontrada"));

            if (!destino.getCartaoId().equals(l.getContaId())) {
                throw new OperacaoNaoPermitida(
                    "Essa fatura e de outro cartao. Mover a compra entre cartoes"
                        + " diferentes mudaria de quem e a divida.");
            }

            exigirFaturaAberta(destino);
            l.cobrarNaFatura(destino.getId(), destino.getVencimento(), hoje);
        }

        propagarParaOPar(l);

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
     * A forma do lancamento novo: a informada, ou a padrao da conta para aquele
     * sentido.
     *
     * <p>A regra pedida em 27/07/2026 foi "se a pessoa nao indicar, salva
     * debito". Duas correcoes apareceram ao desenhar, e as duas viraram
     * codigo:</p>
     *
     * <ul>
     *   <li>Debito <b>literal</b> quebraria na carteira, que so aceita
     *       {@code DINHEIRO}: gravaria nela uma forma que a lista da propria
     *       conta recusa. Por isso o padrao e POR CONTA.</li>
     *   <li>Entrada tambem tem "como o dinheiro se moveu" — o salario e
     *       CREDITADO. Por isso sao DOIS padroes por conta, um de cada
     *       sentido.</li>
     * </ul>
     *
     * <h4>A ausencia que sobrou</h4>
     *
     * <p><b>Categoria sistemica nao recebe padrao.</b> Saldo de abertura e um
     * lancamento em {@code AJUSTE} (A13) e transferencia e um par em
     * {@code TRANSFERENCIA}: nenhum dos dois se moveu por pix, boleto ou coisa
     * nenhuma — o dinheiro so trocou de lugar. Sem esta guarda, o saldo inicial
     * de toda conta nova apareceria no extrato como "pago no debito", que e
     * lixo visivel logo na primeira tela que a pessoa abre.</p>
     *
     * <p>E e ela tambem que deixa as duas pernas de uma transferencia nascerem
     * com forma nula sem nenhum caso especial: a categoria delas e sistemica.</p>
     */
    private FormaPagamento resolverFormaDePagamento(UUID contaId, FormaPagamento informada,
                                                    TipoLancamento tipo, Categoria categoria) {
        if (informada != null) {
            return conferirFormaAceita(contaId, informada, tipo);
        }
        if (categoria.isSistemica()) {
            return null;
        }

        Optional<ContaFormaPagamento> padrao = tipo == TipoLancamento.SAIDA
            ? formasDePagamento.findByContaIdAndPadraoSaidaTrue(contaId)
            : formasDePagamento.findByContaIdAndPadraoEntradaTrue(contaId);

        return padrao.map(ContaFormaPagamento::getForma).orElse(null);
    }

    /**
     * Recusa forma impossivel com a frase que a tela consegue exibir.
     *
     * <p>Duas perguntas, e elas sao diferentes: a conta aceita esta forma? e
     * esta forma serve a este sentido? Uma conta corrente que aceita boleto E
     * credito em conta passaria na primeira e falharia na segunda ao tentar
     * "salario pago no boleto".</p>
     *
     * <p>As duas ja sao chaves compostas no banco —
     * {@code fk_lancamento_forma_da_conta} e
     * {@code fk_lancamento_forma_sentido} — e sao elas que mandam. O que se
     * ganha aqui e dizer QUAIS formas cabem, em vez de "uma restricao de
     * integridade falhou".</p>
     */
    private FormaPagamento conferirFormaAceita(UUID contaId, FormaPagamento forma,
                                               TipoLancamento tipo) {
        if (forma == null) {
            return null;
        }

        List<FormaPagamento> daConta = formasDePagamento.findByContaId(contaId).stream()
            .map(ContaFormaPagamento::getForma)
            .toList();

        if (!daConta.contains(forma)) {
            throw new OperacaoNaoPermitida(daConta.isEmpty()
                ? "Esta conta ainda nao tem formas de pagamento cadastradas."
                    + " Cadastre-as na tela de contas antes de usar " + forma + "."
                : "Esta conta nao aceita " + forma + ". Formas aceitas: " + daConta);
        }

        if (!forma.aceita(tipo)) {
            List<FormaPagamento> servem = daConta.stream().filter(f -> f.aceita(tipo)).toList();
            throw new OperacaoNaoPermitida(
                forma + " nao serve para lancamento de " + tipo
                    + ". Nesta conta servem: " + servem);
        }
        return forma;
    }

    /**
     * Mantem as duas pernas da transferencia dizendo a mesma coisa (F16).
     *
     * <p>Propaga o que <b>precisa</b> ser igual nos dois lados: valor, data de
     * caixa e situacao. Se um lado virasse 100 e o outro continuasse 10,
     * noventa reais apareceriam do nada no patrimonio — em silencio, porque
     * nenhum saldo isolado pareceria errado.</p>
     *
     * <p>O que NAO propaga, e de proposito: a conta e a descricao. A conta e o
     * que distingue as pernas, e corrigir "saiu do Nubank, nao do Itau" e uma
     * correcao de um lado so. A descricao pode legitimamente diferir ("saque" de
     * um lado, "dinheiro para a feira" do outro).</p>
     *
     * <p>A outra metade de F16 — apagar uma perna apaga a outra — nao esta
     * aqui: e {@code ON DELETE CASCADE} no banco. Regra de integridade cumprida
     * pelo banco nao tem como ser esquecida por um caminho de codigo novo.</p>
     */
    private void propagarParaOPar(Lancamento l) {
        if (!l.ehPernaDeTransferencia()) {
            return;
        }
        lancamentos.findById(l.getLancamentoParId()).ifPresent(par -> {
            par.alterarValor(l.getValor());
            par.reagendar(l.getDataCaixa(), l.getDataCaixa());
            par.corrigirSituacao(l.getSituacao());
            par.ajustarCompetencia(l.getDataCompetencia());
        });
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
     * O mesmo enriquecimento de {@code comNomes}, aberto para o extrato da
     * fatura (T-06) usar.
     *
     * <p>Publico e nao duplicado: a fatura precisa exatamente dos mesmos lotes
     * de consulta, e duas copias divergiriam na primeira coluna nova.</p>
     */
    @Transactional(readOnly = true)
    public List<Item> detalhar(List<Lancamento> lista) {
        return comNomes(lista);
    }

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

        // Os emitidos das compras de cartao, e os BANCOS a que os cartoes
        // pertencem — em lote, nao um por linha.
        Map<UUID, CartaoEmitido> porEmitido = indexar(
            emitidos.findAllById(idsDe(lista, Lancamento::getCartaoEmitidoId)),
            CartaoEmitido::getId);

        Map<UUID, UUID> bancoDoCartao = new java.util.HashMap<>();
        for (Cartao k : cartoes.findAllById(
                lista.stream().map(Lancamento::getContaId).distinct().toList())) {
            bancoDoCartao.put(k.getContaId(), k.getContaBancoId());
        }

        Map<UUID, Conta> porBanco = indexar(
            contas.findAllById(bancoDoCartao.values().stream().distinct().toList()),
            Conta::getId);

        return lista.stream()
            .map(l -> new Item(
                l,
                // Compra de cartao mostra o BANCO (B-D61); o resto mostra a
                // propria conta.
                bancoDoCartao.containsKey(l.getContaId())
                    ? porBanco.get(bancoDoCartao.get(l.getContaId()))
                    : porConta.get(l.getContaId()),
                porCategoria.get(l.getCategoriaId()),
                l.getSubcategoriaId() == null ? null : porSubcategoria.get(l.getSubcategoriaId()),
                l.getCartaoEmitidoId() == null ? null : porEmitido.get(l.getCartaoEmitidoId())))
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
        SituacaoLancamento situacao,

        /**
         * Como foi pago (V11). Nulo no POST faz cair no padrao da conta; nulo
         * no PUT limpa o campo. Ver {@code resolverFormaDePagamento}.
         */
        FormaPagamento formaPagamento,

        /**
         * So faz sentido em conta que e cartao. Nulo ou 1 = a vista.
         * Ver {@code dividirEmParcelas}: o residuo dos centavos vai na primeira
         * (F23).
         */
        Integer parcelas,

        /**
         * A fatura que cobra a compra. Nulo = o servico escolhe a primeira
         * aberta cujo fechamento ainda nao passou. No PUT, move o lancamento
         * de ciclo — e a fatura de destino precisa estar ABERTA.
         */
        UUID faturaId,

        /**
         * Qual plastico ou virtual fez a compra (V13). Quando presente, o
         * lancamento e REDIRECIONADO para a conta do cartao — a tela manda o
         * banco, e {@code resolverContaDaCompra} traduz.
         */
        UUID cartaoEmitidoId
    ) {}

    /**
     * Um lancamento com o que a T-08 precisa mostrar junto dele.
     *
     * <p>{@code conta} e o que a tela EXIBE, e para uma compra no cartao isso e
     * o BANCO, nao o cartao (B-D61). A pessoa escolheu "Nubank + Black ****4352"
     * ao lancar; o extrato dizer "Black" na coluna de conta seria dois nomes
     * para o mesmo lancamento em duas telas.</p>
     *
     * <p>Onde o lancamento realmente mora continua sendo
     * {@code lancamento.getContaId()} — a conta do cartao, cujo saldo e a
     * divida. As duas coisas coexistem porque respondem perguntas diferentes:
     * "de qual banco vai sair" e "de quem e a divida".</p>
     */
    public record Item(Lancamento lancamento, Conta conta,
                       Categoria categoria, Subcategoria subcategoria,
                       CartaoEmitido cartaoEmitido) {
    }
}
