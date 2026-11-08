# Онбординг разработчика

Одна страница, чтобы в первый день поднять проект, взять юнит и открыть PR.

## Что поставить

| Что | Версия | Зачем |
|---|---|---|
| Git | любая свежая | клон и ветки |
| Docker с Compose v2 | Docker Desktop или Engine | `docker compose up` поднимает весь стек |
| JDK | 25 | backend (Maven Wrapper качает Maven сам) |
| unzip (Linux/WSL) | из пакетного менеджера ОС | Maven Wrapper проверяет SHA-256 ZIP-дистрибутива |
| Node.js | ≥ 22.22 (`.nvmrc`) | frontend, e2e, служебные скрипты `tools/` |
| Агент | любая CLI с адаптером в `docs/agent-process.md` (сейчас Claude Code или Codex) | работа по скиллам репозитория |

На Linux/WSL для локального `./backend/mvnw` нужен `unzip` (например, `sudo apt-get install unzip` в Ubuntu). Без него Wrapper переключается на `.tar.gz`, а зафиксированный SHA-256 рассчитан для `.zip`: запуск завершится ошибкой `Failed to validate Maven distribution SHA-256`. В Dockerfile `unzip` устанавливается автоматически.

Windows:

- Весь стек разработки — внутри WSL2: репозиторий клонировать в WSL (`~/…`), а не на `C:\`; JDK, Node и агент ставятся туда же. Docker Desktop — с бэкендом WSL2. Так быстрее работают тома и сборка, а нативные бинари `node_modules` совпадают с Linux-образами.
- Концы строк держит `.gitattributes`; `core.autocrlf` не включать.
- Скрипты `tools/` — на Node, без bash, поэтому работают и вне WSL.

## Первый запуск

```bash
git clone <url репозитория> && cd campus-rating
node tools/check-agent-docs.mjs        # обвязка агентов в порядке
docker compose up
```

`.env` не нужен; Docker сам собирает образы. После готовности сервисов приложение открывается на `http://localhost:8080` со статичной заглушкой входа. Демо-аккаунты появятся в мастер-U5, синтетический сид — в мастер-U4.

Стенд запускается в режиме `demo`: база пересоздаётся при каждом старте backend, подтверждение email явно выключено (`EMAIL_VERIFICATION=off`). SMTP и Mailpit не нужны. Том загрузок сохраняется; его очистка в `demo` появится в мастер-U7.

Порты привязаны только к `127.0.0.1`: web — `8080`, backend — `8081`, PostgreSQL — `5433`. Для разработки frontend выполните `npm --prefix frontend ci` и `npm --prefix frontend run dev`: Vite проксирует `/api` на `http://localhost:8081`. Команды отдельных частей — в `AGENTS.md` и Verification Contract мастер-плана.

Для запуска в фоне с ожиданием готовности: `docker compose up -d --build --wait`; состояние — `docker compose ps`, логи — `docker compose logs`. Остановка `docker compose down` сохраняет тома, `docker compose down -v` удаляет их вместе с БД и загрузками. Сохранённый том не защищает данные от пересоздания схемы при следующем старте backend в `demo`.

## Что прочитать

1. `AGENTS.md` — правила для людей и агентов: цикл юнита, код, коммиты и ветки.
2. `REVIEWING.md` — как ревьюим и что считается доказательством.
3. Мастер-план `docs/spec/plans/2026-10-31-001-feat-campus-rating-mvp-plan.md`: Goal Capsule, раздел «Sequencing and Team Lanes» (кто что делает и швы между дорожками), затем свой юнит и его R/KTD/AE. Карта разделов: `rg -n '^#{1,3} ' <план>`.
4. `docs/spec/glossary.md` — термины и имена в коде.
5. Для экранов — `docs/spec/ux/screen-map.md` и каркасы в `docs/spec/ux/wireframes/`.

## Как взять юнит

1. Юниты распределены по дорожкам A и B в мастер-плане (раздел «Sequencing and Team Lanes»); issue не заводятся — какой юнит берёшь, скажи команде.
2. Ветка `u<N>-<кратко>` от свежего `main`.
3. Крупный юнит начинается с плана в `docs/spec/plans/` (список крупных — в мастер-плане, раздел «Implementation Units»).
4. Работа по скиллам из `.agents/skills/` (как вызвать скилл в своей CLI — `docs/agent-process.md`, «Адаптеры CLI»):
   - `write-tests` — приёмочные тесты до реализации, отдельной сессией;
   - `implement-unit` — реализация: контракт, test-first, ворота качества;
   - `review-unit` — независимое ревью по `REVIEWING.md`;
   - `agent-log` — запись в `docs/agent-log.md`.
5. Готово — только с доказательствами: команды и их итоги, для UI — скриншоты на 360 и 1280 px.

## Правила PR

- В `main` — только через PR с `approve` по `REVIEWING.md`; пуш в `main` и `--force` запрещены и закреплены хуком `guard`.
- Один PR — один юнит (или его часть, если так нарезает план юнита) или одна правка спеки. Если код расходится со спекой, сначала отдельный PR в спеку.
- Описание по шаблону `.github/PULL_REQUEST_TEMPLATE.md`: зачем, что изменилось, чем доказано, ссылки на U/R/AE.
- Conventional Commits, scope — модуль: `feat(activity): …`, `docs(spec): …`. Без трейлеров об авторстве ИИ.
- Чужие PR смотрим дважды в день — утром и вечером. Ревьюер сам повторяет проверки на SHA из PR.

## Швы между дорожками

Дорожки A и B почти не трогают общие файлы: у каждого модуля свой пакет, свои changeset Liquibase и свои экраны. Общее:

- API-контракт `docs/spec/api/openapi.yaml` — меняется только PR с ревью второй дорожки;
- интерфейсы модулей `LedgerApi` (U6), чтение назначений студента из `checkpoint` (U7, для профиля U10), `CurrentUser` и `TeacherScope` (U5) — сигнатуры фиксируются в U3, до готовности реализации пишем против заглушки;
- таблицы `app_user` и `group_membership` — базу создаёт U4, расширяет U5.

Нужна правка в чужом шве — комментарий в PR владельца или вопрос команде, а не правка напрямую.
