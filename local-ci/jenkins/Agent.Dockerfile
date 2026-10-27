FROM docker:27.5.1-cli
ARG TARGETARCH
ARG HELM_VERSION=v3.17.2
RUN apk add --no-cache openjdk21-jdk python3 git bash curl \
    && curl -fsSLo /tmp/helm.tar.gz https://get.helm.sh/helm-${HELM_VERSION}-linux-${TARGETARCH}.tar.gz \
    && curl -fsSLo /tmp/helm.tar.gz.sha256sum https://get.helm.sh/helm-${HELM_VERSION}-linux-${TARGETARCH}.tar.gz.sha256sum \
    && cd /tmp && mv helm.tar.gz helm-${HELM_VERSION}-linux-${TARGETARCH}.tar.gz \
    && sha256sum -c helm.tar.gz.sha256sum \
    && tar -xzf helm-${HELM_VERSION}-linux-${TARGETARCH}.tar.gz && mv linux-${TARGETARCH}/helm /usr/local/bin/helm \
    && rm -rf /tmp/helm* /tmp/linux-*
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk
COPY local-ci/jenkins/start-agent.sh /usr/local/bin/start-agent
WORKDIR /workspace
ENTRYPOINT ["bash", "/usr/local/bin/start-agent"]
