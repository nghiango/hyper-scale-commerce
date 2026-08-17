.PHONY: up down services test verify run clean load-smoke load-baseline load-verify load-spike load-saga load-cache load-stream-resilience chaos-up chaos-down chaos-preflight chaos-clean chaos-smoke ha-kafka-up ha-kafka-down ha-kafka-init ha-kafka-preflight ha-kafka-verify ha-services-up ha-services-down ha-ingress-preflight ha-ingress-test ha-events-test ha-ratelimit-test ha-chaos-smoke ha-chaos-replica ha-chaos-kafka-leader ha-chaos-quorum-loss ha-chaos-postgres-loss ha-load-verify ha-qualification ha-db-up ha-db-down ha-db-preflight ha-db-verify ha-db-connectivity-test ha-db-chaos-smoke ha-db-chaos-primary ha-db-chaos-standby ha-db-chaos-etcd ha-db-split-brain ha-db-backup ha-db-pitr-test ha-db-qualification k8s-cluster-up k8s-cluster-down k8s-cluster-preflight k8s-helm-lint k8s-stateful-verify k8s-stateless-verify k8s-hpa-verify k8s-security-verify k8s-qualification k8s-redis-verify cache-replica-qualification

up: ## Start local infrastructure (PostgreSQL)
	docker compose up -d

down: ## Stop local infrastructure
	docker compose down

services: ## Build service images and start both services in containers
	./gradlew :app:bootJar :order-query:bootJar
	docker compose --profile services up -d --build

test: ## Run unit and integration tests
	./gradlew test integrationTest

verify: ## Formatting check, static analysis, build, all tests
	./gradlew build

run: ## Run the application locally (human-readable logs)
	SPRING_PROFILES_ACTIVE=local ./gradlew :app:bootRun

clean: ## Remove build outputs
	./gradlew clean

load-smoke: ## Run short 30-second load smoke verification with data reconciliation
	bash performance/scripts/run-scenario.sh smoke

load-baseline: ## Run stepped saturation baseline scenario
	bash performance/scripts/run-scenario.sh baseline

load-verify: ## Run 10,000 concurrent VU qualification scenario
	bash performance/scripts/run-scenario.sh qualification-10k

load-spike: ## Run 5x traffic spike and recovery qualification scenario
	bash performance/scripts/run-scenario.sh spike-5x

load-saga: ## Run saga compensation and idempotency qualification scenario
	bash performance/scripts/run-scenario.sh saga-idempotency

load-cache: ## Run high-throughput cached read qualification scenario
	bash performance/scripts/run-scenario.sh cached-throughput

load-stream-resilience: ## Run distributed stream operations and rate limiting qualification
	bash performance/scripts/run-scenario.sh stream-resilience

chaos-up: ## Build and start services with Toxiproxy chaos overlay
	./gradlew :app:bootJar :order-query:bootJar
	docker compose -f compose.yaml -f performance/compose.chaos.yml --profile services --profile chaos up -d --build

chaos-down: ## Stop chaos environment and remove containers
	docker compose -f compose.yaml -f performance/compose.chaos.yml --profile services --profile chaos down

chaos-preflight: ## Run preflight safety and proxy checks
	bash performance/chaos/preflight-chaos.sh

chaos-clean: ## Reset all toxics and re-enable all proxies
	bash performance/chaos/cleanup-chaos.sh

chaos-smoke: ## Run automated chaos smoke test with fault injection and data reconciliation
	bash performance/chaos/run-chaos.sh smoke

ha-kafka-up: ## Start 3-node Kafka KRaft cluster and PostgreSQL
	docker compose -f compose.yaml -f performance/compose.ha.yml up -d postgres kafka-1 kafka-2 kafka-3

ha-kafka-down: ## Stop 3-node Kafka KRaft cluster
	docker compose -f compose.yaml -f performance/compose.ha.yml down

ha-kafka-init: ## Initialize durable Kafka HA topics
	bash performance/scripts/init-kafka-ha-topics.sh

ha-kafka-preflight: ## Run Kafka HA cluster preflight check
	bash performance/scripts/preflight-kafka-ha.sh

ha-kafka-verify: ## Run automated Kafka HA broker failover and ISR recovery test
	bash performance/scripts/verify-kafka-ha.sh

ha-services-up: ## Build and start multi-replica services with HAProxy ingress
	./gradlew :app:bootJar :order-query:bootJar
	docker compose -f compose.yaml -f performance/compose.ha.yml --profile services up -d --build

ha-services-down: ## Stop all multi-replica services and ingress
	docker compose -f compose.yaml -f performance/compose.ha.yml --profile services down

ha-ingress-preflight: ## Run preflight check for HAProxy ingress and replicas
	bash performance/scripts/preflight-ingress.sh

ha-ingress-test: ## Run multi-replica ingress routing, security, and failover tests
	bash performance/scripts/test-multi-replica-ingress.sh

ha-events-test: ## Run multi-replica outbox, consumer rebalance, and ordering qualification
	bash performance/scripts/test-multi-replica-events.sh

ha-ratelimit-test: ## Run topology-wide rate limiting and header spoofing test
	bash performance/scripts/test-rate-limiting.sh

ha-chaos-smoke: ## Run rapid HA chaos smoke test
	bash performance/chaos/run-ha-chaos.sh smoke

ha-chaos-replica: ## Run HA application replica loss chaos scenario
	bash performance/chaos/run-ha-chaos.sh app-replica-loss

ha-chaos-kafka-leader: ## Run active Kafka leader loss chaos scenario
	bash performance/chaos/run-ha-chaos.sh kafka-leader-loss

ha-chaos-quorum-loss: ## Run negative control: Kafka quorum loss
	bash performance/chaos/run-ha-chaos.sh kafka-quorum-loss-control

ha-chaos-postgres-loss: ## Run negative control: PostgreSQL primary loss
	bash performance/chaos/run-ha-chaos.sh postgres-loss-control

ha-load-verify: ## Run HA load qualification scenario with k6
	bash performance/scripts/run-scenario.sh ha-qualification

ha-qualification: ## Run end-to-end HA qualification suite and data reconciliation
	bash performance/scripts/run-ha-qualification.sh

ha-db-up: ## Start 3-node etcd and Patroni PostgreSQL HA cluster
	docker compose -f compose.yaml -f performance/compose.ha.yml -f performance/compose.db-ha.yml up -d --build etcd-1 etcd-2 etcd-3 postgres-1 postgres-2 postgres-3

ha-db-down: ## Stop etcd and Patroni PostgreSQL HA cluster
	docker compose -f compose.yaml -f performance/compose.ha.yml -f performance/compose.db-ha.yml down

ha-db-preflight: ## Run preflight check for Patroni PostgreSQL cluster
	bash performance/scripts/preflight-db-ha.sh

ha-db-verify: ## Run Patroni replication and etcd resilience verification
	bash performance/scripts/verify-db-ha.sh

ha-db-connectivity-test: ## Run multi-host JDBC routing and failover reconnection test
	bash performance/scripts/test-primary-connectivity.sh

ha-db-chaos-smoke: ## Run rapid Patroni primary kill and failover smoke test
	bash performance/chaos/run-db-chaos.sh smoke

ha-db-chaos-primary: ## Run PostgreSQL primary SIGKILL failover scenario
	bash performance/chaos/run-db-chaos.sh primary-kill

ha-db-chaos-standby: ## Run synchronous standby loss chaos scenario
	bash performance/chaos/run-db-chaos.sh sync-standby-loss

ha-db-chaos-etcd: ## Run negative control: etcd DCS quorum loss self-fencing
	bash performance/chaos/run-db-chaos.sh etcd-quorum-loss

ha-db-split-brain: ## Verify exactly-one-primary anti-split-brain invariant
	bash performance/chaos/run-db-chaos.sh split-brain-prevention

ha-db-backup: ## Take full physical basebackup and continuous WAL archive
	bash performance/scripts/backup-db.sh full

ha-db-pitr-test: ## Run Point-in-Time Recovery test with sentinel transactions
	bash performance/scripts/test-db-restore-pitr.sh

ha-db-qualification: ## Run end-to-end Database HA load, failover, DR, and reconciliation qualification
	bash performance/scripts/run-db-ha-qualification.sh

k8s-cluster-up: ## Create multi-node kind Kubernetes cluster (3 control plane, 3 workers)
	bash performance/kubernetes/scripts/cluster-up.sh

k8s-cluster-down: ## Delete multi-node kind Kubernetes cluster
	bash performance/kubernetes/scripts/cluster-down.sh

k8s-cluster-preflight: ## Preflight checks for multi-node Kubernetes cluster
	bash performance/kubernetes/scripts/preflight-cluster.sh

k8s-helm-lint: ## Lint Helm chart templates and values
	helm lint performance/helm/hyperscale-commerce

k8s-stateful-verify: ## Verify Kubernetes StatefulSet templates, PDBs, and volume claims
	bash performance/kubernetes/scripts/verify-k8s-stateful.sh

k8s-stateless-verify: ## Verify Kubernetes Deployment, Ingress, and peer sync templates
	bash performance/kubernetes/scripts/verify-k8s-stateless.sh

k8s-hpa-verify: ## Verify Kubernetes HPA scaling bounds and resource QoS
	bash performance/kubernetes/scripts/test-k8s-rollout-hpa.sh

k8s-security-verify: ## Audit Kubernetes NetworkPolicies, RBAC, and security standards
	bash performance/kubernetes/scripts/verify-k8s-security.sh

k8s-qualification: ## Run end-to-end Kubernetes load, rolling update, HPA, and reconciliation qualification
	bash performance/kubernetes/scripts/run-k8s-qualification.sh

k8s-redis-verify: ## Audit Kubernetes Redis L2 cache StatefulSet, Secret, and NetworkPolicies
	bash performance/kubernetes/scripts/verify-k8s-redis.sh

cache-replica-qualification: ## Run end-to-end multi-level cache & read-replica load qualification
	bash performance/kubernetes/scripts/run-cache-replica-qualification.sh
