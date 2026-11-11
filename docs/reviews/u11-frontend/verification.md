# U11 frontend — проверка

## Объём и база

План: [U11 frontend](../../spec/plans/2026-11-11-002-feat-u11-feed-frontend-plan.md). Ветка `u11-feed-frontend` от `b58d949` U10; база PR — `u10-profile-frontend`. При старте PR33/34/35 OPEN, у каждого harness/backend/frontend/e2e SUCCESS. Backend, контракт, generated-типы/примеры и БД не меняются.

Реализованы `/awards`, `/groups/:id/ratings` и входы из `/groups`. Лента читает серверные snapshots, курсор и порядок; отдельный polling query первой страницы каждые 30 s/при возвращении в окно. Новые строки применяются кнопкой с заменой query pages и сбросом старых cursors. Рейтинг показывает серверные балансы и сортировку, проверяет соответствие group/sort ответу, отменяет покинутые запросы. MSW включается только явно.

## Test-first и независимость

Отдельный tester по `write-tests` подготовил `feed.acceptance.test.tsx`, `test/feedAcceptance.tsx`, mock-login в существующем `mocks/handlers.test.ts`. До production: 31 failed/3 passed (guards), затем ещё 2 failed/34 skipped; targeted Red рейтинга — отсутствует таблица, DOM «Раздел появится». Исполнитель не менял acceptance.

Первый Green выявил две ошибки tester fixtures: substring «Награда №1» совпадал с №10…; изолированная auth-сессия не изолировала соседние cookie-based запросы бюджета U9. Исправлял tester, ожидания сохранены; targeted 4 passed/32 skipped. Helper теперь очищает router/QueryClient. Первый полный Red также сообщил timeout остановки worker; targeted Red и финальные Green завершаются штатно.

Собственные parser/presentation/mock тесты сначала падали на отсутствующих модулях. Отдельный Red на неподтверждённого преподавателя: mock rating возвращал 200 вместо GROUP_NOT_ACCESSIBLE; исправлено перед финальным Green. Browser Red ширины целей: АР 35.31 px и БУА 41.70 px при требуемых 44; исправлено токеном `--cr-touch-target` на labels.

## Проверки

| Команда | Результат |
|---|---|
| `npm --prefix frontend run check` | exit 0; lint/typecheck, 283/283 Vitest и 11/11 проверок lint-rule |
| `npm --prefix frontend run build` | exit 0, 1037 modules |
| `npx vitest run src/mocks/feed.test.ts src/features/feed/feed.acceptance.test.tsx` из frontend | exit 0, 40 passed (36 acceptance + 4 mock) |
| `node --test "tools/test/*.test.mjs"` | exit 0, 140/140 |
| `node tools/check-agent-docs.mjs` | exit 0 |
| `git diff --check` | exit 0 |
| `git diff --name-only -- backend docs/spec/api frontend/src/api frontend/src/mocks/examples.generated.ts` | пустой вывод |
| compose e2e down -v → up -d --build --wait | exit 0; все три сервиса healthy, финальная frontend-сборка |
| `npm --prefix e2e test` | exit 0, 4/4, mobile/desktop login + nginx/API |
| compose e2e down -v после прогона | exit 0, только изолированные e2e containers/volumes удалены |

Первый e2e не запустил два browser-теста из-за отсутствующего Chromium, два HTTP-теста прошли. `npx playwright install chromium` — exit 0; повторный и финальный e2e — 4/4. Проверки выполнялись по exit code самой команды, без пайпа, маскирующего отказ.

## Матрица доказательств

| Требование | Реализация | Доказательство |
|---|---|---|
| R3/R23 | SessionGate, AwardsFeed | 3 роли, guest/password guards, отсутствие запросов до допуска |
| R23/R5/R41 | AwardEntry, server snapshots + Moscow formatter | ссылки людей, +38 БУА/+50 БОА, 17:05 МСК, порядок |
| R23/KTD18 | useAwards, uniqueAwards, observer/manual retry | 120 строк, first-wins дедупликация, cursor/EOF/next-error |
| R23/screen-map | useAwardsPoll, newerAwards, showNew | плашка 2, прежние DOM nodes до нажатия, новые cursors после |
| R3/R22/KTD23 | GroupRatings, useGroupRating | non-teacher/bad id/403/404; scope и прямые адреса |
| R22 | server sort query, TeacherTableLayout | AR/BUA/BOA, snapshot balances, сохранение порядка, нет ledger |
| R22/KTD23 | BudgetPage + useAccessibleGroups | ссылка группы другого факультета, отсутствующей в budget fixtures; empty/error/retry |
| R22/R23/KTD17 | contracts.ts, feedError/feedState | malformed/negative/invalid balances/group/sort, сеть/500, независимые состояния |
| R22 | query keys + AbortSignal | поздние ответы старой группы/сортировки отменяются |
| R3/KTD23 | feed MSW + auth readUser | guest401/non-teacher403/unapproved403, scope403/unknown404 |
| R40/ADR009 | theme tokens + feed.css | browser QA, скриншоты и геометрия/контраст |

## Браузер

Явный `VITE_API_MOCKS=true` Vite dev-web; дополнительно Playwright MCP подтвердил реальные переходы из групп в рейтинг и сортировку БУА. Воспроизведение: запустить такой dev-web на свободном loopback-порту, затем `U11_WEB_URL=<url> node docs/reviews/u11-frontend/browser-qa.mjs` (переменную задавать синтаксисом своей shell).

Скрипт проходит реальные формы входа, смену временного пароля администратора, ссылки профилей, группы/рейтинг/сортировку и Tab; делает снимки 360/1280 × light/dark/contrast. Расширенный проход добавляет синтетические длинные ФИО/названия через MSW и плашку новых наград; не меняет DOM приложения для имитации результата. Reduced motion и ожидание атрибута темы стабилизируют снимки и измерения после переключения.

Артефакты: [browser-result.json](browser-result.json), [browser-qa.mjs](browser-qa.mjs). 48 снимков, 0 ошибок приложения/overflow/малых целей/нарушений контраста; минимум контраста 5.57, в high contrast 8.74. Примеры: [лента 360 light](teacher-awards-360-light.png), [рейтинг 1280 dark](rating-bua-1280-dark.png), [рейтинг 360 high contrast](rating-ar-360-contrast.png), [длинная лента](long-awards-360-contrast.png), [длинный рейтинг](long-rating-1280-dark.png).

При настройке расширенного QA прямой импорт mock worker без Vite HMR query создал второй экземпляр, поэтому runtime handler не применялся. Скрипт теперь берёт URL фактического импорта из Vite entry. Синтетический перевод времени заменён настоящим ожиданием poll; состояние темы ожидается явно, а reduced motion исключает измерение промежуточного цвета перехода. Эти исправления относятся только к QA harness.

## Границы доказательств

Реальная лента/ratings API пока planned: SQL, TeacherScope/выдачи/смена курса, транзакция подтверждения участия → проводка → feed, production auth и реальные backend данные не доказываются MSW. Клиент не подменяет backend при HTTP-ошибках. Mock рейтинга использует контрактный scope; id13 — синтетическая недоступная группа отрицательного сценария, id9999 — неизвестная. В U10 ещё нет mock-профиля 1003: переход к этому id честно завершится 404, а не вымышленными данными.

e2e проверяет существующие smoke-сценарии полного стека, U11 проверен отдельными UI acceptance/browser сценариями с явным MSW. U23 не объявляется завершённым. Независимое ревью PR ещё требуется; self-approve/merge не выполнялись.
