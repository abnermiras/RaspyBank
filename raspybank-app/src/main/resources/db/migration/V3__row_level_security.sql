-- =============================================================================
-- V3 — Row Level Security
-- =============================================================================
-- Esta é a migração mais importante do sistema.
--
-- O RF-M2-06 diz que um usuário não deve, EM NENHUMA CIRCUNSTÂNCIA, ver dados
-- de ambiente ao qual não pertence. O princípio P4 diz que o filtro nunca pode
-- depender de o desenvolvedor lembrar de escrevê-lo.
--
-- Row Level Security é o mecanismo que cumpre isso: o próprio Postgres filtra
-- as linhas, em toda consulta, independentemente do SQL enviado. Um SELECT sem
-- WHERE devolve apenas o que aquele usuário pode ver. Uma injeção de SQL
-- bem-sucedida também.
--
-- -----------------------------------------------------------------------------
-- COMO A APLICAÇÃO INFORMA QUEM ESTÁ CONECTADO
-- -----------------------------------------------------------------------------
-- A conexão com o banco é compartilhada por um pool — não há um usuário de
-- banco por pessoa. A identidade é passada em variáveis de sessão, no início
-- de cada transação:
--
--     SELECT set_config('raspybank.usuario_id',  '<uuid>', true);
--
-- O terceiro parâmetro `true` significa LOCAL: o valor vale só até o fim da
-- transação e desaparece quando a conexão volta para o pool. Sem isso, a
-- próxima requisição herdaria a identidade da anterior — que seria exatamente
-- o vazamento que estamos evitando.
--
-- Usamos set_config() e não SET LOCAL porque set_config aceita parâmetro
-- vinculado. SET LOCAL exigiria concatenar o UUID direto no SQL.
--
-- -----------------------------------------------------------------------------
-- POR QUE ISSO SÓ FUNCIONA COM DOIS USUÁRIOS DE BANCO
-- -----------------------------------------------------------------------------
-- RLS é ignorado para o DONO da tabela e para superusuários. É por isso que o
-- script de inicialização criou raspybank_app separado de raspybank_owner.
--
-- O owner ignora RLS — e precisa mesmo, para que as migrações funcionem.
-- A aplicação conecta como raspybank_app e está sujeita às políticas.
--
-- Se um dia alguém configurar a aplicação para conectar como owner, toda a
-- proteção desta migração é desligada em silêncio. Não haverá erro. Existe um
-- teste automatizado justamente para isso.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- Funções auxiliares
-- -----------------------------------------------------------------------------

-- Lê o usuário da sessão. O segundo parâmetro `true` em current_setting faz a
-- função devolver nulo em vez de erro quando a variável não foi definida.
CREATE OR REPLACE FUNCTION app_usuario_id()
RETURNS uuid
LANGUAGE sql
STABLE
AS $$
    SELECT NULLIF(current_setting('raspybank.usuario_id', true), '')::uuid;
$$;

COMMENT ON FUNCTION app_usuario_id() IS
    'Usuário autenticado na transação corrente. Nulo se não definido — e nulo não casa com nada, então nada é visível.';


-- Devolve os ambientes aos quais o usuário da sessão pertence.
--
-- SECURITY DEFINER é ESSENCIAL aqui, por um motivo específico:
-- as políticas abaixo precisam consultar usuario_ambiente. Mas usuario_ambiente
-- também tem política de RLS. Se a função rodasse com os privilégios de quem
-- chama, avaliar a política exigiria avaliar a política — recursão infinita,
-- e o Postgres aborta a consulta.
--
-- SECURITY DEFINER faz a função executar com os privilégios de quem a CRIOU
-- (o owner), que ignora RLS. A recursão desaparece.
--
-- SET search_path é obrigatório em toda função SECURITY DEFINER. Sem isso,
-- alguém poderia criar um schema no início do caminho de busca com uma tabela
-- chamada usuario_ambiente e fazer a função ler a tabela errada — com
-- privilégios de owner. É uma técnica clássica de escalonamento de privilégio.
CREATE OR REPLACE FUNCTION app_ambientes_do_usuario()
RETURNS SETOF uuid
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT ua.ambiente_id
      FROM usuario_ambiente ua
     WHERE ua.usuario_id = app_usuario_id();
$$;

COMMENT ON FUNCTION app_ambientes_do_usuario() IS
    'Ambientes do usuário da sessão. SECURITY DEFINER para evitar recursão de política.';


-- A aplicação precisa poder executar as duas.
GRANT EXECUTE ON FUNCTION app_usuario_id()            TO raspybank_app;
GRANT EXECUTE ON FUNCTION app_ambientes_do_usuario()  TO raspybank_app;


-- -----------------------------------------------------------------------------
-- usuario — cada um enxerga apenas a si mesmo
-- -----------------------------------------------------------------------------
ALTER TABLE usuario ENABLE ROW LEVEL SECURITY;

CREATE POLICY pol_usuario_proprio ON usuario
    FOR ALL
    -- USING controla quais linhas são VISÍVEIS (SELECT, UPDATE, DELETE)
    USING (id = app_usuario_id())
    -- WITH CHECK controla quais linhas podem ser GRAVADAS (INSERT, UPDATE).
    -- Sem esta cláusula, um usuário poderia alterar o próprio registro
    -- atribuindo-o a outro id.
    WITH CHECK (id = app_usuario_id());


-- -----------------------------------------------------------------------------
-- ambiente — visível apenas para quem está vinculado
-- -----------------------------------------------------------------------------
ALTER TABLE ambiente ENABLE ROW LEVEL SECURITY;

CREATE POLICY pol_ambiente_vinculado ON ambiente
    FOR ALL
    USING      (id IN (SELECT app_ambientes_do_usuario()))
    WITH CHECK (id IN (SELECT app_ambientes_do_usuario()));


-- -----------------------------------------------------------------------------
-- usuario_ambiente — vínculos dos ambientes a que o usuário pertence
-- -----------------------------------------------------------------------------
ALTER TABLE usuario_ambiente ENABLE ROW LEVEL SECURITY;

-- Deliberadamente permite ver os OUTROS membros do mesmo ambiente:
-- quem compartilha finanças precisa saber com quem compartilha.
CREATE POLICY pol_ua_visivel ON usuario_ambiente
    FOR ALL
    USING      (ambiente_id IN (SELECT app_ambientes_do_usuario()))
    WITH CHECK (ambiente_id IN (SELECT app_ambientes_do_usuario()));


-- -----------------------------------------------------------------------------
-- registro_auditoria — restrita ao ambiente
-- -----------------------------------------------------------------------------
ALTER TABLE registro_auditoria ENABLE ROW LEVEL SECURITY;

-- A condição sobre ambiente nulo cobre os registros fora de ambiente
-- (login, cadastro): visíveis apenas para o próprio autor.
CREATE POLICY pol_auditoria_ambiente ON registro_auditoria
    FOR ALL
    USING (
        (ambiente_id IS NOT NULL AND ambiente_id IN (SELECT app_ambientes_do_usuario()))
        OR
        (ambiente_id IS NULL AND usuario_id = app_usuario_id())
    )
    WITH CHECK (
        (ambiente_id IS NOT NULL AND ambiente_id IN (SELECT app_ambientes_do_usuario()))
        OR
        (ambiente_id IS NULL AND usuario_id = app_usuario_id())
    );


-- -----------------------------------------------------------------------------
-- outbox — restrita ao ambiente
-- -----------------------------------------------------------------------------
ALTER TABLE outbox ENABLE ROW LEVEL SECURITY;

CREATE POLICY pol_outbox_ambiente ON outbox
    FOR ALL
    USING (
        ambiente_id IS NULL
        OR ambiente_id IN (SELECT app_ambientes_do_usuario())
    )
    WITH CHECK (
        ambiente_id IS NULL
        OR ambiente_id IN (SELECT app_ambientes_do_usuario())
    );


-- =============================================================================
-- COMO VERIFICAR MANUALMENTE
-- =============================================================================
-- Conecte como aplicação:  make psql-app
--
--   SELECT * FROM usuario;
--   -- 0 linhas: nenhuma identidade definida, nada é visível
--
--   SELECT set_config('raspybank.usuario_id', '<uuid-de-um-usuario>', false);
--   SELECT * FROM usuario;
--   -- 1 linha: apenas ele mesmo
--
-- Compare conectando como proprietário (make psql): tudo aparece, porque o
-- owner ignora RLS. Ver as duas saídas lado a lado deixa claro por que a
-- aplicação jamais pode usar o usuário proprietário.
-- =============================================================================
