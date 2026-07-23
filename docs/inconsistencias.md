# RaspyBank — Inconsistências e Pendências Conhecidas

**Versão:** 1.0
**Data:** 23 de julho de 2026
**Regra deste documento:** ambiguidade registrada é decisão adiada conscientemente; ambiguidade solta reaparece como bug. Cada item tem um "quando resolver" — nenhum é urgente por definição, senão já teria sido resolvido.
**Ao resolver um item:** mover a decisão para `decisoes.md` e marcar aqui como resolvido com a referência, sem apagar.

---

## I-01 — `ambiente.status` × `ambiente.excluido_em`: dois mecanismos de ciclo de vida

A tabela `ambiente` tem `status` (`ATIVO/INATIVO`) **e** `excluido_em` (timestamp nullable), e `app_ambientes_do_usuario()` filtra pela exclusão lógica. Pergunta sem dono: **o que `INATIVO` significa que `excluido_em` não significa?**

Leituras possíveis: (a) INATIVO = pausado/arquivado reversível, excluído = terminal — dois estados legítimos; (b) `status` é vestígio e nunca é gravado por ninguém — peso morto; (c) coexistem sem regra — o pior caso, cada query escolhendo um critério.

**Risco se ignorado:** "ambiente excluído aparecendo em relatório" ou o inverso, com cada tela filtrando de um jeito.
**Quando resolver:** antes da V10 se `conta_ambiente` referenciar o ciclo de vida do ambiente; senão, antes do CRUD de ambiente.

## I-02 — `primeiroAmbienteDe` pode devolver `null` e o login segue

`AutenticacaoControlador.primeiroAmbienteDe()` devolve `null` para usuário sem ambiente, e o fluxo emite `jwt.emitirAcesso(usuarioId, null)` sem reclamar. Hoje impossível na prática (A12: cadastro cria o primeiro ambiente atomicamente), mas se acontecer, o sintoma será um token esquisito em vez de um erro claro.

**Quando resolver:** barato — um guard clause com 500/409 explícito. Qualquer sessão que tocar o controlador.

## I-03 — `/renovar` não gera auditoria

Login gera `ACESSO`; a renovação de token — que estende a sessão por mais 30 dias — passa em silêncio. Defensável (a trilha encheria a cada 15 min de uso ativo), mas hoje é **acidental**, não deliberado.

**Decidir:** (a) não auditar renovação, registrado como deliberado; (b) auditar só a revogação de família (o evento de segurança relevante); (c) auditar tudo. A opção (b) parece o melhor custo/benefício — reuso de token detectado é exatamente o que se quer na trilha.
**Quando resolver:** próxima sessão que tocar autenticação.

## I-04 — `Canal.WEB` fixo nos chamadores

`AutenticacaoControlador` e `OnboardingServico` passam `Canal.WEB` hardcoded. Verdade hoje (só existe web), mentira no dia em que o bot Telegram chegar — cadastro via Telegram seria auditado como WEB. O `ContextoRequisicao` já sabe o canal (o `PerfilControlador` lê de lá).

**Quando resolver:** no início do trabalho do bot Telegram, como primeiro item. Não generalizar antes — YAGNI.

## I-05 — Auditoria: gatilho (F26) × serviço (requisitos) — convivência a validar

Os requisitos fecharam auditoria escrita pela camada de serviço (para capturar canal); a Fase 2 fechou F26 com gatilho lendo contexto RLS para as tabelas de domínio. A leitura harmonizada (registrada em `decisoes.md`): gatilho para domínio, serviço para autenticação. Mas essa convivência ainda não foi exercitada — a V10 será o teste. Se o gatilho não conseguir capturar canal de forma satisfatória, revisar formalmente qual modelo vence.

**Quando resolver:** durante o desenho da V10.

## I-06 — Argon2id (requisitos) × BCrypt 12 (implementado)

O documento de requisitos especifica Argon2id para hash de senha; a implementação da Fase 4 usa BCrypt strength 12 (`PasswordEncoder` do Spring). BCrypt 12 é plenamente adequado para o caso; Argon2id é o estado da arte com resistência a GPU. Não é urgência de segurança — é **divergência doc × código** que precisa de veredito: ou os requisitos passam a dizer BCrypt, ou se planeja a migração (o Spring suporta `DelegatingPasswordEncoder` com upgrade transparente no próximo login).

**Quando resolver:** qualquer momento antes da publicação para terceiros. Para uso familiar, BCrypt 12 registrado como decisão basta.

## I-07 — Estados da Fatura (pendência herdada dos requisitos)

Confirmar o conjunto derivável: Aberta, Fechada, Paga, Vencida — lembrando F19: **não existe coluna de status**; tudo deriva de `fechada_em` + somas de pagamento + vencimento.
**Quando resolver:** no desenho da V10 (tabela `fatura`).

## I-08 — Entrada de usuário em ambiente existente (pendência herdada)

Convite por e-mail, código, ou adição manual. Fluxo pequeno, mas toca segurança (quem pode adicionar quem).
**Quando resolver:** depois da V10, antes de qualquer uso real compartilhado.

## I-09 — Dashboard (pendência herdada)

Decisão de produto, não de modelo. Vive no futuro Mapa de Telas.

## I-10 — Sem testes automatizados além do ArchUnit

Risco transversal apontado na avaliação de 23/07. O plano é o **Bloco C**: (1) Testcontainers com Postgres real para migrações + RLS (incluindo o teste de fumaça: dois usuários, cada um cego para os dados do outro); (2) regras de domínio como classes puras testáveis sem Spring — decisão de desenho a tomar ANTES do primeiro serviço de conta; (3) Spring Boot Test só nos fluxos de autenticação.
**Quando resolver:** Bloco C, antes da V10 ser consumida por serviços.
