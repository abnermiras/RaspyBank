import { pedirComRenovacao } from './cliente.js'

// =============================================================================
// Os endpoints, um por função
// =============================================================================
// Nenhuma tela monta caminho de URL na mão. O motivo não é estética: quando o
// contrato mudar, o lugar a corrigir precisa ser um só, e um caminho escrito
// no meio de um componente é o que escapa da busca.
//
// Toda função devolve o envelope { ok, status, corpo } — a tela decide o que
// fazer com 403/404/409, porque a resposta certa depende da tela.
// =============================================================================

const json = (metodo, corpo) => ({ metodo, corpo })

// ------------------------------------------------------------------- perfil
export const perfil = {
  buscar: () => pedirComRenovacao('/api/perfil'),

  alterarNome: (nome) => pedirComRenovacao('/api/perfil/nome', json('PUT', { nome })),

  /**
   * Exige a senha atual, e derruba as OUTRAS sessões.
   *
   * Se a troca aconteceu porque a senha vazou, deixar as antigas vivas manteria
   * o invasor dentro. Esta sessão continua.
   */
  trocarSenha: (senhaAtual, senhaNova) =>
    pedirComRenovacao('/api/perfil/senha', json('PUT', { senhaAtual, senhaNova })),
}

// ---------------------------------------------------------------- ambientes
export const ambientes = {
  listar: () => pedirComRenovacao('/api/ambientes'),

  /** Nasce vazio — só as sistêmicas (F13). Não troca a sessão para ele. */
  criar: (nome) => pedirComRenovacao('/api/ambientes', json('POST', { nome })),
}

// ---------------------------------------------------------------- categorias
export const categorias = {
  listar: (incluirArquivadas = false) =>
    pedirComRenovacao(`/api/categorias?incluirArquivadas=${incluirArquivadas}`),

  criar: (nome, tipo) => pedirComRenovacao('/api/categorias', json('POST', { nome, tipo })),

  alterar: (id, nome, tipo) =>
    pedirComRenovacao(`/api/categorias/${id}`, json('PUT', { nome, tipo })),

  arquivar: (id) => pedirComRenovacao(`/api/categorias/${id}/arquivar`, json('POST')),
  desarquivar: (id) => pedirComRenovacao(`/api/categorias/${id}/desarquivar`, json('POST')),

  criarSubcategoria: (categoriaId, nome) =>
    pedirComRenovacao(`/api/categorias/${categoriaId}/subcategorias`, json('POST', { nome })),
}

export const subcategorias = {
  alterar: (id, nome) => pedirComRenovacao(`/api/subcategorias/${id}`, json('PUT', { nome })),
  arquivar: (id) => pedirComRenovacao(`/api/subcategorias/${id}/arquivar`, json('POST')),
  desarquivar: (id) => pedirComRenovacao(`/api/subcategorias/${id}/desarquivar`, json('POST')),
}

// -------------------------------------------------------------------- contas
export const contas = {
  listar: (incluirEncerradas = false) =>
    pedirComRenovacao(`/api/contas?incluirEncerradas=${incluirEncerradas}`),

  criar: (dados) => pedirComRenovacao('/api/contas', json('POST', dados)),
  alterar: (id, dados) => pedirComRenovacao(`/api/contas/${id}`, json('PUT', dados)),
  encerrar: (id) => pedirComRenovacao(`/api/contas/${id}/encerrar`, json('POST')),
  reabrir: (id) => pedirComRenovacao(`/api/contas/${id}/reabrir`, json('POST')),

  /**
   * Substitui a lista inteira, não acrescenta. `formas: []` deixa a conta sem
   * nenhuma, e `padrao: null` é válido — aceitar várias sem ter preferência.
   *
   * Pode responder 409: remover uma forma que algum lançamento já usou é
   * recusado, para não apagar em silêncio o dado que ela registrava.
   */
  definirFormasDePagamento: (id, formas, padraoSaida, padraoEntrada) =>
    pedirComRenovacao(
      `/api/contas/${id}/formas-pagamento`,
      json('PUT', {
        formas,
        padraoSaida: padraoSaida || null,
        padraoEntrada: padraoEntrada || null,
      }),
    ),
}

// ------------------------------------------------------------- transferências
export const transferencias = {
  /**
   * Cria as DUAS pernas numa transação só. Não existe endpoint para criar uma
   * perna: a primeira sozinha já é um saldo errado.
   *
   * Não manda categoria (é sempre a sistêmica TRANSFERENCIA), nem tipo (origem
   * é saída, destino é entrada), nem forma de pagamento (categoria sistêmica não
   * recebe padrão — quem quiser registrar "por pix" edita a perna depois).
   */
  criar: (dados) => pedirComRenovacao('/api/transferencias', json('POST', dados)),
}

// ------------------------------------------------------------------ cartões
export const cartoes = {
  listar: (incluirEncerrados = false) =>
    pedirComRenovacao(`/api/cartoes?incluirEncerrados=${incluirEncerrados}`),

  buscar: (id) => pedirComRenovacao(`/api/cartoes/${id}`),

  criar: (dados) => pedirComRenovacao('/api/cartoes', json('POST', dados)),
  alterar: (id, dados) => pedirComRenovacao(`/api/cartoes/${id}`, json('PUT', dados)),

  encerrar: (id) => pedirComRenovacao(`/api/cartoes/${id}/encerrar`, json('POST')),
  reabrir: (id) => pedirComRenovacao(`/api/cartoes/${id}/reabrir`, json('POST')),

  emitir: (id, dados) =>
    pedirComRenovacao(`/api/cartoes/${id}/emitidos`, json('POST', dados)),

  cancelarEmitido: (id, emitidoId) =>
    pedirComRenovacao(`/api/cartoes/${id}/emitidos/${emitidoId}/cancelar`, json('POST')),

  reativarEmitido: (id, emitidoId) =>
    pedirComRenovacao(`/api/cartoes/${id}/emitidos/${emitidoId}/reativar`, json('POST')),

  faturas: (id, ano) => pedirComRenovacao(`/api/cartoes/${id}/faturas?ano=${ano}`),
}

/**
 * O cartão é um MEIO DE PAGAMENTO da conta bancária, não uma conta (B-D61).
 *
 * Ninguém pensa "vou gastar na conta do cartão", pensa "paguei no cartão". A
 * tela manda `contaId` = o banco e `cartaoEmitidoId` = o plástico, e o servidor
 * redireciona o lançamento para a conta do cartão sem você ver.
 *
 * Devolve as opções que o combo "como foi pago" mostra: as formas daquela conta
 * mais os cartões dela.
 */
export function opcoesDePagamento(conta, formasConhecidas, cartoesDoAmbiente, sentido) {
  if (!conta) return []

  const formas = formasConhecidas
    .filter((f) => (conta.formasPagamento ?? []).includes(f.valor))
    .filter((f) => f.sentidos.includes(sentido))
    .map((f) => ({ chave: `forma:${f.valor}`, rotulo: f.nome, forma: f.valor, cartao: null }))

  // Cartão de crédito só serve para SAÍDA: ninguém recebe salário no cartão.
  const cartoes =
    sentido === 'SAIDA'
      ? cartoesDoAmbiente
          .filter((c) => c.banco?.id === conta.id && !c.encerradoEm)
          .flatMap((c) =>
            (c.emitidos ?? [])
              .filter((e) => !e.canceladoEm)
              .map((e) => ({
                chave: `cartao:${e.id}`,
                rotulo: `${c.nome} · ${e.tipo === 'FISICO' ? 'físico' : 'virtual'} ····${e.finalDoCartao}`,
                forma: null,
                cartao: e.id,
              })),
          )
      : []

  return [...formas, ...cartoes]
}

// ------------------------------------------------------------------ faturas
export const faturas = {
  buscar: (id) => pedirComRenovacao(`/api/faturas/${id}`),
  lancamentos: (id) => pedirComRenovacao(`/api/faturas/${id}/lancamentos`),

  fechar: (id) => pedirComRenovacao(`/api/faturas/${id}/fechar`, json('POST')),
  reabrir: (id) => pedirComRenovacao(`/api/faturas/${id}/reabrir`, json('POST')),

  /**
   * Paga total ou em parte, com a fatura aberta ou fechada.
   *
   * Antecipar não é conveniência: é como se libera limite (B-D57). Fatura
   * aberta de 5.000 com 1.000 disponível e uma compra de 2.000 para fazer —
   * paga 1.000, o disponível vira 2.000, a compra passa.
   */
  pagar: (id, dados) =>
    pedirComRenovacao(`/api/faturas/${id}/pagamentos`, json('POST', dados)),
}

// --------------------------------------------------------------- lançamentos
export const lancamentos = {
  /** `mes` é obrigatório no formato AAAA-MM. Os demais filtros são opcionais. */
  listar: ({ mes, contaId, categoriaId, situacao }) => {
    const busca = new URLSearchParams({ mes })
    if (contaId) busca.set('contaId', contaId)
    if (categoriaId) busca.set('categoriaId', categoriaId)
    if (situacao) busca.set('situacao', situacao)
    return pedirComRenovacao(`/api/lancamentos?${busca}`)
  },

  criar: (dados) => pedirComRenovacao('/api/lancamentos', json('POST', dados)),
  alterar: (id, dados) => pedirComRenovacao(`/api/lancamentos/${id}`, json('PUT', dados)),
  excluir: (id) => pedirComRenovacao(`/api/lancamentos/${id}`, json('DELETE')),
}

// ---------------------------------------------------------------- relatórios
export const relatorios = {
  /**
   * `contas` recorta o mapa: `TODAS` (padrão), `CARTAO`, `SEM_CARTAO`.
   *
   * Filtro no topo, e não um terceiro número por célula (B-D54): B-D10 separou
   * realizado de previsto com esforço, e um terceiro em doze colunas viraria
   * sopa. Trocar a lente responde "quanto do meu mercado foi no cartão".
   */
  mapaDeGastos: (ano, contas = 'TODAS') =>
    pedirComRenovacao(`/api/relatorios/mapa-de-gastos?ano=${ano}&contas=${contas}`),
}
