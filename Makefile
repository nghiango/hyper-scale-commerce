.PHONY: up down test verify run clean

up: ## Start local infrastructure (PostgreSQL)
	docker compose up -d

down: ## Stop local infrastructure
	docker compose down

test: ## Run unit and integration tests
	./gradlew test integrationTest

verify: ## Formatting check, static analysis, build, all tests
	./gradlew build

run: ## Run the application locally (human-readable logs)
	SPRING_PROFILES_ACTIVE=local ./gradlew :app:bootRun

clean: ## Remove build outputs
	./gradlew clean
