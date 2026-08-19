# RaspyBank — Contrato da API

**Versão:** 1.0
**Data:** 26 de julho de 2026
**Escopo:** endpoints da **V10** (fatia 1 do domínio). Autenticação e sessão já existem e estão descritas na seção 2 para referência.
**Regra deste documento:** o contrato é escrito **antes** do código (consequência 2 do Mapa de Telas). Endpoint implementado diferente do que está aqui é defeito de um dos dois lados — os dois merecem parada. Decisão de comportamento vive em `decisoes.md`; aqui vive a **forma**.

---

## 1. Convenções

### Autenticação
Todo endpoint fora de `/api/auth/**` exige `Authorization: Bearer <token de acesso>`. O prefixo `/api` nasce protegido — liberar é ato explícito, nunca padrão (B-T7).

### O ambiente é implícito
Nenhum endpoint recebe `ambienteId` no corpo ou na query. O ambiente vem do token de acesso, e trocá-lo é `POST /api/sessao/ambiente` (B-T7). Consequência direta em `POST /api/lancamentos`: o lançamento nasce no ambiente ativo, mesmo em conta compartilhada (B-D2).

**Uma exceção, explícita: `POST /api/convites/{id}/aceitar` recebe `ambienteId` no corpo** (B-D90, §2d). Não é furo na regra — é o próprio ponto do endpoint: só quem aceita pode escolher em qual dos ambientes dela a conta vai aparecer, e um valor padrão (o ambiente ativo) seria a escolha silenciosa que B-D90 recusou. Qualquer endpoint novo que "precise" de `ambienteId` no corpo tem que se justificar contra essa decisão, não citar este como precedente solto.

### Dinheiro é string
Valores monetários trafegam como **string** (`"450.00"`), nunca como número JSON. Número em JSON é `double` no JavaScript, e `double` para dinheiro é proibido por F1 — mandar número seria abrir na borda o buraco que o modelo fechou no banco. O cliente converte para decimal; o servidor lê como `BigDecimal`.

### Datas são datas
`data_caixa` e `data_competencia` são `date` (B-D8), no formato `"2026-07-26"`, **sem hora e sem fuso**. Timestamp de auditoria (`criadoEm`) é ISO-8601 com offset — esse sim é instante.

### Erros
Contrato único de B-T1: todo erro devolve `{"erro": "<frase exibível>"}`, e a validação acrescenta `{"campos": {"<campo>": "<mensagem>"}}`. Códigos: **400** validação, **401** sem sessão válida, **403** vínculo inexistente, **404** recurso inexistente, **409** conflito, **500** erro nosso sem detalhe.

### Nada é excluído fisicamente
Categoria e subcategoria **arquivam** (B-D4); conta **encerra** (F7); lançamento é a única entidade com exclusão de verdade (F16), auditada. Por isso os verbos: `POST /{id}/arquivar` em vez de `DELETE`.

---

## 2. Já implementado (referência)

| Método | Caminho | Efeito |
|---|---|---|
| `POST` | `/api/auth/cadastro` | Cria usuário + primeiro ambiente atomicamente (A12). **409** se o e-mail já existe |
| `POST` | `/api/auth/login` | Devolve `tokenAcesso`, `tokenRenovacao`, `ambienteId` |
| `POST` | `/api/auth/renovar` | Rotação atômica (B-T3). Aceita `ambienteId` opcional e o preserva (B-T6) |
| `POST` | `/api/auth/logout` | Revoga só a família da sessão atual — este dispositivo (B-T5) |
| `POST` | `/api/auth/logout-todos` | Revoga todas as famílias do usuário |
| `GET` | `/api/perfil` | Usuário, ambiente atual, canal, ambientes visíveis (filtrados por RLS). Devolve `telegramId` desde a V18 |
| `PUT` | `/api/perfil/nome` | Troca o nome de exibição (B-D71) |
| `PUT` | `/api/perfil/telegram` | Troca o `telegramId` do cadastro, ou apaga com valor vazio (B-D115, 18/08/2026) |
| `PUT` | `/api/perfil/senha` | Troca a senha, exigindo a atual (B-D72) |
| `POST` | `/api/sessao/ambiente` | Troca o ambiente ativo. **403** se não houver vínculo (B-T7) |

### `POST /api/auth/cadastro` — o campo `telegramId` (V18, 29/07/2026)

```json
{ "nome": "Abner", "email": "...", "senha": "...", "telegramId": "@abner" }
```

**Opcional.** É ele que vai ligar a pessoa ao bot (etapa 3 do roteiro); a coluna existe desde a V1 e o caminho de escrita chegou na V18.

- **Vazio ou ausente vira `NULL`**, e isso não é detalhe: `ux_usuario_telegram` é um índice **parcial** (só não-nulos). String vazia gravada seria um valor *real* para o índice, e o segundo cadastro sem Telegram falharia por duplicidade num campo que ninguém preencheu. **Desde a V21, o `CHECK ck_usuario_telegram_identificavel` também recusa `'@'` sozinho e espaços puros** — o critério não é "diferente de vazio", é "sobra algo depois de normalizar" (I-32).
- **409** quando o mesmo Telegram já está em outra conta — duas contas apontando para o mesmo destino fariam o bot não saber para quem lançar. **A partir da V21, a comparação é pela forma normalizada** (minúsculas, sem `@` inicial): `abner`, `ABNER` e `@abner` são o mesmo destino para o índice, e o 409 dispara mesmo quando a grafia enviada difere da já gravada. Antes da V21 não era assim — três grafias diferentes passavam como três contas distintas apontando para o mesmo Telegram (I-31).
- **A validação é frouxa de propósito:** aceita o id numérico (`123456789`) e o usuário (`@abner`). O bot ainda não existe, e uma regra apertada agora tem chance de barrar justamente o valor certo; enquanto não há consumidor, valor errado não causa dano. Quando o bot chegar, ele aperta com a forma que exigir.
- **O caminho de edição chegou em 18/08/2026** — ver `PUT /api/perfil/telegram`, abaixo (B-D115). Isto supera parcialmente B-D105; o resto da decisão (validação frouxa, vazio vira `NULL`) segue valendo, agora nas duas bordas.

### `PUT /api/perfil/nome`
```json
{ "nome": "Abner" }
```
Troca só o **nome de exibição** (B-D71). **O e-mail não tem endpoint de troca**, e a ausência é a proteção: é ele que faz login, e enquanto não existir recuperação de senha, um e-mail digitado errado trancaria a pessoa para fora da própria conta sem volta. Resposta: o mesmo corpo de `GET /api/perfil`, já atualizado.

### `PUT /api/perfil/telegram` (B-D115, 18/08/2026)
```json
{ "telegramId": "123456789" }
```
Troca o `telegramId` gravado no cadastro. **Mesma validação do cadastro** (`@Size(max = 64)`, `@Pattern("|@?[A-Za-z0-9_]{3,63}")`): aceita o id numérico e o `@usuario`, pelo mesmo motivo de então — o bot ainda não existe, e uma regra apertada tem chance de barrar o valor certo. Duas bordas com regras diferentes para o mesmo campo seria divergência.

- **Vazio limpa o campo** (grava `NULL`, não string vazia): `ux_usuario_telegram` é índice **parcial** — só sobre valores não-nulos —, e string vazia seria um valor real para ele; a segunda pessoa a limpar o campo bateria em duplicidade. Mesmo raciocínio do `NULLIF(btrim(...), '')` da V18, agora em Java porque aqui é `UPDATE` direto, não a função de cadastro. **Desde a V21 o banco também garante isto**, com `ck_usuario_telegram_identificavel`: mesmo que um caminho de escrita futuro esqueça a regra em Java, `''`, espaços puros e `'@'` sozinho não gravam (I-32) — a cópia em Java deixou de ser a única guarda.
- **409** quando o valor já pertence a outra conta — o `TratadorGlobalDeErros` já reconhece a violação de `ux_usuario_telegram`. **Desde a V21, "já pertence" é pela forma normalizada** (I-31): gravar `@abner` quando já existe `ABNER` também dá 409.
- **Sem migração e sem função `SECURITY DEFINER` nova para este endpoint especificamente.** A V8 revogou só o `SELECT` da coluna e manteve a escrita concedida; `pol_usuario_escrita` (V15) já casa `id = app_usuario_id()`, e no Perfil há identidade na sessão — o impasse que forçou a função no cadastro (V18) não existe aqui. Mesmo caso do B-D73 (troca de senha). **A V21 chegou depois, por um motivo diferente** — não por este endpoint, mas porque o `qa-adversarial` achou que o índice de baixo não cumpria o que esta seção já afirmava (I-31/I-32).
- **Não há verificação de posse do valor** (B-D116). Quem digitar o Telegram de outra pessoa não recebe aviso: só o índice único impede duas contas apontando para o mesmo destino, e o bot simplesmente não reconhece quem digitou errado. Escolha, não pendência — e a V21 é o que torna essa frase literalmente verdadeira; antes dela, a garantia do índice tinha um furo (I-31).

Resposta: o mesmo corpo de `GET /api/perfil`, já atualizado — igual a `PUT /api/perfil/nome`.

### `PUT /api/perfil/senha`
```json
{ "senhaAtual": "...", "senhaNova": "..." }
```
Exige a senha **atual** — sem ela, quem sentasse numa máquina com a sessão aberta tomaria a conta. **403** quando a atual não confere, com a mesma mensagem enxuta do login. Sucesso: **204**, sem corpo.

**Troca a senha e derruba as OUTRAS sessões** (B-D72). É o comportamento certo pelo motivo oposto ao do 403: se a troca aconteceu porque a senha vazou, deixar as antigas vivas manteria o invasor dentro. Quem trocou continua logado nesta sessão.

### Códigos de erro desta seção

| Código | Quando |
|---|---|
| `400` | `nome` vazio ou maior que 120 caracteres; `senhaAtual`/`senhaNova` ausentes; `senhaNova` com menos de 10 caracteres |
| `403` | `senhaAtual` incorreta |

---

## 3. Categorias e subcategorias

### `GET /api/categorias`
Lista as categorias do ambiente ativo, **com as subcategorias aninhadas**. Uma chamada só: a T-04 mostra os dois níveis juntos, e dois endpoints obrigariam a tela a costurar o que o banco já sabe juntar.

Query opcional: `?incluirArquivadas=true` (padrão `false`).

```json
{
  "categorias": [
    {
      "id": "0198...", "codigo": null, "nome": "Mercado", "tipo": "SAIDA",
      "sistemica": false, "entraNoMapa": true, "arquivadaEm": null,
      "subcategorias": [
        { "id": "0198...", "nome": "Feira", "arquivadaEm": null }
      ]
    },
    {
      "id": "0198...", "codigo": "TRANSFERENCIA", "nome": "Transferência",
      "tipo": "AMBOS", "sistemica": true, "entraNoMapa": false,
      "arquivadaEm": null, "subcategorias": []
    }
  ]
}
```

`sistemica` e `entraNoMapa` viajam separados de propósito (B-D15): a tela usa o primeiro para desenhar o cadeado e o segundo para explicar a ausência no mapa. São perguntas diferentes, e uma flag só respondendo às duas foi o defeito que o I-01 ensinou a evitar.

### `POST /api/categorias`
```json
{ "nome": "Transporte", "tipo": "SAIDA" }
```
Nasce com `sistemica: false`, `entraNoMapa: true`. **409** se já existir categoria não arquivada com o mesmo nome no ambiente — nomes repetidos tornariam o seletor da T-08 ambíguo.

### `PUT /api/categorias/{id}`
```json
{ "nome": "Transporte urbano", "tipo": "SAIDA" }
```
Renomear é **ação leve** (B-D3): o nome é texto pendurado no id, então a troca aparece em todos os lançamentos, passados inclusive, e nunca cria categoria nova. **403** se `sistemica: true` (F10).

### `POST /api/categorias/{id}/arquivar` · `POST /api/categorias/{id}/desarquivar`
Arquivada, a categoria some do seletor da T-08 e **continua nomeando o histórico inteiro** — é por isso que não existe exclusão (B-D4). **403** se sistêmica.

### `POST /api/categorias/{id}/subcategorias` · `PUT /api/subcategorias/{id}` · `POST /api/subcategorias/{id}/arquivar` · `POST /api/subcategorias/{id}/desarquivar`
Mesmas regras. **403** ao criar subcategoria em categoria sistêmica: `TRANSFERENCIA > Pix` não significa nada, e as três sistêmicas existem para o código achar, não para a pessoa organizar. Não existe endpoint de sub-subcategoria: a estrutura tem dois níveis por decisão (F8), e a ausência do caminho é a garantia.

> `desarquivar` de subcategoria foi **acrescentado ao contrato em 26/07/2026**, ao implementar a fatia 1. Sem ele, arquivar uma subcategoria seria irreversível pela tela — o que contradiz B-D4, onde arquivar é justamente a alternativa reversível à exclusão. A categoria já tinha o par completo; a subcategoria tinha só metade, por omissão.

### Códigos de erro desta seção

| Código | Quando |
|---|---|
| `400` | `nome` vazio ou `tipo` fora de `ENTRADA`/`SAIDA`/`AMBOS`. Corpo traz `campos` |
| `403` | Cadeado da sistêmica (F10), ou sessão sem ambiente ativo |
| `404` | Id inexistente **ou de outro ambiente seu** (B-D21). Os dois casos respondem igual de propósito: distinguir viraria um oráculo sobre quais ids existem |
| `409` | Nome já usado por categoria/subcategoria **ativa**. Índice parcial: arquivar e recriar com o mesmo nome é legítimo |

---

## 4. Contas

### `GET /api/contas`
Query opcional: `?incluirEncerradas=true` (padrão `false`).

```json
{
  "contas": [
    {
      "id": "0198...", "nome": "Conta conjunta", "natureza": "ATIVO",
      "encerradaEm": null,
      "saldo": "1250.00", "saldoComPrevistos": "980.00",
      "ambientes": [ { "id": "0198...", "nome": "Casa" } ],
      "formasPagamento": ["DEBITO", "PIX", "BOLETO", "CREDITO_EM_CONTA"],
      "padraoSaida": "DEBITO",
      "padraoEntrada": "CREDITO_EM_CONTA"
    }
  ]
}
```

`saldo` é **sempre calculado** (P1/F2), nunca uma coluna. `ambientes` mostra em quais ambientes a conta é visível — é o que torna B-D2 compreensível para quem olha a tela: dá para ver que a conta é compartilhada e entender por que o gasto foi para o ambiente ativo.

> **Dois saldos, não um — mudança de 26/07/2026 (B-D26).** O contrato previa um `saldo` só. Ao implementar, ficou claro que ele repetiria o defeito que B-D10 evitou no mapa: somar o que já aconteceu com o que está agendado faz o número significar duas coisas ao mesmo tempo, e a tela não teria como separar depois. `saldo` é o dinheiro que está lá (só `REALIZADO`); `saldoComPrevistos` inclui o que já está agendado. É o `saldo` que precisa ser zero para encerrar.

> **Limite conhecido (I-23).** As somas alcançam apenas os lançamentos que o RLS libera — os dos ambientes a que a pessoa pertence. Numa conta conjunta visível também no ambiente pessoal do outro, cada um vê um total diferente. Não foi corrigido de propósito: a correção exigiria uma função `SECURITY DEFINER` nova, e o critério B-D19 só a autoriza diante de impasse estrutural com a política — este é escolha de visibilidade, não impasse. Só passa a doer quando existir convite de usuário (I-08), que ainda não existe.

### `POST /api/contas`
```json
{
  "nome": "Poupança", "natureza": "ATIVO", "saldoInicial": "3000.00",
  "formasPagamento": ["DEBITO", "PIX", "CREDITO_EM_CONTA"],
  "padraoSaida": "DEBITO", "padraoEntrada": "CREDITO_EM_CONTA"
}
```
`saldoInicial` é **opcional e não é campo da conta**: quando presente, o servidor cria um lançamento na categoria sistêmica `AJUSTE` (A13/B-D13). A tela deixa isso visível em vez de fingir que existe um campo mágico — o saldo continua sendo só a soma dos lançamentos.

`saldoInicial` é validado como decimal de até duas casas **antes** de chegar ao banco: `numeric(15,2)` arredondaria `10.005` para `10.01` em silêncio. Aceita negativo — conta `PASSIVO` ou corrente no vermelho começam devendo, e o sinal escolhe o sentido do lançamento (o `valor` gravado segue positivo, F1).

### `PUT /api/contas/{id}` · `POST /api/contas/{id}/encerrar` · `POST /api/contas/{id}/reabrir`
Conta não se exclui, se encerra (F7). Encerrada, some dos seletores e mantém o histórico. **409** ao encerrar conta com saldo diferente de zero — dinheiro não evapora; é preciso transferir ou ajustar antes. A checagem olha o `saldo` (realizado): previsto é agenda, não dinheiro.

> `reabrir` foi **acrescentado ao contrato em 26/07/2026**, pelo mesmo motivo do `desarquivar` de subcategoria. Encerrar é a alternativa *reversível* à exclusão; sem a volta, um clique errado tiraria a conta dos seletores para sempre, e o único contorno seria criar outra — partindo o histórico em duas.

As escritas respondem na **mesma forma** do `GET`, com saldo e vínculos recalculados. Devolver uma forma reduzida obrigaria a tela a ter dois caminhos de leitura para o mesmo objeto, e o segundo é sempre o que fica desatualizado.

### `PUT /api/contas/{id}/formas-pagamento`
```json
{
  "formas": ["DEBITO", "PIX", "BOLETO", "CREDITO_EM_CONTA"],
  "padraoSaida": "PIX", "padraoEntrada": "CREDITO_EM_CONTA"
}
```
Substitui a lista **inteira**, não acrescenta — a tela mostra as caixas com as marcadas, então o que ela envia já é o estado desejado. `formas: []` deixa a conta sem nenhuma; padrão `null` é válido (aceitar várias sem ter preferência).

**`DINHEIRO` é exclusivo** (B-D41): ou a lista tem só `DINHEIRO`, ou não tem `DINHEIRO` nenhum. Papel moeda só existe em conta física — carteira, gaveta, cofre — e nenhuma delas recebe pix; conta em banco guarda dinheiro virtual, e tirá-lo de lá é um saque, não pagamento em espécie. `["DINHEIRO", "PIX"]` responde **403**.

**Dois padrões, um por sentido** (B-D31). Entrada também tem "como o dinheiro se moveu": o salário é *creditado*. Cada padrão precisa estar em `formas` **e** aceitar o sentido correspondente — `padraoSaida: "CREDITO_EM_CONTA"` responde **403**, porque não se paga gasolina com crédito em conta.

Endpoint próprio e não um campo no `PUT /{id}` porque os riscos são diferentes: renomear nunca falha, mexer na lista pode ser recusado — e juntar os dois faria uma recusa dessas impedir também a troca de nome, que não tinha nada a ver.

### `GET /api/formas-pagamento`
O vocabulário, com os sentidos que cada forma aceita. Somente leitura, e não haverá escrita: a lista é fixa (B-D30) — forma nova é uma migração, não um cadastro.

```json
{
  "formasPagamento": [
    { "valor": "DEBITO", "nome": "Débito", "sentidos": ["SAIDA"] },
    { "valor": "PIX", "nome": "Pix", "sentidos": ["ENTRADA", "SAIDA"] },
    { "valor": "CREDITO_EM_CONTA", "nome": "Crédito em conta", "sentidos": ["ENTRADA"] }
  ]
}
```

**Existe para o frontend não reescrever a regra.** Ela já vive em dois lugares por necessidade — as onze linhas de `forma_pagamento_sentido`, que o banco impõe, e o enum Java, que produz a mensagem de erro. Uma terceira cópia em JavaScript divergiria na primeira forma nova, e o sintoma seria o seletor oferecendo o que o servidor recusa (B-D33).

Oito valores: `DEBITO`, `PIX`, `CREDITO_EM_CONTA`, `BOLETO`, `DEBITO_AUTOMATICO`, `DINHEIRO`, `TED`, `DESCONTO_EM_FOLHA`.

Duas ausências e dois nomes longos, todos deliberados:

- **Crédito de cartão não está aqui** (B-D36). Compra no cartão nasce na conta do cartão; quem debita a corrente é o pagamento da fatura. É a V12.
- **`CREDITO_EM_CONTA` e não `CREDITO`** — em português "crédito" significa tanto o cartão quanto "entrou dinheiro", e `DEBITO` na mesma lista já é o cartão.
- **`TED` e não `TRANSFERENCIA`** (B-D37) — `TRANSFERENCIA` já é o código de uma categoria sistêmica.
- **Não existe `SAQUE`** (B-D39). Sacar é transferir da conta para a carteira; um segundo nome para o mesmo evento obrigaria todo relatório futuro a conhecer os dois.

> **409 ao remover forma em uso.** Tirar `BOLETO` de uma conta que tem lançamentos com boleto é recusado, e a mensagem diz quantos. A alternativa apagaria em silêncio exatamente a informação que o campo veio registrar (B-D33).

### Códigos de erro desta seção

| Código | Quando |
|---|---|
| `400` | `nome` vazio, `natureza` fora de `ATIVO`/`PASSIVO`, `saldoInicial` malformado, `formas` ausente no `PUT` de formas |
| `403` | Sessão sem ambiente ativo; `formaPadrao` fora de `formasPagamento` |
| `404` | Id inexistente ou de conta não vinculada ao ambiente ativo (B-D21) |
| `409` | Encerrar conta com saldo diferente de zero; remover forma que algum lançamento usa |

---

## 5. Lançamentos

### `GET /api/lancamentos?mes=2026-07`
Filtro obrigatório por mês (sobre `dataCaixa`). Opcionais: `contaId`, `categoriaId`, `situacao`.

```json
{
  "lancamentos": [
    {
      "id": "0198...", "descricao": "Mercado do mês",
      "valor": "380.00", "tipo": "SAIDA", "situacao": "REALIZADO",
      "dataCaixa": "2026-07-12", "dataCompetencia": "2026-07-12",
      "conta": { "id": "0198...", "nome": "Conta conjunta" },
      "categoria": { "id": "0198...", "nome": "Mercado" },
      "subcategoria": null,
      "formaPagamento": "DEBITO",
      "lancamentoParId": null,
      "criadoEm": "2026-07-12T19:04:11-03:00"
    }
  ]
}
```

Categoria e subcategoria viajam como objeto `{id, nome}` resolvido na hora — não existe nome congelado no lançamento (B-D4 / R8).

### `POST /api/lancamentos`
```json
{
  "contaId": "0198...", "categoriaId": "0198...", "subcategoriaId": null,
  "valor": "380.00", "dataCaixa": "2026-07-12", "descricao": "Mercado do mês",
  "formaPagamento": "PIX"
}
```

Sete campos, e três ausências que são decisão, não esquecimento:

- **sem `ambienteId`** — vem da sessão (B-D2);
- **sem `situacao`** — deriva de `dataCaixa`: passado ou hoje → `REALIZADO`, futuro → `PREVISTO` (B-D9 / R9);
- **sem `tipo`** — deriva de `categoria.tipo` (F12); quando a categoria é `AMBOS`, aí sim o campo `tipo` passa a ser obrigatório no corpo.

**403 na categoria `TRANSFERENCIA`** (B-D42). Ela só nasce em par, por `POST /api/transferencias` — um lançamento avulso aqui seria meia transferência, com dinheiro saindo de uma conta sem entrar em nenhuma. As outras duas sistêmicas continuam aceitas: `AJUSTE` é o caminho que a mensagem de encerrar conta indica, e `NAO_CLASSIFICADO` é o destino do bot do Telegram. A guarda **não** vale no `PUT`: editar uma perna existente é legítimo.

`dataCompetencia` é opcional e, ausente, copia `dataCaixa` (F14). **403** se a conta não pertencer ao ambiente ativo — a restrição de B-D2 é conferida no banco, e o 403 é a tradução dela.

#### `formaPagamento` — opcional, e passa por DUAS perguntas

Quando **informada**, ela precisa passar nas duas, e elas são diferentes:

1. **A conta aceita esta forma?** Senão **403**, dizendo quais aceita. Garantida por `(conta_id, forma_pagamento) → conta_forma_pagamento`.
2. **Esta forma serve a este sentido?** Senão **403**. Garantida por `(forma_pagamento, tipo) → forma_pagamento_sentido`. É esta que impede "salário pago no boleto" numa conta que aceita as duas formas.

Quando **ausente**, o servidor assume o padrão da conta **para aquele sentido** — `padraoSaida` num gasto, `padraoEntrada` numa receita — e só se a categoria não for sistêmica (B-D32).

A guarda de sistêmica não é detalhe: sem ela o saldo de abertura de toda conta nova apareceria no extrato como "pago no débito", que ninguém digitou. E é ela também que faz as duas pernas de uma transferência nascerem sem forma, **sem nenhum caso especial no código**.

`formaPagamento` é **dimensão de análise** (B-D30): explica como o dinheiro se moveu e não entra em soma nenhuma. Saldo, situação e mapa de gastos são exatamente o que eram antes dela.

### `GET /api/lancamentos/{id}` · `PUT /api/lancamentos/{id}` · `DELETE /api/lancamentos/{id}`
Lançamento é editável e excluível **com auditoria** (F16), e a auditoria é por gatilho lendo o contexto do RLS + o canal (F26 / B-D6). `PUT` aceita `situacao` explícito — a derivação de B-D9 vale na criação; corrigir depois é legítimo e é o que a lista da T-08 oferece. `PUT` é substituição completa: trocar a categoria **zera a subcategoria**, porque ela pertencia à anterior e adivinhar uma equivalente seria inventar dado. `DELETE` responde **204**.

> **`formaPagamento` vazia significa coisas diferentes no `POST` e no `PUT`** (B-D32). No `POST` cai no padrão da conta; no `PUT` **limpa** o campo. Não é inconsistência: no `PUT` a tela mostra o campo já preenchido com o valor atual, então mandar vazio é um ato — a pessoa está limpando, e reaplicar o padrão desfaria no servidor o que ela acabou de fazer.

> `GET /api/lancamentos/{id}` foi acrescentado em 26/07/2026: as escritas respondem na mesma forma da lista, e ler de volta depois de gravar é o único jeito de a tela receber a classificação resolvida sem montá-la por conta própria.

### Códigos de erro desta seção

| Código | Quando |
|---|---|
| `400` | `valor` negativo ou com mais de duas casas, campo obrigatório ausente, `mes` malformado |
| `403` | Categoria não aceita o sentido (F12); categoria `AMBOS` sem `tipo`; subcategoria de outra categoria (F11); **conta fora do ambiente ativo** (B-D2); `formaPagamento` que a conta não aceita |
| `404` | Lançamento, categoria ou subcategoria inexistente, ou de outro ambiente seu (B-D21) |

O 403 da conta fora do ambiente é a **tradução da chave composta** `(ambiente_id, conta_id) → conta_ambiente`: o banco recusaria de qualquer forma, mas diria apenas que uma restrição falhou. A frase existe para a tela ter o que mostrar.

---

## 5b. Transferências

### `POST /api/transferencias`
```json
{
  "contaOrigemId": "0198...", "contaDestinoId": "0198...",
  "valor": "100.00", "dataCaixa": "2026-07-27", "descricao": "Saque no caixa"
}
```

Cria **dois** lançamentos ligados (F2) numa transação só, e responde **201** com as duas pernas:

```json
{
  "saida":   { "id": "0198a...", "contaId": "...", "valor": "100.00",
               "situacao": "REALIZADO", "lancamentoParId": "0198b..." },
  "entrada": { "id": "0198b...", "contaId": "...", "valor": "100.00",
               "situacao": "REALIZADO", "lancamentoParId": "0198a..." }
}
```

**Existe como recurso próprio porque a primeira perna sozinha já é um saldo errado** (B-D40). Dois `POST /api/lancamentos` em sequência deixariam, se o segundo falhasse, 100 reais tendo saído de uma conta sem terem entrado em nenhuma — e nada denunciaria isso depois.

Três ausências no corpo, todas decisão:

- **sem `categoriaId`** — é sempre a sistêmica `TRANSFERENCIA`. Deixar escolher permitiria classificar uma transferência como "Mercado", e o mapa contaria como despesa um dinheiro que só trocou de bolso;
- **sem `tipo`** — origem é sempre saída, destino sempre entrada;
- **sem `formaPagamento`** — as duas pernas nascem sem forma, porque categoria sistêmica não recebe padrão. Quem quiser registrar "transferi por pix" edita a perna depois pelo `PUT /api/lancamentos/{id}`.

`descricao` é opcional; ausente, cada perna ganha o nome da **outra** conta.

> **Sacar dinheiro é isto.** Não existe endpoint, categoria nem forma de pagamento chamada "saque" (B-D39): sacar é transferir da conta para a carteira, e o destino ser uma conta de espécie é o que torna a operação um saque. Um segundo nome para o mesmo evento obrigaria todo relatório futuro a conhecer os dois.

### Não existe `GET` nem `DELETE` aqui

Ler transferência é ler lançamento: as duas pernas aparecem no extrato da T-08 como os lançamentos que são, com `lancamentoParId` preenchido. E excluir uma perna **já apaga a outra**, por `ON DELETE CASCADE` (B-D38) — um `DELETE /api/transferencias/{id}` seria um segundo caminho para o mesmo efeito, e segundo caminho é onde as regras divergem.

### O que propaga, e o que não propaga

`PUT /api/lancamentos/{id}` numa perna propaga para a outra o que **precisa** ser igual: valor, data de caixa, competência e situação. Se um lado virasse 100 e o outro continuasse 10, noventa reais apareceriam do nada — em silêncio, porque nenhum saldo isolado pareceria errado.

Não propaga a **conta** nem a **descrição**: a conta é o que distingue as pernas (corrigir "saiu do Nubank, não do Itaú" é de um lado só), e as descrições podem legitimamente diferir.

**403 ao mudar a categoria** de uma perna. Sair de `TRANSFERENCIA` deixaria o par com classificações diferentes em cada lado, e o mapa contaria metade de um movimento que não é gasto (B-D15).

### Códigos de erro desta seção

| Código | Quando |
|---|---|
| `400` | `valor` malformado ou com mais de duas casas, campo obrigatório ausente |
| `403` | Origem igual ao destino; conta encerrada de qualquer um dos lados; sessão sem ambiente ativo |
| `404` | Conta de origem ou de destino inexistente ou fora do ambiente ativo (B-D25) — a mensagem diz **qual dos dois lados** |

---

## 6. Mapa de gastos (T-07)

### `GET /api/relatorios/mapa-de-gastos?ano=2026`

O endpoint que a tela central consome. Um parâmetro só: o ano civil (B-D11).

**Três blocos** — saídas, entradas, saldo (B-D12) — e **cada célula com dois números**, `realizado` e `previsto` (B-D10). O servidor nunca devolve a soma pronta dos dois: se devolvesse, a tela não teria como cumprir o "deixa claro que ainda não realizou". Quem separa é o endpoint; a tela só pinta.

```json
{
  "ano": 2026,
  "ambiente": { "id": "0198...", "nome": "Casa" },
  "saidas": {
    "categorias": [
      {
        "categoriaId": "0198...", "nome": "Mercado",
        "celulas": [
          { "mes": 1, "realizado": "450.00", "previsto": "0.00" },
          { "mes": 2, "realizado": "420.00", "previsto": "0.00" }
        ],
        "total": { "realizado": "870.00", "previsto": "0.00" },
        "subcategorias": [
          {
            "subcategoriaId": "0198...", "nome": "Feira",
            "celulas": [ { "mes": 1, "realizado": "120.00", "previsto": "0.00" } ],
            "total": { "realizado": "120.00", "previsto": "0.00" }
          },
          {
            "subcategoriaId": null, "nome": "(sem subcategoria)",
            "celulas": [ { "mes": 1, "realizado": "330.00", "previsto": "0.00" } ],
            "total": { "realizado": "330.00", "previsto": "0.00" }
          }
        ]
      }
    ],
    "totaisPorMes": [ { "mes": 1, "realizado": "570.00", "previsto": "0.00" } ],
    "total": { "realizado": "570.00", "previsto": "0.00" }
  },
  "entradas": { "categorias": [], "totaisPorMes": [], "total": {} },
  "saldo": {
    "porMes": [ { "mes": 1, "realizado": "2430.00", "previsto": "0.00" } ],
    "total": { "realizado": "2430.00", "previsto": "0.00" }
  }
}
```

Regras de construção:

- **Doze células sempre**, mesmo zeradas. A tabela tem doze colunas de qualquer jeito; devolver esparso empurraria para a tela a tarefa de descobrir buraco, e é justamente aí que nasce coluna desalinhada.
- **Subcategorias aninhadas na categoria**, não em endpoint separado — os "quadros por categoria" da T-07 saem da mesma varredura. Uma tela, uma chamada.
- **`subcategoriaId: null`** é a linha "(sem subcategoria)", legítima porque F11 torna a subcategoria opcional.
- **Só entram categorias com `entraNoMapa = true`** (B-D15). Transferência entre contas próprias e ajuste de saldo ficam de fora; "Não classificado" fica **dentro** — é gasto real sem rótulo, e escondê-lo faria o total mentir para baixo.
- **Mês da célula = mês de `dataCaixa`** (P-T2), e `dataCaixa` é `date` (B-D8), então o mês não depende de fuso.
- **Escopo**: lançamentos do ambiente ativo (F33 / B-D2). ~~Nenhum filtro por ambiente aparece no SQL de aplicação — quem recorta é o RLS.~~ **Corrigido em 26/07/2026:** o filtro por `ambiente_id` **está** na consulta, e precisa estar. O tenant do RLS é o usuário (R7), então a política libera os lançamentos de *todos* os ambientes da pessoa — correto para segurança, errado para a tela. É a regra B-D21: a RLS decide o que você pode ver, o `ambienteId` decide o que você quer ver agora.
- **Ano ausente** → ano corrente. A tela abre no ano em que a pessoa está.
- **Nada é persistido** (P1). O quadro é calculado a cada chamada; se isso doer um dia, a resposta é cache com invalidação explícita, nunca coluna de total.

---

## 6b. Cartões e faturas

> Escrita na varredura que **precedeu** a V12, para ser lida e corrigida antes de qualquer migração — o mesmo procedimento que precedeu a V10 e a V11, e que nas três vezes evitou corretiva. Implementada depois da autorização, no mesmo dia. Decisões em `decisoes.md` §4f (B-D45 a B-D60).

### As três camadas

```
conta "Nubank"  (ATIVO, não-física)        ← já existe hoje
  └── cartão "Black"   limite 10.000 · vence dia 15 · fecha 5 dias antes
        ├── emitido  Abner    físico   ****1234
        ├── emitido  Luciana  físico   ****5678
        └── emitido  —        virtual  ****9012
                 os três consomem os mesmos 10.000
```

O **cartão é o contrato**, e é ele quem tem limite, vencimento e fatura (F18). O **emitido** é cada plástico ou virtual. A **fatura é do contrato** e engloba todos os emitidos dele.

O cartão **também é uma conta** `PASSIVO` (B-D47): a dívida é soma de lançamentos, nunca coluna (P1). **Mas ele NÃO aparece em `GET /api/contas`** (B-D62, revisão de 28/07/2026): *"tratar o cartão de crédito como um banco confunde"*. O recorte fica no repositório (`bancariasDoAmbiente`, que exclui toda conta que tem `Cartao`), e não em cada tela — a dívida continua no patrimônio e aparece inteira em `GET /api/cartoes`.

### `POST /api/cartoes`
```json
{
  "contaBancoId": "0198...", "nome": "Black",
  "limite": "10000.00", "diaVencimento": 15, "diasParaFechamento": 5
}
```

`contaBancoId` é obrigatório e **não pode ser uma conta física** — carteira, gaveta e cofre não emitem cartão de crédito. A regra reusa B-D41: conta cuja lista de formas é só `DINHEIRO` é física. **403** se for.

`diasParaFechamento` é opcional, padrão **5**. Fica por cartão e não fixo no sistema porque cada banco tem sua regra, e um número fixo transformaria a regra do banco em trabalho manual todo mês (B-D49).

### `GET /api/cartoes/{id}`
```json
{
  "id": "0198...", "nome": "Black", "banco": { "id": "0198...", "nome": "Nubank" },
  "limite": "10000.00", "limiteConsumido": "3420.00", "limiteDisponivel": "6580.00",
  "diaVencimento": 15, "diasParaFechamento": 5,
  "emitidos": [
    { "id": "...", "nomeTitular": "Abner", "usuarioId": "0198...",
      "tipo": "FISICO", "finalDoCartao": "1234", "limiteProprio": null }
  ],
  "faturaAberta": { "id": "...", "mesReferencia": "2026-08", "total": "1240.00" }
}
```

**O limite não trava nada** (B-D48). Nenhuma compra é recusada por estourá-lo; os três números existem para você conferir contra o app do banco.

`limiteConsumido` inclui **parcelas futuras**. Elas já existem como lançamentos desde a compra (F23), com data futura — então entram pelo `saldoComPrevistos` e não pelo `saldo` (B-D26). Comprar 10x de R$ 100 derruba o disponível em R$ 1.000 na hora, como no app do banco.

### `PUT /api/cartoes/{id}` · `PUT /api/cartoes/{id}/ciclo` · `POST /api/cartoes/{id}/encerrar` · `POST /api/cartoes/{id}/reabrir`

```json
{ "nome": "Black", "limite": "12000.00" }
```

`PUT /{id}` renomeia o contrato e altera o limite — nada mais. Endpoint próprio e não um campo genérico de contrato porque renomear nunca falha, enquanto mexer no ciclo mexe em faturas (ver abaixo); juntar os dois faria uma correção de nome arrastar consequência que ninguém pediu.

```json
{ "diaVencimento": 20, "diasParaFechamento": 7 }
```

`PUT /{id}/ciclo` muda vencimento e fechamento, e **regera somente as faturas futuras** — o passado não se reescreve. `diasParaFechamento` ausente cai no padrão de 5 (B-D49).

`POST /{id}/encerrar` **não exige dívida zero** e **cancela todos os emitidos em cascata** (B-D65). Ao contrário de encerrar uma conta comum (F7), a dívida do cartão não muda de número nenhum — as parcelas futuras continuam chegando e as faturas continuam pagáveis. O que encerrar faz é impedir compra nova, e por isso o cartão some da lista de meios de pagamento.

`POST /{id}/reabrir` devolve o contrato à lista de meios de pagamento, mas **não reativa os emitidos** (B-D65): ressuscitar em massa devolveria à vida um virtual que a pessoa descartou de propósito — virtual é feito para ser descartado. Reativar plástico é `POST /{id}/emitidos/{emitidoId}/reativar`, um a um.

As quatro respostas voltam na mesma forma do `GET /api/cartoes/{id}`.

### `POST /api/cartoes/{id}/emitidos`
```json
{ "nomeTitular": "Luciana", "tipo": "FISICO", "finalDoCartao": "5678", "limiteProprio": null }
```

`nomeTitular` é **texto livre** e `usuarioId` fica nulo (B-D53): convidar usuário é o I-08, que não existe ainda. Você registra o adicional hoje e vê os gastos dela separados; quando o convite chegar, preenche-se o vínculo e nada mais muda.

`limiteProprio` nulo significa "usa o limite do contrato" — o caso comum.

### `POST /api/cartoes/{id}/emitidos/{emitidoId}/cancelar` · `POST /api/cartoes/{id}/emitidos/{emitidoId}/reativar`

Cancelar **um** plástico não exige matar o contrato inteiro — é a tela de cartões agrupando por banco → contrato → emitidos (B-D66), com botão próprio em cada emitido. `reativar` desfaz o cancelamento individual; não confundir com `POST /{id}/reabrir`, que **não** ressuscita emitidos cancelados em massa (B-D65). Ambos respondem na forma do `GET /api/cartoes/{id}`.

### `GET /api/cartoes/{id}/faturas?ano=2026`

A fatura **não tem coluna de status** (F19), e o estado é sempre calculado. Mas ele **não é um enum de cinco valores** — são três perguntas independentes, e juntá-las num campo só seria o mesmo erro que B-D15 já custou uma vez ("sistêmica" e "entra no mapa" eram duas perguntas fingindo ser uma):

```json
{
  "id": "...", "mesReferencia": "2026-08",
  "vencimento": "2026-09-15", "fechamentoPrevisto": "2026-09-10",
  "total": "5000.00", "pago": "1000.00", "aPagar": "4000.00",
  "ciclo": "ABERTA",          // ABERTA | FECHADA — deriva de fechada_em
  "quitacao": "PARCIAL",      // NADA_PAGO | PARCIAL | QUITADA — deriva das somas
  "vencida": false            // fechada, não quitada, vencimento no passado
}
```

Por que separado: **uma fatura ABERTA pode estar PARCIALMENTE paga**. É o caso da antecipação, e num enum único ele não teria nome — ou ganharia um `ABERTA_COM_ANTECIPACAO` que existe só para tapar o buraco do desenho. A tela compõe o rótulo a partir dos três; o servidor não escolhe por ela.

> **O I-07 fecha aqui** (B-D52). Ele estava aberto desde o modelo lógico porque faltava confirmar se pagamento parcial existia. Existe, e antes do fechamento também.

Faturas são **pré-geradas 12 meses à frente** (F20), para o parcelamento ter onde cair.

### `GET /api/faturas/{id}`

Mesma forma de cada item de `GET /api/cartoes/{id}/faturas`, para uma fatura só. Existe porque as escritas desta seção (`fechar`, `reabrir`, `pagamentos`) precisam devolver a fatura com os números recalculados, e ler uma de cada vez é o caminho — abrir a tela de uma fatura específica não deveria custar buscar o ano inteiro.

### `POST /api/faturas/{id}/fechar` · `POST /api/faturas/{id}/reabrir`

O fechamento automático é `vencimento − diasParaFechamento`, recuando para a **sexta anterior** se cair em fim de semana. O botão manual existe porque cada banco tem sua regra.

`reabrir` **revoga F21**, que dizia que fatura fechada é imutável (B-D50). O motivo é o dele: fechar sem querer não pode ser irreversível.

Com a fatura fechada, **lançamento novo vai para a seguinte** — mesmo que a data da compra seja anterior ao fechamento.

### `POST /api/faturas/{id}/pagamentos`
```json
{ "contaOrigemId": "0198...", "valor": "2500.00",
  "dataCaixa": "2026-08-15", "formaPagamento": "BOLETO" }
```

Cria um **par de lançamentos ligados**, exatamente como a transferência (B-D38), com duas coisas a mais: os dois apontam para a `fatura`, e a perna de saída carrega a **forma de pagamento**, validada contra a lista da conta pagadora (V11).

Quatro liberdades deliberadas, todas pedidas explicitamente:

- **`valor` pode ser parcial** — pagar 2.500 de uma fatura de 5.000 é legítimo;
- **a fatura pode estar ABERTA** — antecipar é o mecanismo de liberar limite, ver abaixo;
- **`contaOrigemId` é livre**, inclusive de outro banco: pagar a fatura do Nubank com a conta do C6, via boleto;
- **a forma é escolhida**, porque débito e boleto são caminhos diferentes para o mesmo pagamento.

#### Antecipar pagamento é como se libera limite

Não é conveniência, é o mecanismo. O caso dele, inteiro:

```
limite 10.000 · fatura aberta com 5.000 · disponível 1.000
                                   ↓
   quer comprar 2.000, e só tem 1.000 de limite
                                   ↓
   paga 1.000 da fatura ABERTA  →  pendente 4.000 · disponível 2.000
                                   ↓
                        a compra de 2.000 passa
```

Sem antecipação, o limite só voltaria no vencimento. É por isso que a fatura aberta aceita pagamento.

O sistema **continua não travando nada** (B-D48): ele deixa lançar e deixa o disponível ficar negativo. O banco de verdade não deixaria, e é justamente por isso que o número precisa ser verdadeiro — ele existe para você ver que estourou, não para impedir.

**O pagamento aparece no extrato da conta pagadora** — o dinheiro saiu de lá, e omitir seria mentir sobre o saldo. **Não aparece no mapa de gastos**: os gastos já entraram um a um quando as compras foram lançadas, e contá-los de novo dobraria o mês.

Categoria: a sistêmica `PAGAMENTO_FATURA`, que B-D13 reservou para este momento. Como transferência, ela nasce com `entraNoMapa = false` — pagar a fatura não é um gasto novo, os gastos já entraram um a um quando as compras foram lançadas. Contá-los de novo dobraria o mês.

### Compra parcelada

`POST /api/lancamentos` numa conta que é cartão ganha dois campos opcionais:

```json
{ "contaId": "<o cartão>", "categoriaId": "...", "valor": "1000.00",
  "dataCompetencia": "2026-08-10", "parcelas": 10 }
```

Gera **10 lançamentos**, um por fatura (F23), com o resíduo dos centavos na primeira. Todos compartilham a mesma `dataCompetencia` — a data da compra — e diferem pela fatura, que é o que ele descreveu: *"a data da compra repete, muda somente a fatura"*.

**Não existe tabela `parcela`** (B-D55). Cada parcela é um lançamento com `grupoParcelamentoId`, `parcelaNumero` e `parcelaTotal`. Uma tabela só guardaria agregado — o total é a soma, a data é a competência repetida, a quantidade é a contagem — e agregado guardado contraria P1.

### Editar um lançamento de cartão

Dois campos a mais no `PUT`, e os dois foram pedidos:

- **`faturaId`** — mover a compra de mês. **403** se a fatura de destino estiver fechada; reabra antes (B-D50), senão "fechada" não significa nada.
- **`dataCompetencia`** — a data da compra muda independentemente da fatura.

### O mapa de gastos, com cartão

O gasto de cartão entra no mapa no **mês da fatura** (B-D54), não no mês da compra. É o regime de caixa que o mapa inteiro já usa (P-T2), e F14 já previa que no cartão a `data_caixa` é mantida pela fatura. Compra de 10x em agosto vira R$ 100 em cada um dos dez meses seguintes — que é quando o dinheiro realmente sai.

`GET /api/relatorios/mapa-de-gastos?ano=2026&contas=CARTAO` ganha um filtro: `TODAS` (padrão), `CARTAO`, `SEM_CARTAO`. Ele responde "quanto do meu mercado foi no cartão" **sem tocar na célula** — B-D10 separou dois números com esforço, e um terceiro em doze colunas viraria sopa.

### Códigos de erro desta seção

| Código | Quando |
|---|---|
| `400` | `limite` malformado, `diaVencimento` fora de 1–31, `parcelas` menor que 1 |
| `403` | `contaBancoId` é conta física (B-D41); mover lançamento para fatura fechada |
| `404` | Cartão, emitido ou fatura inexistente, ou de outro ambiente seu (B-D21) |
| `409` | Fechar fatura já fechada; reabrir fatura não fechada |

---

## 2c. Compartilhamento de ambiente — IMPLEMENTADO na V15 (29/07/2026)

> Escrita como desenho na varredura que precedeu a V15 e implementada no mesmo dia. Decisões em `decisoes.md` §4j (B-D74 a B-D84); o que a implementação acrescentou ao desenho está marcado como **[V15]** ao longo da seção e consolidado em §4j.

### A ideia, na frase dele

*"É como se eu desse a minha senha para a pessoa, mas ao invés de dar minha senha dei meu acesso."*

Quem recebe o acesso vê o ambiente na própria lista, entra nele pelo seletor de sempre, e a partir dali **trabalha dentro dele**: as categorias são as do dono, as contas são as do dono, o mapa é o do dono. Toda ação fica carimbada com o nome de quem a fez.

### `POST /api/ambientes/{id}/acessos`
```json
{ "email": "luciana@exemplo.com" }
```

Concede acesso **imediato**, sem aceite (B-D80). Responde **404** se não houver usuário com aquele e-mail — e isso é um oráculo de enumeração, aceito conscientemente (B-D81): a alternativa esconderia o erro mais comum, que é digitar o e-mail do próprio conhecido errado.

Só o **dono** concede. **403** para quem tem acesso mas não é dono.

**[V15]** Sucesso responde **201** com a lista de acessos resultante (mesmo formato do GET abaixo), no padrão de `POST /api/ambientes`.

### `GET /api/ambientes/{id}/acessos`
```json
{
  "acessos": [
    { "usuarioId": "0198...", "nome": "Abner",   "email": "...", "dono": true  },
    { "usuarioId": "0198...", "nome": "Luciana", "email": "...", "dono": false }
  ]
}
```

O dono vem primeiro, o resto na ordem de chegada. Qualquer membro vê a lista: quem compartilha finanças precisa saber com quem compartilha.

**[V15]** `GET /api/ambientes` (§2b) e a lista de ambientes do perfil ganharam **`dono`** em cada item: com compartilhamento, a lista mistura ambientes próprios e emprestados, e a tela precisa saber onde há porta (convidar, remover) e onde marcar "compartilhado comigo".

### `DELETE /api/ambientes/{id}/acessos/{usuarioId}`

**O dono remove qualquer um; qualquer um remove a si mesmo; o dono não sai** (B-D77). Sem a segunda regra, quem recebeu acesso ficaria preso num ambiente que não pediu; sem a terceira, sobraria um ambiente órfão que ninguém pode mais administrar.

**[V15]** Sucesso responde **204**, sem corpo.

> **Quem estava dentro na hora da revogação.** O JWT já carrega aquele `ambienteId` e vale mais quinze minutos. A RLS para de devolver dados imediatamente — nada indevido aparece —, mas a tela ficaria vazia e sem explicação.
>
> Resolvido por **B-D83**: toda requisição confere se o ambiente do token ainda pertence a quem o apresenta, e responde **403 com frase** quando não pertence. A tela lê isso e volta para um ambiente que é dela.
>
> **[V15]** O corpo do 403 carrega um marcador **estável** além da frase:
>
> ```json
> { "erro": "Voce nao tem mais acesso a este ambiente",
>   "motivo": "SEM_ACESSO_AO_AMBIENTE" }
> ```
>
> A tela decide pelo `motivo`, nunca pela frase — frase muda, marcador não. A conferência vive no filtro JWT (antes do MVC, por isso o corpo é montado à mão, como os 401 de B-A8) e **não cobre `/api/auth/**`**, de propósito: a renovação é a rota de fuga — `ambienteParaRenovacao` já trocava em silêncio para um ambiente próprio quando o declarado não pertence mais, e foi escrita prevendo exatamente este caso. O fluxo completo: 403 com marcador → renovação → token num ambiente da pessoa → a tela recarrega nele e explica o que houve.
>
> **Revogar não derruba as sessões dela** (B-D84). Encerrar sessão revoga a *renovação* e não o *acesso*, então o logoff geral nem fecharia a janela — e ainda a deslogaria do ambiente dela, que não tem relação com quem revogou.

### O que o convidado pode e o que não pode

| Pode — é **dinheiro** | Não pode — é **porta** |
|---|---|
| Lançar, editar e excluir lançamento de qualquer um | Convidar outra pessoa |
| Criar, arquivar e desarquivar categoria | Remover o acesso de alguém |
| Criar, encerrar e reabrir conta | Renomear o ambiente |
| Criar cartão, emitir, cancelar, encerrar | Apagar o ambiente |
| Fechar, reabrir e pagar fatura | |
| Transferir entre contas | |

Renomear está do lado da porta porque o nome é **um só** e aparece na lista de todos, inclusive na do dono.

### A regra que aperta o B-D18

**Vincular e desvincular conta de ambiente passa a exigir ser DONO dos dois lados** (B-D78).

B-D18 dizia "só se vincula conta que já se enxerga", e estava certo para a época — enxergar era sinônimo de ser dono. Com compartilhamento, deixa de ser: a convidada poderia levar a conta conjunta para o ambiente pessoal dela e lançar de lá, invisível ao dono.

**É o que fecha o I-23** (B-D79). Com a regra apertada, todo lançamento daquela conta nasce no mesmo ambiente, e as duas pessoas veem o mesmo saldo, a mesma fatura e o mesmo mapa. Não é contorno: o desenho escolhido simplesmente não cria a divergência.

### Códigos de erro desta seção

| Código | Quando |
|---|---|
| `400` | `email` ausente ou malformado |
| `403` | Não é dono e tentou conceder, remover outro, ou renomear; dono tentando sair |
| `404` | Nenhum usuário com aquele e-mail; ambiente que não é seu (B-D25) |
| `409` | Conceder acesso a quem já tem |

---

## 2d. Compartilhamento de CONTA — V16 (29/07/2026)

> Desenho de princípio em `decisoes.md` §4k (B-D85 a B-D91); a execução, discutida antes do código por pedido dele, em B-D92 a B-D97. O cartão neste modo é §4l (B-D98 a B-D103) e sai na V17.

### O outro modo, e a diferença que importa

| | Compartilhar **ambiente** (§2c) | Compartilhar **conta** (aqui) |
|---|---|---|
| Onde a pessoa trabalha | Dentro do ambiente do dono | No ambiente **dela** |
| Categorias | As do dono | As dela |
| Mapa de gastos | Compartilhado | **Separado** |
| Saldo e fatura | Compartilhados | Compartilhados |
| Serve para | "gerimos a casa juntos" | "dividimos uma conta, orçamentos separados" |

A regra que resume tudo: **o saldo atravessa ambientes; a classificação não** (B-D85). Cada um vê todos os movimentos da conta, e só os próprios trazem categoria e descrição.

### `POST /api/contas/{id}/compartilhamentos`
```json
{ "email": "luciana@exemplo.com" }
```

Cria um convite **pendente** — e aqui está a diferença de B-D80: no ambiente o acesso é imediato, na conta há um aceite, porque só ela pode escolher em qual ambiente dela a conta vai aparecer (B-D90).

Só o **dono do ambiente onde a conta nasceu** compartilha (B-D91 + B-D92). Quem recebeu a conta emprestada não a passa adiante, e quem recebeu o *ambiente* também não: repartir acesso é porta.

Responde **201** com a lista de compartilhamentos. **404** para e-mail não cadastrado (B-D81, mesmo oráculo assumido do §2c). **409** quando já existe convite pendente, quando a pessoa já recebeu a conta, e quando a conta está encerrada.

> **Ter acesso ao seu ambiente NÃO impede** (B-D104, corrigido em 29/07/2026). A primeira versão recusava com 409 — *"ela já vê esta conta"* — e era um erro conceitual: confundia **ver** a conta com **ter** a conta. Quem entra no seu ambiente trabalha dentro dele, com as **suas** categorias e no **seu** mapa; a conta dividida aparece no ambiente **dela**, com as categorias dela e no mapa dela. São coisas diferentes, e o §4k abre dizendo que o segundo modo é *complementar* ao primeiro.
>
> As duas concessões são **independentes**: revogar a conta não tira o ambiente, e remover o acesso ao ambiente não tira a conta.

### `GET /api/contas/{id}/compartilhamentos`
```json
{
  "compartilhamentos": [
    { "usuarioId": "0198...", "nome": "Luciana", "email": "...", "situacao": "PENDENTE" },
    { "usuarioId": "0198...", "nome": "Marina",  "email": "...", "situacao": "ATIVO" }
  ]
}
```

**Não existe campo de ambiente aqui, de propósito.** Em qual ambiente dela a conta entrou é organização da vida dela, e B-D90 já recusou expor isso ao dono quando recusou que ele escolhesse.

### `DELETE /api/contas/{id}/compartilhamentos/{usuarioId}`

Um endpoint para três coisas, no idioma de B-D77: o dono **cancela** um convite pendente, o dono **revoga** um acesso ativo, e qualquer um **sai** da conta que recebeu. **204**.

> **Revogar não apaga nada** (B-D93). O vínculo ganha `encerrado_em`: a conta sai da vista dela, e os lançamentos que ela já fez **ficam** — no ambiente dela, com as categorias dela, e continuam somando no saldo do dono. Não é leniência: aquele dinheiro saiu da conta de verdade, e apagá-lo faria o saldo divergir do extrato do banco.
>
> O banco, aliás, não deixaria apagar: `fk_lancamento_conta` é `ON DELETE RESTRICT`, então o `DELETE` do vínculo é recusado a partir do primeiro lançamento dela.

### `GET /api/convites`
```json
{
  "convites": [
    { "id": "0198...",
      "conta": { "nome": "Conjunta Itaú", "natureza": "ATIVO" },
      "de":    { "nome": "Abner", "email": "..." } }
  ]
}
```

Os convites de conta pendentes **para mim**. A tela mostra isso onde a pessoa entrar, porque um convite que ninguém vê é um convite que não existe.

### `POST /api/convites/{id}/aceitar`
```json
{ "ambienteId": "0198..." }
```

**O `ambienteId` é obrigatório e é o ponto do aceite** (B-D90). Cair no ambiente ativo mandaria a conta doméstica para o PJ sem aviso, e os gastos iriam para o mapa errado até alguém notar — e notar é difícil, porque nada avisa.

**404** para convite que não é dela, e **404** também para ambiente que não é dela ou onde ela não é dona — pelo idioma de B-D25, que já valia no §2c: ambiente alheio é indistinguível de ambiente inexistente, e distinguir transformaria a API num oráculo sobre quais ids existem. **400** fica para `ambienteId` ausente ou malformado.

Sucesso: **201** com um corpo mínimo, `{ "contaId": "0198...", "ambienteId": "0198..." }` — **não** a conta no formato de `GET /api/contas`. A conta acabou de entrar num ambiente em que a pessoa **não está** (o ativo da sessão pode ser outro), e montar a resposta completa exigiria simular um ambiente que a requisição não está vivendo. A tela troca para o ambiente escolhido e recarrega a lista de contas de lá.

Aceitar dentro de um ambiente que ela **recebeu emprestado** (§2c) é recusado junto: espalharia a conta para o dono daquele ambiente, que não participou de nada disto.

### `DELETE /api/convites/{id}`

Recusa. **204**. O convite recusado **desaparece** (B-D94) — a trilha de quem convidou e quem recusou fica em `registro_auditoria`, não numa coluna de situação que passaria a ser uma segunda fonte da verdade sobre quem tem acesso.

### O que muda em `GET /api/contas`

Cada conta ganha três campos:

```json
{ "origem": true, "compartilhada": true, "recebidaDe": null }
```

- **`origem`** — a conta nasceu neste ambiente. É quem responde "há porta aqui?": renomear, encerrar, mexer nas formas de pagamento e compartilhar só aparecem quando `origem` é `true` (B-D95).
- **`compartilhada`** — existe pelo menos um vínculo ativo fora daqui. Explica na tela por que o saldo é maior do que a soma dos lançamentos visíveis.
- **`recebidaDe`** — `{ "nome": "Abner" }` na conta emprestada, nulo na própria.

O **saldo** dessas contas passa a vir de `app_saldo_da_conta` e atravessa ambientes (B-D87/B-D96): os dois veem o mesmo número, que é o que bate com o extrato do banco.

### `GET /api/contas/{id}/extrato?mes=2026-07`

Endpoint novo, e é onde a conta se confere contra o banco. Ele **atravessa ambientes**; o extrato do mês (§5, `GET /api/lancamentos`) continua sendo o do **ambiente** e não atravessa — são duas perguntas diferentes, e a que bate com o banco é esta.

```json
{
  "lancamentos": [
    { "id": "0198...", "meu": true,
      "data": "2026-07-14", "tipo": "SAIDA", "valor": "89.90",
      "formaPagamento": "PIX", "situacao": "REALIZADO",
      "descricao": "Mercado", "categoria": { "nome": "Alimentação" },
      "quem": { "nome": "Abner" } },

    { "id": "0198...", "meu": false,
      "data": "2026-07-15", "tipo": "SAIDA", "valor": "240.00",
      "formaPagamento": "DEBITO", "situacao": "REALIZADO",
      "quem": { "nome": "Luciana" } }
  ]
}
```

**A linha alheia não tem `descricao` nem `categoria`** (B-D89), e não é a tela que os omite: `app_extrato_da_conta` não devolve essas colunas do lançamento de outro ambiente (B-D97). O que a tela nunca recebeu, ela não vaza.

Descrição fica de fora junto com a categoria pelo mesmo motivo prático: é texto livre, e é onde as pessoas escrevem o que não pretendiam dividir — *"presente da Luciana"* é exatamente o caso.

> **A consequência de privacidade, dita em voz alta** (B-D88): cada um passa a saber que um valor se moveu sem ver no quê. É o próprio ponto de uma conta dividida, mas é escolha, não detalhe técnico.

### O que ela pode e o que não pode, na conta emprestada

| Pode | Não pode |
|---|---|
| Lançar, editar e excluir **os próprios** lançamentos | Renomear a conta |
| Ver saldo e extrato completos da conta | Encerrar ou reabrir a conta |
| Transferir de e para a conta | Mexer nas formas de pagamento aceitas |
| Pagar fatura, se for cartão (§4l, V17) | Compartilhar a conta com um terceiro |
| Sair da conta a qualquer momento | Desvincular a conta do ambiente do dono |

As duas últimas da coluna direita eram possíveis no banco depois da V15, e é o Achado 1 de §4k: sem a coluna `origem`, "conta própria" passava a incluir a conta emprestada, e o RLS entregava à convidada o poder de fazer a conta desaparecer do ambiente de quem a criou.

### Códigos de erro desta seção

| Código | Quando |
|---|---|
| `400` | `email` ausente ou malformado; `ambienteId` ausente |
| `403` | Não é dono da conta e tentou compartilhar, revogar, renomear, encerrar ou mexer nas formas |
| `404` | E-mail não cadastrado; conta que não aparece no seu ambiente (B-D25); convite que não é seu; ambiente de destino que não é seu, ou onde você não é dono |
| `409` | Convite pendente repetido; pessoa que já recebeu a conta; conta encerrada |

---

## 2e. Cartão dividido POR PLÁSTICO — V19 (30/07/2026)

> Decisões em `decisoes.md` §4l (B-D98 a B-D103) e **§4n (B-D106 a B-D110), que revoga três delas**. Esta seção foi reescrita: a V17 fez a unidade ser a conta do contrato, e o uso mostrou que estava errado.

### A unidade é o plástico, nunca o contrato

Descrição dele: *"tenho um contrato com limite total de 30 mil; crio um adicional em nome da Luciana que eu posso opcionalmente dizer que tem 1.000 dentro dos meus 30.000. A questão do compartilhamento é poder dar para ela, lá no meio de pagamento, a possibilidade de apontar este cartão adicional que está em nome dela porém **dentro da minha fatura**."*

```
GET    /api/cartoes/{cartaoId}/emitidos/{emitidoId}/compartilhamentos
POST   /api/cartoes/{cartaoId}/emitidos/{emitidoId}/compartilhamentos      { "email": "..." }
DELETE /api/cartoes/{cartaoId}/emitidos/{emitidoId}/compartilhamentos/{usuarioId}
```

**Dividir a CONTA de um cartão responde 403** (B-D106), com a frase apontando este caminho. Era o que a V17 permitia, e entregava os dez plásticos de uma vez.

O aceite é o de sempre (B-D90): `POST /api/convites/{id}/aceitar` com o `ambienteId` escolhido por quem recebe. O convite diz **o que** está sendo oferecido:

```json
{ "convites": [ {
    "id": "0198...",
    "conta": { "id": "0198...", "nome": "Black", "natureza": "PASSIVO" },
    "de": { "nome": "Abner", "email": "..." },
    "plastico": { "id": "0198...", "titular": "Assinaturas",
                  "tipo": "VIRTUAL", "finalDoCartao": "5678" }
} ] }
```

`plastico` nulo = convite de **conta** (§2d); preenchido = convite de **plástico**. Sem essa distinção, ela aceitaria pensando ter recebido a conta do cartão inteira.

**Plástico emitido depois NÃO vai junto** — é o custo de a unidade ser explícita, e tem de ser dividido à parte.

### O que muda em `GET /api/cartoes`

Cada emitido ganha dois números, e eles servem aos dois lados:

```json
{ "emitidos": [
    { "id": "...", "nomeTitular": "Assinaturas", "tipo": "VIRTUAL", "finalDoCartao": "5678",
      "limiteProprio": "1000.00", "limiteEfetivo": "1000.00", "consumido": "150.00" },
    { "id": "...", "nomeTitular": "Abner", "tipo": "FISICO", "finalDoCartao": "1234",
      "limiteProprio": null, "limiteEfetivo": "30000.00", "consumido": "900.00" }
] }
```

- **`limiteEfetivo`** é o "1.000 dentro dos 30.000": o limite próprio quando existe, o do contrato quando não (B-D110).
- **`consumido`** é o que **aquele** plástico gastou, com as parcelas futuras (F23). Do lado de quem abriu o contrato, é o que transforma a fatura nas *mini faturas* do e-mail do banco.

**A lista de `emitidos` de um cartão recebido traz só os plásticos que você recebeu** — e o recorte é da política, não da tela.

### O que ela vê da fatura

```json
{ "total": "150.00", "escopoDoTotal": "MEUS_PLASTICOS" }
```

`escopoDoTotal` é um marcador estável, no espírito de `SEM_ACESSO_AO_AMBIENTE`:

- **`CONTRATO`** para quem abriu o cartão — o valor único que o banco cobra, somando os dez plásticos e atravessando ambientes (B-D87);
- **`MEUS_PLASTICOS`** para quem recebeu um plástico: soma apenas o que ele consumiu.

Com `MEUS_PLASTICOS`, **`pago`, `aPagar` e `quitacao` não são exibidos** pela tela — quem paga a fatura é o dono do contrato (B-D107), e estado de pagamento não faz sentido para quem não paga.

### `GET /api/faturas/{id}/lancamentos`

O extrato **recorta por plástico**, e o filtro deriva da concessão — não é parâmetro:

- quem abriu o contrato vê tudo (as mini faturas);
- quem recebeu plásticos vê as linhas **daqueles** plásticos, e mais nenhuma.

Na linha do outro, no plástico que os dois usam: **valor, data, o plástico, a parcela e quem** — sem `descricao` e sem `categoria`. B-D89 foi **confirmado** aqui (B-D109): ele considerou reverter, e manteve. O que fica privado, na frase dele, é *"a categoria, pois isso é de cada um"*.

`parcelaNumero`/`parcelaTotal` continuam visíveis (B-D102): as próximas parcelas são dinheiro do dono do contrato preso no limite dele.

### Comprar no plástico dividido

`POST /api/lancamentos` com `cartaoEmitidoId` do plástico recebido. O `contaId` enviado é a **conta do cartão** — o banco do contrato é uma conta de outra pessoa, invisível para quem recebeu, e mandá-lo responderia 403.

A conferência "o cartão pertence a esta conta" **não se aplica** ao cartão dividido: no caminho normal ela impede que escolher Nubank e um cartão do C6 grave a compra no C6 em silêncio; aqui a pergunta *"de qual banco?"* não tem resposta possível do lado de quem recebeu. A checagem não afrouxou — ela deixou de existir num contexto em que não significa nada.

### Quem pode o quê

| Pode — é **dinheiro** | Não pode — é do **contrato** |
|---|---|
| Comprar no plástico recebido | Comprar nos outros plásticos (não os vê) |
| Ver o extrato daquele plástico, de todos | Ver o total da fatura, o pago e o a pagar |
| Ver o limite e o consumido do plástico | Pagar a fatura |
| Sair do plástico a qualquer momento | Fechar ou reabrir a fatura |
| | Emitir plástico, mudar limite, encerrar o cartão |
| | Repassar o plástico a um terceiro |

**Cada um pagar uma parte é conversa adiada**, não descartada — palavras dele: *"mais pra frente a gente monta uma opção de cada um pagar uma parte e até mesmo ela pagar uma fatura inteira"*.

### Dividir a conta bancária não divide os cartões

E não é regra escrita: **cai da estrutura**. O cartão é uma conta própria (B-D47), e as políticas de `cartao`, `cartao_emitido` e `fatura` olham a conta **do cartão**, nunca a `conta_banco_id`. Quem recebe a conta bancária ganha saldo, extrato e formas de pagamento — e nada de crédito.

### Códigos de erro desta seção

| Código | Quando |
|---|---|
| `403` | Dividir a **conta** de um cartão (divida o plástico); repassar plástico recebido; emitir, alterar, encerrar o cartão; fechar, reabrir ou **pagar** a fatura de cartão recebido |
| `404` | Cartão, plástico ou fatura fora do seu alcance (B-D25); revogar quem não usa o plástico |
| `409` | Convite de plástico repetido; pessoa que já usa o plástico |

---

## 7. Fora deste contrato

**Recorrência** (F24/F25) ficou fora da V12 por B-D56: não é feature de cartão, e entra depois valendo para conta e cartão ao mesmo tempo. Cartão, emitido e fatura têm o desenho fechado em §6b, ainda sem código.
