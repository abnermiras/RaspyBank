import { useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { AVISO_DE_SESSAO } from '../api/cliente.js'
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

  /**
   * O recado que atravessou um recarregar — hoje, só um existe: o acesso ao
   * ambiente em que a pessoa estava foi revogado (B-D83) e ela voltou para um
   * ambiente dela. Lido uma vez e apagado: recarregar de novo não deve
   * repetir um aviso que já foi dado.
   */
  const [avisoDeSessao, setAvisoDeSessao] = useState(() => {
    const texto = sessionStorage.getItem(AVISO_DE_SESSAO)
    sessionStorage.removeItem(AVISO_DE_SESSAO)
    return texto
  })

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
              <option key={ambiente.id} value={ambiente.id}>
                {/* O sufixo diferencia o emprestado do próprio: os dois estão
                    na mesma lista de propósito (B-D74), e sem a marca a pessoa
                    lançaria "no lugar errado" sem nenhum indício. */}
                {ambiente.dono ? ambiente.nome : `${ambiente.nome} · compartilhado`}
              </option>
            ))}
          </select>
        </div>

        <div className="acoes-topo">
          <span className="saudacao">{nomeDoAtual ? `Você está em ${nomeDoAtual}` : ''}</span>
          <button type="button" className="botao-texto" onClick={sair}>Sair</button>
        </div>
      </header>

      {avisoDeSessao && (
        <p className="aviso" role="alert">
          {avisoDeSessao}
          <button
            type="button" className="botao-texto"
            onClick={() => setAvisoDeSessao(null)}
          >
            Entendi
          </button>
        </p>
      )}

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
