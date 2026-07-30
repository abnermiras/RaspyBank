-- =============================================================================
-- V16 — Compartilhamento de CONTA
-- =============================================================================
-- Implementa o desenho de decisoes.md §4k (B-D85 a B-D97) e api.md §2d.
--
-- O segundo modo de compartilhar, diferente da V15 e complementar a ela:
--
--   Compartilhar AMBIENTE (V15)          Compartilhar CONTA (esta)
--   -------------------------            -------------------------
--   ela trabalha no ambiente do dono     ela trabalha no ambiente DELA
--   categorias do dono                   categorias dela
--   mapa de gastos compartilhado         mapa de gastos SEPARADO
--   saldo e fatura compartilhados        saldo e fatura compartilhados
--
-- A regra que resume o modo inteiro (B-D85): o SALDO atravessa ambientes, a
-- CLASSIFICACAO nao. E a separacao do mapa nao precisa de filtro novo — ele ja
-- recorta por ambiente, e o lancamento dela tem categoria do ambiente dela.
-- Cai da estrutura, nao de uma regra escrita.
--
-- -----------------------------------------------------------------------------
-- OS DOIS ACHADOS QUE MOLDARAM ESTA MIGRACAO
-- -----------------------------------------------------------------------------
-- Achado 1 — do jeito que a V15 ficou, a convidada tomaria a conta.
-- app_contas_proprias() significa "conta ligada a um ambiente de que eu sou
-- dono". Depois do compartilhamento a conta do dono esta ligada ao ambiente
-- DELA, que ela e dona — entao, para o banco, a conta dele passa a ser propria
-- dela. Com isso pol_ca_desvincular a deixaria desvincular a conta do ambiente
-- do dono (a conta desapareceria para ele), e pol_ca_vincular a deixaria
-- repassa-la a um terceiro ambiente dela, que e o que B-D91 proibe em palavras
-- e o banco nao. Nao e um furo da V15: e um significado que so se rompe quando
-- a conta passa a viver em ambiente alheio, e ate aqui isso nao existia.
-- Conserto: a coluna origem (B-D92).
--
-- Achado 2 — revogar nao pode ser DELETE.
-- fk_lancamento_conta (ambiente_id, conta_id) -> conta_ambiente e ON DELETE
-- RESTRICT: a partir do primeiro lancamento dela, apagar o vinculo e recusado
-- pelo banco. E apagar os lancamentos junto seria pior que o erro — aquele
-- dinheiro saiu da conta de verdade, e o saldo do dono passaria a divergir do
-- extrato do banco, que e o sintoma que P1/R1 existem para nao acontecer.
-- Conserto: encerrado_em (B-D93).
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. conta_ambiente aprende duas coisas — B-D92 e B-D93
-- -----------------------------------------------------------------------------
-- origem: o ambiente onde a conta NASCEU. Mesmo idioma de usuario_ambiente.dono
-- (B-D75) — um booleano que responde uma pergunta so, e nao um sistema de
-- papeis. Com ele, "conta propria" volta a significar minha.
ALTER TABLE conta_ambiente ADD COLUMN origem boolean NOT NULL DEFAULT false;

-- encerrado_em: a revogacao do Achado 2. O vinculo encerrado sai da vista de
-- quem recebeu, e os lancamentos dela ficam onde estao.
ALTER TABLE conta_ambiente ADD COLUMN encerrado_em timestamptz;

-- Retroalimentacao: ate esta migracao a conta so vivia em ambientes do proprio
-- dono, e o vinculo mais ANTIGO de cada conta e o do ambiente onde ela nasceu
-- (o segundo, quando existe, e o B-D18 — a propria conta num segundo ambiente
-- seu). O desempate por ambiente_id nao e decorativo: dois vinculos criados na
-- mesma transacao teriam criado_em identico, e sem ele o UPDATE marcaria os
-- dois — quebrando o indice que vem logo abaixo, no meio da migracao.
UPDATE conta_ambiente SET origem = true
 WHERE (conta_id, ambiente_id) IN (
     SELECT DISTINCT ON (conta_id) conta_id, ambiente_id
       FROM conta_ambiente
      ORDER BY conta_id, criado_em, ambiente_id);

-- Exatamente UMA origem por conta, garantido pela estrutura. Indice PARCIAL no
-- formato de ux_ua_um_dono (V15) e de ux_cfp_padrao_saida (V11): um UNIQUE
-- sobre (conta_id, origem) tambem limitaria a UM vinculo nao-origem por conta,
-- que e o oposto do que se quer — a conta compartilhada tem varios.
--
-- Como o indice do dono, ele impede DUAS origens e nao acusa ausencia. Quem
-- garante a presenca e app_criar_conta, a porta estreita, logo adiante.
CREATE UNIQUE INDEX ux_ca_uma_origem ON conta_ambiente (conta_id) WHERE origem;

-- Vinculo ativo por conta: o caminho de app_contas_do_usuario e de toda
-- consulta que atravessa ambientes.
CREATE INDEX ix_ca_ativo ON conta_ambiente (conta_id) WHERE encerrado_em IS NULL;

COMMENT ON COLUMN conta_ambiente.origem IS
    'O ambiente onde a conta nasceu (B-D92). Divide "minha conta" de "conta emprestada"; sem ele, quem recebe a conta pode desvincula-la do ambiente de quem a criou.';
COMMENT ON COLUMN conta_ambiente.encerrado_em IS
    'Revogacao logica (B-D93). DELETE nao serve: fk_lancamento_conta e RESTRICT, e apagar o lancamento dela faria o saldo do dono divergir do extrato do banco.';


-- -----------------------------------------------------------------------------
-- 2. As funcoes de visibilidade aprendem as duas colunas
-- -----------------------------------------------------------------------------
-- O filtro de encerrado_em mora AQUI, num lugar so, e nao espalhado por
-- politica e consulta. E a contrapartida assumida em B-D93: esquecer o filtro
-- em um lugar ressuscitaria o acesso em silencio — o pior tipo de defeito,
-- porque nao da sintoma.
CREATE OR REPLACE FUNCTION app_contas_do_usuario()
RETURNS SETOF uuid
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT ca.conta_id
      FROM conta_ambiente ca
     WHERE ca.ambiente_id IN (SELECT app_ambientes_do_usuario())
       AND ca.encerrado_em IS NULL;
$$;

COMMENT ON FUNCTION app_contas_do_usuario() IS
    'Contas visiveis ao usuario da sessao, via vinculo ATIVO. O filtro de encerrado_em (B-D93) mora aqui, num lugar so.';

-- O conserto do Achado 1: propria = nasceu em ambiente meu.
CREATE OR REPLACE FUNCTION app_contas_proprias()
RETURNS SETOF uuid
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT ca.conta_id
      FROM conta_ambiente ca
     WHERE ca.ambiente_id IN (SELECT app_ambientes_proprios())
       AND ca.origem
       AND ca.encerrado_em IS NULL;
$$;

COMMENT ON FUNCTION app_contas_proprias() IS
    'Contas que NASCERAM em ambiente de que o usuario da sessao e dono (B-D92). Conta emprestada nao entra — era o Achado 1 da V16.';

-- A terceira irma, e ela existe porque os dois modos de compartilhar respondem
-- DIFERENTE para a mesma pergunta — "quem renomeia e encerra esta conta?".
--
--   No compartilhamento de AMBIENTE (B-D76), encerrar conta e DINHEIRO: quem
--   entrou no ambiente do dono ve o mesmo mapa e as mesmas categorias, e tem
--   controle total. Nao e dono de nada, e pode.
--
--   No compartilhamento de CONTA (B-D95), nao pode: a conta aparece na tela de
--   duas pessoas que nao dividem ambiente nenhum, e encerrar tiraria o dinheiro
--   da vista de quem nao pediu.
--
-- A regra que satisfaz as duas nao e "dono" nem "vinculo": e ESTAR NO AMBIENTE
-- ONDE A CONTA NASCEU. Usar app_contas_proprias aqui tiraria do convidado do
-- ambiente um poder que B-D76 lhe deu — foi o que aconteceu na primeira versao
-- desta migracao, e CompartilhamentoApiTest.convidadaOperaODinheiro acusou.
CREATE OR REPLACE FUNCTION app_contas_nao_emprestadas()
RETURNS SETOF uuid
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT ca.conta_id
      FROM conta_ambiente ca
     WHERE ca.ambiente_id IN (SELECT app_ambientes_do_usuario())
       AND ca.origem
       AND ca.encerrado_em IS NULL;
$$;

COMMENT ON FUNCTION app_contas_nao_emprestadas() IS
    'Contas que nasceram em algum ambiente do usuario da sessao — dono ou convidado. Separa o dinheiro da conta (B-D76) da porta dela (B-D91).';

GRANT EXECUTE ON FUNCTION app_contas_nao_emprestadas() TO raspybank_app;


-- -----------------------------------------------------------------------------
-- 3. conta — a porta se separa do dinheiro (B-D95)
-- -----------------------------------------------------------------------------
-- pol_conta_vinculada era FOR ALL: quem enxergava a conta podia renomea-la e
-- encerra-la. Valia enquanto enxergar significava ser dono. Agora a conta
-- aparece na tela de duas pessoas, e encerrar tira o dinheiro da vista de quem
-- nao pediu.
--
-- O predicado e app_contas_nao_emprestadas() e nao app_contas_proprias(), e a
-- diferenca e o que mantem os dois modos coerentes: no compartilhamento de
-- AMBIENTE encerrar conta e dinheiro (B-D76), porque quem encerra esta dentro do
-- ambiente do dono; no de CONTA nao e, porque ela nao esta. "Estar no ambiente
-- onde a conta nasceu" responde as duas de uma vez.
DROP POLICY pol_conta_vinculada ON conta;

CREATE POLICY pol_conta_leitura ON conta
    FOR SELECT
    USING (id IN (SELECT app_contas_do_usuario()));

-- Renomear, encerrar e reabrir. Sem INSERT (nasce por app_criar_conta) e sem
-- DELETE (conta nao se apaga, se encerra — F7).
CREATE POLICY pol_conta_escrita ON conta
    FOR UPDATE
    USING      (id IN (SELECT app_contas_nao_emprestadas()))
    WITH CHECK (id IN (SELECT app_contas_nao_emprestadas()));

-- conta_forma_pagamento pelo mesmo motivo: a lista de formas aceitas e da
-- conta, nao de quem a usa. Ela lanca com as formas que existem; mudar a lista
-- e porta.
DROP POLICY pol_cfp_conta ON conta_forma_pagamento;

CREATE POLICY pol_cfp_leitura ON conta_forma_pagamento
    FOR SELECT
    USING (conta_id IN (SELECT app_contas_do_usuario()));

CREATE POLICY pol_cfp_escrita ON conta_forma_pagamento
    FOR ALL
    USING      (conta_id IN (SELECT app_contas_nao_emprestadas()))
    WITH CHECK (conta_id IN (SELECT app_contas_nao_emprestadas()));


-- -----------------------------------------------------------------------------
-- 4. conta_ambiente — vincular, encerrar, sair
-- -----------------------------------------------------------------------------
-- O INSERT da V15 exigia ser dono dos dois lados (B-D78) e continua exigindo —
-- so que agora "conta propria" e de verdade. A clausula NOT origem e nova: a
-- linha de origem nasce SO pela porta estreita, e sem ela um INSERT comum
-- poderia inventar uma segunda origem para a conta (o indice barraria, mas com
-- erro de indice em vez de recusa de politica).
DROP POLICY pol_ca_vincular ON conta_ambiente;

CREATE POLICY pol_ca_vincular ON conta_ambiente
    FOR INSERT
    WITH CHECK (
        ambiente_id IN (SELECT app_ambientes_proprios())
        AND conta_id IN (SELECT app_contas_proprias())
        AND NOT origem
    );

-- Encerrar o vinculo (B-D93), em DUAS politicas permissivas porque sao duas
-- razoes diferentes para a mesma escrita:
--
--   pol_ca_encerrar  — o dono revoga o acesso de quem recebeu;
--   pol_ca_sair      — quem recebeu devolve a conta, sozinho.
--
-- Nas duas, NOT origem no USING e no WITH CHECK: o vinculo de origem e
-- intocavel por esta via, e o proprio dono nao consegue esconder a conta de si
-- mesmo. E como nenhuma das duas deixa origem mudar de valor, ninguem promove
-- o proprio vinculo emprestado a origem.
CREATE POLICY pol_ca_encerrar ON conta_ambiente
    FOR UPDATE
    USING      (NOT origem AND conta_id IN (SELECT app_contas_proprias()))
    WITH CHECK (NOT origem AND conta_id IN (SELECT app_contas_proprias()));

CREATE POLICY pol_ca_sair ON conta_ambiente
    FOR UPDATE
    USING      (NOT origem AND ambiente_id IN (SELECT app_ambientes_proprios()))
    WITH CHECK (NOT origem AND ambiente_id IN (SELECT app_ambientes_proprios()));


-- -----------------------------------------------------------------------------
-- 5. conta_convite — o aceite de B-D90, e a linha que SOME (B-D94)
-- -----------------------------------------------------------------------------
-- No ambiente o acesso e imediato (B-D80). Na conta ha aceite, porque so ela
-- pode escolher em qual ambiente dela a conta vai aparecer: cair no ambiente
-- ativo mandaria a conta domestica para o PJ sem aviso, e os gastos iriam para
-- o mapa errado ate alguem notar — e notar e dificil, porque nada avisa.
--
-- A tabela guarda o PENDENTE e nada mais. Nao existe coluna de situacao: a
-- verdade sobre quem tem acesso e o vinculo em conta_ambiente, e duas fontes
-- para o mesmo fato e o defeito que o I-01 ja custou uma vez neste projeto.
-- Aceitar cria o vinculo e apaga o convite; recusar so apaga. A trilha de quem
-- convidou e quem recusou fica em registro_auditoria, pelo gatilho.
CREATE TABLE conta_convite (

    id              uuid        PRIMARY KEY DEFAULT uuidv7(),

    conta_id        uuid        NOT NULL REFERENCES conta (id)   ON DELETE RESTRICT,

    convidado_id    uuid        NOT NULL REFERENCES usuario (id) ON DELETE RESTRICT,

    criado_em       timestamptz NOT NULL DEFAULT now(),

    -- Um convite por par. Reconvidar quem ja foi convidado e 409 com frase, e
    -- nao uma segunda linha que a tela mostraria duas vezes.
    CONSTRAINT uq_conta_convite UNIQUE (conta_id, convidado_id)
);

CREATE INDEX ix_conta_convite_convidado ON conta_convite (convidado_id);

COMMENT ON TABLE conta_convite IS
    'Convite PENDENTE de conta (B-D90/B-D94). Some ao ser aceito ou recusado; a verdade do acesso e o vinculo em conta_ambiente.';

-- Nao existe ambiente_origem_id nesta tabela: o ambiente de onde o convite
-- partiu e o de origem da conta, e guardar o mesmo fato duas vezes e o que o
-- I-01 corrigiu.
COMMENT ON COLUMN conta_convite.convidado_id IS
    'Quem recebeu o convite. O ambiente de destino NAO esta aqui: quem escolhe e ela, no aceite (B-D90).';

CREATE TRIGGER tg_auditar_conta_convite
    AFTER INSERT OR UPDATE OR DELETE ON conta_convite
    FOR EACH ROW EXECUTE FUNCTION fn_auditar('ConviteDeConta');

ALTER TABLE conta_convite ENABLE ROW LEVEL SECURITY;

-- Os dois lados leem: o dono para acompanhar o que enviou, ela para saber que
-- existe. Convite que ninguem ve e convite que nao existe.
CREATE POLICY pol_convite_leitura ON conta_convite
    FOR SELECT
    USING (
        convidado_id = app_usuario_id()
        OR conta_id IN (SELECT app_contas_proprias())
    );

-- Convidar e porta, e a porta e do dono da conta (B-D91 + B-D92).
CREATE POLICY pol_convite_criar ON conta_convite
    FOR INSERT
    WITH CHECK (conta_id IN (SELECT app_contas_proprias()));

-- Um DELETE, dois significados: o dono cancela o que enviou, ela recusa o que
-- recebeu. Nenhuma politica de UPDATE, de proposito — convite nao muda de
-- estado, ele nasce e morre (B-D94).
CREATE POLICY pol_convite_apagar ON conta_convite
    FOR DELETE
    USING (
        convidado_id = app_usuario_id()
        OR conta_id IN (SELECT app_contas_proprias())
    );

-- Sem GRANT de tabela: o script de inicializacao define ALTER DEFAULT
-- PRIVILEGES para raspybank_app justamente para que ninguem precise lembrar
-- disto a cada tabela nova. O UPDATE que vem no pacote e inofensivo aqui —
-- conta_convite nao tem politica de UPDATE, e sem politica nao ha linha.


-- -----------------------------------------------------------------------------
-- 6. A porta estreita aprende a origem
-- -----------------------------------------------------------------------------
-- Sem isto toda conta nova nasceria SEM origem, e ux_ca_uma_origem nao acusa
-- ausencia — so duplicidade. O defeito apareceria dias depois, numa conta que
-- ninguem consegue renomear: sem origem, app_contas_proprias nao a devolve e
-- pol_conta_escrita recusa o proprio criador.
--
-- CREATE OR REPLACE preserva o identificador interno (mesmo cuidado da V8).
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

    -- Aqui a conta nasce, e este e o vinculo de ORIGEM (B-D92).
    INSERT INTO conta_ambiente (conta_id, ambiente_id, origem)
    VALUES (v_conta_id, p_ambiente_id, true);

    RETURN v_conta_id;
END;
$$;


-- -----------------------------------------------------------------------------
-- 7. Compartilhar e aceitar
-- -----------------------------------------------------------------------------
-- Convidar NAO ganhou funcao: pol_convite_criar ja diz a regra, e app_criar_conta
-- e app_usuario_por_email (V15) resolvem o resto. Pelo criterio B-D19, funcao
-- nova sem impasse nao se justifica.
--
-- ACEITAR tem impasse, e ele e o mesmo da V10: ela precisa inserir um vinculo
-- entre uma conta que NAO e dela e um ambiente que e — e o WITH CHECK de
-- pol_ca_vincular exige conta propria dos dois lados, exatamente para impedir
-- que alguem capture conta alheia conhecendo o UUID. A diferenca entre captura
-- e aceite nao esta na linha inserida; esta no CONVITE que existe antes dela.
-- Por isso a funcao, e por isso ela le o convite em vez de receber a conta.
CREATE OR REPLACE FUNCTION app_aceitar_convite_de_conta(
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
BEGIN
    IF v_usuario IS NULL THEN
        RAISE EXCEPTION 'Sem identidade na sessao';
    END IF;

    -- O convite e quem autoriza a insercao, e ele tem de ser DELA. Sem esta
    -- clausula a funcao seria "aceite o convite de qualquer um".
    SELECT conta_id INTO v_conta
      FROM conta_convite
     WHERE id = p_convite_id
       AND convidado_id = v_usuario;

    IF v_conta IS NULL THEN
        RAISE EXCEPTION 'Convite % nao encontrado', p_convite_id;
    END IF;

    -- O ambiente de destino tem de ser dela, e dela como DONA: aceitar dentro
    -- de um ambiente que ela so recebeu emprestado (V15) espalharia a conta
    -- para o dono daquele ambiente, que nao participou de nada disto.
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

    -- ON CONFLICT porque revogar nao apaga (B-D93): se ela ja teve esta conta
    -- neste ambiente, a linha existe com encerrado_em preenchido, e aceitar de
    -- novo e reabri-la. Inserir cegamente falharia na chave primaria, e apagar
    -- e recriar perderia o vinculo dos lancamentos antigos dela.
    INSERT INTO conta_ambiente (conta_id, ambiente_id, origem)
    VALUES (v_conta, p_ambiente_id, false)
    ON CONFLICT (conta_id, ambiente_id) DO UPDATE SET encerrado_em = NULL;

    DELETE FROM conta_convite WHERE id = p_convite_id;

    RETURN v_conta;
END;
$$;

COMMENT ON FUNCTION app_aceitar_convite_de_conta(uuid, uuid) IS
    'Aceite de B-D90: cria o vinculo no ambiente ESCOLHIDO por quem recebe e apaga o convite. DEFINER porque a conta nao e dela — o convite e que autoriza.';

GRANT EXECUTE ON FUNCTION app_aceitar_convite_de_conta(uuid, uuid) TO raspybank_app;


-- Revogar tem o mesmo impasse do aceite, e por um motivo que vale ler com
-- calma: o dono precisa encerrar uma linha que ele NAO PODE VER.
--
-- pol_ca_leitura mostra a cada um so o proprio lado do vinculo, e isso e
-- deliberado (B-D90: em qual ambiente ela guardou a conta e organizacao da vida
-- dela). Entao pol_ca_encerrar autoriza a escrita, mas nenhum UPDATE comum
-- chega na linha — nem por JPA, que precisa ler antes, nem por SQL nativo, que
-- precisaria nomear o ambiente dela.
--
-- A alternativa era alargar pol_ca_leitura para "ou a conta e minha", e ela
-- entregaria ao dono os ids dos ambientes dela — pouco em aparencia, e o
-- suficiente para contar quantos sao e correlacionar entre contas. A funcao e
-- mais estreita: recebe a PESSOA, nunca o ambiente, e devolve so quantos
-- vinculos encerrou.
CREATE OR REPLACE FUNCTION app_revogar_conta_compartilhada(
    p_conta_id   uuid,
    p_usuario_id uuid
)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_encerrados integer;
BEGIN
    IF p_conta_id NOT IN (SELECT app_contas_proprias()) THEN
        RAISE EXCEPTION 'Conta % nao e sua', p_conta_id;
    END IF;

    -- NOT origem no filtro: nem por engano o dono encerra o proprio vinculo e
    -- esconde a conta de si mesmo.
    UPDATE conta_ambiente ca
       SET encerrado_em = now()
     WHERE ca.conta_id = p_conta_id
       AND NOT ca.origem
       AND ca.encerrado_em IS NULL
       AND ca.ambiente_id IN (
           SELECT ua.ambiente_id
             FROM usuario_ambiente ua
            WHERE ua.usuario_id = p_usuario_id
              AND ua.dono
       );

    GET DIAGNOSTICS v_encerrados = ROW_COUNT;
    RETURN v_encerrados;
END;
$$;

COMMENT ON FUNCTION app_revogar_conta_compartilhada(uuid, uuid) IS
    'Revogacao logica (B-D93) pelo dono. DEFINER porque a linha a encerrar esta num ambiente que o dono nao pode ver, e nao pode por decisao (B-D90).';

GRANT EXECUTE ON FUNCTION app_revogar_conta_compartilhada(uuid, uuid) TO raspybank_app;


-- -----------------------------------------------------------------------------
-- 8. Quem esta do outro lado — e por que a resposta vem por funcao
-- -----------------------------------------------------------------------------
-- pol_ca_leitura (V15) so mostra vinculos dos ambientes de QUEM PERGUNTA. Por
-- isso o dono nao enxerga a linha do ambiente dela, e ela nao enxerga a do
-- dele: cada um ve so o proprio lado do vinculo. Isso e desejado — o ambiente
-- em que ela guardou a conta e organizacao da vida dela, e B-D90 ja recusou
-- expo-la ao dono quando recusou que ele escolhesse.
--
-- Sobram duas perguntas legitimas que a politica nao responde: "com quem eu
-- dividi esta conta?" (a lista de §2d) e "de quem e esta conta que eu recebi?"
-- (o rotulo na tela dela). As duas funcoes abaixo respondem SO A PESSOA, nunca
-- o ambiente.
--
-- E elas devolvem nome e e-mail em vez de so o id de proposito: a alternativa
-- era alargar pol_usuario_leitura para "quem divide conta comigo", e isso
-- daria acesso a linha cadastral inteira por um caminho novo. Devolver o nome
-- aqui e mais estreito, e o custo e um nome repetido em duas assinaturas.

-- Ela devolve os DOIS estados de §2d numa lista so — quem ja tem a conta e quem
-- ainda nao respondeu — e o pendente e a razao de a funcao existir mesmo para
-- o convite, que e uma tabela que o dono LE por politica.
--
-- O motivo: pol_usuario_leitura (V15) e "eu, e quem divide ambiente comigo", e
-- quem esta sendo convidado, por definicao, ainda nao divide nada. O dono
-- enxerga o convite e nao enxerga o NOME de quem convidou — mostraria um uuid
-- na lista. E o mesmo impasse de app_usuario_por_email pelo avesso.
CREATE OR REPLACE FUNCTION app_compartilhamentos_da_conta(p_conta_id uuid)
RETURNS TABLE (usuario_id uuid, nome text, email text, pendente boolean)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    -- O porteiro. Sem ele, SECURITY DEFINER viraria "liste os conhecidos do
    -- dono de qualquer conta cujo UUID eu adivinhe".
    IF p_conta_id NOT IN (SELECT app_contas_proprias()) THEN
        RAISE EXCEPTION 'Conta % nao e sua', p_conta_id;
    END IF;

    RETURN QUERY
    -- Quem aceitou: o dono do ambiente onde a conta foi guardada. So o dono de
    -- um ambiente aceita nele (app_aceitar_convite_de_conta confere), entao
    -- este JOIN nao perde ninguem.
    SELECT u.id, u.nome, u.email, false
      FROM conta_ambiente ca
      JOIN usuario_ambiente ua ON ua.ambiente_id = ca.ambiente_id AND ua.dono
      JOIN usuario u           ON u.id = ua.usuario_id
     WHERE ca.conta_id = p_conta_id
       AND NOT ca.origem
       AND ca.encerrado_em IS NULL
     UNION ALL
    SELECT u.id, u.nome, u.email, true
      FROM conta_convite cc
      JOIN usuario u ON u.id = cc.convidado_id
     WHERE cc.conta_id = p_conta_id
     ORDER BY 4, 2;
END;
$$;

COMMENT ON FUNCTION app_compartilhamentos_da_conta(uuid) IS
    'Com quem o dono dividiu a conta, aceitos e pendentes. Devolve a PESSOA e nunca o ambiente dela (B-D90).';

-- Os convites que esperam MIM, com a conta que eles oferecem.
--
-- Aqui o impasse e o mais direto de todos: antes do aceite a conta e invisivel
-- para quem foi convidado — pol_conta_leitura pede vinculo, e o vinculo e
-- exatamente o que o aceite vai criar. Sem esta funcao o convite chegaria como
-- "alguem quer dividir algo com voce", que e um convite que ninguem aceita.
--
-- Nao tem porteiro explicito, e nao precisa: a clausula WHERE e o porteiro —
-- ela devolve so as linhas em que o convidado e o usuario da sessao. Nem
-- parametro existe para apontar para outro lugar.
CREATE OR REPLACE FUNCTION app_convites_de_conta_pendentes()
RETURNS TABLE (
    convite_id  uuid,
    conta_id    uuid,
    conta_nome  text,
    natureza    text,
    dono_id     uuid,
    dono_nome   text,
    dono_email  text
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
           u.email
      FROM conta_convite cc
      JOIN conta c              ON c.id = cc.conta_id
      JOIN conta_ambiente ca    ON ca.conta_id = cc.conta_id AND ca.origem
      JOIN usuario_ambiente ua  ON ua.ambiente_id = ca.ambiente_id AND ua.dono
      JOIN usuario u            ON u.id = ua.usuario_id
     WHERE cc.convidado_id = app_usuario_id()
     ORDER BY cc.criado_em;
$$;

COMMENT ON FUNCTION app_convites_de_conta_pendentes() IS
    'Convites de conta esperando o usuario da sessao. DEFINER porque antes do aceite a conta e invisivel para quem foi convidado.';

CREATE OR REPLACE FUNCTION app_dono_da_conta(p_conta_id uuid)
RETURNS TABLE (usuario_id uuid, nome text, email text)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF p_conta_id NOT IN (SELECT app_contas_do_usuario()) THEN
        RAISE EXCEPTION 'Conta % nao esta no seu ambiente', p_conta_id;
    END IF;

    RETURN QUERY
    SELECT u.id, u.nome, u.email
      FROM conta_ambiente ca
      JOIN usuario_ambiente ua ON ua.ambiente_id = ca.ambiente_id AND ua.dono
      JOIN usuario u           ON u.id = ua.usuario_id
     WHERE ca.conta_id = p_conta_id
       AND ca.origem;
END;
$$;

COMMENT ON FUNCTION app_dono_da_conta(uuid) IS
    'Quem abriu a conta — o dono do ambiente de origem. Alimenta o "compartilhada comigo por X" da tela.';

GRANT EXECUTE ON FUNCTION app_compartilhamentos_da_conta(uuid)  TO raspybank_app;
GRANT EXECUTE ON FUNCTION app_convites_de_conta_pendentes()      TO raspybank_app;
GRANT EXECUTE ON FUNCTION app_dono_da_conta(uuid)                TO raspybank_app;


-- -----------------------------------------------------------------------------
-- 9. AS CONSULTAS QUE ATRAVESSAM — B-D87, B-D96, B-D97
-- -----------------------------------------------------------------------------
-- A QUARTA excecao de B-D19, e a primeira em consulta de LEITURA. As tres
-- anteriores (auth_cadastrar_usuario, auth_criar_ambiente_inicial,
-- app_criar_conta / app_criar_ambiente) existiam pelo impasse do dado que
-- nasce invisivel. Esta e outro impasse, e ele e igualmente inevitavel:
--
--   por construcao uma pessoa NAO PODE ver os lancamentos da outra pela
--   politica — e mesmo assim precisa SOMA-LOS.
--
-- Sem isto os dois veem saldos diferentes na mesma conta, e cada um confere o
-- proprio numero contra o mesmo extrato do banco. No caso da fatura (V17) o
-- sintoma e pior: alguem paga menos do que deve e descobre com juros.
--
-- O que impede a excecao de virar porta dos fundos e o PORTEIRO na primeira
-- linha de cada funcao. Sem ele, DEFINER significaria "leia qualquer conta do
-- sistema, basta ter o UUID".
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION app_saldo_da_conta(p_conta_id uuid)
RETURNS TABLE (realizado numeric, previsto numeric)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF p_conta_id NOT IN (SELECT app_contas_do_usuario()) THEN
        RAISE EXCEPTION 'Conta % nao esta no seu ambiente', p_conta_id;
    END IF;

    -- O sinal e aplicado na soma: valor e sempre positivo (F1) e o sentido
    -- mora em tipo. Somar sem o CASE daria o movimento, nao o saldo.
    --
    -- coalesce porque conta sem lancamento nenhum devolveria NULL, e quem
    -- chama teria de tratar nulo como zero — tratamento que se esquece.
    RETURN QUERY
    SELECT coalesce(SUM(CASE WHEN l.situacao = 'REALIZADO'
                             THEN (CASE WHEN l.tipo = 'ENTRADA' THEN l.valor ELSE -l.valor END)
                             ELSE 0 END), 0),
           coalesce(SUM(CASE WHEN l.tipo = 'ENTRADA' THEN l.valor ELSE -l.valor END), 0)
      FROM lancamento l
     WHERE l.conta_id = p_conta_id;
END;
$$;

COMMENT ON FUNCTION app_saldo_da_conta(uuid) IS
    'Saldo que ATRAVESSA ambientes (B-D87): soma todos os lancamentos da conta, inclusive os do ambiente alheio. Porteiro na primeira linha.';

-- O extrato da conta, e a fronteira de privacidade do modo inteiro (B-D97).
--
-- descricao e categoria do lancamento ALHEIO nao saem do banco — nao e filtro
-- de exibicao, e coluna que a funcao nao devolve. A tela nao tem como vazar o
-- que nunca recebeu, e um JSON distraido no controlador nao vira incidente.
--
-- O que a linha alheia mostra (B-D89): valor, data, forma de pagamento e quem.
-- Basta para o saldo bater com o extrato do banco. A descricao fica de fora
-- junto com a categoria pelo mesmo motivo pratico: e texto livre, e e onde as
-- pessoas escrevem o que nao pretendiam dividir — "presente da Luciana" e
-- exatamente o caso.
--
-- As colunas de parcela ja saem daqui, e valem para o cartao (B-D102, V17):
-- as proximas parcelas sao dinheiro do dono preso no limite dele, e faturas de
-- meses que ainda nao chegaram ja nascem com valor comprometido. Estao na
-- assinatura desde agora para a V17 nao precisar derrubar e recriar a funcao.
CREATE OR REPLACE FUNCTION app_extrato_da_conta(
    p_conta_id uuid,
    p_inicio   date,
    p_fim      date
)
RETURNS TABLE (
    id              uuid,
    meu             boolean,
    data_caixa      date,
    tipo            text,
    situacao        text,
    valor           numeric,
    forma_pagamento text,
    descricao       text,
    categoria_id    uuid,
    categoria_nome  text,
    quem_nome       text,
    fatura_id       uuid,
    parcela_numero  smallint,
    parcela_total   smallint
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF p_conta_id NOT IN (SELECT app_contas_do_usuario()) THEN
        RAISE EXCEPTION 'Conta % nao esta no seu ambiente', p_conta_id;
    END IF;

    RETURN QUERY
    SELECT l.id,
           v.meu,
           l.data_caixa,
           l.tipo,
           l.situacao,
           l.valor,
           l.forma_pagamento,
           CASE WHEN v.meu THEN l.descricao    END,
           CASE WHEN v.meu THEN l.categoria_id END,
           CASE WHEN v.meu THEN c.nome         END,
           u.nome,
           l.fatura_id,
           l.parcela_numero,
           l.parcela_total
      FROM lancamento l
      -- LATERAL para calcular "meu" uma vez e usar nos tres CASE. Repetir a
      -- subconsulta em cada um deles seria a mesma pergunta feita quatro vezes,
      -- e a quarta e a que alguem esqueceria de manter igual.
      CROSS JOIN LATERAL (
          SELECT l.ambiente_id IN (SELECT app_ambientes_do_usuario()) AS meu
      ) v
      LEFT JOIN categoria c ON c.id = l.categoria_id
      LEFT JOIN usuario   u ON u.id = l.criado_por
     WHERE l.conta_id = p_conta_id
       AND l.data_caixa BETWEEN p_inicio AND p_fim
     ORDER BY l.data_caixa DESC, l.criado_em DESC;
END;
$$;

COMMENT ON FUNCTION app_extrato_da_conta(uuid, date, date) IS
    'Extrato que ATRAVESSA ambientes (B-D87). O recorte de B-D89 mora AQUI (B-D97): descricao e categoria do lancamento alheio nao saem do banco.';

GRANT EXECUTE ON FUNCTION app_saldo_da_conta(uuid)               TO raspybank_app;
GRANT EXECUTE ON FUNCTION app_extrato_da_conta(uuid, date, date) TO raspybank_app;

-- app_total_da_fatura, a terceira de B-D96, sai na V17 junto com o resto do
-- cartao compartilhado (§4l). Criar aqui uma funcao que ninguem chama seria
-- codigo morto esperando por um chamador.


-- =============================================================================
-- COMO VERIFICAR MANUALMENTE
-- =============================================================================
-- make psql-app
--
--   SELECT set_config('raspybank.usuario_id', '<uuid-do-dono>', false);
--   SELECT set_config('raspybank.canal', 'WEB', false);
--
--   -- Convidar (a conta tem de ser de ORIGEM num ambiente seu)
--   INSERT INTO conta_convite (conta_id, convidado_id)
--   VALUES ('<conta>', app_usuario_por_email('luciana@exemplo.com'));
--
--   -- Trocar para ela e aceitar, escolhendo o ambiente
--   SELECT set_config('raspybank.usuario_id', '<uuid-dela>', false);
--   SELECT app_aceitar_convite_de_conta('<convite>', '<ambiente-dela>');
--   SELECT nome FROM conta;                     -- a conta dele aparece
--   SELECT * FROM app_saldo_da_conta('<conta>'); -- o mesmo saldo dos dois lados
--
--   -- B-D95: ela nao renomeia nem encerra
--   UPDATE conta SET nome = 'minha agora' WHERE id = '<conta>';   -- 0 linhas
--
--   -- Achado 1: ela nao desvincula a conta do ambiente do dono
--   UPDATE conta_ambiente SET encerrado_em = now()
--    WHERE conta_id = '<conta>' AND origem;                       -- 0 linhas
--
--   -- B-D91: ela nao repassa
--   INSERT INTO conta_convite (conta_id, convidado_id)
--   VALUES ('<conta>', '<uuid-de-um-terceiro>');
--   -- ERRO: new row violates row-level security policy
--
--   -- Achado 2: com lancamento dela, o DELETE do vinculo e recusado; o
--   -- encerramento logico passa
--   SELECT set_config('raspybank.usuario_id', '<uuid-do-dono>', false);
--   DELETE FROM conta_ambiente WHERE conta_id = '<conta>' AND NOT origem;
--   -- ERRO: violates foreign key constraint "fk_lancamento_conta"
--   UPDATE conta_ambiente SET encerrado_em = now()
--    WHERE conta_id = '<conta>' AND NOT origem;                   -- 1 linha
-- =============================================================================
