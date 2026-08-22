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
| P4 | **O ambiente é a fronteira de dados, e o filtro dela não depende de ninguém lembrar.** Todo dado financeiro pertence a exatamente um ambiente, e ninguém alcança dado de ambiente com o qual não tem vínculo. O isolamento vive no banco — política de RLS mais a identidade de sessão aplicada por aspecto — e nunca num `where` que o desenvolvedor precisa escrever, porque filtro que se escreve à mão é filtro que um dia se esquece. (Registrado em 16/08/2026 — ver nota abaixo.) |

> **Nota sobre o P4 (16/08/2026).** O princípio é citado pela V1, pela V3, por
> `ConfiguradorSessaoRls`, `ContextoRequisicao`, `CategoriaControlador` e `Ambiente` desde a
> fundação, com as referências `RF-M2-01` e `RF-M2-06` da sessão de requisitos de 20/07 —
> mas nunca havia entrado neste documento. Uma auditoria de fronteiras o encontrou:
> seis arquivos apoiando um argumento numa referência que a fonte de verdade não tinha.
> O texto acima registra o que o código já pratica; não muda comportamento nenhum.
>
> Duas precisões que a formulação de 20/07 não podia ter:
> - **O tenant do RLS é o usuário, não o ambiente** (A08). O ambiente é a fronteira do
>   *dado*; o usuário é o eixo da *política*. As duas coisas convivem — é por vínculo que
>   se decide o que cada um vê, não por igualdade de campo.
> - **Vínculo explícito estende a visibilidade, e isso não fere o P4** (§4j, §4k, §4n).
>   Uma conta continua pertencendo a exatamente um ambiente — a `origem` em
>   `conta_ambiente` — e aparece em outro só quando alguém aceitou um convite. O que o
>   P4 proíbe é alcance sem vínculo, não compartilhamento consentido.
>
> O texto das migrações V1 e V3 fica como está: migração aplicada não se edita, nem no
> comentário — o checksum do Flyway não distingue comentário de comando.

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
| A16 | Faixas de rede Docker **fixas fora de 172.16–172.31**: `default-address-pools` `10.200.0.0/16` no `daemon.json` (redes automáticas) + subnet `10.201.0.0/24` na rede do projeto (`infra/compose.yaml`) | O Default Switch do Hyper-V opera em 172.x e **reatribui a faixa em reboot do host**. Sem subnet explícito, o Docker escolheu 172.18 e colidiu com o gateway do switch: a VM sumia por SSH com **timeout, não refused** — sshd, ufw e ss todos saudáveis, o diagnóstico apontando pra todo lado menos o certo. Fixar as duas faixas torna o mapa previsível (10.200 = Docker automático, 10.201 = RaspyBank) e imune ao próximo boot. Ressalva: `daemon.json` é config de sistema, **fora do repo** — cada VM/host aplica à mão até virar script de provisionamento. Colisão silenciosa é a pior: some sem dizer por quê |

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

# 4f. Decisões do Cartão de Crédito (28/07/2026)

Escritas na varredura que precedeu a V12, no mesmo formato que precedeu a V10 e a V11 — o Abner trouxe a visão de negócio e pediu para segurar o código até o entendimento estar fechado. **Implementadas no mesmo dia, depois da autorização dele.**

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
| B-D60 | **Fatura VAZIA nunca está vencida** | Custou um teste para aparecer, e é o tipo de coisa que só o uso revela: um cartão recém-criado tem faturas de ciclos que já passaram, todas fechadas e com total zero — e sem a condição `total > 0` todas nasciam gritando "vencida". Não há dívida atrasada onde não houve compra |
| B-D56 | **Recorrência (F24/F25) fica FORA desta entrega** | Não é feature de cartão: Netflix no cartão e aluguel no débito automático têm o mesmo problema. Entra depois, sozinha, valendo para conta e cartão ao mesmo tempo. F25 (edição de série em três modos, sem tocar em realizado nem em fatura fechada) é a regra mais delicada do modelo inteiro, e somá-la à maior entrega já feita seria empilhar as duas coisas mais difíceis |

## Regras derivadas, registradas para não virarem discussão depois

- **Mover um lançamento para uma fatura FECHADA é recusado.** Se for o caso, reabra antes (B-D50). Sem isso, "fechada" não significaria nada.
- **Faturas são pré-geradas 12 meses à frente** (F20), para o parcelamento ter onde cair.
- **Compra lançada quando a fatura já fechou vai para a seguinte**, mesmo que a data da compra seja anterior ao fechamento. Palavras dele: *"fatura fechada, lançamento vai para o próximo"*.
- **A data da compra é editável** (`data_competencia`), e a fatura do lançamento também — são campos independentes.

# 4g. Ajustes dos testes de negócio do cartão (28/07/2026)

Oito pontos que o uso real da V12 devolveu. Quatro deles — 3, 5, 7 e 8 da lista dele — eram a mesma ideia vista de ângulos diferentes.

| # | Decisão | Motivo |
|---|---|---|
| B-D61 | **O cartão é um MEIO DE PAGAMENTO da conta bancária, não uma conta.** A tela manda `contaId` = o banco e `cartaoEmitidoId` = o plástico; o servidor redireciona o lançamento para a conta do cartão. No extrato, a coluna Conta mostra o **banco** | Palavras dele: *"quando eu seleciono o primeiro combo box 'conta' lá lista somente as contas bancárias e não lista o Cartão; no combo do 'como foi pago' aparece a lista de meios de pagamento e mais os cartões daquela conta"*. Ninguém pensa "vou gastar na conta do cartão", pensa "paguei no cartão". **O armazenamento não mudou junto**, e não é conservadorismo: pagamento parcial da fatura e pagar a fatura do Nubank com a conta do C6 — os dois pedidos dele — exigem que a dívida seja saldo próprio. Se a compra debitasse o banco direto, a fatura não teria o que pagar |
| B-D62 | **O cartão não aparece na tela de contas**, que passou a se chamar "Contas bancárias" | *"tratar o cartão de crédito como um banco confunde"*. O recorte fica no repositório (`bancariasDoAmbiente`) e não em cada tela, pelo motivo de sempre: quatro telas lembram, a quinta esquece. A dívida não sumiu — continua no patrimônio e inteira na tela de cartões |
| B-D63 | **Criar o contrato cria o cartão FÍSICO junto**, com os quatro dígitos informados na mesma tela | *"quando eu crio um cartão eu preciso informar os 4 últimos dígitos, para que seja possível depois lá na hora de lançar o gasto dizer nubank - físico - 4352"*. Um contrato sem nenhum emitido não recebe compra nenhuma — nasceria inútil, e obrigaria duas etapas para uma coisa só |
| B-D64 | **`lancamento.cartao_emitido_id`**: o lançamento passa a saber qual plástico ou virtual fez a compra | A V12 amarrou o lançamento à FATURA, e isso bastava para cobrar. Não bastava para explicar: *"quando eu clicar em Ver fatura, vai mostrar os gastos de cada cartão virtual, de cada cartão físico, no mesmo mês"*. Sem a coluna, uma fatura com o físico, dois virtuais e o adicional é uma pilha de gastos sem dono. `ck_lancamento_cartao_exige_fatura` é de **mão única**: quem tem cartão tem fatura, mas as duas pernas de um pagamento têm fatura e nenhum cartão |

| B-D65 | **Encerrar cartão NÃO exige dívida zero, e cancela todos os emitidos em cascata** | Correção dele: *"o fato de eu encerrar um cartão não some com o futuro e nem anula ele, a responsabilidade de pagar as faturas em aberto e as dívidas futuras continua"*. Eu tinha copiado F7 — conta com saldo não encerra — e o paralelo não valia: encerrar uma **conta** com saldo faria dinheiro sumir do patrimônio, enquanto encerrar um **cartão** com dívida não muda número nenhum. As parcelas futuras continuam chegando e as faturas continuam pagáveis. O que encerrar faz é uma coisa só: **impedir compra nova**, e por isso ele some da lista de meios de pagamento. **Reabrir não reativa os emitidos**: ressuscitar em massa devolveria à vida um virtual que a pessoa matou de propósito, e virtual é feito para ser descartado |
| B-D66 | **A tela de cartões agrupa por BANCO → contrato → emitidos** | É como ele pensa: *"quando penso em cartão de crédito primeiro eu penso de qual banco, depois no cartão principal e depois nos adicionais ou virtuais"*. A hierarquia visual passou a ser a mesma do modelo (B-D46), e o banco saiu de etiqueta na linha para cabeçalho de grupo. Cada emitido ganhou botão próprio de cancelar e reativar — cancelar um virtual descartado não deveria exigir matar o contrato inteiro |

## Achado que a constraint da V13 denunciou

`ck_lancamento_cartao_exige_fatura` pegou um defeito **da V12** no primeiro parcelamento: as parcelas 2..N eram gravadas antes de receber a fatura. Passou despercebido enquanto o cartão não existia na linha; a constraint nova o expôs no mesmo dia. Corrigido invertendo a ordem — fatura antes do `save`.

# 4h. Renovação concorrente de token (28/07/2026)

| # | Decisão | Motivo |
|---|---|---|
| B-D67 | **Uma renovação de token em voo por vez**, compartilhada por todas as chamadas que precisarem dela | O token de renovação é rotativo (A11): cada uso o consome. As telas disparam chamadas em paralelo — a T-08 faz um `Promise.all` de três — e, sem coordenação, as três renovavam com o MESMO token. A primeira o consumia e as outras chegavam apresentando um token já usado; o servidor, que não distingue reuso legítimo de roubo, revogava a família inteira e deslogava de todos os dispositivos. **A segurança estava certa; o cliente é que estava errado.** Reproduzido contra o servidor real: três renovações paralelas deram 200, 401 e 401, com a família revogada |
| B-D68 | **Se o token já mudou enquanto a chamada estava no ar, não se renova — repete-se** | Consequência do anterior, e barata: uma chamada que levou 401 por causa do token velho não precisa de renovação nenhuma se outra já renovou. Sem esta guarda, sobraria uma renovação inútil por rajada, consumindo um token que acabou de nascer |
| B-D69 | **O frontend ganhou testes, e SEM framework**: Node puro, `node:assert`, dublês de `localStorage` e `fetch` em vinte linhas. Alvo `make web-test` | Trazer vitest ou jest custaria dezenas de pacotes, e a regra de dependências (mapa-telas §7) existe para que cada pacote seja uma decisão — um defeito não justifica uma árvore. O teste **foi conferido contra o código antigo** e falha nele: sem isso, seria um teste que não prova nada. Quando o frontend tiver muitos testes, a conta muda e a decisão se revisita |

## O que este teste ainda não alcança

Ele exercita o módulo, não o navegador. A validação de formulário do HTML — que já escondeu um defeito real, o `pattern` do cartão com escape de JSX — continua fora do alcance de qualquer teste que não abra uma página de verdade.

# 4i. Perfil e ambientes (29/07/2026)

Escopo deliberadamente pequeno: ele cortou o próprio pedido no meio — *"ficou complexo e eu preciso pensar... vamos fazer o básico depois a gente evolui"*. Apagar ambiente e apagar conta ficaram fora.

| # | Decisão | Motivo |
|---|---|---|
| B-D70 | **`app_criar_ambiente(nome)` — a TERCEIRA porta estreita** (V14) | Mesmo impasse de `app_criar_conta` e de `auth_criar_ambiente_inicial`: `pol_ambiente_vinculado` pergunta se o ambiente está entre os do usuário, e para um que está nascendo a resposta é sempre não. Nenhuma ordem de INSERT resolve. **A identidade vem da sessão, não de parâmetro** — com o usuário como argumento, a função viraria "crie um ambiente para fulano". Nasceu de um beco que o uso revelou: o seletor da casca ficava desabilitado com um ambiente só, e não havia como criar o segundo |
| B-D71 | **O e-mail não se edita.** Só o nome | Ele é o login. Trocá-lo muda a identidade de entrada e, **enquanto não existir recuperação de senha**, um e-mail digitado errado tranca a pessoa para fora da própria conta sem volta. A ausência do campo é a proteção |
| B-D72 | **Trocar a senha exige a atual e derruba as OUTRAS sessões** | Sem a atual, quem sentasse numa máquina com a sessão aberta tomaria a conta — e o dono ficaria de fora, porque a troca expulsa todo mundo. Derrubar as outras é o comportamento certo pelo motivo oposto: se a troca aconteceu porque a senha vazou, deixar as antigas vivas manteria o invasor dentro |
| B-D73 | **A troca de senha NÃO ganhou função `SECURITY DEFINER`** | A15 diz que a entidade não mapeia `senha_hash`, e o motivo é que a V8 **revogou o SELECT** — mapear faria todo `findById` falhar. A **escrita** continua concedida, e `pol_usuario_proprio` já limita a linha ao dono. Não há impasse com a política, e pelo critério B-D19 uma função nova não se justifica. Um UPDATE nativo resolve, sem SELECT na coluna proibida |

## O beco que custou uma rodada de teste vermelho

`UsuarioServico` existe por necessidade, não por cerimônia de camada. O aspecto `ConfiguradorSessaoRls` injeta `raspybank.usuario_id` envolvendo métodos `@Transactional`; um repositório chamado **direto do controlador** não passa por ele, `app_usuario_id()` devolve nulo, e `pol_usuario_proprio` não casa com linha nenhuma.

O sintoma é cruel: `findById` devolve vazio para um usuário que existe, e a tela responde **404 sem nada no log**. Está escrito no javadoc da classe para não custar de novo.

## O inventário está em DOIS lugares, e os dois cobram

`MigracoesTest` pegou a função nova ausente do inventário — mas a lista dele é **codificada no próprio teste**, não lida de `docs/security-definer.md`. Então acrescentar uma função `SECURITY DEFINER` exige tocar em dois arquivos, e o teste só reclama do segundo.

Fica registrado como dívida pequena: o teste deveria ler o documento. Enquanto não lê, quem esquecer o documento passa no build.

# 4j. Compartilhamento de ambiente (29/07/2026) — desenho e, no mesmo dia, a V15

Escritas na varredura que precedeu a V15, no mesmo formato que precedeu a V10, a V11 e a V12. **Implementado na V15, no mesmo dia** — o desenho valeu inteiro; o que a implementação acrescentou está no fim da seção.

Descrição dele, e é a frase que orienta tudo: *"é como se eu desse a minha senha para a pessoa, mas ao invés de dar minha senha dei meu acesso"*.

| # | Decisão | Motivo |
|---|---|---|
| B-D74 | **Compartilhar ambiente é UMA LINHA em `usuario_ambiente`.** Não existe mecanismo novo de visibilidade | O tenant do RLS sempre foi o **usuário**, e a visibilidade sempre foi por vínculo (A08/R7). Inserida a linha, o ambiente aparece na lista dela e todas as políticas já respondem certo — contas, categorias, cartões, mapa. Não é sorte: é a fundação, desenhada assim na Fase 2 para exatamente este caso. **Compartilhar conta e compartilhar cartão não existem como mecanismos separados**: quem está no ambiente vê o que está nele |
| B-D75 | **`usuario_ambiente` ganha `dono`, um booleano — e NÃO um sistema de papéis** | Ele foi explícito: *"por hora não vai ter perfil nem nada"*. A coluna responde uma pergunta só — **quem abriu a porta** — e divide o mundo em dois: mexer no **dinheiro** (todos) e mexer na **porta** (só o dono). Sem ela não dá para impedir que a convidada remova o dono do próprio ambiente, nem para mostrar "ambiente de Abner" na lista dela: hoje ninguém sabe quem criou o quê |
| B-D76 | **Porta = convidar, remover acesso, renomear e apagar. Dinheiro = todo o resto** | Renomear entra na porta porque o nome é **um só** e aparece na lista de todos, inclusive na do dono. O resto — lançar, editar e excluir lançamento de qualquer um, criar e encerrar conta, criar e encerrar cartão, fechar e pagar fatura, transferir — é controle total, como ele pediu |
| B-D77 | **O dono remove qualquer um; qualquer um remove a si mesmo; o dono não sai** | Se só o dono removesse, a convidada ficaria presa num ambiente que não pediu. E o dono sair deixaria um ambiente órfão, sem ninguém que possa convidar ou apagar — o caminho para se livrar dele é apagá-lo, que é a conversa adiada |
| B-D78 | **B-D18 APERTA: vincular e desvincular conta de ambiente exige ser DONO dos dois lados** | B-D18 dizia "só se vincula conta que já se enxerga", e o raciocínio estava certo para a época: enxergar significava **ser dona**. Com compartilhamento, passa a significar "minha **ou emprestada**" — e a convidada poderia levar a conta conjunta para o ambiente pessoal dela e lançar de lá, invisível ao dono. Desvincular tem o mesmo peso: tirar a conta do único ambiente em que ela aparece a esconderia de todo mundo, dono incluído |
| B-D79 | **Isto FECHA o I-23**, e não o adia | O I-23 — saldo parcial em conta compartilhada — estava aberto desde 26/07 com a nota "só dói quando existir convite". O convite chegou e ele não vai doer: com B-D78, todo lançamento daquela conta nasce no mesmo ambiente, e as duas pessoas veem o mesmo saldo, a mesma fatura e o mesmo mapa. **Não foi contornado — o desenho escolhido não cria a divergência** |
| B-D80 | **Acesso concedido é imediato, sem aceite** | Coerente com "dei meu acesso": o ambiente aparece na lista dela na hora, e ela sai com um clique se não quiser. Um fluxo de aceite traria três estados, mais uma tela, e a dúvida de "ela viu ou ignorou?". Custo assumido: dá para empurrar um ambiente para a lista de alguém |
| B-D81 | **E-mail não cadastrado responde 404 dizendo isso** | É um oráculo de enumeração: quem tem conta descobre, um por vez, se um e-mail qualquer está cadastrado. **Aceito conscientemente**, e a alternativa era pior no caso real — responder "ok" para um e-mail digitado errado esconderia o erro mais comum de todos, e a pessoa descobriria dias depois que o convite nunca chegou. Se um dia isto sair da rede de casa, a decisão se revisita |
| B-D82 | **A auditoria não muda em nada** | `fn_auditar` lê `raspybank.usuario_id` da **sessão**, não o dono do ambiente. Toda ação da convidada já nasce carimbada com o nome dela — que é exatamente o que ele pediu (*"no log só marcar que a ação feita no ambiente foi feita pelo usuário que ganhou o acesso"*). Zero código |

| # | Decisão | Motivo |
|---|---|---|
| B-D83 | **Toda requisição confere se o `ambienteId` do token ainda pertence a quem o apresenta.** Se não pertence: **403 com frase**, não silêncio | A convidada pode estar trabalhando dentro do ambiente quando o acesso é revogado, e o JWT dela vale mais quinze minutos. A RLS já corta os dados — nada indevido aparece —, mas a tela ficaria **vazia e sem explicação**: contas zeradas, mapa em branco, nenhum erro. A conferência é uma busca por chave primária em `usuario_ambiente`, e `usuarioPertence` já existe. **É mais geral que o compartilhamento**: qualquer motivo para o vínculo sumir — revogação hoje, ambiente apagado amanhã — passa a ter a mesma resposta clara, sem código novo por caso |
| B-D84 | **Revogar acesso NÃO derruba as sessões da pessoa** | A proposta inicial dele era logoff geral em todos os dispositivos. Duas coisas a demoveram. **A primeira**: encerrar sessão revoga a **renovação**, não o **acesso** — achado já registrado neste projeto —, então o JWT continuaria vivo e a janela de tela vazia continuaria existindo. Não resolvia o problema que motivou a ideia. **A segunda**: derrubaria também a sessão dela no ambiente **dela**, que não tem relação nenhuma com quem revogou — ela seria deslogada no meio do próprio mercado por causa de uma arrumação alheia. Com B-D83 o efeito desejado acontece na hora, e sem atingir o que não é do assunto |

## O detalhe que só aparece no uso

Este era o ponto: a convidada trabalhando dentro do ambiente na hora da revogação. Fechado por B-D83 — e vale registrar que a primeira ideia, o logoff geral, **não teria fechado**, porque logout não mata o token de acesso.

## O que a V15 vai precisar carregar

- `usuario_ambiente.dono`, com retroalimentação: hoje cada ambiente tem exatamente um membro, e ele é o dono;
- índice parcial garantindo **exatamente um dono** por ambiente, no formato de `ux_cfp_padrao_saida`;
- `app_ambientes_proprios()` e `app_contas_proprias()`, irmãs das que já existem, filtrando por `dono`;
- o `WITH CHECK` de `pol_ca_ambiente` apertado (B-D78);
- uma função estreita para resolver e-mail → id, porque `pol_usuario_proprio` só deixa cada um enxergar a si mesmo.

## O que a implementação acrescentou (V15, 29/07/2026 — a a f, para revisão)

A lista acima valeu inteira. Estas seis vieram por necessidade descoberta no código, no mesmo espírito das decisões numeradas — **sem número de B-D de propósito: os números são das conversas, e estas ainda não passaram por uma**.

**a) O convidado tem nome — política, e não função.** A lista de acessos mostra nome e e-mail de cada membro, e `pol_usuario_proprio` ("cada um enxerga a si mesmo") esconderia os co-membros. Havia dois caminhos: uma função `SECURITY DEFINER` de leitura, ou dizer a regra nova na própria política. Foi a política — `pol_usuario_leitura` passou a ser "eu, e quem divide ambiente comigo" — porque aqui **não há impasse estrutural**: o vínculo existe, a política é que não o consultava. Furar por conveniência é o que B-D19 proíbe; o I-23 tinha acabado de receber a mesma resposta. A escrita continua "só eu" (`pol_usuario_escrita`), e `senha_hash` continua inalcançável por privilégio de coluna (V8). O que o co-membro passa a ver é a linha cadastral: nome, e-mail, telegram.

**b) A porta é auditada.** Conceder e revogar acesso ganharam gatilho (`tg_auditar_usuario_ambiente`, entidade `Acesso`). B-D82 dizia que a auditoria de domínio não muda — e não mudou; o que faltava era a porta em si deixar rastro: "quem colocou fulano aqui, e quando" é exatamente a pergunta que uma trilha existe para responder. Vínculos criados no cadastro entram com autor nulo, como as categorias sistêmicas — o sistema agindo sozinho se registra assim desde a V10.

**c) Dono não se transfere — por ausência de política.** `usuario_ambiente` não tem política de UPDATE: nem o dono promove um substituto, nem um convidado se promove, nem por injeção. Transferência de posse é conversa que não aconteceu; quando acontecer, ganhará política própria.

**d) Renomear e apagar ambiente já encontram o banco certo.** `pol_ambiente_vinculado` (FOR ALL) deixaria qualquer membro alterar a linha do ambiente. Foi dividida: leitura para membros, UPDATE só para o dono, INSERT e DELETE sem política (nascem pelas portas estreitas; apagar é a conversa adiada). Não existe endpoint de renomear — mas quando existir, o RLS já recusa o convidado sozinho, que é como B-D76 manda.

**e) O 403 de B-D83 carrega um marcador estável.** `motivo: "SEM_ACESSO_AO_AMBIENTE"` além da frase — a tela decide pelo marcador, nunca pelo texto. E a conferência não cobre `/api/auth/**`: a renovação é a rota de fuga, e `ambienteParaRenovacao` já fazia a troca silenciosa para um ambiente próprio (foi escrita prevendo este caso).

**f) `dono` nas listagens de ambiente.** `GET /api/ambientes` e o perfil marcam cada item: a lista agora mistura próprios e emprestados, e a tela precisa saber onde há porta e onde escrever "compartilhado comigo".

# 4k. Compartilhamento de CONTA (29/07/2026) — desenho, ANTES do código

Um segundo modo, diferente do §4j e complementar a ele. **Nada aqui foi implementado.**

| | Compartilhar **ambiente** (§4j) | Compartilhar **conta** (§4k) |
|---|---|---|
| Onde a pessoa trabalha | Dentro do ambiente do dono | No ambiente **dela** |
| Categorias | As do dono | As dela |
| Mapa de gastos | Compartilhado | **Separado** |
| Saldo e fatura | Compartilhados | Compartilhados |
| Serve para | "gerimos a casa juntos" | "dividimos uma conta, orçamentos separados" |

| # | Decisão | Motivo |
|---|---|---|
| B-D85 | **O saldo atravessa ambientes; a classificação não.** É a regra que resume o modo inteiro | Descrição dele: cada um vê todos os lançamentos da conta, mas só os próprios trazem categoria — *"no meu mapa de gastos somente aparecem os meus lançamentos, pois eu tenho categoria neles"*. O mapa não precisa de filtro novo para isso: ele já recorta por ambiente, e o lançamento do outro tem categoria de outro ambiente. **A separação cai da estrutura, não de uma regra escrita** |
| B-D86 | **Isto REABRE o I-23, e de propósito** | Uma hora antes, B-D78/B-D79 fecharam o I-23 **proibindo** a conta de ir para outro ambiente. Este modo pede exatamente isso — e a resposta melhor não é proibir o caso, é **definir o que ele significa**. B-D78 continua valendo e não conflita: ali quem levava a conta era o convidado, **unilateralmente**; aqui é o dono **concedendo**. A diferença é quem decide |
| B-D87 | **Três consultas passam a atravessar ambientes; uma continua não atravessando** | Saldo, extrato e fatura precisam somar tudo — senão os dois veem números diferentes e, no caso da fatura, alguém paga menos do que deve e descobre com juros. O mapa continua recortado por ambiente, e não muda nada. Fazer as três atravessarem exige `SECURITY DEFINER`, e **o impasse é real e inevitável**: por construção uma pessoa não pode ver os lançamentos da outra pela política, e mesmo assim precisa somá-los. Seria a **quarta exceção** do projeto (B-D19) e a primeira em consulta de leitura |
| B-D88 | **Consequência de privacidade, assumida em voz alta**: cada um passa a saber que um valor se moveu sem ver no quê | É o próprio ponto de uma conta dividida — mas é escolha, não detalhe técnico, e por isso está escrita |
| B-D89 | **O extrato mostra do lançamento alheio: valor, data, forma de pagamento e quem.** Sem categoria e **sem descrição** | Basta para o saldo bater com o extrato do banco. A descrição fica de fora junto com a categoria pelo mesmo motivo prático: é texto livre, e é onde as pessoas escrevem o que não pretendiam dividir — *"presente da Luciana"* é exatamente o caso |
| B-D90 | **Quem recebe escolhe em qual ambiente dela a conta aparece, ao aceitar** | Diferente do compartilhamento de ambiente, que é imediato (B-D80): aqui há uma escolha que **só ela pode fazer**, e não dá para adivinhar. Cair no ambiente ativo mandaria a conta doméstica para o PJ sem aviso, e os gastos iriam para o mapa errado até alguém notar — e notar é difícil, porque nada avisa. O dono escolher entre os ambientes dela também não serve: exporia como ela organiza a própria vida, o que a conta compartilhada não pedia |
| B-D91 | **Só o dono do ambiente onde a conta vive pode compartilhá-la** | Coerente com B-D76: repartir acesso é **porta**, não **dinheiro**. Quem recebeu acesso ao ambiente usa a conta à vontade, mas não a passa adiante — senão o acesso se espalha sem o dono saber, e ele só enxergaria a lista final, nunca a cadeia |

## O schema já aguenta, e isso vale saber antes de desenhar demais

O lançamento dela numa conta sua **já é estruturalmente válido hoje**. A chave composta `(ambiente_id, conta_id) → conta_ambiente` só exige que a conta esteja ligada ao ambiente dela — que é o que o compartilhamento cria. E `(ambiente_id, categoria_id) → categoria` já garante que ela use categoria do ambiente dela.

Nenhuma tabela nova para o lançamento. O que falta é a **permissão para criar aquele vínculo** e as **consultas que atravessam**.

## A execução, decidida antes do código (29/07/2026) — B-D92 a B-D97

Ele pediu que os quatro furos da execução fossem discutidos antes de qualquer linha, e a leitura do código antes da conversa mostrou que **dois deles eram piores do que o desenho de princípio supunha**. Os achados vêm primeiro porque foram eles que moldaram as decisões.

### Achado 1 — do jeito que a V15 ficou, a convidada tomaria a conta

`app_contas_proprias()` significa *"conta ligada a um ambiente de que eu sou dono"*. Depois do compartilhamento, a conta do Abner está ligada ao ambiente **dela**, que ela é dona — então, para o banco, a conta dele passa a ser **própria dela**. Duas consequências, ambas com política já escrita:

- `pol_ca_desvincular` a deixaria **desvincular a conta do ambiente do dono**, e a conta desapareceria para ele;
- `pol_ca_vincular` a deixaria repassar a conta para um terceiro ambiente dela — que é exatamente o que B-D91 proíbe em palavras e o banco não.

Não é um furo da V15: é um significado que só se rompe quando a conta passa a viver em ambiente alheio, e até aqui isso não existia.

### Achado 2 — revogar não pode ser um DELETE

`fk_lancamento_conta (ambiente_id, conta_id) → conta_ambiente` é `ON DELETE RESTRICT`. Assim que ela lançar **uma vez**, apagar o vínculo é recusado pelo banco. E apagar os lançamentos dela junto seria pior que o erro: aquele dinheiro saiu da conta de verdade, então o saldo do dono passaria a divergir do extrato do banco — o sintoma que P1 e R1 existem para não acontecer.

| # | Decisão | Motivo |
|---|---|---|
| B-D92 | **`conta_ambiente` ganha `origem`, um booleano: o ambiente onde a conta nasceu** | É o Achado 1, e o conserto é o mesmo idioma de `usuario_ambiente.dono` (B-D75) — um booleano que responde uma pergunta só, com índice único parcial garantindo **uma origem por conta**. `app_contas_proprias()` passa a exigir `dono AND origem`, e aí "minha conta" volta a significar minha. Vincular a **própria** conta num segundo ambiente seu (B-D18) continua valendo e nasce sem origem, porque a conta já nasceu em outro lugar |
| B-D93 | **Revogar é `encerrado_em`, não `DELETE`** | É o Achado 2. O vínculo encerrado some da vista dela e os lançamentos ficam onde estão — no ambiente dela, com as categorias dela, somando no saldo dele, porque o dinheiro realmente passou pela conta. Custo assumido em voz alta: **toda** leitura de conta passa a depender de um filtro, e esquecê-lo em um lugar ressuscita o acesso em silêncio. Por isso o filtro mora dentro de `app_contas_do_usuario()`, num lugar só, e não espalhado por política e consulta |
| B-D94 | **O convite é uma linha que SOME ao ser resolvido** | Aceitar cria o vínculo e apaga o convite; recusar só apaga. A verdade do compartilhamento é **o vínculo**, nunca uma coluna de situação — duas fontes para o mesmo fato é o defeito que o I-01 já custou uma vez neste projeto. A trilha que uma situação permanente daria ("quem convidou, quem recusou, quando") fica em `registro_auditoria` pelo gatilho, que é onde ela pertence |
| B-D95 | **Ela lança e paga. Renomear, encerrar, mexer nas formas de pagamento e repassar adiante são do dono** | Coerente com B-D76 e com B-D91: a conta aparece na tela de duas pessoas, e quem abriu a porta responde por ela. Consequência concreta: `pol_conta_vinculada` (`FOR ALL`) se divide em leitura e escrita, como a V15 fez com `ambiente`. Diferença que vale marcar contra o §4j: **lá** encerrar conta era dinheiro (B-D76), porque quem encerrava estava dentro do ambiente do dono; **aqui** ela não está |
| B-D96 | **As três funções que atravessam, com o mesmo porteiro na primeira linha** | `app_saldo_da_conta(conta)` → realizado e previsto; `app_extrato_da_conta(conta, inicio, fim)` → as linhas de B-D89; `app_total_da_fatura(fatura)` → compras e pagamentos. Cada uma começa conferindo `conta_id IN (SELECT app_contas_do_usuario())` e levanta exceção se não — sem isso, `SECURITY DEFINER` viraria "leia qualquer conta do sistema pelo UUID". É a **quarta exceção** de B-D19 e a primeira em leitura, com o impasse de B-D87: por construção uma pessoa não pode ver os lançamentos da outra pela política, e mesmo assim precisa somá-los |
| B-D97 | **O recorte de B-D89 mora NA FUNÇÃO, não na tela** | `app_extrato_da_conta` não devolve `descricao` nem `categoria_id` do lançamento alheio — não é filtro de exibição, é coluna que não sai do banco. A tela não tem como vazar o que nunca recebeu, e um `JSON` distraído no controlador não vira incidente de privacidade |

# 4l. Cartão compartilhado (29/07/2026) — B-D98 a B-D103

Pedido dele na mesma conversa: *"compartilhamento de conta e depois de cartão"*. Desenhado junto, entregue depois — a V16 leva conta, a V17 leva cartão.

**O que já estava respondido pela estrutura, e por isso não foi discutido:** o cartão **é** uma conta (B-D47, `cartao.conta_id` é PK e FK), então compartilhar cartão é compartilhar a conta do contrato — nenhum mecanismo novo, exatamente como B-D74 disse do ambiente. E o pagamento da fatura tem duas pernas (B-D59): se ela paga, a perna bancária é conta dela, no ambiente dela, e a perna do cartão cai no ambiente dela também, porque é lá que o cartão compartilhado aparece. Estruturalmente pronto; o que faltava era decidir o produto.

| # | Decisão | Motivo |
|---|---|---|
| B-D98 | **Compartilhar cartão é compartilhar a conta do contrato.** Sem tabela nova, sem convite próprio | O cartão é uma conta desde a V12. Um segundo mecanismo de compartilhamento significaria duas portas para a mesma pergunta ("quem vê esta conta?") e duas chances de divergirem. O que muda em relação a uma conta comum é o que se pode **fazer** com ela — B-D100 e B-D101 — e não como o acesso nasce |
| B-D99 | **Os dois pagam a fatura, cada um da própria conta bancária, e a quitação soma os dois** | É o caso real de cartão dividido: cada um paga a parte dele, e pagamento parcial de cada lado é legítimo desde B-D57. Custo assumido: o dono vê a fatura quitada **sem ver de qual conta saiu o dinheiro dela** — a perna bancária vive no ambiente dela e não atravessa. É B-D88 outra vez, e a alternativa (ela transfere e ele paga) transformaria um pagamento na vida em duas operações no sistema, perdendo o rastro de quem pagou o quê |
| B-D100 | **Fechar fatura é dos dois; reabrir é só do dono** | Fechar é rotina de mês e quem está dentro faz. Reabrir **desfaz o que o outro fez** num flag que os dois veem, e é aí que duas mãos no mesmo ciclo viram briga. As duas regras saem de **duas políticas permissivas** separadas pelo valor de `fechada_em` na linha nova — não é regra em código, é RLS, e por isso vale para qualquer caminho que um dia atualize a fatura |
| B-D101 | **Emitir adicional e encerrar o cartão são porta do dono** | Emitir cria plástico sob o limite do contrato **dele**, e encerrar tira o cartão da tela dos dois. Mesma linha de B-D95, e a simetria importa: a lista do que ela pode fazer é a mesma numa conta comum e num cartão, mais fechar fatura |
| B-D102 | **O extrato alheio mostra a parcela — `3/10` — e as futuras** | As próximas parcelas são dinheiro **dele** preso no limite **dele**, e faturas de meses que ainda não chegaram já nascem com valor comprometido. Sem isso, o limite informativo (B-D48) para de servir para planejar exatamente no caso em que planejar importa mais. Custo em voz alta, e é maior que o de B-D89: *"3/10 de R$ 200"* revela que a compra foi de R$ 2.000 — mais do que um lançamento avulso revelaria |
| B-D103 | **`cartao_emitido.usuario_id` continua nulo. Quem comprou vem de `criado_por`** | A V12 reservou o campo esperando o convite (B-D53), e a hipótese natural era ligá-lo agora, restringindo cada pessoa ao próprio plástico. Ele escolheu o contrário — ela seleciona qualquer emitido — e **o argumento que eu usei contra estava errado**: `lancamento.criado_por` já carimba quem lançou, e é dele que o "quem" de B-D89 sai. O que a escolha custa é só o extrato **por plástico** ficar torto se ela selecionar o cartão errado; o caso dos dezessete cartões segue atendido |

## O que a implementação acrescentou (V16 e V17, 29/07/2026 — a a g, para revisão)

As decisões acima valeram inteiras. Estas sete vieram por necessidade que só o código revelou — **sem número de B-D de propósito: os números são das conversas, e estas ainda não passaram por uma**. Duas delas são defeitos que os testes apanharam, e estão marcadas.

**a) `app_contas_nao_emprestadas()`, e por que `app_contas_proprias()` não servia.** B-D95 diz "renomear e encerrar são do dono", e B-D76 diz que encerrar conta é **dinheiro** — quem entra no ambiente por convite pode. As duas estão certas e falam de casos diferentes, mas um predicado só não atende as duas: "ser dono" tiraria do convidado do ambiente um poder que B-D76 lhe deu. A regra que satisfaz as duas é **estar no ambiente onde a conta nasceu**, e ela virou a terceira irmã de `app_contas_do_usuario`. **Defeito apanhado por `CompartilhamentoApiTest.convidadaOperaODinheiro`**, que existia desde a V15 e falhou na primeira rodada da V16.

**b) O limite do cartão não atravessava — e é o número que existe para bater com o app do banco.** `limiteConsumido` vinha de uma soma que respeita a RLS, então as compras de quem divide o cartão **não entravam**: o dono via 0,00 consumido num cartão com R$ 2.000 comprometidos em parcelas dela. B-D48 diz que o limite é informativo, e informativo errado é pior que ausente. **Defeito apanhado por `CartaoCompartilhadoApiTest.aParcelaAlheiaAparece`**, na última asserção — a que eu quase não escrevi.

**c) O convite precisou de duas funções que o desenho não previu.** Antes do aceite, **a conta é invisível para quem foi convidado** (a política pede o vínculo que o aceite vai criar) e **o convidado é invisível para quem convida** (`pol_usuario_leitura` é "eu, e quem divide ambiente comigo"). Sem elas, o convite chegaria como "alguém quer dividir algo com você" e a lista do dono mostraria um uuid. São impasses da mesma natureza de `app_usuario_por_email` — um deles é literalmente o mesmo pelo avesso.

**d) Revogar também virou função, pelo motivo mais curioso do inventário: o dono precisa encerrar uma linha que ele não pode ver.** `pol_ca_leitura` mostra a cada um só o próprio lado do vínculo, e isso é deliberado (B-D90). A alternativa era alargar a política e entregar ao dono os ids dos ambientes dela — pouco em aparência, e o suficiente para contar quantos são e correlacionar entre contas.

**e) `podeCompartilhar` é um campo separado de `origem` na resposta da API.** São perguntas parecidas que não são a mesma: `origem` responde quem mexe no dinheiro da conta, `podeCompartilhar` responde quem mexe na porta. Confundi-las foi a primeira versão do código, e o sintoma foi um 500 na tela de contas de quem entrou no ambiente por convite.

**f) O banco do cartão dividido ganhou frase própria.** O contrato aponta para uma conta de outra pessoa, invisível para quem recebeu o cartão — e o texto de fallback que existia, "(conta removida)", **mentiria**: a conta existe e está bem. Agora responde "(banco de quem dividiu o cartão)", que é verdade nos dois casos.

**g) A conferência do banco na compra deixa de existir no cartão dividido.** `POST /api/lancamentos` conferia que o cartão pertence à conta escolhida — proteção real contra escolher Nubank e gravar no C6. Para quem recebeu o cartão, o banco do contrato é invisível, e a pergunta não tem resposta possível do lado dela. **A checagem não afrouxou: ela deixou de existir num contexto em que não significa nada**, e continua inteira no caminho normal. Foi o único ponto em que o desenho de §4l passou por cima de uma regra existente sem ter previsto.

# 4m. Correções pedidas por ele no primeiro uso (29/07/2026) — B-D104 e B-D105

Ele foi testar o compartilhamento de cartão e bateu numa recusa minha. As duas decisões abaixo saíram daí.

| # | Decisão | Motivo |
|---|---|---|
| B-D104 | **Ter acesso ao ambiente NÃO impede receber a conta.** As duas concessões convivem e são independentes | Frase dele: *"dar acesso ao ambiente inteiro é a mesma coisa que dar minha senha. Porém ainda sim eu preciso poder escolher uma conta que quero que ela tenha acesso lá do ambiente dela"*. O 409 que eu havia posto — *"ela já vê esta conta"* — **era um erro conceitual meu, não uma decisão dele**: confundia **ver** a conta com **ter** a conta. Quem entra no meu ambiente trabalha dentro dele, com as MINHAS categorias e no MEU mapa; a conta dividida aparece no ambiente DELA, com as categorias dela e no mapa dela. O §4k abre chamando o segundo modo de *"complementar"* ao primeiro, e a recusa proibia exatamente a complementaridade. Independência confirmada por ele: revogar a conta não tira o ambiente, e remover o ambiente não tira a conta — foram dois atos, em momentos diferentes |
| B-D105 | **O `telegramId` entra no cadastro, opcional, com validação frouxa e sem caminho de edição** | Pedido dele para preparar a etapa 3. A coluna existe desde a V1 e o caminho de escrita não existia por impasse real: no cadastro não há identidade na sessão, então `pol_usuario_escrita` recusa qualquer UPDATE posterior — o valor tem de entrar na mesma inserção, e a função do cadastro ganhou um quarto parâmetro (V18). **A validação é frouxa de propósito**: o bot não existe, e não há como saber se ele vai querer o id numérico ou o `@usuario` — regra apertada agora tem chance de barrar o valor certo, e valor errado não causa dano enquanto não há consumidor. **Custo registrado**: sem caminho de edição, quem digitar errado hoje não corrige sozinho. É pequeno porque o campo não é o login (contraste com B-D71, onde a ausência da edição é a proteção) |

## O que a implementação acrescentou (h)

**h) O vazio tem de virar nulo, e isso não é higiene — é o índice.** `ux_usuario_telegram` é **parcial** (`WHERE telegram_id IS NOT NULL`), justamente para várias contas conviverem sem Telegram. String vazia gravada seria um valor *real* para ele, e o **segundo** cadastro sem Telegram falharia por duplicidade num campo que ninguém preencheu. `NULLIF(btrim(...), '')` mora dentro da função, e não na tela, porque a tela não é o único caminho.

# 4n. O PLÁSTICO como unidade (30/07/2026) — B-D106 a B-D110

Ele foi usar o cartão compartilhado da V17 e o modelo estava errado. Descrição dele, e é o que reorganiza tudo:

> *"A fatura no mundo real é composta por várias mini faturas. Eu recebo uma fatura que traz o extrato de cada um dos cartões mas com um montante só no final para pagar. (…) Tenho um contrato que vence em um determinado dia com limite total de 30 mil. Crio um adicional em nome da Luciana, que segue o mesmo fechamento e vencimento, e que eu posso opcionalmente dizer que tem 1.000 dentro dos meus 30.000. A questão do compartilhamento é poder dar para ela, lá no meio de pagamento do lançamento, a possibilidade de apontar este cartão adicional que está em nome dela porém dentro da minha fatura."*

**O que o modelo já tinha certo:** a fatura pertence ao contrato, o lançamento sabe qual plástico fez a compra (`cartao_emitido_id`, B-D64/V13) e `limite_proprio` existe desde a V12. As "mini faturas" são um agrupamento do que já está gravado — nenhuma estrutura nova para isso.

**O que estava errado:** a V17 fez a unidade do compartilhamento ser a **conta do contrato**, então dividir um cartão entregava os dez plásticos.

| # | Decisão | Motivo |
|---|---|---|
| B-D106 | **A unidade é o PLÁSTICO** (`cartao_emitido`), nunca o contrato. **Revoga B-D98** | *"Quero compartilhar com ela somente 1 cartão virtual."* O contrato é dele e continua sendo; o que se reparte é um plástico dentro dele, com o limite próprio que ele opcionalmente definiu. O vínculo da conta do cartão com o ambiente dela continua existindo — sem ele o lançamento dela não tem onde morar (chave composta de B-D2) —, mas ele deixa de ser a **concessão**: passa a ser consequência dela. Custo assumido: **plástico emitido depois NÃO vai junto**, tem de ser dividido à parte. É o preço de a unidade ser explícita |
| B-D107 | **Quem paga a fatura é o dono do contrato. Ela não paga** | *"No final a conta vai chegar com o total e eu sou o responsável para fazer esse pagamento, seja ela me transferir ou seja eu pagar essa fatura de alguma conta minha."* **Revoga B-D99**, escolhido no dia anterior, e ele sabia disso ao decidir: *"talvez vamos regredir sim, mais pra frente a gente monta uma opção de cada um pagar uma parte e até mesmo ela pagar uma fatura inteira — a gente estuda isso depois"*. Fica como **conversa adiada**, não descartada |
| B-D108 | **Fechar e reabrir fatura voltam a ser só do dono. Revoga B-D100** | Consequência de B-D107, e ele confirmou: a fatura é o documento do contrato, e quem responde por ela paga por ela. Ela agiria sobre um ciclo que não paga e cujo total não vê. Some a política `pol_fatura_fechar`, que a V17 tinha criado para o membro |
| B-D109 | **B-D89 CONFIRMADO no cartão: a descrição do lançamento alheio continua fora.** Só o "quem", o valor, a data, o plástico e a parcela | Ele considerou reverter — o exemplo dele mostrava "youtube, 50" e "claude, 100" identificados — e **decidiu manter**. O que fica privado, na frase dele, é *"a categoria, pois isso é de cada um"*; a descrição fica junto pelo motivo de B-D89, que não mudou: é texto livre, e é onde se escreve o que não se pretendia dividir. Custo aceito: conferir o extrato contra o e-mail do banco fica pior, porque as linhas do outro dizem só "compra de Abner" |
| B-D110 | **Ela vê o extrato do PLÁSTICO dela, não o do contrato.** E não vê o total da fatura | Ela tem acesso a um plástico, então o extrato que a tela dela mostra é o daquele plástico — com as compras de **todos** que têm acesso a ele (dele e dela), que é o caso do "cartão de assinaturas" que os dois usam. Os outros nove não entram: ela não tem acesso a eles. O total do contrato também não, porque ela não paga (B-D107). O limite exibido é o **do plástico**; sem limite próprio, é o do contrato — e isso ele aceitou explicitamente: *"privacidade só existe se ela fizer um cartão exclusivo pra ela com contrato em banco"* |

## O que isto custa em código já escrito

A V17 saiu ontem e a V19 desmonta a parte dela que decidia **quem pode o quê**: a unidade, o pagamento e o fechamento. O que **sobrevive inteiro** é a maquinaria de atravessar ambientes — `app_total_da_fatura`, `app_extrato_da_fatura`, as políticas de leitura de `cartao` e `fatura`, e o `limiteConsumido` que passou a somar os dois lados. Era a parte difícil, e ela não dependia da unidade.

Vale registrar sem rodeio: a V17 foi desenhada e implementada no mesmo dia, com decisões tomadas antes do código — e ainda assim o modelo de negócio só apareceu quando ele **usou** a tela. Nenhuma quantidade de desenho substitui isso.

# 4o. O escopo segue o ambiente ativo (30/07/2026) — B-D111 e B-D112

Ele foi usar o cartão dividido e trouxe duas coisas. A primeira parecia um defeito do compartilhamento e não era; a segunda derrubou uma preocupação minha com um argumento que eu não tinha feito.

## O que ele viu, e o que o banco mostrou

> *"Compartilhei um cartão e a outra pessoa teve acesso a todos os cartões."*

Fui olhar os dados dele. `cartao_emitido_ambiente` tinha **exatamente uma linha** — o compartilhamento por plástico gravou o que devia. Simulando a sessão dela com o RLS ligado, ela enxergava os três. A causa estava em outra tabela:

```
Luciana Akemi | Financas de Abner | dono = false
```

Ela é **membro do ambiente dele** (V15), e por B-D76 quem entra no ambiente vê tudo que é dinheiro. Não é defeito: é a decisão dele, na frase dele — *"dar acesso ao ambiente inteiro é a mesma coisa que dar minha senha"*. Ele escolheu manter.

**Mas havia um defeito de verdade junto**, e é o que B-D111 corrige.

| # | Decisão | Motivo |
|---|---|---|
| B-D111 | **O escopo do que se vê segue o AMBIENTE ATIVO, e não a soma dos acessos da pessoa** | A RLS é por **usuário** (R7), então quem tem os dois acessos — membro do ambiente dele **e** dona de um plástico — via os três plásticos **inclusive dentro do ambiente dela**, onde só o dividido deveria estar. Não vaza informação nova (ela alcança tudo trocando de ambiente), mas a tela **mente sobre o que foi dividido**, e é ela que a pessoa usa para entender o que deu. São duas perguntas diferentes, e confundi-las foi o defeito: *"esta linha aparece nesta tela?"* é do ambiente aberto; *"posso ler este texto?"* continua sendo da pessoa — por isso `meu` segue por usuário, já que o texto de um lançamento meu é meu em qualquer ambiente meu |
| B-D112 | **O banco do cartão dividido tem NOME para quem recebeu** | Eu tinha escondido atrás de *"(banco de quem dividiu o cartão)"*, tratando como privacidade. Ele derrubou com o que eu não tinha observado: **o nome do cartão já entrega o banco** — *"ultravioleta é Nubank, samsung é Itaú, porto é Porto Seguro"* — e ele foi direto ao ponto: *"compartilhar não tem premissa de privacidade"*. Esconder era proteção que não protegia nada e ainda deixava a tela dela incoerente. Só o **nome** atravessa: a conta continua sendo dele, e `exigirContaNoAmbiente` continua recusando qualquer lançamento que a aponte |

## A consequência na tela, que era o pedido original

No seletor de conta dela entra **"Nubank de Abner"** — com o dono no rótulo, porque ela pode ter um Nubank também. Escolhendo esse banco, o combo de "como foi pago" mostra **só os plásticos daquele banco que ele dividiu**: nada de débito, nada de pix, porque a conta não é dela.

Antes disso o cartão dividido aparecia embaixo de **todas** as contas dela — escolher "C6 dela" e ver "UltraVioleta de Abner" era exatamente o lixo que ele apontou, e uma incoerência que eu tinha criado.

# 4p. A situação de uma compra de cartão segue a FATURA (09/08/2026) — B-D113

Origem: ele fechou uma fatura à mão, pagou o total, e os seis lançamentos dentro dela continuaram `PREVISTO`. Registrado como I-29 antes de virar decisão.

| # | Decisão | Motivo resumido |
|---|---|---|
| B-D113 | **Compra de cartão é `REALIZADO` se, e somente se, a fatura estiver FECHADA e QUITADA. Em qualquer outro caso, `PREVISTO` — independente da data.** B-D9 continua valendo para todo o resto | A data de caixa de uma compra é o vencimento da fatura (F14), e vencimento é uma **previsão** de quando o dinheiro sai. Derivar a situação dela errava nos **dois** sentidos: fatura paga antes do vencimento mantinha as compras previstas — a fatura dizia QUITADA no cabeçalho e "ainda vai sair" em cada linha — e fatura vencida e não paga virava tudo para realizado no dia do vencimento, afirmando no mapa um gasto que não houve. Palavras dele: *"fechei a fatura manual e fiz o pagamento dela, deveria tirar esses lançamentos de previsto"* |

## Por que FECHADA *e* quitada, e não quitada sozinha

A recomendação na conversa foi "quitada sozinha", com o argumento de que exigir fechada quebraria a antecipação (B-D57). **O argumento estava errado**, e ele escolheu o outro caminho: antecipação libera limite por `consumido() = saldo.comPrevistos().abs()`, que soma previsto e realizado igual — nunca dependeu da situação das compras.

E exigir *fechada* elimina de graça um efeito ruim: com "quitada sozinha", pagar por inteiro uma fatura **aberta** realizaria tudo, e a próxima compra a cair nela desfaria a quitação e mandaria todas as compras de volta para previsto. Fatura fechada não recebe lançamento novo (`exigirFaturaAberta`), então o total para de se mexer e **a regra fica estável por construção**.

| | não quitada | quitada |
|---|---|---|
| **aberta** | PREVISTO | PREVISTO |
| **fechada** | PREVISTO | **REALIZADO** |

**Pagamento parcial não aloca nada.** Não há regra que diga quais compras foram pagas com 100 de uma fatura de 282, e inventar uma seria inventar dado. Não quitada é previsto, inteira — e os 100 que saíram do bolso aparecem na perna de SAÍDA do pagamento, na conta corrente, que segue a própria data.

## O que a implementação acrescentou

**Recalcula na leitura, não vira no pagamento.** A quitação muda por caminhos demais — pagar, excluir um pagamento, editar o valor de um pagamento, editar o valor de uma compra, lançar compra em fatura aberta, fechar, reabrir, e um dia o crédito do I-25. Um flip por caminho são sete lugares para esquecer um, e o esquecido não dá sinal. `SituacaoVencidaServico` virou **`SituacaoServico`** com as duas regras e um ponto de entrada (`sincronizar`), porque um nome que descreve metade do trabalho é um nome que mente.

**O recorte tem três cláusulas, e nenhuma é dispensável.** Dentro de uma fatura convivem a COMPRA, a perna de ENTRADA do pagamento (também na conta do cartão) e a perna de SAÍDA do pagamento (conta corrente, e **também com `fatura_id`**, por B-D59). Só a primeira é governada: `conta_id` = conta do cartão **E** `fatura_id` preenchido **E** categoria ≠ `PAGAMENTO_FATURA`. Filtrar só por `fatura_id` congelaria o pagamento inteiro — é a forma exata do defeito do I-24.

**O UPDATE só toca linha que muda** (`situacao <> :alvo`). Não é otimização: `tg_auditar_lancamento` e `tg_outbox_lancamento` disparam em UPDATE por linha, e sem a cláusula toda leitura de tela encheria a trilha de auditoria e o outbox de eventos que não contam mudança nenhuma.

**Os totais vêm de `app_total_da_fatura`**, não do repositório: o total de uma fatura atravessa ambientes (B-D87/B-D96), e a soma recortada pela RLS faria uma fatura parecer quitada por faltar compra de quem divide o cartão — realizando as compras do dono sem ninguém ter pago.

**Só para cartão próprio do ambiente** (B-D107/B-D108), pela mesma razão de `fecharVencidasSePuder`: quem recebeu um plástico não paga a fatura nem fecha o ciclo.

## O que isto custou, e foi aceito

- **A virada é de mão DUPLA**, ao contrário da regra da data. `corrigirSituacao` (B-D22) deixa de valer para compra de cartão — a próxima leitura desfaz a correção. Defensável: a situação de uma compra deixou de ser julgamento e virou fato derivado da fatura.
- **Reabrir uma fatura paga devolve tudo para previsto**, e fechar de novo devolve para realizado. Coerente com B-D50.
- **Fatura nunca paga mantém as compras previstas para sempre.** É dívida em aberto, e mostrar isso é informação — mas o mapa acumula previsto antigo.

Verificada por `SituacaoDeCompraNoCartaoTest` (6 casos), que falha em 3 deles contra o código sem a correção. `SituacaoVencidaTest` continua verde: B-D9 não foi tocado.

# 4q. Data de tela é data LOCAL (09/08/2026) — B-D114

| # | Decisão | Motivo resumido |
|---|---|---|
| B-D114 | **Nenhum campo de data da tela é preenchido com `toISOString()`.** O helper é `hojeISO()`, que usa os getters locais | `toISOString()` devolve UTC, e das 21h em diante em São Paulo ele abre o campo com **amanhã**. Um pagamento feito às 22h nascia com `data_caixa` do dia seguinte e, por B-D9, `PREVISTO` — a fatura ficava quitada com o próprio pagamento previsto. É a mesma armadilha que B-D8 já tinha resolvido no banco (`date` em vez de instante), escapada na tela. Achada como I-28, no formulário de pagamento da T-06, que era a **única** ocorrência no frontend |

# 4r. O filtro de conta da T-08 ganha par: o cartão (19/08/2026) — B-D115

Origem: relato do Abner de que o filtro de conta "não funciona" para a Luciana — o dropdown abre com as contas dela, escolher qualquer uma deixa o grid em branco, e voltar para "todas as contas" traz tudo de volta. No usuário dele o mesmo filtro funcionava.

Não havia defeito. Ele só tem gasto em conta, ela só tem gasto em cartão — a diferença entre os dois usuários era coincidência de dados, não bug. O que existe é uma **lacuna**: uma compra no cartão é inalcançável pelo filtro de conta, porque o seletor vem de `ContaRepositorio.bancariasDoAmbiente`, que **exclui** conta de cartão de propósito (B-D62), enquanto a compra grava `lancamento.contaId` = a conta do cartão (`LancamentoServico.resolverContaDaCompra`), e o filtro é igualdade crua (`l.contaId = :contaId`). O seletor nunca oferece o valor que faria a compra aparecer.

Decisão dele: a T-08 ganha um seletor de **cartão** ao lado do de conta, filtrando pelos cartões utilizados — próprios e compartilhados.

| # | Decisão | Motivo resumido |
|---|---|---|
| B-D115 | **O filtro de cartão da T-08 escreve no mesmo `contaId`** que o filtro de conta — não é parâmetro novo da API. Funciona porque `cartao.id` **já é** o `contaId` do cartão, por construção (F5/B-D47). Os dois seletores são mutuamente exclusivos por construção: um estado só, `filtros.contaId`, que torna irrepresentável o par "conta X E cartão Y" — que devolveria vazio sempre | Um segundo caminho para a mesma consulta (um parâmetro `cartaoId` à parte) seria uma segunda fonte da verdade para a mesma pergunta, e a segunda é sempre a que envelhece. Custo aceito: o contrato passa a dizer explicitamente que `contaId` também aceita o id de um cartão. **B-D62 não é revogada nem enfraquecida** — vale onde foi decidida, a tela de contas; aqui o cartão é a única forma de alcançar as compras, e aparece em seletor próprio, sem virar "mais uma conta" na lista |

**Por que não filtrar por `cartaoEmitidoId` (o plástico).** As duas pernas do pagamento da fatura não têm plástico e moram na mesma conta do cartão (B-D59); filtrar pelo plástico esconderia o pagamento do extrato do cartão. Filtrar pela conta do cartão devolve o pagamento junto — mas só o que o ambiente ativo movimentou (B-D21), não o extrato inteiro do plástico num cartão dividido (B-D110). Plástico é assunto da tela de cartões, não deste filtro.

Não há migração: nenhuma mudança de schema, só o seletor novo e o contrato explicitando o que `contaId` já aceitava na prática.

# 4s. Extrato completo em `.xlsx` — T-10 (20/08/2026) — B-D116 a B-D119

Origem: o RaspyBank não tinha como tirar os dados de dentro dele — toda leitura é tela, recortada por mês e por ambiente ativo, e nenhum endpoint devolvia arquivo. O desenho anterior (`docs/desenho-t10-relatorios.md`, commit 64fe6e1, 19/08/2026) propunha fila assíncrona: tabela de estado, executor, propagação de identidade entre threads, volume em disco, endpoint binário e polling. A investigação de 20/08/2026 respondeu à pergunta que travava a decisão — *"o relatório demora a ponto de justificar isso?"* — com **não**, e a fila foi recusada.

| # | Decisão | Motivo resumido |
|---|---|---|
| B-D116 | **O relatório é síncrono, com teto de 12 meses por pedido.** | A fila foi desenhada e **recusada**: o sistema não tem uma linha de assincronismo, e executor + tabela de estado + propagação de identidade entre threads + volume + polling não se pagam para um arquivo de segundos. O teto de 12 meses é o que sustenta a decisão — limita o pior caso a milhares de linhas, não milhões. A evidência não foi estimativa: o Mapa de Gastos já faz a metade cara (`SituacaoServico.sincronizar` + varredura do ano inteiro) a cada abertura. Crescer para fila depois é **aditivo**: o montador não muda, só o transporte. A propagação de identidade em thread de fundo, que B-D43 deixou em aberto, continua adiada — virá com o relay do outbox (I-30), não com o relatório |
| B-D117 | **O relatório atravessa ambientes de propósito, e é a única leitura que o faz.** | B-D111 (o escopo segue o ambiente ativo) continua valendo para toda tela; o arquivo é o oposto por natureza — é o retrato da pessoa, não da tela aberta. Uma aba por ambiente mantém a separação de vidas *dentro* do arquivo. Nenhum privilégio novo foi preciso: `pol_lancamento_ambiente` já usa `app_ambientes_do_usuario()`, e o recorte por ambiente ativo sempre foi Java (B-D21), não isolamento |
| B-D118 | **No `.xlsx`, dinheiro é número e data é data**, não string. | A convenção "dinheiro é string" governa JSON, onde o risco é `double`; numa planilha feita para somar, string é o defeito. `Valor` é **uma** coluna, com o sinal derivado do `tipo`: o banco guarda positivo, mas uma coluna de positivos soma para um número sem significado, e duas colunas de dinheiro convidam a somar a errada |
| B-D119 | **A aba é a T-08 desempacotada.** | Mesmas colunas da tela de lançamentos (`Data · Descrição · Situação · Categoria · Subcategoria · Conta · Pago com · Parcela · Tipo · Valor · Quem`), com as três células que empacotam mais de um fato — `previsto` dentro de Descrição, `3/12` dentro de Pago com, `+`/`−` dentro de Valor — abertas em colunas próprias. É a mesma regra que separa categoria de subcategoria, aplicada até o fim: numa planilha feita para filtrar, célula que carrega dois fatos é defeito. Mais `Quem`, que a tela não tem e a **máscara exige** — sem ela, a linha alheia (Descrição e Categoria vazias) parece dado corrompido. Ordem `data_caixa DESC, criado_em DESC`; o segundo critério não é enfeite, sem ele duas gerações idênticas produzem arquivos diferentes |

## O que sobreviveu do desenho, e o que não sobreviveu

Sobrevivem inteiros: uma aba por ambiente, a máscara da linha alheia (B-D89/B-D109), dinheiro como número na planilha, e a leitura por `SECURITY DEFINER`. Não sobrevivem: o módulo `raspybank-relatorio` (montador e escritor ficam em `raspybank-app` — sem fila não há ciclo de vida a modelar, e um módulo só com um escritor seria camada técnica, que a arquitetura por contexto de negócio proíbe), a tabela `relatorio`, o volume no Pi, os quatro endpoints (viraram um: `GET /api/relatorios/extrato.xlsx`) e o polling de 3s (virou estado de carregamento honesto — cronômetro correndo, botão desabilitado, "demorando" distinto de "falhou"). Ficou **fora do escopo**, decidido e não em aberto: CSV, coluna de saldo corrente, entrega pelo Telegram.

## A V22 cede o número que era da V21

A migração nasceu `V21__extrato_completo.sql`, seguindo a numeração do desenho. Houve colisão: `V21__telegram_e_um_destino_so.sql` já existe em `origin`, na branch `telegram/desenho-e-fatia-1`. Decisão do Abner em 20/08/2026: o relatório cede o número, e a migração foi renomeada para **V22**.

**Consequência para quem for mergear o Telegram depois:** o Flyway grava o checksum de cada versão aplicada e recusa uma versão menor chegando depois de uma maior já aplicada. Com a V22 aplicada neste banco, a `V21__telegram_e_um_destino_so.sql` não pode entrar como V21 — ela precisará ser renumerada para **V23** no merge. Isto não é automático e não há teste que avise sozinho: quem mergear precisa lembrar.

**Há um terceiro pretendente ao número V21**, sem relação com o Telegram: `docs/inconsistencias.md` (I-24, "O que ficou pendente") registra o gatilho que falta para o invariante de `grupo_parcelamento_id` valer também no banco. Três migrações diferentes já disputaram o nome "V21" nesta história — a do Telegram, a do extrato (renumerada para V22) e essa. Quem for escrever a migração do I-24 confere `§6` (Estado das migrações) para o número livre no momento, não este trecho.

## Os três achados que não chegaram a produção

Registrados em detalhe, no formato de defeito real, em `docs/inconsistencias.md` — os três encontrados e corrigidos antes de qualquer relato de uso, dois deles pelo `qa-adversarial`:

- **I-34** (revisão do plano, não teste): o plano mandava derivar a aba da linha alheia só de `conta_ambiente`, preferindo `origem = true`. Como aceitar um plástico vincula a **conta do contrato inteiro** ao ambiente de quem recebeu (V19), essa derivação vazaria, no arquivo dela, as compras de **todos** os plásticos daquele cartão — mascaradas, mas com valor, data e quem. É exatamente o que B-D106 tirou da tela e o que B-D110 recusa. A V22 aplicada corrige isso reproduzindo a regra da V20: `origem` OU conta que não é cartão OU plástico liberado para aquele ambiente.
- **I-35**: dois ambientes com o mesmo nome podiam produzir duas abas de mesmo nome no `.xlsx`, e o formato descarta ou recusa a pasta inteira — um ambiente sumindo do extrato sem aviso. A causa era desempatar sobre um texto diferente do que o `fastexcel` realmente grava; a regra do que pode virar nome de aba passou a morar em `EscritorXlsx`, que agora recusa antes do primeiro byte.
- **I-36**: o 401 de sessão ausente saía com corpo vazio em **todo** endpoint protegido, desde a Fase 4 — não é defeito da T-10, é do sistema inteiro, só exposto porque a §6c passou a prometer corpo explicitamente. `PontoDeEntradaSemSessao` substitui o `HttpStatusEntryPoint` padrão do Spring. Contrato atualizado em `docs/api.md` §1.

## O que a implementação acrescentou

**a) A aba de capa, "Sobre este arquivo".** Três avisos, sem enfeite: a faixa pedida e quando foi gerado; por que existem linhas com Descrição e Categoria vazias (a coluna `Quem` diz de quem); e que o total não bate com o Mapa de Gastos, porque transferência, ajuste e previsto entram no arquivo — o caminho para reproduzir o número do Mapa é filtrar fora `Transferência`, `Ajuste` e `PREVISTO` pelas colunas `Categoria` e `Situação` (a sinalização que o I-31 já apontava como faltante).

**b) `AutoFilter` na linha 1 e painel congelado.** Custa uma chamada no `fastexcel` e é o uso declarado do arquivo — uma planilha para filtrar que abre sem filtro seria a mesma célula empacotada de novo, só que na ferramenta em vez do dado.

**c) O download passou pelo `cliente.js` existente, sem função paralela.** `pedir` ganhou a opção `comoArquivo`: quando a resposta é `ok`, devolve `{ ok, status, blob, nomeArquivo }`; quando não é, continua lendo como texto/JSON, para `lerErro` seguir funcionando. `pedirComRenovacao` só lê `.status` e `.corpo?.motivo`, então vale sem alteração — o download herda de graça a renovação de token e o tratamento do 403 de B-D83. Uma função paralela teria perdido os dois.

**d) O estado de carregamento é a peça que substitui a fila, não um detalhe de tela.** Ela guarda `{ inicio, fim }` da faixa em geração (não um booleano — se a pessoa mexer nos campos enquanto o arquivo vem, a frase continua descrevendo o pedido no ar), cronometra os segundos, e a partir de 15s passa a dizer que demorar é normal, sem trocar de estado para "falhou". É a resposta concreta à pergunta que motivou toda a recusa da fila: *"o usuário não sabe se travou"*.

**e) As frases de erro do teto de 12 meses são cópia literal do servidor**, duplicadas de propósito na tela para a recusa ser imediata: pedir ao servidor para saber o que dizer seria a mesma ida e volta que a validação local existe para evitar, e duas redações do mesmo erro fariam a pessoa achar que são dois problemas.

## O parecer de fronteiras (commit `08d7226`, 20/08/2026)

**Limpo, sem bloqueante.** O `revisor-de-fronteiras` confirmou três garantias que valem
registrar como confirmadas, não como pendência: o recorte por plástico da V22 não tem caminho
de fuga — `cartao.conta_id` é `PRIMARY KEY` (`V12:100`), então `ct.conta_id IS NULL` só é
verdadeiro para conta que **não** é cartão; `ux_ca_uma_origem` (`V16:75`) garante uma origem
por conta; e o `CROSS JOIN LATERAL` **corta em vez de deixar passar** quando nenhum vínculo
sobrevive ao recorte — o modo de falha é "some", não "vaza". A máscara está completa:
`lancamento` tem exatamente duas colunas de texto livre, `descricao` (mascarada) e
`observacao` (que não sai da função). E B-D117 está contido: `app_extrato_completo` tem um
único chamador em todo o código de produção.

Quatro dívidas ficaram registradas, em `docs/inconsistencias.md`, no formato leve de achado
em aberto (nenhuma é defeito ativo, nenhuma bloqueou a entrega): **I-37** (o rótulo de
apresentação da forma de pagamento mora em `FormaPagamentoControlador`, e o montador do
extrato importa `..web..` para chamá-lo — camada ao contrário, sem decisão que a proíba);
**I-38** (a conexão fica presa durante a transmissão do `.xlsx`, com dois efeitos ainda não
escritos: pressão sobre o autovacuum se o teto de 12 meses subir, e um `.xlsx` truncado se
`idle_in_transaction_session_timeout` for configurado); **I-39** (o 401 JSON de
`PontoDeEntradaSemSessao`, do I-36, alcança toda a cadeia de segurança e não só `/api/**` —
consequência do I-36 que ninguém tinha escrito); **I-40** (`MigracoesTest` confere os nomes
das funções `SECURITY DEFINER` mas não o `search_path` delas — a mais valiosa das quatro, e
pré-existente à T-10).

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
| V12 | Domínio, fatia 2: `cartao`, `cartao_emitido`, `fatura` + `fatura_id` e as colunas de parcelamento no lançamento; `PAGAMENTO_FATURA` como quarta sistêmica. **Sem `parcela`** (B-D55) e **sem `regra_recorrencia`** (B-D56, entrega própria). Sai junto da T-06 e resolve I-07. Verificada por `CartaoApiTest` | ✔ |
| V13 | `lancamento.cartao_emitido_id` (B-D64): o lançamento passa a saber qual plástico ou virtual fez a compra, para o extrato da fatura ter dono. `ck_lancamento_cartao_exige_fatura` denunciou um defeito da V12 no primeiro parcelamento | ✔ |
| V14 | `app_criar_ambiente(nome)` (B-D70): porta estreita para criar ambiente adicional pela tela. Verificada por `PerfilApiTest` | ✔ |
| V15 | **Compartilhamento de ambiente** (§4j, B-D74 a B-D84): `usuario_ambiente.dono` com índice parcial de um dono por ambiente; `app_ambientes_proprios`, `app_contas_proprias`, `app_membros_dos_meus_ambientes`, `app_usuario_por_email`; políticas de `usuario_ambiente`, `conta_ambiente`, `ambiente` e `usuario` divididas por verbo; auditoria da porta. Verificada por `CompartilhamentoApiTest` (16) | ✔ |
| V16 | **Compartilhamento de conta** (§4k, B-D85 a B-D97): `conta_ambiente.origem` e `encerrado_em` com retroalimentação e índice parcial de uma origem por conta; `conta_convite`; `app_contas_nao_emprestadas`; as funções que ATRAVESSAM ambientes (`app_saldo_da_conta`, `app_extrato_da_conta`) — a quarta exceção de B-D19 e a primeira em leitura; aceite, revogação e as duas funções do convite. Fecha os dois Achados da seção. Verificada por `CompartilhamentoContaApiTest` (21) | ✔ |
| V17 | **Cartão compartilhado** (§4l, B-D98 a B-D103): `app_total_da_fatura` e `app_extrato_da_fatura`; políticas de `cartao`, `cartao_emitido` e `fatura` divididas por verbo, com **fechar dos dois e reabrir do dono** separados pelo valor de `fechada_em` na linha nova (B-D100). Nenhuma tabela nova — compartilhar cartão é compartilhar a conta do contrato (B-D98). Verificada por `CartaoCompartilhadoApiTest` (8) | ✔ |
| V20 | **Escopo por ambiente e o banco com nome** (§4o, B-D111/B-D112): `app_nome_do_banco_do_cartao`; `app_extrato_da_fatura` ganha o ambiente por parâmetro e recorta por ele. Os plásticos e os números da fatura passam a seguir o ambiente ativo. Verificada por `CartaoCompartilhadoApiTest` (14) | ✔ |
| V19 | **O PLASTICO como unidade** (§4n, B-D106 a B-D110): `cartao_emitido_ambiente`; `conta_convite.cartao_emitido_id`; `app_emitidos_liberados`, aceite, revogação e lista do plástico; `app_total_do_plastico`; `pol_cartao_emitido_leitura` com as duas origens de visibilidade; `app_extrato_da_fatura` recortando por plástico; `pol_fatura_fechar` removida (B-D108). Revoga B-D98/B-D99/B-D100 da V17. Verificada por `CartaoCompartilhadoApiTest` (12), reescrito | ✔ |
| V22 | **O extrato completo** (§4s, B-D116 a B-D119): `app_extrato_completo(inicio, fim)`, a quarta da família de `app_extrato_da_conta` e a única **sem porteiro** — os parâmetros são datas e não carregam autorização. Devolve todos os lançamentos do período em todos os ambientes do usuário, com a máscara de B-D89 sobre descrição, categoria e subcategoria, e com o recorte por plástico herdado da V20. **Nenhuma tabela nova**: a entrega é síncrona (B-D116), então não há pedido, estado nem expiração a guardar. Nenhum índice novo — as duas pernas caem em `ix_lancamento_ambiente_caixa` e `ix_lancamento_conta_caixa`, ambos da V10 | ✔ |
| V18 | **Telegram no cadastro** (B-D105): `auth_cadastrar_usuario` ganha o quarto parâmetro `telegram_id`, com `DROP` + `CREATE` para não deixar duas portas de cadastro. A coluna e o índice parcial existem desde a V1. Verificada por `AutenticacaoFluxoTest` | ✔ |

**Por que não há V21 nesta tabela.** O extrato completo (V22) nasceu `V21`, seguindo o desenho. Houve colisão com `V21__telegram_e_um_destino_so.sql`, já existente em `origin` na branch `telegram/desenho-e-fatia-1` — decisão registrada em §4s. O Abner decidiu que o relatório cede o número; a migração foi renomeada para V22. **Consequência para o merge do Telegram**: o Flyway não aceita uma V21 chegando depois de uma V22 já aplicada — a migração do Telegram terá de virar **V23**, e isso não é automático.
