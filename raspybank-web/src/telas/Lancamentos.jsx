import { useCallback, useEffect, useMemo, useState } from 'react'
import { lerErro } from '../api/cliente.js'
import {
  categorias as apiCategorias,
  contas as apiContas,
  lancamentos as apiLancamentos,
} from '../api/recursos.js'
import Aviso from '../componentes/Aviso.jsx'
import { useCarregar } from '../ganchos/useCarregar.js'
import { data, dinheiro, hojeISO, mesDe, mesPorExtenso } from '../util/formato.js'

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
      </div>

      {editando && (
        <FormularioDeLancamento
          lancamento={editando === 'novo' ? null : editando}
          apoio={apoio}
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
                <th className="numerico">Valor</th><th />
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

function FormularioDeLancamento({ lancamento, apoio, ocupado, aoGravar, aoCancelar }) {
  const edicao = Boolean(lancamento)

  const [categoriaId, setCategoriaId] = useState(lancamento?.categoria?.id ?? '')
  const [subcategoriaId, setSubcategoriaId] = useState(lancamento?.subcategoria?.id ?? '')
  const [dataCaixa, setDataCaixa] = useState(lancamento?.dataCaixa ?? hojeISO())

  const categoria = apoio.categorias.find((c) => c.id === categoriaId)
  const subcategoriasDisponiveis = (categoria?.subcategorias ?? []).filter((s) => !s.arquivadaEm)
  const exigeTipo = categoria?.tipo === 'AMBOS'

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
          contaId: d.get('contaId'),
          categoriaId,
          subcategoriaId: subcategoriaId || null,
          valor: d.get('valor').trim(),
          dataCaixa,
          descricao: d.get('descricao').trim(),
        }
        const competencia = d.get('dataCompetencia')
        if (competencia) corpo.dataCompetencia = competencia
        if (exigeTipo) corpo.tipo = d.get('tipo')
        // Corrigir a situação é legítimo na edição — só ali o campo é enviado.
        if (edicao) corpo.situacao = d.get('situacao')
        aoGravar(corpo)
      }}
    >
      <h3>{edicao ? 'Editar lançamento' : 'Novo lançamento'}</h3>

      <div className="campos-lado-a-lado">
        <label>
          Conta
          <select name="contaId" defaultValue={lancamento?.conta?.id ?? ''} required>
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
            {apoio.categorias.map((c) => (
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
            <select name="tipo" defaultValue={lancamento?.tipo ?? 'SAIDA'} required>
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
      </div>

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
