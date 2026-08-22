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

## I-08 — Entrada de usuário em ambiente existente (pendência herdada) — **RESOLVIDO na V15, em 29/07/2026**

Convite por e-mail, código, ou adição manual. Fluxo pequeno, mas toca segurança (quem pode adicionar quem).
**Quando resolver:** depois da V10, antes de qualquer uso real compartilhado.

**Resolução:** o compartilhamento de ambiente (§4j de `decisoes.md`, B-D74 a B-D84; contrato em `api.md` §2c). Por e-mail, sem código e sem aceite (B-D80). "Quem pode adicionar quem" ganhou resposta estrutural: só o **dono** (B-D75/B-D76), com a regra repetida na política do banco — não só no serviço. A revogação com a pessoa dentro, que era o risco escondido do fluxo, fechou por B-D83/B-D84.

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

## I-23 — O saldo de conta compartilhada pode ser parcial *(RESOLVIDO em 29/07/2026, na V16)*

O saldo é a soma dos lançamentos, e o RLS só libera os dos ambientes a que a pessoa pertence. Numa conta conjunta visível no ambiente "Casa" **e** no ambiente pessoal de cada um, um lançamento que a Alice fez no ambiente pessoal dela é invisível para o Bruno — e o saldo que ele vê é maior do que o dinheiro que existe.

**Por que não foi corrigida agora:** a correção exigiria uma função `SECURITY DEFINER` somando por fora da política, e o critério B-D19 só a autoriza diante de **impasse estrutural** — quando a linha só se torna visível por um vínculo que ainda não pode existir. Aqui não há impasse: há uma escolha de visibilidade. Furar a política por conveniência é exatamente o que B-D19 passou a proibir.

**RESOLVIDO no desenho, em 29/07/2026** — e não adiado. O compartilhamento (§4j) aperta o B-D18: vincular conta a ambiente passa a exigir ser **dono** dos dois lados (B-D78), então a conta compartilhada não escapa para o ambiente pessoal de quem recebeu o acesso. Todo lançamento dela nasce no mesmo ambiente, e as duas pessoas veem o mesmo saldo.

**Emenda no mesmo dia, algumas horas depois.** O compartilhamento de CONTA (§4k) reabre o caso de propósito, e resolve por outro caminho: em vez de proibir a conta de ir para outro ambiente, define o que isso significa — **o saldo atravessa ambientes, a classificação não** (B-D85). As duas respostas convivem porque tratam de quem decide: em §4j o convidado não leva a conta por conta própria; em §4k o dono concede.

Vale registrar por quê: o I-23 não foi contornado. O modelo de compartilhamento que o Abner escolheu — a pessoa entra no ambiente e trabalha lá dentro, em vez de enxergar a conta do lado de fora — **não cria a divergência**. A correção que sobrou (B-D78) só fecha a porta lateral por onde ela ainda poderia entrar.

**Fechado em código na V16, e o caminho foi o que o desenho previu.** A soma por fora da política existe — `app_saldo_da_conta`, `app_extrato_da_conta` e, na V17, `app_total_da_fatura` — e ela é a **quarta exceção** de B-D19 e a primeira em consulta de leitura. O impasse que a autoriza é real e diferente dos três anteriores: por construção uma pessoa **não pode** ver os lançamentos da outra pela política, e mesmo assim precisa **somá-los**. Não é conveniência de camada — nenhuma política resolveria, porque a política correta é justamente a que esconde o lançamento alheio.

Cada uma das três tem **porteiro na primeira linha** (`conta_id IN (SELECT app_contas_do_usuario())`), sem o qual `SECURITY DEFINER` significaria "leia qualquer conta do sistema, basta ter o UUID". `CompartilhamentoContaApiTest` guarda o saldo igual dos dois lados; `CartaoCompartilhadoApiTest` guarda a fatura — onde o sintoma de errar não é confusão, é juros.

**A alternativa que estava anotada aqui** — marcar a conta como "saldo parcial neste ambiente" em vez de somar — não foi usada como conserto, mas sobreviveu como **informação**: a conta dividida vem marcada `compartilhada: true`, e é essa marca que explica na tela por que o saldo é maior do que a soma dos lançamentos visíveis.

## I-24 — Editar uma compra no cartão a tirava do total da fatura *(RESOLVIDO em 08/08/2026, em produção)*

**O primeiro defeito encontrado com dado real em produção.** Vale registrar inteiro, porque
o que ele ensina não é o erro em si — é o formato dele.

### O sintoma

Uma compra de 116,76 no UltraVioleta (fatura 2026-08) foi editada para 119,97, o valor
certo. Depois disso:

- a **lista** da fatura mostrava 119,97 ✔
- a **tela de lançamentos** mostrava 119,97 ✔
- o **total a pagar** mostrava 2.014,06, quando o certo era 2.134,03 ✘

A diferença era **119,97** — o valor **inteiro** do lançamento, não os 3,21 da correção. O
gasto não tinha ficado desatualizado: tinha parado de ser contado, sem sair da lista.

### A causa

A tela manda o **banco** e o **plástico** (B-D61), e é o servidor que traduz isso na conta
do cartão, onde o lançamento mora. `registrar` fazia a tradução (`resolverContaDaCompra`);
**`atualizar` não fazia** — gravava o banco cru em `conta_id` com um `moverPara` direto. O
`cartao_emitido_id` nem era tocado no PUT.

O resultado é um lançamento com a fatura certa e a conta errada. E é aí que o defeito fica
invisível, porque as duas telas leem por critérios diferentes:

| Função | Filtro | Efeito |
|---|---|---|
| `app_extrato_da_fatura` | `fatura_id` | continua exibindo a compra |
| `app_total_da_fatura` | `fatura_id` **AND** `conta_id = cartao` | descarta a compra |

O `conta_id = cartao` do total **não é bug**: ele existe para não contar a perna de saída do
pagamento da fatura, que vive na conta pagadora de propósito (B-D59). O preço dele é que
qualquer lançamento com a conta corrompida some do total sem sumir da tela.

Efeito colateral que ninguém tinha visto: como `app_saldo_da_conta` soma por `conta_id`, a
compra passou a debitar o **Nubank** direto, em vez da dívida do cartão.

### O que o banco não pegou

`ck_lancamento_cartao_exige_fatura` exige que a fatura **exista**, não que ela seja a do
cartão onde a compra mora. Uma `CHECK` de verdade aqui não é possível — o invariante cruza
`lancamento` e `fatura`, e o Postgres não aceita subconsulta em `CHECK`. Só gatilho.

### A correção (aplicada em 08/08/2026)

Em `LancamentoServico.atualizar`:

1. **Passou a chamar `resolverContaDaCompra`**, a mesma tradução do POST. É a correção da
   causa.
2. **Passou a gravar `cartaoEmitidoId`** — antes o PUT o ignorava por completo, então trocar
   o plástico de uma compra já lançada era impossível, e o silêncio fazia a tela parecer ter
   obedecido.
3. `conferirFormaAceita` passou a usar a conta onde o lançamento **mora**, e não a que a tela
   mandou — é ela que a chave composta `(conta_id, forma_pagamento)` cobra.
4. **`exigirFaturaCoerente`**, novo: recusa (403) lançamento que fique numa fatura de outro
   cartão, com `PAGAMENTO_FATURA` isento por ser a única inconsistência legítima. É a guarda
   que transforma a **classe** do defeito em erro visível.

**Achado junto:** a linha `if (!destino.getCartaoId().equals(l.getContaId()))` comparava a
fatura de destino contra o **banco**, então *trocar o mês da fatura de uma compra sempre dava
403* — o recurso pedido explicitamente ("o usuário pode pegar um lançamento e editar ele e
trocar o mês da fatura") nunca funcionou. Sai consertado de carona, porque agora `conta_id`
é a conta certa.

**Guardado por** `EdicaoDeCompraNoCartaoTest` (4 casos). O teste foi rodado contra o código
**sem** a correção e falha em 3 deles — `expected: <119.97> but was: <0.00>` no total da
fatura e `expected: <403> but was: <200 OK>` na guarda. Um teste de regressão que passa antes
e depois não guarda nada.

### O que ficou pendente

**Migração V21 com o gatilho no banco**, para o invariante valer também contra SQL manual e
código futuro que não passe pelo serviço. **Decisão do Abner (08/08/2026): a V21 é feita
primeiro no ambiente de desenvolvimento**, e vai para o Pi num deploy conjunto depois — não
junto da correção de código, porque migração de schema é classe de risco diferente: o Flyway
a aplica no deploy, e uma que falhe no meio impede o app de subir.

**Atenção a quem pegar esta pendência: o número V21 já tem outros dois pretendentes.** A
T-10 (§4s de `decisoes.md`) tomou o V21 para o extrato completo e cedeu para **V22** ao achar
`V21__telegram_e_um_destino_so.sql` já em `origin`; o Telegram, por sua vez, terá de virar
**V23** no merge (mesma seção). Este gatilho é o **terceiro** candidato ao número — hoje três
migrações diferentes disputam "V21" e nenhuma delas é mais essa outra. Confira o estado das
migrações (`decisoes.md` §6) antes de nomear o arquivo; o próximo número livre pode já ter
mudado de novo.

### A lição

O invariante que faltava não era sobre o cartão — era sobre **duas leituras do mesmo dado
discordarem em silêncio**. Onde uma função de leitura filtra por mais colunas que a outra, a
diferença entre elas é uma corrupção possível que nenhuma tela mostra. Vale procurar os
outros pares assim antes que o dado real os encontre.

---

# Achados da conversa de 09/08/2026 — crédito na fatura do cartão

Origem: caso real trazido pelo Abner. O Ultravioleta do Nubank devolve o IOF — a assinatura
do Claude custa R$ 110 com IOF e o banco credita R$ 10 depois. Não há como registrar isso
hoje. Os três itens abaixo saíram da varredura desse caso; **o I-25 está aberto por decisão
explícita ("não estou conseguindo decidir, fica depois"), e os outros dois são defeitos que
existem independentemente de como o I-25 se resolva.**

## I-25 — Dinheiro que entra na fatura do cartão não tem modelo

**O caso.** Estorno de compra estornada depois do fechamento, estorno feito numa fatura
posterior à da compra, e benefício creditado na fatura — o desconto que o Nubank dá por
antecipar parcelas. Nos três, dinheiro entra no cartão sem ser pagamento de fatura.

**Por que não dá hoje.** `raspybank-web/src/api/recursos.js:267` não oferece os plásticos
quando o sentido é `ENTRADA` — *"Cartão de crédito só serve para SAÍDA: ninguém recebe
salário no cartão"*. O comentário está certo sobre salário e não cobre este caso.

**O resto do caminho já aceita.** Conferido linha a linha: `AJUSTE` é `AMBOS` (V12:77);
`LancamentoServico.registrar`, `resolverContaDaCompra` e `registrarNoCartao` não filtram tipo
em ponto nenhum; nenhum CHECK impede `ENTRADA` em conta de cartão (`ck_lancamento_cartao_exige_fatura`
é de mão única); `resolverFormaDePagamento` devolve nulo para sistêmica, então o crédito não
nasce com forma grudada; o extrato da fatura já desenha `ENTRADA` com `+` (`Cartoes.jsx:681`);
e o limite se corrige sozinho, porque `consumido()` é `saldo.comPrevistos().abs()`. **A
ausência é de modelo e de tela, não de infraestrutura.**

### O que já ficou decidido (não precisa ser repensado)

1. **Estorno total com a fatura ainda aberta e não paga → exclua o lançamento.** Mostrar a
   compra de R$ 100 na padaria e um estorno de R$ 100 na mesma fatura descreve um evento que
   se anulou; as duas linhas só produzem confusão no mapa. A auditoria não se perde:
   `tg_lancamento_auditoria` grava a linha inteira antes de ela sumir.
2. **Estorno parcial com a fatura ainda aberta e não paga → edite o valor** (R$ 150 vira
   R$ 100). Mesmo argumento.
3. **A fronteira não é "total × parcial", é "a fatura já foi paga?"** — enunciada assim
   porque foi assim que ela apareceu. Enquanto a fatura está aberta e ninguém pagou, a compra
   estornada economicamente não aconteceu, e reescrever a linha é honesto. Depois do
   pagamento, apagar a linha faz o pagamento daquele mês deixar de bater com a fatura daquele
   mês. **É esta regra que decide qual caminho vale, e é dela que sai o I-27.**
4. **Categoria única para estorno e desconto.** Para o código os dois são a mesma coisa —
   dinheiro entrando na fatura que não é pagamento. Duas categorias com comportamento
   idêntico seriam uma distinção que nenhuma rotina consegue usar; a diferença mora na
   descrição digitada.
5. **É uma categoria sistêmica, não um `TipoLancamento` novo.** `TipoLancamento` tem dois
   valores porque o sinal do dinheiro mora ali; um terceiro quebraria toda soma que hoje faz
   `CASE WHEN tipo = 'SAIDA'`. E precisa ser sistêmica porque o código tem de encontrá-la
   sozinho para distinguir crédito de pagamento — que é a definição de `CodigoSistemico`.
6. **Nome sugerido:** código `ESTORNO_DESCONTO`, nome de tela "Estornos e descontos".
   Evitar a palavra "crédito" no código é convenção do projeto — em português ela significa
   "cartão de crédito" e "entrou dinheiro" ao mesmo tempo, e foi por isso que
   `FormaPagamento` tem `CREDITO_EM_CONTA` e não `CREDITO`.
7. **Entra no mapa** (`entra_no_mapa = true`), como entrada. Não há risco de contagem dupla:
   a compra está no bloco de saídas, o crédito no de entradas. Efeito colateral aceito e
   conhecido: estorno que cai numa fatura posterior deixa o gasto num mês e o crédito no
   seguinte — o ano fecha certo, o mês a mês não. É a mesma conta que o regime de caixa já
   cobra em B-D54.

### O que ainda não tem decisão

**Como a fatura mostra o crédito.** `TotalDaFatura` tem dois baldes — `total` = saídas na
conta do cartão, `pago` = **todas** as entradas — e está assim em duas leituras que precisam
concordar: o JPQL de `LancamentoRepositorio.java:244` e a função `app_total_da_fatura`
(V17:72). Sem um terceiro balde, o crédito de R$ 10 é contado como pagamento: "A pagar" fica
certo (R$ 100), mas "Compras" diz R$ 110, "Pago" diz R$ 10 que ninguém pagou, e a T-06 mostra
o aviso de fatura parcialmente paga numa fatura intocada.

As duas saídas:

- **(a) Compras líquidas.** Coluna Compras passa a R$ 100; o crédito aparece só como linha no
  extrato. Nenhuma coluna nova na T-06. Custo: "Compras R$ 100" numa fatura onde se comprou
  R$ 110 é meia verdade.
- **(b) Coluna própria.** Compras R$ 110 · Créditos R$ 10 · Pago R$ 0 · A pagar R$ 100. Não
  mente em lugar nenhum. Custo: um campo em `TotalDaFatura`, um `CASE` a mais nas duas
  consultas (as duas já têm essa forma) e uma coluna na T-06.

Recomendação registrada na conversa: **(b)** — o desconto por antecipação é informação que se
quer ver, não diluir dentro do total de compras.

**Nos dois casos**, `Fatura.quitacao()` e `Fatura.estaVencida()` passam a comparar o pago
contra o total **líquido**. Sem isso, uma fatura de R$ 110 com R$ 10 de crédito e R$ 100 pagos
nunca fica quitada — e, pior, uma fatura inteiramente creditada nasce "vencida".

**Quando resolver:** quando houver decisão sobre a apresentação. Nada aqui bloqueia o que já
está no ar; o caso real é contornável registrando o crédito como entrada numa conta comum,
ao custo de a fatura não refletir o abatimento.

## I-26 — `app_total_do_plastico` soma sem olhar o tipo

`app_total_do_plastico` (V19:503) faz `SUM(l.valor)` e `SUM(CASE WHEN situacao = 'REALIZADO' ...)`
**sem filtrar por `tipo`**. A função assume que todo lançamento de um plástico é compra — o
que é verdade hoje, e só hoje.

É defeito latente: não há como criar `ENTRADA` com `cartao_emitido_id` preenchido enquanto a
tela do I-25 não existir. No dia em que existir, o crédito de R$ 10 é **somado** ao consumo
do plástico em vez de subtraído, e o número que a pessoa que recebeu um adicional vê (B-D110)
passa a mentir para cima.

É o mesmo padrão da lição do I-24: duas leituras do mesmo dado com filtros diferentes.
`app_total_da_fatura` distingue `SAIDA` de `ENTRADA`; a irmã dela não.

**Quando resolver:** junto do I-25, na mesma migração — ou antes, isoladamente, já que a
correção (saídas menos entradas) está certa independentemente de como o I-25 se resolva.

## I-27 — Editar valor de lançamento em fatura fechada e paga

`LancamentoServico.atualizar` (linha 474) exige fatura aberta apenas para **mover** um
lançamento de fatura (`exigirFaturaAberta`, na troca de `faturaId`). Alterar o **valor** de
uma compra que está numa fatura já fechada e já paga passa sem guarda: `l.alterarValor(...)`
é chamado direto.

O sintoma é o total da fatura de um mês encerrado mudar depois do fato, deixando de bater com
o pagamento que foi feito contra ele. Nenhuma tela denuncia.

Hoje isso é um defeito discreto. **Com o I-25 ele vira um buraco na regra**, porque as
decisões 1 e 2 acima tornam "edite ou exclua" o caminho *oficial* do estorno — e um caminho
oficial que funciona onde não deveria é pior do que não ter caminho.

**Quando resolver:** antes ou junto do I-25. A guarda é pequena e não depende da decisão de
apresentação.

---

# Achados da sessão de 09/08/2026 — situação de lançamento de cartão

Origem: o Abner fechou uma fatura à mão, pagou o total, e os lançamentos continuaram
`PREVISTO`. A investigação separou **dois defeitos independentes** que produziam o mesmo
sintoma na mesma tela.

Evidência colhida no banco de desenvolvimento em 09/08/2026:

```
UltraVioleta · ago/2026 · vence 18/08 · FECHADA · total 282,00 · pago 282,00 (quitada)
  6 compras   SAIDA   PREVISTO   data_caixa 2026-08-18
  pagamento   par     PREVISTO   data_caixa 2026-08-10   <- I-28
```

## I-28 — A data do pagamento de fatura vem em UTC — **RESOLVIDO em 09/08/2026**

`raspybank-web/src/telas/Cartoes.jsx:910` preenche o campo Data do formulário de pagamento
com `new Date().toISOString().slice(0, 10)`. **`toISOString()` devolve UTC.** Em São Paulo,
das 21h em diante o campo abre com o dia seguinte.

Foi o que aconteceu: pagamento feito às 22h de 09/08 gravado como `data_caixa = 2026-08-10`.
Por B-D9 o par nasce `PREVISTO` — corretamente, para uma data que não é a real.

É a classe de erro que o projeto já documentou em B-D8, no javadoc de `Lancamento`: *"um
lançamento às 21h de 31/jan em São Paulo seria 01/fev em UTC e cairia no MÊS ERRADO"*. Ali a
lição foi aplicada ao banco (`date` em vez de instante); aqui ela escapou na tela.

**Correção:** trocar por `hojeISO()` (`raspybank-web/src/util/formato.js:161`), que usa
`getFullYear/getMonth/getDate` locais e já é o helper usado pelo formulário de lançamentos.
Uma linha. Varredura feita: esta é a **única** ocorrência de `toISOString` em todo o
frontend, e o backend está correto (`TZ=America/Sao_Paulo` no `Dockerfile:126`, no
`infra/compose.yaml:68` e no `.env.example:63`).

**Dado já gravado:** corrigível pela tela, editando o pagamento para a data real.

**Resolução:** trocado por `hojeISO()`. Decisão **B-D114** em `decisoes.md` (§4q). O dado
antigo continua exigindo a edição manual descrita acima — a correção vale para pagamentos
novos, não reescreve o passado.

## I-29 — A situação de compra de cartão ignora o pagamento da fatura, e erra nos dois sentidos — **RESOLVIDO em 09/08/2026**

Hoje `situacao` deriva só da data de caixa (B-D9), e a data de caixa de uma compra no cartão
é o **vencimento** da fatura (F14). Nem `FaturaServico.fechar` nem `FaturaServico.pagar`
tocam na situação das compras. `realizarPrevistosVencidos`
(`LancamentoRepositorio.java:278-280`) filtra só por data, sem olhar fatura nenhuma.

Resultado — o erro acontece nas **duas** direções:

- **Fatura fechada e paga antes do vencimento:** as compras seguem `PREVISTO` até o
  vencimento chegar. A fatura diz QUITADA e cada linha dentro dela diz "o dinheiro ainda vai
  sair". É o caso que originou esta investigação.
- **Fatura vencida e não paga:** no dia do vencimento as compras viram `REALIZADO` sozinhas.
  O dinheiro nunca saiu, e o mapa daquele mês passa a afirmar um gasto que não houve.

É o padrão da lição do I-24: duas leituras do mesmo fato discordando em silêncio.

### A regra decidida (Abner, 09/08/2026)

> **Compra de cartão é `REALIZADO` se, e somente se, a fatura estiver FECHADA e QUITADA.
> Em qualquer outro caso, `PREVISTO` — independente da data.**
>
> **Todo o resto do sistema continua derivando da data, como hoje** (B-D9 intacto). O boleto
> do condomínio agendado para 30/08 vira realizado sozinho em 30/08, e foi para não ter flag
> manual que B-D9 existe.

| | não quitada | quitada |
|---|---|---|
| **aberta** | PREVISTO | PREVISTO |
| **fechada** | PREVISTO | **REALIZADO** |

**Por que "fechada E quitada" e não "quitada" sozinha.** A recomendação na conversa foi
"quitada sozinha", com o argumento de que exigir fechada quebraria a antecipação (B-D57). O
argumento estava errado: antecipação libera limite por `consumido() = saldo.comPrevistos().abs()`,
que soma previsto e realizado igual — nunca dependeu da situação das compras. E exigir
*fechada* elimina de graça um efeito ruim: com "quitada sozinha", pagar uma fatura **aberta**
por inteiro realizaria tudo, e a próxima compra a cair naquela fatura desfaria a quitação e
mandaria todas as compras de volta para previsto. Fatura fechada não recebe lançamento novo
(`exigirFaturaAberta`), então o total para de se mexer e a regra fica estável por construção.

### O recorte — três cláusulas, e nenhuma é dispensável

"Lançamento de cartão" não é `fatura_id IS NOT NULL`. Dentro de uma fatura convivem três
coisas, e só a primeira é governada pela regra nova:

1. As **compras** — `conta_id` = conta do cartão, `fatura_id` preenchido. Regra nova.
2. A **perna de entrada do pagamento** — também na conta do cartão, também com `fatura_id`.
   Ela *é* o pagamento; segue a própria data.
3. A **perna de saída do pagamento** — conta corrente, e **também carrega `fatura_id`**
   (B-D59, para o extrato da corrente dizer qual fatura aquele dinheiro pagou). Dinheiro
   saindo do banco na data dela.

O recorte correto é: `conta_id` = conta do cartão **E** `fatura_id` preenchido **E** categoria
≠ `PAGAMENTO_FATURA`. Filtrar por `fatura_id` sozinho congelaria o pagamento; é exatamente a
forma do defeito do I-24.

### Forma de implementação recomendada

**Recalcular na leitura**, como irmão do `SituacaoVencidaServico`, e **não** um flip no
momento do pagamento. A quitação muda por caminhos demais — pagar, excluir um pagamento,
editar o valor de um pagamento, editar o valor de uma compra, lançar compra em fatura aberta,
fechar, reabrir, e amanhã um crédito do I-25. Um flip por caminho são sete lugares para
esquecer um; um recálculo na leitura é auto-curável, qualquer que tenha sido o caminho.

`realizarPrevistosVencidos` ganha a exclusão das compras de cartão, senão os dois disputam a
mesma coluna.

### Custos aceitos

- **A virada passa a ser de mão dupla.** O `SituacaoVencidaServico` é de mão única de
  propósito, para não brigar com `corrigirSituacao` (B-D22). Para compra de cartão essa
  liberdade some: a situação deixa de ser julgamento e vira fato derivado da fatura.
  Corrigir na mão passa a ser desfeito na próxima leitura.
- **Reabrir uma fatura paga devolve tudo para previsto.** Coerente, e reversível fechando de
  novo. Consequência de B-D50 permitir reabrir fatura quitada.
- **Fatura nunca paga mantém as compras previstas para sempre.** É dívida em aberto, e
  mostrar isso é informação — mas o mapa passa a acumular previsto antigo.
- **Sinergia com o I-27:** editar o valor de uma compra numa fatura fechada e paga desfaz a
  quitação e joga a fatura inteira de volta para previsto. O defeito do I-27 passa a se
  denunciar sozinho em vez de mudar um total em silêncio.

**Resolução:** implementada como descrito. Decisão **B-D113** em `decisoes.md` (§4p).
`SituacaoVencidaServico` virou `SituacaoServico`, com as duas regras e um ponto de entrada
(`sincronizar`), chamado onde a antiga já era mais as três leituras de fatura da T-06.
Guardada por `SituacaoDeCompraNoCartaoTest` (6 casos) — rodada contra o módulo **sem** a
correção, falha em 3 deles: `expected: <REALIZADO> but was: <PREVISTO>` nas duas metades do
fluxo de pagamento e `expected: <PREVISTO> but was: <REALIZADO>` na fatura vencida e não
paga. `SituacaoVencidaTest` continua verde: B-D9 não foi tocado.

**Segue aberto o I-27**, que é a mesma superfície e não foi resolvido aqui — mas mudou de
cara: com B-D113, editar o valor de uma compra numa fatura fechada e paga desfaz a quitação
e joga a fatura inteira de volta para previsto. O defeito passou a se denunciar sozinho em
vez de mudar um total em silêncio.

---

# Achados da auditoria de fronteiras de 16/08/2026

## I-30 — Outbox alimentada desde a V10, sem relay que a consuma

**O que já ficou decidido.** A09 promete "padrão Outbox para eventos entre módulos; relay em
processo agora, Redpanda na Fase 8; produtor não muda". F28 promete "Outbox alimentado desde
o primeiro lançamento, com relay em processo". As duas decisões seguem vigentes — a escrita do
evento na mesma transação da operação, que é o ponto do padrão, está correta e implementada
desde a V10 (`fn_publicar_evento_lancamento`).

**O que o repositório faz hoje.** Só a metade produtora existe. O gatilho grava um evento a
cada lançamento criado, alterado ou excluído, e a tabela `outbox` cresce. Não existe a outra
ponta: `EventoOutbox` (`raspybank-auditoria/src/main/java/com/raspybank/auditoria/dominio/EventoOutbox.java`)
não tem repositório Spring Data, não há `@Scheduled` em lugar nenhum do código (`grep -r
"@Scheduled"` no repositório inteiro não encontra nada), e nada lê a tabela, publica os
eventos ou marca `publicado_em`. "Relay em processo agora" descreve uma intenção registrada
antes de existir — não o estado do código.

**Por que nada quebra por enquanto.** Ninguém consome eventos: não há contexto extraído para
processo próprio, nem bot, nem qualquer outro leitor esperando a fila. A fronteira entre
módulos (A02, policiada por `ArquiteturaTest`) não depende do relay para existir — ela vem do
isolamento de módulo Maven e do ArchUnit, e continua garantida com ou sem consumidor. A tabela
apenas acumula linhas não publicadas indefinidamente, sem consequência visível hoje: nenhuma
tela lê `outbox`, nenhum saldo ou relatório depende dela.

**O que ainda não tem decisão.** Quando o primeiro consumidor precisar existir — extração de
um contexto para processo próprio, ou o bot do Telegram reagindo a lançamento —, alguém decide
se o relay é mesmo "em processo" (thread ou `@Scheduled` dentro do próprio `raspybank-app`,
como A09 já antecipa) ou se compensa pular direto para mensageria. Também fica em aberto se a
tabela cresce sem limite até lá, ou se ganha alguma purga/arquivamento antes de ter leitor.

**Quando resolver:** no início da extração do primeiro contexto para processo separado, ou
antes disso se surgir o primeiro consumidor de eventos (ex.: bot do Telegram). Nenhum uso
atual depende do relay, então nada bloqueia por causa deste item.

---

# Achados da leitura de 19/08/2026 — filtro de conta e cartão na T-08

Origem: o mesmo relato que produziu B-D115 (`decisoes.md` §4r). A lacuna do filtro de conta
foi corrigida com o seletor de cartão; este achado apareceu na mesma leitura, não é
consertado agora, e fica registrado para não reaparecer como bug daqui a três meses.

## I-31 — na T-08, o rodapé de totais não sinaliza que ele não é o extrato que fecha com o banco

**Correção de rota.** Uma primeira versão deste achado comparava `app_saldo_da_conta` com
`LancamentoRepositorio.buscar` e concluía que havia divergência sem decisão entre saldo e
extrato numa conta compartilhada. Não há: é **B-D87** (`decisoes.md:376`) — *"Três consultas
passam a atravessar ambientes; uma continua não atravessando"*. Saldo, extrato da conta
(`app_extrato_da_conta`, usada por `ContaServico.extrato`, `ContaServico.java:246`, exposta em
`GET /api/contas/{id}/extrato`) e total da fatura atravessam ambientes; o mapa não. **B-D96**
(`decisoes.md:411`) nomeia as três funções com o mesmo porteiro na primeira linha
(`conta_id IN (SELECT app_contas_do_usuario())`). Na T-05, onde saldo e extrato aparecem
juntos, os dois atravessam e fecham entre si — não há o que resolver ali. E o **I-23**, na
última seção, já registrou o mecanismo de explicação: a conta dividida vem com
`compartilhada: true`, "e é essa marca que explica na tela por que o saldo é maior do que a
soma dos lançamentos visíveis" (`inconsistencias.md:173`).

O que sobrevive a B-D87 é bem menor, e é de tela, não de leitura.

**O sintoma.** Na **T-08** (`Lancamentos.jsx:119,354-356`), o rodapé soma as linhas do mês
**do ambiente ativo** (`somar(lista)`, sobre a lista que já veio recortada). Numa conta
compartilhada, esse total nunca inclui os lançamentos que a outra pessoa fez no ambiente
dela — e nada na tela diz isso. O comentário em `Contas.jsx:70-71` explica a diferença para
quem lê o código; a T-08 não tem o equivalente para quem só olha a tela.

**A causa.** É deliberada, não é defeito: o extrato do mês (`GET /api/lancamentos`) é o do
ambiente e não atravessa — B-D87 e o Javadoc de `ContaServico.extrato`
(`ContaServico.java:236-238`) dizem isso de propósito, e `ContaControlador.java:173-177`
repete. O rodapé da T-08 apenas herda esse recorte, corretamente. O que falta não é mudar a
conta — é a tela **apontar** que existe um número que fecha com o banco em outro lugar
(`GET /api/contas/{id}/extrato`), do mesmo jeito que a T-05 aponta com `compartilhada: true`
(I-23). A T-08 não tem marca equivalente.

**O que o banco não pegou.** Nada pega — não é constraint nem política. É ausência de sinal
na interface: o rodapé da T-08 não é o extrato que confere contra o banco, e nenhuma etiqueta
diz isso a quem olha a tela.

**O que falta.** Sinalização de tela na T-08 — por exemplo, indicar quando a conta filtrada é
compartilhada e apontar o extrato da T-05 como o número que fecha. Não é decisão de leitura:
B-D87 já decidiu que ler é assim.

**Quando resolver:** sem dono e sem prazo — no próximo trabalho que toque a T-08.

## I-32 — `LancamentoControlador.listar` não valida o `contaId` do filtro

`LancamentoControlador.listar` (`LancamentoControlador.java:82-95`) passa o `contaId` do
filtro direto ao serviço, sem validar. Um id inexistente, de outro ambiente, ou de conta
encerrada devolve **200 com lista vazia**, indistinguível de "este mês não teve movimento".

Não é vazamento: a RLS mais o recorte por `l.ambienteId` no serviço seguram. É pré-existente,
não foi introduzido nesta entrega, e nenhuma decisão vigente exige validar id de **filtro** —
a tabela de B-T1 governa recurso endereçado por caminho, não parâmetro de consulta.

**O que o banco não pegou.** Nada pega — não é constraint nem política, é ausência de
validação de borda.

**Quando resolver:** junto da próxima fatia que mexer na T-08.

---

# Achados da investigação de 19/08/2026 — exclusão de parcela em produção

## I-33 — Excluir uma parcela deixa o grupo de parcelamento incoerente

### O relato (que não se sustentou)

Relato do usuário: *"parcela 2/2 duplicada"*. Investigado com consultas de diagnóstico
direto na base de **produção** (o Pi), em 19/08/2026. **Não há duplicação nenhuma** — a
varredura por par `(grupo_parcelamento_id, parcela_numero)` repetido veio vazia na base
inteira.

O que o usuário viu na fatura de outubro foram duas linhas `2/2` de R$ 105,79 em
05/10/2026, na mesma fatura — mas são **duas compras distintas** ("Petlove - internação
Buzina" e "Petlove - internação Bubu", dois pets), cada uma parcelada em 2x, com mesmo
valor no mesmo dia. É o mesmo padrão do I-24: o relato chegou com a causa embutida, e a
causa era outra. Reforça a regra do CLAUDE.md — o relato é sintoma, não diagnóstico.

### O que a base mostra

Dois grupos de parcelamento com `parcela_total = 2` e **apenas 1 linha** cada:
`34ebbe5e-6a2f-4d79-b3dc-19b25d4bd4b7` e `3b300e3f-5af5-4f08-a2a1-6007890a0003`. As
sobreviventes (as parcelas `2/2`), ambas R$ 105,79, `data_caixa` 2026-10-05, na fatura
`4b4089a3`: `744c0ce6` ("Buzina") e `351a6f23` ("Bubu").

A trilha em `registro_auditoria` confirma que cada compra nasceu numa transação só
(`criado_em` idêntico ao microssegundo entre a parcela 1 e a 2 — F23), o que descarta
envio duplicado:

```
15:03:28.059131  CRIACAO   a63477d5  ┐ mesma transação (compra Buzina)
15:03:28.059131  CRIACAO   744c0ce6  ┘
15:03:55.664503  CRIACAO   351a6f23  ┐ mesma transação (compra Bubu)
15:03:55.664503  CRIACAO   a13e1f33  ┘
15:04:32         CRIACAO   b68c5cd8    (Amazon PetFarmacia, não parcelado)
15:05:14         EXCLUSAO  b68c5cd8
15:05:16         EXCLUSAO  a13e1f33    (parcela 1/2 de Bubu)
15:05:18         EXCLUSAO  a63477d5    (parcela 1/2 de Buzina)
```

As três linhas excluídas tinham `data_caixa` 2026-09-05 — a fatura de setembro. Foram
exclusões **individuais e deliberadas**, com 2 segundos de intervalo entre elas; a hipótese
em aberto é mau uso — o usuário quis limpar a fatura de setembro e não percebeu que estava
partindo compras parceladas ao meio. O `estado_anterior` da auditoria guarda os 23 campos
de cada linha apagada, então a reconstrução fiel é possível.

### A causa

`LancamentoServico.excluir` (`raspybank-lancamento/src/main/java/com/raspybank/lancamento/servico/LancamentoServico.java:612-613`)
apaga o lançamento e nada mais. Nenhuma menção ao grupo. Tira-se uma parcela de um grupo de
duas e sobra uma linha declarando `2/2` num grupo que só tem uma linha.

O javadoc do método, logo acima dele, afirma o contrário em voz alta:

> Pode, porque nada aponta para ele: apagar um lancamento nao deixa nenhuma outra linha
> orfa, e o saldo simplesmente volta a ser a soma do que restou.

Era verdade na V10 e **deixou de ser na V12**: o `grupo_parcelamento_id` é uma linha
apontando para outra. O comentário nunca foi revisitado quando o cartão chegou, e hoje ele
autoriza exatamente a operação que quebra a invariante. Isso é parte do achado, não
detalhe — comentário que afirma uma garantia envelhece junto com o modelo, e um comentário
desatualizado que autoriza a operação errada é pior que nenhum comentário.

B-D55 assumiu esse custo por escrito: *"o `grupo_parcelamento_id` não tem tabela-alvo,
então não há FK garantindo o grupo — é identificador de correlação, e a integridade dele
fica na aplicação"*. A aplicação não a mantém. Este achado é a fatura desse custo chegando.

### O que o banco não pegou

Nada — e não por descuido. O `CHECK` da V12
(`raspybank-app/src/main/resources/db/migration/V12__cartao_de_credito.sql:292-296`) valida
a coerência do **trio dentro de uma linha** (grupo/número/total juntos, ou todos nulos). O
índice `ix_lancamento_grupo_parcelamento` (mesma migração, linhas 299-300) **não é único** —
não precisaria ser, várias linhas do mesmo grupo são o desenho normal. A invariante violada
é **entre linhas** (`count(*) do grupo == parcela_total`), e isso não cabe em `CHECK`: o
Postgres não aceita subconsulta ali. Só gatilho pegaria. Se a garantia deve migrar para o
banco (gatilho) ou ficar no serviço é parte da decisão em aberto.

### A correção — em aberto, nada foi feito

Nem código nem dado foram tocados. O usuário decidiu **pendurar o assunto**, e falta a
decisão de produto:

**Excluir a parcela `1/n` deve apagar o grupo inteiro, ou só a linha em tela?** As
alternativas na mesa: apagar o grupo todo (com confirmação dizendo quantas linhas e quanto
some), ou renumerar as sobreviventes (`2/2 → 1/1`). Renumerar reescreve o passado — passa a
afirmar que a compra foi de R$ 105,79 em 1x quando foi de R$ 211,58 em 2x, e erra em
silêncio o limite consumido, que existe para bater com o app do banco (B-D48).

**A pergunta que trava a decisão**, levantada pelo usuário: o que apagar o grupo faz com a
parcela que já foi paga? Ela tem âncora — B-D113 define "parcela paga" com precisão: a
compra é `REALIZADO` se, e somente se, a fatura dela estiver fechada e quitada. Logo não é
uma decisão só: é uma para parcelas em fatura aberta e outra para as que caíram em fatura
já conferida contra o banco. Mexer no total de uma fatura quitada é o que B-D65 recusou em
outro contexto (encerrar cartão não some com o passado).

**Dado de produção:** os dois grupos órfãos continuam incoerentes no Pi. O conserto
depende da intenção do usuário ao excluir (se era apagar as compras inteiras, apaga-se
também as `2/2`; se era só limpar a fatura de setembro, recriam-se as `1/2` a partir do
`estado_anterior`). É correção manual como `raspybank_owner`, **não** migração Flyway —
Flyway é schema, não dado de uma instalação.

### Estado

Aberto, **sem dono e sem prazo** — pendurado por decisão do usuário em 19/08/2026. Quando
for retomado, a ordem é: decisão registrada em `docs/decisoes.md` → `qa-adversarial` com o
teste vermelho → `dominio-lancamento` (serviço e javadoc) → eventual gatilho por
`banco-e-migracoes` → revisor → `make gate`.

### A lição

Duas, e as duas valem: (a) custo assumido em decisão ("a integridade fica na aplicação") é
dívida com vencimento, não isenção — B-D55 previu exatamente este buraco e ninguém voltou
para tapá-lo; (b) comentário que afirma uma garantia envelhece junto com o modelo, e
comentário desatualizado que autoriza a operação errada é pior que comentário ausente.

---

# Achado da entrega de 20/08/2026 — T-10, extrato completo

## I-34 — O plano da T-10 vazaria os plásticos alheios de um cartão dividido — **RESOLVIDO em 20/08/2026, antes de chegar à produção**

Diferente do I-24 e do I-33, este não veio de um relato de uso: foi encontrado na revisão do
plano de implementação, contra o que a V19/V20 já garantiam na tela. Fica registrado no
mesmo formato dos outros dois porque a lição é reutilizável — a próxima função
`SECURITY DEFINER` sobre lançamento pode cometer o mesmo erro, e nenhum teste de schema o
acusaria.

### O sintoma

Não houve sintoma em produção. O sintoma foi textual: o plano de execução (`docs/desenho-t10-relatorios.md` e a instrução de implementação da V22) mandava resolver `ambiente_da_aba` da linha alheia só a partir de `conta_ambiente`, **preferindo `origem = true`** — a mesma regra que `app_extrato_da_conta` (V16) usa para uma conta comum dividida.

### A causa

Conta comum e conta de cartão não têm a mesma forma de vínculo. Aceitar um **plástico**
vincula a **conta do contrato inteiro** ao ambiente de quem recebeu (`V19__compartilhamento_por_plastico.sql:226`,
função `app_aceitar_convite_de_plastico`) — é assim que o lançamento dela tem onde morar
(chave composta de B-D2). A V19/B-D106 fez dessa vinculação uma **consequência** do
recebimento de um plástico, não mais a **concessão**: quem recebeu um cartão vê só os
plásticos liberados para ela, nunca os dez do contrato.

A regra "preferindo `origem = true`" ignora essa diferença. Aplicada como estava escrita, o
extrato da T-10 entregaria, na aba de quem recebeu **um único** plástico, uma linha para
**cada compra de todos os plásticos daquele cartão** — mascaradas (sem descrição nem
categoria, pela regra de B-D89), mas com valor, data, conta e quem. É exatamente o que
B-D106 tirou da tela e o que B-D110 recusa em palavras: *"ela vê o extrato do PLÁSTICO
dela, não o do contrato"*.

### O que o banco não pegou

Nada — e a razão importa mais que o achado em si. Nenhuma `CHECK`, `FK` ou política de RLS
impediria a versão errada: a política de `lancamento` estava e continua correta, e a função
proposta é `SECURITY DEFINER` **por definição** — ela existe justamente para atravessar a
política. Um teste de schema não vê a diferença entre a consulta certa e a errada, porque
as duas são sintaticamente válidas e as duas *compilam* contra o mesmo banco. A única
defesa possível é alguém comparar a consulta nova contra o modelo de negócio (B-D106) antes
dela virar SQL.

### A correção (aplicada na V22, antes de qualquer deploy)

`app_extrato_completo` reproduz a mesma regra de `app_extrato_da_fatura` (V20): a linha
alheia entra na minha aba quando `origem = true` **OU** a conta não é de cartão **OU** o
plástico está liberado para o meu ambiente (`app_emitidos_liberados`, V19). Conta comum
continua resolvendo por `origem` sozinho — ali o vínculo já diz tudo, porque não existe
unidade menor que a conta. Detalhe em `docs/security-definer.md`, seção "Funções da V22".

### O que ficou pendente

Nada no código desta entrega. A pendência é de processo: **toda função `SECURITY DEFINER`
nova que liste lançamento de conta compartilhada precisa reproduzir o recorte por plástico
quando a conta puder ser de cartão** — e isso não é verificável por teste de schema, só por
revisão contra `decisoes.md` §4n/§4o. Vale conferir as três funções existentes
(`app_extrato_da_conta`, `app_saldo_da_conta`) contra este critério na próxima vez que
alguma delas for tocada; não foram revisadas de novo aqui porque nenhuma mudou.

### A lição

O impasse de uma `SECURITY DEFINER` nova não é só "ela atravessa a RLS" — é "ela precisa
redecidir, em SQL, uma regra de negócio que em outro lugar do sistema é imposta por uma
combinação de tabelas diferentes". Copiar a regra de uma irmã parecida (conta comum) sem
conferir se a irmã se aplica ao caso novo (conta de cartão) é como copiar um `WHERE` que
parece certo e não é — a diferença só aparece quando alguém sabe a história por trás da
V19, e é por isso que este documento existe.

---

# Achados do `qa-adversarial` de 20/08/2026 — T-10, extrato completo (segunda passada)

Origem: os testes escritos contra o plano da T-10, antes de qualquer relato de uso — mesma
classe do I-34: código examinado antes do primeiro byte real chegar a alguém. Os dois abaixo
são independentes entre si e independentes do I-34.

## I-35 — Nome de aba duplicado sumia com um ambiente inteiro do `.xlsx`, sem aviso — **RESOLVIDO em 20/08/2026, antes de chegar a produção**

### O sintoma

Dois ambientes com o **mesmo nome** produziam duas abas com o mesmo `<sheet name="…">` no
OOXML. O formato exige nome único: o Excel recusa a pasta inteira, e uma leitora mais
tolerante fica só com a primeira das duas — um ambiente inteiro desaparecendo do extrato,
sem nenhuma mensagem.

### A causa

A unicidade era decidida sobre uma string **diferente da que chegava no arquivo**. O corte de
31 caracteres era feito em `char` Java, e um emoji caindo bem na posição 30 era partido ao
meio; o `fastexcel` **descarta em silêncio** o que não é XML 1.0 válido — confirmado no
bytecode, `XmlEscapeHelper.appendEscapedCodePoint` faz `return` puro para o meio par
surrogate que sobra do corte. Dois nomes de ambiente diferentes, depois desse descarte,
podiam virar o mesmo texto gravado — e o desempate acontecia **antes** de saber disso.

### O que o banco não pegou

Nada — e não tinha como pegar. O nome da aba não é dado gravado; nasce da formatação, na
hora de montar o arquivo. O que faltava não é constraint nenhuma: é **conferência no
escritor**, que simplesmente não existia. É uma família de defeito que nenhuma `CHECK`
alcança — invariante sobre dado **derivado na saída**, não sobre o que está na tabela.

### A correção (aplicada em 20/08/2026)

A regra "o que pode virar nome de aba" mudou de dono: passou a morar em `EscritorXlsx`
(`raspybank-app/src/main/java/com/raspybank/app/servico/EscritorXlsx.java`), porque é
propriedade do **formato** — deixá-la no montador faria cada relatório futuro redescobri-la
sozinho. O escritor deixou de descartar em silêncio: agora **confere todos os nomes antes do
primeiro byte** (`conferirNomes`) e recusa a planilha inteira se algum nome vier vazio, se
algum nome não for **idêntico** ao próprio saneamento (`nomeDeAbaSaneado`), ou se duas abas
colidirem ignorando caixa. A conferência é antes de abrir a pasta de propósito: estourar na
quarta aba entregaria um `.xlsx` truncado, que é pior de diagnosticar do que um erro limpo
antes de começar.

Do lado de quem chama, `ExtratoCompletoMontador.saneado()` passou a rodar **antes** do
desempate (`unico()`), e não depois — é o próprio texto final que entra no conjunto de
nomes usados, e não um texto intermediário que o escritor ainda ia mudar por baixo.

**Três casos latentes** que o mesmo defeito cobria, e que só apareceram ao escrever o teste
vermelho:

1. Ambiente cujo nome fosse **só** um surrogate solto produzia `<sheet name="">` — e uma
   aba sem nome faz a **pasta inteira** deixar de abrir, pior que o caso original.
2. `unico()` tinha a **segunda cópia** do mesmo bug, no corte que abre espaço para o sufixo
   `(2)` quando dois nomes colidem depois do corte de 31.
3. Caractere de controle no meio do nome (ex.: um ambiente digitado com um tab perdido no
   meio) virava um nome diferente do que a pessoa via na tela, e podia colidir com um
   ambiente realmente chamado assim.

Guardado por `ExtratoCompletoNomeDeAbaTest`.

### A lição

Quando a aplicação desempata nomes, ela precisa desempatar **o texto final** — o que o
formato realmente vai gravar —, nunca um texto intermediário que ainda vai passar por outro
corte ou descarte antes de virar bytes. É a mesma lição do I-24 (duas leituras do mesmo dado
discordando em silêncio), aplicada à escrita: aqui eram duas *decisões* sobre o mesmo dado
— desempatar e escrever — discordando sobre qual string era a de verdade.

## I-36 — O 401 respondia com corpo vazio, em todo endpoint protegido, desde sempre — **RESOLVIDO em 20/08/2026**

Diferente do I-35: **não é defeito da T-10**, é do sistema inteiro, e a T-10 só o expôs.

### O sintoma

Todo `401` de sessão ausente ou expirada respondia com **zero bytes** — em qualquer endpoint
fora de `/api/auth/**`, desde a Fase 4. `docs/api.md` promete, na §1 (B-T1), `{"erro": …}`
para todo erro; a promessa era falsa há semanas, e ninguém tinha percebido porque nenhum
teste conferia o **corpo** de um 401 — só o status.

### Como apareceu

A T-10 acrescentou, na §6c de `docs/api.md`, a linha `401 | sem token, ou token expirado |
corpo é JSON de erro, nunca um .xlsx vazio`. O `qa-adversarial` escreveu o teste que cobrava
essa linha (`ExtratoCompletoFaixaTest.semSessaoRespondeJsonDeErro`) — e ele falhou contra o
código como estava. A entrega documentou o contrário do que o sistema fazia, e o teste
pegou a divergência antes de ela virar arquivo `.xlsx` de zero byte na pasta de downloads de
alguém.

### O que o banco não pegou

Nada — é contrato HTTP, não dado gravado. O que faltava era um teste que conferisse **corpo**
de 401, e não só `getStatusCode()`. Os testes de 401 pré-existentes conferiam só o status:
`CategoriaApiTest`, `ContaApiTest`, `CartaoApiTest`, `LancamentoApiTest`,
`MapaDeGastosApiTest`, `FormaPagamentoApiTest`, `TransferenciaApiTest`, `PerfilApiTest` — nem
um deles teria acusado o corpo vazio, porque nenhum olhava para ele.

### A correção (aplicada em 20/08/2026)

Classe nova, `PontoDeEntradaSemSessao`
(`raspybank-app/src/main/java/com/raspybank/app/seguranca/PontoDeEntradaSemSessao.java`),
registrada em `SegurancaConfig` no lugar do `HttpStatusEntryPoint` do Spring Security (que
por natureza responde sem corpo). Devolve, para token ausente, malformado **ou** expirado —
os três indistintamente, de propósito —
`{"erro":"Sessão expirada ou ausente. Entre novamente."}`, `Content-Type:
application/json;charset=UTF-8`. Sem `WWW-Authenticate`: o cabeçalho faria o navegador abrir
a caixa de usuário/senha dele por cima da tela do sistema. Detalhe registrado em
`docs/api.md` §1.

### O que ficou pendente

Nada de código. Fica o alerta de processo: regra de autorização nova nesta cadeia (papel,
escopo, `@PreAuthorize`) precisa trazer junto um `AccessDeniedHandler` — hoje não existe
porque a única regra desta cadeia é `authenticated()`, e quem passa por ela nunca leva 403
daqui. Sem o handler, o mesmo buraco reabre para o 403.

### A lição

Teste que confere status e não confere corpo é metade de teste, para um contrato que promete
os dois. A promessa de `docs/api.md` — "todo erro devolve `{"erro": …}`" — vale desde
B-T1/B-T2 e nunca tinha sido verificada de ponta a ponta; bastou um teto novo (a T-10) exigir
o corpo explicitamente para o buraco antigo aparecer.

---

# Situação em 26/07/2026

**Resolvidos:** I-01, I-02, I-03, I-05, I-09, I-10, I-11, I-12, I-14, I-15, I-16, I-17, I-19, I-20, I-21, I-22.

**Abertos, com dono e momento:**

| Item | Assunto | Quando |
|---|---|---|
| I-04 + I-13 | Canal auto-declarado (`Canal.WEB` fixo, header `X-Canal`) | Primeiro item do trabalho do bot Telegram. B-D6 preparou o terreno: o canal já viaja no contexto do RLS |
| I-06 | Argon2id (requisitos) × BCrypt 12 (código) | Antes de publicar para terceiros |
| I-07 | Estados da Fatura | Desenho da V12 |
| I-08 | ~~Entrada de usuário em ambiente existente~~ | **Resolvido na V15**, 29/07/2026 — compartilhamento de ambiente (§4j), implementado |
| I-18 | Tela de sessões ativas | Depois do mínimo, antes de exposição à internet |
| I-23 | ~~Saldo parcial em conta compartilhada~~ | **Resolvido em código**, 29/07/2026 — B-D78/B-D79 na V15, B-D85/B-D87/B-D96 nas V16 e V17 (quarta exceção de B-D19, com porteiro) |
| P-T8 | Token em `localStorage` × cookie `httpOnly` (`mapa-telas.md`) | Antes de expor à internet |

Nenhum dos abertos bloqueia a V10.
