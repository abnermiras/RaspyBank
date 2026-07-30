-- =============================================================================
-- V15 — Compartilhamento de ambiente
-- =============================================================================
-- Implementa o desenho de decisoes.md §4j (B-D74 a B-D84) e api.md §2c.
--
-- A frase que orienta tudo: "e como se eu desse a minha senha para a pessoa,
-- mas ao inves de dar minha senha dei meu acesso".
--
-- B-D74: compartilhar ambiente e UMA LINHA em usuario_ambiente. O tenant do
-- RLS sempre foi o usuario e a visibilidade sempre foi por vinculo (A08/R7):
-- inserida a linha, o ambiente aparece na lista da pessoa e todas as politicas
-- ja respondem certo — contas, categorias, cartoes, mapa. Esta migracao nao
-- cria mecanismo novo de visibilidade; ela cria a COLUNA DONO e aperta as
-- portas que o compartilhamento passa a exigir.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. A coluna dono — B-D75
-- -----------------------------------------------------------------------------
-- Um booleano, e NAO um sistema de papeis ("por hora nao vai ter perfil nem
-- nada"). Ela responde uma pergunta so — quem abriu a porta — e divide o mundo
-- em dois: mexer no DINHEIRO (todos) e mexer na PORTA (so o dono).
ALTER TABLE usuario_ambiente ADD COLUMN dono boolean NOT NULL DEFAULT false;

-- Retroalimentacao: ate esta migracao nao existia convite, entao cada ambiente
-- tem exatamente um membro — e ele e quem o criou. Todo mundo vira dono do que
-- ja tem.
UPDATE usuario_ambiente SET dono = true;

-- Exatamente UM dono por ambiente, garantido pela estrutura e nao por
-- validacao. Indice PARCIAL no formato de ux_cfp_padrao_saida (V11): um UNIQUE
-- sobre (ambiente_id, dono) tambem limitaria a um NAO-dono por ambiente, que e
-- o oposto do que se quer.
CREATE UNIQUE INDEX ux_ua_um_dono ON usuario_ambiente (ambiente_id) WHERE dono;

COMMENT ON COLUMN usuario_ambiente.dono IS
    'Quem abriu a porta (B-D75). Dinheiro e de todos; porta (convidar, remover, renomear, apagar) e so do dono.';


-- -----------------------------------------------------------------------------
-- 2. As funcoes irmas — filtram por dono
-- -----------------------------------------------------------------------------
-- Mesmo formato e mesma razao de SECURITY DEFINER de app_ambientes_do_usuario:
-- politicas precisam consultar usuario_ambiente, que tambem tem politica —
-- sem DEFINER, avaliar a politica exigiria avaliar a politica.

CREATE OR REPLACE FUNCTION app_ambientes_proprios()
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
       AND ua.dono
       AND a.excluido_em IS NULL;
$$;

COMMENT ON FUNCTION app_ambientes_proprios() IS
    'Ambientes em que o usuario da sessao e DONO. Irma de app_ambientes_do_usuario, com o filtro de B-D75.';

CREATE OR REPLACE FUNCTION app_contas_proprias()
RETURNS SETOF uuid
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT ca.conta_id
      FROM conta_ambiente ca
     WHERE ca.ambiente_id IN (SELECT app_ambientes_proprios());
$$;

COMMENT ON FUNCTION app_contas_proprias() IS
    'Contas vinculadas a algum ambiente de que o usuario da sessao e dono. Irma de app_contas_do_usuario.';

-- Os membros dos ambientes do usuario da sessao. Alimenta a politica de
-- leitura de usuario: quem compartilha financas precisa saber COM QUEM
-- compartilha — o nome na lista de acessos e o "quem" carimbado em cada acao.
CREATE OR REPLACE FUNCTION app_membros_dos_meus_ambientes()
RETURNS SETOF uuid
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT ua.usuario_id
      FROM usuario_ambiente ua
     WHERE ua.ambiente_id IN (SELECT app_ambientes_do_usuario());
$$;

COMMENT ON FUNCTION app_membros_dos_meus_ambientes() IS
    'Usuarios que dividem algum ambiente com o usuario da sessao. Alimenta a politica de leitura de usuario.';

GRANT EXECUTE ON FUNCTION app_ambientes_proprios()          TO raspybank_app;
GRANT EXECUTE ON FUNCTION app_contas_proprias()             TO raspybank_app;
GRANT EXECUTE ON FUNCTION app_membros_dos_meus_ambientes()  TO raspybank_app;


-- -----------------------------------------------------------------------------
-- 3. usuario_ambiente — a porta, em politicas
-- -----------------------------------------------------------------------------
-- A politica unica FOR ALL da V3 dizia so "e do meu ambiente". Com convite,
-- cada verbo passa a ter regra propria (B-D76/B-D77), e por isso ela se separa
-- em tres. O que NAO existe tambem e regra: nenhuma politica de UPDATE
-- significa que dono nao se transfere — nem por engano, nem por injecao.
DROP POLICY pol_ua_visivel ON usuario_ambiente;

-- Ver os vinculos continua como era: membros do mesmo ambiente se enxergam,
-- porque quem compartilha financas precisa saber com quem compartilha.
CREATE POLICY pol_ua_leitura ON usuario_ambiente
    FOR SELECT
    USING (ambiente_id IN (SELECT app_ambientes_do_usuario()));

-- Conceder e PORTA: so o dono insere vinculo, e o vinculo nasce nao-dono.
-- A segunda condicao nao e redundancia do indice ux_ua_um_dono: o indice
-- impede DOIS donos, esta clausula impede o proprio dono de nomear um
-- substituto sem sair — transferencia de posse e conversa que nao aconteceu.
CREATE POLICY pol_ua_conceder ON usuario_ambiente
    FOR INSERT
    WITH CHECK (
        ambiente_id IN (SELECT app_ambientes_proprios())
        AND NOT dono
    );

-- B-D77: o dono remove qualquer um; qualquer um remove a si mesmo; o dono nao
-- sai. A primeira condicao (NOT dono) e a terceira regra — a linha do dono e
-- irremovivel por esta via, e o caminho para se livrar do ambiente e apaga-lo,
-- que e a conversa adiada.
CREATE POLICY pol_ua_remover ON usuario_ambiente
    FOR DELETE
    USING (
        NOT dono
        AND (
            usuario_id = app_usuario_id()                        -- sair sozinho
            OR ambiente_id IN (SELECT app_ambientes_proprios())  -- dono remove
        )
    );


-- -----------------------------------------------------------------------------
-- 4. conta_ambiente — B-D18 APERTA (B-D78), e isto fecha o I-23 (B-D79)
-- -----------------------------------------------------------------------------
-- B-D18 dizia "so se vincula conta que ja se enxerga", e estava certo para a
-- epoca: enxergar era sinonimo de ser dono. Com compartilhamento deixa de ser
-- — a convidada poderia levar a conta conjunta para o ambiente pessoal dela e
-- lancar de la, invisivel ao dono. Vincular e desvincular passam a exigir ser
-- DONO dos dois lados: do ambiente que recebe e de um ambiente onde a conta
-- ja vive. Com isso todo lancamento da conta compartilhada nasce no mesmo
-- ambiente, e as duas pessoas veem o mesmo saldo, a mesma fatura e o mesmo
-- mapa. Nao e contorno: o desenho escolhido simplesmente nao cria a
-- divergencia.
DROP POLICY pol_ca_ambiente ON conta_ambiente;

CREATE POLICY pol_ca_leitura ON conta_ambiente
    FOR SELECT
    USING (ambiente_id IN (SELECT app_ambientes_do_usuario()));

CREATE POLICY pol_ca_vincular ON conta_ambiente
    FOR INSERT
    WITH CHECK (
        ambiente_id IN (SELECT app_ambientes_proprios())
        AND conta_id IN (SELECT app_contas_proprias())
    );

-- Desvincular tem o mesmo peso do vinculo: tirar a conta do unico ambiente em
-- que ela aparece a esconderia de todo mundo, dono incluido.
CREATE POLICY pol_ca_desvincular ON conta_ambiente
    FOR DELETE
    USING (
        ambiente_id IN (SELECT app_ambientes_proprios())
        AND conta_id IN (SELECT app_contas_proprias())
    );

-- A conta recem-criada continua nao passando por aqui: ela entra por
-- app_criar_conta(), a porta estreita da V10, que ja confere o vinculo do
-- usuario com o ambiente — e vinculo basta, porque criar conta e DINHEIRO
-- (B-D76), nao porta.


-- -----------------------------------------------------------------------------
-- 5. ambiente — renomear e apagar sao PORTA (B-D76)
-- -----------------------------------------------------------------------------
-- Renomear esta do lado da porta porque o nome e UM SO e aparece na lista de
-- todos, inclusive na do dono. Ainda nao existe endpoint de renomear; esta
-- politica e o alicerce que ja chega certo — quando a tela nascer, o banco ja
-- recusa o convidado por conta propria.
DROP POLICY pol_ambiente_vinculado ON ambiente;

CREATE POLICY pol_ambiente_leitura ON ambiente
    FOR SELECT
    USING (id IN (SELECT app_ambientes_do_usuario()));

CREATE POLICY pol_ambiente_porta ON ambiente
    FOR UPDATE
    USING      (id IN (SELECT app_ambientes_proprios()))
    WITH CHECK (id IN (SELECT app_ambientes_proprios()));

-- Sem politica de INSERT nem de DELETE, de proposito: ambiente nasce pelas
-- portas estreitas (auth_criar_ambiente_inicial, app_criar_ambiente), que sao
-- SECURITY DEFINER, e apagar ambiente e a conversa adiada — quando ela
-- acontecer, ganhara politica propria.


-- -----------------------------------------------------------------------------
-- 6. usuario — o convidado tem nome
-- -----------------------------------------------------------------------------
-- pol_usuario_proprio dizia "cada um enxerga apenas a si mesmo", e valia
-- enquanto ninguem dividia ambiente. A lista de acessos (§2c) mostra nome e
-- e-mail de cada membro, e a auditoria carimba o autor — um autor que os
-- outros precisam conseguir LER. A politica de leitura passa a ser "eu, e quem
-- divide ambiente comigo"; a de escrita continua "so eu".
--
-- O que o co-membro passa a ver e a linha CADASTRAL (nome, e-mail, telegram).
-- senha_hash continua fora do alcance por privilegio de COLUNA desde a V8 —
-- politica nenhuma daria acesso a ela.
DROP POLICY pol_usuario_proprio ON usuario;

CREATE POLICY pol_usuario_leitura ON usuario
    FOR SELECT
    USING (
        id = app_usuario_id()
        OR id IN (SELECT app_membros_dos_meus_ambientes())
    );

CREATE POLICY pol_usuario_escrita ON usuario
    FOR UPDATE
    USING      (id = app_usuario_id())
    WITH CHECK (id = app_usuario_id());

-- Sem politica de INSERT (cadastro passa por auth_cadastrar_usuario, DEFINER)
-- nem de DELETE (usuario nao se apaga; se um dia se apagar, ganhara desenho).


-- -----------------------------------------------------------------------------
-- 7. E-mail vira convite — a funcao estreita
-- -----------------------------------------------------------------------------
-- Para convidar, o dono digita um e-mail — de alguem que, por definicao, ainda
-- NAO divide ambiente com ele. E o impasse do criterio B-D19 na forma pura: a
-- linha so se tornaria visivel pelo vinculo que esta operacao vai criar.
-- Devolve o minimo: o id, e nada alem. Nome e e-mail do convidado so aparecem
-- DEPOIS do vinculo, pela politica normal.
--
-- B-D81, aceito conscientemente: quem tem conta descobre, um por vez, se um
-- e-mail esta cadastrado (o 404 da API e um oraculo de enumeracao). A
-- alternativa era pior no caso real — responder "ok" para um e-mail digitado
-- errado esconderia o erro mais comum de todos. Se isto sair da rede de casa,
-- a decisao se revisita.
CREATE OR REPLACE FUNCTION app_usuario_por_email(p_email text)
RETURNS uuid
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT u.id
      FROM usuario u
     WHERE lower(u.email) = lower(p_email)
       AND u.status = 'ATIVO';  -- maiusculo desde a V8 (normalizacao)
$$;

COMMENT ON FUNCTION app_usuario_por_email(text) IS
    'Resolve e-mail em id para o convite (§2c). Devolve SO o id; nome e e-mail so aparecem depois do vinculo.';

GRANT EXECUTE ON FUNCTION app_usuario_por_email(text) TO raspybank_app;


-- -----------------------------------------------------------------------------
-- 8. As portas estreitas aprendem o dono
-- -----------------------------------------------------------------------------
-- Sem isto, todo ambiente novo nasceria SEM dono — e ux_ua_um_dono nao acusa
-- ausencia, so duplicidade. O defeito apareceria dias depois, num convite que
-- ninguem consegue fazer. CREATE OR REPLACE preserva o identificador interno
-- (mesmo cuidado da V8, item 4).

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

    -- Quem cria e dono (B-D75).
    INSERT INTO usuario_ambiente (usuario_id, ambiente_id, dono)
    VALUES (p_usuario_id, v_ambiente_id, true);

    PERFORM fn_criar_categorias_sistemicas(v_ambiente_id);

    RETURN v_ambiente_id;
END;
$$;

CREATE OR REPLACE FUNCTION app_criar_ambiente(p_nome text)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_ambiente_id uuid;
    v_usuario     uuid := app_usuario_id();
BEGIN
    IF v_usuario IS NULL THEN
        RAISE EXCEPTION 'Sem identidade na sessao';
    END IF;

    IF p_nome IS NULL OR btrim(p_nome) = '' THEN
        RAISE EXCEPTION 'Nome do ambiente e obrigatorio';
    END IF;

    INSERT INTO ambiente (nome) VALUES (btrim(p_nome)) RETURNING id INTO v_ambiente_id;

    -- Quem cria e dono (B-D75).
    INSERT INTO usuario_ambiente (usuario_id, ambiente_id, dono)
    VALUES (v_usuario, v_ambiente_id, true);

    PERFORM fn_criar_categorias_sistemicas(v_ambiente_id);

    RETURN v_ambiente_id;
END;
$$;


-- -----------------------------------------------------------------------------
-- 9. Auditoria do proprio acesso
-- -----------------------------------------------------------------------------
-- B-D82 disse que a auditoria de DOMINIO nao muda em nada — fn_auditar le o
-- autor da sessao, entao toda acao da convidada ja nasce carimbada com o nome
-- dela. O que faltava era auditar a PORTA: conceder e revogar acesso sao
-- exatamente o tipo de acao que se quer reconstruir depois ("quem colocou
-- fulano aqui? quando?"). F27 manda: tabela de dominio que muda, audita.
--
-- Vinculos criados no cadastro (pre-identidade) entram com autor nulo e canal
-- DESCONHECIDO — mesmo comportamento das categorias sistemicas, e igualmente
-- inocuo: e o registro de que o sistema agiu sozinho.
CREATE TRIGGER tg_auditar_usuario_ambiente
    AFTER INSERT OR UPDATE OR DELETE ON usuario_ambiente
    FOR EACH ROW EXECUTE FUNCTION fn_auditar('Acesso');


-- =============================================================================
-- COMO VERIFICAR MANUALMENTE
-- =============================================================================
-- make psql-app
--
--   SELECT set_config('raspybank.usuario_id', '<uuid-do-dono>', false);
--   SELECT set_config('raspybank.canal', 'WEB', false);
--
--   -- Conceder: uma linha, e nada mais (B-D74)
--   INSERT INTO usuario_ambiente (usuario_id, ambiente_id)
--   VALUES ('<uuid-da-convidada>', '<ambiente-do-dono>');
--
--   -- Trocar para a convidada: ela ve o ambiente, as contas, as categorias
--   SELECT set_config('raspybank.usuario_id', '<uuid-da-convidada>', false);
--   SELECT nome FROM ambiente;
--
--   -- B-D78: levar a conta compartilhada para o ambiente pessoal dela FALHA
--   INSERT INTO conta_ambiente (conta_id, ambiente_id)
--   VALUES ('<conta-do-ambiente-compartilhado>', '<ambiente-pessoal-dela>');
--   -- ERRO: new row violates row-level security policy
--
--   -- B-D77: ela nao remove o dono, mas remove a si mesma
--   DELETE FROM usuario_ambiente WHERE usuario_id = '<uuid-do-dono>';   -- 0 linhas
--   DELETE FROM usuario_ambiente WHERE usuario_id = app_usuario_id()
--      AND ambiente_id = '<ambiente-do-dono>';                          -- 1 linha
-- =============================================================================
