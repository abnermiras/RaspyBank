# =============================================================================
# RaspyBank — Imagem da aplicação
# =============================================================================
# Construção em DOIS ESTÁGIOS. O primeiro compila; o segundo só recebe o
# resultado. A imagem final não contém Maven, código-fonte nem o JDK completo:
# fica em torno de 250 MB em vez de mais de 800 MB.
#
# Isso importa concretamente: essa imagem é baixada pelo Raspberry Pi em toda
# implantação, por uma conexão residencial.
#
# Construção multi-arquitetura (a VM é amd64, o Pi é arm64):
#   docker buildx build --platform linux/amd64,linux/arm64 -t raspybank:1.0.0 .
# =============================================================================

# -----------------------------------------------------------------------------
# Estágio 1 — compilação
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
COPY raspybank-app/pom.xml         raspybank-app/

# go-offline baixa tudo o que o projeto precisa. Esta camada só é refeita
# quando algum pom.xml mudar.
RUN mvn -B dependency:go-offline

# Agora sim o código-fonte.
COPY raspybank-shared/src      raspybank-shared/src
COPY raspybank-identidade/src  raspybank-identidade/src
COPY raspybank-ambiente/src    raspybank-ambiente/src
COPY raspybank-auditoria/src   raspybank-auditoria/src
COPY raspybank-app/src         raspybank-app/src

# Os testes rodam aqui de propósito: inclui os testes de arquitetura.
# Se alguém quebrar uma fronteira entre módulos, a imagem não é gerada.
RUN mvn -B clean package

# -----------------------------------------------------------------------------
# Estágio 2 — execução
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
