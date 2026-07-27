// =============================================================================
// Guarda dos tokens
// =============================================================================
// As chaves são AS MESMAS do protótipo, de propósito: quem estiver com sessão
// aberta quando a SPA substituir os arquivos de static/ continua logado, em vez
// de ser jogado na tela de login sem explicação.
//
// localStorage segue sendo a escolha simples, não a segura — qualquer script
// injetado na página consegue ler. É P-T8 no mapa de telas, a única pergunta
// ainda aberta, e ela só precisa vencer antes de expor isto à internet.
// =============================================================================

const GUARDA = {
  acesso: 'raspybank.tokenAcesso',
  renovacao: 'raspybank.tokenRenovacao',
  ambiente: 'raspybank.ambienteId',
}

export function guardarSessao(dados) {
  localStorage.setItem(GUARDA.acesso, dados.tokenAcesso)
  localStorage.setItem(GUARDA.renovacao, dados.tokenRenovacao)
  if (dados.ambienteId) {
    localStorage.setItem(GUARDA.ambiente, dados.ambienteId)
  }
}

export function limparSessao() {
  Object.values(GUARDA).forEach((chave) => localStorage.removeItem(chave))
}

export const tokenAcesso = () => localStorage.getItem(GUARDA.acesso)
export const tokenRenovacao = () => localStorage.getItem(GUARDA.renovacao)
export const ambienteAtual = () => localStorage.getItem(GUARDA.ambiente)

export function guardarAcesso(token, ambienteId) {
  if (token) localStorage.setItem(GUARDA.acesso, token)
  guardarAmbiente(ambienteId)
}

/** Guardar só o ambiente, sem tocar no token — o caso de reler o perfil. */
export function guardarAmbiente(ambienteId) {
  if (ambienteId) localStorage.setItem(GUARDA.ambiente, ambienteId)
}
