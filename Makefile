.PHONY: up down services test verify run clean load-smoke load-baseline load-verify load-spike

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
