# syntax=docker/dockerfile:1

# Declared before the first FROM so that it can be used in the FROM line of stage 2.
# Override with: docker compose build --build-arg PAYARA_VERSION=7.2026.11
ARG PAYARA_VERSION=7.2026.10

# ---- Stage 1: build the WAR -------------------------------------------------
# The build stage runs once on the build machine's own platform: the WAR/JAR is the same for amd64 and arm64,
# so a multi-platform image build does not compile (and download from Maven Central) twice, and arm64 is not emulated.
FROM --platform=$BUILDPLATFORM azul-zulu:21 AS build
WORKDIR /build

# Maven is installed from the Apache archive, not with apt: the apt package
# depends on a Java runtime and would pull a second (OpenJDK) JDK next to Zulu.
ARG MAVEN_VERSION=3.9.9
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" | tar -xz -C /opt \
 && ln -s /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/local/bin/mvn
# Prints the Maven and Java versions: the Java line should say Zulu
RUN mvn -v

# Maven Central answers "429 Too Many Requests" when many builds download at once: retry up to 10 times, 10 s apart (Maven 3.9 reads MAVEN_ARGS)
ENV MAVEN_ARGS="-Daether.connector.http.retryHandler.count=10 -Daether.connector.http.retryHandler.interval=10000"

# Resolve dependencies and plugins first so that source changes do not re-download them
# (go-offline also fetches the plugins, so the package step below needs nothing from the network)
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests clean package

# ---- Stage 2: run on Payara Server ------------------------------------------
FROM payara/server-full:${PAYARA_VERSION}

# The file name decides the context root: duck-clinic.war -> /duck-clinic
# Files in $DEPLOY_DIR (/opt/payara/deployments) are deployed when the domain starts.
COPY --from=build --chown=payara:payara /build/target/duck-clinic.war ${DEPLOY_DIR}/duck-clinic.war

EXPOSE 8080
