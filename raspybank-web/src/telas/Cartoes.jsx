import { useCallback, useEffect, useState } from 'react'
import { lerErro } from '../api/cliente.js'
import {
  cartoes as apiCartoes,
  contas as apiContas,
  faturas as apiFaturas,
} from '../api/recursos.js'
import Aviso from '../componentes/Aviso.jsx'
import { useCarregar } from '../ganchos/useCarregar.js'
import { data, dinheiro } from '../util/formato.js'
import {
  carregarFormasDePagamento,
  formasDaContaNoSentido,
} from '../util/formasPagamento.js'

// =============================================================================
// T-06 — Cartões de crédito
// =============================================================================
// O cartão é o CONTRATO, não o plástico (B-D46). "Black" e "Diamond" no mesmo
// Nubank são dois cartões; os plásticos e virtuais debaixo de cada um são os
// emitidos, e todos consomem o mesmo limite.
//
// O LIMITE NÃO TRAVA NADA (B-D48). Nenhuma compra é recusada por estourá-lo, e
// o disponível pode aparecer negativo — o que é informação, não defeito: o
// banco de verdade é quem recusa, e o número existe para bater com o app dele.
//
// O ESTADO DA FATURA SÃO TRÊS CAMPOS, não um (B-D58): ciclo, quitação e
// vencida. É esta tela que compõe o rótulo a partir deles — o servidor não
// escolhe, porque uma fatura ABERTA pode estar parcialmente paga (antecipação
// para liberar limite) e num enum único esse caso não teria nome.
//
// O AGRUPAMENTO É POR BANCO, e a razão é como ele pensa: "quando penso em
// cartão de crédito primeiro eu penso de qual banco, depois no cartão principal
// e depois nos adicionais ou virtuais". A tela segue essa ordem — banco,
// contrato, plásticos —, que é a mesma do modelo (B-D46).
//
// ENCERRAR NÃO EXIGE DÍVIDA ZERO (B-D65). Encerrar um cartão não perdoa a
// fatura: as parcelas futuras continuam chegando e as faturas continuam
// pagáveis. O que encerrar faz é uma coisa só — impedir compra nova.
// =============================================================================

const HOJE = new Date()

export default function Cartoes() {
  const [incluirEncerrados, setIncluirEncerrados] = useState(false)
  const buscar = useCallback(
    () => apiCartoes.listar(incluirEncerrados),
    [incluirEncerrados],
  )
  const { dados, carregando, erro, recarregar } = useCarregar(buscar)

  const [aviso, setAviso] = useState(null)
  const [ocupado, setOcupado] = useState(false)
  const [abrindo, setAbrindo] = useState(false)
  const [selecionado, setSelecionado] = useState(null)
  const [emitindo, setEmitindo] = useState(null)

  // Os seletores precisam das contas de banco e das formas de pagamento.
  const [apoio, setApoio] = useState({ contas: [], formas: [] })
  useEffect(() => {
    Promise.all([apiContas.listar(false), carregarFormasDePagamento()]).then(
      ([respostaContas, formas]) => {
        setApoio({
          contas: respostaContas.ok ? respostaContas.corpo.contas : [],
          formas,
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

  const lista = dados?.cartoes ?? []

  // Banco → contratos daquele banco. A ordem dentro do grupo é a que o servidor
  // devolveu (por nome), e a dos grupos é a do primeiro cartão de cada um —
  // reordenar aqui faria a tela mudar de arrumação entre duas chamadas iguais.
  const porBanco = [
    ...lista.reduce((mapa, c) => {
      const chave = c.banco?.id ?? 'sem-banco'
      return mapa.set(chave, [...(mapa.get(chave) ?? []), c])
    }, new Map()),
  ]

  // Só conta de banco vira cartão: papel moeda não emite crédito (B-D45). A
  // tela já filtra para não oferecer o que o servidor recusa com 403.
  const contasDeBanco = apoio.contas.filter(
    (c) => !((c.formasPagamento ?? []).length === 1 && c.formasPagamento[0] === 'DINHEIRO'),
  )

  return (
    <section className="painel">
      <header className="cabecalho-painel">
        <h2>Cartões de crédito</h2>
        <label className="alternador">
          <input
            type="checkbox"
            checked={incluirEncerrados}
            onChange={(e) => setIncluirEncerrados(e.target.checked)}
          />
          Mostrar encerrados
        </label>
        <button
          type="button" className="botao-principal botao-pequeno"
          onClick={() => setAbrindo(true)}
          disabled={contasDeBanco.length === 0}
          title={
            contasDeBanco.length === 0
              ? 'Crie uma conta de banco antes — cartão de crédito sempre fica embaixo de uma.'
              : undefined
          }
        >
          Novo cartão
        </button>
      </header>

      {abrindo && (
        <FormularioDeCartao
          contas={contasDeBanco}
          ocupado={ocupado}
          aoCancelar={() => setAbrindo(false)}
          aoGravar={async (corpo) => {
            const deu = await executar(
              () => apiCartoes.criar(corpo),
              `Cartão "${corpo.nome}" criado, com as faturas dos próximos 12 meses.`,
            )
            if (deu) setAbrindo(false)
          }}
        />
      )}

      <Aviso aviso={aviso} />
      {erro && <p className="aviso" role="alert">{erro}</p>}
      {carregando && <p className="carregando">Carregando…</p>}

      {!carregando && lista.length === 0 && (
        <p className="texto-vazio">
          Nenhum cartão ainda. Um cartão de crédito sempre fica embaixo de uma
          conta de banco — e a dívida dele aparece junto das outras contas,
          porque é dívida de verdade.
        </p>
      )}

      {porBanco.map(([bancoId, doBanco]) => (
        <section key={bancoId} className="grupo-banco">
          <h3 className="nome-banco">{doBanco[0].banco?.nome}</h3>

          <ul className="lista-cartoes">
            {doBanco.map((cartao) => (
              <li key={cartao.id} className={cartao.encerradoEm ? 'arquivada' : undefined}>
                <LinhaDeCartao
                  cartao={cartao}
                  aberto={selecionado === cartao.id}
                  ocupado={ocupado}
                  aoAlternar={() =>
                    setSelecionado(selecionado === cartao.id ? null : cartao.id)
                  }
                  aoNovoVirtual={() => setEmitindo(cartao)}
                  aoEncerrar={() =>
                    executar(
                      () =>
                        cartao.encerradoEm
                          ? apiCartoes.reabrir(cartao.id)
                          : apiCartoes.encerrar(cartao.id),
                      cartao.encerradoEm
                        ? `Cartão "${cartao.nome}" reaberto — os cartões emitidos continuam cancelados.`
                        : `Cartão "${cartao.nome}" encerrado, com todos os emitidos. As faturas em aberto continuam a pagar.`,
                    )
                  }
                  aoAlternarEmitido={(emitido) =>
                    executar(
                      () =>
                        emitido.canceladoEm
                          ? apiCartoes.reativarEmitido(cartao.id, emitido.id)
                          : apiCartoes.cancelarEmitido(cartao.id, emitido.id),
                      emitido.canceladoEm
                        ? `····${emitido.finalDoCartao} reativado.`
                        : `····${emitido.finalDoCartao} cancelado.`,
                    )
                  }
                />

                {emitindo?.id === cartao.id && (
                  <FormularioDeVirtual
                    cartao={cartao}
                    ocupado={ocupado}
                    aoCancelar={() => setEmitindo(null)}
                    aoGravar={async (corpo) => {
                      const deu = await executar(
                        () => apiCartoes.emitir(cartao.id, corpo),
                        `Cartão ····${corpo.finalDoCartao} criado.`,
                      )
                      if (deu) setEmitindo(null)
                    }}
                  />
                )}

                {selecionado === cartao.id && (
                  <PainelDeFaturas
                    cartao={cartao}
                    contas={apoio.contas}
                    formas={apoio.formas}
                    ocupado={ocupado}
                    aoMudar={recarregar}
                    aoAvisar={setAviso}
                  />
                )}
              </li>
            ))}
          </ul>
        </section>
      ))}

    </section>
  )
}

// ----------------------------------------------------------------------------

function LinhaDeCartao({ cartao, aberto, ocupado, aoAlternar, aoEncerrar, aoNovoVirtual, aoAlternarEmitido }) {
  // Pode ser negativo, e isso é informação: o limite estourou. O sistema não
  // trava (B-D48) — quem recusa a compra é o banco de verdade.
  const estourou = Number(cartao.limiteDisponivel) < 0
  const proporcao = Math.min(
    100,
    Math.max(0, (Number(cartao.limiteConsumido) / Number(cartao.limite)) * 100),
  )

  return (
    <div className="linha-recurso linha-cartao">
      <div className="identidade-conta">
        <button type="button" className="botao-texto expansor" onClick={aoAlternar}>
          <span className="seta">{aberto ? '▾' : '▸'}</span>
          <span className="nome-recurso">{cartao.nome}</span>
        </button>
        <span className="etiqueta etiqueta-fraca" title="Dia do vencimento da fatura">
          vence dia {cartao.diaVencimento}
        </span>
        {cartao.encerradoEm && <span className="etiqueta etiqueta-fraca">encerrado</span>}

      </div>

      {/* Os plásticos e virtuais, cada um com o próprio botão. Encerrar o
          contrato cancela todos (B-D65), mas cancelar um virtual descartado
          não deveria exigir matar o cartão inteiro. */}
      <ul className="lista-emitidos">
        {(cartao.emitidos ?? []).length === 0 && (
          <li className="texto-fraco">
            sem cartão emitido — ele não aparece em "como foi pago" até você criar um
          </li>
        )}
        {(cartao.emitidos ?? []).map((e) => (
          <li key={e.id} className={e.canceladoEm ? 'arquivada' : undefined}>
            <span className="nome-recurso">{e.nomeTitular}</span>
            <span className="texto-fraco">
              {' '}{e.tipo === 'FISICO' ? 'físico' : 'virtual'} ····{e.finalDoCartao}
            </span>
            {e.canceladoEm && <span className="etiqueta etiqueta-fraca">cancelado</span>}
            <button
              type="button" className="botao-texto"
              onClick={() => aoAlternarEmitido(e)}
              disabled={ocupado}
            >
              {e.canceladoEm ? 'Reativar' : 'Cancelar'}
            </button>
          </li>
        ))}
      </ul>

      <div className="acoes-linha">
        {!cartao.encerradoEm && (
          <button type="button" className="botao-texto" onClick={aoNovoVirtual} disabled={ocupado}>
            Novo cartão
          </button>
        )}
        <button
          type="button" className="botao-texto" onClick={aoEncerrar} disabled={ocupado}
          title={
            cartao.encerradoEm
              ? 'Reabre o contrato. Os cartões emitidos continuam cancelados — reative um a um.'
              : 'Cancela todos os cartões emitidos e impede compra nova. As faturas em aberto continuam a pagar.'
          }
        >
          {cartao.encerradoEm ? 'Reabrir' : 'Encerrar'}
        </button>
      </div>

      <div className="saldos">
        <span className={`saldo-principal ${estourou ? 'saida' : ''}`}>
          {dinheiro(cartao.limiteDisponivel)} livre
        </span>
        <span className="saldo-previsto">
          de {dinheiro(cartao.limite)} · usado {dinheiro(cartao.limiteConsumido)}
        </span>
        <span className="barra-limite" aria-hidden="true">
          <span
            className={`barra-limite-preenchida${estourou ? ' estourado' : ''}`}
            style={{ width: `${proporcao}%` }}
          />
        </span>
      </div>
    </div>
  )
}

/**
 * As faturas de um cartão, com fechar, reabrir e pagar.
 *
 * O ano é estado local: quem abre um cartão quer ver o ano corrente, e trocar
 * de ano num cartão não deveria mexer no outro.
 */
function PainelDeFaturas({ cartao, contas, formas, ocupado, aoMudar, aoAvisar }) {
  const [ano, setAno] = useState(HOJE.getFullYear())
  const [pagando, setPagando] = useState(null)
  const [vendo, setVendo] = useState(null)
  const [trabalhando, setTrabalhando] = useState(false)

  const buscar = useCallback(() => apiCartoes.faturas(cartao.id, ano), [cartao.id, ano])
  const { dados, carregando, recarregar } = useCarregar(buscar)

  async function agir(acao, mensagem) {
    setTrabalhando(true)
    try {
      const resposta = await acao()
      if (!resposta.ok) {
        aoAvisar({ texto: lerErro(resposta).mensagem, sucesso: false })
        return false
      }
      aoAvisar({ texto: mensagem, sucesso: true })
      await recarregar()
      await aoMudar()
      return true
    } finally {
      setTrabalhando(false)
    }
  }

  const lista = dados?.faturas ?? []

  return (
    <div className="painel-faturas">
      <div className="navegador-mes">
        <button type="button" className="botao-texto" onClick={() => setAno(ano - 1)}>‹</button>
        <span className="mes-atual">{ano}</span>
        <button type="button" className="botao-texto" onClick={() => setAno(ano + 1)}>›</button>
      </div>

      {carregando && <p className="carregando">Carregando faturas…</p>}

      {!carregando && lista.length === 0 && (
        <p className="texto-vazio">Nenhuma fatura gerada em {ano}.</p>
      )}

      {lista.length > 0 && (
        <table className="tabela-faturas">
          <thead>
            <tr>
              <th>Mês</th><th>Vencimento</th><th>Fecha</th><th>Situação</th>
              <th className="numerico">Total</th>
              <th className="numerico">Pago</th>
              <th className="numerico">A pagar</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {lista.map((f) => (
              <tr key={f.id} className={f.vencida ? 'previsto' : undefined}>
                <td>{f.mesReferencia}</td>
                <td>{data(f.vencimento)}</td>
                <td className="texto-fraco">{data(f.fechamentoPrevisto)}</td>
                <td><EtiquetaDeFatura fatura={f} /></td>
                <td className="numerico">{dinheiro(f.total)}</td>
                <td className="numerico">{dinheiro(f.pago)}</td>
                <td className="numerico">{dinheiro(f.aPagar)}</td>
                <td className="acoes-linha">
                  <button
                    type="button" className="botao-texto"
                    onClick={() => setVendo(vendo?.id === f.id ? null : f)}
                  >
                    {vendo?.id === f.id ? 'Fechar' : 'Ver'}
                  </button>
                  <button
                    type="button" className="botao-texto"
                    disabled={ocupado || trabalhando}
                    onClick={() =>
                      agir(
                        () =>
                          f.ciclo === 'ABERTA'
                            ? apiFaturas.fechar(f.id)
                            : apiFaturas.reabrir(f.id),
                        f.ciclo === 'ABERTA'
                          ? `Fatura de ${f.mesReferencia} fechada.`
                          : `Fatura de ${f.mesReferencia} reaberta.`,
                      )
                    }
                  >
                    {f.ciclo === 'ABERTA' ? 'Fechar' : 'Reabrir'}
                  </button>
                  <button
                    type="button" className="botao-texto"
                    disabled={ocupado || trabalhando || Number(f.aPagar) <= 0}
                    onClick={() => setPagando(f)}
                    title={
                      Number(f.aPagar) <= 0
                        ? 'Nada a pagar nesta fatura'
                        : 'Pagar total ou em parte — antecipar libera limite'
                    }
                  >
                    Pagar
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {vendo && <ExtratoDaFatura fatura={vendo} aoFechar={() => setVendo(null)} />}

      {pagando && (
        <FormularioDePagamento
          fatura={pagando}
          cartao={cartao}
          contas={contas.filter((c) => c.id !== cartao.id)}
          formas={formas}
          aoCancelar={() => setPagando(null)}
          aoGravar={async (corpo) => {
            const deu = await agir(
              () => apiFaturas.pagar(pagando.id, corpo),
              `Pagamento de ${dinheiro(corpo.valor)} registrado.`,
            )
            if (deu) setPagando(null)
          }}
        />
      )}
    </div>
  )
}

/**
 * O rótulo, composto dos TRÊS campos (B-D58).
 *
 * A ordem importa: vencida é o que mais urge, então ganha da quitação; e uma
 * fatura aberta com pagamento parcial mostra as duas coisas, porque foi
 * exatamente esse caso que impediu o estado de ser um enum só.
 */
function EtiquetaDeFatura({ fatura }) {
  if (fatura.vencida) {
    return <span className="etiqueta etiqueta-saida">vencida</span>
  }
  if (fatura.quitacao === 'QUITADA') {
    return <span className="etiqueta etiqueta-entrada">paga</span>
  }

  return (
    <>
      <span className="etiqueta etiqueta-fraca">
        {fatura.ciclo === 'ABERTA' ? 'aberta' : 'fechada'}
      </span>
      {fatura.quitacao === 'PARCIAL' && (
        <span className="etiqueta etiqueta-fraca" title="Pagamento parcial — o resto continua devido">
          parcial
        </span>
      )}
    </>
  )
}

/**
 * O extrato da fatura: cada gasto, com dono.
 *
 * Pedido dele nos testes de negócio — "vai mostrar os gastos de cada cartão
 * virtual, de cada cartão físico, no mesmo mês. Porque no final das contas é
 * uma fatura que está sendo paga."
 *
 * Sem a coluna do cartão, uma fatura com o físico, dois virtuais e o adicional
 * é uma pilha de gastos sem dono.
 */
function ExtratoDaFatura({ fatura, aoFechar }) {
  const buscar = useCallback(() => apiFaturas.lancamentos(fatura.id), [fatura.id])
  const { dados, carregando, erro } = useCarregar(buscar)

  const gastos = dados?.lancamentos ?? []

  return (
    <div className="extrato-fatura">
      <header className="cabecalho-painel">
        <h3>Fatura de {fatura.mesReferencia} · vence {data(fatura.vencimento)}</h3>
        <button type="button" className="botao-texto" onClick={aoFechar}>Fechar</button>
      </header>

      {erro && <p className="aviso" role="alert">{erro}</p>}
      {carregando && <p className="carregando">Carregando…</p>}

      {!carregando && gastos.length === 0 && (
        <p className="texto-vazio">Nenhum gasto nesta fatura.</p>
      )}

      {gastos.length > 0 && (
        <table className="tabela-lancamentos">
          <thead>
            <tr>
              <th>Compra</th><th>Descrição</th><th>Categoria</th><th>Cartão</th>
              <th className="numerico">Valor</th>
            </tr>
          </thead>
          <tbody>
            {gastos.map((g) => (
              <tr key={g.id}>
                <td>{data(g.dataCompetencia)}</td>
                <td>
                  {g.descricao || <span className="texto-fraco">sem descrição</span>}
                  {g.parcelaTotal && (
                    <span className="etiqueta etiqueta-fraca">
                      {g.parcelaNumero}/{g.parcelaTotal}
                    </span>
                  )}
                </td>
                <td>
                  {g.categoria?.nome}
                  {g.subcategoria && (
                    <span className="texto-fraco"> › {g.subcategoria.nome}</span>
                  )}
                </td>
                <td>
                  {g.cartao ? (
                    <>
                      {g.cartao.nomeTitular}
                      <span className="texto-fraco">
                        {' '}{g.cartao.tipo === 'FISICO' ? 'físico' : 'virtual'} ····{g.cartao.finalDoCartao}
                      </span>
                    </>
                  ) : (
                    <span className="texto-fraco">pagamento</span>
                  )}
                </td>
                <td className={`numerico ${g.tipo === 'ENTRADA' ? 'entrada' : 'saida'}`}>
                  {g.tipo === 'ENTRADA' ? '+' : '−'} {dinheiro(g.valor)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

/**
 * Um cartão novo — físico ou virtual — debaixo do mesmo contrato.
 *
 * Ele divide o limite e a fatura com os outros: no fim é uma fatura só que está
 * sendo paga. O que ele traz de próprio são os quatro dígitos, que é como você
 * vai reconhecê-lo na hora de lançar o gasto.
 *
 * O tipo é escolha e não fixo em VIRTUAL, e isso conserta um beco: contratos
 * criados antes de B-D63 nasceram SEM nenhum emitido, e um cartão sem plástico
 * não recebe compra nenhuma. Sem o físico aqui, eles ficariam presos.
 */
function FormularioDeVirtual({ cartao, ocupado, aoGravar, aoCancelar }) {
  return (
    <form
      className="formulario-bloco"
      onSubmit={(e) => {
        e.preventDefault()
        const d = new FormData(e.target)
        aoGravar({
          nomeTitular: d.get('nomeTitular').trim(),
          tipo: d.get('tipo'),
          finalDoCartao: d.get('finalDoCartao').trim(),
        })
      }}
    >
      <h3>Novo cartão — {cartao.nome}</h3>

      <div className="campos-lado-a-lado">
        <label>
          Tipo
          <select name="tipo" defaultValue="VIRTUAL">
            <option value="VIRTUAL">Virtual</option>
            <option value="FISICO">Físico</option>
          </select>
        </label>

        <label>
          Para quê ou de quem
          <input name="nomeTitular" required maxLength={60} placeholder="Assinaturas" />
        </label>

        <label>
          4 últimos dígitos
          <input
            name="finalDoCartao" inputMode="numeric" required
            pattern="[0-9]{4}" maxLength={4} placeholder="9012"
          />
        </label>
      </div>

      <p className="dica">
        Ele divide o <strong>mesmo limite</strong> e cai na{' '}
        <strong>mesma fatura</strong> do {cartao.nome}. No extrato da fatura você
        vê o gasto de cada um separado — mas é uma fatura só que se paga.
      </p>

      <div className="acoes-linha">
        <button type="submit" className="botao-principal botao-pequeno" disabled={ocupado}>
          Criar cartão
        </button>
        <button type="button" className="botao-texto" onClick={aoCancelar}>Cancelar</button>
      </div>
    </form>
  )
}

function FormularioDeCartao({ contas, ocupado, aoGravar, aoCancelar }) {
  const [contaBancoId, setContaBancoId] = useState('')

  return (
    <form
      className="formulario-bloco"
      onSubmit={(e) => {
        e.preventDefault()
        const d = new FormData(e.target)
        aoGravar({
          contaBancoId,
          nome: d.get('nome').trim(),
          limite: d.get('limite').trim(),
          finalDoCartao: d.get('finalDoCartao').trim(),
          diaVencimento: Number(d.get('diaVencimento')),
          diasParaFechamento: Number(d.get('diasParaFechamento')),
        })
      }}
    >
      <h3>Novo cartão de crédito</h3>

      <div className="campos-lado-a-lado">
        <label>
          Banco
          <select value={contaBancoId} required onChange={(e) => setContaBancoId(e.target.value)}>
            <option value="" disabled>Escolha…</option>
            {contas.map((c) => <option key={c.id} value={c.id}>{c.nome}</option>)}
          </select>
        </label>

        <label>
          Nome do cartão
          <input name="nome" required maxLength={60} placeholder="Black" />
        </label>

        <label>
          Limite
          <input name="limite" inputMode="decimal" required placeholder="10000.00" />
        </label>

        <label>
          4 últimos dígitos
          <input
            name="finalDoCartao" inputMode="numeric" required
            pattern="[0-9]{4}" maxLength={4} placeholder="4352"
          />
        </label>
      </div>

      <div className="campos-lado-a-lado">
        <label>
          Dia do vencimento
          <input type="number" name="diaVencimento" min={1} max={31} required defaultValue={10} />
        </label>

        <label>
          Fecha quantos dias antes
          <input type="number" name="diasParaFechamento" min={0} max={28} required defaultValue={5} />
        </label>
      </div>

      <p className="dica">
        O cartão fica <strong>embaixo de uma conta de banco</strong>, e contas
        físicas — carteira, gaveta, cofre — não aparecem na lista: papel moeda
        não emite crédito.
      </p>

      <p className="dica">
        O fechamento é calculado a partir do vencimento, e recua para a{' '}
        <strong>sexta anterior</strong> se cair em fim de semana. Cada banco tem
        sua regra, então o número fica por cartão — e você ainda pode fechar
        qualquer fatura na mão.
      </p>

      <p className="dica">
        Os quatro dígitos são do <strong>cartão físico</strong>, criado junto com
        o contrato — é por eles que você vai reconhecê-lo na hora de lançar um
        gasto. Cartões virtuais entram depois, cada um com os seus.
      </p>

      <p className="dica">
        O limite é <strong>informativo</strong>: o sistema mostra quanto foi
        consumido, mas não recusa compra nenhuma. Quem recusa é o banco.
      </p>

      <div className="acoes-linha">
        <button type="submit" className="botao-principal botao-pequeno" disabled={ocupado}>
          Criar cartão
        </button>
        <button type="button" className="botao-texto" onClick={aoCancelar}>Cancelar</button>
      </div>
    </form>
  )
}

function FormularioDePagamento({ fatura, cartao, contas, formas, aoGravar, aoCancelar }) {
  const [contaOrigemId, setContaOrigemId] = useState('')
  const [formaPagamento, setFormaPagamento] = useState('')

  const origem = contas.find((c) => c.id === contaOrigemId)
  const formasDisponiveis = formasDaContaNoSentido(
    formas,
    origem?.formasPagamento ?? [],
    'SAIDA',
  )

  return (
    <form
      className="formulario-bloco"
      onSubmit={(e) => {
        e.preventDefault()
        const d = new FormData(e.target)
        aoGravar({
          contaOrigemId,
          valor: d.get('valor').trim(),
          dataCaixa: d.get('dataCaixa'),
          formaPagamento: formaPagamento || null,
        })
      }}
    >
      <h3>Pagar a fatura de {fatura.mesReferencia} — {cartao.nome}</h3>

      <div className="campos-lado-a-lado">
        <label>
          Pagar com a conta
          <select
            value={contaOrigemId} required
            onChange={(e) => {
              setContaOrigemId(e.target.value)
              setFormaPagamento('')
            }}
          >
            <option value="" disabled>Escolha…</option>
            {contas.map((c) => (
              <option key={c.id} value={c.id}>{c.nome} — {dinheiro(c.saldo)}</option>
            ))}
          </select>
        </label>

        <label>
          Valor
          <input
            name="valor" inputMode="decimal" required
            defaultValue={fatura.aPagar} placeholder="0,00"
          />
        </label>

        <label>
          Data
          <input
            type="date" name="dataCaixa" required
            defaultValue={new Date().toISOString().slice(0, 10)}
          />
        </label>

        <label>
          Como pagou
          <select
            value={formaPagamento}
            disabled={formasDisponiveis.length === 0}
            onChange={(e) => setFormaPagamento(e.target.value)}
          >
            <option value="">(não informar)</option>
            {formasDisponiveis.map((f) => (
              <option key={f.valor} value={f.valor}>{f.nome}</option>
            ))}
          </select>
        </label>
      </div>

      <p className="dica">
        O valor vem preenchido com o total a pagar, mas você pode{' '}
        <strong>pagar em parte</strong>. E a conta de origem pode ser de{' '}
        <strong>outro banco</strong> — pagar a fatura do {cartao.banco?.nome} com
        outra conta, via boleto, é caso comum.
      </p>

      {fatura.ciclo === 'ABERTA' && (
        <p className="dica">
          Esta fatura ainda está <strong>aberta</strong>, e pagar agora é
          antecipação: o valor pago volta como limite disponível na hora. É assim
          que se libera limite para uma compra que não cabe.
        </p>
      )}

      <div className="acoes-linha">
        <button type="submit" className="botao-principal botao-pequeno">Pagar</button>
        <button type="button" className="botao-texto" onClick={aoCancelar}>Cancelar</button>
      </div>
    </form>
  )
}
