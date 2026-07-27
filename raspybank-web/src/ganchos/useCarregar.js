import { useCallback, useEffect, useState } from 'react'
import { lerErro } from '../api/cliente.js'

/**
 * Carrega algo da API e devolve { dados, carregando, erro, recarregar }.
 *
 * As quatro telas da fatia 6 fazem o mesmo ritual: buscar, mostrar "carregando",
 * tratar falha, e recarregar depois de gravar. Repetir isso quatro vezes seria
 * quatro chances de esquecer um dos passos — normalmente o tratamento de falha,
 * que é o que menos se testa à mão.
 *
 * `buscar` precisa ser estável (useCallback na tela), senão isto vira laço.
 */
export function useCarregar(buscar) {
  const [dados, setDados] = useState(null)
  const [carregando, setCarregando] = useState(true)
  const [erro, setErro] = useState(null)

  const recarregar = useCallback(async () => {
    setCarregando(true)
    try {
      const resposta = await buscar()
      if (resposta.ok) {
        setDados(resposta.corpo)
        setErro(null)
      } else {
        setErro(lerErro(resposta).mensagem)
      }
    } catch {
      setErro('Servidor indisponível.')
    } finally {
      setCarregando(false)
    }
  }, [buscar])

  useEffect(() => {
    recarregar()
  }, [recarregar])

  return { dados, carregando, erro, recarregar }
}
