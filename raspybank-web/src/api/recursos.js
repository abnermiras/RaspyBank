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
  mapaDeGastos: (ano) =>
    pedirComRenovacao(`/api/relatorios/mapa-de-gastos?ano=${ano}`),
}
