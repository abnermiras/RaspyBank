-- =============================================================================
-- V2 — Auditoria e Outbox
-- =============================================================================
-- Duas tabelas de infraestrutura de domínio, ambas decididas antes do modelo
-- de negócio e independentes dele.
--
-- Referências: RF-M9 (auditoria), decisão de arquitetura #05 (outbox)
-- =============================================================================


-- -----------------------------------------------------------------------------
-- registro_auditoria — Módulo M9
-- -----------------------------------------------------------------------------
-- Escrita pela CAMADA DE SERVIÇO da aplicação, não por gatilho de banco.
--
-- Por quê: a informação que dá valor à auditoria aqui é o CANAL de origem.
-- Saber que um lançamento foi alterado pelo Telegram, e não pela web, é o que
-- efetivamente explica alguma coisa. Para o banco, toda alteração chega pela
-- mesma conexão — ele não tem como saber.
--
-- Além disso, só existe um escritor: tudo passa pela aplicação. Um gatilho
-- protegeria contra um cenário que não existe.
--
-- PONTO FRACO ASSUMIDO: alteração feita direto no banco não é auditada.
-- A mitigação é convenção, não técnica — alteração manual passa por migração
-- versionada, que fica registrada no Git.
-- -----------------------------------------------------------------------------
CREATE TABLE registro_auditoria (

    id              uuid        PRIMARY KEY DEFAULT uuidv7(),

    -- Nulo para ações fora de qualquer ambiente: login, cadastro de usuário,
    -- recuperação de senha.
    ambiente_id     uuid,

    -- Quem fez. Sem ON DELETE CASCADE de propósito: se o usuário for removido,
    -- o registro de auditoria PERMANECE. Auditoria que some quando o autor
    -- some não é auditoria.
    usuario_id      uuid        NOT NULL,

    -- Por onde veio. É a razão de existir desta tabela.
    canal           text        NOT NULL,

    -- O que foi tocado. Texto livre com o nome da entidade de negócio
    -- ('Lancamento', 'Conta'). Não é chave estrangeira porque precisa
    -- sobreviver à exclusão do registro original.
    entidade        text        NOT NULL,
    entidade_id     uuid,

    operacao        text        NOT NULL,

    -- Estado antes e depois, em JSON.
    -- jsonb (e não json) armazena em formato binário: ocupa menos, compara
    -- mais rápido e aceita índice. Em criação, o anterior é nulo; em exclusão,
    -- o novo é nulo.
    estado_anterior jsonb,
    estado_novo     jsonb,

    ocorrido_em     timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_auditoria_canal
        CHECK (canal IN ('web', 'telegram', 'sistema')),
    CONSTRAINT ck_auditoria_operacao
        CHECK (operacao IN ('criacao', 'alteracao', 'exclusao')),

    CONSTRAINT fk_auditoria_ambiente FOREIGN KEY (ambiente_id)
        REFERENCES ambiente (id) ON DELETE RESTRICT
);

-- Consulta típica: "o que aconteceu neste ambiente, do mais recente para trás".
CREATE INDEX ix_auditoria_ambiente_data
    ON registro_auditoria (ambiente_id, ocorrido_em DESC);

-- Consulta típica: "todo o histórico deste lançamento".
CREATE INDEX ix_auditoria_entidade
    ON registro_auditoria (entidade, entidade_id);

COMMENT ON COLUMN registro_auditoria.canal IS
    'Origem da operação. É a informação que justifica auditar na aplicação e não no banco.';


-- -----------------------------------------------------------------------------
-- outbox — decisão de arquitetura #05
-- -----------------------------------------------------------------------------
-- O PADRÃO OUTBOX, explicado:
--
-- Quando um módulo faz algo relevante, outros módulos precisam saber. A forma
-- ingênua é publicar uma mensagem logo após gravar no banco:
--
--     salvarLancamento();      -- confirma no banco
--     publicarEvento();        -- e se isto falhar?
--
-- Se a publicação falhar, o banco diz que o lançamento existe e o resto do
-- sistema nunca fica sabendo. O sistema passa a mentir sobre si mesmo, sem
-- erro visível em lugar nenhum.
--
-- O Outbox resolve isso gravando o evento NA MESMA TRANSAÇÃO do dado:
--
--     BEGIN;
--       INSERT INTO lancamento ...;
--       INSERT INTO outbox     ...;
--     COMMIT;                  -- ou os dois entram, ou nenhum entra
--
-- Um processo separado lê as linhas ainda não publicadas e as entrega.
-- Hoje ele entrega em memória; na Fase 8 passa a publicar no Redpanda.
-- O código de negócio não muda uma vírgula — ele nunca soube o destino.
--
-- CONSEQUÊNCIA: a entrega é "pelo menos uma vez", nunca "exatamente uma vez".
-- Se o relay cair entre entregar e marcar como publicado, o evento é entregue
-- de novo. Por isso TODO consumidor precisa ser idempotente — processar o
-- mesmo evento duas vezes tem que dar o mesmo resultado que processar uma.
-- -----------------------------------------------------------------------------
CREATE TABLE outbox (

    id              uuid        PRIMARY KEY DEFAULT uuidv7(),

    ambiente_id     uuid,

    -- Nome do evento em linguagem de negócio (princípio P6):
    -- 'LancamentoRegistrado', não 'LancamentoInsertEvent'.
    tipo_evento     text        NOT NULL,

    -- Qual entidade originou o evento, e qual instância.
    agregado        text        NOT NULL,
    agregado_id     uuid,

    -- Conteúdo do evento. Deve ser AUTOCONTIDO: quem consome não pode
    -- precisar consultar a tabela de origem para entender o que aconteceu.
    -- Essa é a diferença entre módulos desacoplados e módulos que só fingem
    -- estar desacoplados.
    payload         jsonb       NOT NULL,

    criado_em       timestamptz NOT NULL DEFAULT now(),

    -- Nulo = ainda não entregue.
    publicado_em    timestamptz,

    tentativas      integer     NOT NULL DEFAULT 0,
    ultimo_erro     text,

    CONSTRAINT fk_outbox_ambiente FOREIGN KEY (ambiente_id)
        REFERENCES ambiente (id) ON DELETE RESTRICT
);

-- Índice PARCIAL — o mais importante desta migração.
--
-- O relay pergunta o tempo todo: "o que ainda não foi publicado?". Com o
-- tempo, a esmagadora maioria das linhas já terá sido publicada. Um índice
-- comum indexaria todas elas inutilmente e cresceria sem parar.
--
-- Com a cláusula WHERE, o índice contém APENAS as linhas pendentes. Ele fica
-- minúsculo e a consulta do relay custa quase nada, mesmo com milhões de
-- eventos históricos na tabela.
CREATE INDEX ix_outbox_pendentes
    ON outbox (criado_em)
    WHERE publicado_em IS NULL;

COMMENT ON TABLE outbox IS
    'Eventos gravados na mesma transação do dado. Entrega pelo menos uma vez: consumidores devem ser idempotentes.';
