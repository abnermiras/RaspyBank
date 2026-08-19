import { useCallback, useState } from 'react'
import { lerErro } from '../api/cliente.js'
import {
  acessos as apiAcessos,
  ambientes as apiAmbientes,
  perfil as apiPerfil,
} from '../api/recursos.js'
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
// COMPARTILHAMENTO (V15, §4j): "é como se eu desse a minha senha para a
// pessoa, mas ao invés de dar minha senha dei meu acesso". Quem recebe vê o
// ambiente na própria lista e trabalha DENTRO dele — as categorias, contas e
// o mapa são os do dono, e toda ação fica carimbada com quem a fez. Dinheiro
// é de todos; porta (convidar, remover) é só do dono (B-D76).
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

          <FormularioDeTelegram
            telegramAtual={eu.telegramId ?? ''}
            ocupado={ocupado}
            aoGravar={(telegramId) =>
              executar(
                () => apiPerfil.alterarTelegram(telegramId),
                telegramId ? 'Telegram atualizado.' : 'Telegram removido do seu cadastro.',
              )
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
                  {!a.dono && (
                    <>
                      <span className="etiqueta">compartilhado comigo</span>
                      {/* B-D77: qualquer um remove a si mesmo — sem isso a
                          pessoa ficaria presa num ambiente que não pediu. */}
                      <button
                        type="button" className="botao-texto"
                        disabled={ocupado}
                        onClick={() => {
                          if (window.confirm(`Sair de "${a.nome}"? Para voltar, o dono precisa convidar de novo.`)) {
                            executar(
                              () => apiAcessos.remover(a.id, eu.usuarioId),
                              `Você saiu de "${a.nome}".`,
                            )
                          }
                        }}
                      >
                        Sair
                      </button>
                    </>
                  )}
                </li>
              ))}
            </ul>
          </div>

          <Compartilhamento ambientes={listaDeAmbientes} usuarioId={eu.usuarioId} />
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
 * O Telegram, que até aqui só entrava no cadastro.
 *
 * O campo NÃO é `required`, e a ausência é o ponto: apagar o conteúdo e salvar
 * limpa o valor. Quem digitou o usuário errado não tinha caminho de volta —
 * o cadastro só acontece uma vez.
 *
 * Vocabulário igual ao da tela de cadastro de propósito: são o mesmo campo, e
 * dois nomes diferentes fariam parecer que são duas coisas.
 */
function FormularioDeTelegram({ telegramAtual, ocupado, aoGravar }) {
  return (
    <form
      className="bloco-perfil"
      onSubmit={(e) => {
        e.preventDefault()
        // Aparado: espaço em branco é ausência, não identidade.
        aoGravar(new FormData(e.target).get('telegramId').trim())
      }}
    >
      <h3>Alterar Telegram</h3>
      <div className="campos-lado-a-lado">
        <label>
          Telegram (opcional)
          {/* defaultValue e não placeholder: vazio no servidor é campo vazio. */}
          <input
            name="telegramId" maxLength={64} autoComplete="off"
            placeholder="@seu_usuario" defaultValue={telegramAtual}
          />
        </label>
      </div>

      <p className="dica">
        Seu usuário ou o id numérico. Serve para o bot saber quem está
        lançando — deixe em branco se ainda não usa.
      </p>

      <p className="dica">
        Digitou errado? <strong>Apague o campo e salve</strong>: o valor é
        limpo e o bot deixa de reconhecer você.
      </p>

      <div className="acoes-linha">
        <button type="submit" className="botao-principal botao-pequeno" disabled={ocupado}>
          Salvar Telegram
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

/**
 * A porta do ambiente: quem está dentro, convidar, remover.
 *
 * Só aparecem aqui os ambientes em que a pessoa é DONA — mexer na porta é do
 * dono (B-D76). O convite é por e-mail e vale na hora, sem aceite (B-D80): o
 * ambiente aparece na lista da pessoa e ela sai com um clique se não quiser.
 */
function Compartilhamento({ ambientes, usuarioId }) {
  const proprios = ambientes.filter((a) => a.dono)
  const [ambienteId, setAmbienteId] = useState(proprios[0]?.id ?? '')

  const buscar = useCallback(
    () =>
      ambienteId
        ? apiAcessos.listar(ambienteId)
        : Promise.resolve({ ok: true, status: 200, corpo: { acessos: [] } }),
    [ambienteId],
  )
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
      return true
    } catch {
      setAviso({ texto: 'Servidor indisponível.', sucesso: false })
      return false
    } finally {
      setOcupado(false)
    }
  }

  const lista = dados?.acessos ?? []

  return (
    <div className="bloco-perfil">
      <h3>Compartilhamento</h3>

      <p className="dica">
        Compartilhar um ambiente é dar o seu acesso a ele: a pessoa vê e mexe em
        tudo que é <strong>dinheiro</strong> — lançamentos, contas, cartões,
        faturas — e cada ação fica registrada com o nome de quem a fez. Convidar
        e remover acesso continuam só com você.
      </p>

      {proprios.length === 0 && (
        <p className="dica">Você não é dono de nenhum ambiente para compartilhar.</p>
      )}

      {proprios.length > 0 && (
        <>
          <div className="campos-lado-a-lado">
            <label>
              Ambiente
              <select
                value={ambienteId}
                onChange={(e) => setAmbienteId(e.target.value)}
                disabled={proprios.length < 2}
              >
                {proprios.map((a) => (
                  <option key={a.id} value={a.id}>{a.nome}</option>
                ))}
              </select>
            </label>
          </div>

          <Aviso aviso={aviso} />
          {erro && <p className="aviso" role="alert">{erro}</p>}
          {carregando && <p className="carregando">Carregando…</p>}

          {!carregando && (
            <ul className="lista-ambientes">
              {lista.map((acesso) => (
                <li key={acesso.usuarioId}>
                  <span className="nome-recurso">{acesso.nome}</span>
                  <span className="texto-fraco"> {acesso.email}</span>
                  {acesso.dono && <span className="etiqueta etiqueta-entrada">dono</span>}
                  {!acesso.dono && (
                    <button
                      type="button" className="botao-texto"
                      disabled={ocupado}
                      onClick={() => {
                        if (window.confirm(`Remover o acesso de ${acesso.nome}? A revogação vale na hora.`)) {
                          executar(
                            () => apiAcessos.remover(ambienteId, acesso.usuarioId),
                            `Acesso de ${acesso.nome} removido.`,
                          )
                        }
                      }}
                    >
                      {acesso.usuarioId === usuarioId ? 'Sair' : 'Remover'}
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}

          <form
            onSubmit={async (e) => {
              e.preventDefault()
              const formulario = e.target
              const email = new FormData(formulario).get('email').trim()
              const deu = await executar(
                () => apiAcessos.conceder(ambienteId, email),
                `Pronto: ${email} já está dentro. Não há aceite — o ambiente já aparece na lista da pessoa.`,
              )
              if (deu) formulario.reset()
            }}
          >
            <div className="campos-lado-a-lado">
              <label>
                Convidar por e-mail
                <input
                  type="email" name="email" required
                  placeholder="pessoa@exemplo.com"
                />
              </label>
            </div>
            <div className="acoes-linha">
              <button type="submit" className="botao-principal botao-pequeno" disabled={ocupado}>
                Dar acesso
              </button>
            </div>
          </form>
        </>
      )}
    </div>
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
