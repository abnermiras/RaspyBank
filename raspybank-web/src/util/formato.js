// =============================================================================
// Formatação para a tela
// =============================================================================
// Dinheiro chega da API como string ("380.00") e é assim que ele fica até a
// última hora — converter para Number no caminho reintroduz o ponto flutuante
// que o numeric(15,2) do banco existe para evitar. Aqui a string vira texto
// legível direto, sem passar por número quando dá.
// =============================================================================

const MOEDA = new Intl.NumberFormat('pt-BR', {
  style: 'currency',
  currency: 'BRL',
  minimumFractionDigits: 2,
})

/** "380.00" -> "R$ 380,00". Aceita null/undefined e devolve travessão. */
export function dinheiro(valor) {
  if (valor === null || valor === undefined || valor === '') return '—'
  const numero = Number(valor)
  if (Number.isNaN(numero)) return String(valor)
  return MOEDA.format(numero)
}

/** Só o número, sem símbolo — para tabelas onde o "R$" repetido vira ruído. */
export function numero(valor) {
  if (valor === null || valor === undefined || valor === '') return '—'
  const n = Number(valor)
  if (Number.isNaN(n)) return String(valor)
  return n.toLocaleString('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

/** Verdadeiro para "0.00", "0", "-0.00". Usado para apagar zeros da tabela. */
export const ehZero = (valor) => Number(valor) === 0

// =============================================================================
// O caminho de volta: da tela para a API
// =============================================================================
// Este módulo nasceu só com a ida (API -> tela) e a volta ficou faltando, então
// cada formulário mandava `d.get('valor').trim()` cru para o servidor. Como o
// contrato da API é ponto decimal e sem separador de milhar
// (`@Pattern("\\d{1,13}(\\.\\d{1,2})?")`), quem digitava "1450,22" — que é como
// se escreve dinheiro em português, e é o que o próprio placeholder do
// formulário de lançamento sugeria — levava 400 com a mensagem "valor deve ser
// positivo", que descreve a regra e não o que falhou.
//
// POR QUE A CORREÇÃO É AQUI E NÃO NO BACKEND
// A API deve ter UM formato canônico. "1.450" é ambíguo entre locales: mil e
// quatrocentos e cinquenta em pt-BR, um e quarenta e cinco em en-US. Aceitar os
// dois lados no servidor tornaria essa ambiguidade permanente e válida para
// qualquer cliente futuro. Locale é assunto de apresentação, e apresentação é
// este arquivo.
// =============================================================================

/**
 * Texto digitado por gente -> decimal que a API aceita.
 *
 *   "1450,22"     -> "1450.22"
 *   "1.450,22"    -> "1450.22"
 *   "R$ 1.450,22" -> "1450.22"
 *   "1450.22"     -> "1450.22"   (continua valendo, não quebra o que já era usado)
 *   "1.450"       -> "1450"
 *   "10.00"       -> "10.00"
 *   "-500,00"     -> "-500.00"   (só onde o campo aceita sinal)
 *
 * O que não der para interpretar sem adivinhar volta como veio, de propósito:
 * quem recusa é o servidor, com a mensagem dele. Silenciar um valor ambíguo
 * aqui seria pior — gravaria um número que o usuário não quis.
 */
export function paraDecimal(entrada) {
  if (entrada === null || entrada === undefined) return ''
  const original = String(entrada).trim()
  if (original === '') return ''

  // Espaço não separável ( ) entra ao copiar e colar de planilha e site.
  let t = original.replace(/[\s ]/g, '').replace(/^R\$/i, '')

  let sinal = ''
  const comSinal = /^([+-])(.*)$/.exec(t)
  if (comSinal) {
    sinal = comSinal[1] === '-' ? '-' : ''
    t = comSinal[2]
  }

  if (t === '' || !/^[\d.,]+$/.test(t)) return original

  const virgulas = (t.match(/,/g) || []).length
  const pontos = (t.match(/\./g) || []).length

  // "1,4,5" — duas vírgulas não têm leitura possível.
  if (virgulas > 1) return original

  if (virgulas === 1) {
    // A vírgula manda: ela é o decimal, e todo ponto vira separador de milhar.
    t = t.replace(/\./g, '').replace(',', '.')
  } else if (pontos === 1) {
    const [inteiro, fracao] = t.split('.')
    if (fracao.length === 3) {
      // "1.450" — três dígitos depois de um ponto único é MILHAR.
      //
      // Isto é DECISÃO, não fato. Em inglês "1.450" seria um e quarenta e
      // cinco. A interface inteira é em português e quem digita escreve em
      // português, então o milhar ganha. Quem quiser um e quarenta e cinco
      // escreve "1,45", que é inequívoco.
      t = inteiro + fracao
    } else if (fracao.length > 3) {
      return original // "1.4500" — não dá para adivinhar
    }
    // 1 ou 2 dígitos: já é decimal no formato da API, fica como está.
  } else if (pontos > 1) {
    // Vários pontos só podem ser milhar, e a forma tem de fechar certinho.
    if (!/^\d{1,3}(\.\d{3})+$/.test(t)) return original
    t = t.replace(/\./g, '')
  }

  // Arremates: ",50" vira "0.50" e "1450," vira "1450". Sem isso os dois
  // falhariam no regex do servidor por um detalhe que a gente sabe resolver.
  if (t.startsWith('.')) t = '0' + t
  if (t.endsWith('.')) t = t.slice(0, -1)

  return t === '' ? original : sinal + t
}

/**
 * Decimal da API -> texto para preencher um campo editável.
 * "380.00" -> "380,00". O espelho de `paraDecimal`, para quem abre o formulário
 * de edição ver o número no mesmo formato em que vai digitá-lo.
 */
export function paraCampo(valorDaApi) {
  if (valorDaApi === null || valorDaApi === undefined || valorDaApi === '') return ''
  return String(valorDaApi).replace('.', ',')
}

/**
 * "2026-07-12" -> "12/07/2026".
 * Fatiado como texto de propósito: `new Date("2026-07-12")` seria interpretado
 * como UTC e, a oeste de Greenwich, exibiria o dia 11. A data aqui é `date` no
 * banco (B-D8) e não tem fuso; tratá-la como instante inventaria um.
 */
export function data(iso) {
  if (!iso) return '—'
  const [ano, mes, dia] = String(iso).slice(0, 10).split('-')
  return `${dia}/${mes}/${ano}`
}

/** Date -> "AAAA-MM", o formato que o filtro de lançamentos exige. */
export function mesDe(momento) {
  const ano = momento.getFullYear()
  const mes = String(momento.getMonth() + 1).padStart(2, '0')
  return `${ano}-${mes}`
}

/** "2026-07" -> "Julho de 2026" */
export function mesPorExtenso(mes) {
  const [ano, numeroDoMes] = mes.split('-').map(Number)
  const nome = new Date(ano, numeroDoMes - 1, 1)
    .toLocaleDateString('pt-BR', { month: 'long' })
  return `${nome[0].toUpperCase()}${nome.slice(1)} de ${ano}`
}

/** Hoje como "AAAA-MM-DD", sem passar por UTC. */
export function hojeISO() {
  const agora = new Date()
  const mes = String(agora.getMonth() + 1).padStart(2, '0')
  const dia = String(agora.getDate()).padStart(2, '0')
  return `${agora.getFullYear()}-${mes}-${dia}`
}

export const NOMES_DOS_MESES = [
  'Jan', 'Fev', 'Mar', 'Abr', 'Mai', 'Jun',
  'Jul', 'Ago', 'Set', 'Out', 'Nov', 'Dez',
]
