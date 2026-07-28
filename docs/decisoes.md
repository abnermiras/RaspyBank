# RaspyBank — Registro de Decisões Vigentes

**Versão:** 1.2
**Data:** 26 de julho de 2026
**Status:** Fonte de verdade. Quando qualquer documento, conversa ou código contradisser este registro, ESTE registro prevalece — ou é formalmente revisado.
**Origem:** consolida as decisões das sessões "Estrutura de requisitos funcionais" (20/07), "Reescrita do RaspyBank Systems v1.0" (22–23/07), "Modelo lógico e DER" (23/07), "Bloco A — refactor de enums" (23/07) e "Bloco de Domínio — varredura pré-V10" (26/07).

---

## Como usar este documento

1. **Antes de escrever qualquer migração ou serviço de domínio**, valide o desenho contra as decisões F1–F33 e os princípios. Trecho de código sem decisão correspondente é invenção ou buraco de documentação — os dois merecem parada.
2. **Decisão nunca é apagada.** Quando superada, vai para a seção de Revisões com o motivo. O motivo da mudança é tão importante quanto a decisão final.
3. **Chat decide, repositório registra.** Decisões novas tomadas em conversa entram aqui no mesmo commit que as implementa.

---

# 1. Princípios

| # | Princípio |
|---|---|
| P1 | **Nenhuma entidade guarda saldo, total ou agregado.** O lançamento é a fonte única de verdade; todo saldo é calculado. Não há o que reconciliar quando o dado não existe em dois lugares. |
| P2 | **Valor no banco == name() do enum Java.** Sem campo paralelo, sem conversor. A conversão enum→texto existe num único ponto por fluxo, na borda com o banco. (Fechado no Bloco A, 23/07.) |
| P3 | **Migração primeiro, código depois.** O banco aprende o valor novo antes do Java enviá-lo. Na ordem inversa, existe uma janela em que o código grava valor que o CHECK rejeita. |

# 2. Decisões de fundação (Fase 0 / Fase 4)

| # | Decisão | Motivo resumido |
|---|---|---|
| A01 | Monólito modular com satélites extraíveis; **não** microserviços | Uma pessoa desenvolvendo; extração futura viabilizada por fronteiras policiadas |
| A02 | Maven multi-módulo **por contexto de negócio** (identidade, ambiente, auditoria, shared, app; futuros: lancamento, cartao...) | Módulo por camada técnica não dá ao ArchUnit fronteira de negócio para policiar e inviabiliza extração de satélites |
| A03 | ArchUnit valida as fronteiras entre módulos no build (`make gate`) | Fronteira sem verificação automática é convenção, não arquitetura |
| A04 | PostgreSQL 18.4 em Docker; **dois usuários de banco**: `raspybank_owner` (migrações) e `raspybank_app` (aplicação) | Privilégio mínimo; a aplicação não pode alterar schema |
| A05 | Chave primária **UUIDv7**, gerado pelo banco via `DEFAULT uuidv7()` | Sequencial vaza tamanho da base e permite adivinhar vizinhos em URL; UUIDv4 fragmenta índice; v7 é ordenado por tempo |
| A06 | Flyway para migrações, executado pela **aplicação Spring Boot na inicialização** | Migração versionada junto do código; `make up` sobe só o banco, quem migra é `make app` |
| A07 | Multi-tenancy por **Row Level Security**; identidade de sessão via `set_config('raspybank.usuario_id', ..., true)` | Filtro no banco, por política — nenhuma linha de código de aplicação filtra por usuário |
| A08 | **Tenant do RLS é o usuário**, não o ambiente; visibilidade por subquery (`app_ambientes_do_usuario()`, futura `app_contas_do_usuario()`) | Revisão da Fase 2: o caso real (ambiente pessoal + freelance + contas conjuntas compartilhadas com o cônjuge) exige visibilidade por vínculo, não por igualdade de campo |
| A09 | Padrão **Outbox** para eventos entre módulos; relay em processo agora, Redpanda na Fase 8; produtor não muda | Escrita do evento na MESMA transação da operação — ou os dois entram, ou nenhum |
| A10 | Hash de senha: **BCrypt strength 12** | Ver inconsistência I-06 (requisitos citavam Argon2id) |
| A11 | Sessão: JWT de acesso **15 min** (usuarioId + ambienteId embutidos) + refresh token opaco **rotativo de 30 dias** com **teto absoluto de 90 dias**, hash SHA-256 no banco, revogação de família inteira em reuso | Token longo não revoga; token curto sozinho inviabiliza uso; o par resolve os dois (R6). SHA-256 e não BCrypt no refresh: 256 bits de entropia não se adivinham, BCrypt só deixaria a renovação lenta |
| A12 | Primeiro ambiente criado **atomicamente no cadastro** (orquestração direta no OnboardingServico, não evento) | A pessoa loga imediatamente após cadastrar esperando ambiente pronto; consistência eventual aqui seria defeito intermitente. Regra: eventos para o que pode acontecer depois; orquestração para o que precisa estar pronto agora |
| A13 | Saldo de abertura é um **lançamento de abertura**, nunca campo | Consequência direta do P1 |
| A14 | Auditoria de autenticação roda em transação **própria** (`REQUIRES_NEW`) | Registro de tentativa de login sobrevive mesmo se a operação principal falhar — tentativa fracassada é o que mais interessa auditar |
| A15 | Entidade `Usuario` **não mapeia** `senha_hash`; leitura/escrita do hash só via funções SECURITY DEFINER | V8 revogou SELECT da coluna do usuário de aplicação; mapear faria todo findById falhar. Documentação executável: construtor público de Usuario não existe |

# 3. Decisões do modelo de domínio (Fase 2 — F1 a F33)

| # | Decisão |
|---|---|
| F1 | Dinheiro é `numeric(15,2)`; `BigDecimal` na aplicação; `double`/`float` proibidos |
| F2 | Transferência são dois lançamentos ligados; saldo é sempre a soma sem exceção |
| F3 | Tipo fixo é texto com `CHECK`; valor que o usuário cria é tabela |
| F4 | `conta` é a âncora única; todo lançamento aponta para exatamente uma conta |
| F5 | Cartão e investimento são especializações de conta (PK = `conta_id`) |
| F6 | `conta.natureza` com `ATIVO`/`PASSIVO`; patrimônio é a diferença das somas |
| F7 | Conta não se exclui, se encerra |
| F8 | `categoria` e `subcategoria` em tabelas separadas; dois níveis por estrutura |
| F9 | Sistêmicas copiadas por ambiente; `ambiente_id` sempre `NOT NULL` |
| F10 | `categoria.codigo` identifica as sistêmicas; `sistemica` bloqueia edição |
| F11 | Lançamento carrega `categoria_id` e `subcategoria_id`, com FK composta; subcategoria opcional |
| F12 | `categoria.tipo` com `ENTRADA`/`SAIDA`/`AMBOS` |
| F13 | Ambiente novo nasce só com as sistêmicas |
| F14 | `data_competencia` e `data_caixa`; no cartão, `data_caixa` é gravada e mantida pela fatura *(tipo fixado em `date` por B-D8)* |
| F15 | Lançamento de cartão nasce `REALIZADO`; os demais nascem `PREVISTO` — **emendada por B-D9 (ver R9)** |
| F16 | Lançamento é editável e excluível, com auditoria; transferência propaga para o par |
| F17 | `cartao` é filha de `conta`, natureza `PASSIVO`; saldo devedor e limite disponível são cálculo |
| F18 | `cartao_emitido` referencia `cartao`; limite e fatura são do contrato |
| F19 | `fatura` sem coluna de total e sem coluna de status; só `fechada_em` |
| F20 | Faturas pré-geradas por ciclo; compra no dia do fechamento entra na seguinte |
| F21 | Fatura fechada é imutável; correção vira ajuste na fatura aberta |
| F22 | `responsavel_id` referencia usuário; `cartao_emitido` guarda responsável padrão |
| F23 | Parcelas geradas todas na compra, uma por fatura; resíduo na primeira |
| F24 | `regra_recorrencia` com horizonte móvel de 12 meses |
| F25 | Série editável em três modos; realizado e fatura fechada nunca são atingidos |
| F26 | Auditoria por gatilho lendo o contexto do RLS; autor nulo denuncia alteração externa |
| F27 | Todas as tabelas de domínio auditadas, exceto auditoria e outbox; colunas sensíveis excluídas |
| F28 | Outbox alimentado desde o primeiro lançamento, com relay em processo |
| F29 | `lancamento.observacao` em texto livre; anexo fora da v1.0 |
| F30 | Cotas e preço médio fora da v1.0 |
| F31 | ~~Lançamento guarda `categoria_nome`/`subcategoria_nome` gravados na criação~~ — **REVOGADA por B-D4 (ver R8)** |
| F32 | `criado_por` imutável; `responsavel_id` editável |
| F33 | Relatório de ambiente filtra por `lancamento.ambiente_id`; extrato de conta é por conta |

> Nota sobre F26 vs auditoria por serviço: os requisitos definiram auditoria escrita pela **camada de serviço** (para capturar o canal); a Fase 2 fechou F26 com **gatilho lendo contexto RLS** para as tabelas de domínio. Os dois convivem: gatilho cobre o domínio (autor nulo = alteração externa), serviço cobre autenticação (pré-identidade, via `auth_registrar_evento`). Se essa convivência gerar atrito na V10, revisar formalmente.

# 4. Decisões do Bloco A (23/07/2026)

| # | Decisão | Motivo |
|---|---|---|
| B-A1 | Vocabulário canônico de operação de auditoria: `CRIACAO`, `ALTERACAO`, `EXCLUSAO`, `ACESSO` | `ALTERACAO` (e não ATUALIZACAO) porque o CHECK da V2 já dizia assim — o enum se curva ao banco. `ACESSO` criado na V9: login não cria nada, auditá-lo como CRIACAO poluía a trilha |
| B-A2 | Vocabulário canônico de canal: `WEB`, `TELEGRAM`, `SISTEMA`, `DESCONHECIDO` | Espelho do CHECK `ck_auditoria_canal`. DESCONHECIDO na trilha é sinal de bug a investigar |
| B-A3 | Enum `Canal` sem campo `valorBanco`; `name()` direto | O campo paralelo divergiu do banco após a V8 ("web" vs 'WEB'). Duas fontes de verdade eventualmente discordam |
| B-A4 | Enum de status **por módulo** (`StatusUsuario`, `StatusAmbiente`), nunca compartilhado | Ciclos de vida diferentes que hoje coincidem por acaso; enum compartilhado forçaria evolução conjunta. Reforçado pelo próprio Maven/ArchUnit: módulos não se enxergam |
| B-A5 | Assinaturas do `AuditoriaServico` exigem `Canal` e `Operacao` (enums); literal de texto não compila | O compilador vira o guarda da porta. Conversão `.name()` só dentro do serviço |
| B-A6 | Status nas entidades via `@Enumerated(EnumType.STRING)`; ORDINAL proibido | STRING grava name() idêntico ao CHECK por construção; ORDINAL corromperia dados numa reordenação do enum |
| B-A7 | Conversão texto→enum na **borda**, uma única vez, via `valueOf`/`de(String)` | Falha com erro claro em vez de comparação de texto silenciosamente falsa |
| B-A8 | Falhas de login: três `log.debug` distintos (e-mail inexistente / senha errada / status não-ativo), resposta HTTP idêntica nos três | Log para diagnóstico interno; uniformidade externa contra enumeração de cadastros. E-mail nunca vai para log (dado pessoal); UUID basta |

# 4b. Decisões do Bloco C (23/07/2026)

| # | Decisão | Motivo |
|---|---|---|
| B-C1 | Testes de integração usam a **MESMA imagem** (`postgres:18.4`) e o **MESMO script de init** (`infra/postgres/init/01-app-user.sh`) da infra real, via Testcontainers | Testar contra outra versão é testar outro banco; reescrever o init nos testes deixaria o script real sem cobertura |
| B-C2 | Fumaça de RLS por **conexões JDBC cruas** (DriverManager), fora do pool da aplicação | O objeto sob teste são as POLÍTICAS, não o código Java. O teste controla `set_config` explicitamente; se o aspecto quebrar, quem acusa é o teste de fluxo HTTP, não este |
| B-C3 | Regra de domínio nasce em classe **testável sem Spring** (padrão: `TokenRenovacaoTest`) | Fatia mais grossa da pirâmide roda em milissegundos, sem Docker. Regra que não der para testar assim está no lugar errado. Vale para TODO serviço de conta/fatura/parcela da V10+ |
| B-C4 | O inventário de SECURITY DEFINER é **verificado por teste** (`MigracoesTest.inventarioSecurityDefinerConfere` confere `docs/security-definer.md` contra `pg_proc`) | Na primeira execução o teste já pegou o documento mentindo: `app_usuario_id()` nunca foi DEFINER (a V3 não a criou assim, e nem precisa — `current_setting` é legível por qualquer papel). Documento corrigido; o banco estava certo |
| B-C5 | Container de teste é **singleton estático** compartilhado pela suíte, não `@Container` por classe | Subir Postgres uma vez por JVM, não uma vez por classe; o Ryuk limpa ao final |
| B-C6 | `make gate` passa o ambiente por **`--env-file .env`**, não por lista de `-e` | A lista manual esqueceu `JWT_SEGREDO` e o gate nunca subia (achado N-01 da avaliação de 23/07). Com `--env-file`, variável nova no `.env` vale no gate automaticamente |

# 4c. Decisões do Bloco Pré-Telas (23/07/2026)

O bloco que resolve I-02, I-03, I-11, I-12, I-14, I-15 e I-17 — o contrato que o frontend vai consumir. Verificado por 8 cenários novos em `AutenticacaoFluxoTest` (ordens 8–15).

| # | Decisão | Motivo |
|---|---|---|
| B-T1 | **Contrato de erro único** (`TratadorGlobalDeErros`): todo erro responde JSON com a chave `erro` (frase exibível); validação acrescenta `campos` (campo → mensagem). 400 validação, **404 recurso inexistente**, 409 duplicata, 500 interno sem detalhe | As telas precisam de um formato previsível para TODO erro (I-12). Stacktrace/SQL nunca vazam; o detalhe vai para o log. A duplicata é detectada pelo SQLSTATE 23505 na cadeia de causas + nome da constraint — funciona igual para SQL nativo e repositório. **Emenda de 23/07/2026:** o 404 nasceu de um defeito real — sem tratador próprio para `NoResourceFoundException`, a captura geral de `Exception` transformava caminho inexistente em 500 com pilha no log (descoberto quando o navegador pediu `/favicon.ico`). Captura genérica precisa de exceções específicas antes dela |
| B-T2 | Os **401 de autenticação NÃO passam pelo tratador global**: os controladores os constroem à mão, num único método (`naoAutorizado()`), com corpo idêntico para token inválido, reuso detectado e sessão ausente | A uniformidade dos corpos 401 é decisão de segurança (B-A8), não de conveniência — qualquer diferença viraria oráculo para atacante |
| B-T3 | **Rotação de token atômica** (I-11): o "já foi usado?" é decidido por `UPDATE ... WHERE usado_em IS NULL` (`marcarUsadoSeInedito`), nunca por leitura+gravação. Na entidade, `vigente()` responde só o que não muda por corrida | Duas renovações simultâneas com o mesmo token: o banco serializa pela trava de linha, exatamente uma vence, a perdedora cai no ramo de reuso e revoga a família. Retry de rede simultâneo paga o preço do ladrão — postura "na dúvida, o pior" mantida. Teste: `renovacoesSimultaneasSoUmaVence` |
| B-T4 | **I-03 resolvido como opção (b)**: renovação normal NÃO gera auditoria (deliberado); **reuso detectado gera** (`ACESSO`, `{"evento":"reuso_token_renovacao"}`). O serviço devolve resultado `sealed` (`Sucesso`/`ReusoDetectada`/`Inválida`) e o compilador obriga o controlador a tratar o caso de reuso | Auditar cada renovação encheria a trilha a cada 15 min de uso; o evento de segurança relevante é o reuso — exatamente o que se quer encontrar na trilha depois. A resposta externa continua indistinguível (B-T2) |
| B-T5 | **Logout por dispositivo** (I-14): o JWT carrega a claim `fam` (família do token de renovação). `/logout` revoga só a família da sessão atual; `/logout-todos` revoga todas. Token antigo sem `fam` cai no comportamento antigo (todas) — o lado seguro do erro | O botão "Sair" das telas significa "sair DESTE dispositivo"; sair de todos é ação de segurança separada. A família já existia no modelo (V4) — faltava transportá-la no token |
| B-T6 | **Renovação preserva o ambiente declarado** (I-15): `/renovar` aceita `ambienteId` opcional; se o vínculo existe (checado via porta sem contexto — no renovar ainda não há identidade na sessão e o RLS devolveria vazio), o token novo o carrega; senão, **fallback silencioso para o primeiro** (a resposta sempre informa o ambiente efetivo). A renovação NUNCA falha por ambiente | Falhar queimaria o token já rotacionado e derrubaria a sessão por problema que não é de credencial. Quem perdeu o vínculo continua num ambiente que ainda é seu, informado na resposta |
| B-T7 | **Troca explícita de ambiente** em `POST /api/sessao/ambiente` (fora de `/api/auth/**`, que é `permitAll`): confere o vínculo com RLS ativo e emite novo token de acesso; o de renovação não muda. Vínculo inexistente → 403 | Trocar de ambiente é reemitir o recorte de dados, não recriar a sessão. O prefixo `/api/sessao` nasce protegido por padrão — a regra da casa para endpoint novo |
| B-T8 | Guard clause em `primeiroAmbienteDe` (I-02): usuário sem ambiente explode com `IllegalStateException` clara (→ 500), nunca token com ambiente nulo | Estado impossível por construção (A12); se acontecer, o sintoma deve ser barulhento, não um token esquisito |

# 4d. Decisões do Bloco de Domínio (26/07/2026)

A sessão que varreu tudo que estava pendente **antes** de escrever a primeira linha da V10. Origem: auditoria dos documentos contra o código, que encontrou três contradições nunca registradas (escopo da V10, ambiente do lançamento, agrupamento do mapa) e uma lacuna silenciosa (a lista de categorias sistêmicas nunca foi escrita).

## Modelo

| # | Decisão | Motivo |
|---|---|---|
| B-D1 | **A V10 vira duas migrações.** V10 = `categoria`, `subcategoria`, `conta`, `conta_ambiente`, `lancamento` (+ políticas RLS, gatilhos F26, outbox F28, sistêmicas F9/F13). V11 = `cartao`, `cartao_emitido`, `fatura`, `parcela`, `regra_recorrencia` (**renumerada para V12** em 27/07/2026, ver B-D30) | `decisoes.md` §6 e `mapa-telas.md` §4 descreviam V10 de formas incompatíveis, e **nenhuma das duas listas estava completa** — faltava `conta_ambiente` numa e `categoria`/`lancamento`/`parcela`/`regra_recorrencia` na outra. Migração aplicada é imutável: uma V10 gigante que erre em `fatura` obriga corretiva sobre tabela recém-nascida. A V10 agora é exatamente o mínimo aceitável, e a parte funda do domínio (F17–F23) espera a V11 |
| B-D2 | **`lancamento.ambiente_id` = ambiente ativo da sessão** no momento da criação. Restrição garante que a conta pertence àquele ambiente (via `conta_ambiente`). Sem campo de ambiente no formulário | Conta conjunta visível em dois ambientes (o caso que motivou R7) deixava F33 sem resposta: de qual ambiente é o gasto? A sessão já responde — "estou olhando a Casa, logo lancei um gasto da Casa". Torna o caso comum invisível e o caso raro (trocar de ambiente e relançar) explícito |
| B-D3 | **O mapa é chaveado por `categoria_id`/`subcategoria_id`; o nome é texto pendurado no id.** Renomear altera o texto em todos os lançamentos, passados inclusive, e **nunca** cria id novo | Uma categoria é uma coisa; o nome dela é um rótulo. Agrupar por rótulo partiria a mesma categoria em duas linhas depois de um rename, e o total dela ficaria dividido sem que nada de errado tivesse acontecido |
| B-D4 | **F31 revogada** (ver R8): sem `categoria_nome`/`subcategoria_nome` no lançamento. Em troca, **categoria e subcategoria não se excluem, se arquivam** (`arquivada_em`), espelho de F7 | Com B-D3, o nome exibido vem sempre do id — a cópia congelada só se justificaria se o id pudesse deixar de resolver. Com exclusão apenas lógica, ele sempre resolve, e a coluna congelada vira a mesma fonte-dupla que o I-01 acabou de eliminar. Arquivada, a categoria some do formulário de lançamento novo e continua nomeando o histórico inteiro |
| B-D5 | **`ambiente.status` é derrubada** (I-01). Ciclo de vida só por `excluido_em`: NULL = ativo, preenchido = arquivado, e anular reverte | Dois mecanismos para a mesma pergunta garantem que alguma query vai checar só um. `status` nunca foi gravado por ninguém; `app_ambientes_do_usuario()` já filtrava por `excluido_em`. Exclusão lógica **é** o arquivamento reversível — não faltava estado, sobrava coluna |
| B-D8 | **`data_caixa` e `data_competencia` são `date`**, nunca `timestamptz` | O banco guarda timestamps em UTC por arquitetura. Com regime de caixa (P-T2), um lançamento às 21h de 31/jan em São Paulo seria 01/fev em UTC e cairia no mês errado do quadro central. Data de dinheiro não tem hora, e `date` não tem fuso para errar |
| B-D9 | **O status do lançamento deriva da data de caixa** (emenda F15, ver R9): data no passado ou hoje → `REALIZADO`; data no futuro → `PREVISTO`. Sem campo de status no formulário. Cartão segue nascendo `REALIZADO` | F15 foi escrita antes de existir tela. Como o mínimo aceitável não tem cartão, F15 ao pé da letra fazia **todo** lançamento nascer PREVISTO — o usuário cadastraria dez gastos já pagos e teria que confirmar os dez, um a um, para a tela central sair do zero. O status deixa de ser pergunta e vira consequência do que a pessoa digitou |

## Categorias sistêmicas (F9/F10/F13 — a lista que faltava)

| # | Decisão | Motivo |
|---|---|---|
| B-D13 | As sistêmicas são **três**, identificadas por `codigo` (F10): `TRANSFERENCIA`, `AJUSTE`, `NAO_CLASSIFICADO`, todas `tipo = AMBOS`. `PAGAMENTO_FATURA` fica **reservado** para a V12 (era V11 antes de B-D30) | Só entra na lista o que o **código** referencia por `codigo` e não pode perder: `TRANSFERENCIA` porque F2 exige categoria para os dois lançamentos ligados; `AJUSTE` porque A13/T-05 fizeram do saldo de abertura um lançamento; `NAO_CLASSIFICADO` porque F11 torna a subcategoria opcional mas a **categoria obrigatória** — sem ela o bot do Telegram não teria destino válido para "gastei 50 no mercado". Sistêmica é cadeado, e cadeado só onde quebrar dói |
| B-D14 | **F13 mantida ao pé da letra**: ambiente novo nasce só com as três sistêmicas. Sem kit inicial de categorias editáveis | A estrutura de gastos é pessoal. Entregar "Moradia, Lazer, Assinaturas" pronto economiza cinco minutos e impõe um vocabulário para sempre — quem não se reconhece nele arquiva tudo e recomeça, que é pior do que a lista vazia |
| B-D15 | **`sistemica` e `entra_no_mapa` são colunas separadas.** `sistemica` = não pode editar (F10); `entra_no_mapa` = conta como gasto no relatório. Transferência e Ajuste: `entra_no_mapa = false`. Não classificado: **`true`** | Uma flag fazendo dois trabalhos é o defeito do I-01 se repetindo. A regra do mapa é `WHERE entra_no_mapa`, explícita — `WHERE NOT sistemica` só funcionaria por coincidência e **faria o gasto do bot sumir da tela em silêncio**, que é pior do que aparecer sem rótulo. Mover dinheiro entre contas suas não é gasto; gasto sem etiqueta continua sendo gasto |
| B-D16 | `auth_criar_ambiente_inicial` (V5) passa a criar as sistêmicas na mesma transação; a V10 **retroalimenta** os ambientes já existentes | F13 promete que o ambiente nasce com as sistêmicas, mas a V5 só cria ambiente e vínculo — a promessa nunca foi cumprida por ninguém. Ambiente sem sistêmica não consegue receber transferência nem ajuste |

## Mapa de gastos (T-07) — fechamento de P-T3, P-T5, P-T7

| # | Decisão | Motivo |
|---|---|---|
| B-D10 | **Previstos aparecem no mapa, visualmente distintos do realizado** (P-T7). Consequência de contrato: **cada célula devolve dois números, `realizado` e `previsto`**, nunca a soma pronta — idem para totais e para a linha de saldo | O quadro serve para planejar, não só para conferir o passado. Mas misturar num número só faria o total do mês significar duas coisas ao mesmo tempo. Se o servidor mandasse somado, a tela não teria como deixar claro o que ainda não saiu — quem separa é o endpoint, a tela só pinta |
| B-D11 | **Período padrão = ano civil, com seletor de ano** (P-T3) | Bate com a planilha mental de qualquer pessoa e com o ciclo do IR. Doze colunas fixas dão cabeçalho estável e comparação direta entre anos, que a janela móvel de 12 meses não dá |
| B-D12 | **O mapa tem três blocos: saídas, entradas e saldo do mês** (P-T5) | A pergunta que a família faz não é "quanto gastei", é "sobrou ou faltou". Custa um bloco a mais no mesmo endpoint (a agregação já varre os lançamentos; separar por `categoria.tipo` é filtro, não consulta nova) |

## Autenticação e frontend

| # | Decisão | Motivo |
|---|---|---|
| B-D6 | **I-05 resolvido sem arbitrar**: F26 (gatilho) é mantida e o aspecto `ConfiguradorSessaoRls` passa a injetar `raspybank.canal` junto com `raspybank.usuario_id`. O gatilho lê os dois; ausência do canal grava `DESCONHECIDO` (B-A2) | O conflito existia porque o gatilho não sabia o canal — a razão original de auditar pelo serviço. Injetar o canal na mesma transação custa uma linha e **dissolve** o conflito em vez de escolher um lado. Preserva a virtude de F26: `UPDATE` feito por fora da aplicação grava autor nulo e se denuncia |
| B-D7 | **`token_renovacao.ip_origem` passa a ser gravado** (I-16), a partir do request no login. Contrapartida assumida: a **tela de sessões ativas** entra no roteiro pós-mínimo | Coluna existe desde a V4 justificada por "a pessoa reconhecer suas sessões". Gravar sem a tela é dado pessoal parado; por isso a tela vira compromisso registrado, não intenção. Se ela não sair, a coluna volta à pauta para remoção |
| B-D18 | **`conta_ambiente` confere os DOIS lados do vínculo**: o `WITH CHECK` exige `ambiente_id` entre os do usuário **e** `conta_id` entre as contas que ele já enxerga | Achado ao escrever a V10. Conferir só o ambiente protegeria uma metade: `INSERT INTO conta_ambiente VALUES (<conta alheia>, <meu ambiente>)` passaria, e a conta do outro — com todo o histórico — viraria visível. Faltaria só o UUID, e "precisa adivinhar o identificador" é obscuridade, não controle de acesso. Compartilhar a própria conta num segundo ambiente seu continua funcionando; a conta recém-criada, que ninguém enxerga ainda, entra por `app_criar_conta()` |
| B-D19 | **Critério do inventário SECURITY DEFINER reescrito**: o que justifica o furo é o **impasse com a política** (a linha só fica visível por um vínculo que ainda não pode existir), não a camada da operação | O critério anterior dizia "operação de domínio NUNCA passa por SECURITY DEFINER". A V10 achou o primeiro contraexemplo legítimo: criar `conta` é domínio, tem identidade estabelecida e mesmo assim é impossível sob a política. O critério antigo dividia por onde o problema tinha aparecido até então; o novo divide pela causa. Ficou mais restritivo na prática — "é de domínio" nunca foi argumento, e "é pré-identidade" também deixou de ser |
| B-D17 | **P-T6 fechado: React + Vite**, compilado para estáticos servidos pela própria aplicação | Escolha do dono do projeto, com o critério explicitado: o RaspyBank também serve para o tempo investido valer fora de casa, e React é o que o mercado usa. O argumento de bundle (~180 KB vs ~40 KB do Svelte) **não pesou** — numa rede local, para uma família, a diferença é invisível; foi levantado e descartado. Custo aceito: mais cerimônia por tela. Contrapartida: o código é escrito comentando o porquê dos padrões, para o projeto valer como estudo |

## Código do domínio (26/07/2026, fatia 0)

| # | Decisão | Motivo |
|---|---|---|
| B-D20 | **As cinco tabelas da V10 vivem num único módulo `raspybank-lancamento`** (M3), não em três. Cartão (V12) será módulo próprio | A02 manda separar por contexto de negócio, e categoria, conta e lançamento **são** um contexto: o lançamento não existe sem os outros dois, e as três chaves compostas da V10 os amarram no banco. Separá-los obrigaria a referenciá-los por identificador solto — perdendo a relação, sem ganhar fronteira. Fronteira serve para separar o que muda por motivos diferentes; estes três mudam juntos. Cartão não: tem ciclo próprio (fatura, parcela) e referencia conta por id, que é exatamente o caso em que a fronteira paga o próprio custo |
| B-D21 | **Todo repositório do M3 recebe `ambienteId` explícito**, ao contrário dos de identidade e ambiente, onde `findAll()` bastava | Consequência de R7 que só aparece ao escrever o código: o tenant é o **usuário**, então a RLS devolve as linhas de **todos** os ambientes da pessoa. Correto para segurança, errado para a tela — a T-04 mostra o ambiente ativo e só. A regra do módulo: **a RLS decide o que você pode ver; o `ambienteId` decide o que você quer ver agora.** Esquecer o segundo não vaza dado de terceiro, vaza o seu próprio dado de outro ambiente para dentro da tela errada |
| B-D22 | **A derivação de B-D9 mora em `SituacaoLancamento.derivarDe(dataCaixa, hoje)`**, com `hoje` como parâmetro, e a entidade a reaplica em `reagendar(...)`; `corrigirSituacao(...)` permite fixá-la contra a derivação | `hoje` injetado é o que torna a virada de ano testável sem esperar dezembro, e o que impede um build que passa hoje e falha amanhã. `corrigirSituacao` é a razão de B-D9 não ser gatilho: o boleto agendado para amanhã que já foi debitado hoje existe, e regra que o banco impõe é regra que o usuário não consegue contrariar quando tem razão |
| B-D24 | **O vocabulário de erro vive no `shared`**: `OperacaoNaoPermitida` (→ 403) e `RecursoNaoEncontrado` (→ 404), com a tradução para HTTP escrita **uma vez** no `TratadorGlobalDeErros` | A lista de dependências globais é curta de propósito, e esta adição se paga: uma exceção por módulo faria o tratador crescer um ramo por contexto, e cada ramo esquecido viraria 500 no lugar de 403. As classes não conhecem contexto nenhum — são vocabulário, não regra |
| B-D25 | **Id de outro ambiente do próprio usuário responde 404, nunca 403** | Distinguir "não existe" de "não é seu" transformaria a API num oráculo: bastaria varrer identificadores e ler o código de resposta para descobrir quais existem no sistema inteiro. O RLS já produz o primeiro caso a partir do segundo entre usuários; isto mantém o mesmo silêncio entre ambientes do mesmo usuário |
| B-D26 | **Conta devolve DOIS saldos**: `saldo` (só `REALIZADO`) e `saldoComPrevistos`. É o `saldo` que precisa ser zero para encerrar | Mesmo raciocínio de B-D10 no mapa: um número só somando o que aconteceu com o que está agendado significaria duas coisas ao mesmo tempo, e a tela não teria como separar depois. Quem separa é quem calcula. Encerrar olha o realizado porque previsto é agenda, não dinheiro — um boleto marcado para o mês que vem numa conta que se está fechando é lançamento a corrigir, não impedimento |
| B-D27 | **`ConflitoDeEstado` (→ 409) é distinta de `OperacaoNaoPermitida` (→ 403)** | O 403 diz "isto você nunca pode fazer"; o 409 diz "isto você não pode fazer *agora*". A distinção é para a tela: no 403 ela desabilita o botão, no 409 ela mostra o que fazer antes de tentar de novo |
| B-D28 | **O `POST /api/lancamentos` não tem campo `tipo` no caso comum**: ele deriva de `categoria.tipo` (F12). Só categoria `AMBOS` — as três sistêmicas — exige o campo, e declarar o oposto do que a categoria impõe responde 403 em vez de ser ignorado | Escolher "Mercado" já diz que é saída; perguntar de novo é pedir a mesma informação duas vezes, e todo campo a mais no formulário é uma chance a mais de a pessoa desistir de lançar. Onde a categoria realmente não sabe o sentido, aí sim o campo aparece |
| B-D29 | **O mapa sai de UMA varredura no banco; o aninhamento e os totais são feitos em memória** | `GROUPING SETS` traria os totais junto e seria menos código. Não vale: o volume é de dezenas de linhas por ano, o custo em memória é desprezível, e o código que monta a árvore em Java é legível por qualquer pessoa — enquanto o SQL equivalente só seria legível por quem escreve SQL analítico. Se um dia o volume mudar essa conta, a resposta é cache com invalidação explícita, nunca coluna de total (P1/R1) |
| B-D23 | **A entidade recusa valor com mais de duas casas decimais**, em vez de deixar o banco arredondar | O CHECK garante `valor > 0`; a escala o banco **não** garante — `numeric(15,2)` transforma 10,005 em 10,01 em silêncio. Gravar um número que o usuário não digitou é pior do que recusar a operação |

# 4e. Decisões da Forma de Pagamento e da Transferência (27–28/07/2026)

Nasceram do **primeiro teste de negócio do sistema em alpha**. Registram tanto o pedido original quanto os dois pontos em que ele precisou ser corrigido para não se contradizer — e a correção, nos dois casos, veio do próprio Abner ao ver a consequência.

| # | Decisão | Motivo |
|---|---|---|
| B-D30 | **Forma de pagamento é dimensão de análise, não regra de dinheiro.** Vocabulário **fixo** de oito valores em tabela de referência; o que varia é **quais deles cada conta aceita**, em `conta_forma_pagamento`. `lancamento.forma_pagamento` é anulável e não entra em soma nenhuma | Nasceu de um caso real: um gasto de "gasolina, R$ 10" ficou registrado sem que desse para saber se foi débito, pix ou boleto — informação que não se recupera depois. Todas as formas têm o mesmo efeito patrimonial (o valor se move na data de caixa), então nada de saldo, situação ou mapa muda por causa delas. Cadastro livre custaria uma tela, cinco endpoints e um bot do Telegram casando texto livre, para atender um caso que ninguém pediu |
| B-D31 | **A pergunta é "como o dinheiro se moveu", não "como foi pago"** — e ela tem resposta nos **dois** sentidos. `CREDITO_EM_CONTA` existe, e cada conta tem **dois** padrões: um de saída e um de entrada | Correção de uma decisão minha, apontada pelo Abner: a primeira versão recusava forma em ENTRADA com o argumento de que "salário não é pago no débito". O argumento estava certo e o alvo errado — o salário é *creditado*, e isso é uma forma tanto quanto o boleto. Um padrão único de saída deixaria toda entrada em branco para sempre |
| B-D32 | **O padrão é POR CONTA, não um `DEBITO` global**, e não se aplica a categoria sistêmica | A regra pedida foi "se a pessoa não indicar, salva débito". Débito literal quebraria na carteira, que só aceita `DINHEIRO`: gravaria nela uma forma que a lista da própria conta recusa, em silêncio. A guarda de sistêmica tem motivo próprio: sem ela o saldo de abertura de toda conta nova apareceria no extrato como "pago no débito" — lixo visível na primeira tela, que ninguém digitou. É ela também que faz as pernas de uma transferência nascerem sem forma, **sem nenhum caso especial escrito** |
| B-D33 | **A regra de qual forma serve a qual sentido vive em UMA tabela** (`forma_pagamento_sentido`, onze linhas), e não em CHECKs | Com CHECKs, a regra apareceria em três lugares: no lançamento, no padrão da conta e no enum Java. Três cópias divergem, e a divergência aparece como "o sistema aceitou salário pago no boleto", meses depois. Com a tabela, as chaves compostas do lançamento apenas a consultam. Consequência boa: o frontend passou a **ler** a lista de `GET /api/formas-pagamento` em vez de repeti-la, matando a quarta cópia |
| B-D34 | **Vazio significa coisas diferentes no POST e no PUT**: no POST cai no padrão da conta, no PUT limpa o campo | No PUT a tela mostra o campo já preenchido com o valor atual, então mandar vazio é um **ato** — a pessoa está limpando. Reaplicar o padrão desfaria, no servidor, o que ela acabou de fazer. No POST não há valor anterior, e aí o vazio é ausência de resposta, não decisão |
| B-D35 | **Remover uma forma da lista de uma conta que já a usou é recusado com 409**, dizendo quantos lançamentos | A alternativa (`ON DELETE SET NULL`) apagaria em silêncio exatamente o dado que esta funcionalidade veio registrar. Recusar dá a chance de reclassificar antes; apagar não dá chance nenhuma |
| B-D36 | **Crédito de cartão não é forma de pagamento** (mas `CREDITO_EM_CONTA` é outra coisa, e é) | Compra no cartão de crédito nasce na conta **do cartão** (natureza `PASSIVO`); quem debita a corrente é o pagamento da fatura. Se fosse forma de pagamento da corrente, a mesma compra teria dois caminhos de representação e as somas discordariam. É a V12. `CREDITO_EM_CONTA` tem nome longo justamente porque "crédito" significa as duas coisas em português, e `DEBITO` na mesma lista já é o cartão |
| B-D37 | **`TED`, e não `TRANSFERENCIA`, é o nome da forma** | `TRANSFERENCIA` já é o código de uma categoria sistêmica (B-D13). A mesma palavra significando duas coisas no mesmo domínio é a receita para alguém ler a errada — o mesmo raciocínio que produziu `CREDITO_EM_CONTA` |
| B-D38 | **`lancamento.lancamento_par_id` existe, é mútuo e cascateia.** A é par de B e B é par de A; `ON DELETE CASCADE` nos dois sentidos | F2 prometia "dois lançamentos ligados" e F16 prometia "propaga para o par" desde o modelo lógico — e **nenhuma migração tinha criado a coluna**. A promessa estava no documento e não no schema. Sem ela, apagar uma perna deixa a outra órfã e o dinheiro aparece do nada, em silêncio. Mútuo e não um ponteiro só porque numa transferência não existe perna principal: escolher uma criaria uma assimetria que o domínio não tem. E a cascata fica no **banco** porque regra de integridade cumprida pelo banco não tem como ser esquecida por um caminho de código novo |
| B-D39 | **Saque não existe como conceito.** Nem categoria sistêmica, nem forma de pagamento: sacar é transferir da conta para a carteira | Decisão do Abner, contra minha proposta de uma forma `SAQUE`: *"não precisa nem da palavra saque, pode deixar tudo transferência mesmo — transferiu para carteira 100 reais, intrínseco que é um saque"*. Ele está certo, e pelo motivo mais forte: um segundo nome para o mesmo evento obrigaria todo relatório futuro a conhecer os dois, e esquecer um viraria número errado sem aviso. B-D13 já dizia que sistêmica é cadeado, e cadeado só onde quebrar dói |
| B-D41 | **`DINHEIRO` é mutuamente exclusivo com todas as outras formas.** Uma conta guarda papel moeda OU dinheiro virtual | Observação do Abner ao testar: papel moeda só existe em lugar físico — carteira, bolso, gaveta, cofre — e nenhum deles recebe pix. Do outro lado, o dinheiro de uma conta em banco é virtual: tirá-lo de lá não é "pagar em espécie", é um **saque**, que neste sistema é uma transferência para a conta física (B-D39). Uma lista com `DINHEIRO` e `PIX` descreveria uma conta que não existe. Na tela as caixas se **desligam entre si**, em vez de aceitarem e recusarem depois. **Fica no serviço e na tela, não no banco**, ao contrário das outras duas regras de forma: violá-la não grava lançamento errado, só torna a lista incoerente — nenhum número fica errado, só uma opção sem sentido aparece num seletor. Impor no banco custaria um gatilho de nível de comando |
| B-D42 | **`POST /api/lancamentos` recusa a categoria `TRANSFERENCIA`**, e a T-08 tira a opção do seletor. `AJUSTE` e `NAO_CLASSIFICADO` continuam lançáveis | Transferência migrou inteira para o endpoint próprio, e escolhê-la num lançamento avulso criaria **meia transferência**: dinheiro saindo de uma conta sem entrar em nenhuma, com `lancamento_par_id` nulo e nenhum saldo isolado parecendo errado. A guarda fica no `POST` e **não** no `PUT`, porque editar uma perna existente é legítimo (até propaga para a outra) e a categoria não muda ali. As outras duas sistêmicas ficam: "Ajuste de saldo" é o caminho que a própria mensagem de encerrar conta indica, e "Não classificado" é o destino do bot do Telegram |
| B-D43 | **O previsto vencido vira realizado sozinho, na LEITURA.** Mão única: só `PREVISTO` vencido → `REALIZADO`, nunca o contrário | `derivarDe` (B-D9) só rodava na criação e na edição, e nada reavaliava com o tempo: a luz lançada para 05/08 continuava `PREVISTO` em 06/08 e o saldo realizado a ignorava **para sempre**. Na leitura e não num job agendado porque a rotina de fundo não tem `raspybank.usuario_id` na sessão — nenhuma política a avalia e o UPDATE alcançaria zero linhas. Fazê-la funcionar exigiria `SECURITY DEFINER` novo, que passa por B-D19 e pelo inventário. Dentro da requisição, a identidade já existe, a RLS funciona sem furo e a auditoria grava o usuário de verdade. **Mão única evita briga com `corrigirSituacao`**: quando o boleto não foi pago, a correção certa é mudar a **data** (reagendar), não a situação — e aí ele volta a previsto pela regra normal. O caso legítimo de `corrigirSituacao` (marcar como já debitado algo **futuro**) nunca é desfeito |
| B-D44 | **Previsto com data no passado deixou de ser um estado possível** | Consequência direta de B-D43, registrada porque quebrou um teste que dependia dela: o `MapaDeGastosApiTest` criava um `PREVISTO` com data passada para ser independente do dia em que a suíte roda. O cenário mudou para um previsto em dezembro. Quem escrever teste novo precisa saber: para ter um previsto, use data futura |
| B-D40 | **Transferência tem endpoint próprio; uma perna nunca é criada sozinha.** E uma perna não muda de categoria | Dois `POST /api/lancamentos` em sequência deixariam, se o segundo falhasse, 100 reais tendo saído de uma conta sem terem entrado em nenhuma — e nada denunciaria isso depois. Sair de `TRANSFERENCIA` na edição deixaria o par com classificações diferentes em cada lado, e o mapa contaria como despesa metade de um movimento que não é gasto (B-D15) |

# 4f. Decisões do Cartão de Crédito (28/07/2026) — desenho, ANTES do código

Escritas na varredura que precede a V12, no mesmo formato que precedeu a V10 e a V11. **Nada aqui foi implementado ainda.** O Abner trouxe a visão de negócio e pediu explicitamente para segurar o código até o entendimento estar fechado e autorizado.

| # | Decisão | Motivo |
|---|---|---|
| B-D45 | **"Banco" não é entidade nova.** É a `conta` que já existe, e o cartão referencia uma conta `ATIVO` que **não seja física** | Palavras dele: *"o cartão sempre está debaixo de um banco, não consigo criar um cartão sem uma conta de banco criada antes"*. A restrição de não ser carteira já está modelada por B-D41 — conta que só aceita `DINHEIRO` é física, e papel moeda não emite cartão de crédito. Uma tabela `banco` nova não guardaria nada que a conta já não guarde |
| B-D46 | **Três camadas: `conta` (banco) → `cartao` (contrato) → `cartao_emitido` (plástico ou virtual).** A **fatura é do contrato**, e engloba todos os emitidos dele | O exemplo dele fecha exatamente assim: Nubank tem "Black" e "Diamond", cada um com limite próprio; debaixo do Black vivem o físico dele, o adicional da Luciana e os virtuais, e *"tudo isso compartilha 10 mil de limite"*. É F17/F18 palavra por palavra, e é ele quem confirma: *"a fatura engloba todos os cartões daquela fatura"* |
| B-D47 | **O `cartao` É uma conta `PASSIVO`** (F5/F17), com PK igual à da conta | A dívida do cartão é saldo, e saldo é soma de lançamentos (P1). Sem isso o valor devido moraria num segundo lugar e voltaria a haver o que reconciliar (R1). Efeito colateral desejado: o cartão aparece na T-05 junto das outras contas, e o patrimônio (F6) passa a subtraí-lo sozinho |
| B-D48 | **O limite é INFORMATIVO e não trava nada.** Disponível = limite − dívida **com previstos** | Palavras dele: *"o limite do cartão é meramente informativo e o sistema não precisa travar, apenas mostrar o que foi consumido"*. E os dois saldos de B-D26 pagam por si: as parcelas futuras já existem como lançamentos desde a compra (F23), com data futura, então entram em `saldoComPrevistos` e não em `saldo`. Comprar 10x de R$ 100 derruba o disponível em R$ 1.000 na hora — o número bate com o app do banco, que é para isso que ele serve |
| B-D49 | **Fechamento = vencimento − N dias, com N configurável por cartão e padrão 5.** Se cair em fim de semana, recua para a sexta anterior. O fechamento manual existe, para a exceção | Ele pediu "5 dias por padrão sistêmico" e um botão de fechar manualmente porque "cada banco tem uma regra". Fixar 5 no sistema faria a regra do banco virar trabalho manual **todo mês, para sempre**; configurável por cartão resolve o caso permanente e deixa o botão para o que é realmente excepcional |
| B-D50 | **Fatura fechada pode ser REABERTA — revisão de F21** | F21 dizia "fatura fechada é imutável; correção vira ajuste na fatura aberta". Ele quer poder desfazer um fechamento acidental, e quer poder mover um lançamento de mês. Decisão dele, com o motivo dado: *"é mais para se ele fechar a fatura sem querer poder abrir"*. **Consequência a tratar no desenho:** reabrir uma fatura já paga deixa pagamentos apontando para uma fatura aberta — o estado derivado precisa continuar coerente |
| B-D51 | **Pagar a fatura é um par de lançamentos ligados, como a transferência, com duas coisas a mais: aponta para a `fatura` e a perna de saída carrega forma de pagamento.** A conta pagadora é livre — inclusive de outro banco — e o **valor pode ser parcial** | Descrição dele: *"escolho a fatura, o valor que vou pagar, o banco de onde o dinheiro sai, e a forma desse pagamento"*, com o exemplo de pagar a fatura do Nubank com a conta do C6 via boleto. Reaproveita inteira a máquina da transferência (par mútuo, `ON DELETE CASCADE`), então meio pagamento não consegue existir. Usa a sistêmica `PAGAMENTO_FATURA` que B-D13 já reservou |
| B-D57 | **Fatura ABERTA aceita pagamento, e é assim que se libera limite** | Não é conveniência, é o mecanismo. O caso dele: fatura aberta de 5.000 com 1.000 de limite disponível, e uma compra de 2.000 para fazer — paga 1.000 antecipado, o pendente cai para 4.000, o disponível sobe para 2.000, e a compra passa. Sem antecipação o limite só voltaria no vencimento. Eu tinha escrito 403 para este caso **sem base nenhuma**, e ele corrigiu |
| B-D58 | **O estado da fatura são TRÊS perguntas independentes — `ciclo`, `quitacao` e `vencida` — e não um enum de cinco valores** | Consequência direta de B-D57: uma fatura `ABERTA` pode estar parcialmente paga, e num enum único esse caso não teria nome — ou ganharia um `ABERTA_COM_ANTECIPACAO` que existe só para tapar buraco de desenho. É o mesmo erro que B-D15 já custou uma vez, quando "sistêmica" e "entra no mapa" fingiam ser uma pergunta só. A tela compõe o rótulo; o servidor não escolhe por ela |
| B-D59 | **O pagamento da fatura aparece no extrato da conta pagadora e NÃO no mapa de gastos** | Palavras dele: *"seria leal registrar para que a pessoa possa ver que o valor saiu da conta"*. O dinheiro saiu mesmo — omitir seria mentir sobre o saldo. Mas os gastos já entraram no mapa um a um quando as compras foram lançadas, e contar o pagamento de novo dobraria o mês. `PAGAMENTO_FATURA` nasce com `entraNoMapa = false`, pelo mesmo motivo de `TRANSFERENCIA` |
| B-D52 | **O pagamento parcial RESOLVE o I-07**, aberto desde o modelo lógico | F19 já dizia que fatura não tem coluna de status e que tudo deriva de `fechada_em` + somas de pagamento + vencimento. Faltava alguém confirmar que pagamento parcial existe — e ele confirmou ao dizer "o valor que vou pagar". Os estados **Aberta, Fechada, Paga parcialmente, Paga, Vencida** passam a ser todos calculados, sem uma coluna sequer |
| B-D53 | **`cartao_emitido.nome_titular` é TEXTO; `usuario_id` fica nulo até o convite existir** | Ele quer registrar o adicional da Luciana agora, e os gastos dela já são gasto da casa. Mas convidar usuário é o I-08, que não existe. O texto permite registrar hoje; quando o convite chegar, preenche-se o `usuario_id` e nada mais muda. F22 continua valendo: `responsavel_id` é dimensão de análise, não de acesso |
| B-D54 | **O mapa de gastos usa o MÊS DA FATURA**, e ganha um filtro de conta no topo em vez de um terceiro número na célula | Regime de caixa (P-T2) é o que o mapa inteiro já usa, e F14 já dizia que no cartão a `data_caixa` é mantida pela fatura. Compra de 10x em agosto vira R$ 100 em cada um dos dez meses seguintes, que é quando o dinheiro realmente sai. O filtro (`todas / só cartão / sem cartão`) responde "quanto do meu mercado foi no cartão" **sem tocar na célula** — B-D10 separou dois números com esforço, e um terceiro em doze colunas viraria sopa |
| B-D55 | **Não existe tabela `parcela`.** Cada parcela é um `lancamento` numa fatura diferente, com `grupo_parcelamento_id`, `parcela_numero` e `parcela_total` | B-D1 listava `parcela` como tabela. Ao desenhar, ela não guardaria nada que não fosse derivável: o valor total é a soma das parcelas, a data da compra é a `data_competencia` que se repete em todas (F23, confirmado por ele — *"a data da compra repete, muda somente a fatura"*), e a quantidade é a contagem. Tabela que só guarda agregado contraria P1. **Custo assumido e registrado:** o `grupo_parcelamento_id` não tem tabela-alvo, então não há FK garantindo o grupo — é identificador de correlação, e a integridade dele fica na aplicação |
| B-D56 | **Recorrência (F24/F25) fica FORA desta entrega** | Não é feature de cartão: Netflix no cartão e aluguel no débito automático têm o mesmo problema. Entra depois, sozinha, valendo para conta e cartão ao mesmo tempo. F25 (edição de série em três modos, sem tocar em realizado nem em fatura fechada) é a regra mais delicada do modelo inteiro, e somá-la à maior entrega já feita seria empilhar as duas coisas mais difíceis |

## Regras derivadas, registradas para não virarem discussão depois

- **Mover um lançamento para uma fatura FECHADA é recusado.** Se for o caso, reabra antes (B-D50). Sem isso, "fechada" não significaria nada.
- **Faturas são pré-geradas 12 meses à frente** (F20), para o parcelamento ter onde cair.
- **Compra lançada quando a fatura já fechou vai para a seguinte**, mesmo que a data da compra seja anterior ao fechamento. Palavras dele: *"fatura fechada, lançamento vai para o próximo"*.
- **A data da compra é editável** (`data_competencia`), e a fatura do lançamento também — são campos independentes.

# 5. Revisões registradas (R1–R6, sessão de requisitos)

Decisões que substituíram decisões anteriores durante o próprio processo. O motivo da mudança é tão importante quanto a decisão final.

### R1 — A Conta perdeu o saldo persistido
*Antes:* saldo persistido na Conta. *Depois:* Lançamento é fonte única; Conta não guarda saldo. *Motivo:* eliminada a dupla fonte de verdade — não há o que reconciliar quando o dado não existe em dois lugares.

### R2 — A data do dinheiro da parcela
*Antes:* data do dinheiro = vencimento da Fatura. *Depois:* data do fato em todas as parcelas; a distinção é a Fatura. *Motivo:* evitar datas inexistentes (31/02) e eliminar cálculo de data.

### R3 — Data de caixa efetiva
*Adicionada* após a escolha do regime de caixa, que expôs a atribuição incorreta das parcelas ao mês da compra.

### R4 — Ambiente Financeiro readotado
*Antes:* eliminado no redesenho (herança do modelo multiusuário). *Depois:* readotado como fronteira de dados. *Motivo:* uso compartilhado por casal e instalação multifamiliar deixou de ser hipótese e virou requisito presente. Retrofit de isolamento em base com dados reais é das refatorações mais perigosas que existem.

### R5 — Cartão passou de dois para três níveis
*Antes:* Cartão como instrumento único com limite próprio. *Depois:* Cartão (contrato) → Cartão Emitido (físico/virtual). *Motivo:* o caso real tem dezessete cartões sob um único limite e uma única fatura.

### R6 — Estratégia de token
*Antes:* JWT de 24 horas. *Depois:* acesso 15 min + renovação rotativa 30 dias + teto 90 dias. *Motivo:* token longo não pode ser revogado; token curto sozinho inviabiliza o uso; o par resolve os dois.

### R8 — F31 revogada: o lançamento não congela o nome da categoria *(26/07/2026)*
*Antes:* lançamento guardava `categoria_nome`/`subcategoria_nome` copiados na criação, para que renomear a categoria não reescrevesse o passado. *Depois:* o lançamento guarda só os ids; o nome exibido vem sempre da categoria (B-D3), e categoria/subcategoria passam a ter apenas exclusão lógica (B-D4). *Motivo:* uma categoria é uma coisa, o nome dela é um rótulo — trocar o rótulo não cria outra categoria. Com o nome vindo do id, a cópia congelada só se justificaria se o id pudesse deixar de resolver; com arquivamento no lugar de exclusão, ele sempre resolve. O que sobraria seria uma segunda fonte para o mesmo dado, exatamente o defeito que o I-01 acabou de eliminar em `ambiente`.

### R9 — F15 emendada: o status do lançamento deriva da data *(26/07/2026)*
*Antes:* lançamento fora de cartão nasce sempre `PREVISTO`. *Depois:* nasce `REALIZADO` se a data de caixa é passada ou hoje, `PREVISTO` se é futura (B-D9); cartão segue nascendo `REALIZADO`. *Motivo:* F15 foi escrita na Fase 2, antes de existir tela. Como o mínimo aceitável não tem cartão, a regra literal fazia **todo** lançamento nascer previsto — cadastrar dez gastos já pagos exigiria dez confirmações antes de a tela central sair do zero. A intenção de F15 (separar planejado de acontecido) é preservada; o que muda é quem responde a pergunta — a data digitada, não um passo extra.

### R7 — Tenant do RLS passou de ambiente para usuário *(Fase 2)*
*Antes:* `ambiente_id` como tenant, políticas por igualdade de campo. *Depois:* usuário como tenant, visibilidade por subquery sobre tabelas de vínculo. *Motivo:* o caso real de compartilhamento (ambiente pessoal, freelance, contas conjuntas visíveis em mais de um ambiente via `conta_ambiente` N:N) não se expressa por igualdade de um campo.

# 6. Estado das migrações

| Versão | Conteúdo | Aplicada |
|---|---|---|
| V1 | `usuario`, `ambiente`, `usuario_ambiente` | ✔ |
| V2 | `registro_auditoria`, `outbox` (índice parcial em não-publicados) | ✔ |
| V3 | RLS nas cinco tabelas; `app_usuario_id()`, `app_ambientes_do_usuario()` | ✔ |
| V4 | `token_renovacao`; `auth_cadastrar_usuario`, `auth_buscar_credenciais` | ✔ |
| V5 | `auth_criar_ambiente_inicial` | ✔ |
| V6 | `auth_registrar_evento` | ✔ |
| V7 | `auth_ambientes_do_usuario` | ✔ |
| V8 | Normalização maiúsculas nos CHECKs; revogação de SELECT em `senha_hash`; filtro de excluídos em `app_ambientes_do_usuario`; `registro_auditoria.usuario_id` nullable | ✔ |
| V9 | Operação `ACESSO` no `ck_auditoria_operacao`; UPDATE dos logins históricos | ✔ |
| V10 | Domínio, fatia 1 (B-D1): `categoria`, `subcategoria`, `conta`, `conta_ambiente`, `lancamento`; políticas RLS das cinco + `app_contas_do_usuario()`; gatilho de auditoria com canal (F26 + B-D6); outbox do lançamento (F28); sistêmicas (B-D13/B-D16); porta estreita `app_criar_conta()`; `DROP ambiente.status` (B-D5). Verificada por `DominioRlsTest` (14 cenários) | ✔ |
| V11 | **Forma de pagamento e transferência** (B-D30 a B-D40): `forma_pagamento` e `forma_pagamento_sentido` (referência); `conta_forma_pagamento` (lista por conta + dois padrões, com RLS e auditoria); `lancamento.forma_pagamento` amarrada por **duas** chaves compostas; `lancamento.lancamento_par_id` mútuo com `ON DELETE CASCADE`, cumprindo F2/F16. Verificada por `FormaPagamentoApiTest` (21) e `TransferenciaApiTest` (14) | ✔ |
| V12 | Domínio, fatia 2: `cartao`, `cartao_emitido`, `fatura` + colunas de parcelamento no lançamento. **Sem `parcela`** (B-D55) e **sem `regra_recorrencia`** (B-D56, entrega própria). Desenho fechado em §4f; código não iniciado. Sai junto da tela T-06. Resolve I-07 (estados da fatura). **Era a V11** até 27/07/2026, quando a forma de pagamento tomou o número — reserva era plano, não fato, e deixar um buraco na sequência para honrar uma anotação seria pior | ✘ |
