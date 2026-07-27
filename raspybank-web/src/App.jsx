import { BrowserRouter, Navigate, Outlet, Route, Routes } from 'react-router-dom'
import { ProvedorDeAutenticacao, useAutenticacao } from './contexto/Autenticacao.jsx'
import Casca from './telas/Casca.jsx'
import Categorias from './telas/Categorias.jsx'
import Contas from './telas/Contas.jsx'
import Entrada from './telas/Entrada.jsx'
import Lancamentos from './telas/Lancamentos.jsx'
import MapaDeGastos from './telas/MapaDeGastos.jsx'

// =============================================================================
// Rotas
// =============================================================================
// O protótipo escondia e mostrava <div>s. Com rotas de verdade a pessoa ganha
// o botão voltar do navegador, endereço que dá para guardar nos favoritos e
// recarregar sem cair no login. Foi por isso que o react-router entrou.
// =============================================================================

export default function App() {
  return (
    <ProvedorDeAutenticacao>
      <BrowserRouter>
        <Routes>
          <Route path="/entrar" element={<SomenteVisitante />}>
            <Route index element={<Entrada />} />
          </Route>

          <Route element={<SomenteAutenticado />}>
            <Route element={<Casca />}>
              <Route path="/mapa" element={<MapaDeGastos />} />
              <Route path="/lancamentos" element={<Lancamentos />} />
              <Route path="/categorias" element={<Categorias />} />
              <Route path="/contas" element={<Contas />} />
              <Route index element={<Navigate to="/mapa" replace />} />
            </Route>
          </Route>

          {/* Endereço desconhecido não merece tela de erro num app de casa. */}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </ProvedorDeAutenticacao>
  )
}

/**
 * Enquanto a sessão guardada está sendo conferida com o servidor não dá para
 * decidir o destino. Mostrar o login nesse intervalo faria a tela piscar e,
 * pior, sugeriria que a sessão caiu quando ela não caiu.
 */
function Aguardando() {
  return <p className="carregando">Carregando…</p>
}

function SomenteAutenticado() {
  const { perfil, carregando } = useAutenticacao()
  if (carregando) return <Aguardando />
  return perfil ? <Outlet /> : <Navigate to="/entrar" replace />
}

function SomenteVisitante() {
  const { perfil, carregando } = useAutenticacao()
  if (carregando) return <Aguardando />
  return perfil ? <Navigate to="/" replace /> : <Outlet />
}
