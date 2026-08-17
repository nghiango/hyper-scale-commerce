FROM postgres:16-alpine

RUN apk add --no-cache \
    python3 \
    py3-pip \
    py3-psycopg2 \
    py3-yaml \
    py3-urllib3 \
    py3-requests \
    curl \
    jq \
    && pip install --no-cache-dir --break-system-packages "patroni[etcd3]>=3.2.0"

COPY entrypoint.sh /entrypoint.sh
COPY post-bootstrap.sh /post-bootstrap.sh
RUN chmod +x /entrypoint.sh /post-bootstrap.sh \
    && mkdir -p /var/lib/postgresql/data \
    && chown -R postgres:postgres /var/lib/postgresql

USER postgres
WORKDIR /var/lib/postgresql

ENTRYPOINT ["/entrypoint.sh"]
