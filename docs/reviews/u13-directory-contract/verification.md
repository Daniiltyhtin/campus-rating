# U13 — контракт справочников

Первая часть существующего R24, без изменения R/KTD. План: [U13 frontend](../../spec/plans/2026-11-11-003-feat-u13-directory-frontend-plan.md). База — U11 `fdf9939`; backend/БД не меняются.

Добавлены 12 planned ADMIN-операций: GET/POST для трёх коллекций `/admin/directory/faculties`, `/admin/directory/groups`, `/admin/directory/departments` и PUT/DELETE для записи `/{id}`. Мутации требуют session+csrf. DTO включают code/name, facultyId в записи и faculty ref в ответе, admissionYear группы. Публичные урезанные DTO не меняются. Два кода409: DIRECTORY_CODE_TAKEN и DIRECTORY_IN_USE; VALIDATION_FAILED/401/403/404 используют существующие схемы.

HTTP-ограничения: непустые code/name после trim (64/255 code points), год1…9999 без отсечения будущего набора; код уникален в своём справочнике с сохранением регистра. Удаление без каскада, зависимые записи сохраняются. Для backend реализации эти planned операции — контракт; здесь они не объявляются реализованными.

Проверки:

- Redocly2.76.0 с `docs/spec/api/redocly.yaml` — exit0. Негативная копия с admissionYear0 в success example — exit1 по `no-invalid-media-type-examples`, ожидаемый отказ.
- `npm --prefix frontend run api:gen` — exit0; повторная генерация даёт те же SHA256 всех трёх generated-файлов.
- Frontend check/build, tools и agent-docs/diff — фактические итоги внесены в журнал этой ветки после завершения команд.
- В diff отсутствуют backend изменения. Runtime UI/MSW CRUD — следующий отдельный frontend PR; новый контракт ещё требует независимого ревью.

Contract-first PR публикуется перед frontend реализацией. Наличие generated examples само по себе не доказывает авторизацию или транзакции backend.

Первый CI PR40 обнаружил ошибку сериализации: YAML stringify повторно использовал объекты через anchors, swagger-parser в ContractCompatibilityIT передал alias как строку в oasdiff. Anchors убраны без правок backend: parsed YAML до/после deepEqual, Redocly exit0, api:gen не изменяет generated. Исправление проверяется повторным CI этого PR.
