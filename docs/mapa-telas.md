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
- **API: não existe — depende da V12 (a parte mais complexa do domínio: fatura/parcela/F19–F23).**

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
3. **Migração V12 + API:** cartão/cartão emitido/fatura/parcela/recorrência — junto com a T-06.

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
   | 5 | Montar React + Vite e portar T-01/T-02/T-03 | ✔ 27/07/2026 |
   | 6 | Telas novas: T-04, T-05, T-08, T-07 | ✔ 27/07/2026 |

   **Backend do mínimo aceitável fechado em 26/07/2026**: 24 endpoints, 136 testes verdes, tudo contra o contrato de `docs/api.md` — que foi corrigido três vezes quando a implementação mostrou que ele errava (dois saldos em vez de um, `desarquivar`/`reabrir` ausentes, e a frase errada sobre o RLS recortar ambiente sozinho).

   O custo aceito da ordem: a primeira tela nova só aparece na fatia 6. Em troca, o contrato inteiro é verificado de uma vez e o React é montado uma vez só, sem interromper.

4. **SPA em React + Vite** (fatias 5 e 6), começando por T-01/T-02/T-03 (portando o protótipo) e indo para T-04/T-05/T-08/T-07. Quando a T-03 real existir, os três arquivos de `static/` morrem — ver §5b. **Pré-requisito de ambiente resolvido em 27/07/2026:** `node v22.22.1` e `npm 9.2.0`, instalados do archive congelado do Ubuntu 26.04 — sem repositório de terceiro e sem contato com o registry do npm. O frontend vive em `raspybank-web/`, fora do Maven de propósito: o plugin que integraria o build baixaria um binário do Node da internet a cada compilação. As dependências são resolvidas com corte por data (`NPM_CORTE` no Makefile) e instaladas com `npm ci --ignore-scripts`. Ver §7.

O item "esboço visual das T-03/T-07" saiu da lista: o protótipo navegável (§5b) já validou a T-03, e a T-07 ficou especificada célula a célula em `docs/api.md`, que é mais preciso que rabisco.

---

## 7. Política de dependências do frontend (27/07/2026)

**A regra, definida pelo Abner:** só entra no projeto o que está estável há **mais de uma semana**. Nada publicado ou promovido **no dia** da instalação, e isso vale para dependência transitiva, não só para o pacote digitado no comando.

**Por que a regra é essa e não "auditar cada pacote".** O padrão dos ataques de cadeia de suprimentos no npm — a família Shai-Hulud, ativa de setembro/2025 a maio/2026, com o código do worm vazado em meados de maio e clones em circulação — é uma versão maliciosa publicada e retirada em poucas horas. Uma trava de idade derruba quase a classe inteira **sem depender de alguém reconhecer o pacote malicioso**, que é a parte que ninguém acerta de forma confiável.

**Como a regra é aplicada, em mecanismo e não em disciplina:**

| Controle | Onde vive | O que impede |
|---|---|---|
| `NPM_CORTE = 2026-07-17` | `Makefile` | resolução pegar versão recém-publicada |
| `--package-lock-only` na resolução | comando de atualização | baixar código antes de fixar o que será baixado |
| `package-lock.json` versionado | repositório | deriva silenciosa de versão |
| `npm ci` (nunca `npm install`) | `make web-deps` | instalar fora do lock; confere hash de integridade |
| `--ignore-scripts` | `make web-deps` | hooks `preinstall`/`install`/`postinstall` |
| build fora do Maven | `Makefile` | `frontend-maven-plugin` baixar binário do Node a cada build |

Nunca usar `npm create vite@latest` nem qualquer `@latest`: é literalmente "me dê o que subiu hoje".

**Estado verificado em 27/07/2026:** 54 pacotes, 54 hashes de integridade, todos vindos de `registry.npmjs.org`, e **nenhum deles pede script de instalação** — `--ignore-scripts` custa zero aqui.

### Falha conhecida aceita — `react-router-dom` 7.18.1

O `npm audit` acusa duas falhas altas, e elas **ficam**. O motivo:

| Versão | Advisories | Aplicáveis a nós |
|---|---|---|
| 7.11.0 | 14 | sim — open redirect e XSS em `<Link>`/`useNavigate` atingem SPA |
| **7.18.1** | 1 | **não** — CSRF em modo RSC, e não há runtime de servidor do React Router aqui |

Descer para a 7.11.0 zeraria o relatório e **pioraria** a segurança real. A escolha é deliberada: um `npm audit` limpo por construção vale mais como alarme, mas não ao preço de aceitar 14 falhas que de fato nos atingem. Revisar quando sair uma versão fora da faixa 7.12.0–8.2.0.

---

## 8. Portagem do protótipo para React (27/07/2026, fatia 5)

As T-01/T-02/T-03 saíram do protótipo em JavaScript puro e viraram SPA. O que **mudou de comportamento**, de propósito:

- **Rotas de verdade** (`/entrar`, `/mapa`) no lugar de esconder e mostrar `<div>`. A pessoa ganha botão voltar, endereço para guardar nos favoritos e recarga sem cair no login. Foi por isso que o `react-router` entrou — é a única dependência do projeto além do próprio React.
- **Estado de espera explícito.** Abrir o app com token guardado agora mostra "Carregando…" enquanto o servidor confere a sessão. O protótipo piscava o cartão de login nesse intervalo, o que sugeria que a sessão havia caído quando ela não havia.
- **Frase do menu corrigida.** O protótipo dizia que os itens acinzentados "aguardam a V10"; a V10 ficou pronta em 26/07. O que falta é a tela, na fatia 6.

O que **não** mudou, também de propósito: as três chaves de `localStorage` são as mesmas, então quem estiver com sessão aberta quando a SPA substituir os arquivos de `static/` continua logado.

**Verificado de ponta a ponta em 27/07/2026**, com backend real e tudo passando pelo proxy do Vite: cadastro 201, e-mail duplicado 409, senha errada 401 com mensagem vaga, validação 400 com o mapa `campos`, login 200, `/api/perfil` devolvendo ambiente e canal, troca de ambiente 200 com token novo, logout 200.

**Achado sobre o logout, sem defeito:** o token de acesso continua válido depois do logout — ele vence sozinho em 15 minutos. O que o logout revoga é a **renovação**, e isso foi confirmado (401 ao tentar renovar depois). Como o cliente apaga o `localStorage` ao sair, não há exposição prática. Vale lembrar disso antes de alguém "consertar" o que não está quebrado.

**O protótipo em `raspybank-app/src/main/resources/static/` ainda está lá e ainda é o que o Spring serve.** Ele só morre quando o `outDir` do Vite passar a apontar para aquela pasta — decisão da fatia 6, junto com as telas novas.

---

## 9. Telas da fatia 6 (27/07/2026)

As quatro telas do mínimo aceitável existem. O que cada uma **recusou** fazer é o que vale registrar:

**T-04 — Categorias.** As sistêmicas aparecem **com cadeado**, não escondidas: quem procurasse "Transferência" e não a encontrasse concluiria que sumiu. `sistemica` e `entraNoMapa` são lidos como as duas perguntas diferentes que B-D15 separou — o primeiro desenha o cadeado, o segundo vira a etiqueta "fora do mapa".

**T-05 — Contas.** O `saldoComPrevistos` só aparece **quando difere** do `saldo`; repetido, o número viraria ruído. O formulário diz em texto que o saldo inicial vira um lançamento em `Ajuste`, em vez de fingir um campo mágico — o saldo continua sendo só a soma dos lançamentos.

**T-08 — Lançamentos.** A tela **não reimplementa** as duas derivações. A situação é *mostrada* enquanto a pessoa escolhe a data ("nasce como previsto"), mas o campo só é enviado na edição, onde corrigir é legítimo. O campo `tipo` só aparece quando a categoria é `AMBOS` — o único caso em que o servidor não decide sozinho. Duplicar essas regras criaria uma segunda fonte da verdade, e a segunda é sempre a que envelhece.

**T-07 — Mapa de gastos.** Três blocos, doze colunas sempre, e o previsto numa segunda linha da célula, menor e noutra cor. A tela **nunca soma** realizado com previsto: é o B-D10 desenhado. As células são reordenadas por mês na chegada — o contrato promete doze, não promete ordem, e tabela de doze colunas montada fora de ordem erra em silêncio.

**Verificado com dados reais em 27/07/2026:** três categorias sistêmicas nascendo com o ambiente e com o `entraNoMapa` correto; 403 ao renomear sistêmica; 409 em nome duplicado; saldo inicial de `3000,00` virando lançamento em `Ajuste`; situação derivando da data; os dois saldos divergindo em `250,00` por causa de um lançamento futuro; e o mapa devolvendo `5200,00 − 910,50 = 4289,50` com o `Ajuste de saldo` **fora** da soma.

### O que ficou de fora, e por quê

**O protótipo em `static/` continua vivo, e o `outDir` do Vite continua em `dist/`.** Matar o protótipo agora exigiria decidir *como o build do frontend chega à imagem Docker* — se o Vite escreve direto em `resources/static/` (e aí artefato de build entra no controle de versão, ou precisa ser ignorado) ou se o `Dockerfile` copia de `dist/`. É wiring de implantação, não tela, e resolver pela metade seria pior que não resolver. Fica como o primeiro item do próximo passo.

---

## 10. A SPA passou a ser servida pelo Spring (27/07/2026) — fim do protótipo

Os três arquivos de `raspybank-app/src/main/resources/static/` foram **removidos**. Aquela pasta deixou de ser fonte e virou **destino do build do Vite**, e por isso entrou no `.gitignore`: versionar saída de build criaria duas cópias do mesmo código, e a segunda envelheceria em silêncio até alguém depurar a versão errada.

Três problemas apareceram no caminho, e nenhum era "copiar arquivo":

**1. Nome de asset com hash × lista explícita de segurança.** A `SegurancaConfig` liberava arquivo por arquivo, sem curinga, de propósito. O Vite gera `index-DkTzU8dD.css`, e o hash muda a cada build — lista explícita ficaria errada no build seguinte, com a tela em branco como sintoma. Foi aberto **um único curinga**, `/assets/**`, e ele é seguro pelo motivo que a regra original protegia: nenhum controlador mora lá. Todo endpoint nasce sob `/api/`, então controlador novo continua nascendo protegido.

**2. Recarregar a página numa rota do React dava 401.** `/contas` só existe no navegador; recarregar manda o caminho ao Spring, que não tinha controlador ali. Nasceu o `SpaControlador`, e a lista de rotas nele é **explícita**. Um curinga "tudo que não for `/api/**` devolve index.html" funcionaria e esconderia todo erro de digitação: `/api/lancamentoss` passaria a devolver a tela em vez de 404, e o defeito apareceria como "a tela não carrega", longe da causa.

**3. O `Dockerfile` não conhecia o `raspybank-lancamento`.** Defeito real desde a fatia 0, invisível porque ninguém rodou `make gate` desde então.

### O `make gate` estava quebrado, e não era só o módulo

Corrigido o módulo, a imagem ainda falhava: os testes de integração sobem um Postgres via Testcontainers, que precisa do `/var/run/docker.sock` — **e não há daemon do Docker dentro de um `docker build`**. Isso quebrou quando o primeiro teste de integração nasceu, no Bloco C.

A imagem passou a rodar só os testes que **podem** rodar lá (27 puros + 9 de arquitetura), e o **`make gate` agora depende do `make build`**: a suíte inteira roda aqui fora, com Docker disponível, antes de qualquer imagem ser construída. O gate só é honesto assim.

**Verificado em 27/07/2026:** 138 testes verdes; imagem construída e no ar; `/`, `/entrar`, `/mapa`, `/contas` devolvendo a SPA; `/rota-inventada` e `/api/perfil` em 401; `/assets/inventado.js` em 404. O hash do bundle dentro da imagem é **idêntico** ao do build local — o `npm ci` a partir do lock é reproduzível.

---

## 11. Forma de pagamento e transferência (27/07/2026) — o primeiro retorno dos testes de negócio

A primeira coisa que o uso real devolveu não foi um defeito: foi uma **ausência**. Um gasto de "gasolina, R$ 10" ficou registrado sem que desse para saber se tinha sido débito, pix ou boleto. O dado não estava errado — nunca tinha sido capturado, e isso não se recupera depois.

Decisões em `decisoes.md` §4e (B-D30 a B-D40); contrato em `api.md` §4, §4b, §5 e §5b.

### Duas correções do Abner sobre o meu desenho

**"Crédito é recebimento."** Minha primeira versão recusava forma de pagamento em ENTRADA, com o argumento de que "salário não é pago no débito". O argumento estava certo e o alvo errado: a pergunta útil não é *como foi pago*, é **como o dinheiro se moveu** — e ela tem resposta nos dois sentidos. Salário é *creditado*. Daí `CREDITO_EM_CONTA`, os sentidos por forma, e **dois padrões por conta**.

**"Não precisa nem da palavra saque."** Eu tinha proposto `SAQUE` como forma de pagamento na perna de saída. Ele cortou: *"pode deixar tudo transferência mesmo — transferiu para carteira 100 reais, intrínseco que é um saque"*. Está certo, e pelo motivo mais forte: um segundo nome para o mesmo evento obrigaria todo relatório futuro a conhecer os dois, e esquecer um viraria número errado sem aviso.

### Uma correção minha sobre a dele

Ele pediu "se a pessoa não indicar, salva **débito**". Débito literal gravaria na Carteira — que só aceita DINHEIRO — uma forma que a lista da própria conta recusa, em silêncio. Virou **padrão por conta**: na corrente `DEBITO`, na carteira `DINHEIRO`, e o comportamento pedido acontece nas duas.

### O buraco que a transferência revelou

`F2` diz "dois lançamentos **ligados**" e `F16` diz "transferência **propaga para o par**", desde o modelo lógico. Fui procurar a coluna que expressa o par: **não existia**. A promessa estava no documento e não no schema, e nenhuma migração até a V10 tinha reparado.

Sem ela, apagar uma perna deixa a outra órfã e **R$ 100 aparecem do nada** no patrimônio — em silêncio, porque nenhum saldo isolado parece errado.

### Coisas que só apareceram ao fazer

- **A regra de sentido virou tabela, não CHECK.** Com CHECKs ela apareceria em três lugares (lançamento, padrão da conta, enum Java) e três cópias divergem. Virou `forma_pagamento_sentido`, onze linhas, consultadas por chave composta — o mesmo padrão que a V10 já usa três vezes. Efeito colateral bom: nasceu o `GET /api/formas-pagamento` e o **frontend deixou de ter a lista em JavaScript**, matando a quarta cópia.
- **Duas chaves compostas no lançamento, não uma.** A primeira ("a conta aceita esta forma?") não basta: uma conta corrente que aceita boleto E crédito em conta permitiria "salário pago no boleto". Quem barra é a segunda ("esta forma serve a este sentido?").
- **`ux_cfp_padrao_*` obriga uma ordem de gravação.** Mover o padrão de `DEBITO` para `PIX` marcando PIX antes de desmarcar DEBITO deixa duas linhas verdadeiras no meio do caminho, e o índice parcial recusa. `gravarFormas` desmarca tudo, insere as novas como falsas, e só então marca — com `flush()` entre os passos, senão o Hibernate reordena e recria o estado que a sequência evita.
- **A cascata do par ficou no banco, não no serviço.** `ON DELETE CASCADE` mútuo cumpre a metade mais perigosa de F16. Regra de integridade cumprida pelo banco não tem como ser esquecida por um caminho de código novo — e este é o caso em que esquecer faz dinheiro aparecer do nada.
- **As pernas nascem sem forma de pagamento e não há uma linha de código para isso.** Cai da guarda que já existia: categoria sistêmica não recebe padrão, e transferência é sistêmica.
- **A V11 tomou o número que era do cartão.** Cartão virou V12. Os comentários dentro da V10 continuam dizendo "V11 = cartão" e **não foram corrigidos de propósito**: migração aplicada é imutável, e editar mudaria o checksum do Flyway.
- **A mensagem de encerrar conta deixou de mentir.** Ela mandava "transfira ou ajuste o valor antes" desde a fatia 2, e transferir não existia. Metade da instrução apontava para o vazio.

**Verificado em 27/07/2026:** 173 testes verdes (eram 138), sendo 21 em `FormaPagamentoApiTest` e 14 em `TransferenciaApiTest`. O teste que mais importa é `TransferenciaApiTest.excluirUmaPernaExcluiAsDuas`: confere que o **patrimônio total** volta exatamente ao que era, em centavos inteiros.

### Dois ajustes de uso (28/07/2026)

Vieram de testar a V11 na tela, e os dois são regras de negócio que só aparecem quando alguém usa o sistema de verdade.

**`DINHEIRO` desliga as outras formas, e vice-versa** (B-D41). Papel moeda só existe em lugar físico — carteira, bolso, gaveta, cofre — e nenhum deles recebe pix. Do outro lado, o dinheiro de uma conta em banco é virtual: tirá-lo de lá não é pagar em espécie, é um saque, que aqui é uma transferência para a conta física. As caixas se desligam entre si na tela, e o servidor recusa a combinação com 403.

Esta é a **única** regra de forma que não virou chave composta no banco, e a assimetria é deliberada: violá-la não grava lançamento errado, só torna a lista da conta incoerente. Nenhum número fica errado — só uma opção sem sentido apareceria num seletor. Impor no banco custaria um gatilho de nível de comando.

**Transferência saiu do seletor de categoria da T-08** (B-D42). Ela migrou inteira para o botão "Transferir"; escolhê-la num lançamento avulso criaria meia transferência, com `lancamento_par_id` nulo. O `POST /api/lancamentos` recusa com 403 — a tela não é a cerca, o bot do Telegram chega por fora.

As outras duas sistêmicas continuam lançáveis, e isso importa: "Ajuste de saldo" é exatamente o caminho que a mensagem de encerrar conta com saldo indica, e "Não classificado" é o destino do bot quando ninguém classifica.

Um efeito colateral bom no `MapaDeGastosApiTest`: ele criava a transferência do cenário com um `POST` de lançamento na categoria sistêmica, o que passou a ser recusado. Reescrito para usar o endpoint de transferência, o cenário ficou mais honesto — agora existem **duas** pernas, e o mapa precisa ignorar as duas.

**Verificado em 28/07/2026:** 177 testes verdes (eram 173).

### Previsto vencido vira realizado (28/07/2026)

Achado numa varredura de pendências, não por defeito relatado — e teria mordido em dias.

`SituacaoLancamento.derivarDe` (B-D9) só rodava em dois momentos: quando o lançamento nascia e quando alguém o editava. Nada reavaliava com o passar do tempo. Na prática: a conta de luz lançada para 05/08 nascia `PREVISTO` corretamente e, em 06/08, **continuava** `PREVISTO`. O saldo realizado ignorava aquele valor para sempre, e o número que a pessoa confere contra o extrato do banco ficava errado sem nada denunciar.

Decisões em `decisoes.md` §4e (B-D43 e B-D44).

**A virada acontece na leitura, não num job agendado.** O job seria o desenho óbvio — `Canal.SISTEMA` já existe e o javadoc dele fala em "jobs agendados". O impedimento é a RLS: rotina de fundo não tem `raspybank.usuario_id` na sessão, nenhuma política consegue avaliá-la, e o UPDATE alcançaria zero linhas. Fazê-la funcionar exigiria uma função `SECURITY DEFINER` nova — decisão que passa pelo critério B-D19 e pelo inventário de `security-definer.md`, e que merece discussão própria. Dentro da requisição a identidade já está estabelecida e nada disso é necessário.

**A virada é de mão única**, e é isso que evita uma briga infinita com `corrigirSituacao` (B-D22): se ela também desfizesse correções manuais, a pessoa marcaria de volta como previsto e a próxima leitura viraria de novo. Quando o boleto não foi pago, a correção certa é **mudar a data** — "não paguei dia 05, pago dia 12" vira reagendar, e ele volta a previsto pela regra normal de B-D9.

**Um teste caiu, e o motivo é uma consequência que vale registrar:** o `MapaDeGastosApiTest` criava um `PREVISTO` com data **passada**, forçado por `PUT`, justamente para não depender do dia em que a suíte roda. Isso deixou de ser possível — previsto com data no passado é um estado que não sobrevive à primeira leitura. O cenário passou a usar dezembro.

**Verificado em 28/07/2026:** 181 testes verdes (eram 177), sendo 4 novos em `SituacaoVencidaTest` — incluindo o que documenta o caminho do "não paguei, reagendo".

---

## 12. Cartão de crédito — V12 e T-06 (28/07/2026)

A parte mais funda do domínio (F17–F23), e a primeira em que o desenho inteiro foi escrito **antes** de uma linha de código, por pedido dele: *"acho que por hora é legal dar uma segurada em código e só codificar depois de entendimento fechado e autorizado"*.

Decisões em `decisoes.md` §4f (B-D45 a B-D60); contrato em `api.md` §6b.

### O que o desenho prévio evitou

Três coisas que teriam virado migração corretiva se descobertas no meio do código:

**Não existe entidade "banco".** A leitura literal da frase dele — *"não consigo criar um cartão sem uma conta de banco criada antes"* — apontava para a `conta` que já existia. A confirmação veio do exemplo do Nubank com Black e Diamond: dois contratos, limites próprios, mesma conta de banco. Uma tabela `banco` não guardaria nada.

**Não existe tabela `parcela`.** B-D1 a listava desde o começo. Ao desenhar, ela só guardaria agregado — o total é a soma, a data da compra é a competência repetida, a quantidade é a contagem. Virou três colunas no lançamento.

**O estado da fatura não é um enum.** Eu tinha escrito cinco valores mutuamente exclusivos. Quando ele explicou que antecipar pagamento é como se libera limite, o caso "aberta e parcialmente paga" apareceu — e nesse enum ele não teria nome. Viraram três perguntas independentes: `ciclo`, `quitacao`, `vencida`.

### Coisas que só apareceram ao fazer

- **Fatura vazia nascia "vencida"** (B-D60). Um cartão criado hoje ganha faturas de ciclos que já passaram, fechadas e com total zero — e sem a condição `total > 0` todas apareciam atrasadas. Achado por um teste que eu escrevi para outra coisa.
- **A primeira fatura da lista pode já estar fechada, e isso é correto.** As faturas nascem a partir do mês corrente, e se o fechamento daquele ciclo já passou a leitura o fecha. Um teste que supunha "a primeira está aberta" quebrou — e o teste é que estava errado.
- **O fechamento recuando para sexta é visível nos dados.** Outubro de 2026 fecha dia 09 e não 10, porque 10/10 é sábado.
- **`PAGAMENTO_FATURA` virou a quarta sistêmica**, e três testes que contavam três quebraram. A contagem estava certa em todos; o vocabulário é que cresceu.

### Uma observação sobre o saldo do cartão na T-05

O cartão é uma conta `PASSIVO` (B-D47), então ele aparece na lista de contas. Mas o **saldo realizado** dele pode ficar positivo de um jeito que confunde: as compras são cobradas na fatura futura, então nascem `PREVISTO`; um pagamento antecipado é `REALIZADO` hoje. Depois de antecipar R$ 200 de uma fatura de agosto, o cartão mostra `saldo 200,00` e `com previstos −1.250,00`.

Os dois números estão certos e rotulados, mas o que interessa num cartão é **sempre o segundo** — a dívida contratada. Fica registrado como pendência de tela, não de domínio: qual dos dois números é o principal numa conta de cartão é escolha de produto.

**Verificado em 28/07/2026:** 200 testes verdes (eram 181), sendo 19 novos em `CartaoApiTest`. V12 aplicada no banco de desenvolvimento real em 59 ms, e o fluxo inteiro conferido por HTTP: cartão com limite, adicional e virtual, compra à vista, geladeira em 10x, antecipação liberando limite, e o mapa separando gasto de cartão do resto.

### Os testes de negócio do cartão (28/07/2026) — oito pontos

Quatro deles eram a mesma ideia vista de ângulos diferentes: **o cartão é um meio de pagamento, não uma conta**.

Decisões em `decisoes.md` §4g (B-D61 a B-D64).

| # | O quê | Onde ficou |
|---|---|---|
| 1 | "Cartões" fantasma no menu, acinzentado, mais a nota abaixo | Removidos, com as duas regras de CSS que ficariam órfãs |
| 2 | Botão "Ver" → extrato da fatura | T-06, dentro do painel de faturas |
| 3 | Criar cartão virtual; extrato separa por cartão | Botão "Novo virtual" + coluna Cartão no extrato (exigiu a V13) |
| 4 | Quatro dígitos na criação do cartão | O contrato cria o físico junto (B-D63) |
| 5 | Conta = só bancárias; "como foi pago" = formas + cartões | `opcoesDePagamento` monta a lista única |
| 6 | Título "Contas bancárias" | T-05 |
| 7 | Cartão fora da tela de contas | `bancariasDoAmbiente`, no repositório |
| 8 | Encerrar cartão na tela de cartões | T-06, ao lado de "Novo virtual" |

### A inversão que dá nome a tudo

Você lança em **"Nubank"** e escolhe **"Black · físico ····4352"** no mesmo combo onde escolheria débito ou pix. Escolher um cartão habilita o parcelamento.

Por baixo, o lançamento continua morando na conta do cartão — e **isso não foi conservadorismo**. Duas coisas que ele mesmo pediu dependem de a dívida ser saldo próprio: **pagamento parcial** da fatura e **pagar a fatura do Nubank com a conta do C6**. Se a compra debitasse o banco direto, a fatura não teria o que pagar.

A escolha de qual número mostrar no extrato veio dele: **o banco**. Ter escolhido "Nubank" ao lançar e ler "Black" no extrato seriam dois nomes para o mesmo lançamento em duas telas.

### O defeito que a constraint nova denunciou

`ck_lancamento_cartao_exige_fatura` — "quem tem cartão tem fatura" — pegou um erro **da V12** no primeiro parcelamento que rodou depois dela: as parcelas 2 em diante eram gravadas **antes** de receberem a fatura. Passava despercebido enquanto não havia cartão na linha; a constraint o expôs no mesmo dia em que nasceu.

É o terceiro caso nesta semana em que uma regra virada impossibilidade estrutural encontrou algo que os testes não tinham encontrado.

**Verificado em 28/07/2026:** 200 testes verdes. V13 aplicada no banco real em 12 ms, e o fluxo novo conferido por HTTP: cartão físico nascendo com o contrato, virtual dividindo limite e fatura, três compras com donos diferentes aparecendo separadas no extrato da fatura, o cartão fora da lista de contas, e um cartão do Nubank lançado na Carteira recusado com 403.

### Ajustes da tela de cartões (28/07/2026)

Três pontos, e o terceiro derrubou uma decisão que eu tinha tomado por analogia errada.

**Agrupamento por banco** (B-D66). A tela passou a ser **banco → contrato → emitidos**, que é a hierarquia do modelo e também a ordem em que ele pensa. O banco saiu de etiqueta na linha e virou cabeçalho de grupo.

**Cada cartão emitido tem botão próprio** de cancelar e reativar. Cancelar um virtual descartado não deveria exigir encerrar o contrato inteiro.

**Encerrar não exige dívida zero** (B-D65). Eu tinha copiado F7 — conta com saldo não encerra — e o paralelo não valia. Encerrar uma **conta** com saldo faria dinheiro sumir do patrimônio; encerrar um **cartão** com dívida não muda número nenhum: as parcelas futuras continuam chegando e as faturas continuam pagáveis.

Encerrar faz uma coisa só: **impede compra nova**. Em cascata, cancela todos os emitidos — o banco cancela o conjunto, não a capa.

**Reabrir não reativa os emitidos**, e isso é escolha: ressuscitar em massa devolveria à vida um virtual que a pessoa matou de propósito, e virtual existe para ser descartado. Cada um volta pelo próprio botão.

A tela ganhou "Mostrar encerrados", porque um cartão encerrado precisa continuar acessível — a fatura dele ainda se paga.

**Verificado em 28/07/2026:** 201 testes verdes, com dois novos guardando que encerrar com dívida é permitido, que a cascata cancela todos os emitidos e que cartão encerrado recusa compra nova.

## 13. Renovação concorrente de token (28/07/2026)

Um WARN que o Abner notou no log virou o primeiro teste automatizado do frontend.

```
WARN  Reuso de token de renovacao detectado: familia ... revogada.
```

Decisões em `decisoes.md` §4h (B-D67 a B-D69).

### O diagnóstico, provado antes de consertar

O token de renovação é rotativo (A11): cada uso o consome. As telas disparam chamadas em paralelo — a T-08 faz um `Promise.all` de contas, categorias e cartões. Quando o token de acesso vence, **as três levam 401 ao mesmo tempo** e cada uma disparava a própria renovação com o mesmo token.

Reproduzido contra o servidor real antes de tocar no código: três renovações paralelas responderam **200, 401 e 401**, e a família ficou revogada. O efeito para quem usa é ser deslogado de todos os dispositivos sem ninguém ter atacado nada — tipicamente ao voltar a uma aba parada por mais de quinze minutos.

**A segurança estava certa. O cliente é que estava errado.**

### A correção

Uma renovação em voo por vez, compartilhada — o mesmo padrão do cache de `formasPagamento`. Mais uma guarda barata: se o token já mudou enquanto a chamada estava no ar, ela nem renova, só repete.

### O primeiro teste do frontend, e sem framework

`make web-test`, Node puro. Nenhum pacote novo: o Node já roda ESM e já traz `assert`, e os dublês de `localStorage` e `fetch` cabem em vinte linhas. Trazer vitest custaria dezenas de dependências, e a regra do `NPM_CORTE` existe para que cada uma seja uma decisão.

**O teste foi conferido contra o código antigo e falha nele** — três renovações em vez de uma. Um teste de regressão que não reprova a versão com o defeito não prova nada.

Ele ainda não alcança o navegador: a validação de formulário do HTML, que já escondeu o `pattern` do cartão, continua fora do alcance de qualquer teste que não abra uma página de verdade.

## 14. T-09 — Perfil e ambientes (29/07/2026)

Nasceu de um beco que o uso revelou: **o seletor de ambiente da casca ficava desabilitado com um ambiente só, e não havia como criar o segundo.** A lista existia; a porta não.

Decisões em `decisoes.md` §4i (B-D70 a B-D73).

### Escopo cortado por ele, no meio da própria descrição

O pedido original incluía apagar ambiente e apagar conta, com aviso crítico, senha e digitação do nome. Ao ver o tamanho — todas as cinco chaves estrangeiras que apontam para `ambiente` são `RESTRICT`, então apagar é uma demolição em ordem, e um ambiente compartilhado apagado levaria junto os dados de outra pessoa — ele parou: *"ficou complexo e eu preciso pensar... vamos fazer o básico depois a gente evolui"*.

Ficou: ver os dados, trocar o nome, trocar a senha, criar ambiente. **Compartilhamento aparece na tela desabilitado**, com o lugar marcado para a conversa ter onde cair. *(A conversa caiu ali mesmo: virou a seção 15, no dia seguinte da tela.)*

### Três coisas que a tela não faz, e por quê

**O e-mail não se edita** (B-D71). Ele é o login, e sem recuperação de senha um e-mail errado tranca a pessoa para fora sem volta.

**Criar ambiente não troca a sessão.** Quem está no meio de um lançamento na Casa não deveria ser jogado para o ambiente novo sem pedir. A troca continua no seletor do topo.

**Apagar não existe.** O botão ausente é mais honesto que um botão que destrói dados de terceiros sem o desenho pronto.

### O beco que custou uma rodada vermelha

`findById` devolvia vazio para um usuário que existe, e a tela respondia 404 sem nada no log. Causa: o aspecto que injeta a identidade do RLS envolve métodos `@Transactional`, e eu chamei o repositório **direto do controlador** — sem passar por ele, `app_usuario_id()` é nulo e a política não casa com linha nenhuma.

Virou `UsuarioServico`, com o motivo escrito no javadoc. Não é camada por camada: sem o serviço, não funciona.

### Um guarda do projeto pegou meu esquecimento

`MigracoesTest` compara as funções `SECURITY DEFINER` do banco com um inventário e reprovou a build: eu tinha prometido a linha em `security-definer.md` dentro do comentário da própria migração, e não a escrevi.

Achado no caminho: **o inventário mora em dois lugares** — o documento e uma lista codificada no teste. Acrescentar uma função exige tocar nos dois, e o teste só reclama do segundo. Fica como dívida pequena e registrada.

**Verificado em 29/07/2026:** 211 testes verdes, sendo 10 novos em `PerfilApiTest`. V14 aplicada no banco real em 13 ms, e o fluxo conferido por HTTP: perfil com dados, nome trocado sem tocar no e-mail, ambiente "Freelance" criado vazio (0 contas, 4 sistêmicas) sem mudar o ambiente ativo, senha atual errada em 403, e a troca de senha invalidando a antiga.

## 15. Compartilhamento de ambiente na tela (29/07/2026, V15)

O bloco que a T-09 deixou desabilitado virou tela de verdade, no mesmo lugar. Decisões em `decisoes.md` §4j (B-D74 a B-D84); contrato em `api.md` §2c.

### O que a tela faz

- **Perfil › Compartilhamento**: seletor com os ambientes em que a pessoa é **dona** (porta é do dono, B-D76), a lista de quem está dentro — dono marcado, e-mail visível — e o convite por e-mail. O aviso de sucesso diz o que B-D80 decidiu: *não há aceite, o ambiente já aparece na lista da pessoa*.
- **Perfil › Ambientes**: cada item emprestado ganha a etiqueta "compartilhado comigo" e o botão **Sair** (B-D77: qualquer um remove a si mesmo). Sair pede confirmação — para voltar, só com novo convite.
- **Seletor da casca**: o ambiente emprestado aparece com o sufixo "· compartilhado". Sem a marca, a pessoa lançaria no lugar errado sem nenhum indício — é a mesma preocupação do B-D2, agora com ambiente alheio na lista.

### A revogação com a pessoa dentro (B-D83, lado do cliente)

O 403 com `motivo: "SEM_ACESSO_AO_AMBIENTE"` é tratado no `cliente.js`, no mesmo nível da renovação por 401 — nenhuma tela precisa saber disso. O fluxo: renovar (o servidor troca em silêncio para um ambiente próprio, `ambienteParaRenovacao`), gravar a explicação no `sessionStorage`, **recarregar a página**.

O recarregar é proposital, pelo mesmo motivo da `key` no Outlet da casca: a tela aberta pertence ao ambiente que a pessoa PERDEU, e um formulário meio preenchido nela iria para o lugar errado. A explicação atravessa o recarregar e a casca a mostra uma vez — "Seu acesso ao ambiente em que você estava foi removido. Você voltou para um ambiente seu." — com o botão *Entendi*.

**Verificado em 29/07/2026:** 227 testes verdes, sendo 16 novos em `CompartilhamentoApiTest` — incluindo a revogação com token vivo (403 com marcador, renovação devolvendo o ambiente próprio, sessão intacta por B-D84) e a conta que NÃO escapa para o ambiente pessoal da convidada, testada por baixo, direto na política (B-D78).

## 16. Conta e cartão divididos na tela (29/07/2026, V16 e V17)

O outro modo de dividir, e ele mora em telas diferentes do §4j: **a pessoa trabalha no ambiente dela**. Decisões em `decisoes.md` §4k e §4l; contrato em `api.md` §2d e §2e.

### Os convites moram na T-05, no topo

Um convite que ninguém vê é um convite que não existe. Ele fica **acima do formulário de conta nova**, na tela onde a conta vai aparecer quando ela aceitar, e cada linha traz um **seletor de ambiente** — obrigatório, sem valor padrão, porque é a escolha que só ela pode fazer (B-D90). Só ambientes de que ela é **dona** entram no seletor: aceitar dentro de um ambiente emprestado (V15) espalharia a conta para o dono daquele ambiente, que não participou de nada.

Cair no ambiente ativo seria o pior padrão possível: mandaria a conta doméstica para o PJ sem aviso, e os gastos iriam para o mapa errado até alguém notar — e notar é difícil, porque nada avisa.

### Dois campos decidem os botões, e confundi-los foi um defeito real

- **`origem`** — a conta nasceu neste ambiente: libera *Renomear*, *Formas* e *Encerrar*, que são **dinheiro** e valem também para quem entrou no ambiente por convite (B-D76).
- **`podeCompartilhar`** — sou dono do ambiente onde ela nasceu: libera *Dividir*, que é **porta** (B-D91).

A tela **esconde** o que a pessoa não pode fazer, em vez de mostrar e deixar o servidor recusar: um botão que sempre responde 403 é um botão que mente. A primeira versão do código usou um campo só para as duas perguntas, e o sintoma foi um 500 na tela de contas de quem entrou no ambiente por convite.

### As etiquetas dizem coisas diferentes

- **"de Abner"** — conta ou cartão que você recebeu. O título explica o que você pode fazer com ela.
- **"dividida" / "dividido"** — outra pessoa também lança aqui. Ela existe para explicar o saldo: com o número atravessando ambientes (B-D87), a soma dos lançamentos visíveis **não fecha** com o saldo mostrado, e sem a marca isso pareceria erro de conta.
- **"em N ambientes"** — a conta aparece em mais de um ambiente **seu** (B-D18). Antes da V16 esta era a etiqueta "compartilhada", e ela mentia por omissão: a lista só traz os ambientes de quem está olhando, então a conta dividida com outra pessoa aparecia como não compartilhada.

### O extrato da conta é uma tela nova, e é ela que bate com o banco

`GET /api/contas/{id}/extrato` atravessa ambientes; o extrato do mês (T-08) continua sendo o do **ambiente** e não atravessa. São duas perguntas diferentes, e misturá-las faria uma delas mentir.

A linha da outra pessoa aparece como *"movimento de Luciana"*, com valor, data e forma de pagamento — **sem descrição e sem categoria** (B-D89). E não é a tela que as omite: elas não vêm do servidor (B-D97). O que a tela nunca recebeu, ela não vaza.

### O cartão dividido reusa o painel, e ganha uma linha no combo

O painel de divisão é **o mesmo componente** para conta e cartão, porque dividir cartão é dividir a conta do contrato (B-D98) — dois painéis seriam a mesma pergunta feita duas vezes. Só o texto muda.

Na T-08, o cartão dividido **não fica embaixo de nenhuma conta sua**: o banco do contrato é uma conta de outra pessoa, e o agrupamento "banco → cartões" de B-D61 não tem onde se apoiar. Ele aparece para qualquer conta sua, marcado *"(de Abner)"*.

No extrato da fatura, a compra alheia aparece como *"compra de Luciana"* com o plástico e a **parcela** — `1/10` (B-D102) — e sem descrição nem categoria. A parcela é a única coisa que a fatura revela a mais que a conta, e o motivo está escrito: as próximas são dinheiro do dono do contrato preso no limite dele.

**Verificado em 29/07/2026:** 221 testes verdes no backend (21 novos em `CompartilhamentoContaApiTest`, 8 em `CartaoCompartilhadoApiTest`) e `make web-test` verde. Dois defeitos foram achados **pelos testes**, não pela leitura: o convidado do ambiente perdendo o direito de encerrar conta (regressão da V15, apanhada por um teste que já existia) e o limite consumido do cartão ignorando as compras de quem divide — o número que B-D48 diz existir para bater com o app do banco.

## 17. O plástico como unidade na tela (30/07/2026, V19)

A seção 16 saiu ontem e esta corrige a parte dela que estava errada. Decisões em `decisoes.md` §4n (B-D106 a B-D110).

### O botão "Dividir" desceu do cartão para o plástico

Era no cartão, e entregava os dez de uma vez. Agora cada linha de emitido tem o seu, e o painel de divisão diz **qual** plástico está sendo dividido — `Black · Assinaturas ····5678` — porque um contrato com dez cartões teria dez painéis idênticos sem isso.

Dividir a **conta** de um cartão responde 403 com a frase apontando o caminho. O botão nem aparece: a tela de contas não lista cartões (B-D62).

### Cada plástico mostra o próprio número

`consumido de limiteEfetivo` na linha do emitido — os "1.000 dentro dos 30.000", ou o limite do contrato quando o plástico não tem próprio. É o formato do e-mail do banco, e serve aos dois lados: do dono, é a mini fatura de cada cartão; de quem recebeu, é o único número de limite que existe para ele.

### A tabela de faturas tem duas formas

O servidor manda `escopoDoTotal`, e a tela obedece:

- **`CONTRATO`** — colunas Total, Pago e A pagar, a etiqueta de situação e os botões Fechar/Reabrir/Pagar.
- **`MEUS_PLASTICOS`** — uma coluna só, *"Seus cartões"*, sem situação e sem botão de ação. Quem paga a fatura é o dono do contrato (B-D107), e exibir estado de pagamento a quem não paga seria mentir sobre o que aquele número significa.

Nada disso é palpite da tela: o marcador vem da API, como `SEM_ACESSO_AO_AMBIENTE`.

### O extrato da fatura recorta por plástico

Quem recebeu vê as compras **daquele** plástico — as dele e as do dono, porque é o caso do cartão de assinaturas que os dois usam — e nenhuma dos outros nove. A linha do outro aparece como *"compra de Abner"* com o plástico e a parcela, **sem descrição e sem categoria**: B-D89 foi confirmado aqui (B-D109).

### O que ficou de fora, e é decisão dele

Na descrição dele, o combo de meio de pagamento dela mostraria o **banco Nubank** como grupo, e dentro dele o virtual compartilhado. A tela hoje mostra o plástico marcado `(de Abner)` sob as contas dela — o cartão aparece e funciona, mas o nome do banco não.

O motivo é estrutural: o banco do contrato é uma conta que ela **não enxerga**, e expor o nome dele exigiria mais uma função `SECURITY DEFINER`. Fica registrado como pergunta aberta, porque a resposta é dele: mostrar o nome significa ela saber em qual banco ele tem conta.

**Verificado em 30/07/2026:** 227 testes verdes, sendo 12 no `CartaoCompartilhadoApiTest` reescrito, e `make web-test` verde.

## 18. O banco de outra pessoa no seletor dela (30/07/2026, V20)

Duas correções vindas do uso, e a primeira parecia um defeito do compartilhamento sem ser. Decisões em `decisoes.md` §4o.

### O escopo passou a seguir o ambiente ativo

Ele dividiu um plástico e viu a outra pessoa com todos. Os dados mostraram o compartilhamento correto — uma linha em `cartao_emitido_ambiente` — e a causa em outro lugar: ela também é **membro do ambiente dele**, e por B-D76 isso dá acesso a tudo lá dentro. Ficou como está, porque é a decisão dele.

O defeito que veio junto era da tela: como a RLS é por **usuário**, os plásticos apareciam também **dentro do ambiente dela**. Agora a lista de plásticos, os números da fatura e o extrato seguem o **ambiente ativo** — no dele, tudo; no dela, só o dividido.

### O banco de quem dividiu entra no seletor de conta

No formulário de lançamento dela aparece **"Nubank de Abner"**, junto das contas dela. Não é uma conta: escolhendo esse banco, o combo de "como foi pago" mostra **só os plásticos daquele banco que ele dividiu** — sem débito, sem pix, porque o dinheiro daquele banco não se move por ela. E o lançamento grava a conta do **cartão**, não a do banco.

O rótulo traz o dono porque ela pode ter um Nubank também.

Antes disso o cartão dividido aparecia embaixo de **todas** as contas dela, o que era incoerente — escolher "C6 dela" e ver "UltraVioleta de Abner" não quer dizer nada. Foi ele quem apontou.

**Verificado em 30/07/2026:** 229 testes verdes, com dois casos novos guardando o caso que ele achou — a pessoa com os **dois** acessos vendo um plástico no ambiente dela e todos no dele — e `make web-test` verde.

## 19. Perfil ganha o formulário de Telegram (18/08/2026, B-D120)

A T-09 (§14) tinha o campo só para conferência, e B-D105 tinha registrado a ausência de edição como custo pequeno. Ele foi olhar o Perfil em produção, o campo não estava lá, e pediu que estivesse.

Bloco **"Alterar Telegram"**, entre o de nome e o de senha — mesma ordem da tabela de endpoints em `api.md`. Chama `PUT /api/perfil/telegram`.

- Mesma validação frouxa do cadastro: aceita o id numérico e o `@usuario`.
- Dica sob o campo: *"Digitou errado? Apague o campo e salve"* — é a tela explicando, na hora, que vazio limpa o valor em vez de ser rejeitado.
- **Sem confirmação de posse** (B-D121): a tela não pergunta "tem certeza que isto é seu Telegram?" nem envia código nenhum. Digitar o de outra pessoa só falha quando o bot não reconhecer quem mandou mensagem — a tela não avisa antes disso.
- **Achados do `qa-adversarial` deliberadamente não refletidos na tela** (I-43): corpo sem o campo apaga o valor, o campo com espaço nas pontas é rejeitado antes do `.trim()` do servidor (a tela já corta o espaço no cliente, por isso não aparece aqui), e o limite de 64 caracteres do `@Size` não bate com o do `@Pattern` sem `@`. Proporção — junto do bot, primeiro cliente que monta corpo por programa.
