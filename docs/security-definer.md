# RaspyBank — Inventário de Funções SECURITY DEFINER

**Versão:** 1.0
**Data:** 23 de julho de 2026
**Regra deste documento:** toda função `SECURITY DEFINER` é um furo **controlado** na política de Row Level Security. Cada uma existe por um motivo específico, registrado aqui. Criar uma nova exige adicionar a entrada correspondente no mesmo commit — função sem entrada neste inventário é dívida.

---

## Por que essas funções existem

O padrão que as originou repetiu-se três vezes durante a construção do login: **toda leitura ou escrita que acontece antes de a sessão ter identidade** (`raspybank.usuario_id` via `set_config`) colide com as políticas de RLS. Cadastro, busca de credenciais, criação do primeiro ambiente e auditoria de autenticação acontecem todos nesse limbo pré-identidade.

A solução escolhida: funções `SECURITY DEFINER` (executam com privilégios do dono, atravessando o RLS) cobrindo **exclusivamente a superfície pré-autenticação**, cada uma com escopo mínimo e `SET search_path` fixado. A alternativa — desligar RLS nessas tabelas ou dar privilégios ao usuário de aplicação — seria um furo muito maior.

**Critério para criar uma nova:** só se a operação for genuinamente pré-identidade ou de infraestrutura de sessão. Operação de domínio (conta, lançamento, cartão) NUNCA passa por SECURITY DEFINER — ela acontece com identidade estabelecida e o RLS é exatamente quem deve julgá-la.

---

## Funções de contexto (V3) — leem a identidade da sessão

### `app_usuario_id()`
Devolve o UUID do usuário da sessão corrente, lido de `raspybank.usuario_id`. É a base de todas as políticas. SECURITY DEFINER para poder ser chamada de dentro das políticas sem recursão de permissão.

### `app_ambientes_do_usuario()`
Devolve o conjunto de ambientes vinculados ao usuário da sessão (consulta `usuario_ambiente`). Usada nas políticas por subquery — consequência da decisão R7 (tenant = usuário). **Corrigida na V8** para filtrar ambientes logicamente excluídos.

> Futura irmã: `app_contas_do_usuario()` (Fase 2, decisão do modelo de compartilhamento) entrará com a V10+ quando as políticas de `conta`/`lancamento` existirem. Registrar aqui quando nascer.

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

---

## Resumo

| Função | Migração | Momento | Escopo |
|---|---|---|---|
| `app_usuario_id()` | V3 | Com sessão | Ler identidade |
| `app_ambientes_do_usuario()` | V3 (V8) | Com sessão | Visibilidade das políticas |
| `auth_cadastrar_usuario` | V4 | Pré-identidade | Única escrita de `senha_hash` |
| `auth_buscar_credenciais` | V4 | Pré-identidade | Única leitura de `senha_hash` |
| `auth_criar_ambiente_inicial` | V5 | Pré-identidade | Ambiente inicial atômico |
| `auth_registrar_evento` | V6 | Pré-identidade | Auditoria de autenticação, SÓ ela |
| `auth_ambientes_do_usuario(uuid)` | V7 | Pré-identidade | Ambiente para o JWT |
