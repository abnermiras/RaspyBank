import { NavLink, Outlet } from 'react-router-dom'
import { useAutenticacao } from '../contexto/Autenticacao.jsx'

// =============================================================================
// T-03 — Casca autenticada
// =============================================================================
// Topo, menu e a área que muda. Tudo o que vier das fatias seguintes entra no
// <Outlet/>, sem mexer aqui.
//
// Depois da fatia 6, só "Cartões" segue acinzentado — ele ficou fora do mínimo
// aceitável de propósito e não tem API nem tela. Todo o resto navega.
// =============================================================================

const ITENS = [
  { rotulo: 'Mapa de gastos', caminho: '/mapa' },
  { rotulo: 'Lançamentos', caminho: '/lancamentos' },
  { rotulo: 'Categorias', caminho: '/categorias' },
  { rotulo: 'Contas', caminho: '/contas' },
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

          <span
            className="item-menu desabilitado"
            title="Cartão de crédito ficou fora do mínimo aceitável: ele tem fatura, e fatura é um assunto próprio."
          >
            Cartões
          </span>

          <p className="nota-menu">
            Cartões vêm depois — fatura é assunto próprio, e o mínimo aceitável
            fecha sem ela.
          </p>
        </nav>

        <main className="centro">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
