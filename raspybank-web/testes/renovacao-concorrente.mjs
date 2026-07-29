// =============================================================================
// Uma renovação de token por vez, mesmo com chamadas em paralelo
// =============================================================================
// Este é o primeiro teste automatizado do frontend, e ele existe por um defeito
// concreto — não por completude.
//
// -----------------------------------------------------------------------------
// O DEFEITO QUE ELE GUARDA
// -----------------------------------------------------------------------------
// O token de renovação é ROTATIVO (A11): cada uso o consome e gera outro. As
// telas disparam várias chamadas em paralelo — a T-08 faz um `Promise.all` de
// contas, categorias e cartões. Se o token de acesso venceu, as três levam 401
// ao mesmo tempo e, sem coordenação, cada uma dispara a própria renovação com o
// MESMO token de renovação.
//
// A primeira o consome. As outras chegam apresentando um token já usado, e o
// servidor — que não tem como distinguir reuso legítimo de roubo — revoga a
// família inteira, como deve. O efeito é ser deslogado de todos os dispositivos
// sem ninguém ter atacado nada.
//
// Reproduzido contra o servidor real em 28/07/2026: três renovações paralelas
// com o mesmo token deram 200, 401 e 401, e a família ficou revogada.
//
// -----------------------------------------------------------------------------
// POR QUE NÃO HÁ FRAMEWORK DE TESTE AQUI
// -----------------------------------------------------------------------------
// Nenhum. O Node já roda ESM e já traz `assert`; o módulo só precisa de
// `localStorage` e `fetch`, que são dublados abaixo em vinte linhas.
//
// Trazer vitest ou jest custaria dezenas de pacotes novos, e a regra de
// dependências deste projeto (docs/mapa-telas.md §7) existe justamente para que
// cada pacote novo seja uma decisão. Um defeito não justifica uma árvore.
//
// Quando o frontend tiver muitos testes, essa conta muda e a decisão se revisita
// — com a data de corte do NPM_CORTE respeitada, como sempre.
//
// -----------------------------------------------------------------------------
// O QUE ESTE TESTE NÃO ALCANÇA
// -----------------------------------------------------------------------------
// Ele exercita o módulo, não o navegador. A validação de formulário do HTML —
// que já escondeu um defeito real, o `pattern` do cartão — continua fora do
// alcance de qualquer teste que não abra uma página de verdade.
//
// Rodar:  make web-test
// =============================================================================

import assert from 'node:assert/strict'

// -----------------------------------------------------------------------------
// Os dublês: localStorage e um servidor que se comporta como o de verdade
// -----------------------------------------------------------------------------
const guardado = new Map()

globalThis.localStorage = {
  getItem: (chave) => (guardado.has(chave) ? guardado.get(chave) : null),
  setItem: (chave, valor) => guardado.set(chave, String(valor)),
  removeItem: (chave) => guardado.delete(chave),
}

guardado.set('raspybank.tokenAcesso', 'ACESSO-1')
guardado.set('raspybank.tokenRenovacao', 'RENOVACAO-1')
guardado.set('raspybank.ambienteId', 'amb-1')

let renovacoes = 0
const espera = (ms) => new Promise((r) => setTimeout(r, ms))

globalThis.fetch = async (caminho, opcoes) => {
  const ok = (corpo) => ({
    ok: true,
    status: 200,
    text: async () => JSON.stringify(corpo),
  })
  const naoAutorizado = (erro) => ({
    ok: false,
    status: 401,
    text: async () => JSON.stringify({ erro }),
  })

  if (caminho === '/api/auth/renovar') {
    renovacoes += 1
    const usado = JSON.parse(opcoes.body).tokenRenovacao

    // O servidor de verdade: token rotativo. Apresentar um já consumido é
    // indistinguível de roubo, e derruba a família.
    if (usado !== 'RENOVACAO-1') {
      return naoAutorizado('Credenciais invalidas')
    }
    guardado.set('raspybank.tokenRenovacao', 'RENOVACAO-2')

    // A renovação leva tempo. É durante esta janela que as outras chamadas
    // chegam — sem ela, o defeito não se manifesta.
    await espera(30)

    return ok({
      tokenAcesso: 'ACESSO-2',
      tokenRenovacao: 'RENOVACAO-2',
      ambienteId: 'amb-1',
    })
  }

  const enviado = (opcoes.headers.Authorization || '').replace('Bearer ', '')
  return enviado === 'ACESSO-2' ? ok({ recurso: caminho }) : naoAutorizado('expirado')
}

// -----------------------------------------------------------------------------
// O cenário: exatamente o que a tela de lançamentos faz ao abrir
// -----------------------------------------------------------------------------
const { pedirComRenovacao } = await import('../src/api/cliente.js')

const respostas = await Promise.all([
  pedirComRenovacao('/api/contas'),
  pedirComRenovacao('/api/categorias'),
  pedirComRenovacao('/api/cartoes'),
])

assert.equal(
  renovacoes,
  1,
  `Três chamadas paralelas dispararam ${renovacoes} renovações.` +
    ' Cada uma além da primeira apresenta um token já consumido, e o servidor' +
    ' revoga a família inteira — a pessoa é deslogada de todos os dispositivos.',
)

assert.ok(
  respostas.every((r) => r.ok),
  'Toda chamada que levou 401 precisa se recuperar com o token novo,' +
    ' inclusive as que esperaram a renovação de outra.',
)

assert.equal(
  guardado.get('raspybank.tokenAcesso'),
  'ACESSO-2',
  'A sessão renovada precisa ficar guardada, senão a próxima chamada renova de novo.',
)

console.log('ok  uma renovação para três chamadas paralelas, e as três se recuperaram')
