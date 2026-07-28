import { useCallback, useState } from 'react'
import { relatorios } from '../api/recursos.js'
import { useCarregar } from '../ganchos/useCarregar.js'
import { ehZero, numero, NOMES_DOS_MESES } from '../util/formato.js'

// =============================================================================
// T-07 — Mapa de gastos
// =============================================================================
// Três blocos (saídas, entradas, saldo — B-D12) e cada célula com DOIS números,
// realizado e previsto (B-D10). O servidor nunca soma os dois, e a tela também
// não: somar faria o número significar "o que aconteceu" e "o que talvez
// aconteça" ao mesmo tempo, que é exatamente o que o desenho evita.
//
// O previsto só aparece quando existe. Mostrar "0,00" embaixo de cada uma das
// doze células de cada categoria encheria a tabela de zeros e afogaria os
// poucos números que importam.
// =============================================================================

export default function MapaDeGastos() {
  const [ano, setAno] = useState(() => new Date().getFullYear())

  // O recorte de conta (B-D54). Filtro no topo, e não um terceiro número por
  // célula: B-D10 separou realizado de previsto com esforço, e um terceiro em
  // doze colunas viraria sopa. Trocar a lente responde "quanto do meu mercado
  // foi no cartão" sem poluir nada.
  const [contas, setContas] = useState('TODAS')

  const buscar = useCallback(() => relatorios.mapaDeGastos(ano, contas), [ano, contas])
  const { dados, carregando, erro } = useCarregar(buscar)

  return (
    <section className="painel painel-largo">
      <header className="cabecalho-painel">
        <h2>Mapa de gastos</h2>
        <div className="navegador-mes">
          <button type="button" className="botao-texto" onClick={() => setAno(ano - 1)}>‹</button>
          <span className="mes-atual">{ano}</span>
          <button type="button" className="botao-texto" onClick={() => setAno(ano + 1)}>›</button>
        </div>

        <select
          className="filtro-contas"
          value={contas}
          onChange={(e) => setContas(e.target.value)}
          title="Recorta o mapa por origem do gasto"
        >
          <option value="TODAS">Todas as contas</option>
          <option value="CARTAO">Só cartão de crédito</option>
          <option value="SEM_CARTAO">Sem cartão de crédito</option>
        </select>
      </header>

      {erro && <p className="aviso" role="alert">{erro}</p>}
      {carregando && <p className="carregando">Carregando…</p>}

      {dados && (
        <>
          <Bloco titulo="Saídas" bloco={dados.saidas} sentido="saida" />
          <Bloco titulo="Entradas" bloco={dados.entradas} sentido="entrada" />
          <BlocoDeSaldo saldo={dados.saldo} />

          <p className="rodape-painel">
            Transferência entre contas próprias e ajuste de saldo ficam de fora:
            não são despesa nem receita. "Não classificado" fica <strong>dentro</strong> —
            é gasto real sem rótulo, e escondê-lo faria o total mentir para baixo.
          </p>
        </>
      )}
    </section>
  )
}

// ----------------------------------------------------------------------------

function Bloco({ titulo, bloco, sentido }) {
  const [abertas, setAbertas] = useState({})
  const categorias = bloco?.categorias ?? []

  if (categorias.length === 0) {
    return (
      <div className="bloco-mapa">
        <h3>{titulo}</h3>
        <p className="texto-vazio">Nada lançado neste ano.</p>
      </div>
    )
  }

  return (
    <div className="bloco-mapa">
      <h3>{titulo}</h3>
      <div className="rolagem-horizontal">
        <table className="tabela-mapa">
          <thead>
            <tr>
              <th className="coluna-rotulo">Categoria</th>
              {NOMES_DOS_MESES.map((m) => <th key={m} className="numerico">{m}</th>)}
              <th className="numerico total">Total</th>
            </tr>
          </thead>
          <tbody>
            {categorias.map((categoria) => {
              const aberta = abertas[categoria.categoriaId]
              const temSubs = (categoria.subcategorias?.length ?? 0) > 0
              return (
                <FragmentoDeCategoria
                  key={categoria.categoriaId}
                  categoria={categoria}
                  sentido={sentido}
                  aberta={aberta}
                  temSubs={temSubs}
                  aoAlternar={() =>
                    setAbertas({ ...abertas, [categoria.categoriaId]: !aberta })
                  }
                />
              )
            })}
          </tbody>
          <tfoot>
            <tr className="linha-total">
              <th className="coluna-rotulo">Total</th>
              {ordenarPorMes(bloco.totaisPorMes).map((c) => (
                <Celula key={c.mes} celula={c} sentido={sentido} />
              ))}
              <Celula celula={bloco.total} sentido={sentido} destaque />
            </tr>
          </tfoot>
        </table>
      </div>
    </div>
  )
}

function FragmentoDeCategoria({ categoria, sentido, aberta, temSubs, aoAlternar }) {
  return (
    <>
      <tr className="linha-categoria">
        <th className="coluna-rotulo">
          {temSubs ? (
            <button type="button" className="botao-texto expansor" onClick={aoAlternar}>
              <span className="seta">{aberta ? '▾' : '▸'}</span> {categoria.nome}
            </button>
          ) : (
            <span className="sem-expansor">{categoria.nome}</span>
          )}
        </th>
        {ordenarPorMes(categoria.celulas).map((c) => (
          <Celula key={c.mes} celula={c} sentido={sentido} />
        ))}
        <Celula celula={categoria.total} sentido={sentido} destaque />
      </tr>

      {aberta &&
        categoria.subcategorias.map((sub) => (
          <tr key={sub.subcategoriaId ?? 'sem-sub'} className="linha-subcategoria">
            <th className="coluna-rotulo">
              <span className="recuo-sub">{sub.nome}</span>
            </th>
            {ordenarPorMes(sub.celulas).map((c) => (
              <Celula key={c.mes} celula={c} sentido={sentido} />
            ))}
            <Celula celula={sub.total} sentido={sentido} destaque />
          </tr>
        ))}
    </>
  )
}

function BlocoDeSaldo({ saldo }) {
  if (!saldo?.porMes) return null
  return (
    <div className="bloco-mapa">
      <h3>Saldo</h3>
      <div className="rolagem-horizontal">
        <table className="tabela-mapa">
          <thead>
            <tr>
              <th className="coluna-rotulo">Entradas − saídas</th>
              {NOMES_DOS_MESES.map((m) => <th key={m} className="numerico">{m}</th>)}
              <th className="numerico total">Total</th>
            </tr>
          </thead>
          <tbody>
            <tr className="linha-total">
              <th className="coluna-rotulo">No mês</th>
              {ordenarPorMes(saldo.porMes).map((c) => (
                <Celula key={c.mes} celula={c} sentido="saldo" />
              ))}
              <Celula celula={saldo.total} sentido="saldo" destaque />
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  )
}

/**
 * Uma célula, dois números. O previsto vai numa segunda linha e só quando
 * existe — é o "deixa claro que ainda não realizou" do B-D10, e é por isso que
 * ele não pode virar a mesma cor nem a mesma linha do realizado.
 */
function Celula({ celula, sentido, destaque }) {
  if (!celula) return <td className="numerico" />

  const semRealizado = ehZero(celula.realizado)
  const semPrevisto = ehZero(celula.previsto)
  if (semRealizado && semPrevisto) {
    return <td className={`numerico vazia ${destaque ? 'total' : ''}`}>·</td>
  }

  const cor =
    sentido === 'saldo'
      ? Number(celula.realizado) < 0 ? 'saida' : 'entrada'
      : sentido

  return (
    <td className={`numerico ${destaque ? 'total' : ''}`}>
      {!semRealizado && <span className={cor}>{numero(celula.realizado)}</span>}
      {!semPrevisto && (
        <span className="valor-previsto" title="Agendado, ainda não aconteceu">
          {numero(celula.previsto)}
        </span>
      )}
    </td>
  )
}

/**
 * O contrato promete doze células sempre, mas a ordem não é promessa — e uma
 * tabela de doze colunas montada fora de ordem erra em silêncio, sem nada na
 * tela indicando que Março virou Maio.
 */
function ordenarPorMes(celulas) {
  const porMes = new Map((celulas ?? []).map((c) => [c.mes, c]))
  return NOMES_DOS_MESES.map(
    (_, indice) =>
      porMes.get(indice + 1) ?? { mes: indice + 1, realizado: '0.00', previsto: '0.00' },
  )
}
