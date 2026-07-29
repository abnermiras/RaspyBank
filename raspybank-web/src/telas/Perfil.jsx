import { useCallback, useState } from 'react'
import { lerErro } from '../api/cliente.js'
import { ambientes as apiAmbientes, perfil as apiPerfil } from '../api/recursos.js'
import Aviso from '../componentes/Aviso.jsx'
import { useAutenticacao } from '../contexto/Autenticacao.jsx'
import { useCarregar } from '../ganchos/useCarregar.js'
import { data } from '../util/formato.js'

// =============================================================================
// T-09 — Perfil, ambientes e compartilhamento
// =============================================================================
// Nasceu de um beco que o uso revelou: o seletor de ambiente da casca ficava
// desabilitado com um ambiente só, e não havia como criar o segundo. A lista
// existia; a porta não.
//
// O E-MAIL NÃO SE EDITA, e a ausência é decisão: ele é o login. Enquanto não
// existir recuperação de senha, um e-mail digitado errado trancaria a pessoa
// para fora da própria conta, sem volta.
//
// COMPARTILHAMENTO nasce desabilitado de propósito. O desenho ainda não existe,
// e um botão que promete e não entrega é pior que um botão ausente — mas o
// lugar dele já fica marcado, para a conversa ter onde cair.
// =============================================================================

export default function Perfil() {
  const buscar = useCallback(() => apiPerfil.buscar(), [])
  const { dados, carregando, erro, recarregar } = useCarregar(buscar)

  const { carregarPerfil } = useAutenticacao()
  const [aviso, setAviso] = useState(null)
  const [ocupado, setOcupado] = useState(false)
  const [criando, setCriando] = useState(false)

  async function executar(acao, mensagemDeSucesso) {
    setOcupado(true)
    setAviso(null)
    try {
      const resposta = await acao()
      if (!resposta.ok) {
        setAviso({ texto: lerErro(resposta).mensagem, sucesso: false })
        return false
      }
      if (mensagemDeSucesso) setAviso({ texto: mensagemDeSucesso, sucesso: true })
      await recarregar()
      // A casca mostra o ambiente ativo e o nome: sem isto ela ficaria
      // mostrando o valor velho até a próxima navegação.
      await carregarPerfil()
      return true
    } catch {
      setAviso({ texto: 'Servidor indisponível.', sucesso: false })
      return false
    } finally {
      setOcupado(false)
    }
  }

  const eu = dados ?? {}
  const listaDeAmbientes = eu.ambientes ?? []

  return (
    <section className="painel">
      <header className="cabecalho-painel">
        <h2>Perfil</h2>
      </header>

      <Aviso aviso={aviso} />
      {erro && <p className="aviso" role="alert">{erro}</p>}
      {carregando && <p className="carregando">Carregando…</p>}

      {!carregando && (
        <>
          <div className="bloco-perfil">
            <h3>Dados cadastrais</h3>
            <dl className="dados-cadastrais">
              <dt>Nome</dt><dd>{eu.nome}</dd>
              <dt>E-mail</dt>
              <dd>
                {eu.email}
                <span className="texto-fraco"> — é o seu login, e não se altera</span>
              </dd>
              <dt>Cadastro</dt><dd>{eu.criadoEm ? data(eu.criadoEm.slice(0, 10)) : '—'}</dd>
            </dl>
          </div>

          <FormularioDeNome
            nomeAtual={eu.nome ?? ''}
            ocupado={ocupado}
            aoGravar={(nome) =>
              executar(() => apiPerfil.alterarNome(nome), 'Nome atualizado.')
            }
          />

          <FormularioDeSenha
            ocupado={ocupado}
            aoGravar={(atual, nova) =>
              executar(
                () => apiPerfil.trocarSenha(atual, nova),
                'Senha trocada. As outras sessões foram encerradas.',
              )
            }
          />

          <div className="bloco-perfil">
            <div className="cabecalho-painel">
              <h3>Ambientes</h3>
              <button
                type="button" className="botao-principal botao-pequeno"
                onClick={() => setCriando(true)}
              >
                Novo ambiente
              </button>
            </div>

            <p className="dica">
              Um ambiente é uma separação de contas e lançamentos — casa,
              pessoal, freelance. Ele nasce <strong>vazio</strong>: sem contas e
              só com as categorias que o sistema precisa para funcionar. Trocar
              de ambiente é pelo seletor no topo.
            </p>

            {criando && (
              <FormularioDeAmbiente
                ocupado={ocupado}
                aoCancelar={() => setCriando(false)}
                aoGravar={async (nome) => {
                  const deu = await executar(
                    () => apiAmbientes.criar(nome),
                    `Ambiente "${nome}" criado, vazio e pronto para uso.`,
                  )
                  if (deu) setCriando(false)
                }}
              />
            )}

            <ul className="lista-ambientes">
              {listaDeAmbientes.map((a) => (
                <li key={a.id}>
                  <span className="nome-recurso">{a.nome}</span>
                  {a.id === eu.ambienteAtual && (
                    <span className="etiqueta etiqueta-entrada">ativo</span>
                  )}
                </li>
              ))}
            </ul>
          </div>

          <div className="bloco-perfil">
            <h3>Compartilhamento</h3>
            <p className="dica">
              Convidar outra pessoa para um ambiente ainda não existe — o
              desenho está em discussão. Quando existir, é daqui que sai.
            </p>
            <button type="button" className="botao-principal botao-pequeno" disabled>
              Compartilhar ambiente
            </button>
          </div>
        </>
      )}
    </section>
  )
}

// ----------------------------------------------------------------------------

function FormularioDeNome({ nomeAtual, ocupado, aoGravar }) {
  return (
    <form
      className="bloco-perfil"
      onSubmit={(e) => {
        e.preventDefault()
        aoGravar(new FormData(e.target).get('nome').trim())
      }}
    >
      <h3>Alterar nome</h3>
      <div className="campos-lado-a-lado">
        <label>
          Nome
          <input name="nome" required maxLength={120} defaultValue={nomeAtual} />
        </label>
      </div>
      <div className="acoes-linha">
        <button type="submit" className="botao-principal botao-pequeno" disabled={ocupado}>
          Salvar nome
        </button>
      </div>
    </form>
  )
}

/**
 * Trocar senha exige a atual.
 *
 * Sem isso, quem sentasse na máquina com a sessão aberta tomaria a conta — e a
 * troca derruba as outras sessões, então o dono ficaria de fora.
 */
function FormularioDeSenha({ ocupado, aoGravar }) {
  const [erroLocal, setErroLocal] = useState(null)

  return (
    <form
      className="bloco-perfil"
      onSubmit={(e) => {
        e.preventDefault()
        const d = new FormData(e.target)
        const nova = d.get('senhaNova')

        // Conferido aqui só para evitar a viagem: quem manda no tamanho é o
        // servidor, e a regra dele é que vale.
        if (nova !== d.get('senhaRepetida')) {
          setErroLocal('As duas senhas novas não são iguais.')
          return
        }
        setErroLocal(null)
        aoGravar(d.get('senhaAtual'), nova)
        e.target.reset()
      }}
    >
      <h3>Trocar senha</h3>

      <div className="campos-lado-a-lado">
        <label>
          Senha atual
          <input type="password" name="senhaAtual" required autoComplete="current-password" />
        </label>
        <label>
          Senha nova
          <input
            type="password" name="senhaNova" required minLength={10}
            autoComplete="new-password"
          />
        </label>
        <label>
          Repita a nova
          <input type="password" name="senhaRepetida" required autoComplete="new-password" />
        </label>
      </div>

      {erroLocal && <p className="aviso" role="alert">{erroLocal}</p>}

      <p className="dica">
        Trocar a senha <strong>encerra suas outras sessões</strong>. Se a troca é
        porque a senha vazou, deixar as antigas vivas manteria quem a roubou
        dentro do sistema. Esta sessão continua.
      </p>

      <p className="dica">
        Ainda não existe recuperação de senha: se você esquecê-la, não há
        caminho de volta. É a próxima coisa a resolver antes de o sistema sair
        da rede de casa.
      </p>

      <div className="acoes-linha">
        <button type="submit" className="botao-principal botao-pequeno" disabled={ocupado}>
          Trocar senha
        </button>
      </div>
    </form>
  )
}

function FormularioDeAmbiente({ ocupado, aoGravar, aoCancelar }) {
  return (
    <form
      className="formulario-bloco"
      onSubmit={(e) => {
        e.preventDefault()
        aoGravar(new FormData(e.target).get('nome').trim())
      }}
    >
      <h3>Novo ambiente</h3>
      <div className="campos-lado-a-lado">
        <label>
          Nome
          <input name="nome" required maxLength={60} autoFocus placeholder="Freelance" />
        </label>
      </div>
      <div className="acoes-linha">
        <button type="submit" className="botao-principal botao-pequeno" disabled={ocupado}>
          Criar ambiente
        </button>
        <button type="button" className="botao-texto" onClick={aoCancelar}>Cancelar</button>
      </div>
    </form>
  )
}
