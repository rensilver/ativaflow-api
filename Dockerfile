# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-jammy@sha256:4b1a74fbffbe6b4e7b6f0171a472478282fb463790a51edf10e465d578f22b69 AS build
WORKDIR /workspace

COPY .mvn/wrapper/maven-wrapper.properties .mvn/wrapper/maven-wrapper.properties
COPY --chmod=0755 mvnw ./mvnw
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    ./mvnw -B --no-transfer-progress -Dmaven.test.skip=true dependency:go-offline

COPY src/main/ src/main/
# A suite completa (incluindo Testcontainers) deve passar antes do build da imagem.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    ./mvnw -B --no-transfer-progress -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy@sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269 AS runtime
WORKDIR /app

RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app

# O build produz um unico JAR executavel; o .jar.original nao corresponde ao glob.
# O artefato fica somente para leitura, sem permissao de escrita para o runtime.
COPY --from=build --chmod=0444 /workspace/target/*.jar /app/app.jar

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
USER 10001:10001
EXPOSE 8080
STOPSIGNAL SIGTERM
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
