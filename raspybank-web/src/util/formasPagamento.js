import { pedirComRenovacao } from '../api/cliente.js'

// =============================================================================
// Formas de pagamento — lidas do servidor, nunca escritas aqui
// =============================================================================
// A versão anterior deste arquivo tinha a lista das seis formas em JavaScript.
// Funcionava, e estava errada pelo mesmo motivo que qualquer segunda cópia:
// quando a regra de "qual forma serve a qual sentido" nasceu, ela passaria a
// existir em três lugares — nas onze linhas de `forma_pagamento_sentido`, no
// enum Java e aqui. Três cópias divergem, e o sintoma seria o pior possível: o
// seletor oferecendo uma opção que o servidor recusa.
//
// Agora a lista vem de GET /api/formas-pagamento, com os sentidos que cada uma
// aceita. Forma nova é uma migração e um valor no enum; esta tela não muda.
//
// O cache é uma promessa e não um objeto, de propósito: duas telas montando ao
// mesmo tempo compartilham a MESMA requisição em voo, em vez de dispararem
// duas. E é para o tempo de vida da aba — vocabulário fixo não muda no meio da
// sessão, então revalidar seria pedir de novo o que não mudou.
// =============================================================================

let emCache = null

export function carregarFormasDePagamento() {
  if (!emCache) {
    emCache = pedirComRenovacao('/api/formas-pagamento').then((resposta) =>
      resposta.ok ? resposta.corpo.formasPagamento : [],
    )
  }
  return emCache
}

/** Só para teste e para telas que precisem forçar releitura. */
export function esquecerFormasDePagamento() {
  emCache = null
}

/**
 * O rótulo de uma forma, a partir da lista já carregada.
 *
 * Devolve o próprio valor quando a lista ainda não chegou ou quando o servidor
 * manda uma forma que esta versão da tela não conhece — melhor mostrar
 * `DEBITO` do que uma célula vazia.
 */
export function rotuloDaForma(formas, valor) {
  if (!valor) return null
  return formas.find((f) => f.valor === valor)?.nome ?? valor
}

/** As formas que servem a um sentido (`ENTRADA` ou `SAIDA`). */
export function formasDoSentido(formas, sentido) {
  return formas.filter((f) => f.sentidos.includes(sentido))
}

/**
 * As formas de uma conta que servem a um sentido, na ordem do servidor.
 *
 * A ordem importa: `conta.formasPagamento` vem ordenada pelo enum, e refazer a
 * ordem aqui faria as caixas dançarem entre duas telas sem nada ter mudado.
 */
export function formasDaContaNoSentido(formas, formasDaConta, sentido) {
  return formas.filter(
    (f) => formasDaConta.includes(f.valor) && f.sentidos.includes(sentido),
  )
}
