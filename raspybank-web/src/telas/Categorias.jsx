import { useCallback, useState } from 'react'
import { lerErro } from '../api/cliente.js'
import { categorias as apiCategorias, subcategorias as apiSubcategorias } from '../api/recursos.js'
import Aviso from '../componentes/Aviso.jsx'
import { useCarregar } from '../ganchos/useCarregar.js'

// =============================================================================
// T-04 — Categorias e subcategorias
// =============================================================================
// Dois níveis por decisão (F8): não existe sub-subcategoria, e a ausência do
// caminho é a garantia de que ninguém vai criar uma.
//
// As categorias sistêmicas aparecem com cadeado em vez de sumirem. Esconder
// faria a pessoa procurar por "Transferência" e concluir que sumiu; mostrar
// travada explica que ela existe e por que não se mexe nela.
// =============================================================================

const TIPOS = [
  { valor: 'SAIDA', rotulo: 'Saída' },
  { valor: 'ENTRADA', rotulo: 'Entrada' },
  { valor: 'AMBOS', rotulo: 'Ambos' },
]

export default function Categorias() {
  const [incluirArquivadas, setIncluirArquivadas] = useState(false)
  const buscar = useCallback(
    () => apiCategorias.listar(incluirArquivadas),
    [incluirArquivadas],
  )
  const { dados, carregando, erro, recarregar } = useCarregar(buscar)

  const [aviso, setAviso] = useState(null)
  const [editando, setEditando] = useState(null) // {escopo:'categoria'|'sub', id}
  const [criandoSubEm, setCriandoSubEm] = useState(null)
  const [ocupado, setOcupado] = useState(false)

  /** Toda escrita passa por aqui: mesma tradução de erro e mesma recarga. */
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
      return true
    } catch {
      setAviso({ texto: 'Servidor indisponível.', sucesso: false })
      return false
    } finally {
      setOcupado(false)
    }
  }

  const lista = dados?.categorias ?? []

  return (
    <section className="painel">
      <header className="cabecalho-painel">
        <h2>Categorias</h2>
        <label className="alternador">
          <input
            type="checkbox"
            checked={incluirArquivadas}
            onChange={(e) => setIncluirArquivadas(e.target.checked)}
          />
          Mostrar arquivadas
        </label>
      </header>

      <FormularioDeCategoria
        ocupado={ocupado}
        aoCriar={(nome, tipo) =>
          executar(() => apiCategorias.criar(nome, tipo), `Categoria "${nome}" criada.`)
        }
      />

      <Aviso aviso={aviso} />
      {erro && <p className="aviso" role="alert">{erro}</p>}
      {carregando && <p className="carregando">Carregando…</p>}

      {!carregando && lista.length === 0 && (
        <p className="texto-vazio">
          Nenhuma categoria ainda. Crie a primeira acima — ela vira opção no
          seletor de lançamentos.
        </p>
      )}

      <ul className="lista-categorias">
        {lista.map((categoria) => (
          <li key={categoria.id} className={categoria.arquivadaEm ? 'arquivada' : undefined}>
            <LinhaDeCategoria
              categoria={categoria}
              ocupado={ocupado}
              editando={editando?.escopo === 'categoria' && editando.id === categoria.id}
              aoEditar={() => setEditando({ escopo: 'categoria', id: categoria.id })}
              aoCancelar={() => setEditando(null)}
              aoSalvar={async (nome, tipo) => {
                const deu = await executar(() => apiCategorias.alterar(categoria.id, nome, tipo))
                if (deu) setEditando(null)
              }}
              aoArquivar={() =>
                executar(
                  () =>
                    categoria.arquivadaEm
                      ? apiCategorias.desarquivar(categoria.id)
                      : apiCategorias.arquivar(categoria.id),
                )
              }
              aoAdicionarSub={() =>
                setCriandoSubEm(criandoSubEm === categoria.id ? null : categoria.id)
              }
            />

            {criandoSubEm === categoria.id && (
              <FormularioDeSubcategoria
                ocupado={ocupado}
                aoCriar={async (nome) => {
                  const deu = await executar(
                    () => apiCategorias.criarSubcategoria(categoria.id, nome),
                    `Subcategoria "${nome}" criada.`,
                  )
                  if (deu) setCriandoSubEm(null)
                }}
                aoCancelar={() => setCriandoSubEm(null)}
              />
            )}

            {categoria.subcategorias?.length > 0 && (
              <ul className="lista-subcategorias">
                {categoria.subcategorias.map((sub) => (
                  <li key={sub.id} className={sub.arquivadaEm ? 'arquivada' : undefined}>
                    <LinhaDeSubcategoria
                      sub={sub}
                      ocupado={ocupado}
                      editando={editando?.escopo === 'sub' && editando.id === sub.id}
                      aoEditar={() => setEditando({ escopo: 'sub', id: sub.id })}
                      aoCancelar={() => setEditando(null)}
                      aoSalvar={async (nome) => {
                        const deu = await executar(() => apiSubcategorias.alterar(sub.id, nome))
                        if (deu) setEditando(null)
                      }}
                      aoArquivar={() =>
                        executar(() =>
                          sub.arquivadaEm
                            ? apiSubcategorias.desarquivar(sub.id)
                            : apiSubcategorias.arquivar(sub.id),
                        )
                      }
                    />
                  </li>
                ))}
              </ul>
            )}
          </li>
        ))}
      </ul>
    </section>
  )
}

// ----------------------------------------------------------------------------

function LinhaDeCategoria({
  categoria, ocupado, editando, aoEditar, aoCancelar, aoSalvar, aoArquivar, aoAdicionarSub,
}) {
  if (editando) {
    return (
      <form
        className="linha-recurso em-edicao"
        onSubmit={(e) => {
          e.preventDefault()
          const d = new FormData(e.target)
          aoSalvar(d.get('nome').trim(), d.get('tipo'))
        }}
      >
        <input name="nome" defaultValue={categoria.nome} required autoFocus />
        <select name="tipo" defaultValue={categoria.tipo}>
          {TIPOS.map((t) => <option key={t.valor} value={t.valor}>{t.rotulo}</option>)}
        </select>
        <div className="acoes-linha">
          <button type="submit" className="botao-texto" disabled={ocupado}>Salvar</button>
          <button type="button" className="botao-texto" onClick={aoCancelar}>Cancelar</button>
        </div>
      </form>
    )
  }

  return (
    <div className="linha-recurso">
      <span className="nome-recurso">
        {categoria.sistemica && (
          <span
            className="cadeado"
            title="Categoria do sistema: o código depende dela, por isso não se edita nem se arquiva."
          >🔒</span>
        )}
        {categoria.nome}
      </span>

      <Etiqueta tipo={categoria.tipo} />

      {!categoria.entraNoMapa && (
        <span
          className="etiqueta etiqueta-fraca"
          title="Não entra no mapa de gastos: transferência entre contas próprias e ajuste de saldo não são despesa."
        >fora do mapa</span>
      )}

      {categoria.arquivadaEm && <span className="etiqueta etiqueta-fraca">arquivada</span>}

      <div className="acoes-linha">
        {categoria.sistemica ? (
          <span className="dica-inline">do sistema</span>
        ) : (
          <>
            {!categoria.arquivadaEm && (
              <>
                <button type="button" className="botao-texto" onClick={aoEditar} disabled={ocupado}>
                  Renomear
                </button>
                <button
                  type="button" className="botao-texto" onClick={aoAdicionarSub} disabled={ocupado}
                >
                  + Subcategoria
                </button>
              </>
            )}
            <button type="button" className="botao-texto" onClick={aoArquivar} disabled={ocupado}>
              {categoria.arquivadaEm ? 'Desarquivar' : 'Arquivar'}
            </button>
          </>
        )}
      </div>
    </div>
  )
}

function LinhaDeSubcategoria({ sub, ocupado, editando, aoEditar, aoCancelar, aoSalvar, aoArquivar }) {
  if (editando) {
    return (
      <form
        className="linha-recurso em-edicao"
        onSubmit={(e) => {
          e.preventDefault()
          aoSalvar(new FormData(e.target).get('nome').trim())
        }}
      >
        <input name="nome" defaultValue={sub.nome} required autoFocus />
        <div className="acoes-linha">
          <button type="submit" className="botao-texto" disabled={ocupado}>Salvar</button>
          <button type="button" className="botao-texto" onClick={aoCancelar}>Cancelar</button>
        </div>
      </form>
    )
  }

  return (
    <div className="linha-recurso">
      <span className="nome-recurso">{sub.nome}</span>
      {sub.arquivadaEm && <span className="etiqueta etiqueta-fraca">arquivada</span>}
      <div className="acoes-linha">
        {!sub.arquivadaEm && (
          <button type="button" className="botao-texto" onClick={aoEditar} disabled={ocupado}>
            Renomear
          </button>
        )}
        <button type="button" className="botao-texto" onClick={aoArquivar} disabled={ocupado}>
          {sub.arquivadaEm ? 'Desarquivar' : 'Arquivar'}
        </button>
      </div>
    </div>
  )
}

function Etiqueta({ tipo }) {
  const rotulo = TIPOS.find((t) => t.valor === tipo)?.rotulo ?? tipo
  return <span className={`etiqueta etiqueta-${tipo.toLowerCase()}`}>{rotulo}</span>
}

function FormularioDeCategoria({ aoCriar, ocupado }) {
  return (
    <form
      className="formulario-linha"
      onSubmit={(e) => {
        e.preventDefault()
        const d = new FormData(e.target)
        aoCriar(d.get('nome').trim(), d.get('tipo'))
        e.target.reset()
      }}
    >
      <input name="nome" placeholder="Nova categoria" required maxLength={60} />
      <select name="tipo" defaultValue="SAIDA">
        {TIPOS.map((t) => <option key={t.valor} value={t.valor}>{t.rotulo}</option>)}
      </select>
      <button type="submit" className="botao-principal botao-pequeno" disabled={ocupado}>
        Criar
      </button>
    </form>
  )
}

function FormularioDeSubcategoria({ aoCriar, aoCancelar, ocupado }) {
  return (
    <form
      className="formulario-linha recuado"
      onSubmit={(e) => {
        e.preventDefault()
        aoCriar(new FormData(e.target).get('nome').trim())
      }}
    >
      <input name="nome" placeholder="Nova subcategoria" required maxLength={60} autoFocus />
      <button type="submit" className="botao-principal botao-pequeno" disabled={ocupado}>
        Criar
      </button>
      <button type="button" className="botao-texto" onClick={aoCancelar}>Cancelar</button>
    </form>
  )
}
