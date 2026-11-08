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
- Каждый модуль — `package-info.java` с `@ApplicationModule`; `shared` — `type = OPEN`. Публичный API модуля — корень его пакета; вложенные `api` и `domain`, нужные соседям, модуль открывает `@NamedInterface` в своём юните.
- Граница проверяется тестом `ModularityTest` (`ApplicationModules.verify()`); красный — значит, нарушена архитектура, а не тест.
- Побочные эффекты между модулями — через события Modulith (`@ApplicationModuleListener`), не прямым вызовом.
- Баланс и проводки меняются только через `LedgerApi`. Прямой SQL в таблицы журнала и балансов из других модулей запрещён.

## Данные и миграции

- Liquibase, formatted SQL: `src/main/resources/db/changelog/<module>/<yyyyMMddHHmm>-<desc>.sql`, id changeset'а `<автор>:<метка>`.
- Master-changelog подключает папки через `includeAll`; новый файл master не трогает.
- Технические таблицы (реестр событий Modulith `event_publication`) — в `db/changelog/shared/`; папка подключается первой.
- Применённый changeset не редактируется — исправление новым changeset'ом.
- Демо-данные — `context: demo`, идемпотентны (`ON CONFLICT DO NOTHING`).
- Контексты Liquibase задаются всегда: `application.yml` — `default`, `application-demo.yml` — `default,demo` и `drop-first`. Тесты и `prod` идут с `default`. Без контекста Liquibase выполнил бы и `context: demo`.
- Папок модулей в `db/changelog/` заранее нет и `.gitkeep` в них не кладётся: `includeAll` разбирает любой файл папки как changelog. Папку создаёт первый changeset модуля.
- Время хранится в `timestamptz` (UTC), показывается в Europe/Moscow.

## Код

- Время — только инжектируемый `Clock`, никаких `now()` без часов.
- Имена настроек фиксированы с мастер-U2: `campus.mail.email-verification` ← `EMAIL_VERIFICATION` (`required | off`, без значения по умолчанию); `campus.penalty.default-step` ← `CAMPUS_PENALTY_DEFAULT_STEP` (20), `campus.penalty.default-interval` ← `CAMPUS_PENALTY_DEFAULT_INTERVAL` (`1m`), `campus.penalty.poll-period` ← `CAMPUS_PENALTY_POLL_PERIOD` (`1s`). Новые имена для тех же значений не заводить.
- Бизнес-правила (штраф, срезки, бюджет, статусы) — чистые функции в `domain`, без Spring и базы.
- DTO проверяются Bean Validation на входе и разбираются в доменные типы в контроллере.
- Ошибки — RFC 7807 `ProblemDetail` с полем `code` (`BUDGET_EXCEEDED`, `NOT_ENROLLED`, `INVALID_STATE`).
- Блокировки — в порядке назначение → бюджет → баланс (KTD5 мастер-плана). Новая блокировка вне этого порядка — вопрос, а не решение.
- Операции идемпотентны через уникальный ключ, а не через проверку «уже есть?» без блокировки.

## Тесты

- Тест вызывает код так, как его вызывает пользователь, и проверяет видимый результат.
- Доменные функции — модульные тесты без Spring. Пороги: JaCoCo ≥ 80% строк и CRAP ≤ 10 в `*.domain`; PIT ≥ 70% в `ledger.domain`, `checkpoint.domain`, `activity.domain`.
- SQL, транзакции и блокировки — интеграционные тесты `*IT` на Testcontainers PostgreSQL 18, без моков `JdbcTemplate` и репозиториев: они не видят транзакций и ограничений. Наследуй `support/PostgresIntegrationTest`: один контейнер на JVM, сдвигаемый `Clock` сбрасывается перед тестом. Пока контейнер общий, параллельный запуск JUnit не включать.
- HTTP: `MockMvcTester` для срезов, `RestTestClient` для настоящего HTTP; `TestRestTemplate` не использовать.
- События модулей — `@ApplicationModuleTest` и `Scenario` из `spring-modulith-starter-test`.

## Команды

Команды из корня репозитория:

| Что | Команда |
|---|---|
| Unit, интеграция, модульность, контракт, JaCoCo, CPD, CRAP | `./backend/mvnw -f backend/pom.xml verify` и `node tools/crap.mjs backend/target/site/jacoco/jacoco.xml` |
| Мутации по `*.domain` | `./backend/mvnw -f backend/pom.xml -Pquality verify` |
| Один модульный тест | `./backend/mvnw -f backend/pom.xml -Dtest=MoscowTimeTest test` |

Тесты `*IT` требуют Docker. Профиль `quality` (PIT) запускается локально ревьюером, в CI не используется. На Windows — `backend/mvnw.cmd`.

## Уроки

Правила — как в разделе «Уроки» корневого `AGENTS.md`.

- Модель данных считала только стоимость записи → человек спросил про 1000+ проводок на студента → сразу оценивать самое частое чтение (списки, рейтинги), а не только запись (мастер-план, KTD3: журнал проводок и снимок баланса).
