.PHONY: up down services test verify run clean load-smoke load-baseline load-verify load-spike load-saga load-cache load-stream-resilience chaos-up chaos-down chaos-preflight chaos-clean chaos-smoke

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
