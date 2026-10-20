# Multi-stage build; both base images are multi-arch, so this builds natively on x86 and on ARM
# (e.g. an Oracle Ampere VM) without emulation.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
# dependencies change rarely: resolve them in their own layer so code edits rebuild quickly
# (plain RUN rather than a BuildKit cache mount, so it builds with any Docker builder)
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q -o package -DskipTests \
 && cp target/repomind-*.jar /app.jar

FROM eclipse-temurin:25-jre
RUN groupadd --system repomind && useradd --system --gid repomind --home /app repomind \
 && mkdir -p /app /work && chown repomind:repomind /app /work
WORKDIR /app
COPY --from=build --chown=repomind:repomind /app.jar app.jar
USER repomind
ENV REPOMIND_WORKDIR=/work \
    JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
