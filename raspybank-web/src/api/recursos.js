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

// ------------------------------------------------------------------ acessos
/**
 * Compartilhamento de ambiente (§2c): "é como se eu desse a minha senha para a
 * pessoa, mas ao invés de dar minha senha dei meu acesso".
 *
 * Conceder é imediato, sem aceite (B-D80). Pode responder 404 (e-mail não
 * cadastrado — e a mensagem diz isso, B-D81), 403 (só o dono mexe na porta) e
 * 409 (a pessoa já tem acesso).
 */
export const acessos = {
  listar: (ambienteId) => pedirComRenovacao(`/api/ambientes/${ambienteId}/acessos`),

  conceder: (ambienteId, email) =>
    pedirComRenovacao(`/api/ambientes/${ambienteId}/acessos`, json('POST', { email })),

  /** O dono remove qualquer um; qualquer um remove a si mesmo; o dono não sai. */
  remover: (ambienteId, usuarioId) =>
    pedirComRenovacao(`/api/ambientes/${ambienteId}/acessos/${usuarioId}`, json('DELETE')),
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

  /**
   * O extrato da conta, e é ele que ATRAVESSA ambientes (§2d, B-D87).
   *
   * Não confundir com `lancamentos.listar({ contaId })`: aquele é o extrato do
   * AMBIENTE filtrado por conta, e não mostra o lançamento de quem divide a
   * conta com você. O número que bate com o extrato do banco é este.
   *
   * A linha alheia vem sem `descricao` e sem `categoria` (B-D89), e não é a tela
   * que as esconde — a função do banco não as devolve (B-D97).
   */
  extrato: (id, mes) => pedirComRenovacao(`/api/contas/${id}/extrato?mes=${mes}`),

  /** Quem já tem a conta e quem ainda não respondeu, numa lista só. */
  compartilhamentos: (id) => pedirComRenovacao(`/api/contas/${id}/compartilhamentos`),

  /**
   * Cria um convite PENDENTE, e não um acesso.
   *
   * Diferente do ambiente, que é imediato (B-D80): aqui quem recebe escolhe em
   * qual ambiente dela a conta vai aparecer (B-D90), e ninguém pode adivinhar
   * essa escolha. Pode responder 404 (e-mail não cadastrado, B-D81), 403 (só
   * quem abriu a conta reparte, ou é conta de CARTÃO — que se divide por plástico,
   * B-D106) e 409 (convite repetido, pessoa que já recebeu, conta encerrada).
   *
   * Ter acesso ao seu ambiente NÃO impede (B-D104): ver a conta de dentro do seu
   * ambiente e ter a conta no ambiente dela são coisas diferentes.
   */
  compartilhar: (id, email) =>
    pedirComRenovacao(`/api/contas/${id}/compartilhamentos`, json('POST', { email })),

  /**
   * Um caminho para três coisas: cancelar convite, revogar acesso, e sair da
   * conta que você recebeu.
   *
   * Revogar NÃO apaga os lançamentos de quem sai (B-D93) — aquele dinheiro saiu
   * da conta de verdade, e apagá-lo faria o saldo divergir do extrato do banco.
   */
  removerCompartilhamento: (id, usuarioId) =>
    pedirComRenovacao(`/api/contas/${id}/compartilhamentos/${usuarioId}`, json('DELETE')),
}

// ------------------------------------------------------------------ convites
/**
 * Convites de conta, do lado de quem recebe (§2d).
 *
 * Fora de `contas` de propósito: um convite pendente NÃO é uma conta — ela ainda
 * não aparece para a pessoa, e não aparece por política. Só depois do aceite.
 */
export const convites = {
  listar: () => pedirComRenovacao('/api/convites'),

  /** `ambienteId` é obrigatório: é a escolha que só quem recebe pode fazer. */
  aceitar: (id, ambienteId) =>
    pedirComRenovacao(`/api/convites/${id}/aceitar`, json('POST', { ambienteId })),

  recusar: (id) => pedirComRenovacao(`/api/convites/${id}`, json('DELETE')),
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

  /**
   * A unidade do compartilhamento de cartão é o PLÁSTICO (B-D106), nunca o
   * contrato.
   *
   * A V17 dividia a conta do cartão, e o uso mostrou que estava errado: entregava
   * os dez plásticos quando o que se quer dividir é um adicional. Dividir a conta
   * de um cartão agora responde **403** apontando este caminho.
   *
   * Quem recebe ganha um meio de pagamento dentro da fatura de quem abriu o
   * contrato — e o extrato daquele plástico. Não o total da fatura (é de quem
   * paga, B-D107) nem os outros plásticos (B-D110).
   */
  plasticos: {
    compartilhamentos: (cartaoId, emitidoId) =>
      pedirComRenovacao(`/api/cartoes/${cartaoId}/emitidos/${emitidoId}/compartilhamentos`),

    compartilhar: (cartaoId, emitidoId, email) =>
      pedirComRenovacao(
        `/api/cartoes/${cartaoId}/emitidos/${emitidoId}/compartilhamentos`,
        json('POST', { email }),
      ),

    /** Tira alguém, ou sai. Sem plástico sobrando, o cartão sai da vista dela. */
    remover: (cartaoId, emitidoId, usuarioId) =>
      pedirComRenovacao(
        `/api/cartoes/${cartaoId}/emitidos/${emitidoId}/compartilhamentos/${usuarioId}`,
        json('DELETE'),
      ),
  },
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
  const plasticos = (c, sufixo) =>
    (c.emitidos ?? [])
      .filter((e) => !e.canceladoEm)
      .map((e) => ({
        chave: `cartao:${e.id}`,
        rotulo:
          `${c.nome} · ${e.tipo === 'FISICO' ? 'físico' : 'virtual'} ····${e.finalDoCartao}`
          + sufixo,
        forma: null,
        cartao: e.id,
      }))

  const cartoes =
    sentido === 'SAIDA'
      ? cartoesDoAmbiente
          .filter((c) => c.banco?.id === conta.id && !c.encerradoEm)
          .flatMap((c) => plasticos(c, ''))
      : []

  return [...formas, ...cartoes]
}

/**
 * O banco de OUTRA PESSOA, que aparece no seu seletor de conta só para você
 * alcançar o cartão que ela dividiu (B-D112).
 *
 * Não é uma conta sua: você não tem saldo nem forma de pagamento nela, e o
 * servidor recusa qualquer lançamento que a aponte. Ela existe na tela porque o
 * agrupamento "banco → cartões" de B-D61 é como se pensa em cartão — palavras
 * dele: *"primeiro eu penso de qual banco, depois no cartão"*.
 *
 * O nome traz o dono ("Nubank de Abner") porque você pode ter um Nubank também.
 *
 * Antes disso, o cartão dividido aparecia embaixo de TODAS as suas contas, e era
 * lixo: escolher "C6 dela" e ver "UltraVioleta de Abner" não quer dizer nada.
 */
export function bancosEmprestados(cartoesDoAmbiente) {
  const porBanco = new Map()

  for (const c of cartoesDoAmbiente) {
    if (c.origem !== false || c.encerradoEm || !c.banco?.id) continue

    const chave = `${c.banco.id}:${c.recebidoDe}`
    if (!porBanco.has(chave)) {
      porBanco.set(chave, {
        id: chave,
        nome: `${c.banco.nome} de ${c.recebidoDe}`,
        emprestada: true,
        cartoes: [],
      })
    }
    porBanco.get(chave).cartoes.push(c)
  }

  return [...porBanco.values()]
}

/**
 * As opções de "como foi pago" de um banco emprestado: SÓ os plásticos que
 * aquela pessoa dividiu com você, daquele banco.
 *
 * Nada de débito, pix ou boleto — a conta não é sua, e o dinheiro daquele banco
 * não se move por você. Cartão de crédito só serve para SAÍDA.
 */
export function opcoesDoBancoEmprestado(banco, sentido) {
  if (!banco?.emprestada || sentido !== 'SAIDA') return []

  return banco.cartoes.flatMap((c) =>
    (c.emitidos ?? [])
      .filter((e) => !e.canceladoEm)
      .map((e) => ({
        chave: `cartao:${e.id}`,
        rotulo:
          `${c.nome} · ${e.tipo === 'FISICO' ? 'físico' : 'virtual'} ····${e.finalDoCartao}`,
        forma: null,
        cartao: e.id,
        // A conta que o lançamento vai gravar é a do CARTÃO, e não a do banco:
        // o banco é dele, e `exigirContaNoAmbiente` recusaria.
        contaDoCartao: c.id,
      })),
  )
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
