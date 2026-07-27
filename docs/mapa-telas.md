# RaspyBank — Mapa de Telas

**Versão:** 1.1 (todas as perguntas fechadas; ordem de execução da API definida em §6)
**Data:** 26 de julho de 2026
**Regra deste documento:** aqui vivem as decisões de PRODUTO das telas — o que existe, o que mostra, o que fica de fora. Pergunta sem resposta vira `P-T##` na seção 5 e não trava o resto; respondida, vira decisão com a mesma numeração. Decisão técnica de implementação (stack, biblioteca) também é registrada aqui enquanto não houver documento próprio de frontend.

---

## 1. A visão (como contada em 23/07/2026)

> "Um sistema que tem a tela de login e de cadastro e, quando isso feito, joga para uma tela principal na qual mostra o ambiente criado inicialmente. Do lado esquerdo, menu para cadastrar as categorias, as contas e os cartões de crédito. Na tela central, o mapeamento dos gastos: um quadro com os gastos somados por categoria, mês a mês, depois o total; abaixo, um quadro para cada categoria com a sua subcategoria somada."

Em uma frase: **o centro do sistema é a visão de gastos por categoria ao longo dos meses** — a planilha que a família faria à mão, só que alimentada pelo banco.

---

## 2. Inventário de telas

### T-01 — Login
Formulário e-mail + senha. Chama `POST /api/auth/login`, guarda os dois tokens, redireciona para T-03.
- Erro de credencial: mensagem única e vaga ("Credenciais inválidas") — o corpo uniforme do 401 é decisão de segurança (B-A8/B-T2), a tela apenas a respeita.
- Link para T-02.
- **API: pronta.**

### T-02 — Cadastro
Nome, e-mail, senha (10–72 caracteres). Chama `POST /api/auth/cadastro`; sucesso leva ao login (a API pede login explícito após cadastro, de propósito).
- E-mail já usado: o 409 com `{"erro": "Ja existe uma conta com este e-mail"}` aparece no campo de e-mail.
- Validação: o 400 traz `"campos"` (campo → mensagem) — o formulário marca o lugar certo.
- **API: pronta.**

### T-03 — Casca autenticada (layout)
O esqueleto de tudo pós-login:
- **Topo:** nome do ambiente atual + seletor de ambiente (usa `GET /api/perfil` para listar e `POST /api/sessao/ambiente` para trocar) + botão **Sair** (`POST /api/auth/logout` — só este dispositivo; "sair de todos" fica em local secundário, ex.: menu do perfil).
- **Esquerda:** menu de navegação — Mapa de gastos (inicial), Lançamentos, Categorias, Contas, Cartões.
- **Centro:** a tela ativa.
- Renovação transparente do token: em 401 por expiração, chama `POST /api/auth/renovar` **enviando o `ambienteId` atual** (é o contrato do I-15 — sem enviar, a sessão volta ao primeiro ambiente) e repete a chamada original.
- **API: pronta.**

### T-04 — Categorias
Lista categorias do ambiente com suas subcategorias; cria/edita/arquiva as não-sistêmicas (F10: sistêmica bloqueia edição — a tela mostra cadeado, não esconde).
- Campos: nome, tipo (`ENTRADA`/`SAIDA`/`AMBOS`, F12), subcategorias (dois níveis por estrutura, F8 — a tela NÃO oferece terceiro nível).
- **Renomear é ação leve** (B-D3): o nome é texto pendurado no id, então a troca aparece em todos os lançamentos, passados inclusive, e nunca cria categoria nova. Sem aviso, sem confirmação.
- **Não existe excluir, existe arquivar** (B-D4, espelho de F7). Arquivada, a categoria some da lista de escolha da T-08 e continua nomeando o histórico inteiro. O botão diz "Arquivar".
- As três sistêmicas (B-D13) aparecem com cadeado: **Transferência**, **Ajuste de saldo**, **Não classificado**. As duas primeiras trazem a nota "não entra no mapa de gastos" (B-D15) — a tela explica o `entra_no_mapa` em vez de deixar o usuário descobrir pela ausência.
- **API: não existe — depende da V10.**

### T-05 — Contas
Lista contas do ambiente; cria conta com nome, natureza (`ATIVO`/`PASSIVO`, F6). Conta não se exclui, se **encerra** (F7) — o botão diz isso.
- Saldo exibido é SEMPRE calculado (P1/F2); a tela nunca pede "saldo inicial" como campo mágico — saldo inicial é um lançamento na categoria sistêmica **Ajuste de saldo** (A13/B-D13), e a tela deixa isso visível.
- A tela mostra em quais **ambientes** a conta é visível. É o que torna B-D2 compreensível: dá para ver que a conta é compartilhada e entender por que o gasto foi para o ambiente ativo.
- **API: não existe — depende da V10.**

### T-06 — Cartões de crédito
Cadastro do contrato (cartão = especialização de conta, F5/F17) e dos cartões emitidos (F18): limite, dia de fechamento, dia de vencimento, responsável padrão (F22).
- Fatura, parcelas e limites derivados aparecem AQUI numa versão futura da tela — o cadastro vem primeiro.
- **API: não existe — depende da V11 (a parte mais complexa do domínio: fatura/parcela/F19–F23).**

### T-07 — Mapa de gastos (a tela central inicial)
A razão de ser do sistema. **Período: ano civil, com seletor de ano** (P-T3). Três blocos empilhados (P-T5):

1. **Saídas:** linhas = categorias de saída, colunas = os doze meses, células = soma dos lançamentos; última coluna = total da categoria no ano; última linha = total do mês.
2. **Entradas:** o mesmo formato, categorias de entrada.
3. **Saldo do mês:** entradas − saídas, uma linha.

Depois, **um quadro por categoria** com linhas = subcategorias. Lançamento sem subcategoria (F11 permite) aparece como linha "(sem subcategoria)".

Regras que o modelo fixa e a tela respeita:
- O mês de um gasto é o mês da **`data_caixa`** (P-T2): quando o dinheiro saiu. A coluna é `date` (B-D8), então o mês não depende de fuso.
- Somas calculadas na hora, nunca persistidas (P1).
- **Cada célula tem dois números: realizado e previsto** (P-T7 / B-D10), visualmente distintos. O endpoint devolve os dois separados; a tela só pinta. Vale para os totais e para o saldo.
- **Agrupamento por `categoria_id`/`subcategoria_id`, nome exibido é o atual** (B-D3). Renomear não parte a linha em duas.
- **Categoria com `entra_no_mapa = false` não aparece** (B-D15): transferência entre contas próprias e ajuste de saldo não são gasto. "Não classificado" **aparece** — é gasto real sem rótulo, e esconder faria o total mentir para baixo.
- Escopo: lançamentos do ambiente atual (F33), que é o ambiente ativo da sessão (B-D2).
- **API: não existe — ver `docs/api.md` (`GET /api/relatorios/mapa-de-gastos`). Depende da V10.**

### T-08 — Lançamentos *(confirmada no mínimo aceitável — decisão P-T1)*
Não estava na visão original, mas sem ela o T-07 ficaria vazio até o bot do Telegram existir: **nenhuma outra peça do roteiro criava gastos**. Versão mínima: lista com filtro por mês + formulário simples (conta, categoria/subcategoria, valor, data, descrição). Sem cartão, sem parcela, sem recorrência na primeira versão — só o lançamento simples de F4/F14.

O formulário tem **cinco campos e mais nada**:
- **Sem campo de ambiente** (B-D2): vem do ambiente ativo no topo, mesmo em conta compartilhada.
- **Sem campo de status** (B-D9 / R9): data no passado ou hoje → `REALIZADO`; no futuro → `PREVISTO`. A lista mostra o status resultante e permite corrigir depois; o formulário não pergunta.
- A lista de categorias exclui as arquivadas (B-D4) e inclui as sistêmicas que fazem sentido lançar à mão.
- **API: não existe — depende da V10.**

---

## 3. O mínimo aceitável (fechado em 23/07/2026 — decisões P-T1/P-T4)

O menor sistema que uma pessoa da família consegue USAR de ponta a ponta:

| Dentro | Fora (fica para depois) |
|---|---|
| T-01 Login, T-02 Cadastro | Recuperação de senha |
| T-03 Casca com seletor de ambiente e logout | Criar segundo ambiente pela tela; convite (I-08) |
| T-04 Categorias (com as sistêmicas de F13 já visíveis) | — |
| T-05 Contas | Encerramento com regras finas |
| T-08 Lançamento manual simples | Parcelamento, recorrência (F23–F25), transferência (F2) |
| T-07 Mapa de gastos (as duas visões) | Dashboard com gráficos; patrimônio (F6) |
| — | T-06 Cartões por inteiro (contrato+emitido+fatura) |

**Cartão fica fora do mínimo** (decisão P-T4). É a parte mais profunda do domínio (fatura, parcela, ciclo — F17 a F23) e não é pré-requisito para o mapa de gastos funcionar com contas simples. Entra na fatia seguinte, com a tela T-06.

---

## 4. O que a API já oferece × o que falta

**Pronto (bloco pré-telas, 23/07/2026):** autenticação completa, contrato de erro uniforme (`{"erro", "campos"}`), sessão com troca/preservação de ambiente, logout por dispositivo.

**Falta, na ordem imposta pelas telas:**
1. **Migração V10** — a lista canônica das tabelas vive em `decisoes.md` §6, não aqui. *(Este documento repetia a lista e ela divergiu em silêncio: foi o achado I-19 de 26/07. Referenciar, nunca repetir.)*
2. **API V10:** CRUD de categorias/subcategorias, CRUD de contas, CRUD de lançamentos, endpoint do mapa de gastos. Contrato em `docs/api.md`, escrito **antes** do código.
3. **Migração V11 + API:** cartão/cartão emitido/fatura/parcela/recorrência — junto com a T-06.

O fatiamento foi confirmado e formalizado em B-D1: a V10 é exatamente o que o mínimo aceitável consome.

---

## 5. Perguntas — decididas e abertas

### P-T1 — Como entra dado no mínimo aceitável? — **DECIDIDO em 23/07/2026**
**Tela de lançamento manual (T-08).** O mapa de gastos soma lançamentos, e o roteiro original só os criava na fase do Telegram — as telas nasceriam vazias. A T-08 entra no mínimo aceitável: formulário simples, sem parcela nem recorrência. O sistema fica usável de ponta a ponta antes do bot.

### P-T2 — O mês do gasto é competência ou caixa? — **DECIDIDO em 23/07/2026**
**Caixa.** O quadro mês a mês responde "quanto saiu do bolso em cada mês" (`data_caixa`). Consequência assumida: quando o cartão chegar, a compra de junho paga em julho aparecerá em **julho** — é a leitura desejada. O modelo guarda as duas datas (F14), então a lente de competência pode virar alternador no futuro sem mexer em dado.

### P-T3 — Qual o período padrão do quadro? — **DECIDIDO em 26/07/2026**
**Ano civil, com seletor de ano** (B-D11). Bate com a planilha mental de qualquer pessoa e com o ciclo do IR. Doze colunas fixas dão cabeçalho estável e comparação direta entre anos — a janela móvel de 12 meses nunca mostra coluna vazia, mas troca o nome das colunas todo mês e inviabiliza "como foi o ano passado".

### P-T4 — Cartões dentro ou fora do mínimo aceitável? — **DECIDIDO em 23/07/2026**
**Fatia seguinte, por inteiro.** O mínimo fecha com categorias + contas + lançamentos + mapa. Cartão entra depois, completo (cadastro + fatura + parcela), sem apressar a parte mais delicada do domínio (F17–F23). O item "Cartões" pode até aparecer no menu da T-03 como desabilitado ("em breve"), para o menu já ter a forma final.

### P-T5 — Entradas aparecem no mapa? — **DECIDIDO em 26/07/2026**
**Sim: três blocos — saídas, entradas e saldo do mês** (B-D12). Contra a proposta original de adiar. O motivo venceu o escopo: a pergunta que a família faz não é "quanto gastei", é "sobrou ou faltou". Custa um bloco a mais no mesmo endpoint, porque a agregação já varre os lançamentos e separar por `categoria.tipo` (F12) é filtro, não consulta nova.

### P-T6 — Qual a forma do frontend? — **DECIDIDO em 26/07/2026**
**SPA leve compilada para estáticos** (23/07), com **React + Vite** (B-D17), servida pela própria aplicação (ou pelo Caddy na fase Pi). Casa com a API JSON + JWT já construída e com o bot futuro — mesma API para os dois clientes.

O critério que decidiu não foi técnico: o projeto também serve para o tempo investido valer fora de casa, e React é o que o mercado usa. O argumento de tamanho de bundle (~180 KB contra ~40 KB do Svelte) foi levantado e **descartado** — numa rede local, para uma família, a diferença é invisível, e usá-lo teria sido inflar um fator irrelevante. Custo aceito: mais cerimônia por tela que Svelte. Contrapartida assumida: o código é escrito comentando o **porquê** dos padrões, para o projeto servir de estudo além de sistema.

### P-T7 — Lançamentos PREVISTOS entram na soma do mapa? — **DECIDIDO em 26/07/2026**
**Entram, visualmente distintos** (B-D10): o quadro mostra os gastos futuros e deixa claro que ainda não se realizaram. O quadro serve para planejar, não só para conferir o passado.

Consequência de contrato, e o motivo de a decisão ter que vir antes do código: **cada célula devolve dois números, `realizado` e `previsto`**, nunca a soma pronta — idem para totais e saldo. Se o servidor mandasse somado, a tela não teria como cumprir a parte do "deixa claro".

A pergunta mudou de forma no caminho. A tensão original — "somar previstos mistura futuro com passado" — nasceu de F15 fazer *todo* lançamento nascer previsto (achado I-22). Com B-D9, o status passa a derivar da data, e previsto volta a significar o que deveria: **agendado para frente**.

---

## 5b. Protótipo navegável (23/07/2026)

Existe um protótipo real de T-01/T-02/T-03 em `raspybank-app/src/main/resources/static/` (`index.html`, `estilo.css`, `app.js`), servido pela própria aplicação em `http://localhost:8080/`. Ele consome a API de verdade: login, cadastro, perfil, troca de ambiente, logout por dispositivo e renovação transparente com preservação de ambiente.

**O que ele é:** a prova visível de que o circuito HTTP → JWT → contexto → RLS → banco funciona, e a validação prática do contrato de erro (o 409 e o mapa `campos` aparecem no formulário).

**O que ele NÃO é:** a fundação do frontend. É JavaScript puro sem build, escrito porque P-T6 ainda não escolheu o framework. **Quando a SPA de verdade nascer, estes três arquivos morrem** — não devem ganhar features nem virar padrão.

Decisões técnicas embutidas nele, que precisam de veredito próprio quando a SPA for desenhada:
- **P-T8 (aberta) — onde guardar o token no navegador.** O protótipo usa `localStorage`, que é a escolha simples, não a segura: qualquer script injetado o lê. Aceitável num sistema familiar auto-hospedado; a alternativa (cookie `httpOnly` + `SameSite`, exigindo mudança no servidor) deve ser avaliada antes de expor à internet.
- O `SegurancaConfig` libera os arquivos da tela por **lista explícita**, nunca por curinga. `TelasEstaticasTest` é a cerca: garante que a tela carrega sem token e que a API continua em 401.

## 6. Consequências imediatas — as perguntas fecharam em 26/07/2026

Todas as `P-T` estão decididas, exceto **P-T8** (token em `localStorage` × cookie `httpOnly`), que só vence antes de expor à internet e não bloqueia nada. O trabalho destravado, em ordem:

1. **Migração V10** (migração primeiro, código depois — P3), crescendo os testes do Bloco C: fumaça de RLS para as tabelas novas e regras de domínio puras (padrão B-C3) para as somas do mapa. Lista de tabelas em `decisoes.md` §6; regras novas em `decisoes.md` §4d.
2. **`docs/api.md`** — contrato dos endpoints da V10, escrito antes do código. *(Feito na mesma sessão.)*
3. **API V10** contra o contrato, em fatias — ordem confirmada em 26/07/2026, com a API inteira **antes** de qualquer tela:

   | Fatia | O quê | Estado |
   |---|---|---|
   | 0 | Módulo `raspybank-lancamento` (B-D20): cinco entidades, cinco repositórios, enums, regra de situação (B-D22). 27 testes puros | ✔ |
   | 1 | API de categorias e subcategorias (T-04): 9 endpoints, vocabulário de erro em `shared` (403/404), 12 testes de API | ✔ |
   | 2 | API de contas (T-05): 5 endpoints, saldo calculado em dois números (B-D26), saldo inicial virando lançamento em `AJUSTE`, 11 testes de API | ✔ |
   | 3 | API de lançamentos (T-08): 5 endpoints, as duas derivações (situação pela data, tipo pela categoria), exclusão física auditada, 15 testes de API | ✔ |
   | 4 | API do mapa de gastos (T-07): uma varredura, três blocos, doze células sempre, 10 testes de API | ✔ |
   | 5 | Montar React + Vite e portar T-01/T-02/T-03 | ⏳ 27/07/2026 — **bloqueada:** `node`/`npm` não instalados na VM |
   | 6 | Telas novas: T-04, T-05, T-08, T-07 | |

   **Backend do mínimo aceitável fechado em 26/07/2026**: 24 endpoints, 136 testes verdes, tudo contra o contrato de `docs/api.md` — que foi corrigido três vezes quando a implementação mostrou que ele errava (dois saldos em vez de um, `desarquivar`/`reabrir` ausentes, e a frase errada sobre o RLS recortar ambiente sozinho).

   O custo aceito da ordem: a primeira tela nova só aparece na fatia 6. Em troca, o contrato inteiro é verificado de uma vez e o React é montado uma vez só, sem interromper.

4. **SPA em React + Vite** (fatias 5 e 6), começando por T-01/T-02/T-03 (portando o protótipo) e indo para T-04/T-05/T-08/T-07. Quando a T-03 real existir, os três arquivos de `static/` morrem — ver §5b. **Pré-requisito de ambiente:** `node` e `npm` ainda não existem na VM.

O item "esboço visual das T-03/T-07" saiu da lista: o protótipo navegável (§5b) já validou a T-03, e a T-07 ficou especificada célula a célula em `docs/api.md`, que é mais preciso que rabisco.
