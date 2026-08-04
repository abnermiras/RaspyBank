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
.PHONY: help up down restart logs ps psql psql-app db-reset db-dump tools tools-down check-env build test arch app gate diag web web-build web-deps web-audit web-test pi-deploy pi-up pi-down pi-logs pi-ps pi-test

# Data de corte das dependências do frontend. REGRA: nada publicado há menos de
# uma semana entra no projeto. O padrão de ataque de cadeia de suprimentos no
# npm é uma versão maliciosa que vive poucas horas até ser retirada; uma trava
# de idade derruba quase a classe inteira sem depender de reconhecer o pacote.
# Ao mexer nas dependências, mova esta data — nunca a remova.
NPM_CORTE = 2026-07-17

# -----------------------------------------------------------------------------
help:  ## Lista os alvos disponíveis
	@grep -hE '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
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

# -----------------------------------------------------------------------------
# Aplicação
# -----------------------------------------------------------------------------
# As variáveis do .env precisam virar variáveis de AMBIENTE para o Spring Boot
# enxergá-las. "set -a" faz toda variável definida a seguir ser exportada
# automaticamente; "set +a" desliga esse comportamento.

build:  ## Compila tudo e roda os testes (inclui os de arquitetura)
	mvn -B clean install

test:  ## Roda apenas os testes
	mvn -B test

arch:  ## Roda somente os testes de fronteira entre módulos
	mvn -B -pl raspybank-app test -Dtest=ArquiteturaTest

app: check-env  ## Sobe o backend na VM, com recarga rápida
	set -a; . ./.env; set +a; \
	mvn -B -pl raspybank-app spring-boot:run

# -----------------------------------------------------------------------------
# Portão — antes de dar uma feature por pronta
# -----------------------------------------------------------------------------
# O container recebe o .env INTEIRO via --env-file: variável nova no .env passa
# a valer no gate automaticamente, sem precisar lembrar de acrescentar um -e
# aqui (foi exatamente assim que JWT_SEGREDO ficou de fora e o gate quebrou).
# O único -e explícito é DB_HOST, que dentro da rede do Compose é o nome do
# serviço, não localhost — e -e vence o --env-file em caso de conflito.
#
# Depende de "build" porque a imagem NÃO roda os testes de integração — dentro
# de um `docker build` não há daemon do Docker para o Testcontainers usar. O
# gate só é honesto se a suíte inteira passar antes, aqui fora.
gate: check-env build  ## Constrói a imagem real e sobe. Se passar aqui, passa no Pi.
	docker build -t raspybank:local .
	docker run --rm -it \
		--network raspybank_raspybank \
		--env-file .env \
		-e DB_HOST=postgres \
		-p 127.0.0.1:8080:8080 \
		raspybank:local

diag:  ## Consulta o endpoint de diagnóstico
	@curl -s http://localhost:8080/api/diagnostico | python3 -m json.tool

# -----------------------------------------------------------------------------
# Frontend
# -----------------------------------------------------------------------------
# Nenhum alvo aqui usa "npm install" solto, e isso é deliberado:
#   - "npm ci" obedece ao package-lock.json e recusa qualquer versão fora dele,
#     conferindo o hash de integridade de cada pacote. "npm install" pode
#     resolver versão nova sem avisar.
#   - "--ignore-scripts" bloqueia os hooks preinstall/install/postinstall, que
#     são o vetor de propagação do worm Shai-Hulud. Custo verificado em
#     27/07/2026: zero — nenhum dos 54 pacotes da árvore pede script.
# O build do frontend NÃO está no "mvn install" de propósito: o plugin que faria
# isso baixa um binário do Node da internet a cada build, criando exatamente a
# raiz de confiança nova que a regra do NPM_CORTE existe para evitar.

web: ## Sobe o frontend em modo desenvolvimento (proxy para o backend em :8080)
	cd raspybank-web && npm run dev

web-build: ## Gera o build de produção do frontend
	cd raspybank-web && npm run build

web-deps: ## Instala as dependências do frontend a partir do lock, sem rodar scripts
	cd raspybank-web && npm ci --ignore-scripts

web-audit: ## Mostra as falhas conhecidas nas dependências do frontend
	@cd raspybank-web && npm audit || true

# Sem framework de teste, e isso é decisão: o Node já roda ESM e já traz assert,
# e os dublês de localStorage e fetch cabem em vinte linhas. Trazer vitest ou
# jest custaria dezenas de pacotes novos, e a regra do NPM_CORTE existe para que
# cada pacote seja uma decisão — um defeito não justifica uma árvore.
web-test: ## Roda os testes do frontend (Node puro, sem dependência nova)
	@for teste in raspybank-web/testes/*.mjs; do \
		node "$$teste" || exit 1; \
	done

# -----------------------------------------------------------------------------
# Raspberry Pi — producao
# -----------------------------------------------------------------------------
# Os alvos acima usam COMPOSE_LOCAL. Rodar "make up" no Pi subiria o perfil de
# DESENVOLVIMENTO — porta do banco publicada, log_statement=all, sem limite de
# memoria. Por isso o Pi tem alvos proprios, com prefixo "pi-".
#
# Nada aqui roda na VM de desenvolvimento: o compose.pi.yaml aponta o volume
# para um diretorio do host que so existe no Pi.

pi-deploy: check-env  ## No Pi: puxa do git, faz dump, reconstroi a imagem e sobe
	@test -z "$$(git status --porcelain)" || { \
		echo "ERRO: ha alteracoes nao commitadas neste Pi."; \
		echo "      Commite e empurre antes de puxar, ou o pull vai embolar."; \
		git status --short; \
		exit 1; \
	}
	git pull --ff-only
	@echo ""
	@echo ">>> Dump antes de trocar a imagem. Se algo der errado, ele e a volta."
	$(MAKE) db-dump
	@echo ""
	docker build -t raspybank:local .
	$(COMPOSE_PI) up -d
	@echo ""
	@echo "Aguardando o backend ficar saudavel (leva cerca de um minuto)..."
	@until [ "$$(docker inspect -f '{{.State.Health.Status}}' raspybank-app 2>/dev/null)" = "healthy" ]; do \
		printf "."; sleep 3; \
	done
	@echo ""
	@echo "No ar em http://raspybank.piratanet.com.br"

pi-up: check-env  ## No Pi: sobe com o perfil de producao
	$(COMPOSE_PI) up -d

pi-down:  ## No Pi: para, PRESERVANDO os dados
	$(COMPOSE_PI) down

pi-logs:  ## No Pi: acompanha os logs (Ctrl+C para sair)
	$(COMPOSE_PI) logs -f

pi-ps:  ## No Pi: estado dos containers de producao
	$(COMPOSE_PI) ps

# O host do Pi nao tem node, e isso e correto: o Dockerfile compila tudo dentro
# da imagem. Este alvo roda os mesmos testes de "web-test" num container.
pi-test:  ## No Pi: roda os testes do frontend sem node instalado no host
	@for teste in raspybank-web/testes/*.mjs; do \
		echo "--- $$teste"; \
		docker run --rm -v "$$PWD/raspybank-web:/w" -w /w \
			node:22.22.1-bookworm-slim node "$${teste#raspybank-web/}" || exit 1; \
	done
