# Backend — правила для агентов

Java 25, Spring Boot 4.1, Spring Modulith 2.1, PostgreSQL 18, Liquibase, Maven (`mvnw`, на Windows — `mvnw.cmd`). Дополняет корневой `AGENTS.md`.

## Spring Boot 4 — не Boot 3

Обучающие данные моделей в основном про Boot 3. Проверяй себя:

- Jackson 3: пакеты `tools.jackson.*` (аннотации остались в `com.fasterxml.jackson.annotation`). Импорт `com.fasterxml.jackson.databind` — ошибка.
- Стартеры модульные: `spring-boot-starter-webmvc`, `spring-boot-starter-liquibase`, тестовые `spring-boot-starter-webmvc-test`, `spring-boot-starter-security-test`.
- `@MockBean` и `@SpyBean` удалены: только `@MockitoBean` и `@MockitoSpyBean`.
- Сервлет 6.1, Tomcat 11. Undertow не поддерживается.
- Полный чек-лист ловушек — `.agents/skills/review-unit/references/spring-boot-4-checklist.md`.

## Модули

- Пакет на фичу: `ru.campusrating.<module>` — `identity`, `directory`, `ledger`, `checkpoint`, `activity`, `profile`, `feed`, `poll`, `admin`, `notification`, `analytics`, `ai`, `settings`, `shared`.
- Внутри модуля: `api` (контроллеры, DTO), `domain` (чистые функции и типы), `internal` (репозитории, сервисы). Чужой `internal` не импортировать.
- Граница проверяется тестом `ModularityTest` (`ApplicationModules.verify()`); красный — значит, нарушена архитектура, а не тест.
- Побочные эффекты между модулями — через события Modulith (`@ApplicationModuleListener`), не прямым вызовом.
- Баланс и проводки меняются только через `LedgerApi`. Прямой SQL в таблицы журнала и балансов из других модулей запрещён.

## Данные и миграции

- Liquibase, formatted SQL: `src/main/resources/db/changelog/<module>/<yyyyMMddHHmm>-<desc>.sql`, id changeset'а `<автор>:<метка>`.
- Master-changelog подключает папки через `includeAll`; новый файл master не трогает.
- Применённый changeset не редактируется — исправление новым changeset'ом.
- Демо-данные — `context: demo`, идемпотентны (`ON CONFLICT DO NOTHING`). Тесты идут без контекста `demo`.
- Время хранится в `timestamptz` (UTC), показывается в Europe/Moscow.

## Код

- Время — только инжектируемый `Clock`, никаких `now()` без часов.
- Бизнес-правила (штраф, срезки, бюджет, статусы) — чистые функции в `domain`, без Spring и базы.
- DTO проверяются Bean Validation на входе и разбираются в доменные типы в контроллере.
- Ошибки — RFC 7807 `ProblemDetail` с полем `code` (`BUDGET_EXCEEDED`, `NOT_ENROLLED`, `INVALID_STATE`).
- Блокировки — в порядке назначение → бюджет → баланс (KTD5 мастер-плана). Новая блокировка вне этого порядка — вопрос, а не решение.
- Операции идемпотентны через уникальный ключ, а не через проверку «уже есть?» без блокировки.

## Тесты

- Доменные функции — модульные тесты без Spring.
- Всё с SQL, транзакциями и блокировками — интеграционные тесты `*IT` на Testcontainers PostgreSQL 18. Мок `JdbcTemplate` или репозитория для этого не годится: он не видит транзакций и ограничений.
- Время в тестах — сдвигаемый `Clock` из базового класса интеграционных тестов.
- Тест вызывает код так, как его вызывает пользователь, и проверяет видимый результат.
- Пороги: JaCoCo ≥ 80% строк и CRAP ≤ 10 в `*.domain`; PIT ≥ 70% в `ledger.domain`, `checkpoint.domain`, `activity.domain`.

## Команды

Появятся в мастер-U2: `./mvnw verify` (unit, интеграция, модульность, контракт, JaCoCo, CPD) и `./mvnw -Pquality verify` (PIT). На Windows — `mvnw.cmd`.
