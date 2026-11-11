---
title: U11 frontend — лента наград и рейтинги групп
type: feat
date: 2026-11-11
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: docs/spec/plans/2026-10-31-001-feat-campus-rating-mvp-plan.md
execution: code
---

# U11 frontend — лента наград и рейтинги групп

## Goal Capsule

- **Цель.** Все вошедшие читают `/awards`, преподаватель открывает рейтинг доступной группы `/groups/:id/ratings`, выбирает АР/БУА/БОА и переходит в профиль студента.
- **Источники.** Мастер-U11: R3, R22, R23, KTD18, KTD23; R5/R40/R41 для ссылок, вида и времени. Контракт: listAwardsFeed, getGroupRating, listAccessibleGroups; glossary, screen-map, S5-Awards, ADR009, frontend/AGENTS.md.
- **Граница.** Только frontend, backend/API/generated/БД не меняются. Planned API — явный VITE_API_MOCKS=true; настоящий API без автоматического fallback. SQL, реальные права TeacherScope, создание проводок и транзакции доказываются будущим backend-юнитом.
- **База.** Ветка `u11-feed-frontend` от `b58d949` (U10/PR35); PR в `u10-profile-frontend`, пока родители PR33–35 открыты. На старте все OPEN, harness/backend/frontend/e2e SUCCESS. После принятия родителей — main и повтор интеграционных ворот.
- **Процесс.** implement-unit + ub-mode; отдельный разрешённый человеком tester по write-tests до production. Исполнитель не меняет acceptance. Готовый зелёный результат коммитится явными путями, пушится в свою ветку, создаётся PR; без self-approve/merge.

## Files

- `frontend/src/features/feed/`: AwardsFeed, GroupRatings, parsers, hooks, presentation, стили и собственные unit-тесты. `feed.acceptance.test.tsx` — владелец tester.
- `frontend/src/test/feedAcceptance.tsx`: test-only helper/fixtures, владелец tester.
- `frontend/src/app/router.tsx`: lazy routes awards и groups/:id/ratings внутри SessionGate.
- `frontend/src/features/activities/BudgetPage.tsx`: рядом с бюджетом отдельный список доступных групп со ссылками на рейтинги и ссылка на награды; не выводить доступ из бюджета. Переиспользовать useAccessibleGroups.
- `frontend/src/mocks/feed.ts`, `feed.test.ts`, `handlers.ts`: контекстные MSW из contract examples, auth через readUser. Существующие activity mocks не расширять на проводки.
- План, `docs/reviews/u11-frontend/`, `docs/agent-log.md`, уроки frontend/AGENTS.md. Вспомогательная mock smoke-проверка handlers.test.ts может потребовать настоящего mock login, если старый тест читал награды без сессии; меняет tester.

## Наблюдаемое поведение и границы

1. **Доступ.** SessionGate/password guard работают до feature-запросов. Лента — STUDENT/TEACHER/ADMIN. Рейтинги — только TEACHER; прочим «Нет доступа» без rating/groups запросов. id — положительное безопасное целое; abc/0/negative/unsafe — «Группа не найдена» без rating-запроса. Backend остаётся источником прав: прямой URL к недоступной группе обрабатывает 403 GROUP_NOT_ACCESSIBLE, 404 — «Группа не найдена».
2. **Награда.** Студент со ссылкой /users/:id, положительная знаковая сумма с русской валютой, название активности текстом, преподаватель со ссылкой /users/:id, дата по Europe/Moscow с МСК. Сохранять серверный порядок, не сортировать и не получать дополнительные профили/активности. Пример: Кузнецов, +38 БУА, Олимпиада по программированию, Петров, 03.11.2026 17:05 МСК. Схема feed уже содержит только ACTIVITY_AWARD, отрицательные/нулевые суммы считаются malformed; клиент не строит ленту из ledger.
3. **Курсор.** useInfiniteQuery, cursor только из nextCursor, без OFFSET; key содержит viewer. IntersectionObserver + доступная кнопка «Показать ещё», дедупликация entryId с сохранением первого появления. 120 записей/дубли на границах — каждая ровно один раз. Следующая ошибка сохраняет видимые строки и предлагает повторить страницу; без автоматического цикла повторов observer. Конечная страница не делает лишних запросов.
4. **Новые награды.** Отдельный query первой страницы каждые 30 s при активном окне (технический интервал), без refetch всего читаемого infinite-list при focus. Более новые относительно первого загруженного (createdAt,id) элементы показываются плашкой-кнопкой «Новые награды: N — показать». До нажатия DOM списка не меняется и позиция чтения сохраняется. После нажатия лента начинается с новой первой страницы, устаревшие cursors удаляются; следующие страницы снова идут по её курсору. Ошибка фонового опроса не скрывает список, доступен повтор. Не объявлять N полным числом за пределами полученной первой страницы.
5. **Рейтинг.** Сервер возвращает полный список ACTIVE-членов и snapshot balances. АР (default), БУА, БОА переключают sort query; запросы отменяются AbortSignal, key содержит viewer/group/sort, поздний ответ не смешивается с новым. Порядок сервера: выбранная валюта desc, ФИО asc, id asc; клиент ничего не суммирует и не переупорядочивает. Заголовок с именем и факультетом группы, таблица «Студент / АР / БУА / БОА», имена ведут в профиль. Список групп берётся из /me/accessible-groups, включая группы иных факультетов, открытые сервером; не повторять вычисления выдач и курса на клиенте.
6. **Состояния.** Loading/empty/error+retry для feed/rating/accessible-groups. Сеть/500/malformed — понятная ошибка, не пустой список. Ошибка смены sort не показывает старую сортировку как новую. 403 — «Нет доступа», 404 — «Группа не найдена». Фоновая и следующая ошибки независимы от первой загрузки.
7. **Входной парсинг.** Типы из generated schema; обязательные поля/ref, id positive safe integer, amount positive safe integer (int32), enum Currency, UTC ISO, nextCursor string|null; balances AR 0…1000, BUA/BOA >=0 safe integers. Проверять соответствие group.id и sort запросу, не принимать чужую группу под текущим заголовком. Не подменять malformed нулями/unsafe cast. Серверный snapshot — единственный источник балансов.
8. **Вид.** Общие Mantine layouts/токены «Редакции», три темы, 360/1280 px без overflow, длинные поля переносятся; focus и клавиатура, цели >=44/52 px, контраст >=4.5/7. U23 не объявляется завершённым.
9. **MSW.** Примеры listAwardsFeed/getGroupRating/listAccessibleGroups, auth readUser, детерминированные cursors и сортировка mock rating по desc валюте/ФИО/id. Guest 401, non-teacher 403, группа вне возвращаемого scope 403; неизвестная группа 404. Без второго auth state. Моки демонстрируют награды и рейтинги, не симулируют backend-транзакцию подтверждения U9. Настоящие profile mock записи для отсутствующего в U10 id1003 не добавлять: переход доказывается маршрутом, а неизвестный профиль честно показывает 404.

## Цепочка требований и приёмка

| Требование | Пример/граница | Критерий и проверка |
|---|---|---|
| R3/R23 | guest/password guard; 3 роли | нет запросов до допуска; доступ ленты; acceptance |
| R23/R5/R41 | +38 БУА/+50 БОА, имена/источник/Москва | snapshot-текст, ссылки, порядок; acceptance |
| R23/KTD18 | 120 строк, дубли, cursor null, page500→retry | без дублей/потери строк, правильный cursor; acceptance |
| R23/screen-map | poll приносит 2 новых; отказ опроса | плашка без сдвига, применение/сброс cursor, retry; acceptance |
| R3/R22/KTD23 | student/admin, bad id, 403, 404 | запреты и объяснения, нет недопустимых запросов; acceptance |
| R22/KTD23 | доступная группа другого факультета | ссылка из серверного scope и рейтинг; acceptance |
| R22 | AR940/940, BUA120/38, BOA50/0 | sort param, стабильный порядок сервера, все balances; acceptance + mock unit |
| R22 | sort/group смена и поздний ответ | отмена/изоляция запросов; acceptance |
| R22/R23/KTD17 | loading/empty/network500/malformed | независимые состояния и retry; acceptance + parser unit |
| R40/ADR009 | 360/1280 ×3 темы, long text, keyboard | browser screenshots/метрики/сценарии |
| Граница | backend/API/generated unchanged | git diff; реальный SQL/доступ не проверен этим frontend-юнитом |

## Порядок и Verification Contract

1. Tester пишет acceptance через реальные routes + MSW + изолированный QueryClient + CampusThemeProvider/Mantine test env. Red по placeholder/отсутствующему поведению; без импорта будущих production файлов. В отчёте тестировщика — сценарий→тест и red. Исполнитель начинает production после этого.
2. Собственные parser unit Red → parsers/hooks → feed/poll → rating/links → контекстный MSW. Acceptance меняет только tester по обнаруженной ошибке fixture, ожидания из требований не ослаблять.
3. `npm --prefix frontend run check`, `npm --prefix frontend run build`; `node --test "tools/test/*.test.mjs"`; `node tools/check-agent-docs.mjs`; `git diff --check`; пустой diff backend/API/generated.
4. Проверить доступность Docker, локальный compose/e2e по мастер-плану если engine доступен. Для изолированного frontend разрешённый явный MSW dev-web, при недоступном Docker — прямой Vite, ограничение записать. Открыть feed/rating/groups на 360/1280 во всех темах; переходы, keyboard, long fields, контраст/цели/focus/overflow. Скриншоты в docs/reviews/u11-frontend/.
5. Verification/handoff с реальными командами/rc, матрицей и непроверенным backend; agent-log и уроки в этой ветке. Зелёный коммит, сверка git show --stat, push/PR в U10. Независимое ревью выполняет человек/отдельный reviewer; исполнитель не approve/merge.
