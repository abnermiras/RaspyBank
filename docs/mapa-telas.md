# RaspyBank — Mapa de Telas

**Versão:** 0.1 (rascunho em construção)
**Data:** 23 de julho de 2026
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
- **API: não existe — depende da V10 (fatia 1).**

### T-05 — Contas
Lista contas do ambiente; cria conta com nome, natureza (`ATIVO`/`PASSIVO`, F6). Conta não se exclui, se **encerra** (F7) — o botão diz isso.
- Saldo exibido é SEMPRE calculado (P1/F2); a tela nunca pede "saldo inicial" como campo mágico — saldo inicial é um lançamento de ajuste, e a tela deixa isso visível.
- **API: não existe — depende da V10 (fatia 1).**

### T-06 — Cartões de crédito
Cadastro do contrato (cartão = especialização de conta, F5/F17) e dos cartões emitidos (F18): limite, dia de fechamento, dia de vencimento, responsável padrão (F22).
- Fatura, parcelas e limites derivados aparecem AQUI numa versão futura da tela — o cadastro vem primeiro.
- **API: não existe — depende da V10 (fatia 2, a parte mais complexa do domínio: fatura/parcela/F19–F23).**

### T-07 — Mapa de gastos (a tela central inicial)
A razão de ser do sistema. Duas visões empilhadas:

1. **Quadro geral:** linhas = categorias (de saída), colunas = meses do período, células = soma dos lançamentos; última coluna = total da categoria no período; última linha = total do mês.
2. **Quadros por categoria:** para cada categoria, o mesmo formato com linhas = subcategorias. Lançamento sem subcategoria (F11 permite) aparece como linha "(sem subcategoria)".

Regras que o modelo já fixa e a tela respeita:
- O mês de um gasto é o mês da **`data_caixa`** (decisão P-T2): quando o dinheiro saiu.
- Somas calculadas na hora, nunca persistidas (P1).
- Nomes de categoria exibidos no histórico são os gravados no lançamento (F31) — renomear categoria não reescreve o passado.
- Escopo: lançamentos do ambiente atual (F33).
- **API: não existe — precisa de um endpoint de agregação (ex.: `GET /api/relatorios/gastos-por-categoria?de=2026-01&ate=2026-12`). Depende da V10 (fatia 1) + lançamentos existirem.**

### T-08 — Lançamentos *(confirmada no mínimo aceitável — decisão P-T1)*
Não estava na visão original, mas sem ela o T-07 ficaria vazio até o bot do Telegram existir: **nenhuma outra peça do roteiro criava gastos**. Versão mínima: lista com filtro por mês + formulário simples (conta, categoria/subcategoria, valor, data, descrição). Sem cartão, sem parcela, sem recorrência na primeira versão — só o lançamento simples de F4/F14/F15.
- **API: não existe — depende da V10 (fatia 1).**

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
1. **V10 fatia 1 (migração):** `categoria`, `subcategoria`, `conta`, `lancamento` (+ políticas RLS, gatilhos de auditoria F26, outbox F28, sistêmicas F9/F13).
2. **API fatia 1:** CRUD de categorias/subcategorias, CRUD de contas, CRUD de lançamentos, endpoint de agregação do mapa de gastos.
3. **V10 fatia 2 + API:** cartão/cartão emitido/fatura/parcela/recorrência — junto com a T-06.

Este mapa **confirma a estratégia já registrada** de fatiar a V10: a fatia 1 é exatamente o que o mínimo aceitável consome.

---

## 5. Perguntas — decididas e abertas

### P-T1 — Como entra dado no mínimo aceitável? — **DECIDIDO em 23/07/2026**
**Tela de lançamento manual (T-08).** O mapa de gastos soma lançamentos, e o roteiro original só os criava na fase do Telegram — as telas nasceriam vazias. A T-08 entra no mínimo aceitável: formulário simples, sem parcela nem recorrência. O sistema fica usável de ponta a ponta antes do bot.

### P-T2 — O mês do gasto é competência ou caixa? — **DECIDIDO em 23/07/2026**
**Caixa.** O quadro mês a mês responde "quanto saiu do bolso em cada mês" (`data_caixa`). Consequência assumida: quando o cartão chegar, a compra de junho paga em julho aparecerá em **julho** — é a leitura desejada. O modelo guarda as duas datas (F14), então a lente de competência pode virar alternador no futuro sem mexer em dado.

### P-T3 — Qual o período padrão do quadro? *(aberta)*
Ano civil com seletor de ano? Últimos 12 meses móveis? Proposta: ano civil (bate com a planilha mental de todo mundo), seletor simples de ano.

### P-T4 — Cartões dentro ou fora do mínimo aceitável? — **DECIDIDO em 23/07/2026**
**Fatia seguinte, por inteiro.** O mínimo fecha com categorias + contas + lançamentos + mapa. Cartão entra depois, completo (cadastro + fatura + parcela), sem apressar a parte mais delicada do domínio (F17–F23). O item "Cartões" pode até aparecer no menu da T-03 como desabilitado ("em breve"), para o menu já ter a forma final.

### P-T5 — Entradas aparecem no mapa? *(aberta)*
"Mapeamento dos gastos" fala de saída. Mas categoria tem tipo ENTRADA também (F12). O quadro mostra só saídas, ou um bloco separado de entradas + linha de saldo do mês? Proposta: só saídas no mínimo; entradas na evolução seguinte.

### P-T6 — Qual a forma do frontend? — **PARCIALMENTE DECIDIDO em 23/07/2026**
**SPA leve, compilada para arquivos estáticos**, servida pela própria aplicação (ou pelo Caddy na fase Pi). Casa com a API JSON + JWT já construída e com o bot futuro (mesma API para os dois clientes). **Pendente:** o framework (React / Vue / Svelte) e a toolchain — decidir antes da primeira linha de tela.

### P-T7 — Lançamentos PREVISTOS entram na soma do mapa? *(aberta)*
F15: lançamento fora de cartão nasce `PREVISTO` e vira `REALIZADO` na confirmação. Com a lente de caixa (P-T2), somar previstos mistura futuro com passado. Opções: (a) só realizados; (b) tudo, com previsto destacado visualmente; (c) realizados + previstos apenas do mês corrente para frente. Afeta o desenho do endpoint de agregação — decidir junto com ele.

---

## 6. Consequências imediatas (quando as perguntas fecharem)

1. Desenhar a **V10 fatia 1** (migração primeiro, código depois — P3), crescendo os testes do Bloco C: fumaça de RLS para as tabelas novas, regras de domínio puras (padrão B-C3) para as somas do mapa.
2. Especificar os **endpoints da fatia 1** neste documento (ou em `docs/api.md`), antes do código.
3. Esboço visual (wireframe) das T-03/T-07 — texto ou rabisco, o suficiente para validar a leitura da matriz antes de codificar.
