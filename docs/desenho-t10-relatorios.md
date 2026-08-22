# RaspyBank — Desenho da T-10: Relatórios

**Data:** 19/08/2026 · **Estado:** **SUPERADO em 20/08/2026** · **Implementado:** ver abaixo

> ## Leia isto antes — SUPERADO, e por outro caminho
>
> Este documento é **desenho, não decisão**, e nunca foi fonte de verdade — `docs/decisoes.md`
> é. Em 20/08/2026 o Abner e a investigação do dia decidiram **diferente do que está escrito
> aqui**: a T-10 saiu **síncrona**, sem fila, sem tabela de estado, sem executor e sem volume
> em disco. As decisões de verdade estão em `docs/decisoes.md` §4s, como **B-D116 a B-D119** —
> e valem a pena ler juntas com este arquivo, porque a diferença entre os dois é o que a
> investigação encontrou.
>
> **Armadilha para quem ler daqui a um ano:** os números **B-D116 a B-D119** abaixo, neste
> arquivo, **têm outro conteúdo** dos B-D116–B-D119 que existem de verdade. Aqui, B-D116 é
> "identidade em thread de fundo" — a peça da fila que a decisão real **recusou**. Em
> `decisoes.md`, B-D116 é "o relatório é síncrono, com teto de 12 meses". Mesmo número, duas
> conversas diferentes: os números deste rascunho nunca passaram a valer, e a numeração real
> só foi atribuída no dia em que a decisão de verdade fechou. Se você está procurando o que
> B-D116 diz, vá para `decisoes.md` — nunca para aqui.
>
> O que sobreviveu da investigação registrada abaixo: uma aba por ambiente, a máscara da linha
> alheia, dinheiro como número no `.xlsx`, e a leitura por `SECURITY DEFINER`. O que não
> sobreviveu: a fila inteira — tabela `relatorio`, executor, propagação de identidade entre
> threads, volume no Pi, 4 endpoints e polling. O motivo de a fila ter caído está em
> `decisoes.md` §4s: o Mapa de Gastos já fazia a parte cara a cada abertura, e um teto de 12
> meses bastou para limitar o pior caso sem nenhuma daquelas peças.
>
> Este arquivo fica no repositório como registro do caminho que **não** foi tomado e do porquê
> — não é lixo, é a investigação que tornou a decisão de 20/08/2026 possível de defender. Mas
> ele não é fonte de verdade e nunca foi: quando ele contradisser `decisoes.md`, `decisoes.md`
> vence, sempre.

---

## Contexto

Hoje o RaspyBank não tem como tirar os dados de dentro dele. Toda leitura é tela, recortada
por mês e por ambiente ativo, e não existe um único endpoint que devolva arquivo. Quem quiser
conferir a vida financeira inteira contra o banco, montar uma análise própria ou simplesmente
guardar uma cópia, não tem caminho.

O pedido: uma seção nova **Relatórios** no menu, com um botão **Baixar tudo** e uma grade que
mostra os pedidos em processamento e libera o arquivo quando termina. O arquivo é um `.xlsx`
com **uma aba por ambiente** do usuário, contendo **todos os lançamentos** — de todas as contas
e todos os cartões, sem recorte de período.

Quatro decisões já tomadas em conversa, que este plano executa:

| | Decidido |
|---|---|
| Ambientes | Todos em que o usuário tem vínculo — o que o seletor de ambiente lista, inclusive ambiente de outra pessoa que o convidou |
| Linha alheia em conta/cartão dividido | **Entra, mascarada** (sem descrição e sem categoria), para o extrato fechar com a T-05 |
| Onde o arquivo espera | Disco do Pi, em volume. **Apagado assim que ele baixa**; expira em 7 dias se nunca for baixado |
| Conteúdo | Tudo — inclusive transferência, ajuste e previsto — com colunas `situação` e `entra no mapa` para ele reproduzir o número do mapa filtrando no próprio Excel |

## O que a exploração mudou no desenho

Três achados que valem mais que qualquer escolha de biblioteca:

**1. A RLS já libera todos os ambientes — o relatório multi-ambiente não fura nada.**
`pol_lancamento_ambiente` (`V10__dominio_lancamento.sql:515`) é
`ambiente_id IN (SELECT app_ambientes_do_usuario())`, e `raspybank.ambiente_id` é escrito pelo
aspecto mas **nunca lido por política nenhuma**. O recorte por ambiente ativo é Java, por
usabilidade (B-D21), não isolamento. Uma aba por ambiente é o formato natural do que o banco
entrega; basta **não** estreitar. Nenhum privilégio novo é necessário para atravessar ambientes.

**2. A máscara mora no banco, e uma consulta direta a perderia inteira.**
`app_extrato_da_conta` (`V16__compartilhamento_de_conta.sql:700`) faz
`CASE WHEN v.meu THEN l.descricao END`, com `meu = l.ambiente_id IN (SELECT app_ambientes_do_usuario())`.
Consulta direta em `lancamento` nunca traz linha alheia — a RLS a corta antes —, então o extrato
de uma conta dividida **não fecharia com a T-05**. Por isso o relatório lê por função
`SECURITY DEFINER`, não por repositório.

**3. O aspecto de RLS não é acoplado a HTTP — ele é acoplado a `@Transactional`.**
`ConfiguradorSessaoRls` (`raspybank-shared/.../persistencia/ConfiguradorSessaoRls.java`) roda em
qualquer método transacional e lê `ContextoRequisicao`, que é `ThreadLocal` **puro** (não
herdável) preenchido só pelo `FiltroAutenticacaoJwt`. Uma thread de fundo, portanto, roda o
aspecto e **não tem identidade**: `app_usuario_id()` vira NULL, nenhuma política casa, e a
leitura devolve zero linhas **sem erro nenhum**. É o mesmo modo de falha silenciosa que hoje
mantém o outbox parado (I-30), e foi o motivo pelo qual B-D43 recusou um job agendado.

A saída não é função nova de banco — é **propagar a identidade para a thread**. É o caminho que
B-D43 deixou explicitamente "para discussão própria", e esta entrega é essa discussão.

## Decisões novas a registrar (mesmo commit da implementação)

| # | Decisão |
|---|---|
| **B-D116** | **O trabalho de fundo herda identidade por `ContextoRequisicao.definir(...)` na própria thread, com `limpar()` em `finally`.** O `usuarioId` vem da linha de pedido gravada dentro da requisição — nunca de varredura sem identidade. Canal `SISTEMA`. Nenhuma `SECURITY DEFINER` nova para "o worker enxergar tudo": isso furaria B-D19 |
| **B-D117** | **O relatório atravessa ambientes de propósito, e é a única leitura que o faz.** B-D111 (o escopo segue o ambiente ativo) continua valendo para toda tela; o arquivo é o oposto por natureza — é o retrato da pessoa, não da tela aberta. Uma aba por ambiente mantém a separação de vidas *dentro* do arquivo |
| **B-D118** | **No `.xlsx`, dinheiro é número e data é data** — não string. A convenção "dinheiro é string" governa JSON, onde o risco é `double`; numa planilha feita para somar, string é o defeito. Colunas `valor` (sempre positivo, como no banco) e `valor com sinal` (derivada do `tipo`, é a que soma) |
| **B-D119** | **Faxina e recuperação acontecem na leitura, não em job agendado** — o padrão de B-D43. Ao listar a fila, pedidos `PROCESSANDO` órfãos (aplicação reiniciada) viram `ERRO`, e pedidos expirados têm o arquivo apagado. Tudo dentro da requisição de quem é dono, com identidade e RLS |

## Migração — V21 (P3: banco antes do código)

`raspybank-app/src/main/resources/db/migration/V21__relatorios.sql`, por `banco-e-migracoes`.

**Tabela `relatorio`** — a fila que a tela mostra:

```
id uuid pk · usuario_id uuid not null · ambiente_id uuid           (o ativo no pedido, só rastro)
tipo text not null CHECK (tipo IN ('EXTRATO_COMPLETO'))            (extensível, hoje um só)
estado text not null CHECK (estado IN ('PENDENTE','PROCESSANDO','PRONTO','BAIXADO','ERRO','EXPIRADO'))
arquivo_nome text · tamanho_bytes bigint · linhas integer · erro text
criado_em · iniciado_em · concluido_em · baixado_em · expira_em
```

Sem coluna de total ou agregado (P1) — `linhas` e `tamanho_bytes` descrevem o **arquivo**, não o
dinheiro. O caminho em disco **não** é coluna: é derivado do `id`, para que mover o volume não
exija UPDATE.

**RLS** — primeira tabela do sistema cujo dono é o usuário direto, sem passar por ambiente:

```sql
CREATE POLICY pol_relatorio_dono ON relatorio FOR ALL
    USING (usuario_id = app_usuario_id()) WITH CHECK (usuario_id = app_usuario_id());
```

Acrescentar a tabela à lista de `MigracoesTest.rlsLigadoNasTabelas`, senão o build quebra.

**Função `app_extrato_completo()`** — da quarta exceção (B-D96), mesma família de
`app_extrato_da_conta`, e pelo mesmo motivo: listar lançamentos que a política corretamente
esconde. Sem parâmetro, logo **sem porteiro a escrever** — ela deriva tudo de `app_usuario_id()`
e devolve vazio se ele for NULL. Uma chamada só, em vez de uma por conta (importa num Pi).

Devolve, por linha: `ambiente_da_aba`, `conta_id`, `conta_nome`, `cartao_nome`, `plastico`,
`data_caixa`, `data_competencia`, `tipo`, `situacao`, `valor`, `forma_pagamento`, `fatura_mes`,
`parcela_numero`, `parcela_total`, `grupo_parcelamento_id`, `quem_nome`, `meu`, e — sob
`CASE WHEN meu THEN … END` — `descricao`, `observacao`, `categoria_nome`, `subcategoria_nome`,
`entra_no_mapa`.

`ambiente_da_aba` resolve a pergunta que a máscara cria: a linha alheia nasceu no ambiente da
outra pessoa, então ela vai para a aba do **meu** ambiente que enxerga aquela conta (via
`conta_ambiente`, preferindo `origem = true`). Linha minha vai pelo `lancamento.ambiente_id`,
que é o ambiente ativo na criação (B-D2).

Obrigatórios do padrão: `SECURITY DEFINER` + `SET search_path = public, pg_temp` +
`COMMENT ON FUNCTION` + `GRANT EXECUTE TO raspybank_app` + entrada em
`docs/security-definer.md` **e** em `MigracoesTest.DEFINER_INVENTARIADAS` — o teste quebra o
build se faltar qualquer um.

## Módulo novo — `raspybank-relatorio`

Relatório tem ciclo próprio (pedido, estado, arquivo, expiração) e **não pode conhecer
lançamento** — é exatamente o caso em que a fronteira se paga. Contém:

- `dominio/Relatorio.java`, `dominio/EstadoRelatorio.java`
- `repositorio/RelatorioRepositorio.java`
- `servico/RelatorioServico.java` — criar pedido, transitar estado, faxina na leitura (B-D119)
- `servico/ArmazenamentoDeRelatorio.java` — grava, lê e apaga o arquivo no volume
- `servico/EscritorXlsx.java` — **genérico**: recebe abas, cabeçalhos e células tipadas. Não
  conhece dinheiro nem lançamento, e é por isso que o módulo não precisa conhecer `lancamento`

Registrar em **quatro** lugares — `<modules>` e `<dependencyManagement>` da raiz, dependências
de `raspybank-app`, e os dois blocos `COPY` do `Dockerfile` (esquecer aqui quebra `make gate` e
só no fim). Mais `ArquiteturaTest`: constante do contexto novo e teste de isolamento **nos dois
sentidos**.

**Biblioteca de Excel:** nenhuma existe hoje. Recomendo **`org.dhatim:fastexcel`** (escrita,
streaming, sem XmlBeans) em vez de POI: o alvo é um Pi 4 com `-XX:+UseSerialGC` e Postgres
limitado a 1 GB, e POI materializa a planilha em memória a menos que se use SXSSF. Fixar versão
**estável há mais de uma semana**, conforme a regra de supply chain do projeto.

## Montagem — em `raspybank-app`

A costura entre contextos é o que `raspybank-app` existe para fazer (é o que
`RelatorioControlador` já faz hoje com `AmbienteServico` + `MapaDeGastosServico`).

- **`ExtratoCompletoMontador`** — chama `AmbienteServico.listarDoUsuario()` (nome das abas) e a
  função `app_extrato_completo()`, agrupa por `ambiente_da_aba`, e entrega ao `EscritorXlsx`.
  Antes de ler, chama `SituacaoServico.sincronizar(ambienteId, hoje)` **uma vez por ambiente** —
  não por conta nem por fatura, que multiplicaria UPDATEs em lote e gatilhos de auditoria sem ganho.
- **`ProcessadorDeRelatorios`** — `ThreadPoolTaskExecutor` de **uma** thread, fila limitada. A
  fila que o usuário vê é a **tabela**, não a do executor. O `submit` acontece dentro da
  requisição, carregando `usuarioId`/`ambienteId` no objeto da tarefa; a thread abre com
  `ContextoRequisicao.definir(usuarioId, ambienteId, null, Canal.SISTEMA)` e **fecha com
  `limpar()` em `finally`** — sem isso, a thread reaproveitada herda identidade alheia, que é o
  pior defeito possível neste sistema.
- Isso evita de propósito o problema do I-30: o worker **nunca varre** por trabalho pendente sem
  identidade; ele sempre recebe de quem tinha sessão.

Nomes de aba: `Ambiente.nome` saneado — 31 caracteres, sem `[ ] : * ? / \`, com sufixo numérico
quando dois ambientes colidirem depois do corte.

Primeira aba, **"Sobre este arquivo"**: quando foi gerado, quais ambientes entraram, o que está
mascarado e por quê (B-D89/B-D109), e o aviso de que o total **não** bate com o Mapa de Gastos
porque transferência, ajuste e previsto entram aqui — com a coluna `entra no mapa` como o
caminho para reproduzir o número. É a sinalização que o I-31 diz faltar na T-08.

## Borda HTTP — `RelatorioControlador` (já existe, `@RequestMapping("/api/relatorios")`)

| Verbo | Rota | O que faz |
|---|---|---|
| `POST` | `/api/relatorios/pedidos` | Corpo `{"tipo":"EXTRATO_COMPLETO"}`. **202** com o pedido. Recusa **409** se já houver um `PENDENTE`/`PROCESSANDO` do mesmo usuário — dois cliques não viram dois arquivos |
| `GET` | `/api/relatorios/pedidos` | A fila do usuário. É aqui que a faxina de B-D119 roda |
| `GET` | `/api/relatorios/pedidos/{id}/arquivo` | **O primeiro endpoint binário do projeto.** `Content-Disposition: attachment`, `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`. Ao terminar de transmitir, marca `BAIXADO` e **apaga o arquivo** |
| `DELETE` | `/api/relatorios/pedidos/{id}` | Tira da grade (e apaga o arquivo, se houver) |

Registrar os quatro em `docs/api.md`, junto do endpoint — nunca depois.

## Infra — o volume que não existe

Hoje `infra/compose.pi.yaml` não declara volume nenhum para o serviço `app`, e o container roda
como usuário sem privilégio `raspybank` num filesystem efêmero. Por `infra-e-implantacao`:

- volume nomeado montado em `/var/lib/raspybank/relatorios`, com dono `raspybank` (o `Dockerfile`
  precisa criar o diretório com o dono certo antes do `USER`)
- propriedade `raspybank.relatorios.diretorio` no `application.yml`, com padrão de
  desenvolvimento fora do container
- o mesmo em `infra/compose.yaml`, senão `make app` local grava em lugar nenhum

O arquivo vive **fora** do backup do Postgres. É deliberado: relatório se regera, não se restaura.

## Frontend — T-10

- `raspybank-web/src/telas/Relatorios.jsx`, no padrão de `MapaDeGastos.jsx`
  (`<section className="painel">`, `useCarregar`, `buscar` em `useCallback`)
- Registrar em **quatro** lugares: `App.jsx` (rota), `Casca.jsx` (array `ITENS`),
  `SpaControlador.java` e `SegurancaConfig.java` — as duas listas de rota do backend precisam
  andar juntas, e o javadoc do `SpaControlador` avisa disso
- **Polling**: não existe nenhum no projeto hoje. Recarrega a cada 3 s **enquanto** houver
  `PENDENTE`/`PROCESSANDO`, e para quando a fila esfria — nunca um `setInterval` eterno
- **Download**: `api/cliente.js` sempre faz `text()` + `JSON.parse` e o token vive no
  `localStorage`, então `<a href>` simples não carrega `Authorization`. Precisa de uma função
  nova (`pedirArquivo`) que devolva `blob()` e monte `URL.createObjectURL` — com `revokeObjectURL`
  depois. Ele acessa do desktop e do celular, então o nome do arquivo tem que vir no
  `Content-Disposition` e ser lido de lá
- O menu é **lateral esquerdo** (`.casca > .corpo > .menu`), não direito — vale confirmar se é
  isso mesmo que você quer antes de eu mexer no CSS

## Testes — `qa-adversarial`, depois da entrega

Os que importam, todos de quebrar:

1. **RLS na direção errada**: o relatório de A não contém nenhuma linha de B. O teste monta dois
   usuários sem vínculo e confere linha a linha
2. **Vazamento de ThreadLocal**: dois pedidos de usuários diferentes na **mesma** thread do
   executor, em sequência — o segundo arquivo não pode conter nada do primeiro. É o teste que
   justifica o `finally`
3. **Máscara**: linha alheia em conta dividida aparece com valor, data e quem, e com descrição,
   categoria e observação **vazias**
4. **Uma aba por ambiente**, e lançamento no ambiente errado é falha
5. **Extrato fecha**: soma da coluna com sinal, para uma conta dividida, igual a
   `app_saldo_da_conta` da mesma conta
6. **Recuperação**: `PROCESSANDO` órfão vira `ERRO` ao listar, e não fica girando para sempre
7. **Download apaga**: segundo GET no mesmo arquivo devolve 404/410, não o arquivo de novo
8. **409** no segundo pedido enquanto o primeiro roda

## Verificação

```
make build                       # compila os 7 módulos + testes
make arch                        # ArquiteturaTest com o contexto novo declarado
make db-reset && make test       # V21 aplicada em base limpa (exigência de migração nova)
make gate                        # imagem real e subida — o portão antes de entregar
```

Depois, à mão: entrar, abrir **Relatórios**, clicar **Baixar tudo**, ver a linha aparecer na
grade e virar "pronto", baixar, abrir no LibreOffice e conferir (a) uma aba por ambiente, (b)
dinheiro somável e data como data, (c) a aba de capa, (d) a linha alheia mascarada numa conta
dividida, (e) a linha some da grade como baixada e o arquivo sumiu do volume.

## Ordem de execução

decisão registrada → **V21** (`banco-e-migracoes`) → módulo e escritor → montador e worker
(`dominio-lancamento` + montagem em app) → borda HTTP (`api-e-contrato`) → volume
(`infra-e-implantacao`) → tela (`frontend-web`) → `qa-adversarial` → `escriba` (decisões, api,
mapa-telas, security-definer) → `revisor-de-fronteiras` → `make gate` → commit.

## O que ainda está em aberto

Nada aqui está decidido, e nenhuma destas perguntas precisa de resposta para o plano acima
existir — mas todas mudam o tamanho dele. Ficam registradas para você mastigar:

**1. Um relatório ou uma família?** O plano trata `EXTRATO_COMPLETO` como o primeiro de uma
lista (o `CHECK` do `tipo` já é extensível). Se a intenção for chegar a "fechamento mensal",
"gastos por cartão", "o que mandar para o contador", vale desenhar o segundo **agora**, no papel
— porque é o segundo que revela se a tabela e a tela aguentam família, ou se foram desenhadas
para um caso só.

**2. Tudo desde sempre, ou faixa escolhível?** "Baixar tudo" hoje significa toda a história. Uma
faixa de datas é uma linha no formulário e um parâmetro na função — mas deixa de ser um botão só,
e a tela vira formulário.

**3. `.xlsx` é o único formato?** CSV é quase de graça (o mesmo montador, outro escritor), abre no
celular sem app e é o que ferramenta externa costuma querer. O `.xlsx` é o que ganha aba por
ambiente, número formatado e data de verdade. Podem coexistir sem custo real.

**4. O extrato precisa de saldo corrente?** Uma coluna de saldo acumulado por conta é o que
transforma o dump em algo que se confere linha a linha contra o extrato do banco. Não existe
hoje em lugar nenhum, e derivá-la exige ordenação estável e uma decisão sobre o que fazer com
`PREVISTO` no meio da soma — que é justamente onde a T-05 e a T-08 já divergem (I-31).

**5. O arquivo só desce pelo navegador?** O bot do Telegram já está desenhado (D1–D8) e um
relatório pronto é exatamente o tipo de coisa que se quer receber no celular sem abrir o site.
Se isso for para acontecer, o `PRONTO` deveria disparar um evento — e aí a tabela `relatorio`
vira produtora de outbox, e o I-30 deixa de ser adiável.

**6. A fila vale a pena agora?** É a pergunta do custo, logo abaixo. Ela é reversível numa
direção só: começar síncrono e crescer para a fila é barato; começar com a fila e descobrir que
ela nunca teve fila é seis peças de infraestrutura para manter sem motivo.

## O custo, dito em voz alta

O sistema não tem uma única linha de assincronismo hoje. Esta entrega traz executor, tabela de
estado, propagação de identidade entre threads, volume novo, endpoint binário e polling — seis
coisas que não existem — para um arquivo que, na base atual, provavelmente fica pronto antes de
o primeiro polling acontecer. A fila é infraestrutura para um problema que você ainda não tem.

O que a paga: é a mesma propagação de identidade que o relay do outbox (I-30) vai precisar, e
B-D116 é a decisão que B-D43 adiou. Se um dia o relatório demorar, nada muda de forma. Se você
preferir a versão de uma tarde — geração síncrona, download direto, sem tabela e sem fila —
ela é honesta e cabe em bem menos código; só não é o que você pediu, e desmontá-la depois custa
mais do que não construí-la agora.
