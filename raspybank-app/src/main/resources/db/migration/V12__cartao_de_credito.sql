-- =============================================================================
-- V12 — Cartão de crédito: contrato, cartões emitidos, fatura e parcelamento
-- =============================================================================
-- A parte mais funda do domínio (F17–F23), desenhada com o Abner em 28/07/2026
-- ANTES de uma linha de código — mesmo procedimento que precedeu a V10 e a V11,
-- e que nas duas vezes evitou migração corretiva.
--
-- Decisões em docs/decisoes.md §4f (B-D45 a B-D59). Contrato em api.md §6b.
--
-- -----------------------------------------------------------------------------
-- O MODELO EM TRÊS CAMADAS, E POR QUE NÃO HÁ TABELA "BANCO"
-- -----------------------------------------------------------------------------
--     conta "Nubank"  (ATIVO, não-física)        ← já existe desde a V10
--       └── cartao "Black"    limite · vencimento · fechamento
--             ├── cartao_emitido  Abner    físico   ****1234
--             ├── cartao_emitido  Luciana  físico   ****5678
--             └── cartao_emitido  —        virtual  ****9012
--
-- "Banco" é a CONTA que já existe (B-D45). Uma tabela nova não guardaria nada
-- que a conta não guarde, e o Abner foi explícito: "não consigo criar um cartão
-- sem uma conta de banco criada antes".
--
-- O CARTÃO É O CONTRATO, e é ele quem tem limite e fatura (F18). Os plásticos e
-- virtuais são cartao_emitido, e todos consomem o mesmo limite do contrato. Dois
-- cartões diferentes no mesmo banco — "Black" e "Diamond" — são dois contratos,
-- com limites próprios.
--
-- -----------------------------------------------------------------------------
-- O CARTÃO TAMBÉM É UMA CONTA (B-D47)
-- -----------------------------------------------------------------------------
-- cartao.conta_id é a chave primária E a estrangeira, apontando para conta
-- (F5/F17). Não é elegância: a dívida do cartão é saldo, e saldo é soma de
-- lançamentos (P1). Uma coluna "valor devido" faria o mesmo número existir em
-- dois lugares, e é exatamente o que R1 existe para impedir.
--
-- Efeitos colaterais desejados, todos de graça:
--   - o cartão aparece na T-05 junto das outras contas, com a dívida à vista;
--   - o patrimônio (F6) já o subtrai, porque a natureza é PASSIVO;
--   - pagar a fatura reusa a máquina da transferência sem código novo;
--   - o extrato do cartão é o extrato de uma conta.
--
-- -----------------------------------------------------------------------------
-- ROTEIRO
-- -----------------------------------------------------------------------------
--   1. PAGAMENTO_FATURA — a quarta sistêmica, que B-D13 reservou
--   2. cartao          — o contrato
--   3. cartao_emitido  — cada plástico ou virtual
--   4. fatura          — um ciclo do contrato, SEM coluna de status (F19)
--   5. lancamento      — fatura_id e as três colunas de parcelamento
--   6. Row Level Security e auditoria
-- =============================================================================


-- #############################################################################
-- 1. PAGAMENTO_FATURA — a sistêmica que estava reservada desde a V10
-- #############################################################################
-- B-D13 fixou três sistêmicas e deixou PAGAMENTO_FATURA anotada para "quando o
-- cartão chegar". Chegou.
--
-- entra_no_mapa = FALSE, e a razão é a mesma de TRANSFERENCIA mas vale repetir
-- porque errar aqui dobra o mês inteiro em silêncio: os gastos do cartão já
-- entraram no mapa UM A UM, quando cada compra foi lançada. O pagamento da
-- fatura é o dinheiro saindo da conta para cobrir aqueles gastos — contá-lo de
-- novo somaria a mesma despesa duas vezes (B-D59).
--
-- O pagamento continua aparecendo no EXTRATO da conta pagadora, e isso é
-- deliberado: o dinheiro saiu de lá, e omitir seria mentir sobre o saldo.
-- "Não entra no mapa" e "não existe" são coisas diferentes.
-- #############################################################################
CREATE OR REPLACE FUNCTION fn_criar_categorias_sistemicas(p_ambiente_id uuid)
RETURNS void
LANGUAGE sql
AS $$
    INSERT INTO categoria (ambiente_id, codigo, nome, tipo, sistemica, entra_no_mapa)
    VALUES
        (p_ambiente_id, 'TRANSFERENCIA',    'Transferência',      'AMBOS', true, false),
        (p_ambiente_id, 'AJUSTE',           'Ajuste de saldo',    'AMBOS', true, false),
        (p_ambiente_id, 'NAO_CLASSIFICADO', 'Não classificado',   'AMBOS', true, true),
        (p_ambiente_id, 'PAGAMENTO_FATURA', 'Pagamento de fatura','AMBOS', true, false)
    ON CONFLICT DO NOTHING;
$$;

COMMENT ON FUNCTION fn_criar_categorias_sistemicas(uuid) IS
    'As QUATRO sistemicas (B-D13 + V12). Idempotente: chamada na criacao do ambiente e na retroalimentacao.';

-- Retroativa, pelo mesmo motivo de B-D16 na V10: os ambientes que já existem
-- também precisam da categoria nova, senão pagar fatura falha neles com um erro
-- que ninguém liga à migração. A função é idempotente, então isto é seguro.
SELECT fn_criar_categorias_sistemicas(id) FROM ambiente;


-- #############################################################################
-- 2. cartao — O CONTRATO
-- #############################################################################
CREATE TABLE cartao (

    -- Chave primária E estrangeira: o cartão É uma conta (F5/F17, B-D47).
    -- RESTRICT e não CASCADE: apagar a conta apagaria o contrato e as faturas
    -- junto, e conta não se apaga — se encerra (F7).
    conta_id                uuid        PRIMARY KEY
                                        REFERENCES conta (id) ON DELETE RESTRICT,

    -- O banco. É uma conta comum, e o serviço recusa que ela seja física —
    -- carteira, gaveta e cofre não emitem cartão de crédito (B-D45, via B-D41).
    --
    -- Por que a checagem de "não física" NÃO está aqui: ela depende de consultar
    -- conta_forma_pagamento, e CHECK não faz subconsulta. Um gatilho resolveria,
    -- e gatilho é a ferramenta mais cara de manter do arquivo — pelo mesmo
    -- critério de B-D41, que deixou a exclusividade do dinheiro no serviço.
    conta_banco_id          uuid        NOT NULL
                                        REFERENCES conta (id) ON DELETE RESTRICT,

    nome                    text        NOT NULL,

    -- INFORMATIVO, nunca uma cerca (B-D48). Nenhuma compra é recusada por
    -- estourá-lo. O número existe para bater com o app do banco, e o banco de
    -- verdade é quem recusa.
    limite                  numeric(15,2) NOT NULL,

    dia_vencimento          smallint    NOT NULL,

    -- Configurável por cartão, padrão 5 (B-D49). Fixo no sistema faria a regra
    -- de cada banco virar trabalho manual todo mês, para sempre.
    dias_para_fechamento    smallint    NOT NULL DEFAULT 5,

    encerrado_em            timestamptz,

    criado_em               timestamptz NOT NULL DEFAULT now(),
    atualizado_em           timestamptz NOT NULL DEFAULT now(),

    -- O cartão não é o próprio banco. Sem isto, um UPDATE distraído criaria um
    -- contrato apontando para si mesmo, e o pagamento da fatura viraria uma
    -- transferência da conta para ela mesma.
    CONSTRAINT ck_cartao_banco_diferente CHECK (conta_id <> conta_banco_id),

    CONSTRAINT ck_cartao_limite CHECK (limite > 0),

    CONSTRAINT ck_cartao_dia_vencimento CHECK (dia_vencimento BETWEEN 1 AND 31),

    -- Até 28 porque o fechamento precisa continuar dentro do mês anterior ao
    -- vencimento, inclusive em fevereiro.
    CONSTRAINT ck_cartao_dias_fechamento CHECK (dias_para_fechamento BETWEEN 0 AND 28)
);

CREATE INDEX ix_cartao_banco ON cartao (conta_banco_id);

COMMENT ON TABLE cartao IS
    'O CONTRATO de credito (F17). PK = conta_id: o cartao e uma conta PASSIVO, e a divida e soma de lancamentos (P1).';
COMMENT ON COLUMN cartao.limite IS
    'Informativo (B-D48). Nao trava compra nenhuma; existe para bater com o app do banco.';


-- #############################################################################
-- 3. cartao_emitido — CADA PLÁSTICO OU VIRTUAL
-- #############################################################################
-- O titular é TEXTO e usuario_id é anulável (B-D53).
--
-- O Abner quer registrar o adicional da Luciana hoje, e os gastos dela já são
-- gasto da casa. Mas convidar usuário é o I-08, que não existe. O texto permite
-- registrar agora; quando o convite chegar, preenche-se o usuario_id e nada
-- mais muda — nenhuma migração, nenhum dado perdido.
-- #############################################################################
CREATE TABLE cartao_emitido (

    id                  uuid        PRIMARY KEY DEFAULT uuidv7(),

    cartao_id           uuid        NOT NULL
                                    REFERENCES cartao (conta_id) ON DELETE CASCADE,

    nome_titular        text        NOT NULL,

    -- Nulo até o convite (I-08) existir. Quando existir, este campo vira o
    -- vinculo de verdade e o nome passa a ser so rotulo.
    usuario_id          uuid        REFERENCES usuario (id) ON DELETE SET NULL,

    tipo                text        NOT NULL,

    -- Só os quatro últimos, e por decisão: número completo de cartão é dado que
    -- este sistema não tem motivo para guardar, e guardar o que não se precisa é
    -- criar responsabilidade de graça.
    final_do_cartao     text        NOT NULL,

    -- Nulo = usa o limite do contrato, que é o caso comum. Preenchido = sublimite
    -- deste cartão dentro do global.
    limite_proprio      numeric(15,2),

    cancelado_em        timestamptz,

    criado_em           timestamptz NOT NULL DEFAULT now(),
    atualizado_em       timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_cartao_emitido_tipo CHECK (tipo IN ('FISICO', 'VIRTUAL')),

    CONSTRAINT ck_cartao_emitido_final CHECK (final_do_cartao ~ '^[0-9]{4}$'),

    CONSTRAINT ck_cartao_emitido_limite CHECK (limite_proprio IS NULL OR limite_proprio > 0)
);

CREATE INDEX ix_cartao_emitido_cartao ON cartao_emitido (cartao_id);

COMMENT ON COLUMN cartao_emitido.nome_titular IS
    'Texto ate o convite existir (B-D53). Depois, usuario_id vira o vinculo e este campo e so rotulo.';


-- #############################################################################
-- 4. fatura — UM CICLO DO CONTRATO, SEM COLUNA DE STATUS
-- #############################################################################
-- F19 é categórica: fatura não tem coluna de total nem de status. Só fechada_em.
--
-- E B-D58 vai além: o estado NÃO É um enum de cinco valores. São três perguntas
-- independentes, calculadas na hora:
--
--   ciclo     ABERTA | FECHADA          ← deriva de fechada_em
--   quitacao  NADA_PAGO | PARCIAL | QUITADA   ← deriva das somas
--   vencida   booleano                  ← fechada, não quitada, vencimento passou
--
-- Juntá-las num campo só seria o erro que B-D15 já custou uma vez. E teria um
-- sintoma concreto: uma fatura ABERTA pode estar parcialmente paga — é o caso da
-- antecipação (B-D57) — e nesse enum o caso não teria nome.
-- #############################################################################
CREATE TABLE fatura (

    id                  uuid        PRIMARY KEY DEFAULT uuidv7(),

    cartao_id           uuid        NOT NULL
                                    REFERENCES cartao (conta_id) ON DELETE CASCADE,

    -- Sempre o dia 1 do mês de referência. Guardar como date e não como (ano,
    -- mes) deixa ORDER BY e BETWEEN funcionarem sem truque.
    mes_referencia      date        NOT NULL,

    vencimento          date        NOT NULL,

    -- Calculado na geração: vencimento - dias_para_fechamento, recuando para a
    -- sexta anterior se cair em fim de semana. Guardado e não derivado porque
    -- dias_para_fechamento pode mudar depois, e o ciclo que já correu não pode
    -- mudar junto — senão uma compra migraria de fatura sozinha.
    fechamento_previsto date        NOT NULL,

    -- O ÚNICO campo de estado (F19). Nulo = aberta.
    fechada_em          timestamptz,

    criado_em           timestamptz NOT NULL DEFAULT now(),
    atualizado_em       timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ux_fatura_ciclo UNIQUE (cartao_id, mes_referencia),

    CONSTRAINT ck_fatura_fechamento_antes CHECK (fechamento_previsto <= vencimento)
);

CREATE INDEX ix_fatura_cartao_vencimento ON fatura (cartao_id, vencimento);

COMMENT ON TABLE fatura IS
    'Um ciclo do contrato. SEM coluna de status (F19): ciclo, quitacao e vencida sao calculados (B-D58).';


-- #############################################################################
-- 5. lancamento — A FATURA E O PARCELAMENTO
-- #############################################################################
ALTER TABLE lancamento ADD COLUMN fatura_id uuid;

-- RESTRICT: apagar uma fatura que tem lançamentos apagaria compras de verdade.
-- Fatura não se apaga — no máximo se reabre (B-D50).
ALTER TABLE lancamento ADD CONSTRAINT fk_lancamento_fatura
    FOREIGN KEY (fatura_id) REFERENCES fatura (id) ON DELETE RESTRICT;

CREATE INDEX ix_lancamento_fatura ON lancamento (fatura_id)
    WHERE fatura_id IS NOT NULL;


-- -----------------------------------------------------------------------------
-- Parcelamento SEM tabela de parcela (B-D55)
-- -----------------------------------------------------------------------------
-- B-D1 listava uma tabela `parcela`. Ao desenhar, ela não guardaria nada que não
-- fosse derivável dos lançamentos: o valor total é a soma das parcelas, a data
-- da compra é a data_competencia que se REPETE em todas (F23, confirmado pelo
-- Abner — "a data da compra repete, muda somente a fatura"), e a quantidade é a
-- contagem. Tabela que só guarda agregado contraria P1.
--
-- CUSTO ASSUMIDO E REGISTRADO: grupo_parcelamento_id não tem tabela-alvo, então
-- não existe chave estrangeira garantindo o grupo. É identificador de
-- correlação, e a integridade dele fica na aplicação. Foi decisão consciente,
-- não esquecimento — está em B-D55.
-- -----------------------------------------------------------------------------
ALTER TABLE lancamento ADD COLUMN grupo_parcelamento_id uuid;
ALTER TABLE lancamento ADD COLUMN parcela_numero        smallint;
ALTER TABLE lancamento ADD COLUMN parcela_total         smallint;

-- Os três andam juntos ou nenhum existe. Sem isto, um lançamento poderia dizer
-- "parcela 3" sem dizer de quantas, e a tela não teria o que mostrar.
ALTER TABLE lancamento ADD CONSTRAINT ck_lancamento_parcelamento_completo CHECK (
    (grupo_parcelamento_id IS NULL AND parcela_numero IS NULL AND parcela_total IS NULL)
    OR
    (grupo_parcelamento_id IS NOT NULL AND parcela_numero IS NOT NULL AND parcela_total IS NOT NULL
     AND parcela_numero BETWEEN 1 AND parcela_total
     AND parcela_total BETWEEN 2 AND 99)
);

CREATE INDEX ix_lancamento_grupo_parcelamento ON lancamento (grupo_parcelamento_id)
    WHERE grupo_parcelamento_id IS NOT NULL;

COMMENT ON COLUMN lancamento.fatura_id IS
    'A fatura que cobra este lancamento. Nulo em lancamento de conta comum.';
COMMENT ON COLUMN lancamento.grupo_parcelamento_id IS
    'Correlaciona as parcelas da mesma compra (B-D55). Sem tabela-alvo de proposito: uma so guardaria agregado.';


-- #############################################################################
-- 6. ROW LEVEL SECURITY E AUDITORIA
-- #############################################################################
-- Regra da casa: tabela de domínio nova nasce com RLS ligado.
--
-- As três perguntam à CONTA, e não a um ambiente_id que nenhuma delas tem —
-- mesma razão de conta (R7). O cartão É uma conta, então app_contas_do_usuario()
-- já responde por ele; emitido e fatura descem pelo cartao_id.
--
-- Nenhuma porta estreita nova é necessária. Criar um cartão é criar uma conta
-- por app_criar_conta() — que já existe e já confere o vínculo — e SÓ ENTÃO
-- inserir a linha em cartao. Nesse ponto o vínculo existe, app_contas_do_usuario()
-- já devolve a conta, e o WITH CHECK passa. O impasse que justificou a porta
-- estreita da V10 não se repete aqui (B-D19).
-- #############################################################################
ALTER TABLE cartao ENABLE ROW LEVEL SECURITY;
CREATE POLICY pol_cartao_conta ON cartao
    FOR ALL
    USING      (conta_id IN (SELECT app_contas_do_usuario()))
    WITH CHECK (conta_id IN (SELECT app_contas_do_usuario()));

ALTER TABLE cartao_emitido ENABLE ROW LEVEL SECURITY;
CREATE POLICY pol_cartao_emitido_cartao ON cartao_emitido
    FOR ALL
    USING      (cartao_id IN (SELECT app_contas_do_usuario()))
    WITH CHECK (cartao_id IN (SELECT app_contas_do_usuario()));

ALTER TABLE fatura ENABLE ROW LEVEL SECURITY;
CREATE POLICY pol_fatura_cartao ON fatura
    FOR ALL
    USING      (cartao_id IN (SELECT app_contas_do_usuario()))
    WITH CHECK (cartao_id IN (SELECT app_contas_do_usuario()));

-- Mesmo gatilho genérico de F26 / B-D6, que extrai ambiente_id e id do JSON e
-- por isso serve a qualquer tabela sem alteração.
CREATE TRIGGER tg_auditar_cartao
    AFTER INSERT OR UPDATE OR DELETE ON cartao
    FOR EACH ROW EXECUTE FUNCTION fn_auditar('Cartao');

CREATE TRIGGER tg_auditar_cartao_emitido
    AFTER INSERT OR UPDATE OR DELETE ON cartao_emitido
    FOR EACH ROW EXECUTE FUNCTION fn_auditar('CartaoEmitido');

-- A fatura é auditada com atenção especial porque fechar e reabrir são as
-- operações que mais mudam o que a pessoa vê (B-D50), e "quem fechou isto?" é
-- pergunta que vai aparecer.
CREATE TRIGGER tg_auditar_fatura
    AFTER INSERT OR UPDATE OR DELETE ON fatura
    FOR EACH ROW EXECUTE FUNCTION fn_auditar('Fatura');
