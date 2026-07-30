import { useCallback, useState } from 'react'
import { lerErro } from '../api/cliente.js'
import Aviso from './Aviso.jsx'
import { useCarregar } from '../ganchos/useCarregar.js'

/**
 * Com quem esta conta — ou este cartão — está dividida, e o convite por e-mail.
 *
 * Serve aos dois porque compartilhar cartão É compartilhar a conta do contrato
 * (B-D98): o cartão é uma conta desde a V12, e um segundo painel seria a mesma
 * pergunta feita duas vezes, com duas chances de divergir. `ehCartao` muda o
 * texto, nunca o comportamento.
 *
 * NÃO mostra em qual ambiente da outra pessoa o recurso entrou, e o servidor
 * também não devolve isso: é organização da vida dela (B-D90).
 */
function PainelDeCompartilhamento({ recurso, api, ehCartao = false, aoMudar }) {
  const buscar = useCallback(() => api.compartilhamentos(recurso.id), [recurso.id, api])
  const { dados, carregando, erro, recarregar } = useCarregar(buscar)

  const [aviso, setAviso] = useState(null)
  const [ocupado, setOcupado] = useState(false)

  async function executar(acao, mensagemDeSucesso) {
    setOcupado(true)
    setAviso(null)
    try {
      const resposta = await acao()
      if (!resposta.ok) {
        setAviso({ texto: lerErro(resposta).mensagem, sucesso: false })
        return false
      }
      setAviso({ texto: mensagemDeSucesso, sucesso: true })
      await recarregar()
      await aoMudar()
      return true
    } catch {
      setAviso({ texto: 'Servidor indisponível.', sucesso: false })
      return false
    } finally {
      setOcupado(false)
    }
  }

  const lista = dados?.compartilhamentos ?? []

  return (
    <div className="formulario-bloco">
      <h3>Dividir “{recurso.nome}”</h3>

      <p className="dica">
        {ehCartao ? (
          <>
            Quem recebe <strong>compra neste cartão e paga a fatura</strong> — da
            conta bancária dela, e a fatura soma os dois. Fechar a fatura os dois
            podem; reabrir, emitir cartão novo, mudar o limite e encerrar continuam
            com você. Da compra da outra pessoa você vê valor, data, o plástico e a
            parcela — <strong>não</strong> vê a descrição nem a categoria.
          </>
        ) : (
          <>
            Quem recebe vê o <strong>saldo e o extrato inteiros</strong> desta conta
            e lança nela — no ambiente dela, com as categorias dela. O mapa de
            gastos de cada um continua separado. Do lançamento da outra pessoa você
            vê valor, data, forma de pagamento e quem fez; <strong>não</strong> vê
            a descrição nem a categoria.
          </>
        )}
      </p>

      <Aviso aviso={aviso} />
      {erro && <p className="aviso" role="alert">{erro}</p>}
      {carregando && <p className="carregando">Carregando…</p>}

      {!carregando && lista.length === 0 && (
        <p className="texto-vazio">
          {ehCartao ? 'Este cartão é só seu.' : 'Esta conta é só sua.'}
        </p>
      )}

      <ul className="lista-ambientes">
        {lista.map((c) => (
          <li key={c.usuarioId}>
            <span className="nome-recurso">{c.nome}</span>
            <span className="texto-fraco"> {c.email}</span>
            {c.situacao === 'PENDENTE' && (
              <span className="etiqueta etiqueta-fraca" title="Ainda não aceitou">
                aguardando
              </span>
            )}
            <button
              type="button" className="botao-texto" disabled={ocupado}
              onClick={() => {
                const frase = c.situacao === 'PENDENTE'
                  ? `Cancelar o convite de ${c.nome}?`
                  : `Tirar ${c.nome} daqui? Os lançamentos que ela já fez`
                    + ' continuam no saldo — aquele dinheiro saiu da conta de verdade.'
                if (window.confirm(frase)) {
                  executar(
                    () => api.removerCompartilhamento(recurso.id, c.usuarioId),
                    c.situacao === 'PENDENTE'
                      ? `Convite de ${c.nome} cancelado.`
                      : `${c.nome} não tem mais acesso.`,
                  )
                }
              }}
            >
              {c.situacao === 'PENDENTE' ? 'Cancelar' : 'Tirar'}
            </button>
          </li>
        ))}
      </ul>

      <form
        onSubmit={async (e) => {
          e.preventDefault()
          const formulario = e.target
          const email = new FormData(formulario).get('email').trim()
          const deu = await executar(
            () => api.compartilhar(recurso.id, email),
            `Convite enviado para ${email}. ${ehCartao ? 'O cartão' : 'A conta'} aparece`
              + ' para a pessoa quando ela aceitar e escolher em qual ambiente dela guardar.',
          )
          if (deu) formulario.reset()
        }}
      >
        <div className="campos-lado-a-lado">
          <label>
            Dividir com (e-mail)
            <input type="email" name="email" required placeholder="pessoa@exemplo.com" />
          </label>
        </div>
        <div className="acoes-linha">
          <button type="submit" className="botao-principal botao-pequeno" disabled={ocupado}>
            Enviar convite
          </button>
        </div>
      </form>
    </div>
  )
}

export default PainelDeCompartilhamento
