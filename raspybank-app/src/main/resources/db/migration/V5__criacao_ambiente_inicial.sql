-- =============================================================================
-- V5 — Criação do ambiente inicial
-- =============================================================================
-- Resolve o mesmo impasse que a V4 resolveu para o cadastro de usuário.
--
-- A política pol_ambiente_vinculado tem WITH CHECK exigindo que o ambiente
-- esteja entre os ambientes do usuário da sessão. No momento da criação isso
-- é logicamente impossível: o vínculo só pode existir depois que o ambiente
-- existir, e o ambiente só entra se o vínculo já existisse.
--
-- A saída é a mesma da V4: uma porta única, estreita e explícita. A função
-- executa com privilégios do proprietário, cria ambiente e vínculo na mesma
-- transação, e devolve o identificador. Nada além disso.
-- =============================================================================

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
    -- Garante que o usuário existe. Sem esta checagem, a função aceitaria
    -- qualquer UUID e criaria ambientes órfãos.
    IF NOT EXISTS (SELECT 1 FROM usuario WHERE id = p_usuario_id) THEN
        RAISE EXCEPTION 'Usuario % nao encontrado', p_usuario_id;
    END IF;

    -- Só o PRIMEIRO ambiente é criado automaticamente. Do segundo em diante,
    -- a criação é sempre explícita — e passa pelo fluxo normal, sujeito a RLS.
    IF EXISTS (SELECT 1 FROM usuario_ambiente WHERE usuario_id = p_usuario_id) THEN
        RAISE EXCEPTION 'Usuario % ja possui ambiente', p_usuario_id;
    END IF;

    INSERT INTO ambiente (nome) VALUES (p_nome) RETURNING id INTO v_ambiente_id;

    INSERT INTO usuario_ambiente (usuario_id, ambiente_id)
    VALUES (p_usuario_id, v_ambiente_id);

    RETURN v_ambiente_id;
END;
$$;

GRANT EXECUTE ON FUNCTION auth_criar_ambiente_inicial(uuid, text) TO raspybank_app;

COMMENT ON FUNCTION auth_criar_ambiente_inicial(uuid, text) IS
    'Cria ambiente e vinculo no cadastro. Porta unica: recusa se o usuario ja tiver ambiente.';
