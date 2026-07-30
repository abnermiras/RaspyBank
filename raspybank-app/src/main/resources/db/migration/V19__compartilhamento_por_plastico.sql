-- =============================================================================
-- V19 — O PLASTICO como unidade do compartilhamento de cartao
-- =============================================================================
-- Implementa decisoes.md §4n (B-D106 a B-D110) e api.md §2e, reescrita.
--
-- A V17 saiu no dia anterior e fez a unidade ser a CONTA DO CONTRATO: dividir um
-- cartao entregava os dez plasticos. O uso mostrou o modelo certo, na frase dele:
--
--   "tenho um contrato com limite total de 30 mil. Crio um adicional em nome da
--    Luciana (...) que eu posso opcionalmente dizer que tem 1.000 dentro dos meus
--    30.000. A questao do compartilhamento e poder dar para ela, la no meio de
--    pagamento do lancamento, a possibilidade de apontar este cartao adicional
--    que esta em nome dela porem DENTRO DA MINHA FATURA."
--
-- O que ja estava certo e nao muda: a fatura pertence ao contrato, o lancamento
-- sabe qual plastico fez a compra (cartao_emitido_id, V13) e limite_proprio
-- existe desde a V12. As "mini faturas" do e-mail do banco sao agrupamento do
-- que ja esta gravado.
--
-- -----------------------------------------------------------------------------
-- O VINCULO DA CONTA CONTINUA, MAS DEIXA DE SER A CONCESSAO
-- -----------------------------------------------------------------------------
-- O lancamento dela precisa de conta_ambiente (chave composta de B-D2), e a
-- conta do lancamento de cartao e a conta do CARTAO. Entao o vinculo tem de
-- existir no ambiente dela — o que muda e o significado: ele passa a ser
-- CONSEQUENCIA de ela ter um plastico, e nao o ato de conceder.
--
-- Quem decide o que ela ve, daqui para frente, e cartao_emitido_ambiente.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. cartao_emitido_ambiente — a concessao, agora (B-D106)
-- -----------------------------------------------------------------------------
CREATE TABLE cartao_emitido_ambiente (

    cartao_emitido_id uuid        NOT NULL
                                  REFERENCES cartao_emitido (id) ON DELETE RESTRICT,

    ambiente_id       uuid        NOT NULL
                                  REFERENCES ambiente (id) ON DELETE RESTRICT,

    criado_em         timestamptz NOT NULL DEFAULT now(),

    -- Revogacao logica, pelo mesmo motivo de conta_ambiente.encerrado_em
    -- (B-D93): as compras que ela fez no plastico ficam, porque aquele dinheiro
    -- entrou na fatura de verdade.
    encerrado_em      timestamptz,

    CONSTRAINT pk_cartao_emitido_ambiente PRIMARY KEY (cartao_emitido_id, ambiente_id)
);

CREATE INDEX ix_cea_ambiente ON cartao_emitido_ambiente (ambiente_id)
    WHERE encerrado_em IS NULL;

COMMENT ON TABLE cartao_emitido_ambiente IS
    'Quais plasticos aparecem em quais ambientes (B-D106). O plastico e a unidade do compartilhamento de cartao; a conta do contrato nao e.';
COMMENT ON COLUMN cartao_emitido_ambiente.ambiente_id IS
    'O ambiente ESCOLHIDO por quem recebeu, no aceite (B-D90). Nao existe linha para o ambiente de origem: la o plastico aparece por nascimento.';

CREATE TRIGGER tg_auditar_cartao_emitido_ambiente
    AFTER INSERT OR UPDATE OR DELETE ON cartao_emitido_ambiente
    FOR EACH ROW EXECUTE FUNCTION fn_auditar('PlasticoCompartilhado');

ALTER TABLE cartao_emitido_ambiente ENABLE ROW LEVEL SECURITY;


-- -----------------------------------------------------------------------------
-- 2. Quais plasticos eu alcanco
-- -----------------------------------------------------------------------------
-- DEFINER pela razao de sempre nesta familia: a funcao consulta
-- usuario_ambiente, que tem politica, e alimenta politicas — sem DEFINER,
-- avaliar a politica exigiria avaliar a politica.
CREATE OR REPLACE FUNCTION app_emitidos_liberados()
RETURNS SETOF uuid
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT cea.cartao_emitido_id
      FROM cartao_emitido_ambiente cea
     WHERE cea.ambiente_id IN (SELECT app_ambientes_do_usuario())
       AND cea.encerrado_em IS NULL;
$$;

COMMENT ON FUNCTION app_emitidos_liberados() IS
    'Plasticos compartilhados COM o usuario da sessao (B-D106). Nao inclui os que nasceram nos ambientes dele — esses vem por app_contas_nao_emprestadas.';

GRANT EXECUTE ON FUNCTION app_emitidos_liberados() TO raspybank_app;


-- -----------------------------------------------------------------------------
-- 3. cartao_emitido — quem ve qual plastico
-- -----------------------------------------------------------------------------
-- A V17 dizia "ve o emitido quem ve a conta do cartao", e era isso que entregava
-- os dez plasticos junto. Agora sao duas origens de visibilidade, e a diferenca
-- entre elas e o coracao desta migracao:
--
--   nasceu num ambiente meu   -> vejo todos os plasticos do contrato;
--   foi compartilhado comigo  -> vejo AQUELE plastico, e mais nenhum.
DROP POLICY pol_cartao_emitido_leitura ON cartao_emitido;

CREATE POLICY pol_cartao_emitido_leitura ON cartao_emitido
    FOR SELECT
    USING (
        cartao_id IN (SELECT app_contas_nao_emprestadas())
        OR id IN (SELECT app_emitidos_liberados())
    );

-- A escrita nao muda: emitir, cancelar e reativar sao porta do contrato
-- (B-D101), e pol_cartao_emitido_escrita da V17 continua valendo como esta.

-- E as politicas de cartao_emitido_ambiente, agora que ha com que compara-las.
--
-- Ler: o dono do contrato ve com quem dividiu; quem recebeu ve o proprio
-- vinculo. Cada um so o seu lado, como em conta_ambiente.
CREATE POLICY pol_cea_leitura ON cartao_emitido_ambiente
    FOR SELECT
    USING (
        ambiente_id IN (SELECT app_ambientes_do_usuario())
        OR cartao_emitido_id IN (
            SELECT ce.id FROM cartao_emitido ce
             WHERE ce.cartao_id IN (SELECT app_contas_proprias())
        )
    );

-- Escrever: NENHUMA politica de INSERT nem de UPDATE, de proposito. Conceder
-- passa por app_aceitar_convite_de_plastico e revogar por
-- app_revogar_plastico_compartilhado — as duas DEFINER, pelos impasses que os
-- comentarios delas explicam. Sem politica, nao ha caminho por fora.
CREATE POLICY pol_cea_apagar ON cartao_emitido_ambiente
    FOR DELETE
    USING (false);

COMMENT ON POLICY pol_cea_apagar ON cartao_emitido_ambiente IS
    'Recusa explicita: o vinculo nao se apaga, se encerra (encerrado_em). As compras feitas no plastico precisam continuar existindo.';


-- -----------------------------------------------------------------------------
-- 4. O convite aprende o plastico
-- -----------------------------------------------------------------------------
-- Uma coluna anulavel em vez de uma tabela nova: o convite ja carrega conta,
-- convidado e a trilha, e o que muda e UM detalhe do que esta sendo oferecido.
-- Tabela separada duplicaria o fluxo inteiro — criar, listar, aceitar, recusar,
-- cancelar — para diferir num campo.
ALTER TABLE conta_convite ADD COLUMN cartao_emitido_id uuid
    REFERENCES cartao_emitido (id) ON DELETE CASCADE;

-- O par tem de ser coerente: o plastico oferecido precisa pertencer ao cartao
-- cuja conta esta no convite. Sem isto, um convite poderia oferecer o plastico
-- de um cartao e o vinculo de outro — e o aceite gravaria as duas coisas, cada
-- uma apontando para um cartao diferente.
--
-- O indice existe para ser ALVO da chave composta: a PK de cartao_emitido e
-- (id), e o Postgres exige um indice unico sobre exatamente as colunas
-- referenciadas. Ele nao acrescenta restricao nenhuma — (id, cartao_id) ja e
-- unico porque id sozinho e. E o mesmo padrao de uq_ca_ambiente_conta na V10.
CREATE UNIQUE INDEX ux_ce_id_cartao ON cartao_emitido (id, cartao_id);

ALTER TABLE conta_convite ADD CONSTRAINT fk_convite_plastico_do_cartao
    FOREIGN KEY (cartao_emitido_id, conta_id)
    REFERENCES cartao_emitido (id, cartao_id);

COMMENT ON COLUMN conta_convite.cartao_emitido_id IS
    'Nulo = convite de CONTA (§4k). Preenchido = convite de PLASTICO (B-D106). Cartao nunca se divide inteiro.';


-- -----------------------------------------------------------------------------
-- 5. Aceitar um plastico
-- -----------------------------------------------------------------------------
-- Mesmo impasse do aceite de conta, com um passo a mais: alem do vinculo da
-- conta do cartao — que ela nao poderia inserir, porque pol_ca_vincular exige
-- conta propria dos dois lados — ha o vinculo do plastico, cuja tabela nao tem
-- politica de INSERT nenhuma.
--
-- As duas insercoes numa transacao so: um vinculo de conta sem plastico liberado
-- deixaria ela vendo um cartao sem nenhum meio de pagamento, e um plastico
-- liberado sem vinculo de conta deixaria o lancamento dela sem onde morar.
CREATE OR REPLACE FUNCTION app_aceitar_convite_de_plastico(
    p_convite_id  uuid,
    p_ambiente_id uuid
)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_usuario uuid := app_usuario_id();
    v_conta   uuid;
    v_emitido uuid;
BEGIN
    IF v_usuario IS NULL THEN
        RAISE EXCEPTION 'Sem identidade na sessao';
    END IF;

    SELECT cc.conta_id, cc.cartao_emitido_id
      INTO v_conta, v_emitido
      FROM conta_convite cc
     WHERE cc.id = p_convite_id
       AND cc.convidado_id = v_usuario;

    IF v_emitido IS NULL THEN
        RAISE EXCEPTION 'Convite % nao encontrado ou nao e de plastico', p_convite_id;
    END IF;

    -- O ambiente de destino tem de ser dela, e dela como DONA — mesmo motivo do
    -- aceite de conta: aceitar dentro de um ambiente recebido emprestado
    -- espalharia o plastico para o dono daquele ambiente.
    IF NOT EXISTS (
        SELECT 1
          FROM usuario_ambiente ua
          JOIN ambiente a ON a.id = ua.ambiente_id
         WHERE ua.usuario_id = v_usuario
           AND ua.ambiente_id = p_ambiente_id
           AND ua.dono
           AND a.excluido_em IS NULL
    ) THEN
        RAISE EXCEPTION 'Ambiente % nao e seu', p_ambiente_id;
    END IF;

    -- A conta do cartao: consequencia, nao concessao (B-D106). ON CONFLICT
    -- porque ela pode ja ter outro plastico do MESMO cartao — ou ter tido e
    -- devolvido.
    INSERT INTO conta_ambiente (conta_id, ambiente_id, origem)
    VALUES (v_conta, p_ambiente_id, false)
    ON CONFLICT (conta_id, ambiente_id) DO UPDATE SET encerrado_em = NULL;

    INSERT INTO cartao_emitido_ambiente (cartao_emitido_id, ambiente_id)
    VALUES (v_emitido, p_ambiente_id)
    ON CONFLICT (cartao_emitido_id, ambiente_id) DO UPDATE SET encerrado_em = NULL;

    DELETE FROM conta_convite WHERE id = p_convite_id;

    RETURN v_conta;
END;
$$;

COMMENT ON FUNCTION app_aceitar_convite_de_plastico(uuid, uuid) IS
    'Aceite de plastico (B-D106): libera o plastico e vincula a conta do cartao, numa transacao. DEFINER — nem a conta e dela, nem cartao_emitido_ambiente tem politica de escrita.';

GRANT EXECUTE ON FUNCTION app_aceitar_convite_de_plastico(uuid, uuid) TO raspybank_app;


-- -----------------------------------------------------------------------------
-- 6. Revogar um plastico
-- -----------------------------------------------------------------------------
-- Irma de app_revogar_conta_compartilhada, e com o mesmo motivo para ser funcao:
-- o dono precisa encerrar uma linha que esta no ambiente dela, e ele nao ve
-- aquele ambiente (B-D90).
--
-- O passo que so existe aqui: quando NAO SOBRA nenhum plastico liberado daquele
-- cartao, o vinculo da conta tambem se encerra. Sem isso ela ficaria com um
-- cartao na tela sem nenhum meio de pagamento — e com acesso de leitura ao
-- contrato inteiro, que e exatamente o que B-D106 tirou.
CREATE OR REPLACE FUNCTION app_revogar_plastico_compartilhado(
    p_emitido_id uuid,
    p_usuario_id uuid
)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_cartao     uuid;
    v_encerrados integer;
BEGIN
    SELECT ce.cartao_id INTO v_cartao
      FROM cartao_emitido ce WHERE ce.id = p_emitido_id;

    IF v_cartao IS NULL THEN
        RAISE EXCEPTION 'Plastico % nao encontrado', p_emitido_id;
    END IF;

    -- Uma funcao para DOIS significados, no idioma de B-D77: o dono revoga
    -- qualquer um, e qualquer um sai por conta propria. A regra do "se nao sobrar
    -- plastico, o cartao sai da vista" e a mesma nos dois casos, e duplica-la
    -- numa segunda funcao seria a chance de as duas divergirem no dia em que uma
    -- delas mudasse.
    IF v_cartao NOT IN (SELECT app_contas_proprias())
       AND p_usuario_id <> app_usuario_id() THEN
        RAISE EXCEPTION 'Plastico % nao e de um cartao seu', p_emitido_id;
    END IF;

    UPDATE cartao_emitido_ambiente cea
       SET encerrado_em = now()
     WHERE cea.cartao_emitido_id = p_emitido_id
       AND cea.encerrado_em IS NULL
       AND cea.ambiente_id IN (
           SELECT ua.ambiente_id FROM usuario_ambiente ua
            WHERE ua.usuario_id = p_usuario_id AND ua.dono
       );

    GET DIAGNOSTICS v_encerrados = ROW_COUNT;

    -- Sobrou algum plastico deste cartao para esta pessoa? Se nao, o cartao sai
    -- da vista dela.
    UPDATE conta_ambiente ca
       SET encerrado_em = now()
     WHERE ca.conta_id = v_cartao
       AND NOT ca.origem
       AND ca.encerrado_em IS NULL
       AND ca.ambiente_id IN (
           SELECT ua.ambiente_id FROM usuario_ambiente ua
            WHERE ua.usuario_id = p_usuario_id AND ua.dono
       )
       AND NOT EXISTS (
           SELECT 1
             FROM cartao_emitido_ambiente cea
             JOIN cartao_emitido ce ON ce.id = cea.cartao_emitido_id
            WHERE ce.cartao_id = v_cartao
              AND cea.ambiente_id = ca.ambiente_id
              AND cea.encerrado_em IS NULL
       );

    RETURN v_encerrados;
END;
$$;

COMMENT ON FUNCTION app_revogar_plastico_compartilhado(uuid, uuid) IS
    'Revoga um plastico (B-D106) e, se nao sobrar nenhum daquele cartao, tira o cartao da vista dela. Logica, nunca DELETE: as compras dela ficam.';

GRANT EXECUTE ON FUNCTION app_revogar_plastico_compartilhado(uuid, uuid) TO raspybank_app;


-- -----------------------------------------------------------------------------
-- 7. Com quem eu dividi cada plastico
-- -----------------------------------------------------------------------------
-- Mesma forma de app_compartilhamentos_da_conta, e pelos mesmos dois motivos:
-- o dono nao ve o ambiente dela, e o nome de quem foi convidado nao sai por
-- pol_usuario_leitura antes de existir vinculo.
CREATE OR REPLACE FUNCTION app_compartilhamentos_do_plastico(p_emitido_id uuid)
RETURNS TABLE (usuario_id uuid, nome text, email text, pendente boolean)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_cartao uuid;
BEGIN
    SELECT ce.cartao_id INTO v_cartao
      FROM cartao_emitido ce WHERE ce.id = p_emitido_id;

    IF v_cartao IS NULL OR v_cartao NOT IN (SELECT app_contas_proprias()) THEN
        RAISE EXCEPTION 'Plastico % nao e de um cartao seu', p_emitido_id;
    END IF;

    RETURN QUERY
    SELECT u.id, u.nome, u.email, false
      FROM cartao_emitido_ambiente cea
      JOIN usuario_ambiente ua ON ua.ambiente_id = cea.ambiente_id AND ua.dono
      JOIN usuario u           ON u.id = ua.usuario_id
     WHERE cea.cartao_emitido_id = p_emitido_id
       AND cea.encerrado_em IS NULL
     UNION ALL
    SELECT u.id, u.nome, u.email, true
      FROM conta_convite cc
      JOIN usuario u ON u.id = cc.convidado_id
     WHERE cc.cartao_emitido_id = p_emitido_id
     ORDER BY 4, 2;
END;
$$;

COMMENT ON FUNCTION app_compartilhamentos_do_plastico(uuid) IS
    'Com quem o dono dividiu este plastico, aceitos e pendentes. Devolve a PESSOA e nunca o ambiente dela (B-D90).';

GRANT EXECUTE ON FUNCTION app_compartilhamentos_do_plastico(uuid) TO raspybank_app;


-- -----------------------------------------------------------------------------
-- 8. O extrato da fatura passa a RECORTAR POR PLASTICO — B-D110
-- -----------------------------------------------------------------------------
-- A V17 devolvia a fatura inteira para quem enxergasse o cartao. Com o plastico
-- como unidade, isso entregaria as compras dos outros nove.
--
-- O filtro nao e parametro: ele deriva da concessao. Quem tem o cartao nascido
-- num ambiente seu ve tudo — sao as "mini faturas" do e-mail do banco, o caso
-- dele. Quem recebeu plasticos ve as linhas DAQUELES plasticos, e mais nenhuma.
--
-- O pagamento da fatura (conta_id do cartao, sem emitido) aparece so para o
-- dono: e movimento do contrato, e por B-D107 ela nao paga.
CREATE OR REPLACE FUNCTION app_extrato_da_fatura(p_fatura_id uuid)
RETURNS TABLE (
    id                uuid,
    meu               boolean,
    data_caixa        date,
    data_competencia  date,
    tipo              text,
    situacao          text,
    valor             numeric,
    forma_pagamento   text,
    descricao         text,
    categoria_id      uuid,
    categoria_nome    text,
    subcategoria_id   uuid,
    subcategoria_nome text,
    quem_nome         text,
    cartao_emitido_id uuid,
    titular           text,
    tipo_emitido      text,
    final_do_cartao   text,
    parcela_numero    smallint,
    parcela_total     smallint
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_cartao    uuid;
    v_do_dono   boolean;
BEGIN
    SELECT f.cartao_id INTO v_cartao FROM fatura f WHERE f.id = p_fatura_id;

    IF v_cartao IS NULL THEN
        RAISE EXCEPTION 'Fatura % nao encontrada', p_fatura_id;
    END IF;

    IF v_cartao NOT IN (SELECT app_contas_do_usuario()) THEN
        RAISE EXCEPTION 'Cartao da fatura % nao esta no seu ambiente', p_fatura_id;
    END IF;

    v_do_dono := v_cartao IN (SELECT app_contas_nao_emprestadas());

    RETURN QUERY
    SELECT l.id,
           v.meu,
           l.data_caixa,
           l.data_competencia,
           l.tipo,
           l.situacao,
           l.valor,
           l.forma_pagamento,
           CASE WHEN v.meu THEN l.descricao       END,
           CASE WHEN v.meu THEN l.categoria_id    END,
           CASE WHEN v.meu THEN c.nome            END,
           CASE WHEN v.meu THEN l.subcategoria_id END,
           CASE WHEN v.meu THEN s.nome            END,
           u.nome,
           l.cartao_emitido_id,
           ce.nome_titular,
           ce.tipo,
           ce.final_do_cartao,
           l.parcela_numero,
           l.parcela_total
      FROM lancamento l
      CROSS JOIN LATERAL (
          SELECT l.ambiente_id IN (SELECT app_ambientes_do_usuario()) AS meu
      ) v
      LEFT JOIN categoria      c  ON c.id  = l.categoria_id
      LEFT JOIN subcategoria   s  ON s.id  = l.subcategoria_id
      LEFT JOIN usuario        u  ON u.id  = l.criado_por
      LEFT JOIN cartao_emitido ce ON ce.id = l.cartao_emitido_id
     WHERE l.fatura_id = p_fatura_id
       AND (v_do_dono OR l.cartao_emitido_id IN (SELECT app_emitidos_liberados()))
     ORDER BY l.data_competencia DESC, l.criado_em DESC;
END;
$$;

COMMENT ON FUNCTION app_extrato_da_fatura(uuid) IS
    'Extrato da fatura. O dono ve tudo (as mini faturas do contrato); quem recebeu plasticos ve as linhas DAQUELES plasticos (B-D110).';

-- O total do plastico — o unico numero de fatura que ela ve (B-D110), porque o
-- total do contrato e de quem paga (B-D107).
--
-- Soma compras e devolve tambem o previsto: parcela futura ja existe como
-- lancamento desde a compra (F23), e e ela que ocupa o limite do plastico.
CREATE OR REPLACE FUNCTION app_total_do_plastico(
    p_emitido_id uuid,
    p_fatura_id  uuid DEFAULT NULL
)
RETURNS TABLE (realizado numeric, previsto numeric)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_cartao uuid;
BEGIN
    SELECT ce.cartao_id INTO v_cartao
      FROM cartao_emitido ce WHERE ce.id = p_emitido_id;

    IF v_cartao IS NULL THEN
        RAISE EXCEPTION 'Plastico % nao encontrado', p_emitido_id;
    END IF;

    -- O porteiro aceita as duas origens de visibilidade: o dono do contrato e
    -- quem recebeu ESTE plastico. Sem a segunda, a tela dela nao teria numero.
    IF v_cartao NOT IN (SELECT app_contas_nao_emprestadas())
       AND p_emitido_id NOT IN (SELECT app_emitidos_liberados()) THEN
        RAISE EXCEPTION 'Plastico % nao esta no seu alcance', p_emitido_id;
    END IF;

    RETURN QUERY
    SELECT coalesce(SUM(CASE WHEN l.situacao = 'REALIZADO' THEN l.valor ELSE 0 END), 0),
           coalesce(SUM(l.valor), 0)
      FROM lancamento l
     WHERE l.cartao_emitido_id = p_emitido_id
       AND (p_fatura_id IS NULL OR l.fatura_id = p_fatura_id);
END;
$$;

COMMENT ON FUNCTION app_total_do_plastico(uuid, uuid) IS
    'Quanto um plastico consumiu — na fatura informada, ou no total. E o numero da tela de quem recebeu (B-D110).';

GRANT EXECUTE ON FUNCTION app_total_do_plastico(uuid, uuid) TO raspybank_app;


-- -----------------------------------------------------------------------------
-- 8b. O convite pendente diz O QUE esta sendo oferecido
-- -----------------------------------------------------------------------------
-- Sem isto, um convite de plastico chegaria na tela dela como convite de conta —
-- e ela aceitaria pensando ter recebido a conta do cartao inteira, que e
-- exatamente o que B-D106 deixou de existir.
--
-- O nome do plastico vem junto pelo mesmo motivo de o nome da conta vir: antes
-- do aceite, cartao_emitido tambem e invisivel para ela (pol_cartao_emitido_leitura
-- pede uma das duas origens, e nenhuma existe ainda).
--
-- DROP antes do CREATE, e nao CREATE OR REPLACE: acrescentar colunas a um
-- RETURNS TABLE muda o tipo de retorno, e o Postgres recusa a substituicao —
-- "cannot change return type of existing function". Foi assim que a primeira
-- versao desta migracao falhou, e o sintoma foi a suite inteira nao subir.
DROP FUNCTION app_convites_de_conta_pendentes();

CREATE FUNCTION app_convites_de_conta_pendentes()
RETURNS TABLE (
    convite_id      uuid,
    conta_id        uuid,
    conta_nome      text,
    natureza        text,
    dono_id         uuid,
    dono_nome       text,
    dono_email      text,
    emitido_id      uuid,
    emitido_titular text,
    emitido_tipo    text,
    emitido_final   text
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT cc.id,
           c.id,
           c.nome,
           c.natureza,
           u.id,
           u.nome,
           u.email,
           ce.id,
           ce.nome_titular,
           ce.tipo,
           ce.final_do_cartao
      FROM conta_convite cc
      JOIN conta c              ON c.id = cc.conta_id
      JOIN conta_ambiente ca    ON ca.conta_id = cc.conta_id AND ca.origem
      JOIN usuario_ambiente ua  ON ua.ambiente_id = ca.ambiente_id AND ua.dono
      JOIN usuario u            ON u.id = ua.usuario_id
      LEFT JOIN cartao_emitido ce ON ce.id = cc.cartao_emitido_id
     WHERE cc.convidado_id = app_usuario_id()
     ORDER BY cc.criado_em;
$$;

COMMENT ON FUNCTION app_convites_de_conta_pendentes() IS
    'Convites esperando o usuario da sessao. As colunas de emitido dizem se e convite de CONTA (nulas) ou de PLASTICO (B-D106).';


-- -----------------------------------------------------------------------------
-- 9. Fechar fatura volta a ser do dono — B-D108
-- -----------------------------------------------------------------------------
-- A V17 criou pol_fatura_fechar para o membro, separada de pol_fatura_porta pelo
-- valor de fechada_em na linha nova. Com B-D107 (quem paga e o dono) ela deixa de
-- fazer sentido: ela agiria sobre um ciclo que nao paga e cujo total nao ve.
--
-- Some a politica, e sobra pol_fatura_porta. O fechamento AUTOMATICO da leitura
-- continua funcionando para o dono; no ambiente de quem recebeu ele nao roda —
-- quem cuida disso e o servico, que agora so o dispara para o cartao proprio.
DROP POLICY pol_fatura_fechar ON fatura;


-- =============================================================================
-- COMO VERIFICAR MANUALMENTE
-- =============================================================================
-- make psql-app
--
--   SELECT set_config('raspybank.usuario_id', '<uuid-do-dono>', false);
--   SELECT set_config('raspybank.canal', 'WEB', false);
--
--   -- Convidar para UM plastico
--   INSERT INTO conta_convite (conta_id, convidado_id, cartao_emitido_id)
--   VALUES ('<conta-do-cartao>', app_usuario_por_email('luciana@exemplo.com'),
--           '<emitido-virtual>');
--
--   -- Ela aceita, escolhendo o ambiente
--   SELECT set_config('raspybank.usuario_id', '<uuid-dela>', false);
--   SELECT app_aceitar_convite_de_plastico('<convite>', '<ambiente-dela>');
--
--   -- B-D106: ela ve UM plastico, e nao os dez
--   SELECT nome_titular, final_do_cartao FROM cartao_emitido;
--
--   -- B-D110: o extrato traz so as linhas daquele plastico
--   SELECT meu, valor, descricao, quem_nome FROM app_extrato_da_fatura('<fatura>');
--   SELECT * FROM app_total_do_plastico('<emitido-virtual>', '<fatura>');
--
--   -- B-D108: ela nao fecha a fatura
--   UPDATE fatura SET fechada_em = now() WHERE id = '<fatura>';   -- 0 linhas
--
--   -- B-D101: e nao emite plastico novo
--   INSERT INTO cartao_emitido (cartao_id, nome_titular, tipo, final_do_cartao)
--   VALUES ('<cartao>', 'Ela', 'VIRTUAL', '9999');
--   -- ERRO: new row violates row-level security policy
-- =============================================================================
