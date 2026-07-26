-- =============================================================================
-- V10 — Domínio: categoria, conta e lançamento (fatia 1)
-- =============================================================================
-- A migração que traz o negócio para dentro do banco. Até aqui só existia
-- identidade, ambiente e infraestrutura; a partir daqui existe dinheiro.
--
-- REGRA DO FLYWAY: este arquivo é IMUTÁVEL depois de aplicado. É por isso que
-- a sessão de 26/07/2026 varreu todas as pendências ANTES de escrevê-lo — as
-- decisões B-D1 a B-D17 em docs/decisoes.md §4d existem para que este arquivo
-- não precise ser corrigido por outro.
--
-- O que NÃO está aqui: cartão, cartão emitido, fatura, parcela e recorrência.
-- São a V11 (B-D1), a parte mais funda do domínio (F17–F23), e sai junto da
-- tela T-06. Fatiar reduz o tamanho do que é irreversível de cada vez.
--
-- -----------------------------------------------------------------------------
-- ROTEIRO DESTE ARQUIVO
-- -----------------------------------------------------------------------------
--   1. Limpeza herdada  — DROP ambiente.status (B-D5 / I-01)
--   2. Tabelas          — categoria, subcategoria, conta, conta_ambiente,
--                         lancamento
--   3. Row Level Security — políticas das cinco + app_contas_do_usuario()
--   4. Auditoria        — gatilho genérico lendo usuário E canal (F26 / B-D6)
--   5. Outbox           — eventos do lançamento (F28)
--   6. Sistêmicas       — as três de B-D13, na criação e retroativas (B-D16)
--
-- Referências: F1–F33 do Modelo Lógico, B-D1 a B-D16 do Bloco de Domínio.
-- =============================================================================


-- #############################################################################
-- 1. LIMPEZA HERDADA — ambiente.status sai de cena
-- #############################################################################
-- Inconsistência I-01: a tabela ambiente carregava status (ATIVO/INATIVO) E
-- excluido_em, dois mecanismos respondendo à mesma pergunta. Ninguém nunca
-- gravou status; app_ambientes_do_usuario() sempre filtrou por excluido_em.
--
-- A coluna não some por limpeza estética. Ela some porque coluna morta um dia
-- é usada por engano, e aí passam a existir dois critérios de "ambiente ativo"
-- que discordam — que foi exatamente o defeito corrigido na V8, item 4.
--
-- Exclusão lógica JÁ É o arquivamento reversível: preencher excluido_em some
-- com o ambiente, anular a coluna o traz de volta. Não faltava estado, sobrava
-- coluna. Decisão B-D5.
--
-- CONSEQUÊNCIA OBRIGATÓRIA NO JAVA: a entidade Ambiente não pode mais mapear
-- status, e o enum StatusAmbiente deixa de existir. O Hibernate seleciona toda
-- coluna mapeada; manter o campo faria todo findById falhar.
-- #############################################################################
ALTER TABLE ambiente DROP CONSTRAINT ck_ambiente_status;
ALTER TABLE ambiente DROP COLUMN status;

COMMENT ON COLUMN ambiente.excluido_em IS
    'Unico mecanismo de ciclo de vida (B-D5). Nulo = ativo; preenchido = arquivado. Anular reverte.';


-- #############################################################################
-- 2. TABELAS
-- #############################################################################

-- -----------------------------------------------------------------------------
-- categoria — F8, F9, F10, F12
-- -----------------------------------------------------------------------------
-- Categoria e subcategoria vivem em tabelas separadas, com exatamente dois
-- níveis (F8). Não existe auto-relacionamento e isso é deliberado: árvore de
-- profundidade livre parece mais flexível e custa consulta recursiva em todo
-- relatório, para atender um caso que ninguém pediu.
--
-- As categorias são COPIADAS por ambiente (F9), não compartilhadas. Duas casas
-- na mesma instalação têm cada uma o seu "Mercado", com id próprio. Compartilhar
-- criaria uma dependência entre ambientes que a fronteira de dados existe para
-- impedir.
-- -----------------------------------------------------------------------------
CREATE TABLE categoria (

    id              uuid        PRIMARY KEY DEFAULT uuidv7(),

    ambiente_id     uuid        NOT NULL,

    -- Preenchido SÓ nas sistêmicas (F10). É por ele que o código encontra a
    -- categoria de transferência sem depender do nome — que o usuário poderia
    -- renomear, já que renomear é ação leve (B-D3).
    codigo          text,

    nome            text        NOT NULL,

    -- ENTRADA, SAIDA ou AMBOS (F12). AMBOS existe para as sistêmicas:
    -- transferência e ajuste servem aos dois sentidos.
    tipo            text        NOT NULL,

    -- Duas colunas, duas perguntas diferentes — e a separação é decisão
    -- registrada (B-D15), não acidente:
    --
    --   sistemica     = "pode editar?"        (F10)
    --   entra_no_mapa = "conta como gasto?"   (relatório T-07)
    --
    -- Elas quase coincidem, e foi aí que a armadilha apareceu: usar sistemica
    -- para as duas coisas faria "Não classificado" sumir do mapa de gastos.
    -- Ela é sistêmica (o bot do Telegram precisa dela por codigo) mas é gasto
    -- de verdade — só falta o rótulo. Escondê-la faria o total mentir para
    -- baixo, em silêncio, que é o pior tipo de erro num relatório.
    --
    -- Uma flag, um trabalho. A lição veio do I-01, logo acima neste arquivo.
    sistemica       boolean     NOT NULL DEFAULT false,
    entra_no_mapa   boolean     NOT NULL DEFAULT true,

    -- Exclusão é lógica (B-D4 / R8), espelho de F7. Arquivada, a categoria
    -- some do formulário de lançamento novo e CONTINUA nomeando o histórico
    -- inteiro. É por isso que o lançamento não precisa congelar o nome: o id
    -- sempre resolve.
    arquivada_em    timestamptz,

    criado_em       timestamptz NOT NULL DEFAULT now(),
    atualizado_em   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_categoria_tipo
        CHECK (tipo IN ('ENTRADA', 'SAIDA', 'AMBOS')),

    -- Sistêmica tem código; comum não tem. Escrito como igualdade de booleanos
    -- porque a regra é uma bicondicional, não duas condições soltas.
    CONSTRAINT ck_categoria_codigo
        CHECK (sistemica = (codigo IS NOT NULL)),

    CONSTRAINT fk_categoria_ambiente FOREIGN KEY (ambiente_id)
        REFERENCES ambiente (id) ON DELETE RESTRICT,

    -- Alvo das chaves compostas de subcategoria e lancamento. Redundante com a
    -- PK do ponto de vista da unicidade, e obrigatório do ponto de vista do
    -- Postgres: FK composta exige um índice único exatamente sobre o par.
    CONSTRAINT uq_categoria_ambiente_id UNIQUE (ambiente_id, id)
);

-- Um código por ambiente. Parcial porque só as sistêmicas têm código.
CREATE UNIQUE INDEX ux_categoria_codigo
    ON categoria (ambiente_id, codigo)
    WHERE codigo IS NOT NULL;

-- Nome único entre as ATIVAS, ignorando maiúsculas.
--
-- Por que só entre as ativas: arquivar "Mercado" e criar outra "Mercado" é
-- legítimo — a primeira segue nomeando o passado, a segunda recomeça a
-- contagem. Impedir isso obrigaria a inventar "Mercado 2".
--
-- Por que impedir duas ativas com o mesmo nome: o seletor da tela de
-- lançamento ficaria com duas linhas idênticas e o usuário escolheria no
-- escuro.
CREATE UNIQUE INDEX ux_categoria_nome
    ON categoria (ambiente_id, lower(nome))
    WHERE arquivada_em IS NULL;

CREATE TRIGGER tg_categoria_atualizado
    BEFORE UPDATE ON categoria
    FOR EACH ROW EXECUTE FUNCTION fn_atualizar_timestamp();

COMMENT ON TABLE categoria IS
    'Primeiro nivel da classificacao (F8). Copiada por ambiente (F9), nunca compartilhada.';
COMMENT ON COLUMN categoria.entra_no_mapa IS
    'Conta como gasto no relatorio T-07. Falso em transferencia e ajuste; verdadeiro em nao classificado (B-D15).';


-- -----------------------------------------------------------------------------
-- subcategoria — F8
-- -----------------------------------------------------------------------------
-- Segundo e último nível. Não existe tabela de sub-subcategoria, e a ausência
-- é a garantia: o que não tem caminho no schema não vira dado por engano.
--
-- ambiente_id aparece aqui DENORMALIZADO, e não por descuido. Duas razões:
--
--   1. A política de RLS precisa do ambiente. Sem a coluna, a política faria
--      subquery em categoria — que também tem política — e cada leitura de
--      subcategoria pagaria a avaliação de duas políticas.
--
--   2. A FK composta abaixo torna a denormalização IMPOSSÍVEL de divergir:
--      (ambiente_id, categoria_id) referencia categoria(ambiente_id, id).
--      Uma subcategoria não consegue apontar para categoria de outro ambiente
--      nem que alguém tente.
--
-- Denormalizar sem restrição é criar duas verdades. Com restrição, é criar um
-- atalho que o banco garante.
-- -----------------------------------------------------------------------------
CREATE TABLE subcategoria (

    id              uuid        PRIMARY KEY DEFAULT uuidv7(),

    ambiente_id     uuid        NOT NULL,
    categoria_id    uuid        NOT NULL,

    nome            text        NOT NULL,

    arquivada_em    timestamptz,

    criado_em       timestamptz NOT NULL DEFAULT now(),
    atualizado_em   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_subcategoria_categoria FOREIGN KEY (ambiente_id, categoria_id)
        REFERENCES categoria (ambiente_id, id) ON DELETE RESTRICT,

    -- Alvo da FK composta de lancamento (F11).
    CONSTRAINT uq_subcategoria_categoria_id UNIQUE (categoria_id, id)
);

CREATE UNIQUE INDEX ux_subcategoria_nome
    ON subcategoria (categoria_id, lower(nome))
    WHERE arquivada_em IS NULL;

CREATE INDEX ix_subcategoria_categoria ON subcategoria (categoria_id);

CREATE TRIGGER tg_subcategoria_atualizado
    BEFORE UPDATE ON subcategoria
    FOR EACH ROW EXECUTE FUNCTION fn_atualizar_timestamp();

COMMENT ON TABLE subcategoria IS
    'Segundo e ultimo nivel (F8). Nao existe terceiro nivel por decisao.';


-- -----------------------------------------------------------------------------
-- conta — F4, F6, F7
-- -----------------------------------------------------------------------------
-- A âncora do modelo: todo lançamento aponta para exatamente uma conta (F4).
--
-- REPARE NO QUE NÃO EXISTE AQUI:
--
--   * ambiente_id — a conta não pertence a UM ambiente. A visibilidade é N:N
--     via conta_ambiente, porque o caso real (conta conjunta do casal, visível
--     no ambiente da casa E no pessoal de cada um) não se expressa por
--     igualdade de campo. Foi a revisão R7 que trouxe isso.
--
--   * saldo — princípio P1, sem exceção. O saldo é a soma dos lançamentos,
--     calculada na hora. Não há o que reconciliar quando o dado não existe em
--     dois lugares (R1). Saldo de abertura é um lançamento na categoria
--     sistêmica AJUSTE (A13), não um campo mágico.
-- -----------------------------------------------------------------------------
CREATE TABLE conta (

    id              uuid        PRIMARY KEY DEFAULT uuidv7(),

    nome            text        NOT NULL,

    -- ATIVO (o dinheiro é seu) ou PASSIVO (você deve). Patrimônio é a
    -- diferença das somas, nunca uma coluna (F6).
    natureza        text        NOT NULL,

    -- Conta não se exclui, se encerra (F7). Encerrada, some dos seletores e
    -- mantém o histórico inteiro — apagar uma conta apagaria o passado dela.
    encerrada_em    timestamptz,

    criado_em       timestamptz NOT NULL DEFAULT now(),
    atualizado_em   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_conta_natureza
        CHECK (natureza IN ('ATIVO', 'PASSIVO'))
);

CREATE TRIGGER tg_conta_atualizado
    BEFORE UPDATE ON conta
    FOR EACH ROW EXECUTE FUNCTION fn_atualizar_timestamp();

COMMENT ON TABLE conta IS
    'Ancora unica do lancamento (F4). Sem ambiente_id (N:N via conta_ambiente, R7) e sem saldo (P1).';


-- -----------------------------------------------------------------------------
-- conta_ambiente — o vínculo que R7 exige
-- -----------------------------------------------------------------------------
-- Sem esta tabela, conta não teria política de RLS: o tenant é o USUÁRIO
-- (A08/R7), e a visibilidade de uma conta se decide por vínculo, não por
-- campo. Ela também é o alvo da restrição que amarra lançamento a ambiente
-- (B-D2), lá embaixo.
-- -----------------------------------------------------------------------------
CREATE TABLE conta_ambiente (

    conta_id        uuid        NOT NULL,
    ambiente_id     uuid        NOT NULL,
    criado_em       timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_conta_ambiente PRIMARY KEY (conta_id, ambiente_id),

    CONSTRAINT fk_ca_conta    FOREIGN KEY (conta_id)
        REFERENCES conta (id)    ON DELETE RESTRICT,
    CONSTRAINT fk_ca_ambiente FOREIGN KEY (ambiente_id)
        REFERENCES ambiente (id) ON DELETE RESTRICT,

    -- Alvo da FK composta de lancamento. A PK indexa (conta_id, ambiente_id);
    -- a FK precisa do par na ordem inversa.
    CONSTRAINT uq_ca_ambiente_conta UNIQUE (ambiente_id, conta_id)
);

COMMENT ON TABLE conta_ambiente IS
    'Visibilidade N:N da conta (R7). Conta conjunta aparece em mais de um ambiente.';


-- -----------------------------------------------------------------------------
-- lancamento — F4, F11, F14, F15, F29, F32, F33
-- -----------------------------------------------------------------------------
-- A tabela que justifica todas as outras. Fonte única de verdade sobre
-- dinheiro (P1/R1): saldo, total por categoria, patrimônio — tudo é soma
-- daqui, nada é guardado pronto.
--
-- -----------------------------------------------------------------------------
-- AS TRÊS CHAVES COMPOSTAS, E POR QUE ELAS SÃO O CORAÇÃO DESTA TABELA
-- -----------------------------------------------------------------------------
-- Cada uma transforma uma regra de negócio em impossibilidade estrutural. São
-- regras que, sem elas, viveriam em algum "if" de serviço que alguém um dia
-- esquece de escrever:
--
--   (ambiente_id, conta_id) -> conta_ambiente
--       A conta precisa estar visível no ambiente do lançamento. É a decisão
--       B-D2 virando estrutura: um lançamento nunca cai num ambiente que não
--       enxerga a própria conta.
--
--   (ambiente_id, categoria_id) -> categoria
--       Categoria de outro ambiente não classifica lançamento deste. F9 diz
--       que categorias são copiadas por ambiente; esta FK garante.
--
--   (categoria_id, subcategoria_id) -> subcategoria
--       A FK composta de F11: "Alimentação > Combustível" não existe. A
--       subcategoria tem que pertencer à categoria informada, e não há como
--       gravar o par errado.
--
-- A terceira aceita subcategoria nula (F11 a torna opcional). No Postgres, FK
-- composta com MATCH SIMPLE — o padrão — não é verificada quando qualquer
-- coluna é nula, que é exatamente o comportamento desejado aqui.
-- -----------------------------------------------------------------------------
CREATE TABLE lancamento (

    id                  uuid            PRIMARY KEY DEFAULT uuidv7(),

    -- O ambiente ATIVO na criação (B-D2). É por ele que o relatório filtra
    -- (F33) — e é a resposta para "de quem é o gasto numa conta conjunta".
    ambiente_id         uuid            NOT NULL,

    conta_id            uuid            NOT NULL,

    categoria_id        uuid            NOT NULL,
    subcategoria_id     uuid,

    -- ENTRADA ou SAIDA. Aqui NÃO existe AMBOS: uma categoria pode servir aos
    -- dois sentidos, um lançamento concreto tem um só.
    tipo                text            NOT NULL,

    -- PREVISTO ou REALIZADO.
    --
    -- Repare que não há gatilho derivando este valor da data, embora B-D9 diga
    -- que ele deriva. A derivação vive numa classe de domínio pura, testável
    -- sem Spring (padrão B-C3), por dois motivos: o PUT permite corrigir a
    -- situação explicitamente, e uma regra que o banco impõe é uma regra que o
    -- usuário não consegue contrariar quando tem razão.
    situacao            text            NOT NULL,

    -- numeric(15,2) — F1, sem exceção. double para dinheiro é proibido.
    -- Sempre POSITIVO: o sinal é responsabilidade de tipo, não do valor.
    -- Guardar negativo abriria duas representações para a mesma saída.
    valor               numeric(15,2)   NOT NULL,

    descricao           text,

    -- Texto livre (F29). Anexo ficou fora da v1.0 (F29).
    observacao          text,

    -- As duas datas de F14, ambas date e nunca timestamptz (B-D8).
    --
    -- Por que date importa tanto aqui: o banco guarda timestamps em UTC. Com o
    -- regime de caixa (P-T2), um lançamento às 21h de 31/jan em São Paulo
    -- seria 01/fev em UTC e cairia no MÊS ERRADO do quadro central. Data de
    -- dinheiro não tem hora, e date não tem fuso para errar.
    data_competencia    date            NOT NULL,
    data_caixa          date            NOT NULL,

    -- F32: quem criou é imutável, quem é responsável pode mudar.
    -- Sem ON DELETE CASCADE: o lançamento sobrevive à remoção do autor.
    criado_por          uuid            NOT NULL,
    responsavel_id      uuid,

    criado_em           timestamptz     NOT NULL DEFAULT now(),
    atualizado_em       timestamptz     NOT NULL DEFAULT now(),

    CONSTRAINT ck_lancamento_tipo
        CHECK (tipo IN ('ENTRADA', 'SAIDA')),
    CONSTRAINT ck_lancamento_situacao
        CHECK (situacao IN ('PREVISTO', 'REALIZADO')),
    CONSTRAINT ck_lancamento_valor
        CHECK (valor > 0),

    CONSTRAINT fk_lancamento_conta FOREIGN KEY (ambiente_id, conta_id)
        REFERENCES conta_ambiente (ambiente_id, conta_id) ON DELETE RESTRICT,

    CONSTRAINT fk_lancamento_categoria FOREIGN KEY (ambiente_id, categoria_id)
        REFERENCES categoria (ambiente_id, id) ON DELETE RESTRICT,

    CONSTRAINT fk_lancamento_subcategoria FOREIGN KEY (categoria_id, subcategoria_id)
        REFERENCES subcategoria (categoria_id, id) ON DELETE RESTRICT,

    CONSTRAINT fk_lancamento_criado_por FOREIGN KEY (criado_por)
        REFERENCES usuario (id) ON DELETE RESTRICT,
    CONSTRAINT fk_lancamento_responsavel FOREIGN KEY (responsavel_id)
        REFERENCES usuario (id) ON DELETE RESTRICT
);

-- O índice do mapa de gastos (T-07): "todos os lançamentos deste ambiente,
-- neste ano". É a consulta mais quente do sistema — a tela principal a faz
-- em toda visita.
CREATE INDEX ix_lancamento_ambiente_caixa
    ON lancamento (ambiente_id, data_caixa);

-- O índice do extrato: "o que passou nesta conta".
CREATE INDEX ix_lancamento_conta_caixa
    ON lancamento (conta_id, data_caixa);

-- Para a tela de categorias responder "posso arquivar?" sem varrer a tabela.
CREATE INDEX ix_lancamento_categoria
    ON lancamento (categoria_id);

CREATE TRIGGER tg_lancamento_atualizado
    BEFORE UPDATE ON lancamento
    FOR EACH ROW EXECUTE FUNCTION fn_atualizar_timestamp();

COMMENT ON TABLE lancamento IS
    'Fonte unica de verdade sobre dinheiro (P1/R1). Todo saldo e todo total sao soma daqui.';
COMMENT ON COLUMN lancamento.valor IS
    'Sempre positivo. O sinal e responsabilidade de tipo (ENTRADA/SAIDA).';
COMMENT ON COLUMN lancamento.ambiente_id IS
    'Ambiente ativo na criacao (B-D2). Resolve a duvida em conta compartilhada e alimenta o relatorio (F33).';


-- #############################################################################
-- 3. ROW LEVEL SECURITY
-- #############################################################################
-- Tudo que a V3 explicou continua valendo: o tenant é o usuário (A08/R7), a
-- identidade chega por variável de sessão, e a proteção só funciona porque a
-- aplicação conecta como raspybank_app e não como proprietário.
--
-- A regra da casa para tabela nova: nasce com RLS ligado. Uma tabela de
-- domínio sem política é uma tabela que qualquer usuário lê inteira.
-- #############################################################################

-- -----------------------------------------------------------------------------
-- A função que faltava: quais contas o usuário enxerga
-- -----------------------------------------------------------------------------
-- Irmã de app_ambientes_do_usuario(). Mesmas razões para SECURITY DEFINER:
-- a política de conta precisa consultar conta_ambiente, que também tem
-- política — sem DEFINER, avaliar a política exigiria avaliar a política.
--
-- SET search_path é obrigatório em toda função SECURITY DEFINER: sem ele,
-- alguém poderia criar um schema no início do caminho de busca com uma tabela
-- chamada conta_ambiente e fazer esta função ler a tabela errada, com
-- privilégios de proprietário.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION app_contas_do_usuario()
RETURNS SETOF uuid
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT ca.conta_id
      FROM conta_ambiente ca
     WHERE ca.ambiente_id IN (SELECT app_ambientes_do_usuario());
$$;

COMMENT ON FUNCTION app_contas_do_usuario() IS
    'Contas visiveis ao usuario da sessao, via conta_ambiente. SECURITY DEFINER para evitar recursao de politica.';

GRANT EXECUTE ON FUNCTION app_contas_do_usuario() TO raspybank_app;


ALTER TABLE categoria ENABLE ROW LEVEL SECURITY;
CREATE POLICY pol_categoria_ambiente ON categoria
    FOR ALL
    USING      (ambiente_id IN (SELECT app_ambientes_do_usuario()))
    WITH CHECK (ambiente_id IN (SELECT app_ambientes_do_usuario()));

ALTER TABLE subcategoria ENABLE ROW LEVEL SECURITY;
CREATE POLICY pol_subcategoria_ambiente ON subcategoria
    FOR ALL
    USING      (ambiente_id IN (SELECT app_ambientes_do_usuario()))
    WITH CHECK (ambiente_id IN (SELECT app_ambientes_do_usuario()));

-- conta é a única que não filtra por ambiente_id, porque não tem a coluna.
-- Quem responde é o vínculo.
ALTER TABLE conta ENABLE ROW LEVEL SECURITY;
CREATE POLICY pol_conta_vinculada ON conta
    FOR ALL
    USING      (id IN (SELECT app_contas_do_usuario()))
    WITH CHECK (id IN (SELECT app_contas_do_usuario()));

-- conta_ambiente é a tabela mais perigosa desta migração, e o WITH CHECK
-- abaixo tem DUAS condições por um motivo concreto.
--
-- Conferir só o ambiente pareceria suficiente — afinal, você só vincula ao SEU
-- ambiente. Mas o vínculo tem dois lados, e o outro lado é a conta:
--
--     INSERT INTO conta_ambiente VALUES (<conta do Bruno>, <ambiente da Alice>);
--
-- Com só a primeira condição, isso passaria. Alice não enxerga a conta do
-- Bruno, então precisaria do UUID por outro meio — mas "precisa adivinhar o
-- identificador" é obscuridade, não controle de acesso. E o estrago seria
-- total: vinculada ao ambiente dela, a conta e todo o histórico dela passariam
-- a ser visíveis.
--
-- A segunda condição fecha isso: só se vincula conta que já se enxerga.
-- Compartilhar a PRÓPRIA conta num segundo ambiente seu continua funcionando;
-- capturar conta alheia deixa de funcionar.
--
-- E a conta recém-criada, que ninguém enxerga ainda? Ela não passa por aqui —
-- passa por app_criar_conta(), a porta estreita da seção 6.
ALTER TABLE conta_ambiente ENABLE ROW LEVEL SECURITY;
CREATE POLICY pol_ca_ambiente ON conta_ambiente
    FOR ALL
    USING (ambiente_id IN (SELECT app_ambientes_do_usuario()))
    WITH CHECK (
        ambiente_id IN (SELECT app_ambientes_do_usuario())
        AND conta_id IN (SELECT app_contas_do_usuario())
    );

ALTER TABLE lancamento ENABLE ROW LEVEL SECURITY;
CREATE POLICY pol_lancamento_ambiente ON lancamento
    FOR ALL
    USING      (ambiente_id IN (SELECT app_ambientes_do_usuario()))
    WITH CHECK (ambiente_id IN (SELECT app_ambientes_do_usuario()));


-- -----------------------------------------------------------------------------
-- CONSEQUÊNCIA: criar conta exige uma porta estreita
-- -----------------------------------------------------------------------------
-- A política acima cria o mesmo impasse que a V4 encontrou no cadastro e a V5
-- na criação do ambiente:
--
--   O WITH CHECK de conta pergunta a app_contas_do_usuario() se a conta é
--   visível. Para uma conta que está nascendo, a resposta é sempre NÃO — o
--   vínculo em conta_ambiente só pode existir DEPOIS que a conta existir, e a
--   conta só entra se o vínculo já existisse.
--
-- Nenhuma ordem de INSERT resolve, porque o WITH CHECK é avaliado na hora do
-- INSERT em conta. A saída é a mesma das outras duas migrações: uma porta
-- única, estreita e explícita — app_criar_conta(), na seção 6, que cria conta
-- e vínculo na mesma transação e não faz mais nada.
--
-- EXCEÇÃO ASSUMIDA E REGISTRADA: docs/security-definer.md estabelecia que
-- "operação de domínio NUNCA passa por SECURITY DEFINER, porque ela acontece
-- com identidade estabelecida e o RLS é exatamente quem deve julgá-la".
-- app_criar_conta() é a primeira exceção — e ela não contraria o MOTIVO da
-- regra, só a formulação. O problema aqui não é falta de identidade: é que a
-- visibilidade da conta depende de um vínculo que ainda não pode existir. É o
-- mesmo formato de impasse da V5, agora numa tabela de domínio. O inventário
-- foi emendado no mesmo commit, com o critério reescrito para falar do
-- impasse e não da camada.
-- -----------------------------------------------------------------------------


-- #############################################################################
-- 4. AUDITORIA POR GATILHO — F26, F27, B-D6
-- #############################################################################
-- A V2 registrou a intenção oposta: auditoria escrita pela camada de serviço,
-- porque só a aplicação sabe o CANAL de origem. A Fase 2 fechou F26 no sentido
-- contrário: gatilho lendo o contexto do RLS.
--
-- Os dois lados tinham razão sobre coisas diferentes, e a inconsistência I-05
-- ficou registrada meses esperando um vencedor. A resolução (B-D6) não escolheu
-- lado: removeu a razão do conflito. O aspecto que já injeta raspybank.usuario_id
-- passa a injetar raspybank.canal na mesma transação, e o gatilho lê os dois.
--
-- O que se ganha em relação ao modelo de serviço, e que era o argumento de F26:
-- alteração feita FORA da aplicação — psql, script, migração — grava autor nulo
-- e canal DESCONHECIDO. Ela se denuncia. Auditoria escrita pelo serviço só
-- registra o que o serviço faz, e por isso nunca acusa quem passou por fora.
-- #############################################################################

CREATE OR REPLACE FUNCTION fn_auditar()
RETURNS TRIGGER
LANGUAGE plpgsql
-- SECURITY DEFINER porque registro_auditoria tem RLS e o autor pode ser nulo
-- (alteração externa). Sem isto, a política recusaria a própria auditoria — e
-- o efeito seria perverso: a operação suspeita passaria e o registro dela não.
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_usuario   uuid;
    v_canal     text;
    v_operacao  text;
    v_anterior  jsonb;
    v_novo      jsonb;
    v_ambiente  uuid;
    v_entidade  text := TG_ARGV[0];
    v_id        uuid;
BEGIN
    -- Nulo quando não há identidade na sessão. É informação, não ausência
    -- dela: significa que alguém mexeu por fora (ver V8, item 2).
    v_usuario := NULLIF(current_setting('raspybank.usuario_id', true), '')::uuid;

    -- O canal de B-D6. DESCONHECIDO e não SISTEMA de propósito (V8, item 1):
    -- rotina do sistema e alteração manual são coisas diferentes, e a segunda
    -- é justamente o que a auditoria deveria gritar.
    v_canal := coalesce(
        NULLIF(current_setting('raspybank.canal', true), ''),
        'DESCONHECIDO');

    IF TG_OP = 'INSERT' THEN
        v_operacao := 'CRIACAO';
        v_novo     := to_jsonb(NEW);
    ELSIF TG_OP = 'UPDATE' THEN
        v_operacao := 'ALTERACAO';
        v_anterior := to_jsonb(OLD);
        v_novo     := to_jsonb(NEW);
    ELSE
        v_operacao := 'EXCLUSAO';
        v_anterior := to_jsonb(OLD);
    END IF;

    -- Extrair pelo JSON, e não por NEW.ambiente_id, deixa o gatilho servir a
    -- TODAS as tabelas de domínio com um código só — inclusive conta, que
    -- deliberadamente não tem a coluna (R7). Campo ausente vira NULL em vez de
    -- erro de compilação do plpgsql.
    v_ambiente := (coalesce(v_novo, v_anterior) ->> 'ambiente_id')::uuid;
    v_id       := (coalesce(v_novo, v_anterior) ->> 'id')::uuid;

    INSERT INTO registro_auditoria (
        ambiente_id, usuario_id, canal,
        entidade, entidade_id, operacao,
        estado_anterior, estado_novo)
    VALUES (
        v_ambiente, v_usuario, v_canal,
        v_entidade, v_id, v_operacao,
        v_anterior, v_novo);

    -- Gatilho AFTER: o valor de retorno é ignorado, mas plpgsql exige um.
    RETURN NULL;
END;
$$;

COMMENT ON FUNCTION fn_auditar() IS
    'Auditoria de dominio por gatilho (F26). Le usuario E canal do contexto do RLS (B-D6); ausencia deles denuncia alteracao externa.';


-- F27: todas as tabelas de domínio são auditadas. As exceções são a própria
-- auditoria e o outbox — auditar o registro de auditoria é recursão sem
-- informação nova.
--
-- O argumento do gatilho é o nome da entidade em linguagem de NEGÓCIO
-- ('Lancamento'), não o nome da tabela. Quem lê a trilha lê o domínio.
CREATE TRIGGER tg_auditar_categoria
    AFTER INSERT OR UPDATE OR DELETE ON categoria
    FOR EACH ROW EXECUTE FUNCTION fn_auditar('Categoria');

CREATE TRIGGER tg_auditar_subcategoria
    AFTER INSERT OR UPDATE OR DELETE ON subcategoria
    FOR EACH ROW EXECUTE FUNCTION fn_auditar('Subcategoria');

CREATE TRIGGER tg_auditar_conta
    AFTER INSERT OR UPDATE OR DELETE ON conta
    FOR EACH ROW EXECUTE FUNCTION fn_auditar('Conta');

CREATE TRIGGER tg_auditar_conta_ambiente
    AFTER INSERT OR UPDATE OR DELETE ON conta_ambiente
    FOR EACH ROW EXECUTE FUNCTION fn_auditar('ContaAmbiente');

CREATE TRIGGER tg_auditar_lancamento
    AFTER INSERT OR UPDATE OR DELETE ON lancamento
    FOR EACH ROW EXECUTE FUNCTION fn_auditar('Lancamento');


-- #############################################################################
-- 5. OUTBOX — F28
-- #############################################################################
-- "Outbox alimentado desde o primeiro lançamento, com relay em processo."
--
-- O evento é gravado na MESMA transação do lançamento — ou os dois entram, ou
-- nenhum entra. É a diferença entre um sistema que avisa e um sistema que
-- mente sobre si mesmo (ver V2 para o raciocínio completo).
--
-- Só lancamento publica. Categoria e conta são cadastro; ninguém reage à
-- criação de uma categoria. Publicar tudo "por precaução" encheria a tabela de
-- eventos que nenhum consumidor lê.
-- #############################################################################

CREATE OR REPLACE FUNCTION fn_publicar_evento_lancamento()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_tipo    text;
    v_estado  jsonb;
BEGIN
    IF TG_OP = 'INSERT' THEN
        v_tipo := 'LancamentoRegistrado';
        v_estado := to_jsonb(NEW);
    ELSIF TG_OP = 'UPDATE' THEN
        v_tipo := 'LancamentoAlterado';
        v_estado := to_jsonb(NEW);
    ELSE
        v_tipo := 'LancamentoExcluido';
        v_estado := to_jsonb(OLD);
    END IF;

    INSERT INTO outbox (ambiente_id, tipo_evento, agregado, agregado_id, payload)
    VALUES (
        (v_estado ->> 'ambiente_id')::uuid,
        v_tipo,
        'Lancamento',
        (v_estado ->> 'id')::uuid,
        -- Payload AUTOCONTIDO (V2): quem consome não pode precisar consultar a
        -- tabela de origem para entender o que aconteceu. Mandar a linha
        -- inteira é o jeito mais barato de garantir isso enquanto não há
        -- consumidor real dizendo do que precisa.
        v_estado);

    RETURN NULL;
END;
$$;

COMMENT ON FUNCTION fn_publicar_evento_lancamento() IS
    'Grava o evento na mesma transacao do lancamento (F28). Entrega pelo menos uma vez: consumidores idempotentes.';

CREATE TRIGGER tg_outbox_lancamento
    AFTER INSERT OR UPDATE OR DELETE ON lancamento
    FOR EACH ROW EXECUTE FUNCTION fn_publicar_evento_lancamento();


-- #############################################################################
-- 6. CATEGORIAS SISTÊMICAS — F9, F10, F13, B-D13, B-D16
-- #############################################################################
-- A lista que nunca tinha sido escrita.
--
-- F10 dizia que categoria.codigo identifica as sistêmicas. F13 dizia que todo
-- ambiente nasce com elas. F9 dizia que são copiadas por ambiente. QUAIS eram
-- elas não estava em documento nenhum (achado I-21 de 26/07) — e sem a lista
-- esta migração não teria como existir.
--
-- O critério de B-D13: só entra o que o CÓDIGO referencia por codigo e não
-- pode perder. Sistêmica é cadeado, e cadeado só onde quebrar dói.
--
--   TRANSFERENCIA     F2 faz da transferência dois lançamentos ligados; eles
--                     precisam de categoria, e ela não pode ser apagada.
--                     Fora do mapa: mover dinheiro entre contas suas não é
--                     gasto, e somar inflaria o total da tela central.
--
--   AJUSTE            A13 fez do saldo de abertura um lançamento. Fora do
--                     mapa pela mesma razão: é correção contábil, não despesa.
--
--   NAO_CLASSIFICADO  F11 torna a subcategoria opcional, mas a CATEGORIA é
--                     obrigatória. Quando o bot do Telegram receber "gastei 50
--                     no mercado" sem classificação, precisa de destino válido.
--                     DENTRO do mapa (B-D15): é gasto real, só falta o rótulo.
--
-- PAGAMENTO_FATURA fica reservado para a V11, junto do cartão.
--
-- B-D14 manteve F13 ao pé da letra: NÃO existe kit inicial de categorias
-- editáveis. A estrutura de gastos é pessoal; entregar "Moradia, Lazer,
-- Assinaturas" pronto economiza cinco minutos e impõe um vocabulário para
-- sempre.
-- #############################################################################

CREATE OR REPLACE FUNCTION fn_criar_categorias_sistemicas(p_ambiente_id uuid)
RETURNS void
LANGUAGE sql
AS $$
    INSERT INTO categoria (ambiente_id, codigo, nome, tipo, sistemica, entra_no_mapa)
    VALUES
        (p_ambiente_id, 'TRANSFERENCIA',    'Transferência',    'AMBOS', true, false),
        (p_ambiente_id, 'AJUSTE',           'Ajuste de saldo',  'AMBOS', true, false),
        (p_ambiente_id, 'NAO_CLASSIFICADO', 'Não classificado', 'AMBOS', true, true)
    -- Idempotente de propósito: esta função é chamada na criação do ambiente E
    -- na retroalimentação abaixo. Rodar duas vezes não pode quebrar nada.
    ON CONFLICT DO NOTHING;
$$;

COMMENT ON FUNCTION fn_criar_categorias_sistemicas(uuid) IS
    'As tres sistemicas de B-D13. Idempotente: chamada na criacao do ambiente e na retroalimentacao.';


-- -----------------------------------------------------------------------------
-- A promessa de F13 passa a ser cumprida
-- -----------------------------------------------------------------------------
-- A V5 criou auth_criar_ambiente_inicial e ela nunca criou categoria nenhuma —
-- F13 prometia desde a Fase 2 e ninguém executava. Ambiente sem as sistêmicas
-- não consegue receber transferência nem ajuste de saldo.
--
-- CREATE OR REPLACE preserva o identificador interno da função, então quem
-- depende dela continua valendo sem ser recriado. Mesmo cuidado da V8, item 4.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION auth_criar_ambiente_inicial(
    p_usuario_id uuid,
    p_nome text
)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_ambiente_id uuid;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM usuario WHERE id = p_usuario_id) THEN
        RAISE EXCEPTION 'Usuario % nao encontrado', p_usuario_id;
    END IF;

    IF EXISTS (SELECT 1 FROM usuario_ambiente WHERE usuario_id = p_usuario_id) THEN
        RAISE EXCEPTION 'Usuario % ja possui ambiente', p_usuario_id;
    END IF;

    INSERT INTO ambiente (nome) VALUES (p_nome) RETURNING id INTO v_ambiente_id;

    INSERT INTO usuario_ambiente (usuario_id, ambiente_id)
    VALUES (p_usuario_id, v_ambiente_id);

    -- A novidade da V10: o ambiente nasce com as sistêmicas, na MESMA
    -- transação. Se as categorias falharem, o ambiente não existe — a promessa
    -- de F13 não pode valer só às vezes.
    PERFORM fn_criar_categorias_sistemicas(v_ambiente_id);

    RETURN v_ambiente_id;
END;
$$;

GRANT EXECUTE ON FUNCTION auth_criar_ambiente_inicial(uuid, text) TO raspybank_app;

COMMENT ON FUNCTION auth_criar_ambiente_inicial(uuid, text) IS
    'Cria ambiente, vinculo e categorias sistemicas no cadastro (B-D16). Porta unica: recusa se o usuario ja tiver ambiente.';


-- -----------------------------------------------------------------------------
-- Retroalimentação dos ambientes que já existem
-- -----------------------------------------------------------------------------
-- Todo ambiente criado antes desta migração nasceu sem sistêmica nenhuma,
-- porque a V5 não as criava. Sem este trecho, as contas existentes ficariam
-- permanentemente incapazes de registrar uma transferência — e o defeito só
-- apareceria muito depois, num ambiente antigo, sem causa aparente.
--
-- Roda como proprietário (é migração), então o RLS não atrapalha.
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    r record;
BEGIN
    FOR r IN SELECT id FROM ambiente LOOP
        PERFORM fn_criar_categorias_sistemicas(r.id);
    END LOOP;
END;
$$;


-- -----------------------------------------------------------------------------
-- Porta estreita para criar conta
-- -----------------------------------------------------------------------------
-- Mesmo impasse que a V4 resolveu para o cadastro e a V5 para o ambiente: a
-- política de conta exige um vínculo em conta_ambiente que só pode existir
-- depois da conta, e a conta só entra se o vínculo já existisse.
--
-- A saída é a mesma das outras duas: uma porta única, estreita e explícita.
-- Executa com privilégios do proprietário, cria conta e vínculo na mesma
-- transação, e devolve o identificador. Nada além disso.
--
-- A checagem de vínculo do usuário com o ambiente NÃO é opcional: sem ela, a
-- função aceitaria qualquer ambiente e viraria justamente o buraco que o RLS
-- existe para fechar. SECURITY DEFINER ignora políticas — quem escreve uma
-- função dessas assume a responsabilidade que o banco deixou de ter.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION app_criar_conta(
    p_ambiente_id uuid,
    p_nome        text,
    p_natureza    text
)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_conta_id uuid;
    v_usuario  uuid := app_usuario_id();
BEGIN
    IF v_usuario IS NULL THEN
        RAISE EXCEPTION 'Sem identidade na sessao';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM usuario_ambiente
         WHERE usuario_id = v_usuario AND ambiente_id = p_ambiente_id
    ) THEN
        RAISE EXCEPTION 'Usuario % nao pertence ao ambiente %', v_usuario, p_ambiente_id;
    END IF;

    INSERT INTO conta (nome, natureza)
    VALUES (p_nome, p_natureza)
    RETURNING id INTO v_conta_id;

    INSERT INTO conta_ambiente (conta_id, ambiente_id)
    VALUES (v_conta_id, p_ambiente_id);

    RETURN v_conta_id;
END;
$$;

GRANT EXECUTE ON FUNCTION app_criar_conta(uuid, text, text) TO raspybank_app;

COMMENT ON FUNCTION app_criar_conta(uuid, text, text) IS
    'Porta unica de criacao de conta: cria conta e vinculo juntos. Confere o vinculo do usuario com o ambiente.';


-- =============================================================================
-- COMO VERIFICAR MANUALMENTE
-- =============================================================================
-- make psql-app
--
--   SELECT * FROM categoria;
--   -- 0 linhas: sem identidade na sessao, nada e visivel
--
--   SELECT set_config('raspybank.usuario_id', '<seu-uuid>', false);
--   SELECT codigo, nome, sistemica, entra_no_mapa FROM categoria;
--   -- 3 linhas: as sistemicas do seu ambiente
--
--   SELECT set_config('raspybank.canal', 'WEB', false);
--   SELECT app_criar_conta('<ambiente-uuid>', 'Corrente', 'ATIVO');
--   SELECT entidade, operacao, canal FROM registro_auditoria
--    ORDER BY ocorrido_em DESC LIMIT 2;
--   -- Conta/CRIACAO/WEB e ContaAmbiente/CRIACAO/WEB
--
-- Repita a criacao SEM definir raspybank.canal: a auditoria grava
-- DESCONHECIDO e usuario nulo. E o comportamento desejado — a alteracao que
-- passou por fora se denuncia sozinha (F26 / B-D6).
-- =============================================================================
