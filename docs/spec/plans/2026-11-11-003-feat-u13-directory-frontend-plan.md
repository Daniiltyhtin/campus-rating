---
title: U13 frontend — справочники
type: feat
date: 2026-11-11
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: docs/spec/plans/2026-10-31-001-feat-campus-rating-mvp-plan.md
execution: code
---

# U13 frontend — справочники

## Goal Capsule

- **Цель.** Администратор на `/admin/directory` просматривает, создаёт, редактирует и удаляет факультеты, учебные группы и кафедры (R24, мастер-U13).
- **Источники.** Мастер-план Goal/R3/R24/KTD10/KTD17/KTD23/U13/Verification; glossary, screen-map, ADR009, frontend/AGENTS.md; OpenAPI admin directory operations.
- **Граница.** Первая законченная часть U13: только три справочника. Допуск преподавателей/выдачи, импорт, настройки/семестры/старосты, временные пароли и материалы сдачи — следующие части; U13 целиком не объявляется готовым. Backend/БД не меняются. Явный MSW до готовности API, без fallback по HTTP-ошибке.
- **База/контракт.** `spec-u13-directory-api` от `fdf9939` U11; сначала отдельный PR контракта с generated, потом `u13-directory-frontend` поверх него. Родители PR33–36 на старте OPEN/CI SUCCESS. Роли/требования не меняются; добавляются planned HTTP-операции существующего R24. Независимое ревью/merge — человек, исполнитель не approve/merge.
- **Процесс.** implement-unit/ub-mode, frontend tester по write-tests до production (человек разрешил); acceptance исполнитель не меняет. Готовый результат разрешено коммитить/пушить/создавать PR.

## Files

- Контрактный PR: `docs/spec/api/openapi.yaml`, `docs/spec/api/README.md`, generated `frontend/src/api/schema.d.ts`, `frontend/src/api/problemCodes.generated.ts`, `frontend/src/mocks/examples.generated.ts` (только api:gen), этот план, `docs/reviews/u13-directory-contract/`, журнал. Никаких backend изменений.
- Frontend PR: `frontend/src/features/admin/directory/` (DirectoryPage, формы/диалоги, parsers/validation/hooks/styles/unit tests); `directory.acceptance.test.tsx` — tester.
- `frontend/src/test/directoryAcceptance.tsx` — test-only helper/fixtures, tester.
- `frontend/src/app/router.tsx`: один lazy route admin/directory внутри SessionGate. Навигация уже содержит ссылку.
- `frontend/src/mocks/directory.ts`, `directory.test.ts`, `handlers.ts`: MSW с readUser/validCsrf, generated examples. Не править старые feature/auth mocks; публичные registration directory GET могут переопределяться новым mock handler для отражения изменений справочников.
- `docs/reviews/u13-directory-frontend/`, `docs/agent-log.md`, связанные уроки frontend/AGENTS.md в пределах бюджета. Общую тему не расширять.

## Контракт и наблюдаемое поведение

1. **Доступ.** Только ADMIN; STUDENT/TEACHER — «Нет доступа» без admin-запросов. Guest/password — существующие guards до feature-запросов. CSRF на POST/PUT/DELETE через общий клиент; backend/mocks проверяют права независимо от UI.
2. **HTTP.** `/api/v1/admin/directory/{faculties,groups,departments}`: GET полный список, POST→201; `/{id}` PUT полная замена редактируемых полей→200, DELETE→204. Все операции planned/ADMIN. GET порядок name/id от сервера, клиент его сохраняет. Нет догадок о курсах и агрегаций по студентам.
3. **Данные.** Факультет: id/code/name; группа: id/code/name/faculty/admissionYear; кафедра: id/code/name/faculty. В write вместо faculty — facultyId. code/name обязательны после trim, максимум 64/255 Unicode code points; код уникален внутри каждого из трёх справочников, регистр сохраняется (как действующий U4 SQL). facultyId положительный safe integer из списка, admissionYear целый 1…9999; будущий набор разрешён, текущая дата не ограничивает год. Это ограничения HTTP-полей, не изменение правил курса KTD23.
4. **Три вкладки.** «Факультеты» (default), «Учебные группы», «Кафедры», доступные клавиатурой. Таблица: code/name, у групп faculty/admissionYear, у кафедр faculty. Loading/empty/error/retry раздельны. Faculty query используется формами связи; ошибка справочника не подменяется пустым выбором, есть retry. Списки групп/кафедр не строятся из публичных урезанных refs, где нет code/admissionYear.
5. **Создание/правка.** Кнопки «Добавить факультет/группу/кафедру»; диалог с полями «Код», «Название», при необходимости «Факультет», «Год набора». Подпись «Код» не смешивается с названием. «Сохранить» и «Отмена»; явная валидация до HTTP. Edit предзаполняется строкой, форма привязана к kind/id; после закрытия не остаются старые поля/ошибки. При отсутствии факультетов группа/кафедра не сохраняется, объясняется необходимость сначала создать факультет.
6. **Результат/инвалидация.** mutation retry=false; pending блокирует повтор submit/закрытие диалога и конфликтующие действия. После HTTP success диалог закрывается, текстовый role=status сообщает успех, QueryClient invalidates admin directory и публичные directory/accessible-groups/profile keys, не копирует строку в local server state. Дальнейший GET может ошибиться: показать refresh retry, не повторять уже успешную мутацию. Списки могут обновляться после серверной задержки.
7. **Удаление.** Только после диалога с названием, «Удалить»/«Отмена»; повтор click не создаёт повторный DELETE. Сервер отвергает используемую запись (включая группу со студентами, факультет с группами/кафедрами, кафедру с преподавателями/allowlist); 409 DIRECTORY_IN_USE — понятный текст без удаления строки/ложного успеха. Клиент не решает возможность удаления по отсутствующим в API связям. 404 — запись уже удалена, закрыть устаревший диалог, обновить списки и сообщить об этом. Cancel не делает запрос.
8. **Ошибки записи.** VALIDATION_FAILED — fieldErrors под известными полями + общий текст; unknown field не пропадает бесследно (общая ошибка остаётся). 409 DIRECTORY_CODE_TAKEN — ошибка «Код уже используется» у code, поля сохраняются. 403 — понятный отказ, без фиктивного success; CSRF_INVALID — обновить csrf отдельной кнопкой и просить повторить действие, автоматически мутацию не повторять. Сеть/500/malformed success — понятная ошибка, форма сохраняется и можно вручную повторить; для malformed небезопасно обещать, что сервер ничего не записал, текст предлагает обновить список прежде повтора. Не показывать raw ProblemDetail.
9. **Входной парсинг.** generated schemas, safe ids, обязательные поля, string/nonblank/code/name limits, enum kind, целый admissionYear, faculty refs, arrays; не подменять malformed пустыми данными. Write validation — чистые функции, FormData локальна; серверные списки — только TanStack Query. Ответ PUT/POST должен соответствовать типу и ожидаемому id (для edit).
10. **MSW.** Создаётся из generated admin/public examples, один auth state readUser; все три вида CRUD со стабильными ids, сортировкой, uniqueness и связями. Неизвестный facultyId — field error. Контрактная ИВТ-21(id11) считается занятой студентами и не удаляется; ПИ(id101) занятой преподавателем; ФИТ(id1) связан группами/кафедрой. Остальные новые записи можно создать/изменить/удалить. Ограничения fake-сервера явны, реальные FK/транзакции не доказаны. Успешные изменения отражаются в public faculty/group/department lists для нового чтения, без изменения старого auth mock-state.
11. **Вид.** Mantine/токены «Редакция», три темы; min360 без overflow, длинные имена/коды переносятся, цели обе стороны >=44/52, контраст >=4.5/7. Диалог: focus trap, видимый focus, Esc/cancel до отправки, возвращение фокуса, ошибки связаны с полем. Не объявлять U23 завершённым.

## Цепочка требований / приёмка

| Требование | Пример/граница | Проверка |
|---|---|---|
| R3/R24 | guest/password/student/teacher; admin | guard и отсутствие запросов, доступ ADMIN; acceptance |
| R24 | 3 списка, порядок, loading/empty/network500/malformed/retry | реальные routes/MSW; acceptance/parser unit |
| R24 | создать ФКН/группу2026/кафедру с facultyId | POST body/CSRF, новый GET/result status; acceptance/mock unit |
| R24/KTD17 | blank/64 vs65/255 vs256/year1/9999/fraction/faculty missing | нет HTTP, ошибки полей; acceptance/validation unit |
| R24 | edit/cancel/reopen другой id, серверные ошибки | правильный PUT/id, поля не смешиваются; acceptance |
| R24 | used group11 DELETE409; новая группа delete204; cancel | строка сохранена/обновлена без false success; acceptance/mock unit |
| R24 | code duplicate409, server fieldErrors,403,CSRF,network500 | понятные ошибки/поля сохранены/нет auto-retry; acceptance |
| R24 | double submit, write success + GET500 | одна мутация; retry только чтения; acceptance |
| R24 |404 stale editor/delete | invalidation/объяснение; acceptance |
| R24 | mutate→new public list | общие FK refs/сортировка; mock unit |
| R40/ADR009 |360/1280 ×3темы, формы/confirm/error/long/keyboard | browser screenshots/метрики |
| Граница | отсутствие backend/SQL изменений | git diff; реальные ограничения не проверены |

## Порядок / Verification Contract

1. Контрактный PR: добавить planned операции/DTO/примеры/ошибки, Redocly lint, api:gen дважды с нулевым вторым diff; frontend check/build, tools/doc/diff. Перед frontend отдельный contract PR опубликован. Product R/KTD не изменяются.
2. Tester до production через реальные routes/MSW/изолированную auth, CampusThemeProvider + Mantine env=test; Red по placeholder. Не импортировать будущие production-модули. Очистка QueryClient/router и cookies.
3. Исполнитель после Red: собственные parser/validation tests Red → hooks/UI → scoped mocks → acceptance Green. Исполнитель не меняет acceptance.
4. `npm --prefix frontend run check`, build; `node --test "tools/test/*.test.mjs"`; `node tools/check-agent-docs.mjs`; diff --check; backend пуст. Явный MSW dev-web, browser проход всех3справочников/create/edit/delete/errors/dialog keyboard на360/1280 ×3темы, снимки. При доступном Docker — изолированный compose/e2e и cleanup, иначе ограничение.
5. Отчёт/матрица/handoff и agent-log; зелёный коммит явных путей, сверка stat, push/PR в contract branch. Без self-approve/merge; последующие части U13 отдельно.
