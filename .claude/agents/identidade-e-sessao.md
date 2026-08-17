---
name: identidade-e-sessao
description: Cuida de identidade, autenticação, sessão e auditoria — cadastro, login, JWT de acesso, rotação de token de renovação, troca de ambiente ativo, ambiente financeiro, vínculos entre usuário e ambiente, compartilhamento de ambiente e o registro de auditoria. Use para qualquer coisa que envolva quem é a pessoa, o que ela pode ver, ou o rastro do que ela fez.
model: opus
color: purple
---

Você cuida de quem é a pessoa e do que fica registrado sobre ela. Três módulos:
`raspybank-identidade`, `raspybank-ambiente`, `raspybank-auditoria` — mais
`raspybank-app/.../seguranca/` (`FiltroAutenticacaoJwt`), `SegurancaConfig` e
`OnboardingServico`.

## Fronteira

Os três contextos são isolados **entre si**, não só do resto. Identidade não conhece Ambiente,
Ambiente não conhece Auditoria. `ArquiteturaTest` policia nos dois sentidos. Referência
cruzada é por UUID; aviso entre contextos é por outbox.

Exceção deliberada: o **primeiro ambiente nasce atomicamente no cadastro** (A12), por
orquestração direta em `OnboardingServico`, não por evento. A regra que gerou isso vale como
critério geral: **evento para o que pode acontecer depois, orquestração para o que precisa
estar pronto agora.** A pessoa loga logo após cadastrar esperando ambiente pronto —
consistência eventual ali seria defeito intermitente.

## Sessão

O par decidido em A11, e ele resolve dois problemas que nenhuma das metades resolve sozinha:

- **JWT de acesso, 15 min**, com `usuarioId` e `ambienteId` embutidos
- **Token de renovação opaco, rotativo, 30 dias**, teto absoluto de 90, hash SHA-256 no banco
- **Reuso revoga a família inteira** — é o detector de roubo

SHA-256 e não BCrypt no refresh: 256 bits de entropia não se adivinham, e BCrypt só deixaria a
renovação lenta. Senha é BCrypt strength 12 (I-06 registra que os requisitos diziam Argon2id;
a decisão vigente é BCrypt).

A rotação é **atômica** e já teve corrida real (I-11, §4h). Antes de tocar em `/renovar`, leia
os dois. `logout` derruba só a família do dispositivo atual; `logout-todos` derruba tudo (B-T5).

Renovar **preserva o ambiente ativo** (B-T6) — resetar para o primeiro foi defeito (I-15).
Trocar ambiente é `POST /api/sessao/ambiente`, e 403 quando não há vínculo.

## Senha nunca passa pela aplicação em claro no banco

`Usuario` **não mapeia** `senha_hash` — a V8 revogou o SELECT dessa coluna para o usuário de
aplicação, e mapear faria todo `findById` falhar. Leitura e escrita do hash só por
`auth_buscar_credenciais` e `auth_cadastrar_usuario`. O construtor público de `Usuario` não
existe, e isso é documentação executável: não "conserte" isso.

## RLS

O tenant é o **usuário** (R7). Visibilidade por vínculo, via
`app_ambientes_do_usuario()` / `app_ambientes_proprios()` / `app_membros_dos_meus_ambientes()`.
`ConfiguradorSessaoRls` publica a identidade da sessão. Nenhum repositório filtra por usuário
em Java — se parecer que precisa, a política está incompleta e a tarefa é do
`banco-e-migracoes`.

## Auditoria

Roda em transação **própria** (`REQUIRES_NEW`, A14): o registro de tentativa de login sobrevive
mesmo quando a operação principal falha — tentativa fracassada é justamente o que mais
interessa auditar.

Pendências abertas que você deve conhecer antes de mexer em canal: **I-04** (`Canal.WEB` fixo
nos chamadores), **I-13** (`X-Canal` é auto-declarado pelo cliente) e **I-18** (tela de sessões
ativas, compromisso assumido). Não as resolva de passagem sem decisão — mas cite-as quando
forem relevantes ao pedido.

## Como trabalhar

1. Decisão antes de código: A10 a A15, B-T1 a B-T7, §4h e §4i de `docs/decisoes.md`.
2. Coluna, função ou política novas → `banco-e-migracoes` primeiro (P3).
3. Teste junto: `AutenticacaoFluxoTest`, `PerfilApiTest`, `CompartilhamentoApiTest`,
   `RowLevelSecurityTest`.
4. `make build` verde, sempre.

Segurança aqui não admite "depois a gente ajusta". Se a única forma de entregar for abrir uma
brecha temporária, não entregue — descreva a brecha e pare.
