# U9 frontend — отчёт исполнителя

Ветка `u9-activities-frontend`, база `ccdfd5d` из PR33. Область: R3/R17–R21/R41, AE8–AE10; [план](../../spec/plans/2026-11-11-001-feat-u9-activities-frontend-plan.md). Это отчёт реализации, независимого approve ещё нет.

## Результат

Добавлены `/activities`, `/activities/new`, `/activities/:id/confirm`, `/groups`: фильтр списка, запись/отмена, создание, серверный предпросмотр и подтверждение, бюджет, статистика и курсорная история. SessionGate сохранён, роль проверяется перед feature-запросами. Часы браузера не заменяют server enrollmentOpen/confirmationOpen.

Суммы в UI приходят из preview/result, формулы есть только в fake API. Preview key включает весь состав и уровни, AbortSignal отменяет старый запрос. Confirm не повторяется автоматически; обновление всех данных через invalidation. Форма выбора сбрасывается при смене activity.id даже на прогретом кеше. Чистая функция ограничивает выбор 500 строками, parseParticipation отдельно проверяет границу запроса.

Моки используют generated examples, существующую mock-сессию и Clock, сохраняют изменения в памяти. После перезагрузки страницы mock-данные сбрасываются. Production-сборка не включает запуск MSW и использует реальный API; ошибки сервера не включают fallback.

## Команды и Red

| Проверка | Результат |
|---|---|
| Baseline frontend check до U9 | 82 Vitest + 11 lint-rule, exit 0 |
| Независимые acceptance до production | 34 Red по placeholder UI, ещё 3 целевых Red до соответствующих экранов; exit 1 |
| Чистые date/validation/parsers до production | Red, затем 30 passed |
| Stateful mock workflows до handlers | 4 Red, затем 4 passed |
| Cached navigation, default-button rerender | По одному воспроизведённому Red, после правки оба passed |
| Ограничение выбора 500/501 | Red на добавлении 501-го, затем 3 passed с проверкой тела запроса |
| `npm --prefix frontend run check` | ESLint + TypeScript exit 0; 169/169 Vitest, 11/11 lint-rule; exit 0 |
| `npm --prefix frontend run build` | 1025 modules, exit 0 |
| Mock suite после изоляции Clock | 4/4 passed, exit 0 |
| `node --test "tools/test/*.test.mjs"` | 140/140 passed, exit 0 |
| `node tools/check-agent-docs.mjs` | passed, exit 0 |
| `git diff --check` | exit 0 |

Tester написал 37 исходных acceptance и добавил 38-й: deferred PUT, double click и disabled до refetch дают один запрос. Его ожидания исполнитель не менял. Tester сам исправил свои фикстуры: исторический UTC+4 в Москве, FontFaceSet для autosize, эквивалентные ISO `Z`/`.000Z`, область поиска ошибки закрытой записи.

## Трассировка

| Критерий | Реализация и доказательство |
|---|---|
| R3: роли/гость/чужой author/invalid id | ActivityShared, router, ConfirmParticipationPage; acceptance guards |
| R17/R18: порядок, фильтры, запись и PENDING, закрытие после GET | ActivityListPage; acceptance list/enrollment и deferred PUT; student-list снимки |
| R17/R41: форма, доступные группы, границы 2/10000, UTC | CreateActivityPage, time; acceptance + time.test; create снимки |
| R19: серверный состав, SOCIAL eligibility, readonly confirmed | ConfirmParticipationPage; acceptance; confirm-success снимки |
| R19/R20/AE8: preview 75 × 50 % → API 38, гонка ответов, дефицит | queries, ConfirmParticipationPage, ActivityFailure; acceptance; confirm-deficit снимки |
| R19/AE10: studentIds, конкурирующий budget error, повтор total0 | client guard, ActivityFailure, confirm mutation; acceptance + client.test |
| R19: лимит 500/501, новый ресурс без старого выбора | selection.test + parseParticipation, navigation.test; полный UI с 501 строкой не принят как проверка |
| R21: серверный месяц, прежние группы, summary вместо суммы страниц | BudgetPage, query hooks; acceptance; budgets снимки |
| R21: cursor, dedup, reset группы/месяца, stale cancellation, retry | History, useAwardHistory; acceptance, browser «Показать ещё», декабрь без истории |
| R41: явный Europe/Moscow | time.test, даты всех экранов; форма 14:00 → сохранённое 11:00Z → показ 14:00 МСК |
| ADR009: адаптивность, три темы, клавиатура | 36 снимков, ui-metrics.json, browser Space на фильтре с видимым outline |

## Браузер и снимки

Локальный отдельный compose dev-web с **явным MSW**, порт 9190. Основной demo-стенд и backend не пересоздавались. Проверены вход студента и преподавателя, запись и отмена, фильтр клавиатурой, создание, отрицательный и положительный preview, подтверждение и readonly после refetch, остаток бюджета 12 после начисления 38, курсорная страница истории, фильтр группы и переход месяца со сбросом группы. После перезапуска Vite с финальной темой повторены запись/отмена/подтверждение/бюджет: **0 console errors**, остаётся предупреждение React Router о HydrateFallback при initial lazy route.

Снимки full-page сделаны при viewport 360×900 и 1280×900. Фиксированная нижняя навигация на full-page снимке остаётся на высоте первого viewport; при прокрутке содержимое доступно. Цвета считались после document.fonts.ready и завершения CSS-анимаций, с композициями фонов предков. Проверены видимые текстовые узлы main; исключены disabled, скрытые узлы и option. Это измерение конкретных состояний, не полный аудит U23.

| Набор | 360 light / dark / contrast | 1280 light / dark / contrast |
|---|---|---|
| Студент | [light](student-list-360-light.png) · [dark](student-list-360-dark.png) · [contrast](student-list-360-contrast.png) | [light](student-list-1280-light.png) · [dark](student-list-1280-dark.png) · [contrast](student-list-1280-contrast.png) |
| Преподаватель | [light](teacher-list-360-light.png) · [dark](teacher-list-360-dark.png) · [contrast](teacher-list-360-contrast.png) | [light](teacher-list-1280-light.png) · [dark](teacher-list-1280-dark.png) · [contrast](teacher-list-1280-contrast.png) |
| Создание | [light](create-360-light.png) · [dark](create-360-dark.png) · [contrast](create-360-contrast.png) | [light](create-1280-light.png) · [dark](create-1280-dark.png) · [contrast](create-1280-contrast.png) |
| Дефицит | [light](confirm-deficit-360-light.png) · [dark](confirm-deficit-360-dark.png) · [contrast](confirm-deficit-360-contrast.png) | [light](confirm-deficit-1280-light.png) · [dark](confirm-deficit-1280-dark.png) · [contrast](confirm-deficit-1280-contrast.png) |
| Успех | [light](confirm-success-360-light.png) · [dark](confirm-success-360-dark.png) · [contrast](confirm-success-360-contrast.png) | [light](confirm-success-1280-light.png) · [dark](confirm-success-1280-dark.png) · [contrast](confirm-success-1280-contrast.png) |
| Бюджет/история | [light](budgets-360-light.png) · [dark](budgets-360-dark.png) · [contrast](budgets-360-contrast.png) | [light](budgets-1280-light.png) · [dark](budgets-1280-dark.png) · [contrast](budgets-1280-contrast.png) |

[Измерения](ui-metrics.json): горизонтальный overflow 0/36; минимум текстового контраста light 5.57, dark 7.25, contrast 8.42; измеренные активные цели main ≥44 px и ≥52 px в contrast. Для checkbox/radio меряется связанный label, включая доступную область клика.

## Ограничения и ручной повтор

Activity-backend остаётся planned: реальная интеграция этих операций, серверная атомарность бюджета и права не проверены этим frontend PR. БД, backend, API и generated-файлы не изменены. Профили `/users/:id` пока показывают существующий placeholder следующего frontend-юнита.

Полный UI-прогон 501 строки в jsdom прерван tester после >90 s: синхронная отрисовка блокировала даже таймаут. Он удалил только свой новый тяжёлый тест; граница подтверждена чистыми функциями выбора/запроса, performance большого реального состава ещё не подтверждён. Не подменялись hooks или React state ради зелёного теста.

В dev с VITE_API_MOCKS=true войти demo-студентом или преподавателем из auth-стенда; открыть «Активности». Для преподавателя подтверждение «Хакатон факультета»: выбрать Кузнецова, 100 % показывает дефицит 25, 50 % даёт preview 38, подтверждение обновляет бюджет 950→988 и остаток 50→12. Смена месяца на 2026-12 показывает пустую историю. Мок Clock берётся из сервера разработки; для стабильного теста handlers получают фиксированный Clock.

Вспомогательные файлы: test-only helper; экспорт readUser/validCsrf из существующего auth mock; один longhand в общей теме и его rerender-тест. Новых продуктовых правил вне плана нет. Независимый reviewer должен повторить проверки на опубликованном SHA по REVIEWING.md.
