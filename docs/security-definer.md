# RaspyBank — Inventário de Funções SECURITY DEFINER

**Versão:** 1.6
**Data:** 20 de agosto de 2026
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

### `auth_cadastrar_usuario(nome, email, hash, telegram)` — V4, **assinatura mudada na V18**
Grava usuário completo, incluindo `senha_hash`. Existe por dois motivos que se somam: (1) no cadastro não há identidade na sessão, o RLS recusaria o INSERT; (2) desde a V8 o usuário de aplicação não tem privilégio sequer de leitura em `senha_hash` — esta função é o único caminho de escrita do hash.

**A V18 acrescentou `telegram_id`, e o quarto parâmetro é consequência do mesmo impasse do item (1):** gravar o usuário e completar o Telegram numa segunda instrução não funciona, porque `pol_usuario_escrita` (`id = app_usuario_id()`) recusa — ainda não há identidade. O valor tem de entrar na mesma inserção.

Foi `DROP` + `CREATE`, e não `CREATE OR REPLACE`: mudar a lista de argumentos cria uma função **nova** em Postgres, e a de três argumentos continuaria viva — duas portas para o cadastro, uma delas esquecendo o Telegram em silêncio, e duas linhas neste inventário onde deve haver uma.

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

## Funções do compartilhamento (V15)

### `app_ambientes_proprios()` — V15
Irmã de `app_ambientes_do_usuario()`, filtrando por `dono` (B-D75). Mesma razão de DEFINER: alimenta políticas que consultam `usuario_ambiente`, que também tem política. Aparece em três lugares de porta: quem concede acesso (`pol_ua_conceder`), quem remove (`pol_ua_remover`) e quem altera o ambiente (`pol_ambiente_porta`) — além do `WITH CHECK` apertado de `conta_ambiente` (B-D78).

### `app_contas_proprias()` — V15
Irmã de `app_contas_do_usuario()`, atravessando `conta_ambiente` a partir dos ambientes em que o usuário é dono. É a **segunda condição** do vínculo apertado por B-D78: vincular e desvincular conta exige ser dono dos dois lados — sem ela, a convidada levaria a conta compartilhada para o ambiente pessoal e lançaria de lá, invisível ao dono.

### `app_membros_dos_meus_ambientes()` — V15
Os usuários que dividem algum ambiente com o usuário da sessão. Alimenta `pol_usuario_leitura` ("eu, e quem divide ambiente comigo") — a regra nova foi dita **na política**, não numa função de leitura por fora, porque ali não havia impasse: o vínculo existe, a política é que não o consultava (ver §4j, acréscimo *a*). Esta função existe só para a política não recursar.

### `app_usuario_por_email(email)` — V15
A função estreita do convite, e **o impasse de B-D19 na forma pura**: quem está sendo convidado, por definição, ainda não divide ambiente com quem convida — a linha dele só se tornaria visível pelo vínculo que a operação vai criar. Devolve **só o id** (nem nome, nem status): nome e e-mail aparecem depois do vínculo, pela política normal. É também o oráculo de enumeração aceito em B-D81, e por isso o escopo mínimo importa em dobro.

## Funções do compartilhamento de CONTA (V16)

Cinco funções novas e **duas naturezas diferentes**. A distinção importa mais que a contagem: a primeira é o impasse de sempre; as outras quatro são a **quarta exceção** do critério e as primeiras em consulta de **leitura**.

### `app_aceitar_convite_de_conta(convite_id, ambiente_id)` — V16
O impasse clássico, numa forma nova: ela precisa inserir um vínculo entre uma conta que **não é dela** e um ambiente que é. O `WITH CHECK` de `pol_ca_vincular` exige conta própria dos dois lados — e exige por um bom motivo, que é impedir captura de conta alheia por quem tenha o UUID (B-D18).

O que separa aceite de captura **não está na linha inserida**; está no convite que existe antes dela. Por isso a função **lê o convite** em vez de receber a conta como parâmetro: quem chama não escolhe o que aceitar, só qual dos convites dela resolver.

Ela confere duas coisas antes de gravar, e nenhuma é decorativa: que o convite é **dela** (sem isso, seria "aceite o convite de qualquer um") e que o ambiente de destino é dela **como dona** — aceitar dentro de um ambiente recebido emprestado na V15 espalharia a conta para o dono daquele ambiente, que não participou de nada disto.

### `app_revogar_conta_compartilhada(conta_id, usuario_id)` — V16
A irmã do aceite, e pelo motivo mais curioso do inventário: **o dono precisa encerrar uma linha que ele não pode ver.** `pol_ca_leitura` mostra a cada um só o próprio lado do vínculo, e isso é deliberado (B-D90 — em qual ambiente dela a conta entrou é organização da vida dela). A política `pol_ca_encerrar` autoriza a escrita, mas nenhum UPDATE comum alcança a linha: JPA precisa ler antes, e SQL nativo precisaria nomear o ambiente dela.

A alternativa era alargar `pol_ca_leitura` para "ou a conta é minha". Ela entregaria ao dono os **ids dos ambientes dela** — pouco em aparência, e o suficiente para contar quantos são e correlacionar entre contas. A função é mais estreita: recebe a **pessoa**, nunca o ambiente, e devolve só quantos vínculos encerrou.

### `app_saldo_da_conta(conta_id)` e `app_extrato_da_conta(conta_id, inicio, fim)` — V16
**A quarta exceção (B-D96), e o impasse é de outra forma:** por construção uma pessoa não pode ver os lançamentos da outra pela política — e mesmo assim precisa **somá-los**. Sem isto os dois veem saldos diferentes na mesma conta e cada um confere o próprio número contra o mesmo extrato do banco.

Não é conveniência de camada: nenhuma política resolveria, porque a política correta é justamente a que esconde o lançamento alheio. O que existe é uma pergunta legítima — *"quanto tem nesta conta?"* — cuja resposta atravessa uma fronteira que o resto do sistema não deve atravessar.

**O porteiro é o que impede a exceção de virar porta dos fundos:** a primeira linha de cada uma confere `conta_id IN (SELECT app_contas_do_usuario())` e levanta exceção se não. Sem ele, DEFINER significaria "leia qualquer conta do sistema, basta ter o UUID" — exatamente o furo que B-D18 fechou em `conta_ambiente`.

`app_extrato_da_conta` carrega, além disso, a **fronteira de privacidade do modo inteiro** (B-D97): `descricao` e `categoria` do lançamento alheio não saem do banco — a função devolve `NULL` nessas colunas via `CASE WHEN meu`. Não é filtro de exibição; é dado que a aplicação nunca recebe. A tela não tem como vazar o que nunca chegou nela, e um JSON distraído no controlador não vira incidente de privacidade.

### `app_compartilhamentos_da_conta(conta_id)` e `app_dono_da_conta(conta_id)` — V16
`pol_ca_leitura` mostra a cada um só o **próprio lado** do vínculo: o dono não enxerga a linha do ambiente dela, e ela não enxerga a do dele. Isso é desejado — em qual ambiente ela guardou a conta é organização da vida dela, e B-D90 já recusou expor isso ao dono quando recusou que ele escolhesse.

Sobram duas perguntas que a política não responde: *"com quem eu dividi esta conta?"* e *"de quem é esta conta que eu recebi?"*. As duas funções respondem **só a pessoa, nunca o ambiente**.

Elas devolvem **nome e e-mail**, e não apenas o id, por uma escolha de escopo: a alternativa era alargar `pol_usuario_leitura` para "quem divide conta comigo", e isso abriria a linha cadastral por um caminho novo e permanente. Devolver o nome dentro de uma função com porteiro é mais estreito. O custo é um nome repetido em duas assinaturas.

### O que a V16 mudou nas funções que já existiam
- **`app_contas_do_usuario()`** passou a exigir vínculo **ativo** (`encerrado_em IS NULL`). O filtro da revogação lógica (B-D93) mora aqui, num lugar só, e não espalhado por política e consulta — esquecê-lo em um lugar ressuscitaria o acesso em silêncio.
- **`app_contas_proprias()`** passou a exigir `origem` além de `dono` (B-D92). Era o **Achado 1** da V16: depois do compartilhamento, a conta do dono está ligada ao ambiente dela, que ela é dona — e sem `origem`, "conta própria" passava a incluir a conta emprestada. Com isso o RLS deixaria a convidada desvincular a conta do ambiente de quem a criou, e repassá-la a um terceiro.
- **`app_criar_conta()`** passou a marcar o vínculo como `origem`. Sem isso a conta nova nasceria sem origem, e `ux_ca_uma_origem` não acusa ausência — o defeito apareceria dias depois, numa conta que nem o criador consegue renomear.

## Funções do cartão compartilhado (V17)

### `app_total_da_fatura(fatura_id)` e `app_extrato_da_fatura(fatura_id)` — V17
As duas últimas da quarta exceção, anunciadas em B-D96 e criadas aqui — a V16 deliberadamente não as criou para não deixar código morto esperando chamador.

O impasse é o de `app_saldo_da_conta`, e o **sintoma de não resolvê-lo é pior**: um saldo divergente é confuso, uma fatura que parece menor do que é faz o pagamento sair curto, e a pessoa descobre com juros.

`app_total_da_fatura` deriva a conta do cartão **da própria fatura** em vez de recebê-la por parâmetro: com ela por fora, dois argumentos incoerentes produziriam um total silenciosamente errado. O filtro por essa conta não é redundante — o pagamento marca a fatura nas duas pernas (B-D59), então sem ele a saída da conta pagadora entraria no total de compras.

`app_extrato_da_fatura` carrega o mesmo recorte de B-D89/B-D97 e uma coluna que aqui pesa mais: a **parcela** (B-D102). O titular do plástico não é recortado, e é coerente com B-D103 — o contrato é do dono, e ele conhece os próprios emitidos.

## Funções do plástico compartilhado (V19)

Cinco funções, e a migração inteira existe porque a V17 errou a unidade: dividia a conta do contrato, entregando os dez plásticos (§4n, B-D106).

### `app_emitidos_liberados()` — V19
Irmã de `app_contas_do_usuario`, e a razão de DEFINER é a de sempre: consulta `usuario_ambiente`, que tem política, e alimenta política. Ela sustenta a distinção que é o coração da V19 — **duas origens de visibilidade de plástico**: nasceu num ambiente meu (vejo todos os do contrato) ou foi compartilhado comigo (vejo aquele, e mais nenhum).

### `app_aceitar_convite_de_plastico(convite_id, ambiente_id)` — V19
O impasse do aceite, com **um passo a mais**: além do vínculo da conta do cartão — que ela não poderia inserir, porque `pol_ca_vincular` exige conta própria dos dois lados — há o vínculo do plástico, e `cartao_emitido_ambiente` **não tem política de escrita nenhuma**. Não existe caminho por fora das funções, e isso é deliberado.

As duas inserções numa transação: vínculo de conta sem plástico liberado deixaria ela vendo um cartão sem meio de pagamento; plástico liberado sem vínculo de conta deixaria o lançamento dela sem onde morar.

### `app_revogar_plastico_compartilhado(emitido_id, usuario_id)` — V19
Mesmo motivo da irmã de conta — a linha a encerrar está num ambiente que o dono não pode ver (B-D90) — com um passo próprio: **quando não sobra nenhum plástico daquele cartão, o vínculo da conta também se encerra**. Sem isso ela ficaria com um cartão na tela sem meio de pagamento nenhum, e com leitura do contrato inteiro — exatamente o que B-D106 tirou.

Uma função para **dois significados** (o dono revoga, ou a pessoa sai), no idioma de B-D77: a regra do "se não sobrar plástico" é a mesma nos dois casos, e duplicá-la numa segunda função seria a chance de divergirem.

### `app_compartilhamentos_do_plastico(emitido_id)` — V19
Mesma forma da irmã de conta, pelos mesmos dois motivos: o dono não vê o ambiente dela, e o nome de quem foi convidado não sai por `pol_usuario_leitura` antes de existir vínculo.

### `app_total_do_plastico(emitido_id, fatura_id)` — V19
O único número de fatura que quem recebeu um plástico vê (B-D110) — o do contrato é de quem paga (B-D107). O porteiro aceita **as duas origens**: o dono do contrato e quem recebeu aquele plástico; sem a segunda, a tela dela não teria número.

### O que a V19 mudou nas que já existiam
- **`app_extrato_da_fatura`** passou a **recortar por plástico**, e o filtro deriva da concessão em vez de ser parâmetro: o dono vê tudo (as mini faturas), quem recebeu vê as linhas dos plásticos que recebeu.
- **`app_convites_de_conta_pendentes`** passou a dizer se o convite é de conta ou de plástico. Foi `DROP` + `CREATE`: acrescentar colunas a um `RETURNS TABLE` muda o tipo de retorno, e o Postgres recusa a substituição — a primeira versão da migração falhou exatamente aí.

## Funções da V20

### `app_nome_do_banco_do_cartao(cartao_id)` — V20
Devolve **só o nome** do banco do contrato, para quem recebeu um plástico (B-D112). A conta do banco é de quem abriu o cartão e não é visível para quem recebeu — daí o impasse.

O escopo mínimo é o ponto: não devolve saldo, não devolve formas de pagamento, e não cria direito nenhum sobre a conta. `exigirContaNoAmbiente` continua recusando qualquer lançamento que a aponte; o nome existe para a tela poder agrupar "banco → cartões", que é como se pensa em cartão (B-D61).

### O que a V20 mudou
- **`app_extrato_da_fatura`** ganhou o **ambiente por parâmetro** e passou a recortar por ele (B-D111). O ambiente não é palpite da tela: a função confere que ele é de quem chama antes de usar, senão o parâmetro viraria um jeito de pedir o recorte de um ambiente alheio. Foi `DROP` + `CREATE` — a lição da V19 sobre `RETURNS TABLE` já estava aprendida.

## Funções da V22

### `app_extrato_completo(inicio, fim)` — V22
A quarta da família de `app_extrato_da_conta`, e o mesmo impasse: `pol_lancamento_ambiente` é `ambiente_id IN (SELECT app_ambientes_do_usuario())`, então consulta comum em `lancamento` **nunca** traz a linha alheia de uma conta ou de um plástico dividido. Isso está certo para a tela — a T-08 lista por consulta comum e por isso nunca mostra linha de outra pessoa. Mas o `.xlsx` da T-10 existe para **fechar com o extrato do banco** (B-D117): sem as linhas dela, a soma do arquivo diverge do que a T-05 mostra na mesma conta, e quem abre o arquivo conclui — com razão — que ele está errado.

**É a única da lista SEM porteiro, e a ausência é o argumento.** Todas as irmãs começam com `IF p_alguma_coisa NOT IN (SELECT ...) THEN RAISE`, porque recebem um identificador que precisa ser conferido. Aqui os dois parâmetros são **datas**: não apontam para ninguém e não carregam autorização nenhuma. Não existe "peça o extrato de fulano" porque não existe parâmetro que diga fulano — tudo deriva de `app_usuario_id()`: os ambientes dele, os vínculos de conta dele, os plásticos liberados para ele. Sem identidade na sessão a função devolve **zero linha**, e o `IF app_usuario_id() IS NULL THEN RETURN` na primeira linha existe para dizer isso em voz alta, não porque as consultas precisem dele.

**O que ela deixa passar, exatamente:** a linha de outra pessoa em conta ou plástico que eu alcance, com valor, data, conta, forma de pagamento, parcela e **quem** — e **sem** descrição, categoria e subcategoria, que são a máscara de B-D89/B-D97 e não saem do banco. Nada além dessas três é mascarado, porque valor e data são justamente o que faz a soma fechar.

**O recorte por plástico é herdado da V20 (B-D110/B-D111), e sem ele a função vazaria.** Aceitar um plástico vincula a **conta do contrato** ao ambiente de quem recebeu (V19). Esse vínculo, sozinho, entregaria no arquivo dela as compras de **todos** os plásticos daquele cartão — mascaradas, mas com valor e data. É exatamente o que B-D106 tirou da tela. A regra aplicada é a mesma de `app_extrato_da_fatura`: no ambiente onde o cartão **nasceu**, tudo; nos outros, só os plásticos liberados **para aquele ambiente**. Conta comum não entra nessa conversa — lá o vínculo já diz tudo. Consequência assumida, idêntica à da V20: as duas pernas do pagamento da fatura não têm plástico (B-D59), então o pagamento feito pelo dono não aparece na aba de quem só recebeu um plástico — e está correto, porque ela não paga a fatura (B-D107).

**`ambiente_da_aba` é a coluna que a máscara obriga a existir.** A linha alheia nasceu no ambiente da outra pessoa, e aquele ambiente não é aba nenhuma do meu arquivo. A aba dela é o ambiente **meu** pelo qual eu enxergo aquela conta, resolvido por `conta_ambiente` e preferindo `origem = true` — quando a conta nasceu num ambiente meu, é ali que o dinheiro dela mora para mim. Linha minha vai direto pelo `lancamento.ambiente_id` (B-D2).

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
| `auth_cadastrar_usuario` | V4 (V18) | Pré-identidade | Única escrita de `senha_hash` e de `telegram_id` no cadastro |
| `auth_buscar_credenciais` | V4 | Pré-identidade | Única leitura de `senha_hash` |
| `auth_criar_ambiente_inicial` | V5 (V10) | Pré-identidade | Ambiente inicial + sistêmicas, atômico |
| `auth_registrar_evento` | V6 | Pré-identidade | Auditoria de autenticação, SÓ ela |
| `auth_ambientes_do_usuario(uuid)` | V7 | Pré-identidade | Ambiente para o JWT |
| `app_criar_conta` | V10 | Com sessão | Impasse de vínculo: conta + `conta_ambiente` |
| `app_criar_ambiente` | V14 | Com sessão | Impasse de vínculo: ambiente + `usuario_ambiente` + sistêmicas |
| `fn_criar_categorias_sistemicas` — *não é DEFINER* | V10 | Interno | As sistêmicas de B-D13 |
| `fn_auditar` | V10 | Gatilho | Auditoria de domínio com canal |
| `fn_publicar_evento_lancamento` | V10 | Gatilho | Outbox do lançamento |
| `app_ambientes_proprios()` | V15 | Com sessão | Visibilidade de porta (dono) |
| `app_contas_proprias()` | V15 | Com sessão | Trava dupla de B-D78 |
| `app_membros_dos_meus_ambientes()` | V15 | Com sessão | `pol_usuario_leitura` sem recursão |
| `app_usuario_por_email` | V15 | Com sessão | Convite: e-mail → id, e SÓ o id |
| `app_aceitar_convite_de_conta` | V16 | Com sessão | Impasse de vínculo: o convite é que autoriza |
| `app_revogar_conta_compartilhada` | V16 | Com sessão | Encerrar uma linha que o dono não pode ver |
| `app_saldo_da_conta` | V16 | Com sessão | **Quarta exceção**: soma que atravessa ambientes |
| `app_extrato_da_conta` | V16 | Com sessão | Idem, e a fronteira de privacidade de B-D89/B-D97 |
| `app_compartilhamentos_da_conta` | V16 | Com sessão | Com quem dividi — a pessoa, nunca o ambiente |
| `app_dono_da_conta` | V16 | Com sessão | De quem é a conta que eu recebi |
| `app_contas_nao_emprestadas` | V16 | Com sessão | Dinheiro da conta (B-D76) × porta dela (B-D91) |
| `app_convites_de_conta_pendentes` | V16 | Com sessão | O convite antes de a conta existir para quem recebe |
| `app_total_da_fatura` | V17 | Com sessão | Fatura que atravessa: pagamento curto é juros |
| `app_extrato_da_fatura` | V17 (V19) | Com sessão | Idem, com a parcela de B-D102; recorta por plástico |
| `app_emitidos_liberados` | V19 | Com sessão | As duas origens de visibilidade de plástico |
| `app_aceitar_convite_de_plastico` | V19 | Com sessão | Libera o plástico e vincula a conta do cartão |
| `app_revogar_plastico_compartilhado` | V19 | Com sessão | Revoga, e leva o cartão se não sobrar plástico |
| `app_compartilhamentos_do_plastico` | V19 | Com sessão | Com quem dividi este plástico |
| `app_total_do_plastico` | V19 | Com sessão | O único número de fatura de quem recebeu |
| `app_nome_do_banco_do_cartao` | V20 | Com sessão | Só o nome do banco, para agrupar na tela |
| `app_extrato_completo` | V22 | Com sessão | O arquivo da T-10: atravessa **todos** os ambientes, e a única sem porteiro |
