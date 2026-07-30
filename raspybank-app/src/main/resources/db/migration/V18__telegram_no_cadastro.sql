-- =============================================================================
-- V18 — O Telegram entra no cadastro
-- =============================================================================
-- Pedido dele em 29/07/2026: "acrescenta o id do telegram no cadastro pra pessoa
-- preencher".
--
-- A COLUNA JA EXISTE desde a V1 (`usuario.telegram_id`), com indice unico
-- parcial (`ux_usuario_telegram`, so para nao-nulos) e SELECT concedido na V8.
-- Ela nasceu esperando o bot, que e a etapa 3 do roteiro. Nada disso muda aqui.
--
-- O que faltava era um CAMINHO DE ESCRITA no momento do cadastro, e ele nao
-- existia por um motivo estrutural, nao por esquecimento:
--
--   no cadastro nao ha identidade na sessao, entao pol_usuario_escrita
--   (id = app_usuario_id()) recusa qualquer UPDATE. Gravar o usuario e depois
--   completar o telegram em outra instrucao NAO funciona — a segunda instrucao
--   e recusada pela politica.
--
-- Por isso o valor tem de entrar na MESMA insercao, e a insercao mora dentro de
-- auth_cadastrar_usuario. A funcao ganha um quarto parametro.
--
-- DROP e nao CREATE OR REPLACE: mudar a lista de argumentos cria uma funcao
-- NOVA em Postgres (sobrecarga), e a de tres argumentos continuaria viva. Duas
-- portas para o cadastro, sendo que uma esquece o telegram em silencio, e o
-- inventario de docs/security-definer.md passaria a ter duas linhas onde deve
-- ter uma.
-- =============================================================================

DROP FUNCTION auth_cadastrar_usuario(text, text, text);

CREATE FUNCTION auth_cadastrar_usuario(
    p_nome        text,
    p_email       text,
    p_senha_hash  text,
    p_telegram_id text DEFAULT NULL
)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_id uuid;
BEGIN
    -- NULLIF em vez de aceitar o que vier: o campo e opcional na tela, e string
    -- vazia gravada aqui viraria um valor que o indice unico parcial considera
    -- REAL — o segundo cadastro sem telegram falharia com erro de duplicidade,
    -- num campo que ninguem preencheu. O indice e `WHERE telegram_id IS NOT
    -- NULL` exatamente para deixar varios sem valor, e nulo e o unico jeito de
    -- ficar fora dele.
    INSERT INTO usuario (nome, email, senha_hash, telegram_id)
    VALUES (p_nome, p_email, p_senha_hash, NULLIF(btrim(p_telegram_id), ''))
    RETURNING id INTO v_id;

    RETURN v_id;
END;
$$;

COMMENT ON FUNCTION auth_cadastrar_usuario(text, text, text, text) IS
    'Unica escrita de senha_hash (V4/V8), agora com telegram_id: no cadastro nao ha identidade na sessao, e um UPDATE posterior seria recusado pela politica.';

GRANT EXECUTE ON FUNCTION auth_cadastrar_usuario(text, text, text, text) TO raspybank_app;


-- =============================================================================
-- COMO VERIFICAR MANUALMENTE
-- =============================================================================
-- make psql-app
--
--   -- Dois cadastros SEM telegram convivem (o indice parcial ignora nulos)
--   SELECT auth_cadastrar_usuario('A', 'a@x.local', 'hash', NULL);
--   SELECT auth_cadastrar_usuario('B', 'b@x.local', 'hash', '');
--   SELECT nome, telegram_id FROM usuario WHERE email LIKE '%@x.local';
--   -- os dois com telegram_id nulo: a string vazia virou nulo
--
--   -- O mesmo telegram duas vezes NAO convive
--   SELECT auth_cadastrar_usuario('C', 'c@x.local', 'hash', '123456');
--   SELECT auth_cadastrar_usuario('D', 'd@x.local', 'hash', '123456');
--   -- ERRO: duplicate key value violates unique constraint "ux_usuario_telegram"
-- =============================================================================
