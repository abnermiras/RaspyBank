import { NavLink, Outlet } from 'react-router-dom'
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
]

export default function Casca() {
  const { perfil, sair, trocarAmbiente } = useAutenticacao()

  const ambientes = perfil?.ambientes ?? []
  const atual = perfil?.ambienteAtual ?? ''
  const nomeDoAtual = ambientes.find((a) => a.id === atual)?.nome

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
            onChange={(e) => trocarAmbiente(e.target.value)}
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
          <Outlet />
        </main>
      </div>
    </div>
  )
}
