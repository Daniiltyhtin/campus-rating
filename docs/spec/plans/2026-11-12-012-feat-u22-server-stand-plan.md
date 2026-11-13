---
title: U22 Server Stand (local part) - Plan
type: feat
date: 2026-11-12
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: docs/spec/plans/2026-10-31-001-feat-campus-rating-mvp-plan.md
origin: docs/spec/plans/2026-10-31-001-feat-campus-rating-mvp-plan.md
execution: code
---

# U22 Стенд на сервере: локальная часть — Plan

## Goal Capsule

- **Objective:** всё, что нужно для подъёма демо-стенда на сервере команды, лежит в репозитории и проверено: override compose, пример nginx хоста с TLS, шаблон окружения без значений, инструкция и тесты режимов запуска. Само развёртывание, DNS и почтовые записи — вне задачи.
- **Authority:** мастер-план (R47, R48, KTD22, раздел U22) → решения владельца 12.11 → этот план.
- **Open blockers:** нет.

---

## Product Contract

### Summary

Стенд хакатона работает в профиле `demo` за nginx хоста, который терминирует TLS и проксирует на контейнер `web` по `127.0.0.1`. Почта — SMTP Яндекса с паролем приложения; режим подтверждения email задаётся явно. Наружу из стека не публикуется ни один порт.

### Requirements

- R1. Override `docker-compose.server.yml` поверх `docker-compose.yml`: `web` — только `127.0.0.1:${WEB_PORT}` (по умолчанию 8080); у `backend` и `postgres` портов на хосте нет; профиль `demo` (R47). Переменные `EMAIL_VERIFICATION`, `CAMPUS_PUBLIC_URL`, `POSTGRES_PASSWORD` обязательны: без них `docker compose config` завершается ошибкой (синтаксис `${VAR:?…}`). Дополнение 13.11 (проверка безопасности): `CAMPUS_DEMO_RESET=false` по умолчанию в override — данные переживают перезапуск, сброс к сиду только по флагу; у всех сервисов `restart: unless-stopped`.
- R2. За nginx хоста приложение видит исходную схему `https` и IP клиента: внутренний nginx `web` передаёт `X-Forwarded-Proto` от хоста (если заголовка нет — `$scheme`), Spring в серверной конфигурации включает `server.forward-headers-strategy=native`; локальный `docker compose up` без override ведёт себя как сейчас.
- R3. Почта Яндекса через окружение: `SPRING_MAIL_HOST=smtp.yandex.ru`, `SPRING_MAIL_PORT=465`, SSL, `SPRING_MAIL_USERNAME` — адрес ящика, `SPRING_MAIL_PASSWORD` — пароль приложения, отправитель равен адресу ящика (`CAMPUS_MAIL_FROM`). Без SMTP стенд работает с `EMAIL_VERIFICATION=off`; `required` без SMTP не стартует (как сейчас, мастер-R48).
- R4. `deploy/server.env.example` — все переменные R1–R3 с комментариями и без значений секретов; имя не попадает под шаблон `.env*`.
- R5. `deploy/nginx/campus.conf.example` — сервер 80 с редиректом на 443; 443 с сертификатом certbot (`/etc/letsencrypt/live/<домен>/`); `proxy_pass http://127.0.0.1:${WEB_PORT}`; заголовки `Host`, `X-Real-IP`, `X-Forwarded-For`, `X-Forwarded-Proto https`; `client_max_body_size 12m` (как во внутреннем nginx). Дополнение 13.11: `server_tokens off`, HSTS и лимит `POST` входа, регистрации, подтверждения email и смены пароля — 10 в минуту с IP, всплеск 10, сверх — `429`.
- R6. `deploy/README.md`: требования к серверу (Docker, Compose, nginx, certbot), первый запуск, проверка (`/actuator/health` через внутренний адрес, вход демо-аккаунтом по домену), перезапуск без потери данных и сброс к сиду по флагу (R1), обновление версии, проверка отправки письма, необязательная переадресация входящей почты через Cloudflare Email Routing, список DNS-записей (A, SPF, DKIM, DMARC Яндекса) — без конкретных значений.
- R7. README в корне — ссылка на `deploy/README.md`.

### Scope Boundaries

- Не входит: развёртывание на сервере, настройка DNS, выпуск сертификата, профиль `prod` для стенда, резервное копирование.

---

## Planning Contract

**Product Contract preservation:** мастер-план не меняется; решения владельца 12.11 — nginx на хосте с TLS, SMTP Яндекса (пользовательский ящик), переадресация через Cloudflare — по желанию.

### Key Technical Decisions

- KTD1. **Override, а не отдельный compose.** `docker compose -f docker-compose.yml -f docker-compose.server.yml` — сборка и healthcheck общие; override снимает порты `backend`/`postgres` (`ports: !reset []`) и переопределяет `web`.
- KTD2. **Схема из заголовка хоста.** Во внутреннем `frontend/nginx.conf` — `map $http_x_forwarded_proto $forwarded_proto { default $http_x_forwarded_proto; '' $scheme; }`; `X-Forwarded-For` — `$proxy_add_x_forwarded_for` (последний адрес — nginx хоста, мастер-KTD22). Spring: `SERVER_FORWARD_HEADERS_STRATEGY=native` только в override.
- KTD3. **SSL для порта 465** — `SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_ENABLE=true` в override (ровно это требует Яндекс на 465).
- KTD4. **Проверка override без Docker** — node-тест в `tools/test` разбирает YAML override и проверяет порты и обязательные переменные; CI `harness` его уже запускает.

---

## Implementation Units

### U1. Override compose и прокси-заголовки

- **Goal:** серверный режим запуска.
- **Requirements:** R1, R2, R3; KTD1–KTD3.
- **Dependencies:** нет.
- **Files:** `docker-compose.server.yml`, `frontend/nginx.conf`, `tools/test/compose-server.test.mjs`, `frontend/src/test/nginx-config.test.ts` (или node-тест рядом).
- **Test scenarios:**
  - Override: у `backend` и `postgres` нет `ports`; у `web` — единственный порт с адресом `127.0.0.1`.
  - Override: `EMAIL_VERIFICATION`, `CAMPUS_PUBLIC_URL`, `POSTGRES_PASSWORD` заданы через `${…:?…}`; `SPRING_PROFILES_ACTIVE=demo`; `SERVER_FORWARD_HEADERS_STRATEGY=native`.
  - `frontend/nginx.conf` передаёт `X-Forwarded-Proto` из заголовка хоста с запасным `$scheme`.
  - Ручная проверка: `docker compose -f docker-compose.yml -f docker-compose.server.yml config` без переменных падает с понятной ошибкой; с переменными — порты как выше.
- **Verification:** node-тесты `harness` зелёные; e2e базы (без override) зелёные — локальный режим не изменился.

### U2. Режимы запуска

- **Goal:** тест профилей мастер-U22.
- **Requirements:** мастер-R47; KTD22.
- **Dependencies:** нет.
- **Files:** `backend/src/test/java/ru/campusrating/RunModeConfigTest.java`.
- **Test scenarios:**
  - Профиль `prod`: `spring.liquibase.drop-first=false`, контексты без `demo`.
  - Профиль `demo`: `drop-first=true`, контексты включают `demo`; с `campus.demo.reset=false` — `drop-first=false`.
- **Verification:** `RunModeConfigTest` зелёный.

### U3. Шаблоны и инструкция

- **Goal:** `deploy/` и ссылка в README.
- **Requirements:** R4–R7.
- **Dependencies:** U1.
- **Files:** `deploy/server.env.example`, `deploy/nginx/campus.conf.example`, `deploy/README.md`, `README.md`.
- **Test expectation:** none -- документация и шаблоны; проверка — `docker compose ... config` с переменными из `server.env.example`, заполненными пробными значениями, и `nginx -t` на примере (если nginx установлен локально).
- **Verification:** по инструкции можно поднять стенд, не задавая вопросов; секретов в файлах нет.

---

## Verification Contract

- `./backend/mvnw -f backend/pom.xml -Pquality verify`, `node --test tools/test/*.test.mjs`, `node tools/check-agent-docs.mjs`, `npm --prefix frontend run check`, e2e базы — зелёные.

## Definition of Done

- U1–U3 сделаны; CI зелёный; запись в `docs/agent-log.md`.
