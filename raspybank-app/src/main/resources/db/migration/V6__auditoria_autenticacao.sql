-- =============================================================================
-- V6 — Auditoria de autenticação
-- =============================================================================
-- Terceira e última porta SECURITY DEFINER da fundação.
--
-- A política pol_auditoria_ambiente exige, para registros sem ambiente, que
-- usuario_id seja igual ao usuário da sessão. Mas cadastro e login acontecem
-- ANTES de haver identidade na sessão — é justamente o que eles estabelecem.
--
-- Todas as três portas seguem a mesma regra: escopo mínimo e explícito.
-- Esta só grava eventos de autenticação, sempre sem ambiente, e nunca lê nada.
-- =============================================================================

CREATE OR REPLACE FUNCTION auth_registrar_evento(
    p_usuario_id uuid,
    p_canal text,
    p_operacao text,
    p_detalhe jsonb
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    INSERT INTO registro_auditoria
        (ambiente_id, usuario_id, canal, entidade, operacao, estado_novo)
    VALUES
        (NULL, p_usuario_id, p_canal, 'Autenticacao', p_operacao, p_detalhe);
END;
$$;

GRANT EXECUTE ON FUNCTION auth_registrar_evento(uuid, text, text, jsonb) TO raspybank_app;

COMMENT ON FUNCTION auth_registrar_evento(uuid, text, text, jsonb) IS
    'Registra cadastro e login, que ocorrem antes de haver identidade na sessao.';
