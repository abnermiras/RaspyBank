import { useCallback, useEffect, useMemo, useState } from 'react'
import { lerErro } from '../api/cliente.js'
import {
  categorias as apiCategorias,
  contas as apiContas,
  lancamentos as apiLancamentos,
  transferencias as apiTransferencias,
} from '../api/recursos.js'
import Aviso from '../componentes/Aviso.jsx'
import { useCarregar } from '../ganchos/useCarregar.js'
import { data, dinheiro, hojeISO, mesDe, mesPorExtenso } from '../util/formato.js'
import {
  carregarFormasDePagamento,
  formasDaContaNoSentido,
  rotuloDaForma,
} from '../util/formasPagamento.js'

// =============================================================================
// T-08 — Lançamentos
// =============================================================================
// Duas derivações moram no servidor e a tela precisa NÃO duplicá-las:
//
//   - a SITUAÇÃO sai da data (futuro = previsto). A tela mostra o que vai
//     acontecer enquanto a pessoa escolhe a data, mas não manda o campo — só
//     na edição, onde corrigir é legítimo.
//   - o TIPO sai da categoria. O campo só aparece quando a categoria é AMBOS,
//     que é o único caso em que o servidor não consegue decidir sozinho.
//
// Reimplementar essas regras aqui criaria uma segunda fonte da verdade, e a
// segunda é sempre a que fica desatualizada.
//
// A FORMA DE PAGAMENTO (V11) segue a mesma disciplina. As opções são o
// cruzamento de DUAS listas que vêm do servidor: as formas que aquela conta
// aceita, e as que servem ao sentido do lançamento. A tela não decide nenhuma
// das duas — só mostra, para a pessoa ver o que vai ser gravado antes de gravar.
//
// TRANSFERÊNCIA é o único caminho desta tela que NÃO cria um lançamento: cria
// dois, ligados, numa transação só. A primeira perna sozinha já seria um saldo
// errado, e é por isso que ela tem endpoint próprio em vez de dois POSTs.
// =============================================================================

const SITUACOES = [
  { valor: '', rotulo: 'Todas' },
  { valor: 'REALIZADO', rotulo: 'Realizadas' },
  { valor: 'PREVISTO', rotulo: 'Previstas' },
]

export default function Lancamentos() {
  const [mes, setMes] = useState(() => mesDe(new Date()))
  const [filtros, setFiltros] = useState({ contaId: '', categoriaId: '', situacao: '' })
  const [aviso, setAviso] = useState(null)
  const [ocupado, setOcupado] = useState(false)
  const [editando, setEditando] = useState(null) // lançamento inteiro, ou 'novo'
  const [transferindo, setTransferindo] = useState(false)

  const [formasConhecidas, setFormasConhecidas] = useState([])
  useEffect(() => {
    carregarFormasDePagamento().then(setFormasConhecidas)
  }, [])

  const buscar = useCallback(
    () => apiLancamentos.listar({ mes, ...filtros }),
    [mes, filtros],
  )
  const { dados, carregando, erro, recarregar } = useCarregar(buscar)

  // Os seletores precisam de contas e categorias ativas. Carregados uma vez.
  const [apoio, setApoio] = useState({ contas: [], categorias: [] })
  useEffect(() => {
    Promise.all([apiContas.listar(false), apiCategorias.listar(false)]).then(
      ([respostaContas, respostaCategorias]) => {
        setApoio({
          contas: respostaContas.ok ? respostaContas.corpo.contas : [],
          categorias: respostaCategorias.ok ? respostaCategorias.corpo.categorias : [],
        })
      },
    )
  }, [])

  async function executar(acao, mensagemDeSucesso) {
    setOcupado(true)
    setAviso(null)
    try {
      const resposta = await acao()
      if (!resposta.ok) {
        setAviso({ texto: lerErro(resposta).mensagem, sucesso: false })
        return false
      }
      if (mensagemDeSucesso) setAviso({ texto: mensagemDeSucesso, sucesso: true })
      await recarregar()
      return true
    } catch {
      setAviso({ texto: 'Servidor indisponível.', sucesso: false })
      return false
    } finally {
      setOcupado(false)
    }
  }

  function andarMes(passo) {
    const [ano, numeroDoMes] = mes.split('-').map(Number)
    setMes(mesDe(new Date(ano, numeroDoMes - 1 + passo, 1)))
  }

  const lista = dados?.lancamentos ?? []
  const totais = useMemo(() => somar(lista), [lista])

  // Transferência sai do seletor do formulário, e SÓ dele.
  //
  // Ela migrou inteira para o botão "Transferir", que cria as duas pernas numa
  // transação só. Escolhê-la aqui criaria meia transferência: dinheiro saindo
  // de uma conta sem entrar em nenhuma, com o par nulo — exatamente o estado
  // que o endpoint de transferência existe para impedir. O servidor recusa com
  // 403 mesmo que alguém tente por fora, mas oferecer a opção e depois recusar
  // é pior do que não oferecer.
  //
  // As outras duas sistêmicas FICAM. "Ajuste de saldo" é caminho legítimo e a
  // mensagem de encerrar conta aponta para ele; "Não classificado" é o destino
  // do bot do Telegram e de quem não quer classificar agora.
  const categoriasLancaveis = useMemo(
    () => apoio.categorias.filter((c) => c.codigo !== 'TRANSFERENCIA'),
    [apoio.categorias],
  )

  return (
    <section className="painel">
      <header className="cabecalho-painel">
        <h2>Lançamentos</h2>
        <div className="navegador-mes">
          <button type="button" className="botao-texto" onClick={() => andarMes(-1)}>‹</button>
          <span className="mes-atual">{mesPorExtenso(mes)}</span>
          <button type="button" className="botao-texto" onClick={() => andarMes(1)}>›</button>
        </div>
      </header>

      <div className="filtros">
        <select
          value={filtros.contaId}
          onChange={(e) => setFiltros({ ...filtros, contaId: e.target.value })}
        >
          <option value="">Todas as contas</option>
          {apoio.contas.map((c) => <option key={c.id} value={c.id}>{c.nome}</option>)}
        </select>

        <select
          value={filtros.categoriaId}
          onChange={(e) => setFiltros({ ...filtros, categoriaId: e.target.value })}
        >
          <option value="">Todas as categorias</option>
          {apoio.categorias.map((c) => <option key={c.id} value={c.id}>{c.nome}</option>)}
        </select>

        <select
          value={filtros.situacao}
          onChange={(e) => setFiltros({ ...filtros, situacao: e.target.value })}
        >
          {SITUACOES.map((s) => <option key={s.valor} value={s.valor}>{s.rotulo}</option>)}
        </select>

        <button
          type="button" className="botao-principal botao-pequeno"
          onClick={() => setEditando('novo')}
          disabled={apoio.contas.length === 0 || apoio.categorias.length === 0}
          title={
            apoio.contas.length === 0
              ? 'Crie uma conta antes — um lançamento precisa de onde sair ou entrar.'
              : undefined
          }
        >
          Novo lançamento
        </button>

        <button
          type="button" className="botao-texto"
          onClick={() => setTransferindo(true)}
          disabled={apoio.contas.length < 2}
          title={
            apoio.contas.length < 2
              ? 'Transferência precisa de duas contas: uma de origem e uma de destino.'
              : undefined
          }
        >
          Transferir
        </button>
      </div>

      {transferindo && (
        <FormularioDeTransferencia
          contas={apoio.contas}
          ocupado={ocupado}
          aoCancelar={() => setTransferindo(false)}
          aoGravar={async (corpo) => {
            const deu = await executar(
              () => apiTransferencias.criar(corpo),
              'Transferência registrada nas duas contas.',
            )
            if (deu) setTransferindo(false)
          }}
        />
      )}

      {editando && (
        <FormularioDeLancamento
          lancamento={editando === 'novo' ? null : editando}
          apoio={apoio}
          categoriasLancaveis={categoriasLancaveis}
          formasConhecidas={formasConhecidas}
          ocupado={ocupado}
          aoCancelar={() => setEditando(null)}
          aoGravar={async (corpo) => {
            const deu = await executar(
              () =>
                editando === 'novo'
                  ? apiLancamentos.criar(corpo)
                  : apiLancamentos.alterar(editando.id, corpo),
              editando === 'novo' ? 'Lançamento registrado.' : 'Lançamento atualizado.',
            )
            if (deu) setEditando(null)
          }}
        />
      )}

      <Aviso aviso={aviso} />
      {erro && <p className="aviso" role="alert">{erro}</p>}
      {carregando && <p className="carregando">Carregando…</p>}

      {!carregando && lista.length === 0 && (
        <p className="texto-vazio">Nenhum lançamento em {mesPorExtenso(mes)}.</p>
      )}

      {lista.length > 0 && (
        <>
          <table className="tabela-lancamentos">
            <thead>
              <tr>
                <th>Data</th><th>Descrição</th><th>Categoria</th><th>Conta</th>
                <th>Pago com</th><th className="numerico">Valor</th><th />
              </tr>
            </thead>
            <tbody>
              {lista.map((l) => (
                <tr key={l.id} className={l.situacao === 'PREVISTO' ? 'previsto' : undefined}>
                  <td>{data(l.dataCaixa)}</td>
                  <td>
                    {l.descricao || <span className="texto-fraco">sem descrição</span>}
                    {l.situacao === 'PREVISTO' && (
                      <span className="etiqueta etiqueta-fraca" title="Ainda não aconteceu">
                        previsto
                      </span>
                    )}
                  </td>
                  <td>
                    {l.categoria?.nome}
                    {l.subcategoria && (
                      <span className="texto-fraco"> › {l.subcategoria.nome}</span>
                    )}
                  </td>
                  <td>{l.conta?.nome}</td>
                  <td>
                    {l.formaPagamento
                      ? rotuloDaForma(formasConhecidas, l.formaPagamento)
                      : <span className="texto-fraco">—</span>}
                    {l.lancamentoParId && (
                      <span
                        className="etiqueta etiqueta-fraca"
                        title="Uma das duas pernas de uma transferência. Excluir esta exclui a outra."
                      >
                        transferência
                      </span>
                    )}
                  </td>
                  <td className={`numerico ${l.tipo === 'ENTRADA' ? 'entrada' : 'saida'}`}>
                    {l.tipo === 'ENTRADA' ? '+' : '−'} {dinheiro(l.valor)}
                  </td>
                  <td className="acoes-linha">
                    <button
                      type="button" className="botao-texto"
                      onClick={() => setEditando(l)} disabled={ocupado}
                    >
                      Editar
                    </button>
                    <button
                      type="button" className="botao-texto perigo" disabled={ocupado}
                      onClick={() => {
                        // Exclusão é física, mas auditada (F16). O aviso existe
                        // porque, ao contrário de categoria e conta, aqui não há
                        // volta pela tela.
                        if (confirm(`Excluir "${l.descricao || 'lançamento'}"? A exclusão fica registrada na auditoria, mas não dá para desfazer por aqui.`)) {
                          executar(() => apiLancamentos.excluir(l.id), 'Lançamento excluído.')
                        }
                      }}
                    >
                      Excluir
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          <footer className="resumo-mes">
            <span className="entrada">Entradas {dinheiro(totais.entradas)}</span>
            <span className="saida">Saídas {dinheiro(totais.saidas)}</span>
            <strong>Saldo do mês {dinheiro(totais.saldo)}</strong>
          </footer>
        </>
      )}
    </section>
  )
}

/**
 * Soma em centavos, com inteiro. Somar "380.00" como float acumularia erro de
 * ponto flutuante justamente onde ele é mais visível: no total que a pessoa
 * confere contra o extrato do banco.
 */
function somar(lista) {
  const centavos = (v) => Math.round(Number(v) * 100)
  let entradas = 0
  let saidas = 0
  for (const l of lista) {
    if (l.tipo === 'ENTRADA') entradas += centavos(l.valor)
    else saidas += centavos(l.valor)
  }
  return {
    entradas: (entradas / 100).toFixed(2),
    saidas: (saidas / 100).toFixed(2),
    saldo: ((entradas - saidas) / 100).toFixed(2),
  }
}

// ----------------------------------------------------------------------------

function FormularioDeLancamento({ lancamento, apoio, categoriasLancaveis, formasConhecidas, ocupado, aoGravar, aoCancelar }) {
  const edicao = Boolean(lancamento)

  const [categoriaId, setCategoriaId] = useState(lancamento?.categoria?.id ?? '')
  const [subcategoriaId, setSubcategoriaId] = useState(lancamento?.subcategoria?.id ?? '')
  const [dataCaixa, setDataCaixa] = useState(lancamento?.dataCaixa ?? hojeISO())

  // A conta virou estado controlado por causa da forma de pagamento: as opções
  // de forma são as da conta escolhida, então trocar de conta precisa reagir.
  const [contaId, setContaId] = useState(lancamento?.conta?.id ?? '')
  const [formaPagamento, setFormaPagamento] = useState(lancamento?.formaPagamento ?? '')

  const categoria = apoio.categorias.find((c) => c.id === categoriaId)

  // Editar uma perna de transferência é legítimo (dá para corrigir valor e
  // data), e o seletor precisa conseguir MOSTRAR a categoria dela. Sem esta
  // linha, o campo apareceria vazio e a pessoa seria obrigada a trocar de
  // categoria — que é justamente o que o servidor recusa com 403.
  const opcoesDeCategoria =
    categoria && !categoriasLancaveis.some((c) => c.id === categoria.id)
      ? [categoria, ...categoriasLancaveis]
      : categoriasLancaveis
  const subcategoriasDisponiveis = (categoria?.subcategorias ?? []).filter((s) => !s.arquivadaEm)
  const exigeTipo = categoria?.tipo === 'AMBOS'

  // Controlado só porque a dica da forma padrão precisa saber o sentido: o
  // servidor não assume forma para ENTRADA, e prometer na tela o que ele não vai
  // fazer é pior do que não prometer nada.
  const [tipo, setTipo] = useState(lancamento?.tipo ?? 'SAIDA')

  const conta = apoio.contas.find((c) => c.id === contaId)

  // Espelha resolverFormaDePagamento do servidor. É a única duplicação de regra
  // desta tela, e ela existe para MOSTRAR, não para decidir: o que for gravado
  // continua sendo o que o servidor resolver. Se as duas divergirem, o defeito
  // é uma dica errada — não um dado errado.
  const sentido = exigeTipo ? tipo : categoria?.tipo

  // O cruzamento: as formas que a conta aceita E que servem a este sentido.
  // Sem o segundo filtro, a conta corrente ofereceria "boleto" ao lançar o
  // salário — e o servidor recusaria, com razão.
  const formasDisponiveis = formasDaContaNoSentido(
    formasConhecidas,
    conta?.formasPagamento ?? [],
    sentido,
  )

  const assumida =
    categoria && !categoria.sistemica
      ? sentido === 'SAIDA'
        ? conta?.padraoSaida
        : sentido === 'ENTRADA'
          ? conta?.padraoEntrada
          : null
      : null

  function trocarConta(novoId) {
    setContaId(novoId)
    // A forma escolhida pertencia à conta anterior e pode não existir na nova —
    // o servidor recusaria. Mesma lógica de zerar a subcategoria ao trocar de
    // categoria: manter o valor antigo seria mandar algo inválido de propósito.
    const nova = apoio.contas.find((c) => c.id === novoId)
    setFormaPagamento(
      (nova?.formasPagamento ?? []).includes(formaPagamento) ? formaPagamento : '',
    )
  }

  // A situação que o servidor vai derivar. Mostrada, nunca enviada na criação.
  const situacaoPrevista = dataCaixa > hojeISO() ? 'PREVISTO' : 'REALIZADO'

  function trocarCategoria(novoId) {
    setCategoriaId(novoId)
    // Trocar a categoria zera a subcategoria: ela pertencia à anterior, e
    // adivinhar uma equivalente seria inventar dado. É a mesma regra do PUT.
    setSubcategoriaId('')
  }

  return (
    <form
      className="formulario-bloco"
      onSubmit={(e) => {
        e.preventDefault()
        const d = new FormData(e.target)
        const corpo = {
          contaId,
          categoriaId,
          subcategoriaId: subcategoriaId || null,
          valor: d.get('valor').trim(),
          dataCaixa,
          descricao: d.get('descricao').trim(),
          // Vazio significa coisas diferentes nos dois verbos, e é o servidor
          // quem decide: no POST cai na forma padrão da conta, no PUT limpa.
          formaPagamento: formaPagamento || null,
        }
        const competencia = d.get('dataCompetencia')
        if (competencia) corpo.dataCompetencia = competencia
        if (exigeTipo) corpo.tipo = tipo
        // Corrigir a situação é legítimo na edição — só ali o campo é enviado.
        if (edicao) corpo.situacao = d.get('situacao')
        aoGravar(corpo)
      }}
    >
      <h3>{edicao ? 'Editar lançamento' : 'Novo lançamento'}</h3>

      <div className="campos-lado-a-lado">
        <label>
          Conta
          <select
            value={contaId} required
            onChange={(e) => trocarConta(e.target.value)}
          >
            <option value="" disabled>Escolha…</option>
            {apoio.contas.map((c) => <option key={c.id} value={c.id}>{c.nome}</option>)}
          </select>
        </label>

        <label>
          Categoria
          <select
            value={categoriaId} required
            onChange={(e) => trocarCategoria(e.target.value)}
          >
            <option value="" disabled>Escolha…</option>
            {opcoesDeCategoria.map((c) => (
              <option key={c.id} value={c.id}>{c.nome}</option>
            ))}
          </select>
        </label>

        <label>
          Subcategoria
          <select
            value={subcategoriaId}
            onChange={(e) => setSubcategoriaId(e.target.value)}
            disabled={subcategoriasDisponiveis.length === 0}
          >
            <option value="">
              {subcategoriasDisponiveis.length === 0 ? '(nenhuma)' : '(sem subcategoria)'}
            </option>
            {subcategoriasDisponiveis.map((s) => (
              <option key={s.id} value={s.id}>{s.nome}</option>
            ))}
          </select>
        </label>
      </div>

      <div className="campos-lado-a-lado">
        <label>
          Valor
          <input
            name="valor" inputMode="decimal" required
            defaultValue={lancamento?.valor ?? ''} placeholder="0,00"
          />
        </label>

        <label>
          Data de caixa
          <input
            type="date" name="dataCaixa" required
            value={dataCaixa} onChange={(e) => setDataCaixa(e.target.value)}
          />
        </label>

        {exigeTipo && (
          <label>
            Tipo
            <select value={tipo} onChange={(e) => setTipo(e.target.value)} required>
              <option value="SAIDA">Saída</option>
              <option value="ENTRADA">Entrada</option>
            </select>
          </label>
        )}

        {edicao && (
          <label>
            Situação
            <select name="situacao" defaultValue={lancamento.situacao}>
              <option value="REALIZADO">Realizado</option>
              <option value="PREVISTO">Previsto</option>
            </select>
          </label>
        )}

        <label>
          {sentido === 'ENTRADA' ? 'Como entrou' : 'Como foi pago'}
          <select
            value={formaPagamento}
            disabled={formasDisponiveis.length === 0}
            onChange={(e) => setFormaPagamento(e.target.value)}
          >
            <option value="">
              {formasDisponiveis.length === 0 ? '(nenhuma disponível)' : '(não informar)'}
            </option>
            {formasDisponiveis.map((f) => (
              <option key={f.valor} value={f.valor}>{f.nome}</option>
            ))}
          </select>
        </label>
      </div>

      {contaId && sentido && formasDisponiveis.length === 0 && (
        <p className="dica">
          {(conta?.formasPagamento ?? []).length === 0 ? (
            <>
              A conta <strong>{conta?.nome}</strong> não tem formas de pagamento
              cadastradas. Cadastre-as na tela de <strong>Contas</strong> para
              poder registrar como o dinheiro se moveu.
            </>
          ) : (
            <>
              Nenhuma das formas de <strong>{conta?.nome}</strong> serve para{' '}
              {sentido === 'ENTRADA' ? 'entrada' : 'saída'}. Ajuste a lista dela
              na tela de <strong>Contas</strong>.
            </>
          )}
        </p>
      )}

      {!edicao && !formaPagamento && assumida && (
        <p className="dica">
          Sem escolher, este lançamento será gravado como{' '}
          <strong>{rotuloDaForma(formasConhecidas, assumida)}</strong>, que é o
          padrão de {sentido === 'ENTRADA' ? 'entradas' : 'saídas'} da conta{' '}
          <strong>{conta?.nome}</strong>.
        </p>
      )}

      <label className="campo-largo">
        Descrição
        <input name="descricao" defaultValue={lancamento?.descricao ?? ''} maxLength={120} />
      </label>

      {exigeTipo && (
        <p className="dica">
          Esta categoria aceita os dois sentidos, então o tipo precisa ser dito.
          Nas outras ele vem da própria categoria.
        </p>
      )}

      {!edicao && (
        <p className="dica">
          Pela data escolhida, este lançamento nasce como{' '}
          <strong>{situacaoPrevista === 'PREVISTO' ? 'previsto' : 'realizado'}</strong>
          {situacaoPrevista === 'PREVISTO' && ' — está no futuro, então ainda não conta como dinheiro que saiu'}.
        </p>
      )}

      <div className="acoes-linha">
        <button type="submit" className="botao-principal botao-pequeno" disabled={ocupado}>
          {edicao ? 'Salvar' : 'Registrar'}
        </button>
        <button type="button" className="botao-texto" onClick={aoCancelar}>Cancelar</button>
      </div>
    </form>
  )
}

/**
 * Transferência entre contas próprias — os dois lançamentos ligados de F2.
 *
 * O formulário é curto de propósito, e cada ausência é decisão do servidor:
 * não há categoria (é sempre a sistêmica Transferência), não há tipo (origem é
 * saída, destino é entrada) e não há forma de pagamento (categoria sistêmica
 * não recebe padrão — o dinheiro não se moveu por pix nem boleto, só trocou de
 * lugar). Quem quiser registrar "transferi por pix" edita a perna depois.
 */
function FormularioDeTransferencia({ contas, ocupado, aoGravar, aoCancelar }) {
  const [origemId, setOrigemId] = useState('')
  const [destinoId, setDestinoId] = useState('')
  const [dataCaixa, setDataCaixa] = useState(hojeISO())

  const origem = contas.find((c) => c.id === origemId)
  const destino = contas.find((c) => c.id === destinoId)

  // A mesma conta dos dois lados não move dinheiro nenhum. O servidor recusa
  // com 403; tirar a opção da lista evita a viagem e o erro.
  const destinosPossiveis = contas.filter((c) => c.id !== origemId)

  function trocarOrigem(novoId) {
    setOrigemId(novoId)
    if (novoId === destinoId) setDestinoId('')
  }

  return (
    <form
      className="formulario-bloco"
      onSubmit={(e) => {
        e.preventDefault()
        const d = new FormData(e.target)
        aoGravar({
          contaOrigemId: origemId,
          contaDestinoId: destinoId,
          valor: d.get('valor').trim(),
          dataCaixa,
          descricao: d.get('descricao').trim(),
        })
      }}
    >
      <h3>Transferir entre contas</h3>

      <div className="campos-lado-a-lado">
        <label>
          De
          <select value={origemId} required onChange={(e) => trocarOrigem(e.target.value)}>
            <option value="" disabled>Escolha…</option>
            {contas.map((c) => (
              <option key={c.id} value={c.id}>
                {c.nome} — {dinheiro(c.saldo)}
              </option>
            ))}
          </select>
        </label>

        <label>
          Para
          <select
            value={destinoId} required
            disabled={!origemId}
            onChange={(e) => setDestinoId(e.target.value)}
          >
            <option value="" disabled>Escolha…</option>
            {destinosPossiveis.map((c) => (
              <option key={c.id} value={c.id}>
                {c.nome} — {dinheiro(c.saldo)}
              </option>
            ))}
          </select>
        </label>
      </div>

      <div className="campos-lado-a-lado">
        <label>
          Valor
          <input name="valor" inputMode="decimal" required placeholder="0,00" />
        </label>

        <label>
          Data de caixa
          <input
            type="date" name="dataCaixa" required
            value={dataCaixa} onChange={(e) => setDataCaixa(e.target.value)}
          />
        </label>
      </div>

      <label className="campo-largo">
        Descrição
        <input
          name="descricao" maxLength={120}
          placeholder={
            origem && destino
              ? `opcional — sem ela: "Transferência para ${destino.nome}"`
              : 'opcional'
          }
        />
      </label>

      <p className="dica">
        Isto cria <strong>dois</strong> lançamentos ligados: uma saída em{' '}
        {origem ? <strong>{origem.nome}</strong> : 'uma conta'} e uma entrada em{' '}
        {destino ? <strong>{destino.nome}</strong> : 'outra'}, ambos na categoria
        Transferência. O patrimônio total não muda — o dinheiro só troca de
        lugar, e por isso transferência não aparece no mapa de gastos.
      </p>

      <p className="dica">
        Sacar dinheiro é isto: transferir da conta para a carteira. Não existe
        lançamento de "saque" separado, porque seria um segundo nome para o
        mesmo evento.
      </p>

      <div className="acoes-linha">
        <button type="submit" className="botao-principal botao-pequeno" disabled={ocupado}>
          Transferir
        </button>
        <button type="button" className="botao-texto" onClick={aoCancelar}>Cancelar</button>
      </div>
    </form>
  )
}
