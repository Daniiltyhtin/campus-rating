---
title: U6 Ledger - Plan
type: feat
date: 2026-11-09
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: docs/spec/plans/2026-10-31-001-feat-campus-rating-mvp-plan.md
origin: docs/spec/plans/2026-10-31-001-feat-campus-rating-mvp-plan.md
execution: code
---

# U6 Ledger - Plan

## Goal Capsule

- **Цель.** Все три валюты меняются только через `LedgerApi`: проводка неизменяема и идемпотентна, снимок баланса обновляется в той же транзакции, АР не выходит за 0…1000, сверка показывает расхождения. Интерфейс шва попадает в `main` раньше реализации, чтобы дорожка B писала U5 и U9 против него.
- **Иерархия источников.** Мастер-план `docs/spec/plans/2026-10-31-001-feat-campus-rating-mvp-plan.md` (раздел U6, R5, R6, R8, R9, R11, AE4, AE5, AE6, KTD3, KTD5, KTD6, KTD15), `docs/spec/api/module-seams.md` (правила, протокол транзакции начисления, раздел 1 `LedgerApi`), `docs/spec/api/openapi.yaml` (операция `reconcileLedger`, схемы `LedgerReconciliation`, `LedgerEntry`), ADR001. Здесь R, KTD и U — локальные, ссылки на мастер помечены «мастер-». Сигнатуры не повторяются: источник — `module-seams.md`.
- **Профиль исполнения.** U1 (интерфейс) — механический перенос сигнатур, отдельный маленький PR, ревьюит второй разработчик как потребитель. U2–U5 пишет агент-исполнитель test-first, ревьюит другой агент или человек и сам гоняет `*IT` с Docker.
- **Остановись и спроси, если:**
  - нужно изменить сигнатуру из `module-seams.md` (правится документ с ревью потребителей);
  - тест можно сделать зелёным только ослаблением мастер-KTD3 (запись мимо `post`, баланс без `CHECK`);
  - порядок блокировок расходится с мастер-KTD5.
- **Ветки.** `u6a-ledger-api` — этот план и U1 одним PR; `u6-ledger` (U2–U5) от `main`. U1 не зависит от мастер-U4 и вливается сразу. U2 и дальше стартуют после слияния мастер-U4: внешний ключ на `app_user` и исключения `shared.error` приходят оттуда.

---

## Product Contract

### Summary

Модуль `ledger`: интерфейс `LedgerApi` и его типы в корне пакета, реализация в `internal`, правила суммы в `domain`, две таблицы, эндпоинт сверки для администратора.

### Requirements

- R1. Запись — только `LedgerApi.post` пачкой внутри транзакции вызывающего (`MANDATORY`); результат по каждому элементу — `Posted`, `AlreadyPosted` или `Zero`, в порядке входа (мастер-KTD3, швы).
- R2. Фактическую сумму считает чистая функция под блокировкой баланса: АР 0…1000, сумма, срезанная до 0, проводки не даёт; БУА и БОА не срезаются (мастер-R9, R11, AE4, AE5).
- R3. Повтор ключа не меняет баланс и возвращает исходную проводку, в том числе при двух параллельных транзакциях (мастер-AE6).
- R4. Снимок баланса и проводка — в одной транзакции; сбой откатывает обе; `CHECK` в базе держит АР 0…1000 даже в обход кода.
- R5. Чтение — `balances` из снимка, `history` и `awards` с курсором `(createdAt desc, id desc)`; битый курсор — `VALIDATION_FAILED` по полю `cursor`.
- R6. Сверка `reconcile()` и `GET /api/v1/admin/ledger/reconciliation` возвращают пары, где снимок ≠ сумме журнала.

### Scope Boundaries

- Контроллер `/ledger/{userId}/entries` — см. открытый вопрос Q1; до ответа в U6 не входит.
- Проверка роли `ADMIN` у эндпоинта сверки — `SecurityConfig` мастер-U5 (дорожка B). До его слияния эндпоинт без защиты, стенда ещё нет (мастер-U22).
- Начисление семестра и штрафы — мастер-U8: они вызывают `post` с ключами из швов.

---

## Planning Contract

### Key Technical Decisions

- KTD1. **Интерфейс отдельным PR до реализации.** Без бина `LedgerApi` в `main` ничего не ломается: его ещё никто не внедряет. B компилирует U5 и U9 против настоящих типов и мокает их, как велят швы. Пустой реализации в `src/main` нет.
- KTD2. **Схема.**
  - `ledger_entry`: `id bigserial`, `student_id bigint not null references app_user(id)`, `currency text`, `kind text`, `amount int not null check (amount <> 0)`, `source_type text`, `source_id bigint null`, `source_title text not null`, `author_id bigint null`, `author_name text null`, `idempotency_key text not null unique`, `created_at timestamptz not null`; индексы `(student_id, created_at desc, id desc)` и частичный `(created_at desc, id desc) where kind = 'ACTIVITY_AWARD'`.
  - `ledger_balance`: `student_id bigint references app_user(id)`, `currency text`, `amount int not null`, первичный ключ `(student_id, currency)`, `check (currency <> 'AR' or amount between 0 and 1000)`.
  - Перечисления — `text` с `check` по значениям `Currency`, `EntryKind`, `SourceType`: так их видно в SQL без справочных таблиц.
- KTD3. **Порядок внутри `post`.**
  1. Проверить элементы (KTD4); ошибка — `IllegalArgumentException` до любой записи.
  2. Создать недостающие строки баланса: `insert … on conflict do nothing` в порядке `(student_id, currency)`.
  3. Заблокировать строки баланса пачки одним `select … for update order by student_id, currency` (мастер-KTD5: баланс — последним и по возрастанию id студента).
  4. Для каждого элемента по порядку входа: есть проводка с этим ключом — `AlreadyPosted`; иначе `BalanceRules` по текущему значению в памяти; `0` — `Zero`; иначе вставка проводки и сдвиг значения в памяти.
  5. Записать итоговые балансы.

  Параллельная транзакция с тем же ключом ждёт на шаге 3 и после фиксации первой видит её проводку на шаге 4 (READ COMMITTED перечитывает после блокировки). Уникальный индекс — страховка, а не основной механизм.
- KTD4. **Проверка элемента.** Знак по виду: `PENALTY` < 0, остальные > 0. Валюта по виду: `INITIAL`, `SEMESTER_ACCRUAL`, `PENALTY` — только `AR`; `ACTIVITY_AWARD` — `BUA` или `BOA`. Обязательные поля — по швам (`author` и `source.id` у `ACTIVITY_AWARD`, `source.id` у `PENALTY`). Валюта по виду — решение этого плана (в швах его нет): оно не даёт штрафу случайно уйти в БОА.
- KTD5. **Где что лежит.** Интерфейс и типы — корень `ru.campusrating.ledger` (швы). `BalanceRules` и проверка элемента — `ledger.domain` (чистые функции, PIT ≥ 70%). Репозиторий на `JdbcClient`, сервис — `ledger.internal`, контроллер — `ledger.api`. Курсор — `base64url("<filter>:<epochMicros>:<id>")` (`filter` — `history/<studentId>/<currency|ALL>` или `awards`; несовпадение фильтра — ошибка `cursor`, время — от 1970-01-01 до 9999-12-31 включительно) в `ledger.domain`.
- KTD6. **Сверка.** Один SQL: полное внешнее соединение снимка и `sum(amount)` по `(student_id, currency)`; `checkedPairs` — число пар в объединении, `checkedAt` — из `Clock`.

### Open Questions

- Q1. **Кто делает контроллер `/ledger/{userId}/entries`.** Таблица модулей плана U3 отдаёт его мастер-U6 (A), а `module-seams.md` — мастер-U10 (B). Предложение: U6, потому что `history` реализуется здесь же, а контроллер — два десятка строк; тогда правится строка в `module-seams.md`. Решают оба разработчика до старта U4 этого плана.

### Risks and Dependencies

| Риск | Митигация |
|---|---|
| Эндпоинт сверки открыт до мастер-U5 | В PR мастер-U5 — тест `401` и `403` для `/api/v1/admin/**`; строка в описании PR U6 |
| Взаимная блокировка двух пачек с разным набором студентов | Одна сортировка `(student_id, currency)` на вставке и блокировке; IT с двумя транзакциями в обратном порядке студентов |
| Потребитель зовёт `post` без транзакции | `MANDATORY` бросает исключение; IT на это |
| Инвариант ломается записью мимо `post` | Граница модуля (`ModularityTest`), `CHECK`, сверка; негативный сценарий ревью из мастер-U6 |

---

## Implementation Units

### U1. Интерфейс `LedgerApi` (PR U6a)

- **Goal.** Типы шва в `main`, B компилируется против них.
- **Requirements.** R1, R5, R6 (сигнатуры); KTD1.
- **Dependencies.** нет.
- **Files:** `backend/src/main/java/ru/campusrating/ledger/LedgerApi.java` и типы шва из `module-seams.md` в том же пакете (`package-info.java` с `@ApplicationModule` уже есть с мастер-U2).
- **Approach.** Перенести сигнатуры раздела 1 швов без изменений, без реализации. `@Nullable` — `org.jspecify.annotations.Nullable`: JSpecify 1.0.1 уже приходит транзитивно (`dependency:tree`), новой зависимости не нужно.
- **Test scenarios:** `ModularityTest` зелёный; приложение стартует (`SmokeIT`): бина `LedgerApi` никто не требует.
- **Verification.** `./backend/mvnw -f backend/pom.xml verify`; ревью — второй разработчик.

### U2. Правила суммы и проверка элемента

- **Goal.** Вся арифметика и валидация — чистые функции с полным покрытием.
- **Requirements.** R2; KTD4, KTD5.
- **Dependencies.** U1.
- **Files:** `backend/src/main/java/ru/campusrating/ledger/domain/BalanceRules.java`, `PostingRules.java`, `LedgerCursor.java`; тесты рядом в `backend/src/test/java/ru/campusrating/ledger/domain/`.
- **Execution note.** Test-first: сначала `BalanceRulesTest`.
- **Test scenarios:**
  - Covers мастер-AE5. +200 при АР 900 → +100; при АР 1000 → 0.
  - Covers мастер-AE4. −20 при АР 10 → −10; при АР 0 → 0.
  - +50 БОА при 5000 БОА → +50 (без срезки).
  - `PENALTY` с положительной суммой, `ACTIVITY_AWARD` в `AR`, `ACTIVITY_AWARD` без автора, `PENALTY` без `source.id`, сумма 0 — отказ.
  - Курсор: кодирование и разбор туда-обратно; мусор и чужой формат — ошибка разбора.
- **Verification.** Модульные тесты зелёные; PIT по `ledger.domain` ≥ 70%.

### U3. Схема и `post`

- **Goal.** Проводки и балансы пишутся атомарно, идемпотентно и без взаимных блокировок.
- **Requirements.** R1–R4; KTD2, KTD3.
- **Dependencies.** U2; слияние мастер-U4 (`app_user`, `shared.error`).
- **Files:** `backend/src/main/resources/db/changelog/ledger/<ts>-ledger.sql`; `backend/src/main/java/ru/campusrating/ledger/internal/` (репозиторий, сервис `LedgerService implements LedgerApi`); `backend/src/test/java/ru/campusrating/ledger/LedgerIT.java`.
- **Test scenarios:**
  - Проводка `INITIAL` +1000 создаёт баланс и возвращает `Posted` с `balanceAfter` 1000.
  - Повтор ключа → `AlreadyPosted` с исходной суммой, баланс прежний.
  - Covers мастер-AE6. Две транзакции с одним ключом параллельно (барьер) → одна проводка, второй результат — `AlreadyPosted`.
  - Две пачки по студентам (A, B) и (B, A) параллельно — обе завершаются, взаимной блокировки нет.
  - Пачка из трёх элементов: результаты в порядке входа; элемент со срезкой до 0 — `Zero`, записи нет.
  - `post` без транзакции — исключение, записей нет.
  - Исключение после `post` в транзакции вызывающего откатывает и проводку, и баланс.
  - `update ledger_balance set amount = 1001` по `AR` — нарушение `CHECK`.
  - Инвариант: 1000 случайных операций по нескольким студентам и валютам — снимок равен сумме журнала.
- **Verification.** `./backend/mvnw -f backend/pom.xml verify` с `LedgerIT`.

### U4. Чтение: балансы, история, награды

- **Goal.** Профиль, лента и рейтинги читают из снимка и журнала через шов.
- **Requirements.** R5.
- **Dependencies.** U3; ответ на Q1.
- **Files:** `backend/src/main/java/ru/campusrating/ledger/internal/`; `LedgerIT.java`; при решении Q1 в пользу U6 — контроллер `listLedgerEntries`, `x-status: implemented` в `openapi.yaml` и строка в `module-seams.md`.
- **Test scenarios:**
  - `balances` без строк → 0, 0, 0; пачечный `balances` возвращает ключ для каждого запрошенного id.
  - `history` 25 записей, `limit` 20 → 20 и курсор, вторая страница → 5 и `null`; фильтр по валюте; одинаковое `createdAt` не теряет и не дублирует записи.
  - `awards` содержит только `ACTIVITY_AWARD`.
  - Битый курсор → `ValidationFailedException` по полю `cursor`.
- **Verification.** `./backend/mvnw -f backend/pom.xml verify`.

### U5. Сверка

- **Goal.** Администратор видит расхождения снимка и журнала.
- **Requirements.** R6; KTD6.
- **Dependencies.** U3.
- **Files:** `backend/src/main/java/ru/campusrating/ledger/internal/` (запрос), `backend/src/main/java/ru/campusrating/ledger/api/` (контроллер `reconcileLedger`); `docs/spec/api/openapi.yaml` (`x-status: implemented`); `LedgerIT.java`.
- **Test scenarios:**
  - После операций U3 `mismatches` пусто, `checkedPairs` равно числу пар.
  - Прямой `update ledger_balance` в тесте → одна пара в `mismatches` с балансом и суммой журнала.
  - `GET /api/v1/admin/ledger/reconciliation` отдаёт форму схемы `LedgerReconciliation`, `checkedAt` из тестовых часов.
- **Verification.** `./backend/mvnw -f backend/pom.xml verify`; сверка на демо-стеке (`docker compose up`) — пустой список.

---

## Verification Contract

| Ворота | Команда | Когда |
|---|---|---|
| Backend | `./backend/mvnw -f backend/pom.xml verify` | U1–U5 |
| Мутации и CRAP | `./backend/mvnw -f backend/pom.xml -Pquality verify` и `node tools/crap.mjs backend/target/site/jacoco/jacoco.xml` | U2, затем перед PR U6 |
| Стек | `docker compose up` и `GET /api/v1/admin/ledger/reconciliation` | U5 |

## Definition of Done

**PR U6a (U1):** типы шва совпадают с `module-seams.md`, `verify` зелёный, ревью второго разработчика.

**PR U6 (U2–U5):**
- Тестовые сценарии U2–U5 реализованы; PIT `ledger.domain` ≥ 70%, JaCoCo ≥ 80% строк и CRAP ≤ 10 в `ledger.domain`.
- Негативный сценарий мастер-U6: в учебной ветке баланс меняется в обход `LedgerApi` — ревьюер выносит `request changes` со ссылкой на мастер-KTD3; ветка не вливается.
- Сверка на демо-стеке пустая.
- Запись в `docs/agent-log.md` и уроки — в той же ветке.
