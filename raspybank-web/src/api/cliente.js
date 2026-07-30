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
 * A renovação em voo, compartilhada por todo mundo que precisar dela.
 *
 * O token de renovação é ROTATIVO (A11): cada uso o consome e gera outro. Sem
 * esta variável, três chamadas paralelas que levam 401 ao mesmo tempo disparam
 * TRÊS renovações com o mesmo token — a primeira o consome e as outras duas
 * chegam apresentando um token já usado.
 *
 * Do ponto de vista do servidor isso é indistinguível de roubo, e ele faz o que
 * deve: revoga a família inteira. O efeito é ser deslogado de todos os
 * dispositivos sem ninguém ter atacado nada.
 *
 * Reproduzido contra o servidor real em 28/07/2026: três renovações em paralelo
 * com o mesmo token deram 200, 401 e 401, e a família ficou revogada. A tela de
 * lançamentos dispara exatamente três chamadas num `Promise.all`.
 *
 * A promessa é guardada e não o resultado, de propósito: quem chegar durante a
 * renovação espera a MESMA, em vez de começar outra. É o mesmo padrão do cache
 * de `formasPagamento`.
 */
let renovacaoEmVoo = null

function renovar() {
  if (!renovacaoEmVoo) {
    renovacaoEmVoo = pedir('/api/auth/renovar', {
      metodo: 'POST',
      // O ambienteId vai junto por causa do I-15: sem ele a sessão renovada
      // voltaria ao primeiro ambiente e o seletor "pularia" sozinho.
      corpo: { tokenRenovacao: tokenRenovacao(), ambienteId: ambienteAtual() },
    })
      .then((resposta) => {
        if (resposta.ok) guardarSessao(resposta.corpo)
        return resposta
      })
      // Liberar SEMPRE, inclusive em falha de rede: deixar a promessa presa
      // faria toda chamada seguinte esperar por uma renovação que nunca volta.
      .finally(() => {
        renovacaoEmVoo = null
      })
  }
  return renovacaoEmVoo
}

/**
 * Chamada autenticada com renovação transparente.
 *
 * O 401 por token expirado não deve devolver a pessoa ao login: tenta-se uma
 * renovação e repete-se a chamada. A repetição acontece no máximo uma vez — se
 * o 401 persistir depois de uma renovação bem-sucedida, o problema não é o
 * token, e insistir viraria laço.
 */
export async function pedirComRenovacao(caminho, opcoes = {}) {
  const autenticado = { ...opcoes, autenticado: true }

  // Guardado ANTES de sair: é ele que diz, no retorno, se alguém já renovou
  // enquanto esta chamada estava no ar.
  const tokenUsado = tokenAcesso()

  const primeira = await pedir(caminho, autenticado)
  if (primeira.status === 403 && primeira.corpo?.motivo === 'SEM_ACESSO_AO_AMBIENTE') {
    return voltarParaAmbienteProprio(primeira)
  }
  if (primeira.status !== 401) return primeira

  // Outra chamada já renovou enquanto esta viajava. Renovar de novo consumiria
  // um token que acabou de nascer, sem necessidade — basta repetir com o novo.
  if (tokenAcesso() !== tokenUsado) {
    return pedir(caminho, autenticado)
  }

  const renovacao = await renovar()
  if (!renovacao.ok) return primeira // a sessão acabou de verdade

  return pedir(caminho, autenticado)
}

/**
 * O 403 de B-D83: o acesso ao ambiente do token foi revogado.
 *
 * O RLS já cortou os dados — nada indevido apareceu —, mas sem reação a tela
 * ficaria vazia e sem explicação: contas zeradas, mapa em branco, nenhum erro.
 * A saída é a renovação, que é a rota de fuga deliberada: o servidor
 * (`ambienteParaRenovacao`) percebe que o ambiente declarado não é mais da
 * pessoa e emite o token num ambiente que É dela, em silêncio.
 *
 * O recarregar da página é proposital, pelo mesmo motivo da `key` no Outlet da
 * casca: a tela aberta pertence ao ambiente que a pessoa PERDEU, e qualquer
 * formulário meio preenchido nela iria para o lugar errado. A explicação
 * atravessa o recarregar pelo sessionStorage e a casca a mostra uma vez.
 */
export const AVISO_DE_SESSAO = 'raspybank.avisoDeSessao'

async function voltarParaAmbienteProprio(respostaOriginal) {
  const renovacao = await renovar()
  if (renovacao.ok) {
    sessionStorage.setItem(
      AVISO_DE_SESSAO,
      'Seu acesso ao ambiente em que você estava foi removido. Você voltou para um ambiente seu.',
    )
    window.location.replace('/mapa')
  }
  // Renovação recusada = a sessão acabou de verdade; o 403 sobe para a tela e
  // o fluxo normal de sessão expirada assume.
  return respostaOriginal
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
