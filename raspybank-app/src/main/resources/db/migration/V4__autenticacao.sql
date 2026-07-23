-- =============================================================================
-- V4 — Autenticação
-- =============================================================================
-- Implementa a estratégia definida na revisão R6 dos requisitos:
--   token de acesso de 15 minutos, renovação rotativa de 30 dias,
--   teto absoluto de 90 dias.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- O problema que esta função resolve
-- -----------------------------------------------------------------------------
-- A migração V3 ativou Row Level Security em `usuario`, com a política
-- "cada um enxerga apenas a si mesmo". Isso cria um impasse no login:
--
--   para descobrir QUEM é a pessoa, é preciso consultar pelo e-mail;
--   mas a política só libera a linha para quem JÁ está identificado.
--
-- Ou seja: ninguém consegue fazer login, porque a consulta que descobre a
-- identidade exige a identidade que ela mesma descobriria.
--
-- A saída errada seria afrouxar a política. A saída certa é abrir UMA porta,
-- estreita e explícita: uma função SECURITY DEFINER que devolve exatamente os
-- três campos necessários para autenticar, e nada além disso.
--
-- Ela executa com privilégios do proprietário, portanto ignora RLS. Por isso
-- devolve apenas id, hash da senha e status — nem nome, nem telegram, nem
-- data de cadastro. Se vazar, vaza o mínimo.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION auth_buscar_credenciais(p_email text)
RETURNS TABLE (id uuid, senha_hash text, status text)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT u.id, u.senha_hash, u.status
      FROM usuario u
     WHERE lower(u.email) = lower(p_email);
$$;

GRANT EXECUTE ON FUNCTION auth_buscar_credenciais(text) TO raspybank_app;

COMMENT ON FUNCTION auth_buscar_credenciais(text) IS
    'Porta unica de leitura pre-autenticacao. Devolve o minimo necessario para validar a senha.';


-- Cadastro tem o mesmo impasse: precisa inserir um usuário sem haver
-- identidade na sessão, e a cláusula WITH CHECK da política recusaria.
CREATE OR REPLACE FUNCTION auth_cadastrar_usuario(
    p_nome text,
    p_email text,
    p_senha_hash text
)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_id uuid;
BEGIN
    INSERT INTO usuario (nome, email, senha_hash)
    VALUES (p_nome, p_email, p_senha_hash)
    RETURNING id INTO v_id;

    RETURN v_id;
END;
$$;

GRANT EXECUTE ON FUNCTION auth_cadastrar_usuario(text, text, text) TO raspybank_app;


-- -----------------------------------------------------------------------------
-- token_renovacao
-- -----------------------------------------------------------------------------
-- O token de acesso dura 15 minutos e não fica guardado em lugar nenhum: é
-- assinado, e a assinatura prova sua validade. O de renovação é diferente —
-- dura 30 dias, então precisa poder ser revogado, e por isso fica registrado.
--
-- ROTAÇÃO: a cada uso, o token é invalidado e um novo é emitido. Isso reduz
-- drasticamente a janela em que um token roubado é útil.
--
-- DETECÇÃO DE REUSO: se um token JÁ USADO for apresentado outra vez, só há
-- duas explicações — ou alguém copiou o token, ou houve falha de rede e o
-- cliente repetiu. Como não há como distinguir com segurança, o sistema
-- assume o pior e revoga a FAMÍLIA inteira, derrubando todas as sessões
-- daquela cadeia. É por isso que existe a coluna familia_id.
-- -----------------------------------------------------------------------------
CREATE TABLE token_renovacao (

    id              uuid        PRIMARY KEY DEFAULT uuidv7(),

    usuario_id      uuid        NOT NULL,

    -- Guardamos o HASH do token, nunca o token.
    -- Mesma lógica da senha: se o banco vazar, os tokens não são utilizáveis.
    token_hash      text        NOT NULL,

    -- Identifica a cadeia de rotações originada de um mesmo login.
    familia_id      uuid        NOT NULL,

    criado_em       timestamptz NOT NULL DEFAULT now(),

    -- 30 dias a partir da emissão deste token.
    expira_em       timestamptz NOT NULL,

    -- Teto absoluto: 90 dias a partir do login original, herdado por toda a
    -- família. Impede que a rotação sucessiva estenda o acesso para sempre —
    -- em algum momento a pessoa precisa digitar a senha de novo.
    teto_em         timestamptz NOT NULL,

    -- Preenchido quando o token é trocado por um novo. Token com esta coluna
    -- preenchida que reaparecer dispara a revogação da família.
    usado_em        timestamptz,

    revogado_em     timestamptz,

    -- Contexto do login, útil na auditoria e para a pessoa reconhecer sessões.
    ip_origem       inet,
    agente_usuario  text,

    CONSTRAINT fk_token_usuario FOREIGN KEY (usuario_id)
        REFERENCES usuario (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX ux_token_renovacao_hash ON token_renovacao (token_hash);

-- Revogar a família inteira precisa ser rápido: é operação de emergência.
CREATE INDEX ix_token_renovacao_familia ON token_renovacao (familia_id);

CREATE INDEX ix_token_renovacao_usuario ON token_renovacao (usuario_id);

COMMENT ON TABLE token_renovacao IS
    'Tokens de renovacao com rotacao e deteccao de reuso. Guarda hash, nunca o token.';

-- -----------------------------------------------------------------------------
-- Por que esta tabela NÃO tem Row Level Security
-- -----------------------------------------------------------------------------
-- Decisão consciente, não esquecimento.
--
-- A consulta acontece antes da autenticação: no momento em que o token de
-- renovação é apresentado, ainda não existe identidade na sessão. Uma política
-- de RLS aqui recriaria exatamente o impasse que a função acima resolve.
--
-- O que torna isso aceitável: a tabela não contém dado financeiro, e localizar
-- uma linha exige conhecer o hash de um token — ou seja, possuir o token. A
-- proteção vem do segredo, não do filtro.
--
-- Ainda assim, toda consulta da aplicação filtra por usuario_id além do hash.
-- Defesa em profundidade.
-- -----------------------------------------------------------------------------
