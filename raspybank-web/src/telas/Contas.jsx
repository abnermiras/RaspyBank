import { useCallback, useEffect, useState } from 'react'
import { lerErro } from '../api/cliente.js'
import {
  ambientes as apiAmbientes,
  contas as apiContas,
  convites as apiConvites,
} from '../api/recursos.js'
import Aviso from '../componentes/Aviso.jsx'
import PainelDeCompartilhamento from '../componentes/PainelDeCompartilhamento.jsx'
import { useAutenticacao } from '../contexto/Autenticacao.jsx'
import { useCarregar } from '../ganchos/useCarregar.js'
import { data, dinheiro, mesDe, mesPorExtenso, paraDecimal } from '../util/formato.js'
import {
  carregarFormasDePagamento,
  formasDoSentido,
  rotuloDaForma,
} from '../util/formasPagamento.js'

// =============================================================================
// T-05 — Contas bancárias
// =============================================================================
// "Bancárias" no título e não só "Contas", porque o cartão de crédito TAMBÉM é
// uma conta por baixo (B-D47) e não aparece aqui (B-D62). Palavras dele nos
// testes de negócio: "tratar o cartão de crédito como um banco confunde".
//
// A dívida do cartão não sumiu — ela continua no patrimônio e aparece inteira
// na tela de Cartões. O que saiu é o cartão fingindo ser um lugar onde se
// guarda dinheiro.
//
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
//
// COMPARTILHAMENTO DE CONTA (V16, §4k) — o segundo modo, e diferente do
// ambiente: quem recebe a conta trabalha no ambiente DELA. As categorias são
// dela, o mapa é dela, e a conta é dos dois. A regra que resume: o saldo
// atravessa ambientes, a classificação não (B-D85).
//
// Três campos novos governam esta tela, e a diferença entre os dois primeiros é
// a parte que erra fácil:
//
//   origem           — a conta nasceu neste ambiente. Libera renomear, encerrar
//                      e formas, que são DINHEIRO e valem também para quem
//                      entrou no ambiente por convite (B-D76).
//   podeCompartilhar — sou dono do ambiente onde ela nasceu. Libera a PORTA
//                      (B-D91). Mais estreito de propósito: quem recebeu o
//                      ambiente usa a conta, mas não a passa adiante.
//   compartilhada    — alguém mais tem esta conta. Sem esta marca, o saldo
//                      maior que a soma dos lançamentos visíveis pareceria erro.
//
// O SALDO já vem somando os dois lados (B-D87). Por isso o extrato da conta tem
// endpoint próprio: o extrato do mês é o do ambiente e não atravessa, então numa
// conta compartilhada ele nunca fecharia com o saldo mostrado aqui.
// =============================================================================

const NATUREZAS = [
  { valor: 'ATIVO', rotulo: 'Ativo (dinheiro seu)' },
  { valor: 'PASSIVO', rotulo: 'Passivo (dívida)' },
]

export default function Contas() {
  const { perfil } = useAutenticacao()
  const [incluirEncerradas, setIncluirEncerradas] = useState(false)
  const buscar = useCallback(
    () => apiContas.listar(incluirEncerradas),
    [incluirEncerradas],
  )
  const { dados, carregando, erro, recarregar } = useCarregar(buscar)

  const [aviso, setAviso] = useState(null)
  const [editando, setEditando] = useState(null)
  const [editandoFormas, setEditandoFormas] = useState(null)
  const [compartilhando, setCompartilhando] = useState(null)
  const [vendoExtrato, setVendoExtrato] = useState(null)
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
        <h2>Contas bancárias</h2>
        <label className="alternador">
          <input
            type="checkbox"
            checked={incluirEncerradas}
            onChange={(e) => setIncluirEncerradas(e.target.checked)}
          />
          Mostrar encerradas
        </label>
      </header>

      {/*
        Os convites vêm ANTES do formulário, e no topo da tela de contas de
        propósito: um convite que ninguém vê é um convite que não existe, e é
        aqui que a conta vai aparecer quando ela aceitar.
      */}
      <ConvitesPendentes aoAceitar={recarregar} />

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
          Nenhuma conta bancária ainda. Um lançamento precisa de uma conta,
          então esta é a primeira coisa a criar — e um cartão de crédito
          precisa de uma para ficar embaixo.
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
                aoCompartilhar={() =>
                  setCompartilhando(compartilhando === conta.id ? null : conta.id)
                }
                aoVerExtrato={() =>
                  setVendoExtrato(vendoExtrato === conta.id ? null : conta.id)
                }
                aoDevolver={() => {
                  if (
                    window.confirm(
                      `Devolver "${conta.nome}" para ${conta.recebidaDe}?`
                        + ' Os lançamentos que você já fez nela continuam no seu histórico'
                        + ' e no saldo da conta — o dinheiro passou por lá de verdade.',
                    )
                  ) {
                    // Sair é o MESMO caminho de revogar, com o próprio id: o
                    // dono remove qualquer um, qualquer um remove a si mesmo.
                    executar(
                      () => apiContas.removerCompartilhamento(conta.id, perfil.usuarioId),
                      `Você devolveu "${conta.nome}".`,
                    )
                  }
                }}
              />
            )}

            {compartilhando === conta.id && (
              <PainelDeCompartilhamento
                recurso={conta}
                api={apiContas}
                aoMudar={recarregar}
              />
            )}

            {vendoExtrato === conta.id && <ExtratoDaConta conta={conta} />}

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

function LinhaDeConta({
  conta, ocupado, formasConhecidas,
  aoEditar, aoEditarFormas, aoEncerrar, aoCompartilhar, aoVerExtrato, aoDevolver,
}) {
  const temPrevisto = conta.saldo !== conta.saldoComPrevistos
  const formas = conta.formasPagamento ?? []

  // Antes da V16, "compartilhada" era ter mais de um ambiente na lista — e a
  // lista só mostra os ambientes de quem está olhando, então a conta dividida
  // com outra pessoa aparecia como não compartilhada. Agora o servidor responde.
  const emMaisDeUmAmbienteMeu = (conta.ambientes?.length ?? 0) > 1
  const recebida = Boolean(conta.recebidaDe)

  return (
    <div className="linha-recurso linha-conta">
      <div className="identidade-conta">
        <span className="nome-recurso">{conta.nome}</span>
        <span className={`etiqueta etiqueta-${conta.natureza.toLowerCase()}`}>
          {conta.natureza === 'ATIVO' ? 'Ativo' : 'Passivo'}
        </span>
        {conta.encerradaEm && <span className="etiqueta etiqueta-fraca">encerrada</span>}

        {recebida && (
          <span
            className="etiqueta etiqueta-fraca"
            title={
              `${conta.recebidaDe} dividiu esta conta com você. Lance nela à vontade;`
              + ' os seus lançamentos ficam no SEU mapa, com as SUAS categorias,'
              + ' e o saldo é o mesmo para os dois.'
            }
          >
            de {conta.recebidaDe}
          </span>
        )}

        {conta.compartilhada && (
          <span
            className="etiqueta etiqueta-fraca"
            title={
              'Outra pessoa também lança nesta conta. O saldo acima já soma os dois'
              + ' lados — use o Extrato para ver os movimentos dela.'
            }
          >
            dividida
          </span>
        )}

        {emMaisDeUmAmbienteMeu && (
          <span
            className="etiqueta etiqueta-fraca"
            title={`Aparece nos seus ambientes: ${conta.ambientes.map((a) => a.nome).join(', ')}`}
          >
            em {conta.ambientes.length} ambientes
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

      {/*
        Os botões seguem os dois campos, e não um só. `origem` libera o que é
        dinheiro (renomear, formas, encerrar) — inclusive para quem entrou no
        ambiente por convite, que é o que B-D76 manda. `podeCompartilhar` libera
        a porta, e é mais estreito. Esconder em vez de mostrar-e-recusar: um
        botão que sempre responde 403 é um botão que mente.
      */}
      <div className="acoes-linha">
        <button type="button" className="botao-texto" onClick={aoVerExtrato} disabled={ocupado}>
          Extrato
        </button>

        {conta.origem && !conta.encerradaEm && (
          <button type="button" className="botao-texto" onClick={aoEditar} disabled={ocupado}>
            Renomear
          </button>
        )}
        {conta.origem && !conta.encerradaEm && (
          <button type="button" className="botao-texto" onClick={aoEditarFormas} disabled={ocupado}>
            Formas
          </button>
        )}
        {conta.podeCompartilhar && !conta.encerradaEm && (
          <button type="button" className="botao-texto" onClick={aoCompartilhar} disabled={ocupado}>
            Dividir
          </button>
        )}
        {conta.origem && (
          <button type="button" className="botao-texto" onClick={aoEncerrar} disabled={ocupado}>
            {conta.encerradaEm ? 'Reabrir' : 'Encerrar'}
          </button>
        )}
        {recebida && (
          <button type="button" className="botao-texto" onClick={aoDevolver} disabled={ocupado}>
            Devolver
          </button>
        )}
      </div>
    </div>
  )
}

/**
 * Os convites de conta esperando você (B-D90).
 *
 * O AMBIENTE É UM CAMPO OBRIGATÓRIO, e é o ponto do aceite. Cair no ambiente
 * ativo mandaria a conta doméstica para o PJ sem aviso, e os gastos iriam para o
 * mapa errado até alguém notar — e notar é difícil, porque nada avisa.
 *
 * Só ambientes de que a pessoa é DONA entram no seletor: aceitar dentro de um
 * ambiente que ela recebeu emprestado espalharia a conta para o dono daquele
 * ambiente, que não participou de nada disto.
 */
function ConvitesPendentes({ aoAceitar }) {
  const buscar = useCallback(() => apiConvites.listar(), [])
  const { dados, recarregar } = useCarregar(buscar)

  const [meusAmbientes, setMeusAmbientes] = useState([])
  const [aviso, setAviso] = useState(null)
  const [ocupado, setOcupado] = useState(false)
  const [escolhas, setEscolhas] = useState({})

  const lista = dados?.convites ?? []

  useEffect(() => {
    if (lista.length === 0) return
    apiAmbientes.listar().then((r) => {
      if (r.ok) setMeusAmbientes((r.corpo.ambientes ?? []).filter((a) => a.dono))
    })
  }, [lista.length])

  if (lista.length === 0) return null

  async function executar(acao, mensagemDeSucesso) {
    setOcupado(true)
    setAviso(null)
    try {
      const resposta = await acao()
      if (!resposta.ok) {
        setAviso({ texto: lerErro(resposta).mensagem, sucesso: false })
        return
      }
      setAviso({ texto: mensagemDeSucesso, sucesso: true })
      await recarregar()
      await aoAceitar()
    } catch {
      setAviso({ texto: 'Servidor indisponível.', sucesso: false })
    } finally {
      setOcupado(false)
    }
  }

  return (
    <div className="formulario-bloco">
      <h3>Contas que dividiram com você</h3>

      <p className="dica">
        Escolha em qual dos seus ambientes cada conta vai aparecer — é uma escolha
        que só você pode fazer, e ela decide em qual mapa de gastos os seus
        lançamentos vão entrar. O saldo é o mesmo para os dois; a{' '}
        <strong>classificação</strong> é sua.
      </p>

      <Aviso aviso={aviso} />

      <ul className="lista-ambientes">
        {lista.map((convite) => {
          const escolhido = escolhas[convite.id] ?? meusAmbientes[0]?.id ?? ''

          return (
            <li key={convite.id}>
              <span className="nome-recurso">{convite.conta.nome}</span>
              <span className="texto-fraco"> de {convite.de.nome}</span>

              <label>
                Aparecer em
                <select
                  value={escolhido}
                  onChange={(e) =>
                    setEscolhas({ ...escolhas, [convite.id]: e.target.value })
                  }
                >
                  {meusAmbientes.map((a) => (
                    <option key={a.id} value={a.id}>{a.nome}</option>
                  ))}
                </select>
              </label>

              <button
                type="button" className="botao-principal botao-pequeno"
                disabled={ocupado || !escolhido}
                onClick={() =>
                  executar(
                    () => apiConvites.aceitar(convite.id, escolhido),
                    `"${convite.conta.nome}" agora aparece no ambiente escolhido.`,
                  )
                }
              >
                Aceitar
              </button>

              <button
                type="button" className="botao-texto" disabled={ocupado}
                onClick={() =>
                  executar(
                    () => apiConvites.recusar(convite.id),
                    `Convite de ${convite.de.nome} recusado.`,
                  )
                }
              >
                Recusar
              </button>
            </li>
          )
        })}
      </ul>
    </div>
  )
}

/**
 * O extrato da conta, e é ele que ATRAVESSA ambientes (B-D87).
 *
 * O extrato do mês (T-08) é o do ambiente e não atravessa — numa conta dividida
 * ele nunca fecharia com o saldo mostrado aqui. Esta é a lista que confere
 * contra o extrato do banco.
 */
function ExtratoDaConta({ conta }) {
  const [mes, setMes] = useState(mesDe(new Date()))
  const buscar = useCallback(() => apiContas.extrato(conta.id, mes), [conta.id, mes])
  const { dados, carregando, erro } = useCarregar(buscar)

  const linhas = dados?.lancamentos ?? []

  return (
    <div className="formulario-bloco">
      <h3>Extrato de “{conta.nome}”</h3>

      <div className="campos-lado-a-lado">
        <label>
          Mês
          <input type="month" value={mes} onChange={(e) => setMes(e.target.value)} />
        </label>
      </div>

      <p className="dica">
        {mesPorExtenso(mes)}. Esta lista mostra <strong>todos</strong> os
        movimentos da conta, inclusive os de quem a divide com você — é ela que
        bate com o extrato do banco.
      </p>

      {erro && <p className="aviso" role="alert">{erro}</p>}
      {carregando && <p className="carregando">Carregando…</p>}

      {!carregando && linhas.length === 0 && (
        <p className="texto-vazio">Nenhum movimento neste mês.</p>
      )}

      <ul className="lista-ambientes">
        {linhas.map((l) => (
          <li key={l.id} className={l.meu ? undefined : 'arquivada'}>
            <span className="texto-fraco">{data(l.data)}</span>

            {/*
              A linha alheia não tem descrição nem categoria para mostrar, e não
              é esta tela que as esconde: elas não vêm do servidor (B-D97). O que
              se põe no lugar é o nome de quem fez — que é o que basta para o
              valor deixar de ser um mistério.
            */}
            <span className="nome-recurso">
              {l.meu ? (l.descricao || l.categoria?.nome || '—') : `movimento de ${l.quem.nome}`}
            </span>

            {l.meu && l.categoria && (
              <span className="etiqueta etiqueta-fraca">{l.categoria.nome}</span>
            )}
            {l.situacao === 'PREVISTO' && (
              <span className="etiqueta etiqueta-fraca">previsto</span>
            )}
            {l.parcelaTotal > 1 && (
              <span
                className="etiqueta etiqueta-fraca"
                title="Compra parcelada: as próximas parcelas já estão comprometidas nas faturas seguintes"
              >
                {l.parcelaNumero}/{l.parcelaTotal}
              </span>
            )}

            <span className={l.tipo === 'ENTRADA' ? 'etiqueta-entrada' : undefined}>
              {l.tipo === 'ENTRADA' ? '+' : '−'} {dinheiro(l.valor)}
            </span>
          </li>
        ))}
      </ul>
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
  // Papel moeda OU dinheiro virtual, nunca os dois — e por isso as caixas se
  // desligam entre si em vez de só recusarem a combinação depois.
  //
  // DINHEIRO é papel moeda, e o único lugar que guarda papel moeda é físico:
  // carteira, gaveta, cofre. Nenhum deles aceita pix. Do outro lado, o dinheiro
  // de uma conta em banco é virtual — tirá-lo de lá não é "pagar em espécie", é
  // um SAQUE, que neste sistema é uma transferência para a conta física.
  function alternar(valor) {
    let nova

    if (formas.includes(valor)) {
      nova = formas.filter((f) => f !== valor)
    } else if (valor === 'DINHEIRO') {
      // Marcar dinheiro apaga o resto: a conta passou a ser física.
      nova = ['DINHEIRO']
    } else {
      // E marcar qualquer forma virtual apaga o dinheiro, pelo mesmo motivo ao
      // contrário.
      nova = [...formas.filter((f) => f !== 'DINHEIRO'), valor]
    }

    // Desmarcar uma forma que era padrão precisa limpar aquele padrão junto. O
    // servidor recusa padrão fora da lista — e um 403 vindo daqui seria culpa
    // da tela, que tinha como saber antes de enviar.
    aoMudar(
      nova,
      nova.includes(padraoSaida) ? padraoSaida : '',
      nova.includes(padraoEntrada) ? padraoEntrada : '',
    )
  }

  const ehContaFisica = formas.includes('DINHEIRO')

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

      <p className="dica">
        {ehContaFisica ? (
          <>
            Esta é uma conta <strong>física</strong> — carteira, gaveta, cofre.
            Ela guarda papel moeda, e papel moeda não recebe pix nem paga boleto.
            Marcar qualquer outra forma desmarca o dinheiro.
          </>
        ) : (
          <>
            Esta é uma conta <strong>virtual</strong> — o dinheiro dela é um
            número no banco. Marcar <strong>Dinheiro</strong> desmarca as
            outras: tirar dinheiro daqui não é pagar em espécie, é um saque, que
            neste sistema é uma transferência para a carteira.
          </>
        )}
      </p>

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
        const saldoInicial = paraDecimal(d.get('saldoInicial'))
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
