#!/bin/bash
# =============================================================================
# RaspyBank — Inicialização do banco
# =============================================================================
# Executado pelo Postgres UMA ÚNICA VEZ, na primeira subida, com o volume vazio.
# Alterou este arquivo? Só terá efeito depois de "make db-reset", que destrói
# o volume e recria o banco do zero.
#
# -----------------------------------------------------------------------------
# POR QUE DOIS USUÁRIOS DE BANCO
# -----------------------------------------------------------------------------
# O Postgres já criou o usuário definido em POSTGRES_USER. Ele é o PROPRIETÁRIO
# do banco: cria tabelas, altera estrutura, executa as migrações do Flyway.
#
# Este script cria um SEGUNDO usuário, usado pela aplicação em execução.
# Ele pode ler e gravar dados, mas não pode alterar a estrutura.
#
# Isso não é preciosismo. É requisito da decisão #04 — multi-tenancy com
# Row Level Security.
#
# Row Level Security é o mecanismo do Postgres que filtra linhas automaticamente,
# no próprio banco, garantindo que um tenant jamais veja dados de outro mesmo
# que a aplicação esqueça o WHERE. Só que ele tem uma característica decisiva:
#
#     RLS É IGNORADO PARA O DONO DA TABELA E PARA SUPERUSUÁRIOS.
#
# Ou seja: se a aplicação conectasse como proprietário, toda a proteção de
# isolamento entre tenants estaria desligada, silenciosamente, e ninguém
# perceberia até vazar dado financeiro de um cliente para outro.
#
# Por isso a separação existe desde a primeira linha de infraestrutura, antes
# mesmo de existir tabela.
# =============================================================================

set -e  # aborta na primeira falha

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL

    -- -------------------------------------------------------------------------
    -- Usuário de execução da aplicação
    -- -------------------------------------------------------------------------
    CREATE ROLE ${APP_DB_USER} WITH
        LOGIN
        PASSWORD '${APP_DB_PASSWORD}'
        NOSUPERUSER      -- nunca superusuário: RLS o ignoraria
        NOCREATEDB
        NOCREATEROLE
        NOINHERIT;

    -- Permite conectar ao banco e enxergar o schema público.
    GRANT CONNECT ON DATABASE ${POSTGRES_DB} TO ${APP_DB_USER};
    GRANT USAGE   ON SCHEMA public          TO ${APP_DB_USER};

    -- -------------------------------------------------------------------------
    -- Privilégios sobre objetos FUTUROS
    -- -------------------------------------------------------------------------
    -- Ainda não existe nenhuma tabela: elas serão criadas pelo Flyway depois.
    -- ALTER DEFAULT PRIVILEGES define o que acontece automaticamente com tudo
    -- que o proprietário criar daqui em diante — sem isso, cada migração futura
    -- exigiria um GRANT manual, e alguém acabaria esquecendo.
    ALTER DEFAULT PRIVILEGES FOR ROLE ${POSTGRES_USER} IN SCHEMA public
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${APP_DB_USER};

    ALTER DEFAULT PRIVILEGES FOR ROLE ${POSTGRES_USER} IN SCHEMA public
        GRANT USAGE, SELECT ON SEQUENCES TO ${APP_DB_USER};

    -- Note o que NÃO foi concedido: CREATE, ALTER e DROP.
    -- A aplicação em execução não tem como alterar a estrutura do banco,
    -- nem por acidente nem por injeção de SQL bem-sucedida.

    -- -------------------------------------------------------------------------
    -- Extensões
    -- -------------------------------------------------------------------------
    -- Busca textual por similaridade. Será usada para localizar lançamentos
    -- pela descrição, tolerando erros de digitação.
    -- Criada agora porque exige privilégio de superusuário — a aplicação
    -- não conseguiria criá-la depois.
    CREATE EXTENSION IF NOT EXISTS pg_trgm;

    -- Remoção de acentos, para busca que ignore diferenças de acentuação.
    CREATE EXTENSION IF NOT EXISTS unaccent;

    -- gen_random_uuid() faz parte do núcleo do Postgres desde a versão 13.
    -- Nenhuma extensão é necessária para gerar identificadores UUID.

EOSQL

echo "[init] Usuário de aplicação '${APP_DB_USER}' criado com privilégios restritos."
