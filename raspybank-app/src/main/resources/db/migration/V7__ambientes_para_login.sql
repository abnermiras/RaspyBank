-- =============================================================================
-- V7 — Leitura de ambientes durante o login
-- =============================================================================
-- Diferente das três anteriores: aquelas eram ESCRITAS antes da autenticação,
-- esta é uma LEITURA antes da autenticação.
--
-- No momento de emitir o token, o servidor precisa saber a qual ambiente a
-- pessoa pertence — mas a política pol_ua_visivel filtra usuario_ambiente
-- pelos ambientes do usuário da sessão, e a sessão ainda não tem identidade.
-- Filtrar por parâmetro não resolve: a política é avaliada antes do WHERE.
--
-- Escopo mínimo: devolve apenas identificadores de ambiente de um usuário
-- específico. Nem nome, nem status, nem os outros membros.
-- =============================================================================

CREATE OR REPLACE FUNCTION auth_ambientes_do_usuario(p_usuario_id uuid)
RETURNS SETOF uuid
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT ua.ambiente_id
      FROM usuario_ambiente ua
      JOIN ambiente a ON a.id = ua.ambiente_id
     WHERE ua.usuario_id = p_usuario_id
       AND a.excluido_em IS NULL
     ORDER BY ua.criado_em;
$$;

GRANT EXECUTE ON FUNCTION auth_ambientes_do_usuario(uuid) TO raspybank_app;

COMMENT ON FUNCTION auth_ambientes_do_usuario(uuid) IS
    'Ambientes de um usuario, para emissao do token. Unica leitura pre-autenticacao.';
