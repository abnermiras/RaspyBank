-- =============================================================================
-- V14 — Criar ambiente pela tela
-- =============================================================================
-- Ate aqui, ambiente so nascia de um jeito: automaticamente, no cadastro, pela
-- funcao auth_criar_ambiente_inicial (A12). O seletor da T-03 mostrava a lista
-- e ficava desabilitado com um ambiente so, porque nao havia como criar o
-- segundo — e o Abner esbarrou nisso usando o sistema.
--
-- -----------------------------------------------------------------------------
-- POR QUE ISTO PRECISA DE UMA PORTA ESTREITA
-- -----------------------------------------------------------------------------
-- Mesmo impasse de app_criar_conta na V10, e da propria
-- auth_criar_ambiente_inicial na V5:
--
--   pol_ambiente_vinculado tem WITH CHECK (id IN app_ambientes_do_usuario()).
--   Para um ambiente que esta NASCENDO a resposta e sempre NAO — o vinculo em
--   usuario_ambiente so pode existir depois do ambiente existir, e o ambiente
--   so entra se o vinculo ja existisse.
--
-- Nenhuma ordem de INSERT resolve, porque o WITH CHECK e avaliado na hora do
-- INSERT em ambiente. A saida e a mesma das outras duas: uma porta unica,
-- estreita e explicita, que faz as tres insercoes na mesma transacao e nao faz
-- mais nada.
--
-- EXCECAO ASSUMIDA, e a TERCEIRA do projeto (criterio B-D19): o que a justifica
-- e o IMPASSE COM A POLITICA, nao a camada da operacao. Aqui a identidade esta
-- estabelecida — a funcao inclusive a exige — e mesmo assim a operacao e
-- impossivel sob a politica. E o mesmo formato de B-D19, agora na criacao de
-- ambiente. O inventario em docs/security-definer.md ganha esta linha.
--
-- -----------------------------------------------------------------------------
-- O QUE ELA NAO FAZ, E E DELIBERADO
-- -----------------------------------------------------------------------------
-- Nao apaga, nao renomeia, nao convida ninguem. Porta estreita que cresce deixa
-- de ser estreita, e o valor dela e justamente ser pequena o bastante para
-- caber inteira na cabeca de quem revisa.
-- =============================================================================

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
    -- A identidade vem da SESSAO e nao de parametro, de proposito: com o
    -- usuario como argumento, esta funcao viraria "crie um ambiente para
    -- fulano" e qualquer chamador poderia criar ambiente no nome de outro.
    IF v_usuario IS NULL THEN
        RAISE EXCEPTION 'Sem identidade na sessao';
    END IF;

    IF p_nome IS NULL OR btrim(p_nome) = '' THEN
        RAISE EXCEPTION 'Nome do ambiente e obrigatorio';
    END IF;

    INSERT INTO ambiente (nome) VALUES (btrim(p_nome)) RETURNING id INTO v_ambiente_id;

    INSERT INTO usuario_ambiente (usuario_id, ambiente_id)
    VALUES (v_usuario, v_ambiente_id);

    -- Ambiente novo nasce com as sistemicas e SO com elas (F13/B-D14). Sem
    -- elas, o ambiente nao receberia transferencia, ajuste de saldo nem
    -- pagamento de fatura — nasceria quebrado de um jeito que so apareceria na
    -- primeira operacao.
    PERFORM fn_criar_categorias_sistemicas(v_ambiente_id);

    RETURN v_ambiente_id;
END;
$$;

COMMENT ON FUNCTION app_criar_ambiente(text) IS
    'Porta estreita: ambiente + vinculo + sistemicas na mesma transacao. Terceira excecao de B-D19.';

GRANT EXECUTE ON FUNCTION app_criar_ambiente(text) TO raspybank_app;
