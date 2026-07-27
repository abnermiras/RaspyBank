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
