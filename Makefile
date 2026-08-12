SHELL := /bin/bash

# This machine ships a JRE-only JDK 21 (java runs, javac does not exist), so JAVA_HOME is
# pinned to a JDK that can actually compile. Without it Maven reports "release 17 not
# supported", which sends you looking at the compiler plugin instead of at the toolchain.
JAVA_HOME ?= $(shell ls -d /usr/lib/jvm/java-17-openjdk-amd64 2>/dev/null || echo $$JAVA_HOME)
MVN := JAVA_HOME=$(JAVA_HOME) ./mvnw -B

.DEFAULT_GOAL := help
.PHONY: help build test up down demo bench logs clean

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

build: ## Compile and package all services (skips tests)
	$(MVN) package -DskipTests

test: ## Unit + Testcontainers integration tests
	$(MVN) test

test-fast: ## Only the tests that need no Docker daemon
	$(MVN) -Pno-docker test

up: build ## Build jars, start the whole stack, wait until healthy
	docker compose up -d --build
	@echo "Waiting for services to report healthy..."
	@for i in $$(seq 1 40); do \
		if ! docker compose ps --format '{{.Health}}' | grep -q starting; then break; fi; \
		sleep 5; \
	done
	@docker compose ps --format 'table {{.Service}}\t{{.State}}\t{{.Health}}'

demo: ## Run the end-to-end demonstration
	./demo.sh

bench: ## Regenerate results/results.md
	./bench.sh

logs: ## Tail application logs
	docker compose logs -f orders-service inventory-service gateway-service

down: ## Stop everything and remove volumes
	docker compose down -v

clean: down ## Also remove build output
	$(MVN) clean
