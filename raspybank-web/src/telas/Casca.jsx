import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAutenticacao } from '../contexto/Autenticacao.jsx'

// =============================================================================
// T-03 — Casca autenticada
// =============================================================================
// Topo, menu e a área que muda. Tudo o que vier das fatias seguintes entra no
// <Outlet/>, sem mexer aqui.
//
// Todo item do menu navega. O acinzentado de "Cartões" e a nota que o explicava
// saíram na V12, quando o cartão passou a existir de verdade — placeholder que
// sobrevive à feature vira mentira na tela, e ali ele aparecia DUAS vezes: o
// link real e o fantasma logo abaixo.
// =============================================================================

const ITENS = [
  { rotulo: 'Mapa de gastos', caminho: '/mapa' },
  { rotulo: 'Lançamentos', caminho: '/lancamentos' },
  { rotulo: 'Categorias', caminho: '/categorias' },
  { rotulo: 'Contas', caminho: '/contas' },
  { rotulo: 'Cartões', caminho: '/cartoes' },
  { rotulo: 'Perfil', caminho: '/perfil' },
]

export default function Casca() {
  const { perfil, sair, trocarAmbiente } = useAutenticacao()
  const navegar = useNavigate()

  const ambientes = perfil?.ambientes ?? []
  const atual = perfil?.ambienteAtual ?? ''
  const nomeDoAtual = ambientes.find((a) => a.id === atual)?.nome

  /**
   * Trocar de ambiente e ir para o mapa, com os dados do ambiente novo.
   *
   * Antes, a troca atualizava o token e o perfil e deixava a tela aberta
   * exibindo os dados do ambiente ANTERIOR — só um recarregar do navegador
   * corrigia. A tela mentia em silêncio, que é o pior jeito de errar.
   *
   * São duas coisas, e as duas são necessárias:
   *
   *   1. Navegar para o mapa. Foi o pedido dele, e faz sentido: o mapa é a
   *      resposta de "como está este ambiente".
   *   2. A `key` no <Outlet/> abaixo. Sem ela, quem JÁ estivesse no mapa não
   *      veria nada acontecer — o React Router não remonta um componente ao
   *      navegar para a rota em que ele já está.
   */
  async function trocarEIrParaOMapa(ambienteId) {
    const resposta = await trocarAmbiente(ambienteId)
    if (resposta.ok) navegar('/mapa')
  }

  return (
    <div className="casca">
      <header className="topo">
        <span className="marca-pequena">RaspyBank</span>

        <div className="seletor-ambiente">
          <label htmlFor="ambiente" className="rotulo-ambiente">Ambiente</label>
          <select
            id="ambiente"
            value={atual}
            // Com um ambiente só, o seletor não tem o que oferecer.
            disabled={ambientes.length < 2}
            onChange={(e) => trocarEIrParaOMapa(e.target.value)}
          >
            {ambientes.map((ambiente) => (
              <option key={ambiente.id} value={ambiente.id}>{ambiente.nome}</option>
            ))}
          </select>
        </div>

        <div className="acoes-topo">
          <span className="saudacao">{nomeDoAtual ? `Você está em ${nomeDoAtual}` : ''}</span>
          <button type="button" className="botao-texto" onClick={sair}>Sair</button>
        </div>
      </header>

      <div className="corpo">
        <nav className="menu">
          {ITENS.map(({ rotulo, caminho }) => (
            <NavLink
              key={caminho}
              to={caminho}
              className={({ isActive }) => (isActive ? 'item-menu ativo' : 'item-menu')}
            >
              {rotulo}
            </NavLink>
          ))}
        </nav>

        <main className="centro">
          {/*
            A `key` amarra a tela ao ambiente: trocar de ambiente REMONTA o que
            estiver aberto, e o `useCarregar` de cada tela busca de novo.

            Remontar perde o estado dos formulários, e aqui isso é desejado:
            um lançamento meio preenchido para a Casa não deveria sobreviver à
            troca para o PJ — ele iria para a conta errada.
          */}
          <Outlet key={atual} />
        </main>
      </div>
    </div>
  )
}
