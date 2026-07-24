# RaspyBank — Registro de Decisões Vigentes

**Versão:** 1.0
**Data:** 23 de julho de 2026
**Status:** Fonte de verdade. Quando qualquer documento, conversa ou código contradisser este registro, ESTE registro prevalece — ou é formalmente revisado.
**Origem:** consolida as decisões das sessões "Estrutura de requisitos funcionais" (20/07), "Reescrita do RaspyBank Systems v1.0" (22–23/07), "Modelo lógico e DER" (23/07) e "Bloco A — refactor de enums" (23/07).

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
| F14 | `data_competencia` e `data_caixa`; no cartão, `data_caixa` é gravada e mantida pela fatura |
| F15 | Lançamento de cartão nasce `REALIZADO`; os demais nascem `PREVISTO` |
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
| F31 | Lançamento guarda `categoria_nome`/`subcategoria_nome` gravados na criação |
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
| B-T1 | **Contrato de erro único** (`TratadorGlobalDeErros`): todo erro responde JSON com a chave `erro` (frase exibível); validação acrescenta `campos` (campo → mensagem). 400 validação, 409 duplicata, 500 interno sem detalhe | As telas precisam de um formato previsível para TODO erro (I-12). Stacktrace/SQL nunca vazam; o detalhe vai para o log. A duplicata é detectada pelo SQLSTATE 23505 na cadeia de causas + nome da constraint — funciona igual para SQL nativo e repositório |
| B-T2 | Os **401 de autenticação NÃO passam pelo tratador global**: os controladores os constroem à mão, num único método (`naoAutorizado()`), com corpo idêntico para token inválido, reuso detectado e sessão ausente | A uniformidade dos corpos 401 é decisão de segurança (B-A8), não de conveniência — qualquer diferença viraria oráculo para atacante |
| B-T3 | **Rotação de token atômica** (I-11): o "já foi usado?" é decidido por `UPDATE ... WHERE usado_em IS NULL` (`marcarUsadoSeInedito`), nunca por leitura+gravação. Na entidade, `vigente()` responde só o que não muda por corrida | Duas renovações simultâneas com o mesmo token: o banco serializa pela trava de linha, exatamente uma vence, a perdedora cai no ramo de reuso e revoga a família. Retry de rede simultâneo paga o preço do ladrão — postura "na dúvida, o pior" mantida. Teste: `renovacoesSimultaneasSoUmaVence` |
| B-T4 | **I-03 resolvido como opção (b)**: renovação normal NÃO gera auditoria (deliberado); **reuso detectado gera** (`ACESSO`, `{"evento":"reuso_token_renovacao"}`). O serviço devolve resultado `sealed` (`Sucesso`/`ReusoDetectada`/`Inválida`) e o compilador obriga o controlador a tratar o caso de reuso | Auditar cada renovação encheria a trilha a cada 15 min de uso; o evento de segurança relevante é o reuso — exatamente o que se quer encontrar na trilha depois. A resposta externa continua indistinguível (B-T2) |
| B-T5 | **Logout por dispositivo** (I-14): o JWT carrega a claim `fam` (família do token de renovação). `/logout` revoga só a família da sessão atual; `/logout-todos` revoga todas. Token antigo sem `fam` cai no comportamento antigo (todas) — o lado seguro do erro | O botão "Sair" das telas significa "sair DESTE dispositivo"; sair de todos é ação de segurança separada. A família já existia no modelo (V4) — faltava transportá-la no token |
| B-T6 | **Renovação preserva o ambiente declarado** (I-15): `/renovar` aceita `ambienteId` opcional; se o vínculo existe (checado via porta sem contexto — no renovar ainda não há identidade na sessão e o RLS devolveria vazio), o token novo o carrega; senão, **fallback silencioso para o primeiro** (a resposta sempre informa o ambiente efetivo). A renovação NUNCA falha por ambiente | Falhar queimaria o token já rotacionado e derrubaria a sessão por problema que não é de credencial. Quem perdeu o vínculo continua num ambiente que ainda é seu, informado na resposta |
| B-T7 | **Troca explícita de ambiente** em `POST /api/sessao/ambiente` (fora de `/api/auth/**`, que é `permitAll`): confere o vínculo com RLS ativo e emite novo token de acesso; o de renovação não muda. Vínculo inexistente → 403 | Trocar de ambiente é reemitir o recorte de dados, não recriar a sessão. O prefixo `/api/sessao` nasce protegido por padrão — a regra da casa para endpoint novo |
| B-T8 | Guard clause em `primeiroAmbienteDe` (I-02): usuário sem ambiente explode com `IllegalStateException` clara (→ 500), nunca token com ambiente nulo | Estado impossível por construção (A12); se acontecer, o sintoma deve ser barulhento, não um token esquisito |

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
| V10 | **Próxima** — domínio: `conta`, `conta_ambiente`, `cartao`, `cartao_emitido`, `fatura`. Validar cada trecho contra F1–F33 antes de aplicar | ✘ |
