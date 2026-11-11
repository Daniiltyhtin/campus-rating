# U10 frontend — отчёт исполнителя

Ветка `u10-profile-frontend`, база `04ee40a` из PR34; [план](../../spec/plans/2026-11-11-002-feat-u10-profile-frontend-plan.md). Область: R3/R5/R6/R40/R41 и KTD18. Независимая приёмка реализации ещё требуется.

## Поведение и файлы

`/users/:id` заменяет placeholder профилем владельца. Студент: факультет/группа, балансы АР/БУА/БОА, вкладки истории и задач. Преподаватель: кафедра/факультет и ссылка в ленту наград; администратор: ФИО/роль. Role владельца не выводится из роли зрителя. История не запрашивается до валидного student profile и у teacher/admin.

`contracts.ts` разбирает unknown в generated schema aliases и строит только разрешённые поля. PUBLIC assignmentId и лишние submission-поля отбрасываются. `presentation.ts` форматирует фактические суммы и отдельно ограничивает ссылки: OWN + совпадение viewer/owner, ADMIN + роль ADMIN; PUBLIC не даёт перехода. Задачи и их статусы/порядок берутся из серверных массивов, без перерасчёта по браузерным часам.

`queries.ts` использует viewer/id/currency в query keys и AbortSignal, история — infinite query с API cursor. `ProfilePage.tsx` сохраняет уже загруженные строки при ошибке следующей страницы и повторяет именно следующий запрос, дедуплицирует id, даёт observer и кнопку подгрузки. Балансы только из profile, не сумма загруженных проводок. Названия и авторы истории — snapshot, null автор — «Система», даты — Europe/Moscow. ID профиля дополнительно сверяется с route id. `profile.css` использует токены существующей темы.

`mocks/profile.ts` выбирает variants generated examples по текущему mock viewer и владельцу, поддерживает студента1002 из roster U9, 401/404, currency и завершающийся cursor. Источник сессии один — readUser auth mock. Роутер и handlers получают только подключения этой фичи. БД, backend, контракт, generated-файлы не меняются; time formatter U9 переиспользован без правок.

## Проверки

| Команда/этап | Итог |
|---|---|
| Tester RED до production | 25 cases: 24 failed / 1 passed, exit1; pass — существующий auth guard; нет missing imports |
| Собственные contract/presentation/mock Red | 24 cases: 18 failed / 6 passed, exit1 |
| Независимые acceptance после реализации | 25/25 passed, exit0; бизнес-ожидания не менялись |
| ESLint и TypeScript | exit0 |
| `VITEST_MAX_WORKERS=1 npm --prefix frontend run check` | ESLint/TypeScript exit0, 218/218 Vitest и11/11 lint-rule, exit0 |
| `npm --prefix frontend run build` | финальная сборка1030 modules, exit0 |
| `node --test "tools/test/*.test.mjs"` | 140/140 passed, exit0 |
| `node tools/check-agent-docs.mjs` | passed, exit0 |
| `git diff --check` | exit0 |
| Browser Playwright CLI/Chrome + MSW | финальный полный сценарий exit0, 36 снимков, все проверки метрик прошли; ошибок приложения0 |

Независимый tester сам стабилизировал только timing своего suite: asyncUtilTimeout5000 с восстановлением и timeout15000 для трёх describe. На перегруженном хосте дефолтные 1000 ms давали пустой/auth-loading DOM с разными наборами failures. После изолированного запуска 25/25 passed; hooks/данные/приватность/counts/cancellation не подменялись, sleeps/force нет. Исполнитель acceptance не редактировал. В локальном полном прогоне VITEST_MAX_WORKERS=1 — ограничение ресурса процесса, не изменение проверок проекта.

Первый полный прогон дал217/218: старый shared mock smoke запрашивал профиль гостем. Tester добавил настоящий CSRF/login преподавателя и очистку cookies в handlers.test.ts, оставив точное сравнение с исходным PUBLIC generated example. Targeted smoke3/3 passed; финальный полный прогон218/218. Это необходимый вспомогательный файл, явно добавленный в Files плана. Урок в frontend/AGENTS.md сокращён до прохождения лимита инструкции8192 bytes.

## Матрица доказательств

| Критерий | Проверка |
|---|---|
| R3/R5: guest/password guard, invalid/unsafe id без API | Независимые guards acceptance |
| R5: own/public/teacher/admin, 940/120/50 из API | acceptance профилей, screenshots всех ролей |
| R3/R5: PUBLIC/чужой OWN/non-admin ADMIN без любых private href/полей | acceptance privacy + presentation unit, foreign-tasks browser |
| R5: ADMIN материалы и собственные задания | acceptance + browser student-tasks/admin-tasks после настоящего mock login/password change |
| R5: active/completed, пустые списки, статусы от API | acceptance задач и снимки |
| R6: +50 БОА, −20 АР, INITIAL, SEMESTER_ACCRUAL, source/author snapshot | acceptance истории + signed formatter unit; браузер истории |
| R6/KTD18: 120 строк, дубль на границе, currency/cursor | acceptance 120 уникальных id, неизменённый баланс940, query-параметры |
| R6/KTD18: поздняя валюта/id не смешивается | acceptance с deferred response и проверкой AbortSignal.aborted |
| R5/R6: загрузка/пустота/ошибка, retry, 403/404, malformed | acceptance независимых состояний + parser unit |
| R6: следующая ошибка сохраняет предыдущую историю | acceptance со счётчиком cursor и retry следующей страницы |
| R40/R41/ADR009: 360/1280, 3 темы, Москва, keyboard | снимки/метрики и browser Space на валюте с solid outline |

## UI-доказательства

Отдельный Vite dev-web с явным VITE_API_MOCKS=true на 127.0.0.1:5190; Docker engine не отвечал, MCP browser backend также давал timeout. Использован Playwright из существующего e2e package с установленным Chrome headless; реальные страницы, UI login/logout и смена временного admin password. Основные backend/DB не пересоздавались. Browser-result отделяет ожидаемый guest401 и favicon404 от ошибок приложения.

36 full-page снимков при viewport360×900 и1280×900: [метрики](ui-metrics.json), [сценарии и console outcome](browser-result.json). Фиксированная нижняя навигация остаётся на высоте первого viewport в full-page снимке; прокрутка позволяет прочитать содержимое. Контраст считался после fonts.ready и завершения анимаций по видимым текстовым узлам main с композициями фонов; disabled/option/hidden исключены. Radio target — связанный label. Это проверка данных состояний, не полный аудит U23.

| Экран | 360 light/dark/contrast | 1280 light/dark/contrast |
|---|---|---|
| История студента | [light](student-history-360-light.png) · [dark](student-history-360-dark.png) · [contrast](student-history-360-contrast.png) | [light](student-history-1280-light.png) · [dark](student-history-1280-dark.png) · [contrast](student-history-1280-contrast.png) |
| Свои задачи | [light](student-tasks-360-light.png) · [dark](student-tasks-360-dark.png) · [contrast](student-tasks-360-contrast.png) | [light](student-tasks-1280-light.png) · [dark](student-tasks-1280-dark.png) · [contrast](student-tasks-1280-contrast.png) |
| Чужие задачи | [light](foreign-tasks-360-light.png) · [dark](foreign-tasks-360-dark.png) · [contrast](foreign-tasks-360-contrast.png) | [light](foreign-tasks-1280-light.png) · [dark](foreign-tasks-1280-dark.png) · [contrast](foreign-tasks-1280-contrast.png) |
| Преподаватель | [light](teacher-360-light.png) · [dark](teacher-360-dark.png) · [contrast](teacher-360-contrast.png) | [light](teacher-1280-light.png) · [dark](teacher-1280-dark.png) · [contrast](teacher-1280-contrast.png) |
| Администратор | [light](admin-360-light.png) · [dark](admin-360-dark.png) · [contrast](admin-360-contrast.png) | [light](admin-1280-light.png) · [dark](admin-1280-dark.png) · [contrast](admin-1280-contrast.png) |
| Материалы для admin | [light](admin-tasks-360-light.png) · [dark](admin-tasks-360-dark.png) · [contrast](admin-tasks-360-contrast.png) | [light](admin-tasks-1280-light.png) · [dark](admin-tasks-1280-dark.png) · [contrast](admin-tasks-1280-contrast.png) |

Overflow0/36, min text contrast light5.07/dark7.54/contrast7.05, active targets ≥44/52. Проверены keyboard Space, фильтр BOA без AR, подгрузка INITIAL, собственные ссылки и отсутствие чужих private href, admin materials /admin/submissions/7001. Full UI стресс-проверка на120 строк — acceptance; browser использует небольшие generated examples.

## Ограничения и ручной повтор

Profile/ledger API planned. Реальная интеграция, серверная приватность и производительность БД этим frontend PR не заявляются. `/tasks/:id`, `/admin/submissions/:id`, `/awards` пока ведут в существующие placeholder других юнитов; здесь проверяется предусмотренная контрактом ссылка.

Для ручного повтора: явно включить MSW и запустить frontend dev; войти demo-студентом, открыть «Профиль», загрузить историю, сменить валюту/вкладку. Затем войти преподавателем и открыть /users/1001 — без переходов к сдаче; /users/2001 — кафедра и лента. Войти demo-admin, сменить временный пароль, открыть /users/1001 → «Задачи» → «Материалы сдачи». Моки сбрасываются при reload; реальных учётных данных нет.

План не расширен продуктовыми правилами. Вспомогательные файлы — test-only helper, подключение mocks и переиспользование существующего formatter. Независимый reviewer повторяет проверки на опубликованном SHA по REVIEWING.md; данный отчёт не является approve.
