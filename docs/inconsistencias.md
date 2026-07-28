# RaspyBank — Inconsistências e Pendências Conhecidas

**Versão:** 1.1
**Data:** 26 de julho de 2026
**Regra deste documento:** ambiguidade registrada é decisão adiada conscientemente; ambiguidade solta reaparece como bug. Cada item tem um "quando resolver" — nenhum é urgente por definição, senão já teria sido resolvido.
**Ao resolver um item:** mover a decisão para `decisoes.md` e marcar aqui como resolvido com a referência, sem apagar.

---

## I-01 — `ambiente.status` × `ambiente.excluido_em`: dois mecanismos de ciclo de vida — **RESOLVIDO em 26/07/2026**

A tabela `ambiente` tem `status` (`ATIVO/INATIVO`) **e** `excluido_em` (timestamp nullable), e `app_ambientes_do_usuario()` filtra pela exclusão lógica. Pergunta sem dono: **o que `INATIVO` significa que `excluido_em` não significa?**

Leituras possíveis: (a) INATIVO = pausado/arquivado reversível, excluído = terminal — dois estados legítimos; (b) `status` é vestígio e nunca é gravado por ninguém — peso morto; (c) coexistem sem regra — o pior caso, cada query escolhendo um critério.

**Resolução:** leitura (b). A V10 derruba a coluna `status`; o ciclo de vida fica só em `excluido_em`, e a exclusão lógica **é** o arquivamento reversível (basta anular a coluna). Decisão B-D5 em `decisoes.md`. Este item virou o critério de projeto usado no resto da varredura de 26/07: **uma flag, um trabalho** — foi ele que expôs o defeito de `sistemica` acumulando dois significados (B-D15) e a redundância de F31 (B-D4).

## I-02 — `primeiroAmbienteDe` pode devolver `null` e o login segue — **RESOLVIDO em 23/07/2026**

`AutenticacaoControlador.primeiroAmbienteDe()` devolve `null` para usuário sem ambiente, e o fluxo emite `jwt.emitirAcesso(usuarioId, null)` sem reclamar. Hoje impossível na prática (A12: cadastro cria o primeiro ambiente atomicamente), mas se acontecer, o sintoma será um token esquisito em vez de um erro claro.

**Resolução:** guard clause com `IllegalStateException` explícita (→ 500 pelo tratador global). Decisão B-T8 em `decisoes.md`.

## I-03 — `/renovar` não gera auditoria — **RESOLVIDO em 23/07/2026**

Login gera `ACESSO`; a renovação de token — que estende a sessão por mais 30 dias — passa em silêncio. Defensável (a trilha encheria a cada 15 min de uso ativo), mas hoje é **acidental**, não deliberado.

**Resolução:** opção (b), como sugerido. Renovação normal não audita (agora deliberado); reuso detectado audita `ACESSO` com `{"evento":"reuso_token_renovacao"}`. O resultado `sealed` do serviço obriga o controlador a tratar o caso. Decisão B-T4 em `decisoes.md`.

## I-04 — `Canal.WEB` fixo nos chamadores

`AutenticacaoControlador` e `OnboardingServico` passam `Canal.WEB` hardcoded. Verdade hoje (só existe web), mentira no dia em que o bot Telegram chegar — cadastro via Telegram seria auditado como WEB. O `ContextoRequisicao` já sabe o canal (o `PerfilControlador` lê de lá).

**Quando resolver:** no início do trabalho do bot Telegram, como primeiro item. Não generalizar antes — YAGNI.

## I-05 — Auditoria: gatilho (F26) × serviço (requisitos) — **RESOLVIDO em 26/07/2026**

Os requisitos fecharam auditoria escrita pela camada de serviço (para capturar canal); a Fase 2 fechou F26 com gatilho lendo contexto RLS para as tabelas de domínio. A leitura harmonizada (registrada em `decisoes.md`): gatilho para domínio, serviço para autenticação. Mas essa convivência ainda não foi exercitada — a V10 será o teste. Se o gatilho não conseguir capturar canal de forma satisfatória, revisar formalmente qual modelo vence.

**Resolução:** nenhum dos dois lados perdeu. O conflito existia por uma razão só — o gatilho não sabia o canal — e ela foi removida: o aspecto `ConfiguradorSessaoRls` passa a injetar `raspybank.canal` na mesma transação em que já injeta `raspybank.usuario_id`, e o gatilho lê os dois. F26 segue valendo, com a virtude intacta (alteração feita por fora da aplicação grava autor nulo e se denuncia). Decisão B-D6 em `decisoes.md`.

## I-06 — Argon2id (requisitos) × BCrypt 12 (implementado)

O documento de requisitos especifica Argon2id para hash de senha; a implementação da Fase 4 usa BCrypt strength 12 (`PasswordEncoder` do Spring). BCrypt 12 é plenamente adequado para o caso; Argon2id é o estado da arte com resistência a GPU. Não é urgência de segurança — é **divergência doc × código** que precisa de veredito: ou os requisitos passam a dizer BCrypt, ou se planeja a migração (o Spring suporta `DelegatingPasswordEncoder` com upgrade transparente no próximo login).

**Quando resolver:** qualquer momento antes da publicação para terceiros. Para uso familiar, BCrypt 12 registrado como decisão basta.

## I-07 — Estados da Fatura (pendência herdada dos requisitos)

Confirmar o conjunto derivável: Aberta, Fechada, Paga, Vencida — lembrando F19: **não existe coluna de status**; tudo deriva de `fechada_em` + somas de pagamento + vencimento.
**Quando resolver:** no desenho da **V12** (era V11 antes de B-D30) (a fatia 2 passou a ser V11 por B-D1; `fatura` não está mais na V10).

## I-08 — Entrada de usuário em ambiente existente (pendência herdada)

Convite por e-mail, código, ou adição manual. Fluxo pequeno, mas toca segurança (quem pode adicionar quem).
**Quando resolver:** depois da V10, antes de qualquer uso real compartilhado.

## I-09 — Dashboard (pendência herdada) — **RESOLVIDO em 26/07/2026**

Decisão de produto, não de modelo. Vive no futuro Mapa de Telas.

**Resolução:** o Mapa de Telas existe e respondeu. Dashboard com gráficos está explicitamente **fora** do mínimo aceitável (`mapa-telas.md` §3); quem ocupa o centro da tela principal é a T-07, o mapa de gastos — que é uma matriz, não um painel de indicadores. A pendência deixa de ser ambiguidade e vira item de backlog comum.

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

## I-16 — `token_renovacao.ip_origem` existe e nunca é gravado — **RESOLVIDO em 26/07/2026**

A V4 criou a coluna e a justificou ("a pessoa reconhecer sessões"); a entidade não a mapeia e o login não a preenche. Ou gravar, ou remover na V10.

**Resolução:** passa a ser gravado no login, a partir do request. A coluna deixa de ser órfã **em troca de um compromisso**: a tela de sessões ativas entra no roteiro pós-mínimo aceitável (item I-18 abaixo). Decisão B-D7 em `decisoes.md`. Sem a tela, é dado pessoal parado — e nesse caso o item volta à pauta para remoção, não para ser esquecido de novo.

## I-17 — Derivas menores entidade × schema e limpezas — **RESOLVIDO em 23/07/2026**

(a) `RegistroAuditoria.usuarioId` declara `nullable = false`, mas a V8 derrubou o NOT NULL da coluna; (b) javadoc do campo `canal` ainda cita valores minúsculos pré-V8; (c) import duplicado de `ContextoRequisicao` em `AutenticacaoControlador`; (d) `listarDoUsuarioSemContexto` devolve `List<Object>` em vez de `List<UUID>`; (e) senha sem `@Size(max = 72)` — BCrypt trunca em 72 bytes.

**Resolução:** os cinco corrigidos junto com o bloco pré-telas. Ressalva no (e): `@Size` conta caracteres, não bytes — senha com muitos caracteres multibyte ainda pode passar de 72 bytes; aceitável porque o BCrypt trunca em silêncio, sem erro.

---

# Achados da varredura de 26/07/2026 (pré-V10)

Origem: auditoria dos documentos contra o código, feita a pedido, **antes** de escrever a primeira linha da V10. Três contradições e duas lacunas não estavam registradas em lugar nenhum — todas foram decididas na mesma sessão e vivem em `decisoes.md` §4d. Ficam aqui pelo rastro: o que estava errado importa tanto quanto o que ficou certo.

## I-19 — Os dois documentos descreviam V10 diferentes — **RESOLVIDO em 26/07/2026**

`decisoes.md` §6 dizia V10 = `conta`, `conta_ambiente`, `cartao`, `cartao_emitido`, `fatura`. `mapa-telas.md` §4 dizia fatia 1 = `categoria`, `subcategoria`, `conta`, `lancamento`. Nenhuma lista continha a outra, e **nenhuma das duas estava completa**: faltava `conta_ambiente` numa (sem ela `conta` não tem política de RLS, porque R7 faz a visibilidade por subquery sobre a tabela de vínculo) e faltavam `categoria`, `lancamento`, `parcela` e `regra_recorrencia` na outra.

**Resolução:** B-D1 — V10 (fatia 1) e V12 (fatia 2, era V11), com as listas completas em `decisoes.md` §6.
**Lição registrada:** duas listas da mesma coisa em documentos diferentes divergem sem ninguém perceber. A lista canônica é a de `decisoes.md` §6; `mapa-telas.md` referencia, não repete.

## I-20 — Lançamento em conta compartilhada não tinha ambiente definido — **RESOLVIDO em 26/07/2026**

R7 justifica `conta_ambiente` N:N com "contas conjuntas visíveis em mais de um ambiente". F4 diz que todo lançamento aponta para exatamente uma conta. F33 diz que o relatório filtra por `lancamento.ambiente_id`. Os três juntos deixavam sem resposta: numa conta visível em dois ambientes, de qual ambiente é o gasto? Ninguém tinha decidido, e a primeira tela de lançamento teria que inventar.

**Resolução:** B-D2 — o ambiente ativo da sessão, com restrição garantindo que a conta pertence a ele.

## I-21 — A lista de categorias sistêmicas nunca foi escrita — **RESOLVIDO em 26/07/2026**

F10 diz que `categoria.codigo` identifica as sistêmicas; F13 diz que ambiente novo nasce com elas; F9 diz que são copiadas por ambiente. **Quais são elas não estava em documento nenhum** — e é semente de migração: sem a lista, a V10 não sai. Junto veio a descoberta de que `auth_criar_ambiente_inicial` (V5) nunca cumpriu a promessa de F13: cria ambiente e vínculo, mais nada.

**Resolução:** B-D13 (as três: `TRANSFERENCIA`, `AJUSTE`, `NAO_CLASSIFICADO`), B-D14 (sem kit inicial), B-D15 (`sistemica` ≠ `entra_no_mapa`), B-D16 (V5 estendida + retroalimentação dos ambientes existentes).

## I-22 — F15 tornava a tela central inutilizável no mínimo aceitável — **RESOLVIDO em 26/07/2026**

F15 (lançamento fora de cartão nasce `PREVISTO`) foi escrita na Fase 2, antes de existir tela. Como o mínimo aceitável não tem cartão, **todo** lançamento nasceria previsto; com o mapa somando realizados, o usuário cadastraria dez gastos já pagos e a tela central continuaria zerada até confirmar os dez.

**Resolução:** B-D9 / R9 — o status deriva da data de caixa.
**Lição registrada:** decisão de modelo tomada antes de existir tela precisa ser reencontrada quando a tela aparece. F15 não estava errada; estava incompleta por falta de um caso que só a tela revelou.

## I-18 — Tela de sessões ativas *(aberta — compromisso assumido em 26/07/2026)*

Contrapartida de B-D7: `token_renovacao.ip_origem` passou a ser gravado, e gravar dado pessoal só se justifica se ele for usado. A tela lista as sessões do usuário (dispositivo, IP, último uso) e permite encerrar uma delas — o `/logout` por família (B-T5) já dá a mecânica pronta.

**Quando resolver:** depois do mínimo aceitável, antes de qualquer exposição à internet. Se for descartada, o I-16 reabre para remover a coluna.

## I-23 — O saldo de conta compartilhada pode ser parcial *(aberta — achada em 26/07/2026, fatia 2)*

O saldo é a soma dos lançamentos, e o RLS só libera os dos ambientes a que a pessoa pertence. Numa conta conjunta visível no ambiente "Casa" **e** no ambiente pessoal de cada um, um lançamento que a Alice fez no ambiente pessoal dela é invisível para o Bruno — e o saldo que ele vê é maior do que o dinheiro que existe.

**Por que não foi corrigida agora:** a correção exigiria uma função `SECURITY DEFINER` somando por fora da política, e o critério B-D19 só a autoriza diante de **impasse estrutural** — quando a linha só se torna visível por um vínculo que ainda não pode existir. Aqui não há impasse: há uma escolha de visibilidade. Furar a política por conveniência é exatamente o que B-D19 passou a proibir.

**Quando resolver:** junto de I-08 (entrada de usuário em ambiente existente). Enquanto não houver convite, não há segundo usuário para divergir, e o número está certo para todo mundo que existe hoje.

**Alternativa a avaliar na hora:** em vez de somar por fora, marcar a conta como "saldo parcial neste ambiente" quando ela tiver vínculo com ambiente que o usuário não enxerga — dizer a verdade sobre o recorte é melhor do que furar a política para escondê-lo.

---

# Situação em 26/07/2026

**Resolvidos:** I-01, I-02, I-03, I-05, I-09, I-10, I-11, I-12, I-14, I-15, I-16, I-17, I-19, I-20, I-21, I-22.

**Abertos, com dono e momento:**

| Item | Assunto | Quando |
|---|---|---|
| I-04 + I-13 | Canal auto-declarado (`Canal.WEB` fixo, header `X-Canal`) | Primeiro item do trabalho do bot Telegram. B-D6 preparou o terreno: o canal já viaja no contexto do RLS |
| I-06 | Argon2id (requisitos) × BCrypt 12 (código) | Antes de publicar para terceiros |
| I-07 | Estados da Fatura | Desenho da V12 |
| I-08 | Entrada de usuário em ambiente existente (convite) | Depois do mínimo, antes de uso compartilhado real |
| I-18 | Tela de sessões ativas | Depois do mínimo, antes de exposição à internet |
| I-23 | Saldo parcial em conta compartilhada entre ambientes que o usuário não enxerga | Junto de I-08. Sem convite, não há segundo usuário para divergir |
| P-T8 | Token em `localStorage` × cookie `httpOnly` (`mapa-telas.md`) | Antes de expor à internet |

Nenhum dos abertos bloqueia a V10.
