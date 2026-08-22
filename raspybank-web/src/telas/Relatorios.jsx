import { useCallback, useEffect, useState } from 'react'
import { lerErro } from '../api/cliente.js'
import { relatorios } from '../api/recursos.js'
import { hojeISO, NOMES_DOS_MESES } from '../util/formato.js'

// =============================================================================
// T-10 — Relatórios
// =============================================================================
// A única tela do sistema que não mostra dados: ela produz um arquivo. Por isso
// não usa `useCarregar` — aquele gancho busca ao montar, e aqui nada acontece
// até a pessoa clicar. Estado local mesmo, e de propósito.
//
// O QUE ESTA TELA SUBSTITUI
//
// O desenho anterior previa fila assíncrona: tabela de estado, executor, volume
// em disco e polling de 3 em 3 segundos. Isso foi recusado porque o arquivo sai
// em segundos e a infraestrutura não se pagava (B-D116). Mas a fila resolvia uma
// coisa real, e a pergunta que a recusou foi exatamente esta: *"não gostaria que
// o site ficasse com a bolinha rolando e o usuário não sabe se travou ou não"*.
//
// Então o estado de carregamento daqui é ENTREGA, não enfeite: é o que ficou no
// lugar da fila. Ele diz o que está sendo gerado, para qual faixa, há quantos
// segundos, e — passando de QUINZE_SEGUNDOS — que demorar é normal e a página
// não travou. O padrão do projeto (`<p className="carregando">Carregando…</p>`)
// não serviria aqui: ele é bom para um instante, e mentiria numa espera longa.
// =============================================================================

/** A partir daqui a espera deixa de ser instantânea e passa a precisar de explicação. */
const QUINZE_SEGUNDOS = 15

// =============================================================================
// As frases de erro, copiadas do servidor
// =============================================================================
// Estas duas strings são CÓPIA LITERAL das que `RelatorioControlador` devolve
// no `{"erro": ...}` do endpoint do extrato. Não são redações desta tela.
//
// Existem aqui só para a recusa ser imediata em vez de custar uma ida e volta —
// a mesma faixa recusada aqui seria recusada lá, com estas mesmas palavras. Por
// isso a cópia é literal: duas redações do mesmo erro fariam a pessoa ler uma
// frase no clique e outra na volta, e concluir que são problemas diferentes.
//
// Mudou a frase no servidor? Muda aqui junto. É duplicação consciente, e a
// alternativa (pedir ao servidor para saber o que dizer) é justamente a ida e
// volta que estas constantes evitam.
// =============================================================================

const TETO_DE_DOZE_MESES =
  'O período não pode passar de 12 meses. Ajuste a data inicial ou a final e tente de novo.'

const FIM_ANTES_DO_INICIO = 'A data final não pode ser anterior à inicial.'

export default function Relatorios() {
  const [inicio, setInicio] = useState(() => padraoDeInicio(hojeISO()))
  const [fim, setFim] = useState(hojeISO)

  /**
   * A faixa que está sendo gerada AGORA — não um booleano.
   *
   * Guardar `{ inicio, fim }` congela o que foi pedido: se a pessoa mexer nos
   * campos enquanto o arquivo vem, a frase de espera continua descrevendo o
   * pedido que está no ar, e não o que ela acabou de digitar. Um booleano
   * deixaria a tela contar a faixa errada.
   */
  const [gerando, setGerando] = useState(null)
  const [segundos, setSegundos] = useState(0)
  const [erro, setErro] = useState(null)
  const [pronto, setPronto] = useState(null)

  // O cronômetro nasce e morre com a geração. Sem ele, "está gerando" seria
  // indistinguível de "travou" — que é o defeito inteiro que esta tela evita.
  useEffect(() => {
    if (!gerando) return undefined
    setSegundos(0)
    const relogio = setInterval(() => setSegundos((s) => s + 1), 1000)
    return () => clearInterval(relogio)
  }, [gerando])

  const baixar = useCallback(
    async (evento) => {
      evento.preventDefault()
      if (gerando) return // o botão já está desabilitado; isto é o cinto

      setErro(null)
      setPronto(null)

      // As duas recusas que a tela antecipa. Cortesia, não garantia: o servidor
      // valida tudo de novo, e é ele quem manda. O ganho é a pessoa saber na
      // hora do clique, sem esperar uma ida e volta para ouvir o óbvio.
      //
      // Comparar duas strings ISO é comparação de datas de verdade:
      // "2026-01-02" > "2026-01-01" tanto como texto quanto como dia. Nenhum
      // `Date` no caminho, nenhum fuso inventado (B-D114).
      //
      // Data AUSENTE não é checada aqui de propósito — os campos são
      // `required`, então o navegador barra antes com a mensagem dele, na
      // língua dele. Inventar uma terceira frase para isso não ajudaria.
      if (inicio && fim && fim < inicio) {
        setErro(FIM_ANTES_DO_INICIO)
        return
      }
      if (passaDoTeto(inicio, fim)) {
        setErro(TETO_DE_DOZE_MESES)
        return
      }

      const faixa = { inicio, fim }
      setGerando(faixa)
      try {
        const resposta = await relatorios.extrato(faixa.inicio, faixa.fim)

        if (!resposta.ok) {
          // Mesmo caminho de erro de todas as outras telas: o download passa
          // por `pedir` com `comoArquivo`, e uma resposta de erro continua
          // sendo lida como o `{"erro": ...}` do contrato.
          setErro(lerErro(resposta).mensagem)
          return
        }

        const nome = resposta.nomeArquivo || `extrato-${faixa.inicio}-a-${faixa.fim}.xlsx`
        salvar(resposta.blob, nome)
        setPronto(nome)
      } catch {
        // Falha de rede de verdade — a única coisa que `pedir` deixa virar
        // exceção. Aqui ela merece frase própria em vez do "Servidor
        // indisponível." genérico: numa espera longa o mais provável não é o
        // servidor estar fora do ar, e sim a conexão ter caído no meio do
        // arquivo. Dizer isso é o que faz a pessoa tentar de novo em vez de
        // achar que o sistema quebrou.
        setErro('A conexão caiu antes de o arquivo chegar. Tente baixar de novo.')
      } finally {
        setGerando(null)
      }
    },
    [inicio, fim, gerando],
  )

  return (
    <section className="painel">
      <header className="cabecalho-painel">
        <h2>Relatórios</h2>
      </header>

      {erro && <p className="aviso" role="alert">{erro}</p>}
      {pronto && (
        <p className="aviso sucesso" role="status">
          Extrato baixado: <strong>{pronto}</strong>
        </p>
      )}

      <form onSubmit={baixar}>
        <div className="campos-lado-a-lado">
          <label>
            De
            <input
              type="date"
              value={inicio}
              max={fim || undefined}
              required
              disabled={!!gerando}
              onChange={(e) => setInicio(e.target.value)}
            />
          </label>
          <label>
            Até
            <input
              type="date"
              value={fim}
              min={inicio || undefined}
              required
              disabled={!!gerando}
              onChange={(e) => setFim(e.target.value)}
            />
          </label>
        </div>

        <button
          type="submit"
          className="botao-principal botao-pequeno"
          // Desabilitado enquanto roda para que dois cliques não virem dois
          // downloads: o segundo pedido geraria o mesmo arquivo de novo, e a
          // pessoa acabaria com duas cópias e o dobro do trabalho no servidor.
          disabled={!!gerando}
        >
          {gerando ? 'Gerando…' : 'Baixar extrato'}
        </button>
      </form>

      {gerando && <Espera faixa={gerando} segundos={segundos} />}

      <p className="rodape-painel">
        O arquivo traz <strong>uma aba por ambiente</strong> — inclusive os
        compartilhados com você — e, dentro de cada uma, todos os lançamentos do
        período, do mais recente para o mais antigo. A primeira aba explica o
        que o arquivo contém.
      </p>
    </section>
  )
}

// ----------------------------------------------------------------------------

/**
 * A espera, dita por extenso.
 *
 * Três informações, e cada uma responde a uma pergunta que a bolinha girando
 * não responde: o QUE está sendo feito, para QUAL faixa, e há QUANTO tempo. A
 * quarta só aparece quando precisa — passando de quinze segundos, o silêncio
 * começa a parecer travamento, e aí a tela diz que não é.
 */
function Espera({ faixa, segundos }) {
  return (
    <div className="espera-longa" role="status" aria-live="polite">
      <p className="espera-titulo">
        Gerando o extrato de {mesCurto(faixa.inicio)} a {mesCurto(faixa.fim)}…
        {' '}
        <span className="espera-relogio">{segundos}s</span>
      </p>
      {segundos >= QUINZE_SEGUNDOS && (
        <p className="espera-paciencia">
          Períodos longos levam mais tempo. A página não travou — o download
          começa sozinho assim que o arquivo ficar pronto.
        </p>
      )}
    </div>
  )
}

// ----------------------------------------------------------------------------

/**
 * Entrega o blob ao navegador como download.
 *
 * O `revokeObjectURL` está no `finally` de propósito: sem ele, cada download
 * deixa o arquivo inteiro preso na memória da aba até ela ser fechada — e esta
 * é uma aba que fica aberta o dia todo. É vazamento silencioso, do tipo que só
 * aparece como "o navegador está lento" semanas depois.
 *
 * O tique de atraso também não é enfeite: revogar na MESMA volta do laço de
 * eventos do clique cancela o download antes de ele começar em alguns
 * navegadores. Um tique depois o navegador já leu o blob, e a URL pode morrer.
 */
function salvar(blob, nome) {
  const url = URL.createObjectURL(blob)
  try {
    const ancora = document.createElement('a')
    ancora.href = url
    ancora.download = nome
    document.body.appendChild(ancora)
    ancora.click()
    ancora.remove()
  } finally {
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
}

// =============================================================================
// O teto de 12 meses, do mesmo jeito que o servidor conta
// =============================================================================
// O servidor recusa com `fim.isAfter(inicio.plusMonths(12))`: meses de
// CALENDÁRIO, teto inclusive — 12 meses exatos passam, um dia a mais recusa.
// Nada de "365 dias", que erraria em todo ano bissexto.
//
// Contar diferente aqui seria pior do que não contar: existiria uma faixa que a
// tela aceita e o servidor recusa (feio, mas contornável) ou uma que a tela
// recusa e o servidor aceitaria — e essa a pessoa não tem como contornar, porque
// o botão simplesmente não deixa. Por isso `maisDozeMeses` é o espelho exato de
// `LocalDate.plusMonths(12)`, inclusive no aparo do dia que não existe no mês de
// destino (29/02/2028 + 12 meses = 28/02/2029, e não 01/03).
//
// Tudo fatiado como texto, sem `Date` no caminho da data em si:
// `new Date("2026-08-20")` é lido como UTC e, a oeste de Greenwich, já nasce no
// dia 19 (B-D114). O `Date` só aparece como calendário — para perguntar quantos
// dias tem um mês e para somar um dia —, sempre montado a partir de componentes
// locais, que é a construção que não passa por fuso.
// =============================================================================

const juntar = (ano, mes, dia) =>
  `${ano}-${String(mes).padStart(2, '0')}-${String(dia).padStart(2, '0')}`

/** Um ano de calendário para frente (`passo` +1) ou para trás (−1), com aparo. */
function umAno(iso, passo) {
  const [ano, mes, dia] = String(iso).slice(0, 10).split('-').map(Number)
  const alvo = ano + passo
  // Dia 0 do mês seguinte é o último dia deste mês — o jeito de perguntar ao
  // calendário quantos dias fevereiro tem naquele ano, sem tabela de bissexto.
  const ultimoDiaDoMes = new Date(alvo, mes, 0).getDate()
  return juntar(alvo, mes, Math.min(dia, ultimoDiaDoMes))
}

/** O espelho de `LocalDate.plusMonths(12)` do servidor. */
const maisDozeMeses = (iso) => umAno(iso, +1)

/**
 * A mesma pergunta que o servidor faz, com o mesmo resultado.
 *
 * Faixa incompleta não passa por aqui: sem as duas datas não há o que comparar,
 * e quem recusa data ausente é o `required` do campo (e o servidor, depois).
 */
function passaDoTeto(inicio, fim) {
  if (!inicio || !fim) return false
  return fim > maisDozeMeses(inicio)
}

/** Um dia para frente. O `Date` normaliza a virada de mês e de ano sozinho. */
function diaSeguinte(iso) {
  const [ano, mes, dia] = String(iso).slice(0, 10).split('-').map(Number)
  const d = new Date(ano, mes - 1, dia + 1)
  return juntar(d.getFullYear(), d.getMonth() + 1, d.getDate())
}

/**
 * O padrão da tela: os últimos 12 meses, que é também o padrão do servidor
 * quando nenhuma data é enviada.
 *
 * O ajuste no fim não é paranoia — é O caso que quebraria a tela em silêncio.
 * O aparo de `umAno` não é reversível: em 29/02/2028, um ano para trás dá
 * 28/02/2027 (o dia 29 não existe lá), e 28/02/2027 + 12 meses volta como
 * 28/02/2028 — um dia ANTES do fim. A faixa padrão estouraria o próprio teto, e
 * o botão recusaria o formulário recém-aberto, sem a pessoa ter tocado em nada.
 *
 * Um dia para frente resolve: 01/03/2027 a 29/02/2028 cabe no teto com folga de
 * um dia, e nos outros 1460 dias do ciclo a condição nem chega a ser verdadeira.
 */
function padraoDeInicio(hoje) {
  const candidato = umAno(hoje, -1)
  return passaDoTeto(candidato, hoje) ? diaSeguinte(candidato) : candidato
}

/** "2026-08-20" -> "ago/2026". O dia não importa para descrever uma faixa. */
function mesCurto(iso) {
  if (!iso) return '—'
  const [ano, mes] = String(iso).slice(0, 10).split('-')
  return `${NOMES_DOS_MESES[Number(mes) - 1].toLowerCase()}/${ano}`
}
