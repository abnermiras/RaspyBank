import { useCallback, useEffect, useState } from 'react'
import { lerErro } from '../api/cliente.js'
import { contas as apiContas } from '../api/recursos.js'
import Aviso from '../componentes/Aviso.jsx'
import { useCarregar } from '../ganchos/useCarregar.js'
import { dinheiro } from '../util/formato.js'
import {
  carregarFormasDePagamento,
  formasDoSentido,
  rotuloDaForma,
} from '../util/formasPagamento.js'

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
//
// FORMAS DE PAGAMENTO (V11) — a lista é por conta, e é ela que alimenta o
// seletor da T-08. Uma conta sem lista nenhuma continua funcionando: o que ela
// perde é o campo "como o dinheiro se moveu" no lançamento.
//
// São DOIS padrões, um por sentido, e não um só. Entrada também tem "como o
// dinheiro chegou": o salário é creditado. Um padrão único de saída deixaria
// toda entrada em branco para sempre — e "crédito em conta" nem sequer poderia
// ser escolhida como padrão, porque não serve para saída.
//
// A lista das formas e os sentidos que cada uma aceita vêm do SERVIDOR. Repetir
// isso em JavaScript criaria a terceira cópia de uma regra que já vive no banco
// e no enum, e a divergência apareceria como um seletor oferecendo o que o
// servidor recusa.
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
  const [editandoFormas, setEditandoFormas] = useState(null)
  const [ocupado, setOcupado] = useState(false)

  // O vocabulário de formas vem do servidor. Carregado uma vez por tela; o
  // módulo compartilha a mesma promessa entre telas que montem juntas.
  const [formasConhecidas, setFormasConhecidas] = useState([])
  useEffect(() => {
    carregarFormasDePagamento().then(setFormasConhecidas)
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
        formasConhecidas={formasConhecidas}
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
                formasConhecidas={formasConhecidas}
                aoEditar={() => setEditando(conta.id)}
                aoEditarFormas={() =>
                  setEditandoFormas(editandoFormas === conta.id ? null : conta.id)
                }
                aoEncerrar={() =>
                  executar(() =>
                    conta.encerradaEm
                      ? apiContas.reabrir(conta.id)
                      : apiContas.encerrar(conta.id),
                  )
                }
              />
            )}

            {editandoFormas === conta.id && (
              <EditorDeFormas
                conta={conta}
                ocupado={ocupado}
                formasConhecidas={formasConhecidas}
                aoCancelar={() => setEditandoFormas(null)}
                aoGravar={async (formas, padraoSaida, padraoEntrada) => {
                  const deu = await executar(
                    () => apiContas.definirFormasDePagamento(
                      conta.id, formas, padraoSaida, padraoEntrada),
                    `Formas de pagamento de "${conta.nome}" atualizadas.`,
                  )
                  if (deu) setEditandoFormas(null)
                }}
              />
            )}
          </li>
        ))}
      </ul>
    </section>
  )
}

// ----------------------------------------------------------------------------

function LinhaDeConta({ conta, ocupado, formasConhecidas, aoEditar, aoEditarFormas, aoEncerrar }) {
  const temPrevisto = conta.saldo !== conta.saldoComPrevistos
  const compartilhada = (conta.ambientes?.length ?? 0) > 1
  const formas = conta.formasPagamento ?? []

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
        <span className="formas-da-conta">
          {formas.length === 0 ? (
            <span className="texto-fraco">sem forma de pagamento</span>
          ) : (
            formas.map((f) => {
              const ehPadrao = f === conta.padraoSaida || f === conta.padraoEntrada
              const sentidos = [
                f === conta.padraoSaida && 'saídas',
                f === conta.padraoEntrada && 'entradas',
              ].filter(Boolean)

              return (
                <span
                  key={f}
                  className={`etiqueta etiqueta-fraca${ehPadrao ? ' etiqueta-padrao' : ''}`}
                  title={
                    ehPadrao
                      ? `Assumida nas ${sentidos.join(' e nas ')} desta conta`
                      : undefined
                  }
                >
                  {rotuloDaForma(formasConhecidas, f)}
                </span>
              )
            })
          )}
        </span>
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
        {!conta.encerradaEm && (
          <button type="button" className="botao-texto" onClick={aoEditarFormas} disabled={ocupado}>
            Formas
          </button>
        )}
        <button type="button" className="botao-texto" onClick={aoEncerrar} disabled={ocupado}>
          {conta.encerradaEm ? 'Reabrir' : 'Encerrar'}
        </button>
      </div>
    </div>
  )
}

/**
 * As caixas das formas, mais um seletor de padrão para cada sentido.
 *
 * Usado na criação e na edição, com o mesmo comportamento nos dois lugares —
 * duas cópias divergiriam na primeira correção feita só num deles.
 */
function SeletorDeFormas({ formasConhecidas, formas, padraoSaida, padraoEntrada, aoMudar, desabilitado }) {
  function alternar(valor) {
    const nova = formas.includes(valor)
      ? formas.filter((f) => f !== valor)
      : [...formas, valor]

    // Desmarcar uma forma que era padrão precisa limpar aquele padrão junto. O
    // servidor recusa padrão fora da lista — e um 403 vindo daqui seria culpa
    // da tela, que tinha como saber antes de enviar.
    aoMudar(
      nova,
      nova.includes(padraoSaida) ? padraoSaida : '',
      nova.includes(padraoEntrada) ? padraoEntrada : '',
    )
  }

  const marcadasNoSentido = (sentido) =>
    formasDoSentido(formasConhecidas, sentido).filter((f) => formas.includes(f.valor))

  return (
    <>
      <fieldset className="grade-formas" disabled={desabilitado}>
        <legend>Formas de pagamento aceitas</legend>
        {formasConhecidas.map((f) => (
          <label key={f.valor} className="alternador">
            <input
              type="checkbox"
              checked={formas.includes(f.valor)}
              onChange={() => alternar(f.valor)}
            />
            {f.nome}
            {/* Dizer o sentido evita a pergunta "por que crédito em conta não
                aparece no seletor de padrão de saída?" */}
            {f.sentidos.length === 1 && (
              <span className="texto-fraco">
                {' '}({f.sentidos[0] === 'SAIDA' ? 'só saída' : 'só entrada'})
              </span>
            )}
          </label>
        ))}
      </fieldset>

      <div className="campos-lado-a-lado">
        <label>
          Padrão nas saídas
          <select
            value={padraoSaida}
            disabled={desabilitado || marcadasNoSentido('SAIDA').length === 0}
            onChange={(e) => aoMudar(formas, e.target.value, padraoEntrada)}
          >
            <option value="">(nenhum — sempre perguntar)</option>
            {marcadasNoSentido('SAIDA').map((f) => (
              <option key={f.valor} value={f.valor}>{f.nome}</option>
            ))}
          </select>
        </label>

        <label>
          Padrão nas entradas
          <select
            value={padraoEntrada}
            disabled={desabilitado || marcadasNoSentido('ENTRADA').length === 0}
            onChange={(e) => aoMudar(formas, padraoSaida, e.target.value)}
          >
            <option value="">(nenhum — sempre perguntar)</option>
            {marcadasNoSentido('ENTRADA').map((f) => (
              <option key={f.valor} value={f.valor}>{f.nome}</option>
            ))}
          </select>
        </label>
      </div>
    </>
  )
}

function EditorDeFormas({ conta, ocupado, formasConhecidas, aoGravar, aoCancelar }) {
  const [formas, setFormas] = useState(conta.formasPagamento ?? [])
  const [padraoSaida, setPadraoSaida] = useState(conta.padraoSaida ?? '')
  const [padraoEntrada, setPadraoEntrada] = useState(conta.padraoEntrada ?? '')

  return (
    <form
      className="formulario-bloco"
      onSubmit={(e) => {
        e.preventDefault()
        aoGravar(formas, padraoSaida, padraoEntrada)
      }}
    >
      <h3>Formas de pagamento — {conta.nome}</h3>

      <SeletorDeFormas
        formasConhecidas={formasConhecidas}
        formas={formas}
        padraoSaida={padraoSaida}
        padraoEntrada={padraoEntrada}
        desabilitado={ocupado}
        aoMudar={(novas, novoSaida, novoEntrada) => {
          setFormas(novas)
          setPadraoSaida(novoSaida)
          setPadraoEntrada(novoEntrada)
        }}
      />

      <p className="dica">
        Esta lista é o que o seletor da tela de lançamentos vai oferecer para
        esta conta. Tirar uma forma que algum lançamento já usou é recusado —
        apagá-la desses lançamentos destruiria justamente a informação que ela
        registrava.
      </p>

      <div className="acoes-linha">
        <button type="submit" className="botao-principal botao-pequeno" disabled={ocupado}>
          Salvar formas
        </button>
        <button type="button" className="botao-texto" onClick={aoCancelar}>Cancelar</button>
      </div>
    </form>
  )
}

function FormularioDeConta({ aoCriar, ocupado, formasConhecidas }) {
  const [aberto, setAberto] = useState(false)

  // Nasce configurada como conta corrente, que é a maioria: débito e pix para
  // gastar, crédito em conta para receber o salário. Não é adivinhação, é o
  // caso comum — quem está criando a carteira desmarca tudo e marca dinheiro.
  // Uma tela sem nada marcado faria TODA conta exigir esse trabalho.
  const PADRAO_CONTA_CORRENTE = {
    formas: ['DEBITO', 'PIX', 'CREDITO_EM_CONTA'],
    saida: 'DEBITO',
    entrada: 'CREDITO_EM_CONTA',
  }

  const [formas, setFormas] = useState(PADRAO_CONTA_CORRENTE.formas)
  const [padraoSaida, setPadraoSaida] = useState(PADRAO_CONTA_CORRENTE.saida)
  const [padraoEntrada, setPadraoEntrada] = useState(PADRAO_CONTA_CORRENTE.entrada)

  function fechar() {
    setAberto(false)
    setFormas(PADRAO_CONTA_CORRENTE.formas)
    setPadraoSaida(PADRAO_CONTA_CORRENTE.saida)
    setPadraoEntrada(PADRAO_CONTA_CORRENTE.entrada)
  }

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
          formasPagamento: formas,
          padraoSaida: padraoSaida || null,
          padraoEntrada: padraoEntrada || null,
        })
        fechar()
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

      <SeletorDeFormas
        formasConhecidas={formasConhecidas}
        formas={formas}
        padraoSaida={padraoSaida}
        padraoEntrada={padraoEntrada}
        desabilitado={ocupado}
        aoMudar={(novas, novoSaida, novoEntrada) => {
          setFormas(novas)
          setPadraoSaida(novoSaida)
          setPadraoEntrada(novoEntrada)
        }}
      />

      <p className="dica">
        O saldo inicial não é um campo da conta: ele vira um lançamento na
        categoria <strong>Ajuste</strong>. O saldo continua sendo só a soma dos
        lançamentos, sempre. Aceita negativo, para conta que começa devendo.
      </p>

      <p className="dica">
        Os padrões são o que um lançamento assume quando você não diz como o
        dinheiro se moveu. Não valem para o saldo inicial nem para
        transferências — nenhum dos dois se moveu por pix, boleto ou coisa
        nenhuma: o dinheiro só trocou de lugar.
      </p>

      <div className="acoes-linha">
        <button type="submit" className="botao-principal botao-pequeno" disabled={ocupado}>
          Criar conta
        </button>
        <button type="button" className="botao-texto" onClick={fechar}>
          Cancelar
        </button>
      </div>
    </form>
  )
}
