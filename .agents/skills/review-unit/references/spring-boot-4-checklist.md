# Чек-лист ревью backend (Spring Boot 4.1, Modulith, Liquibase)

| Проверка | Нарушение выглядит так |
|---|---|
| Jackson 3 | `import com.fasterxml.jackson.databind.ObjectMapper` вместо `tools.jackson.databind.*` |
| Моки в тестах | `@MockBean` / `@SpyBean` вместо `@MockitoBean` / `@MockitoSpyBean` |
| Стартеры | `spring-boot-starter-web` вместо `spring-boot-starter-webmvc`; тестовые аннотации без `spring-boot-starter-webmvc-test` |
| Границы модулей | импорт `ru.campusrating.<другой>.internal.*`; `ModularityTest` выключен или изменён |
| Журнал проводок | изменение `balance` или вставка в журнал не через `LedgerApi` |
| Время | `Instant.now()`, `LocalDateTime.now()` без инжектированного `Clock` |
| Блокировки | `FOR UPDATE` не в порядке назначение → бюджет → баланс; несколько балансов не по возрастанию id |
| Идемпотентность | «проверить, есть ли, потом вставить» без уникального ключа или блокировки |
| Миграции | правка уже применённого changeset'а; файл вне `db/changelog/<module>/`; демо-данные без `context: demo` |
| Ошибки | ответ не `ProblemDetail`; доменная ошибка без поля `code` |
| Валидация | доменный код повторно валидирует уже разобранные данные или контроллер принимает сырые строки в домен |
| Тесты SQL | репозиторий или транзакция проверены моком вместо Testcontainers |
| Таймзона | месяц бюджета или дата считаются не в Europe/Moscow |
| Секреты | ключи, пароли, токены в `application*.yml` вместо переменных окружения |
