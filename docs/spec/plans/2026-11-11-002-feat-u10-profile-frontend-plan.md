---
title: U10 frontend — профиль и история
type: feat
date: 2026-11-11
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: docs/spec/plans/2026-10-31-001-feat-campus-rating-mvp-plan.md
execution: code
---

# U10 frontend — профиль и история

## Goal Capsule

- **Цель.** Ссылки `/users/:id` открывают профиль пользователя: студент — балансы, история проводок и задачи; преподаватель — кафедра и факультет; администратор — ФИО и роль.
- **Источники.** Мастер-U10: R3, R5, R6, R40, R41, KTD18; `docs/spec/api/openapi.yaml`: getUserProfile, listLedgerEntries, UserProfile/ProfileTask/LedgerEntryView; glossary; screen-map и S1-Profile; ADR009 и frontend/AGENTS.md.
- **Граница.** Только frontend. Backend/API/generated/БД не меняются. Операции profile/ledger planned; явный MSW для разработки, production вызывает настоящий API без fallback по HTTP-ошибке. Задачи и админские материалы за пределами юнита, здесь только предусмотренные контрактом ссылки.
- **База.** `u10-profile-frontend` от `04ee40a` (PR34). Пока родитель открыт, будущий PR базируется на `u9-activities-frontend`; после принятия родителей — main и повтор интеграционных проверок. Коммит/пуш только зелёного результата по сохранённому поручению человека; Red-подготовка не публикуется как готовая реализация.
- **Процесс.** implement-unit + ub-mode. Отдельный tester с write-tests пишет acceptance до production. Человек разрешил tester для frontend без дополнительных вопросов. Исполнитель не редактирует его ожидания. После первоначальной подготовки при остатке лимита человек попросил продолжать: теперь реализация и все ворота до публикации.

## Files

- `frontend/src/features/profile/`: ProfilePage, balances/tasks/history UI, query hooks, входные parsers, форматирование истории, стили, собственные тесты. `profile.acceptance.test.tsx` — только tester.
- `frontend/src/app/router.tsx`: один lazy-route users/:id внутри SessionGate; каркас/прочие экраны не менять.
- `frontend/src/mocks/profile.ts`, `profile.test.ts`, `handlers.ts`: контекстные профили и курсорная история из generated examples, без второго auth-state; использовать существующий readUser из mocks/auth.ts.
- Вспомогательный `frontend/src/mocks/handlers.test.ts`: старый smoke чтения PUBLIC example должен выполнить настоящий mock login преподавателя, поскольку профиль доступен вошедшим; сравнение с исходным generated example сохраняется, cookies очищаются после теста. Правку делает tester.
- `frontend/src/test/profileAcceptance.tsx`: test-only MSW/QueryClient/реальные routes, как существующие auth/activities helpers; владелец tester.
- План, `docs/reviews/u10-frontend/`, журнал и уроки frontend/AGENTS.md. Допустим вспомогательный общий time formatter только если необходим; текущий Europe/Moscow formatter из activities можно переиспользовать без правки.

## Наблюдаемое поведение и границы

1. **Доступ и адрес (R3/R5).** Любой вошедший видит свой/чужой профиль всех ролей. Гость проходит существующий login guard, mustChangePassword — существующий password guard; не вызывать profile/ledger до доступа. Положительный безопасный целый id; abc/0/отрицательный/unsafe id — «Пользователь не найден» без feature-запросов. API 404 — такой же понятный результат, 403 — «Нет доступа», сеть/500/malformed — ошибка с retry, не фиктивный пустой профиль.
2. **Заголовок/балансы (R5).** ФИО из API, для студента — группа и факультет, карточки АР/БУА/БОА из balances (например 940/120/50). АР показывать «из 1000»; ничего не пересчитывать суммой истории. Для преподавателя — ФИО, кафедра, факультет и ссылка «Лента наград» на /awards. Для администратора — ФИО и роль; нет вымышленных студенческих балансов/задач. Для teacher/admin ledger не запрашивать.
3. **Вкладки (S1/R5).** У студента «История» (по умолчанию) и «Задачи», доступные клавиатурой. Задачи разделены на «Активные задачи» и «Завершённые задачи» по двум массивам сервера, с сохранением его порядка; локально не пересчитывать статус по Date. OVERDUE/IN_PROGRESS/SUBMITTED/ACCEPTED — русские статусы из glossary. Название, дедлайн по Москве и «Списано N АР» видны у каждой задачи; пустота каждого списка объясняется отдельно.
4. **Переходы и приватность (R3/R5).** PUBLIC — статичная строка без ссылки на сдачу, даже при ошибочно присланном assignmentId. OWN + viewer.id===profile.id — название ведёт /tasks/:assignmentId. ADMIN + viewer.role===ADMIN — «Материалы сдачи» ведёт /admin/submissions/:assignmentId. В чужом профиле не создавать OWN-ссылку; не создавать ADMIN-ссылку непривилегированному зрителю. Никакие URL сдачи, file или returnComment из лишних внешних полей не рендерятся. Backend остаётся источником настоящей авторизации ресурсов.
5. **История (R6/R41).** `[Дата] · [+50 БОА] · «Помощь в организации конференции» от [ФИО преподавателя]`; сумма со знаком и русской валютой, отрицательная −20 АР. PENALTY поясняет «Просрочка», source.title виден, author=null — «Система». INITIAL — «Стартовые баллы», SEMESTER_ACCRUAL — начисление семестра с source.title. Не перезапрашивать текущие названия/ФИО вместо снимка истории; имя автора со ссылкой /users/:id. Все даты Europe/Moscow с явной подписью МСК. Источники показывать текстом, без неподтверждённых маршрутов к чужим ресурсам.
6. **Фильтр и пагинация (R6/KTD18).** «Все / АР / БУА / БОА» меняет currency query param (для All отсутствует), query key включает viewer/id/currency, курсор начинается заново. Смена id/валюты отменяет старый запрос через AbortSignal, поздний ответ не смешивается с новым списком. Сохранять порядок сервера, дедуплицировать id при склейке. IntersectionObserver подгружает nextCursor, «Показать ещё» — клавиатурная альтернатива. Следующая страница не скрывает уже загруженные строки; её ошибка показывается рядом с повтором страницы. Основные loading/empty/error/retry раздельны от профиля. На 120 записях все id показаны ровно один раз; summary баланса не выводить из страниц.
7. **Входная проверка.** Discriminated union профиля/task.view из generated schema. Проверять id, role/enums, обязательные поля, целые балансы (АР 0…1000, БУА/БОА ≥0), суммы/charged, UTC ISO timestamps, source/author nullable, cursor string|null. Ledger amount — безопасное целое, не 0, знак и валюта сохраняются; все EntryKind берутся из контракта. Не подавлять malformed через unsafe cast или нули. Не полагаться на роль зрителя для определения роли владельца.
8. **Вид (R40/ADR009).** Тема «Редакция», только токены и общие layouts, три темы; min 360 px без overflow, active targets ≥44/52, текст ≥4.5/7, видимый focus. Длинные ФИО, кафедры, названия и отрицательные суммы переносятся. Не расширять тему и не объявлять U23 выполненным.
9. **Моки.** Generated examples: studentPublic/Own/ByAdmin, teacher/admin и LedgerEntryPage. Выбирать профиль по route id и view по mock viewer; неизвестный id — 404, гость —401. Поддержать существующие ссылки на студентов 1001/1002 из U9 (второй — синтетический пользователь по данным contract roster). currency фильтруется на fake API, cursor детерминирован, nextCursor исчезает на последней странице; у teacher/admin история пустая. Изменений activity mock-state в этом юните не требуется.

## Матрица приёмки

| Требование | Пример/граница | Доказательство |
|---|---|---|
| R3/R5 | guest, временный пароль, abc/0/unsafe id | Guard/нет feature-запросов; acceptance |
| R5 | own/public student 940/120/50 | ФИО/группа/факультет/балансы, страницы не суммируются; acceptance |
| R5 | teacher/admin profile | Верные реквизиты, ссылка /awards у teacher, нет ledger-запроса; acceptance |
| R5 | active OVERDUE/SUBMITTED, completed ACCEPTED, пустой массив | Раздельные списки, статус/Москва/штраф, порядок сервера; acceptance |
| R3/R5 | PUBLIC; OWN у владельца; ADMIN у администратора | Верные переходы или их отсутствие; acceptance |
| R3/R5 | чужой OWN/не-admin ADMIN, лишние file/link/returnComment | Приватные элементы/переходы не появляются; acceptance |
| R6/R41 | award+50 BOA, penalty−20 AR author=null, INITIAL+1000, semester | Знак, валюта, source snapshot, автор/Система, Москва; acceptance + formatter unit |
| R6/KTD18 | 120 строк/несколько cursors, дубль на границе | Ровно 120 уникальных строк, остаток баланса 940; acceptance |
| R6/KTD18 | currency BUA→BOA, id1001→1002, задержанный старый ответ | Query params/новый cursor, отмена/нет смешения; acceptance |
| R5/R6 | loading/empty/500→retry, 404/403, malformed | Понятные независимые состояния; acceptance + parser unit |
| R40/ADR009 | 360/1280 × 3 темы, длинные поля, keyboard | Запущенный web, сценарии и скриншоты/измерения |
| Регрессия | auth/U9/styleguide, generated | frontend check/build, tools и пустой backend/generated diff |

## Порядок и ворота

1. Tester: реальные routes, MSW, изолированный QueryClient, CampusThemeProvider + Mantine test env. RED до production должен быть по placeholder/отсутствию поведения, не из-за импорта будущих файлов. Локальный запуск из frontend, без тяжёлых DOM stress-тестов на тысячи контролов. Ошибка fixture — исправляет tester.
2. Исполнитель: parsers/formatter с unit Red → profile/roles → tasks privacy → ledger filter/cursor/error → контекстные mocks. Acceptance не менять.
3. `npm --prefix frontend run check`, затем build; `node --test "tools/test/*.test.mjs"`; `node tools/check-agent-docs.mjs`; diff --check, отсутствие backend/API/generated изменений.
4. Явный MSW dev-web в локальном compose; если Docker engine недоступен — тот же frontend напрямую через Vite с VITE_API_MOCKS=true. Own/foreign student, teacher/admin; фильтры/пагинация/переходы. Скриншоты на 360/1280 во всех темах; контраст/цели/focus/overflow; реальные backend операции пока не заявлять.
5. Отчёт с командами/rc, матрицей и ограничениями, журнал/уроки в этой ветке. Только после зелёных ворот — коммит явных путей, сверка stat, push и PR. Независимая приёмка отдельным reviewer/человеком.
