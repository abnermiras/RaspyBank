# =============================================================================
# RaspyBank — Imagem da aplicação
# =============================================================================
# Construção em TRÊS ESTÁGIOS: um compila a SPA, outro compila o backend e
# embute a SPA nele, e o último só recebe o jar. A imagem final não contém
# Node, Maven nem código-fonte.
#
# Tamanho medido em 27/07/2026: 530 MB, dos quais 406 MB são a própria base
# eclipse-temurin:21-jre-noble e 65 MB são o jar. O comentário original falava
# em 250 MB — número de quando a base era menor, corrigido aqui para não
# induzir a caçar uma regressão que não existe. A SPA inteira pesa 1,6 MB.
#
# Isso importa concretamente: essa imagem é baixada pelo Raspberry Pi em toda
# implantação, por uma conexão residencial.
#
# Construção multi-arquitetura (a VM é amd64, o Pi é arm64):
#   docker buildx build --platform linux/amd64,linux/arm64 -t raspybank:1.0.0 .
# =============================================================================

# -----------------------------------------------------------------------------
# Estágio 1 — frontend
# -----------------------------------------------------------------------------
# A imagem constrói o próprio frontend em vez de copiar um build feito na
# máquina de quem chamou. O motivo é que um `dist` local esquecido é
# indistinguível de um recém-gerado, e a imagem sairia com a tela errada sem
# nenhum sinal. Por isso a saída do build local está no .dockerignore.
#
# A versão do Node é FIXA e igual à da VM. Tag móvel (`node:22`) traria "o que
# for o 22 hoje", que é exatamente o que a regra de dependências deste projeto
# proíbe — ver docs/mapa-telas.md §7.
FROM node:22.22.1-bookworm-slim AS frontend

WORKDIR /build/raspybank-web

# Só o manifesto e o lock primeiro: enquanto eles não mudarem, o Docker
# reaproveita a camada de instalação, que é a demorada.
COPY raspybank-web/package.json raspybank-web/package-lock.json ./

# `npm ci` obedece ao lock e recusa versão fora dele, conferindo o hash de
# integridade de cada pacote. `--ignore-scripts` bloqueia os hooks de
# instalação, que são o vetor de propagação dos worms do npm.
RUN npm ci --ignore-scripts

COPY raspybank-web/ ./

# O outDir configurado no vite.config.js aponta para fora desta pasta, então o
# destino precisa existir com o mesmo formato do repositório.
RUN mkdir -p /build/raspybank-app/src/main/resources && npm run build

# -----------------------------------------------------------------------------
# Estágio 2 — compilação do backend
# -----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Copiamos PRIMEIRO apenas os arquivos de definição do projeto.
# Motivo: o Docker guarda em cache cada camada. Enquanto nenhum pom.xml mudar,
# a etapa de download de dependências é reaproveitada — e ela é, de longe, a
# mais demorada. Copiar o código-fonte junto invalidaria o cache a cada
# alteração de uma única linha.
COPY pom.xml .
COPY raspybank-shared/pom.xml      raspybank-shared/
COPY raspybank-identidade/pom.xml  raspybank-identidade/
COPY raspybank-ambiente/pom.xml    raspybank-ambiente/
COPY raspybank-auditoria/pom.xml   raspybank-auditoria/
COPY raspybank-lancamento/pom.xml  raspybank-lancamento/
COPY raspybank-app/pom.xml         raspybank-app/

# go-offline baixa tudo o que o projeto precisa. Esta camada só é refeita
# quando algum pom.xml mudar.
RUN mvn -B dependency:go-offline

# Agora sim o código-fonte.
COPY raspybank-shared/src      raspybank-shared/src
COPY raspybank-identidade/src  raspybank-identidade/src
COPY raspybank-ambiente/src    raspybank-ambiente/src
COPY raspybank-auditoria/src   raspybank-auditoria/src
COPY raspybank-lancamento/src  raspybank-lancamento/src
COPY raspybank-app/src         raspybank-app/src

# A SPA compilada entra como recurso estático do backend, para o Spring
# servi-la no mesmo :8080 da API. Precisa vir DEPOIS do COPY acima: aquele
# traz a pasta resources inteira e apagaria isto se viesse por último.
COPY --from=frontend /build/raspybank-app/src/main/resources/static \
                     raspybank-app/src/main/resources/static

# Os testes de arquitetura rodam aqui de propósito: se alguém quebrar uma
# fronteira entre módulos, a imagem não é gerada.
#
# Os de integração ficam de fora porque NÃO PODEM rodar aqui: eles sobem um
# Postgres via Testcontainers, que precisa falar com o daemon do Docker pelo
# /var/run/docker.sock — e dentro de um `docker build` esse soquete não
# existe. Isto não é uma folga na cerca: quem roda a suíte inteira é o
# `make build`, e o `make gate` passou a depender dele justamente para que
# nenhuma imagem seja construída sem os testes completos terem passado antes.
# O prefixo "surefire." na segunda propriedade é obrigatório, e sem ele os
# módulos SEM teste nenhum (shared, ambiente, auditoria) quebram a build com
# "No tests matching pattern": o filtro não casa nada lá, e o padrão do plugin
# é tratar isso como erro.
RUN mvn -B clean package \
        -Dtest='!com.raspybank.integracao.**' \
        -Dsurefire.failIfNoSpecifiedTests=false

# -----------------------------------------------------------------------------
# Estágio 3 — execução
# -----------------------------------------------------------------------------
# JRE, não JDK: não há compilação em produção.
FROM eclipse-temurin:21-jre-noble

# Usuário sem privilégio. Container rodando como root é risco desnecessário:
# uma falha na aplicação passa a ser uma falha com poder de root dentro do
# container, e isso encurta bastante o caminho para escapar dele.
RUN groupadd --system raspybank && \
    useradd --system --gid raspybank --home /app raspybank

WORKDIR /app

COPY --from=builder --chown=raspybank:raspybank \
     /build/raspybank-app/target/raspybank-app-*.jar app.jar

USER raspybank

EXPOSE 8080

ENV TZ=America/Sao_Paulo

# Opções da JVM.
#   MaxRAMPercentage — a JVM enxerga o limite de memória do container e usa
#   até 75% dele. Sem isso, ela pode enxergar a RAM da máquina inteira e ser
#   morta pelo kernel no Raspberry Pi.
#   UseSerialGC — coletor de lixo mais simples. Em máquina com poucos núcleos
#   e heap pequeno, gasta menos memória e CPU que o padrão.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC -Djava.security.egd=file:/dev/./urandom"

# Verificação de saúde: o Docker só considera o container pronto quando o
# endpoint do actuator responder.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
