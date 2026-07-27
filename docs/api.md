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
| `GET` | `/api/perfil` | Usuário, ambiente atual, canal, ambientes visíveis (filtrados por RLS) |
| `POST` | `/api/sessao/ambiente` | Troca o ambiente ativo. **403** se não houver vínculo (B-T7) |

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
      "ambientes": [ { "id": "0198...", "nome": "Casa" } ]
    }
  ]
}
```

`saldo` é **sempre calculado** (P1/F2), nunca uma coluna. `ambientes` mostra em quais ambientes a conta é visível — é o que torna B-D2 compreensível para quem olha a tela: dá para ver que a conta é compartilhada e entender por que o gasto foi para o ambiente ativo.

> **Dois saldos, não um — mudança de 26/07/2026 (B-D26).** O contrato previa um `saldo` só. Ao implementar, ficou claro que ele repetiria o defeito que B-D10 evitou no mapa: somar o que já aconteceu com o que está agendado faz o número significar duas coisas ao mesmo tempo, e a tela não teria como separar depois. `saldo` é o dinheiro que está lá (só `REALIZADO`); `saldoComPrevistos` inclui o que já está agendado. É o `saldo` que precisa ser zero para encerrar.

> **Limite conhecido (I-23).** As somas alcançam apenas os lançamentos que o RLS libera — os dos ambientes a que a pessoa pertence. Numa conta conjunta visível também no ambiente pessoal do outro, cada um vê um total diferente. Não foi corrigido de propósito: a correção exigiria uma função `SECURITY DEFINER` nova, e o critério B-D19 só a autoriza diante de impasse estrutural com a política — este é escolha de visibilidade, não impasse. Só passa a doer quando existir convite de usuário (I-08), que ainda não existe.

### `POST /api/contas`
```json
{ "nome": "Poupança", "natureza": "ATIVO", "saldoInicial": "3000.00" }
```
`saldoInicial` é **opcional e não é campo da conta**: quando presente, o servidor cria um lançamento na categoria sistêmica `AJUSTE` (A13/B-D13). A tela deixa isso visível em vez de fingir que existe um campo mágico — o saldo continua sendo só a soma dos lançamentos.

`saldoInicial` é validado como decimal de até duas casas **antes** de chegar ao banco: `numeric(15,2)` arredondaria `10.005` para `10.01` em silêncio. Aceita negativo — conta `PASSIVO` ou corrente no vermelho começam devendo, e o sinal escolhe o sentido do lançamento (o `valor` gravado segue positivo, F1).

### `PUT /api/contas/{id}` · `POST /api/contas/{id}/encerrar` · `POST /api/contas/{id}/reabrir`
Conta não se exclui, se encerra (F7). Encerrada, some dos seletores e mantém o histórico. **409** ao encerrar conta com saldo diferente de zero — dinheiro não evapora; é preciso transferir ou ajustar antes. A checagem olha o `saldo` (realizado): previsto é agenda, não dinheiro.

> `reabrir` foi **acrescentado ao contrato em 26/07/2026**, pelo mesmo motivo do `desarquivar` de subcategoria. Encerrar é a alternativa *reversível* à exclusão; sem a volta, um clique errado tiraria a conta dos seletores para sempre, e o único contorno seria criar outra — partindo o histórico em duas.

As escritas respondem na **mesma forma** do `GET`, com saldo e vínculos recalculados. Devolver uma forma reduzida obrigaria a tela a ter dois caminhos de leitura para o mesmo objeto, e o segundo é sempre o que fica desatualizado.

### Códigos de erro desta seção

| Código | Quando |
|---|---|
| `400` | `nome` vazio, `natureza` fora de `ATIVO`/`PASSIVO`, `saldoInicial` malformado |
| `403` | Sessão sem ambiente ativo |
| `404` | Id inexistente ou de conta não vinculada ao ambiente ativo (B-D21) |
| `409` | Encerrar conta com saldo diferente de zero |

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
  "valor": "380.00", "dataCaixa": "2026-07-12", "descricao": "Mercado do mês"
}
```

Seis campos, e três ausências que são decisão, não esquecimento:

- **sem `ambienteId`** — vem da sessão (B-D2);
- **sem `situacao`** — deriva de `dataCaixa`: passado ou hoje → `REALIZADO`, futuro → `PREVISTO` (B-D9 / R9);
- **sem `tipo`** — deriva de `categoria.tipo` (F12); quando a categoria é `AMBOS`, aí sim o campo `tipo` passa a ser obrigatório no corpo.

`dataCompetencia` é opcional e, ausente, copia `dataCaixa` (F14). **403** se a conta não pertencer ao ambiente ativo — a restrição de B-D2 é conferida no banco, e o 403 é a tradução dela.

### `GET /api/lancamentos/{id}` · `PUT /api/lancamentos/{id}` · `DELETE /api/lancamentos/{id}`
Lançamento é editável e excluível **com auditoria** (F16), e a auditoria é por gatilho lendo o contexto do RLS + o canal (F26 / B-D6). `PUT` aceita `situacao` explícito — a derivação de B-D9 vale na criação; corrigir depois é legítimo e é o que a lista da T-08 oferece. `PUT` é substituição completa: trocar a categoria **zera a subcategoria**, porque ela pertencia à anterior e adivinhar uma equivalente seria inventar dado. `DELETE` responde **204**.

> `GET /api/lancamentos/{id}` foi acrescentado em 26/07/2026: as escritas respondem na mesma forma da lista, e ler de volta depois de gravar é o único jeito de a tela receber a classificação resolvida sem montá-la por conta própria.

### Códigos de erro desta seção

| Código | Quando |
|---|---|
| `400` | `valor` negativo ou com mais de duas casas, campo obrigatório ausente, `mes` malformado |
| `403` | Categoria não aceita o sentido (F12); categoria `AMBOS` sem `tipo`; subcategoria de outra categoria (F11); **conta fora do ambiente ativo** (B-D2) |
| `404` | Lançamento, categoria ou subcategoria inexistente, ou de outro ambiente seu (B-D21) |

O 403 da conta fora do ambiente é a **tradução da chave composta** `(ambiente_id, conta_id) → conta_ambiente`: o banco recusaria de qualquer forma, mas diria apenas que uma restrição falhou. A frase existe para a tela ter o que mostrar.

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

## 7. Fora deste contrato

Cartão, cartão emitido, fatura, parcela e recorrência são **V11**, junto da tela T-06 (B-D1). Quando chegarem, `POST /api/lancamentos` ganha os campos de parcelamento e este documento ganha uma seção — não o contrário.
