import {
  ambienteAtual,
  guardarSessao,
  tokenAcesso,
  tokenRenovacao,
} from './sessao.js'

// =============================================================================
// Conversa com a API
// =============================================================================
// Nenhuma tela chama fetch direto. Duas regras vivem aqui e em nenhum outro
// lugar: como um erro da API vira objeto, e quando um 401 merece renovação.
// =============================================================================

/**
 * Nunca lança por status HTTP. Devolve sempre { ok, status, corpo } para que a
 * tela trate 401/409/400 como resposta e não como exceção — só falha de rede
 * vira erro de verdade.
 */
export async function pedir(caminho, opcoes = {}) {
  const cabecalhos = { 'Content-Type': 'application/json' }
  if (opcoes.autenticado) {
    cabecalhos.Authorization = `Bearer ${tokenAcesso()}`
  }

  const resposta = await fetch(caminho, {
    method: opcoes.metodo || 'GET',
    headers: cabecalhos,
    body: opcoes.corpo ? JSON.stringify(opcoes.corpo) : undefined,
  })

  const texto = await resposta.text()
  return {
    ok: resposta.ok,
    status: resposta.status,
    corpo: texto ? JSON.parse(texto) : {},
  }
}

/**
 * Chamada autenticada com renovação transparente.
 *
 * O 401 por token expirado não deve devolver a pessoa ao login: tenta-se uma
 * renovação e repete-se a chamada. É aqui que o contrato do I-15 aparece — o
 * ambienteId vai junto, senão a sessão renovada voltaria ao primeiro ambiente
 * e o seletor "pularia" sozinho.
 *
 * A repetição acontece no máximo uma vez. Se o 401 persistir depois de uma
 * renovação bem-sucedida, o problema não é o token, e insistir viraria laço.
 */
export async function pedirComRenovacao(caminho, opcoes = {}) {
  const autenticado = { ...opcoes, autenticado: true }

  const primeira = await pedir(caminho, autenticado)
  if (primeira.status !== 401) return primeira

  const renovacao = await pedir('/api/auth/renovar', {
    metodo: 'POST',
    corpo: { tokenRenovacao: tokenRenovacao(), ambienteId: ambienteAtual() },
  })
  if (!renovacao.ok) return primeira // a sessão acabou de verdade

  guardarSessao(renovacao.corpo)
  return pedir(caminho, autenticado)
}

/**
 * Traduz o corpo de erro da API — o contrato do I-12: `erro` sempre existe;
 * `campos` (campo -> mensagem) só na validação. Devolve a mensagem a mostrar
 * e quais campos marcar, sem tocar no DOM.
 */
export function lerErro(resposta) {
  const corpo = resposta?.corpo ?? {}
  const campos = corpo.campos ?? null
  if (campos) {
    const primeiro = Object.keys(campos)[0]
    return { mensagem: campos[primeiro], campos }
  }
  return {
    mensagem: corpo.erro || 'Não foi possível completar a operação.',
    campos: null,
  }
}
