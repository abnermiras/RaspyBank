import { useCallback, useState } from 'react'
import { lerErro } from '../api/cliente.js'
import { contas as apiContas } from '../api/recursos.js'
import Aviso from '../componentes/Aviso.jsx'
import { useCarregar } from '../ganchos/useCarregar.js'
import { dinheiro } from '../util/formato.js'

// =============================================================================
// T-05 — Contas
// =============================================================================
// Dois saldos, não um (B-D26): `saldo` é o dinheiro que está lá; o outro
// inclui o que já foi agendado. Somar os dois num número só faria o valor
// significar duas coisas ao mesmo tempo — o mesmo defeito que B-D10 evitou no
// mapa. A tela mostra o segundo apenas quando ele difere do primeiro, senão o
// número repetido vira ruído.
//
// Conta não se exclui, se encerra (F7), e encerrar exige saldo zero. O 409 que
// vem daí não é erro de digitação: é o sistema dizendo que dinheiro não evapora.
// =============================================================================

const NATUREZAS = [
  { valor: 'ATIVO', rotulo: 'Ativo (dinheiro seu)' },
  { valor: 'PASSIVO', rotulo: 'Passivo (dívida)' },
]

export default function Contas() {
  const [incluirEncerradas, setIncluirEncerradas] = useState(false)
  const buscar = useCallback(
    () => apiContas.listar(incluirEncerradas),
    [incluirEncerradas],
  )
  const { dados, carregando, erro, recarregar } = useCarregar(buscar)

  const [aviso, setAviso] = useState(null)
  const [editando, setEditando] = useState(null)
  const [ocupado, setOcupado] = useState(false)

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

  const lista = dados?.contas ?? []

  return (
    <section className="painel">
      <header className="cabecalho-painel">
        <h2>Contas</h2>
        <label className="alternador">
          <input
            type="checkbox"
            checked={incluirEncerradas}
            onChange={(e) => setIncluirEncerradas(e.target.checked)}
          />
          Mostrar encerradas
        </label>
      </header>

      <FormularioDeConta
        ocupado={ocupado}
        aoCriar={(dadosDaConta) =>
          executar(() => apiContas.criar(dadosDaConta), `Conta "${dadosDaConta.nome}" criada.`)
        }
      />

      <Aviso aviso={aviso} />
      {erro && <p className="aviso" role="alert">{erro}</p>}
      {carregando && <p className="carregando">Carregando…</p>}

      {!carregando && lista.length === 0 && (
        <p className="texto-vazio">
          Nenhuma conta ainda. Um lançamento precisa de uma conta, então esta é a
          primeira coisa a criar.
        </p>
      )}

      <ul className="lista-contas">
        {lista.map((conta) => (
          <li key={conta.id} className={conta.encerradaEm ? 'arquivada' : undefined}>
            {editando === conta.id ? (
              <form
                className="linha-recurso em-edicao"
                onSubmit={async (e) => {
                  e.preventDefault()
                  const nome = new FormData(e.target).get('nome').trim()
                  const deu = await executar(() => apiContas.alterar(conta.id, { nome }))
                  if (deu) setEditando(null)
                }}
              >
                <input name="nome" defaultValue={conta.nome} required autoFocus />
                <div className="acoes-linha">
                  <button type="submit" className="botao-texto" disabled={ocupado}>Salvar</button>
                  <button type="button" className="botao-texto" onClick={() => setEditando(null)}>
                    Cancelar
                  </button>
                </div>
              </form>
            ) : (
              <LinhaDeConta
                conta={conta}
                ocupado={ocupado}
                aoEditar={() => setEditando(conta.id)}
                aoEncerrar={() =>
                  executar(() =>
                    conta.encerradaEm
                      ? apiContas.reabrir(conta.id)
                      : apiContas.encerrar(conta.id),
                  )
                }
              />
            )}
          </li>
        ))}
      </ul>
    </section>
  )
}

// ----------------------------------------------------------------------------

function LinhaDeConta({ conta, ocupado, aoEditar, aoEncerrar }) {
  const temPrevisto = conta.saldo !== conta.saldoComPrevistos
  const compartilhada = (conta.ambientes?.length ?? 0) > 1

  return (
    <div className="linha-recurso linha-conta">
      <div className="identidade-conta">
        <span className="nome-recurso">{conta.nome}</span>
        <span className={`etiqueta etiqueta-${conta.natureza.toLowerCase()}`}>
          {conta.natureza === 'ATIVO' ? 'Ativo' : 'Passivo'}
        </span>
        {conta.encerradaEm && <span className="etiqueta etiqueta-fraca">encerrada</span>}
        {compartilhada && (
          <span
            className="etiqueta etiqueta-fraca"
            title={`Visível em: ${conta.ambientes.map((a) => a.nome).join(', ')}`}
          >
            compartilhada
          </span>
        )}
      </div>

      <div className="saldos">
        <span className="saldo-principal" title="Só o que já aconteceu (REALIZADO)">
          {dinheiro(conta.saldo)}
        </span>
        {temPrevisto && (
          <span
            className="saldo-previsto"
            title="Incluindo lançamentos já agendados, que ainda não aconteceram"
          >
            {dinheiro(conta.saldoComPrevistos)} com previstos
          </span>
        )}
      </div>

      <div className="acoes-linha">
        {!conta.encerradaEm && (
          <button type="button" className="botao-texto" onClick={aoEditar} disabled={ocupado}>
            Renomear
          </button>
        )}
        <button type="button" className="botao-texto" onClick={aoEncerrar} disabled={ocupado}>
          {conta.encerradaEm ? 'Reabrir' : 'Encerrar'}
        </button>
      </div>
    </div>
  )
}

function FormularioDeConta({ aoCriar, ocupado }) {
  const [aberto, setAberto] = useState(false)

  if (!aberto) {
    return (
      <button
        type="button" className="botao-principal botao-pequeno"
        onClick={() => setAberto(true)}
      >
        Nova conta
      </button>
    )
  }

  return (
    <form
      className="formulario-bloco"
      onSubmit={(e) => {
        e.preventDefault()
        const d = new FormData(e.target)
        const saldoInicial = d.get('saldoInicial').trim()
        aoCriar({
          nome: d.get('nome').trim(),
          natureza: d.get('natureza'),
          // Ausente é diferente de zero: sem valor, nenhum lançamento nasce.
          ...(saldoInicial ? { saldoInicial } : {}),
        })
        setAberto(false)
      }}
    >
      <div className="campos-lado-a-lado">
        <label>
          Nome
          <input name="nome" required maxLength={60} autoFocus />
        </label>
        <label>
          Natureza
          <select name="natureza" defaultValue="ATIVO">
            {NATUREZAS.map((n) => <option key={n.valor} value={n.valor}>{n.rotulo}</option>)}
          </select>
        </label>
        <label>
          Saldo inicial
          <input name="saldoInicial" inputMode="decimal" placeholder="opcional" />
        </label>
      </div>

      <p className="dica">
        O saldo inicial não é um campo da conta: ele vira um lançamento na
        categoria <strong>Ajuste</strong>. O saldo continua sendo só a soma dos
        lançamentos, sempre. Aceita negativo, para conta que começa devendo.
      </p>

      <div className="acoes-linha">
        <button type="submit" className="botao-principal botao-pequeno" disabled={ocupado}>
          Criar conta
        </button>
        <button type="button" className="botao-texto" onClick={() => setAberto(false)}>
          Cancelar
        </button>
      </div>
    </form>
  )
}
