#
# SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
# SPDX-License-Identifier: BUSL-1.1
#

.PHONY: dev dev-down dev-logs dev-help deploy deploy-status deploy-logs rollback backup deploy-check
export VERSION BUILD FROM_VERSION DEPLOY_HOST RESUME_PUSH

dev:
	@bash -c "$$(cat scripts/dev.sh)" "$(CURDIR)/scripts/dev.sh"

dev-down:
	docker compose -f scripts/dev-compose.yml down

dev-logs:
	tail -F .dev/backend.log .dev/frontend.log

dev-help:
	@echo 'make dev       启动数据库、构建后端、启动后端和前端（Ctrl+C 停止前后端）'
	@echo 'make dev-down  停止开发数据库，保留数据卷；前后端请在 make dev 终端按 Ctrl+C'
	@echo 'make dev-logs  查看前后端日志'

deploy:
	@bash -c "$$(cat scripts/deploy.sh)" "$(CURDIR)/scripts/deploy.sh" deploy

deploy-status:
	@bash scripts/deploy.sh status

deploy-logs:
	@bash scripts/deploy.sh logs

rollback:
	@bash scripts/deploy.sh rollback

backup:
	@bash scripts/deploy.sh backup

deploy-check:
	@bash -n scripts/deploy.sh scripts/deploy-remote.sh scripts/smoke-image.sh scripts/push-image.sh deploy/entrypoint.sh
	@python3 -c "import ast; from pathlib import Path; ast.parse(Path('scripts/registry-login.py').read_text())"
	@POSTGRES_PASSWORD=validation-only APP_IMAGE=validation-only DOMAIN=things.zhigongshulian.com docker compose -f deploy/compose.yml config --quiet
