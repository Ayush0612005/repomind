# RepoMind as a single-container Hugging Face Space (free CPU hardware: 2 vCPU, 16 GB RAM, no
# persistent disk). Everything the demo needs is baked in so a cold start takes a minute or two:
# the app, the models, and a database snapshot of the indexed demo repositories with pre-warmed
# answers. The public Space is read-only, so Kafka (ingestion and review jobs) is left out.
#
# Built by deploy/huggingface/prepare.sh, which pins REPOMIND_COMMIT and adds data/repomind.dump.

ARG REPOMIND_COMMIT=main

# ---------------------------------------------------------------------------------------- the app
FROM maven:3.9-eclipse-temurin-25 AS app
ARG REPOMIND_COMMIT
ADD https://github.com/Ayush0612005/repomind/archive/${REPOMIND_COMMIT}.tar.gz /tmp/src.tar.gz
RUN mkdir /src && tar -xzf /tmp/src.tar.gz -C /src --strip-components=1
WORKDIR /src
RUN mvn -B -q package -DskipTests && cp target/repomind-*.jar /app.jar

# ------------------------------------------------------------------------------ models and runtime
FROM ollama/ollama:0.40.4 AS models
# pull at build time so a cold start doesn't download 2 GB; drop GPU libraries the Space can't use
RUN (ollama serve > /dev/null 2>&1 &) && sleep 5 \
 && ollama pull nomic-embed-text && ollama pull qwen2.5-coder:3b \
 && rm -rf /usr/lib/ollama/cuda_* /usr/lib/ollama/rocm*

# ------------------------------------------------------------------------------------- the Space
FROM eclipse-temurin:25-jre

ARG PROMETHEUS=3.5.0
ARG GRAFANA=12.0.2
ARG CADDY=2.10.0

RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl gnupg redis-server \
 && install -d /usr/share/postgresql-common/pgdg \
 && curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc \
 && echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] https://apt.postgresql.org/pub/repos/apt noble-pgdg main" > /etc/apt/sources.list.d/pgdg.list \
 && apt-get update && apt-get install -y --no-install-recommends postgresql-18 postgresql-18-pgvector \
 && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL https://github.com/prometheus/prometheus/releases/download/v${PROMETHEUS}/prometheus-${PROMETHEUS}.linux-amd64.tar.gz \
      | tar -xz -C /opt && mv /opt/prometheus-${PROMETHEUS}.linux-amd64 /opt/prometheus \
 && curl -fsSL https://dl.grafana.com/oss/release/grafana-${GRAFANA}.linux-amd64.tar.gz \
      | tar -xz -C /opt && mv /opt/grafana-v${GRAFANA} /opt/grafana \
 && curl -fsSL https://github.com/caddyserver/caddy/releases/download/v${CADDY}/caddy_${CADDY}_linux_amd64.tar.gz \
      | tar -xz -C /usr/local/bin caddy

COPY --from=models /usr/bin/ollama /usr/bin/ollama
COPY --from=models /usr/lib/ollama /usr/lib/ollama

# Hugging Face runs the container as uid 1000
RUN (userdel -r ubuntu 2>/dev/null || true) && useradd -m -u 1000 user
USER user
ENV HOME=/home/user PATH=/usr/lib/postgresql/18/bin:/opt/prometheus:/opt/grafana/bin:$PATH
WORKDIR /home/user

COPY --chown=user --from=models /root/.ollama /home/user/.ollama
COPY --chown=user --from=app /app.jar /opt/repomind/app.jar
COPY --chown=user . /opt/repomind/

ENV DB_URL=jdbc:postgresql://127.0.0.1:5432/repomind DB_USER=repomind DB_PASSWORD=repomind \
    REDIS_URL=redis://127.0.0.1:6379 OLLAMA_URL=http://127.0.0.1:11434 KAFKA_BOOTSTRAP=127.0.0.1:9092 \
    SPRING_KAFKA_LISTENER_AUTOSTARTUP=false SPRING_KAFKA_ADMIN_AUTOCREATE=false \
    SPRING_AI_MODEL_CHAT=ollama SPRING_AI_OLLAMA_INIT_PULLMODELSTRATEGY=never \
    REPOMIND_OLLAMA_CHAT_MODEL=qwen2.5-coder:3b SPRING_AI_OLLAMA_CHAT_OPTIONS_NUMCTX=6144 \
    REPOMIND_QA_MAXCONTEXTTOKENS=2500 REPOMIND_QA_RETRIEVELIMIT=6 \
    REPOMIND_LIMITS_ENABLED=true REPOMIND_LIMITS_ANONYMOUS_REQUESTSPERMINUTE=20 \
    REPOMIND_LIMITS_ANONYMOUS_DAILYTOKENS=60000 \
    REPOMIND_WORKDIR=/tmp/repomind JAVA_OPTS="-Xms512m -Xmx3g -XX:+ExitOnOutOfMemoryError" \
    OLLAMA_MODELS=/home/user/.ollama/models OLLAMA_KEEP_ALIVE=24h OLLAMA_NUM_PARALLEL=1

EXPOSE 7860
CMD ["bash", "/opt/repomind/start.sh"]
