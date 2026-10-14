#
# SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
# SPDX-License-Identifier: BUSL-1.1
#

ARG BUILD_BASE_IMAGE=m.daocloud.io/docker.io/library/maven:3.9.11-eclipse-temurin-25
ARG RUNTIME_BASE_IMAGE=m.daocloud.io/docker.io/library/eclipse-temurin:25-jre

FROM ${BUILD_BASE_IMAGE} AS build
SHELL ["/bin/bash", "-o", "pipefail", "-c"]
ENV MAVEN_OPTS="-Xmx1024m -XX:ActiveProcessorCount=2" \
    NG_BUILD_MAX_WORKERS=2 \
    YARN_CACHE_FOLDER=/root/.cache/yarn \
    NPM_CONFIG_REGISTRY=https://registry.npmmirror.com
WORKDIR /src
COPY deploy/maven-settings.xml /root/.m2/settings.xml
COPY --from=github-deps / /opt/github-deps/
COPY . .
RUN --mount=type=cache,target=/root/.m2/repository \
    --mount=type=cache,target=/root/.cache/yarn \
    --mount=type=cache,id=thingsboard-github-deps,target=/root/.cache/github-deps,sharing=locked \
    (cd /opt/github-deps && sha256sum --check checksums.sha256) \
    && cp -R /opt/github-deps/archives/. /root/.cache/github-deps/ \
    && printf '\nyarn-offline-mirror "/root/.cache/github-deps"\n' >> ui-ngx/.yarnrc \
    && \
    mvn -B install -pl application -am -DskipTests \
      -Dmaven.gitcommitid.skip=true -Dpkg.gradle.phase=none \
      -Dpkg.skip.deb=true -Dpkg.skip.rpm=true -Dpkg.skip.zip=true \
      -Dskip.ui.production.build=false \
      -Dyarn.build.script=build:prod:docker \
      '-Dyarn.install.extra.args=--frozen-lockfile --verbose --prefer-offline' \
      2>&1 | sed -u -E '/\[INFO\] verbose [0-9.]+ (Copying|Creating directory|Creating symlink|Removing) /d' \
    && mkdir -p /out/extensions \
    && cp application/target/*-boot.jar /out/thingsboard.jar \
    && cp -R application/target/data /out/data \
    && cp -R application/target/extensions/. /out/extensions/ \
    && cp application/src/main/resources/logback.xml /out/logback.xml

FROM ${RUNTIME_BASE_IMAGE}
ARG APP_SCHEMA
ARG APP_SOURCE
LABEL com.zhigong.schema=$APP_SCHEMA org.opencontainers.image.revision=$APP_SOURCE
RUN apt-get update && apt-get install -y --no-install-recommends curl fontconfig fonts-dejavu-core libharfbuzz0b \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 things && useradd --uid 10001 --gid things --home-dir /data things \
    && mkdir /app /data && chown things:things /data
COPY --from=build /out/ /app/
COPY deploy/entrypoint.sh /app/entrypoint.sh
RUN chmod 755 /app/entrypoint.sh
USER 10001:10001
WORKDIR /data
ENV HOME=/data
EXPOSE 8080 1883
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=12 \
    CMD curl --noproxy '*' -fsS http://127.0.0.1:8080/api/noauth/setup/state >/dev/null || exit 1
ENTRYPOINT ["/app/entrypoint.sh"]
CMD ["server"]
