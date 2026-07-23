-- =============================================================================
-- V8 — Normalização de convenções
-- =============================================================================
-- Esta migração não cria nada. Ela conserta três coisas que a Fase 2 revelou,
-- antes que as tabelas de domínio sejam criadas em cima delas.
--
-- Separar "consertar o que existe" de "criar o que é novo" em migrações
-- distintas não é preciosismo: se algo falhar aqui, você sabe exatamente que
-- foi normalização, e não modelagem.
--
-- 1. Valores de domínio passam a MAIÚSCULAS
-- 2. senha_hash sai do alcance do usuário de aplicação
-- 3. app_ambientes_do_usuario() passa a respeitar exclusão lógica
-- 4. Auditoria aceita autor desconhecido
--
-- Referência: decisões F1 a F33 do Modelo Lógico v1.0
-- =============================================================================


-- =============================================================================
-- 1. VALORES DE DOMÍNIO EM MAIÚSCULAS
-- =============================================================================
-- Por quê: o JPA grava enum Java com @Enumerated(EnumType.STRING), e o nome de
-- um enum Java é maiúsculo por convenção da linguagem. Com valores minúsculos
-- no banco, cada enum exigiria um conversor próprio — em toda tabela nova, para
-- sempre.
--
-- Existem hoje duas colunas com valores e pouquíssimas linhas. Normalizar agora
-- custa esta migração; normalizar depois custa uma migração maior e um período
-- em que metade do sistema fala uma língua e metade fala outra.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- usuario.status
-- -----------------------------------------------------------------------------
-- A ordem importa e não é intuitiva:
--   1. derrubar a restrição — senão o UPDATE viola a regra antiga
--   2. derrubar o valor padrão — pelo mesmo motivo
--   3. atualizar os dados
--   4. recriar padrão e restrição já na convenção nova
-- -----------------------------------------------------------------------------

-- O gatilho tg_usuario_atualizado mexeria em atualizado_em de todas as linhas.
-- Isso não quebraria nada, mas registraria uma alteração que a pessoa não fez.
-- Desligar durante a normalização mantém o histórico honesto.
ALTER TABLE usuario DISABLE TRIGGER tg_usuario_atualizado;

ALTER TABLE usuario DROP CONSTRAINT ck_usuario_status;
ALTER TABLE usuario ALTER COLUMN status DROP DEFAULT;

UPDATE usuario SET status = upper(status);

ALTER TABLE usuario ALTER COLUMN status SET DEFAULT 'ATIVO';
ALTER TABLE usuario ADD CONSTRAINT ck_usuario_status
    CHECK (status IN ('ATIVO', 'INATIVO'));

ALTER TABLE usuario ENABLE TRIGGER tg_usuario_atualizado;


-- -----------------------------------------------------------------------------
-- ambiente.status
-- -----------------------------------------------------------------------------
ALTER TABLE ambiente DISABLE TRIGGER tg_ambiente_atualizado;

ALTER TABLE ambiente DROP CONSTRAINT ck_ambiente_status;
ALTER TABLE ambiente ALTER COLUMN status DROP DEFAULT;

UPDATE ambiente SET status = upper(status);

ALTER TABLE ambiente ALTER COLUMN status SET DEFAULT 'ATIVO';
ALTER TABLE ambiente ADD CONSTRAINT ck_ambiente_status
    CHECK (status IN ('ATIVO', 'INATIVO'));

ALTER TABLE ambiente ENABLE TRIGGER tg_ambiente_atualizado;


-- -----------------------------------------------------------------------------
-- registro_auditoria.canal e .operacao
-- -----------------------------------------------------------------------------
-- Além de normalizar, canal ganha um valor novo: DESCONHECIDO.
--
-- A partir da V14 a auditoria passa a ser escrita por gatilho, e o gatilho lê o
-- canal de uma variável de sessão. Quando essa variável não existe — alteração
-- feita direto no psql, por exemplo — não há canal a informar.
--
-- Poderíamos gravar SISTEMA nesse caso, mas seria mentira: rotina do sistema e
-- alteração manual são coisas diferentes, e a segunda é exatamente o que a
-- auditoria deveria gritar. Um valor próprio preserva a distinção.
-- -----------------------------------------------------------------------------
ALTER TABLE registro_auditoria DROP CONSTRAINT ck_auditoria_canal;
ALTER TABLE registro_auditoria DROP CONSTRAINT ck_auditoria_operacao;

UPDATE registro_auditoria
   SET canal    = upper(canal),
       operacao = upper(operacao);

ALTER TABLE registro_auditoria ADD CONSTRAINT ck_auditoria_canal
    CHECK (canal IN ('WEB', 'TELEGRAM', 'SISTEMA', 'DESCONHECIDO'));

ALTER TABLE registro_auditoria ADD CONSTRAINT ck_auditoria_operacao
    CHECK (operacao IN ('CRIACAO', 'ALTERACAO', 'EXCLUSAO'));


-- =============================================================================
-- 2. AUDITORIA ACEITA AUTOR DESCONHECIDO
-- =============================================================================
-- usuario_id era NOT NULL, o que fazia sentido quando a aplicação era a única
-- escritora: ela sempre sabe quem está logado.
--
-- Com o gatilho, deixa de fazer. E o efeito colateral seria grave: qualquer
-- migração futura que altere dados — corrigir uma linha, popular uma coluna
-- nova — roda sem variável de sessão, o INSERT da auditoria falharia, e a
-- migração inteira seria abortada. Gatilho dispara para o proprietário também.
--
-- Nulo aqui significa exatamente o que parece: não havia identidade na sessão.
-- É informação, não ausência dela.
-- =============================================================================
ALTER TABLE registro_auditoria ALTER COLUMN usuario_id DROP NOT NULL;

COMMENT ON COLUMN registro_auditoria.usuario_id IS
    'Autor da operacao. Nulo quando a alteracao ocorreu fora da aplicacao (ver canal = DESCONHECIDO).';


-- =============================================================================
-- 3. senha_hash FORA DO ALCANCE DA APLICAÇÃO
-- =============================================================================
-- Motivação: o modelo compartilhado exige que você enxergue o NOME de quem
-- compartilha uma conta com você — senão o extrato mostra um lançamento cujo
-- responsável é um identificador sem rosto. Isso significa relaxar a política
-- pol_usuario_proprio na V13.
--
-- Mas Row Level Security filtra LINHAS, não colunas. Relaxar a política sem
-- mais nada tornaria o hash de senha alheio legível pela aplicação.
--
-- O que torna a solução possível é algo que a V4 já construiu sem saber: a
-- aplicação NUNCA lê senha_hash diretamente. O login passa por
-- auth_buscar_credenciais, que é SECURITY DEFINER e roda como proprietário.
-- A coluna pode simplesmente sair do alcance.
--
-- -----------------------------------------------------------------------------
-- A ARMADILHA: por que não basta revogar a coluna
-- -----------------------------------------------------------------------------
-- Isto aqui NÃO funcionaria:
--
--     REVOKE SELECT (senha_hash) ON usuario FROM raspybank_app;
--
-- No PostgreSQL, privilégio de tabela e privilégio de coluna são concessões
-- independentes. Quem tem SELECT na TABELA lê todas as colunas, e nenhuma
-- revogação de coluna reduz isso — a revogação simplesmente não encontra nada
-- para remover, e o comando termina sem erro. Silenciosamente inócuo.
--
-- A sequência correta é derrubar o privilégio de tabela e reconceder coluna a
-- coluna. Toda coluna nova em usuario, daqui para frente, precisa ser
-- acrescentada aqui explicitamente — o que é chato e é a intenção: obriga a
-- pensar antes de expor.
--
-- -----------------------------------------------------------------------------
-- CONSEQUÊNCIA OBRIGATÓRIA NO CÓDIGO JAVA
-- -----------------------------------------------------------------------------
-- A entidade Usuario NÃO pode mais mapear senhaHash. O Hibernate seleciona
-- todas as colunas mapeadas, então um findById passaria a falhar por permissão
-- negada. O campo sai da entidade; a senha continua trafegando apenas pelas
-- funções SECURITY DEFINER, que é como o login já funciona hoje.
--
-- INSERT, UPDATE e DELETE seguem intactos: revogamos apenas SELECT.
-- =============================================================================
REVOKE SELECT ON usuario FROM raspybank_app;

GRANT SELECT (id, nome, email, telegram_id, status, criado_em, atualizado_em)
    ON usuario TO raspybank_app;


-- =============================================================================
-- 4. AMBIENTE EXCLUÍDO DEIXA DE SER VISÍVEL
-- =============================================================================
-- Defeito encontrado ao revisar a fundação para a Fase 2.
--
-- A V1 criou ambiente.excluido_em para exclusão lógica. A V7 respeita a coluna:
-- auth_ambientes_do_usuario filtra por excluido_em IS NULL antes de emitir o
-- token. Mas app_ambientes_do_usuario, da V3 — a função que alimenta TODAS as
-- políticas de RLS — nunca filtrou.
--
-- Resultado: um ambiente excluído sumia da lista no login, mas seus dados
-- continuavam visíveis a quem já tivesse um token. Duas fontes de verdade sobre
-- a mesma pergunta, discordando entre si.
--
-- CREATE OR REPLACE preserva o identificador interno da função, então as
-- políticas que dependem dela continuam válidas sem serem recriadas.
-- =============================================================================
CREATE OR REPLACE FUNCTION app_ambientes_do_usuario()
RETURNS SETOF uuid
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT ua.ambiente_id
      FROM usuario_ambiente ua
      JOIN ambiente a ON a.id = ua.ambiente_id
     WHERE ua.usuario_id = app_usuario_id()
       AND a.excluido_em IS NULL;
$$;

COMMENT ON FUNCTION app_ambientes_do_usuario() IS
    'Ambientes ativos do usuario da sessao. SECURITY DEFINER para evitar recursao de politica.';


-- =============================================================================
-- COMO VERIFICAR
-- =============================================================================
-- make psql
--
--   SELECT status FROM usuario;      -- ATIVO
--   SELECT status FROM ambiente;     -- ATIVO
--
-- make psql-app
--
--   SELECT set_config('raspybank.usuario_id', '<seu-uuid>', false);
--   SELECT id, nome, email FROM usuario;   -- funciona
--   SELECT senha_hash      FROM usuario;   -- ERRO: permission denied
--
-- O erro na última linha é o resultado esperado desta migração.
-- =============================================================================
