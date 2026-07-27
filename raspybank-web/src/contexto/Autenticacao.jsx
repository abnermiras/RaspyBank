import { createContext, useCallback, useContext, useEffect, useState } from 'react'
import { pedir, pedirComRenovacao } from '../api/cliente.js'
import {
  guardarAcesso,
  guardarAmbiente,
  guardarSessao,
  limparSessao,
  tokenAcesso,
} from '../api/sessao.js'

// =============================================================================
// Estado de sessão
// =============================================================================
// O protótipo relia /api/perfil depois de toda mudança, e essa escolha fica:
// o servidor é a fonte da verdade sobre qual ambiente está ativo, porque quem
// decide isso é o token — não o que o cliente lembra. O localStorage aqui é
// só uma lembrança, e ela perde da resposta do servidor sempre.
// =============================================================================

const Contexto = createContext(null)

export function ProvedorDeAutenticacao({ children }) {
  const [perfil, setPerfil] = useState(null)
  const [carregando, setCarregando] = useState(Boolean(tokenAcesso()))

  /** Relê o perfil. Se o servidor recusar, a sessão local não vale mais nada. */
  const carregarPerfil = useCallback(async () => {
    const resposta = await pedirComRenovacao('/api/perfil')
    if (!resposta.ok) {
      limparSessao()
      setPerfil(null)
      return null
    }
    setPerfil(resposta.corpo)
    // O ambiente do token manda sobre o que estiver guardado no cliente.
    guardarAmbiente(resposta.corpo.ambienteAtual)
    return resposta.corpo
  }, [])

  // Sessão guardada de uma visita anterior: tenta entrar direto.
  useEffect(() => {
    if (!tokenAcesso()) return
    carregarPerfil().finally(() => setCarregando(false))
  }, [carregarPerfil])

  const entrar = useCallback(
    async (email, senha) => {
      const resposta = await pedir('/api/auth/login', {
        metodo: 'POST',
        corpo: { email, senha },
      })
      if (!resposta.ok) return resposta

      guardarSessao(resposta.corpo)
      await carregarPerfil()
      return resposta
    },
    [carregarPerfil],
  )

  /** Sair (I-14): encerra SÓ este dispositivo. As outras sessões seguem. */
  const sair = useCallback(async () => {
    try {
      await pedirComRenovacao('/api/auth/logout', { metodo: 'POST' })
    } catch {
      // Sem servidor, a saída local basta — não prender ninguém dentro do app.
    } finally {
      limparSessao()
      setPerfil(null)
    }
  }, [])

  /** Troca de ambiente (I-15): novo token de acesso com o outro recorte. */
  const trocarAmbiente = useCallback(
    async (ambienteId) => {
      const resposta = await pedirComRenovacao('/api/sessao/ambiente', {
        metodo: 'POST',
        corpo: { ambienteId },
      })
      // Deu errado ou deu certo, o perfil é relido: em qualquer caso a tela
      // termina mostrando o estado real do servidor, nunca um palpite.
      if (resposta.ok) {
        guardarAcesso(resposta.corpo.tokenAcesso, resposta.corpo.ambienteId)
      }
      await carregarPerfil()
      return resposta
    },
    [carregarPerfil],
  )

  const valor = { perfil, carregando, entrar, sair, trocarAmbiente, carregarPerfil }
  return <Contexto.Provider value={valor}>{children}</Contexto.Provider>
}

export function useAutenticacao() {
  const contexto = useContext(Contexto)
  if (!contexto) {
    throw new Error('useAutenticacao precisa estar dentro de ProvedorDeAutenticacao')
  }
  return contexto
}
