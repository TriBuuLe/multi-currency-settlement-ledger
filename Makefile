# Common tasks. `make help` lists them.

SHELL := /bin/bash
BASE  ?= http://localhost:8080

.DEFAULT_GOAL := help
.PHONY: help build test up down logs demo verify exposure load psql smoke clean

help: ## List the available targets
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

build: ## Compile and package, skipping tests
	mvn -B -ntp -DskipTests package

test: ## Run every test (needs Docker: Postgres runs in Testcontainers)
	mvn -B -ntp verify

up: ## Start Postgres, the ledger, and Prometheus
	docker compose up -d --build
	@echo "waiting for the ledger to report healthy..."
	@until curl -fsS $(BASE)/actuator/health >/dev/null 2>&1; do sleep 2; done
	@echo "ready:"
	@echo "  API docs    $(BASE)/docs"
	@echo "  OpenAPI     $(BASE)/openapi"
	@echo "  metrics     $(BASE)/actuator/prometheus"
	@echo "  Prometheus  http://localhost:9090"

down: ## Stop everything and remove the database volume
	docker compose down -v

logs: ## Tail the ledger's logs
	docker compose logs -f ledger

demo: ## Walk one trade from funding through settlement and reconciliation
	./scripts/demo.sh

verify: ## Ask the ledger to prove its books add up
	@curl -fsS -X POST $(BASE)/api/v1/admin/verify | python3 -m json.tool

exposure: ## Show current settlement risk
	@curl -fsS $(BASE)/api/v1/risk/exposure | python3 -m json.tool

smoke: ## Check the endpoints a running service should expose
	@curl -fsS $(BASE)/actuator/health   | python3 -m json.tool | head -5
	@curl -fsS $(BASE)/openapi           | python3 -c 'import json,sys; print("openapi title:", json.load(sys.stdin)["info"]["title"])'
	@curl -fsS $(BASE)/api/v1/trial-balance | python3 -m json.tool | head -20

load: ## Run the k6 load profile against a running service
	k6 run load/transfers.js

psql: ## Open a psql shell on the running database
	docker compose exec postgres psql -U ledger -d ledger

clean: ## Remove build output
	mvn -B -ntp clean
