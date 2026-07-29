# RaspyBank — Inventário de Funções SECURITY DEFINER

**Versão:** 1.1
**Data:** 26 de julho de 2026
**Regra deste documento:** toda função `SECURITY DEFINER` é um furo **controlado** na política de Row Level Security. Cada uma existe por um motivo específico, registrado aqui. Criar uma nova exige adicionar a entrada correspondente no mesmo commit — função sem entrada neste inventário é dívida.

---

## Por que essas funções existem

O padrão que as originou repetiu-se três vezes durante a construção do login: **toda leitura ou escrita que acontece antes de a sessão ter identidade** (`raspybank.usuario_id` via `set_config`) colide com as políticas de RLS. Cadastro, busca de credenciais, criação do primeiro ambiente e auditoria de autenticação acontecem todos nesse limbo pré-identidade.

A solução escolhida: funções `SECURITY DEFINER` (executam com privilégios do dono, atravessando o RLS) cobrindo **exclusivamente a superfície pré-autenticação**, cada uma com escopo mínimo e `SET search_path` fixado. A alternativa — desligar RLS nessas tabelas ou dar privilégios ao usuário de aplicação — seria um furo muito maior.

**Critério para criar uma nova (reescrito em 26/07/2026):** só se a operação enfrentar um **impasse estrutural com a política** — isto é, se a linha a ser criada só se tornar visível por um vínculo que ainda não pode existir no momento da escrita. Fora disso, operação de domínio NÃO passa por SECURITY DEFINER: ela acontece com identidade estabelecida e o RLS é exatamente quem deve julgá-la.

> **Por que o critério mudou.** A formulação anterior dizia "operação de domínio (conta, lançamento, cartão) NUNCA passa por SECURITY DEFINER". A V10 encontrou o primeiro contraexemplo legítimo: criar uma `conta` é operação de domínio, com identidade estabelecida — e ainda assim impossível sob a política, porque a visibilidade da conta vem de `conta_ambiente`, que só pode existir depois da conta. É o mesmo impasse da V5, numa tabela de negócio.
>
> O critério antigo dividia pela **camada** (autenticação sim, domínio não), que era só onde o problema tinha aparecido até então. O novo divide pela **causa**, que é o que realmente justifica o furo. A regra ficou mais restritiva na prática: "é de domínio" nunca foi argumento, e "é pré-identidade" também deixou de ser — o que vale é demonstrar o impasse.

**Gatilhos são um caso à parte.** Função de gatilho que escreve em tabela com RLS precisa de DEFINER por uma razão diferente e mais simples: ela roda em nome de quem disparou a operação, e o registro que ela grava (auditoria, outbox) precisa entrar mesmo quando o autor não tem — ou não é — identidade válida. Uma auditoria que a política recusa é o pior resultado possível: a operação suspeita passa e o registro dela não.

---

## Funções de contexto (V3) — leem a identidade da sessão

### `app_usuario_id()` — **não é SECURITY DEFINER**
Devolve o UUID do usuário da sessão corrente, lido de `raspybank.usuario_id`. É a base de todas as políticas — mas roda com privilégios de quem chama, porque `current_setting` é legível por qualquer papel e a função não toca tabela nenhuma. Não há RLS a atravessar, logo não há motivo para DEFINER.

> **Correção de 23/07/2026 (primeira captura do Bloco C):** este documento afirmava que a função era SECURITY DEFINER "para evitar recursão de permissão". O teste `MigracoesTest.inventarioSecurityDefinerConfere` — que confere este inventário contra `pg_proc` — revelou na primeira execução que a V3 nunca a criou assim. O banco estava certo; o documento, não. Fica registrado como lembrete do motivo de o inventário ser verificado por teste e não por leitura.

### `app_ambientes_do_usuario()`
Devolve o conjunto de ambientes vinculados ao usuário da sessão (consulta `usuario_ambiente`). Usada nas políticas por subquery — consequência da decisão R7 (tenant = usuário). **Corrigida na V8** para filtrar ambientes logicamente excluídos.

### `app_contas_do_usuario()` — V10
Devolve as contas visíveis ao usuário da sessão, atravessando `conta_ambiente`. Mesma razão de DEFINER da irmã acima: a política de `conta` precisa consultar `conta_ambiente`, que também tem política — sem DEFINER, avaliar a política exigiria avaliar a política.

Ela aparece em dois lugares, e o segundo é o que importa para a segurança: além de decidir o que se lê em `conta`, ela é a **segunda condição** do `WITH CHECK` de `conta_ambiente`. Sem essa condição, qualquer usuário poderia vincular a conta de outra pessoa ao próprio ambiente e passar a enxergá-la inteira — bastaria ter o UUID. Conferir só o `ambiente_id` protege um lado do vínculo e deixa o outro aberto.

## Funções de autenticação (V4–V7) — operam no limbo pré-identidade

### `auth_cadastrar_usuario(nome, email, hash)` — V4
Grava usuário completo, incluindo `senha_hash`. Existe por dois motivos que se somam: (1) no cadastro não há identidade na sessão, o RLS recusaria o INSERT; (2) desde a V8 o usuário de aplicação não tem privilégio sequer de leitura em `senha_hash` — esta função é o único caminho de escrita do hash.

### `auth_buscar_credenciais(email)` — V4
Devolve `id, senha_hash, status` para o e-mail. Único caminho de **leitura** do hash (mesma revogação da V8). Chamada no login, antes de haver identidade. O consumidor Java aplica timing-equalization quando o e-mail não existe.

### `auth_criar_ambiente_inicial(...)` — V5
Cria o primeiro ambiente e o vínculo no ato do cadastro, atomicamente com a criação do usuário (decisão A12). Pré-identidade: no instante da chamada, o usuário recém-criado ainda não é a identidade da sessão.

### `auth_registrar_evento(usuario_id, canal, operacao, detalhe)` — V6
Grava auditoria de autenticação. Pré-identidade pelo mesmo motivo do cadastro; a política de `registro_auditoria` recusaria o INSERT.

**⚠ Achado do Bloco A (23/07), importante:** apesar do nome genérico, esta função é **específica de autenticação**. O corpo fixa `entidade = 'Autenticacao'` e `ambiente_id = NULL`. Ela **não deve crescer** para servir auditoria de domínio: quando conta/lançamento precisarem auditar, o caminho é outro (escrita com identidade estabelecida, `ambiente_id` preenchido, passando pelo RLS normal — via serviço/gatilho conforme F26). Se alguém for tentado a "só adicionar um parâmetro entidade" aqui, este parágrafo existe para impedir.

### `auth_ambientes_do_usuario(usuario_id)` — V7
Lista ambientes de um usuário **por parâmetro** (não pela sessão). Usada no login para escolher o ambiente que entra no JWT — momento em que a identidade ainda não foi estabelecida na sessão. Não confundir com `app_ambientes_do_usuario()` (sem parâmetro, lê a sessão): nomes parecidos, momentos opostos do ciclo de vida.

## Funções de domínio (V10)

### `app_criar_conta(ambiente_id, nome, natureza)` — V10
**A primeira exceção ao critério antigo**, e a razão de ele ter sido reescrito. Cria `conta` e o vínculo em `conta_ambiente` na mesma transação.

O impasse: a política de `conta` pergunta a `app_contas_do_usuario()` se a conta é visível, e para uma conta que está nascendo a resposta é sempre não — o vínculo só pode existir depois da conta, e a conta só entra se o vínculo já existisse. Nenhuma ordem de INSERT resolve, porque o `WITH CHECK` é avaliado na hora.

Ela **confere o vínculo do usuário com o ambiente** antes de gravar, e essa checagem não é decorativa: SECURITY DEFINER ignora políticas, então quem escreve uma função dessas assume a responsabilidade que o banco deixou de ter. Sem a checagem, a função aceitaria qualquer ambiente e seria exatamente o buraco que o RLS existe para fechar.

### `app_criar_ambiente(nome)` — V14
**A terceira exceção**, e do mesmo formato das outras duas. Cria `ambiente`, o vínculo em `usuario_ambiente` e as sistêmicas, na mesma transação.

O impasse é o de sempre: `pol_ambiente_vinculado` pergunta a `app_ambientes_do_usuario()` se o ambiente está entre os do usuário, e para um que está nascendo a resposta é sempre não. Nenhuma ordem de INSERT resolve, porque o `WITH CHECK` é avaliado na hora do INSERT em `ambiente`.

**A identidade vem da sessão, não de parâmetro**, e a diferença é de segurança: com o usuário como argumento, esta função viraria "crie um ambiente para fulano" e qualquer chamador poderia criar ambiente no nome de outro. É a mesma disciplina que `app_criar_conta` aplica ao conferir o vínculo antes de gravar — DEFINER ignora políticas, então quem escreve a função assume a responsabilidade que o banco deixou de ter.

**O que ela não faz:** não apaga, não renomeia, não convida. Porta estreita que cresce deixa de ser estreita.

Nasceu de um beco que o uso revelou: o seletor de ambiente da casca ficava desabilitado com um ambiente só, e não havia como criar o segundo.

### `fn_criar_categorias_sistemicas(ambiente_id)` — V10 — **não é SECURITY DEFINER**
Insere as três sistêmicas de B-D13. Não precisa de DEFINER porque nunca é chamada sozinha por usuário: roda dentro de `auth_criar_ambiente_inicial` (que já é DEFINER) ou como proprietário, na retroalimentação da própria migração. Listada aqui para que a ausência do modificador seja deliberada e não descuido.

## Funções de gatilho (V10)

Caso à parte, pela razão explicada no critério: o registro que elas gravam precisa entrar mesmo quando o autor não tem identidade válida.

### `fn_auditar()` — V10
Auditoria de domínio por gatilho (F26), lendo usuário **e canal** (B-D6) do contexto do RLS. DEFINER porque `registro_auditoria` tem política e o autor pode ser legitimamente nulo — é assim que alteração feita fora da aplicação se denuncia. Sem DEFINER, a política recusaria justamente o registro que mais interessa.

### `fn_publicar_evento_lancamento()` — V10
Grava no `outbox` na mesma transação do lançamento (F28). DEFINER pela mesma razão.

---

## Resumo

| Função | Migração | Momento | Escopo |
|---|---|---|---|
| `app_usuario_id()` — *não é DEFINER* | V3 | Com sessão | Ler identidade |
| `app_ambientes_do_usuario()` | V3 (V8) | Com sessão | Visibilidade das políticas |
| `app_contas_do_usuario()` | V10 | Com sessão | Visibilidade de conta + trava do vínculo |
| `auth_cadastrar_usuario` | V4 | Pré-identidade | Única escrita de `senha_hash` |
| `auth_buscar_credenciais` | V4 | Pré-identidade | Única leitura de `senha_hash` |
| `auth_criar_ambiente_inicial` | V5 (V10) | Pré-identidade | Ambiente inicial + sistêmicas, atômico |
| `auth_registrar_evento` | V6 | Pré-identidade | Auditoria de autenticação, SÓ ela |
| `auth_ambientes_do_usuario(uuid)` | V7 | Pré-identidade | Ambiente para o JWT |
| `app_criar_conta` | V10 | Com sessão | Impasse de vínculo: conta + `conta_ambiente` |
| `app_criar_ambiente` | V14 | Com sessão | Impasse de vínculo: ambiente + `usuario_ambiente` + sistêmicas |
| `fn_criar_categorias_sistemicas` — *não é DEFINER* | V10 | Interno | As sistêmicas de B-D13 |
| `fn_auditar` | V10 | Gatilho | Auditoria de domínio com canal |
| `fn_publicar_evento_lancamento` | V10 | Gatilho | Outbox do lançamento |
