# =============================================================================
# RaspyBank — Makefile
# =============================================================================
# Existe por um motivo prático: o comando real do Compose é longo demais para
# ser digitado dezenas de vezes por dia, e digitar errado significa subir o
# ambiente errado. Encapsular aqui elimina essa classe de erro.
#
# Uso:  make <alvo>       Ex.: make up
#       make              (sem argumento) lista os alvos disponíveis
#
# ATENÇÃO: Makefile exige TAB de indentação, não espaços.
# Se aparecer "missing separator", é isso.
# =============================================================================

# Combinação de arquivos para desenvolvimento. A ordem importa: o último vence.
COMPOSE_LOCAL = docker compose --env-file .env -f infra/compose.yaml -f infra/compose.local.yaml

# Combinação para o Raspberry Pi. Usada apenas lá, a partir da Fase 9.
COMPOSE_PI    = docker compose --env-file .env -f infra/compose.yaml -f infra/compose.pi.yaml

# Carrega as variáveis do .env se ele existir, para os alvos que precisam delas.
-include .env

.DEFAULT_GOAL := help
.PHONY: help up down restart logs ps psql db-reset tools tools-down check-env

# -----------------------------------------------------------------------------
help:  ## Lista os alvos disponíveis
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

check-env:
	@test -f .env || (echo "ERRO: .env não encontrado. Rode: cp .env.example .env" && exit 1)

# -----------------------------------------------------------------------------
# Ciclo de vida
# -----------------------------------------------------------------------------
up: check-env  ## Sobe o ambiente de desenvolvimento
	$(COMPOSE_LOCAL) up -d
	@echo ""
	@echo "Aguardando o banco ficar saudável..."
	@until [ "$$(docker inspect -f '{{.State.Health.Status}}' raspybank-postgres 2>/dev/null)" = "healthy" ]; do \
		printf "."; sleep 2; \
	done
	@echo ""
	@echo "Banco pronto em 127.0.0.1:$(POSTGRES_PORT)"

down:  ## Para o ambiente, PRESERVANDO os dados
	$(COMPOSE_LOCAL) down

restart: down up  ## Reinicia o ambiente

ps:  ## Mostra o estado dos containers
	$(COMPOSE_LOCAL) ps

logs:  ## Acompanha os logs em tempo real (Ctrl+C para sair)
	$(COMPOSE_LOCAL) logs -f

# -----------------------------------------------------------------------------
# Banco de dados
# -----------------------------------------------------------------------------
psql: check-env  ## Abre o terminal SQL como PROPRIETÁRIO do banco
	docker exec -it raspybank-postgres psql -U $(POSTGRES_USER) -d $(POSTGRES_DB)

psql-app: check-env  ## Abre o terminal SQL como usuário de APLICAÇÃO (útil para testar RLS)
	docker exec -it raspybank-postgres psql -U $(APP_DB_USER) -d $(POSTGRES_DB)

db-reset: check-env  ## DESTRÓI o banco e recria do zero. Perde todos os dados.
	@echo "Isto apagará TODOS os dados do banco local."
	@read -p "Digite 'sim' para confirmar: " ok; [ "$$ok" = "sim" ] || exit 1
	$(COMPOSE_LOCAL) down -v
	$(MAKE) up

db-dump: check-env  ## Gera um dump do banco em backups/
	@mkdir -p backups
	docker exec raspybank-postgres pg_dump -U $(POSTGRES_USER) -d $(POSTGRES_DB) \
		| gzip > backups/raspybank-$$(date +%Y%m%d-%H%M%S).sql.gz
	@echo "Dump gerado em backups/"

# -----------------------------------------------------------------------------
# Ferramentas opcionais
# -----------------------------------------------------------------------------
tools: check-env  ## Sobe o Adminer (cliente web) em 127.0.0.1:8081
	$(COMPOSE_LOCAL) --profile tools up -d
	@echo "Adminer em http://localhost:8081"
	@echo "  Sistema: PostgreSQL | Servidor: postgres | Base: $(POSTGRES_DB)"

tools-down:  ## Derruba o Adminer
	$(COMPOSE_LOCAL) --profile tools stop adminer
