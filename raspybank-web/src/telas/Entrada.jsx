import { useRef, useState } from 'react'
import { lerErro, pedir } from '../api/cliente.js'
import Aviso from '../componentes/Aviso.jsx'
import { useAutenticacao } from '../contexto/Autenticacao.jsx'

// =============================================================================
// T-01 / T-02 — Login e cadastro
// =============================================================================
// Os dois formulários dividem o mesmo cartão porque dividem o mesmo objetivo:
// chegar autenticado. Trocar de um para o outro não navega — quem alterna é
// estado local, e a URL continua sendo /entrar.
// =============================================================================

export default function Entrada() {
  const [modo, setModo] = useState('login')
  const [aviso, setAviso] = useState(null) // { texto, sucesso }
  const [camposComErro, setCamposComErro] = useState({})
  const [enviando, setEnviando] = useState(false)
  const emailDoLogin = useRef(null)
  const senhaDoLogin = useRef(null)

  const { entrar } = useAutenticacao()

  function alternar(proximo) {
    setModo(proximo)
    setAviso(null)
    setCamposComErro({})
  }

  function aplicarErro(resposta) {
    const { mensagem, campos } = lerErro(resposta)
    setCamposComErro(campos ?? {})
    setAviso({ texto: mensagem, sucesso: false })
  }

  async function aoEntrar(evento) {
    evento.preventDefault()
    const dados = new FormData(evento.target)
    setAviso(null)
    setCamposComErro({})
    setEnviando(true)
    try {
      const resposta = await entrar(dados.get('email').trim(), dados.get('senha'))
      // O 401 é deliberadamente vago: e-mail inexistente e senha errada
      // respondem igual, para o login não virar verificador de cadastro
      // (B-A8/B-T2). A tela não tenta adivinhar mais do que a API contou.
      if (!resposta.ok) aplicarErro(resposta)
      else if (senhaDoLogin.current) senhaDoLogin.current.value = ''
    } catch {
      setAviso({ texto: 'Servidor indisponível. Tente de novo.', sucesso: false })
    } finally {
      setEnviando(false)
    }
  }

  async function aoCadastrar(evento) {
    evento.preventDefault()
    const dados = new FormData(evento.target)
    const email = dados.get('email').trim()
    setAviso(null)
    setCamposComErro({})
    setEnviando(true)
    try {
      const resposta = await pedir('/api/auth/cadastro', {
        metodo: 'POST',
        corpo: {
          nome: dados.get('nome').trim(),
          email,
          senha: dados.get('senha'),
          // Vazio vira nulo no banco (V18), então mandar '' é seguro — mas
          // mandar o campo já aparado evita gravar espaço como identidade.
          telegramId: (dados.get('telegramId') ?? '').trim(),
        },
      })
      if (!resposta.ok) {
        // 409 = e-mail já cadastrado; 400 = validação, com "campos".
        aplicarErro(resposta)
        return
      }
      // A API exige login explícito após o cadastro, de propósito.
      setModo('login')
      setCamposComErro({})
      setAviso({ texto: 'Conta criada. Faça login para continuar.', sucesso: true })
      queueMicrotask(() => {
        if (emailDoLogin.current) emailDoLogin.current.value = email
        senhaDoLogin.current?.focus()
      })
    } catch {
      setAviso({ texto: 'Servidor indisponível. Tente de novo.', sucesso: false })
    } finally {
      setEnviando(false)
    }
  }

  const marca = (campo) => (camposComErro[campo] ? 'com-erro' : undefined)

  return (
    <main className="tela-entrada">
      <div className="cartao-entrada">
        <h1 className="marca">RaspyBank</h1>
        <p className="subtitulo">Controle financeiro da casa</p>

        {modo === 'login' ? (
          <form onSubmit={aoEntrar} autoComplete="on">
            <label htmlFor="login-email">E-mail</label>
            <input
              type="email" id="login-email" name="email" required
              autoComplete="username" ref={emailDoLogin} className={marca('email')}
            />

            <label htmlFor="login-senha">Senha</label>
            <input
              type="password" id="login-senha" name="senha" required
              autoComplete="current-password" ref={senhaDoLogin} className={marca('senha')}
            />

            <Aviso aviso={aviso} />
            <button type="submit" className="botao-principal" disabled={enviando}>
              {enviando ? 'Entrando…' : 'Entrar'}
            </button>
          </form>
        ) : (
          <form onSubmit={aoCadastrar} autoComplete="on">
            <label htmlFor="cadastro-nome">Nome</label>
            <input
              type="text" id="cadastro-nome" name="nome" required
              autoComplete="name" className={marca('nome')}
            />

            <label htmlFor="cadastro-email">E-mail</label>
            <input
              type="email" id="cadastro-email" name="email" required
              autoComplete="username" className={marca('email')}
            />

            <label htmlFor="cadastro-senha">Senha</label>
            <input
              type="password" id="cadastro-senha" name="senha" required
              minLength={10} maxLength={72} autoComplete="new-password"
              className={marca('senha')}
            />
            <small className="dica">Ao menos 10 caracteres.</small>

            {/*
              OPCIONAL, e é o que vai ligar você ao bot (etapa 3 do roteiro). Fica
              no cadastro porque é lá que o valor pode entrar: o caminho de
              escrita é a própria inserção do usuário (V18) — no cadastro não há
              identidade na sessão, e um preenchimento posterior seria recusado
              pela política do banco.
            */}
            <label htmlFor="cadastro-telegram">Telegram (opcional)</label>
            <input
              type="text" id="cadastro-telegram" name="telegramId"
              maxLength={64} autoComplete="off" placeholder="@seu_usuario"
              className={marca('telegramId')}
            />
            <small className="dica">
              Seu usuário ou o id numérico. Serve para o bot saber quem está
              lançando — deixe em branco se ainda não usa.
            </small>

            <Aviso aviso={aviso} />
            <button type="submit" className="botao-principal" disabled={enviando}>
              {enviando ? 'Criando…' : 'Criar conta'}
            </button>
          </form>
        )}

        <p className="alternar">
          <span>{modo === 'login' ? 'Ainda não tem conta?' : 'Já tem conta?'}</span>
          <button
            type="button" className="botao-texto"
            onClick={() => alternar(modo === 'login' ? 'cadastro' : 'login')}
          >
            {modo === 'login' ? 'Cadastre-se' : 'Entrar'}
          </button>
        </p>
      </div>
    </main>
  )
}
