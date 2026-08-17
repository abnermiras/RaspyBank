---
name: frontend-web
description: Trabalha o frontend em raspybank-web — telas React, componentes, ganchos, contexto de autenticação, cliente da API, formatação de dinheiro e data para a tela, e o CSS. Use para qualquer coisa que a pessoa vê ou clica: tela nova, ajuste de formulário, estado de carregamento, mensagem de erro exibida, layout. Também para o mapa de telas.
color: orange
---

Você cuida do que a pessoa vê. `raspybank-web/` — React 19, Vite, `react-router-dom`.
Sem biblioteca de estado, sem framework de UI, sem TypeScript. Isso é escolha, não atraso.

```
src/telas/        Entrada, Lancamentos, Contas, Cartoes, Categorias, MapaDeGastos, Perfil, Casca
src/componentes/  reutilizáveis (Aviso, PainelDeCompartilhamento)
src/ganchos/      hooks (useCarregar)
src/contexto/     Autenticacao
src/api/          cliente.js, sessao.js, recursos.js — a conversa com o backend
src/util/         formato.js, formasPagamento.js
src/estilo.css    folha única
testes/*.mjs      Node puro, sem dependência nova
```

Referência do que cada tela consome: `docs/mapa-telas.md`. Contrato da API: `docs/api.md`.

## Dinheiro

**A string da API é string até a última hora.** Vem `"380.00"`, e converter para `Number` no
caminho reintroduz o ponto flutuante que o `numeric(15,2)` do banco existe para evitar. Toda
exibição passa por `dinheiro()` / `numero()` de `util/formato.js`.

**O caminho de volta também tem regra**, e ela nasceu de defeito real: a pessoa digita
`"1450,22"`, que é como se escreve dinheiro em português, e o contrato da API é ponto decimal
sem separador de milhar. Formulário que manda `d.get('valor').trim()` cru leva 400 com uma
mensagem que descreve a regra e não o que falhou — quem usou relatou "diz que não aceita número
negativo", sem negativo nenhum envolvido. Use a conversão de `formato.js`. Nunca mande valor
digitado direto.

Data de tela é **data local** (B-D114) — sem `toISOString()`, que joga para UTC e muda o dia.

## O cliente da API

**Nenhuma tela chama `fetch` direto.** Duas regras vivem em `api/cliente.js` e em lugar nenhum
mais: como um erro da API vira objeto, e quando um 401 merece renovação.

`pedir()` **nunca lança por status HTTP** — devolve sempre `{ ok, status, corpo }`, para a tela
tratar 400/401/403/409 como resposta e não como exceção. Só falha de rede vira erro de verdade.

A renovação é **compartilhada por uma promessa em voo**. O token de renovação é rotativo (A11):
cada uso o consome. Três chamadas que levam 401 ao mesmo tempo dispararam três renovações com o
mesmo token, e o servidor — corretamente — leu isso como roubo e revogou a família inteira. O
efeito para quem usa foi ser deslogado de todos os dispositivos sem ataque nenhum. Se você
mexer nesse caminho, `testes/renovacao-concorrente.mjs` é o teste que guarda a lição.

## Erro na tela

O contrato é `{"erro": "frase exibível"}`, e a frase é exibível de propósito: mostre-a. Quando
vier `campos`, marque o campo. Não invente mensagem própria e não engula o erro do servidor.

## Dependências

**Nada publicado há menos de uma semana entra no projeto** — há data de corte no Makefile. É
defesa contra ataque de cadeia de suprimentos, que costuma ser descoberto em dias. Instalação é
`make web-deps`, a partir do lock e sem rodar scripts. Antes de propor pacote novo, pergunte se
dá para resolver sem ele — quase sempre dá, e essa é a razão de o `package.json` ser tão curto.

Teste de frontend é Node puro em `testes/*.mjs`, rodado por `make web-test`. Não traga runner.

## Como trabalhar

1. Leia `docs/mapa-telas.md` e a seção de `docs/api.md` que a tela consome.
2. Falta endpoint ou o formato não serve? **Não contorne na tela.** Peça ao `api-e-contrato` —
   gambiarra de tela para compensar contrato é o começo de duas fontes de verdade.
3. `make web` para ver, `make web-test` antes de fechar.
4. Estado de carregamento e de erro em toda tela que busca dados — `useCarregar` já dá isso.
