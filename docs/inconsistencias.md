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

## I-02 — `primeiroAmbienteDe` pode devolver `null` e o login segue — **RESOLVIDO em 23/07/2026**

`AutenticacaoControlador.primeiroAmbienteDe()` devolve `null` para usuário sem ambiente, e o fluxo emite `jwt.emitirAcesso(usuarioId, null)` sem reclamar. Hoje impossível na prática (A12: cadastro cria o primeiro ambiente atomicamente), mas se acontecer, o sintoma será um token esquisito em vez de um erro claro.

**Resolução:** guard clause com `IllegalStateException` explícita (→ 500 pelo tratador global). Decisão B-T8 em `decisoes.md`.

## I-03 — `/renovar` não gera auditoria — **RESOLVIDO em 23/07/2026**

Login gera `ACESSO`; a renovação de token — que estende a sessão por mais 30 dias — passa em silêncio. Defensável (a trilha encheria a cada 15 min de uso ativo), mas hoje é **acidental**, não deliberado.

**Resolução:** opção (b), como sugerido. Renovação normal não audita (agora deliberado); reuso detectado audita `ACESSO` com `{"evento":"reuso_token_renovacao"}`. O resultado `sealed` do serviço obriga o controlador a tratar o caso. Decisão B-T4 em `decisoes.md`.

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

## I-10 — Sem testes automatizados além do ArchUnit — **RESOLVIDO (esqueleto) em 23/07/2026**

Risco transversal apontado na avaliação de 23/07. O plano era o **Bloco C**: (1) Testcontainers com Postgres real para migrações + RLS (incluindo o teste de fumaça: dois usuários, cada um cego para os dados do outro); (2) regras de domínio como classes puras testáveis sem Spring — decisão de desenho a tomar ANTES do primeiro serviço de conta; (3) Spring Boot Test só nos fluxos de autenticação.

**Resolução:** os três itens existem e passam (31 testes). Decisões B-C1 a B-C5 em `decisoes.md`. Arquivos: `raspybank-app/src/test/java/com/raspybank/integracao/` (base + `MigracoesTest` + `RowLevelSecurityTest` + `AutenticacaoFluxoTest`) e `raspybank-identidade/.../TokenRenovacaoTest` (o padrão da camada pura). O esqueleto cobre a fundação; a V10 deve CRESCER esses arquivos, não criar outro modelo.

---

# Achados da avaliação de 23/07/2026 (ainda não resolvidos)

Origem: varredura completa do repositório. Mesma regra do documento: registrado = adiado conscientemente.

## I-11 — Corrida na rotação do token de renovação — **RESOLVIDO em 23/07/2026**

`AutenticacaoServico.renovar()` faz read-check-write sem trava: duas requisições simultâneas com o MESMO token podem ambas passar por `jaFoiUsado()` e ambas receber tokens novos — exatamente o cenário que a detecção de reuso existe para pegar. Correção: `UPDATE ... SET usado_em = now() WHERE token_hash = :h AND usado_em IS NULL` decidindo pelo contador de linhas (0 = reuso), ou `SELECT ... FOR UPDATE`.

**Resolução:** a primeira alternativa (`marcarUsadoSeInedito` no repositório). Teste de corrida real com duas requisições simultâneas: `AutenticacaoFluxoTest.renovacoesSimultaneasSoUmaVence`. Decisão B-T3 em `decisoes.md`.

## I-12 — Colisão de e-mail no cadastro vira 500 — **RESOLVIDO em 23/07/2026**

Não há tratador global de erros; a violação de `ux_usuario_email` sobe como erro genérico. As telas precisarão de um contrato de erro estável (409 limpo para duplicata, 400 para validação, JSON uniforme).

**Resolução:** `TratadorGlobalDeErros` (`@RestControllerAdvice`): contrato `{"erro": ...}` (+ `"campos"` na validação), 409 para duplicata, 400 para validação/corpo ilegível, 500 sem vazamento. Decisões B-T1/B-T2 em `decisoes.md`.

## I-13 — `X-Canal` é auto-declarado pelo cliente

`FiltroAutenticacaoJwt.canalDe()` confia num header que qualquer cliente forja. Quando o bot Telegram chegar, o canal deve derivar do caminho de autenticação (credencial/rota própria), nunca de header. Irmão do I-04.
**Quando resolver:** junto com o I-04, no início do trabalho do bot.

## I-14 — `/logout` derruba TODAS as sessões do usuário — **RESOLVIDO em 23/07/2026**

`encerrarSessoes()` revoga todos os tokens de todos os dispositivos. Pode ser o desejado ("sair de todos os lugares"), mas o nome promete outra coisa, e não existe logout de um único dispositivo porque o JWT não carrega `familia_id`. Decidir e registrar.

**Resolução:** o JWT ganhou a claim `fam`; `/logout` revoga só a família da sessão atual (este dispositivo), `/logout-todos` revoga todas. Token sem `fam` cai no comportamento antigo — o lado seguro. Decisão B-T5 em `decisoes.md`.

## I-15 — Renovação reseta o ambiente para o primeiro; não existe troca de ambiente — **RESOLVIDO em 23/07/2026**

`/login` e `/renovar` chamam `primeiroAmbienteDe()`. Usuário com dois ambientes operando no segundo volta ao primeiro a cada renovação. E não há endpoint de troca de ambiente — com R4 (multi-ambiente) sendo requisito central, a lacuna aparece no primeiro uso real de casal. `/renovar` deve preservar o ambiente do token anterior; a troca explícita precisa de endpoint próprio que valide o vínculo.

**Resolução:** `/renovar` aceita `ambienteId` opcional e preserva (com vínculo conferido; fallback para o primeiro, sempre informado na resposta); troca explícita em `POST /api/sessao/ambiente`, protegida, validando o vínculo com RLS ativo. Decisões B-T6/B-T7 em `decisoes.md`.

## I-16 — `token_renovacao.ip_origem` existe e nunca é gravado

A V4 criou a coluna e a justificou ("a pessoa reconhecer sessões"); a entidade não a mapeia e o login não a preenche. Ou gravar, ou remover na V10.
**Quando resolver:** na tela de "sessões ativas", se existir; senão, remover.

## I-17 — Derivas menores entidade × schema e limpezas — **RESOLVIDO em 23/07/2026**

(a) `RegistroAuditoria.usuarioId` declara `nullable = false`, mas a V8 derrubou o NOT NULL da coluna; (b) javadoc do campo `canal` ainda cita valores minúsculos pré-V8; (c) import duplicado de `ContextoRequisicao` em `AutenticacaoControlador`; (d) `listarDoUsuarioSemContexto` devolve `List<Object>` em vez de `List<UUID>`; (e) senha sem `@Size(max = 72)` — BCrypt trunca em 72 bytes.

**Resolução:** os cinco corrigidos junto com o bloco pré-telas. Ressalva no (e): `@Size` conta caracteres, não bytes — senha com muitos caracteres multibyte ainda pode passar de 72 bytes; aceitável porque o BCrypt trunca em silêncio, sem erro.
