---
title: U9–U11 Backend — Activities, Profile, Feed - Plan
type: feat
date: 2026-11-11
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: docs/spec/plans/2026-10-31-001-feat-campus-rating-mvp-plan.md
origin: docs/spec/plans/2026-10-31-001-feat-campus-rating-mvp-plan.md
execution: code
---

# U9–U11 Backend: активности и бюджеты, профиль и история, лента и рейтинги - Plan

## Goal Capsule

- **Цель.** Серверная часть мастер-U9, U10 и U11: 15 операций контракта в статусе `planned` переходят в `implemented`, экраны PR #34–#36 работают против настоящего backend, а не MSW.
- **Иерархия источников.** Контракт `docs/spec/api/openapi.yaml` (операции тегов `activity`, `profile`, `feed`, `listLedgerEntries`) — главный источник поведения и кодов ошибок; мастер-план (U9–U11; R3, R5, R6, R17–R23, R41; AE8–AE10; KTD3, KTD5, KTD18, KTD23); `docs/spec/api/module-seams.md` (правила, протокол транзакции начисления, швы 0–6). Здесь П, KTD и U — локальные, ссылки на мастер помечены «мастер-».
- **Профиль исполнения.** Каждый юнит пишет агент-исполнитель test-first; ревьюер гоняет `*IT` с Docker и проверку контракта. Контракт и швы не меняются.
- **Остановись и спроси, если:**
  - нужно изменить контракт или сигнатуру `module-seams.md`;
  - тест зеленеет только ценой нарушения порядка блокировок (баланс раньше бюджета) или записи мимо `LedgerApi`;
  - модулю нужна зависимость вне диаграммы швов (`activity` → `directory`, `feed` → `activity` и т. п.).
- **Ветки и PR.**
  - `u9a-backend-plan` — этот план.
  - `u9-activities` — U1–U3, от `main`.
  - `u11-feed` — U5, от `main`, независим от U9: данные ленты — проводки `ACTIVITY_AWARD`, тест пишет их через `LedgerApi`.
  - `u10-profile` — U4, после слияния PR U7 (#26–#29): нужен шов `StudentAssignments`; до слияния — стопкой на `u7-reading`.

---

## Product Contract

### Summary

Модуль `activity`: активности, запись на `SOCIAL`, подтверждение участия с расчётом начисления и месячным бюджетом пары (преподаватель, группа), история и статистика начислений преподавателя. Модуль `profile`: профиль по роли владельца и представлению смотрящего. Модуль `ledger`: контроллер истории проводок. Модуль `feed`: публичная лента наград и рейтинг группы из снимка балансов.

### Правила

| № | Правило | Источник |
|---|---|---|
| П1 | Начисление — `round_half_up(maxPoints × level / 100)`; `ACADEMIC` → БУА, `SOCIAL` → БОА; `maxPoints` 2…10000 | мастер-R17, R19, AE9 |
| П2 | Запись и отмена — только `SOCIAL` и строго раньше `startsAt`; в момент начала и позже — `422 ENROLLMENT_CLOSED`; `ACADEMIC` — `409 INVALID_STATE`; запись требует `ACTIVE`-членства (`422 MEMBERSHIP_NOT_ACTIVE`), отмена — нет; повтор ничего не меняет | мастер-R18, контракт |
| П3 | Подтверждение — только автор и только при `now >= startsAt`, иначе `409 INVALID_STATE`; участники `SOCIAL` — записавшиеся, `ACADEMIC` — любые `ACTIVE`-студенты целевых групп | мастер-R19, AE10 |
| П4 | Не больше одного начисления на (активность, студент): повтор с тем же уровнем — в `alreadyConfirmed`, без проводки и расхода бюджета; с другим уровнем — весь запрос `409 ALREADY_CONFIRMED` | мастер-R19, контракт |
| П5 | Бюджет — лимит `SettingsApi.monthlyBudgetLimit()` на (преподаватель, группа, месяц по Москве), общий для БУА и БОА; превышение остатка хотя бы одной группы — весь запрос `422 BUDGET_EXCEEDED` с нехваткой по группам; ровно на остаток — проходит | мастер-R20, AE8 |
| П6 | Группа начисления — группа студента на момент подтверждения; сумма начислений преподавателя по группе за месяц равна расходу её бюджета | мастер-R21, контракт `getMyAwardsSummary` |
| П7 | Доступ к своим ресурсам после потери доступа к группе сохраняется: активности, участники, подтверждение, бюджет групп своих активностей, история начислений | мастер-KTD23, швы §3 |
| П8 | Профиль виден любому вошедшему; задача в профиле — `OWN` себе, `ADMIN` администратору, иначе `PUBLIC` без `assignmentId`; ссылок, файлов и комментариев возврата в профиле нет никогда | мастер-R5, контракт |
| П9 | История проводок — снимок источника и автора из журнала; автор `null` — система | мастер-R6, швы §1 |
| П10 | Лента — только `ACTIVITY_AWARD`, новые сверху; ФИО студента — текущее из `identity`, автора — снимок | мастер-R23, швы §1 |
| П11 | Рейтинг — `ACTIVE`-студенты доступной группы, балансы из снимка без `SUM` по журналу, по убыванию выбранной валюты, затем ФИО, затем id | мастер-R22, KTD3 |

### Не входит

- Экраны — PR #34–#36 (отдельная дорожка).
- Правка лимита бюджета и семестров — администрирование.
- Отмена подтверждения и сторно начисления — вне MVP (мастер «Scope Boundaries»).

---

## Planning Contract

### Key Technical Decisions

- KTD1. **Схема `activity`** (changeset `db/changelog/activity/<ts>-activity.sql`):
  - `activity(id, author_id → app_user, title, description, type, starts_at, max_points, created_at)`;
  - `activity_group(activity_id, group_id → study_group, primary key (activity_id, group_id))`;
  - `activity_enrollment(activity_id, student_id, enrolled_at, primary key (activity_id, student_id))`;
  - `activity_award(id bigserial, activity_id, student_id, group_id, level, amount, currency, author_id, confirmed_at, ledger_entry_id, unique (activity_id, student_id))`, индекс `(author_id, confirmed_at desc, id desc)`;
  - `activity_budget(id bigserial, teacher_id, group_id, month date, spent int ≥ 0, unique (teacher_id, group_id, month))`.
  Лимит в строке бюджета не хранится: он читается из `SettingsApi` при каждой проверке, остаток — `max(0, limit − spent)`.
- KTD2. **Домен — чистые функции в `activity.domain`.** `ParticipationLevel` (25/50/75/100), `AwardCalculator` (П1), `BudgetPolicy` (нужно по группам, остатки → `BudgetCheck` с `shortfall`, «в пределах» или нет), `ActivityWindow` (запись открыта, подтверждение открыто по `startsAt` и `now`). PIT по `activity.domain` ≥ 70 %.
- KTD3. **Транзакция подтверждения** по протоколу швов:
  1. `SELECT … FOR UPDATE` строки `activity` — сериализует подтверждения одной активности;
  2. проверки состава в порядке: повторы `studentId` (`400`) → время (`409 INVALID_STATE`) → уже подтверждённые с другим уровнем (`409 ALREADY_CONFIRMED`) → допуск (`422 STUDENT_NOT_ELIGIBLE`) → запись у `SOCIAL` (`422 NOT_ENROLLED`); у каждой ошибки — полный список `studentIds`;
  3. отбросить уже подтверждённых с тем же уровнем, сгруппировать новые по группе студента;
  4. вставить недостающие строки бюджета `ON CONFLICT DO NOTHING`, затем `SELECT … FOR UPDATE` по возрастанию `group_id`; проверить остатки (`BudgetPolicy`);
  5. `spent += need`, вставка `activity_award`;
  6. последним — `LedgerApi.post` одной пачкой по возрастанию `studentId`: `ACTIVITY_AWARD`, ключ `award:{activityId}:{studentId}`, источник `ACTIVITY` с id и названием, автор — id и ФИО преподавателя; `entryId` из `Posted` — в `activity_award.ledger_entry_id`.
  Предпросмотр выполняет шаги 2–4 без `FOR UPDATE` и без записи; нехватка — `withinBudget: false`, а не ошибка.
- KTD4. **Месяц** — `MoscowTime` от `Clock`: и для бюджета подтверждения, и для фильтров `/budgets`, `/me/awards`, `/me/awards/summary`.
- KTD5. **Курсоры** — непрозрачные строки по образцу `LedgerCursor`: `/me/awards` — `(confirmed_at desc, id desc)` с группой и месяцем внутри курсора; чужой фильтр или битый курсор — `400` по полю `cursor`.
- KTD6. **Профиль** собирает `profile.internal.ProfileService` из `GroupMembers.card`, `GroupMembers.groups`, `DirectoryApi.department`, `LedgerApi.balances`, `StudentAssignments.active/completed`; своих таблиц у `profile` нет. Представление задачи выбирается по `CurrentUser` (П8).
- KTD7. **История** — `ledger/api/LedgerEntriesController` поверх `LedgerApi.history`; существование пользователя не проверяет (швы §1): неизвестный id — пустая страница.
- KTD8. **Лента и рейтинг** — `feed.internal`: лента — `LedgerApi.awards` + `GroupMembers.users` по `studentId` одной пачкой на страницу; рейтинг — `TeacherScope.requireAccessible`, `GroupMembers.activeStudents`, `LedgerApi.balances(ids)` одной пачкой, сортировка в памяти (П11). Тест рейтинга доказывает отсутствие `SUM` по журналу: счётчик запросов к `ledger_entry` или проверка SQL.
- KTD9. **Контракт.** У каждой реализованной операции `x-status: implemented`; `ContractCompatibilityIT` проходит. Расхождения генератора схемы (как `anyOf` с `null` в U7) закрываются конвертером в тестовой поддержке, не правкой контракта.

### Open Questions

- Q1. **Лимит снижен ниже уже потраченного.** Остаток показывается как `0`, а не отрицательным (`max(0, limit − spent)`), любое новое начисление в эту группу в этом месяце отклоняется. Предложение — так и оставить; правка лимита — только администрированием.

### Risks and Dependencies

| Риск | Митигация |
|---|---|
| Взаимная блокировка двух подтверждений разных активностей одного преподавателя | KTD3: строки бюджета по возрастанию `group_id`, баланс последним; IT с барьером |
| Двойное начисление при параллельных повторах | `FOR UPDATE` активности, `unique (activity_id, student_id)`, ключ идемпотентности журнала; IT с двумя потоками |
| Граница месяца по Москве | `MutableClock` в IT: 31.10 20:59:59Z — октябрь, 21:00:00Z — ноябрь |
| U10 ждёт шов `StudentAssignments` из PR U7 | ветка `u10-profile` стопкой на `u7-reading`; retarget на `main` после слияния U7 |
| Фоновые задания штрафа и семестра в тестах | `campus.*.scheduler.enabled=false` в тестовых свойствах (PR U8) |

---

## Implementation Units

### U1. Домен и схема `activity`

- **Goal.** Расчёт начисления, окно записи и подтверждения, проверка бюджета — чистыми функциями; таблицы KTD1.
- **Requirements.** П1, П2, П5; KTD1, KTD2.
- **Files:** `backend/src/main/java/ru/campusrating/activity/domain/{ParticipationLevel,AwardCalculator,BudgetPolicy,ActivityWindow}.java`, `backend/src/main/resources/db/changelog/activity/<ts>-activity.sql`; `backend/src/test/java/ru/campusrating/activity/domain/{AwardCalculatorTest,BudgetPolicyTest,ActivityWindowTest}.java`.
- **Test scenarios:**
  - Covers мастер-AE9. 75 × 50 % = 38; 100 × 25 % = 25; 2 × 25 % = 1.
  - Ровно на остаток — в пределах; на 1 больше — нехватка 1 по этой группе; две группы — нехватка только у той, где не хватает.
  - Остаток при `spent > limit` — 0 (Q1).
  - Запись открыта при `now < startsAt` и только у `SOCIAL`; подтверждение открыто при `now >= startsAt`.

### U2. Активности и запись

- **Goal.** `createActivity`, `listActivities`, `getActivity`, `enrollInActivity`, `cancelEnrollment` по контракту.
- **Requirements.** П2, П7; мастер-R17, R18, KTD23.
- **Dependencies.** U1.
- **Files:** `backend/src/main/java/ru/campusrating/activity/api/ActivityController.java`, `backend/src/main/java/ru/campusrating/activity/internal/{ActivityService,ActivityRepository}.java`; `backend/src/test/java/ru/campusrating/activity/ActivityIT.java`.
- **Test scenarios:**
  - Создание: максимум 1 — `400` по `maxPoints`; время начала в прошлом — `400`; группа вне доступных — `403 GROUP_NOT_ACCESSIBLE`; преподаватель без допуска — `403`.
  - Список студента: только активности его группы, будущие по возрастанию начала, затем прошедшие от недавних; `enrolled` и `myAward` — его; студент с `PENDING` видит.
  - Список преподавателя — только свои, в том числе после снятия доступа к группе.
  - `getActivity` студентом другой группы — `403`; несуществующая — `404`.
  - Запись: `SOCIAL` до начала — успех, повтор ничего не меняет; ровно в момент начала и после — `422 ENROLLMENT_CLOSED`; `ACADEMIC` — `409 INVALID_STATE`; `PENDING` — `422 MEMBERSHIP_NOT_ACTIVE`; студент другой группы — `403`.
  - Отмена до начала — успех, без записи — ничего не меняет; в момент начала — `422`.

### U3. Подтверждение участия, бюджет и начисления преподавателя

- **Goal.** `listActivityParticipants`, `previewParticipation`, `confirmParticipation`, `listBudgets`, `listMyAwards`, `getMyAwardsSummary`.
- **Requirements.** П3–П7; KTD3–KTD5; мастер-AE8, AE10.
- **Dependencies.** U2.
- **Files:** `backend/src/main/java/ru/campusrating/activity/api/{ParticipationController,BudgetController}.java`, `backend/src/main/java/ru/campusrating/activity/internal/{ParticipationService,BudgetRepository,AwardRepository,AwardCursor}.java`; `backend/src/test/java/ru/campusrating/activity/{ParticipationIT,BudgetIT}.java`.
- **Test scenarios:**
  - Участники `SOCIAL` — записавшиеся, `ACADEMIC` — `ACTIVE`-студенты целевых групп; по группе, затем ФИО; у подтверждённых — уровень и сумма; не автор — `403`.
  - Подтверждение до начала — `409 INVALID_STATE`; повтор `studentId` — `400`.
  - Covers мастер-AE10. Незаписанный на `SOCIAL` — `422 NOT_ENROLLED`; на `ACADEMIC` — начисление; `PENDING` или студент чужой группы — `422 STUDENT_NOT_ELIGIBLE`.
  - Covers мастер-AE8. Превышение остатка одной из двух групп — `422 BUDGET_EXCEEDED` с нехваткой этой группы, проводок, строк `activity_award` и расхода нет.
  - Covers мастер-AE8. Граница месяца: подтверждение в 31.10 20:59:59Z расходует октябрь, в 21:00:00Z — ноябрь; в новом месяце остаток снова равен лимиту.
  - `ACADEMIC` и `SOCIAL` расходуют один бюджет группы.
  - Повтор того же запроса — всё в `alreadyConfirmed`, баланс и расход не меняются; повтор с другим уровнем — `409 ALREADY_CONFIRMED` со `studentIds`.
  - Два параллельных одинаковых подтверждения — одна проводка на студента и один расход.
  - Два параллельных подтверждения разных активностей одного преподавателя для одной группы не превышают бюджет и не дают взаимной блокировки.
  - Предпросмотр: те же ошибки состава; нехватка — `withinBudget: false` и `shortfall`; ничего не записано.
  - Начисление видно в `LedgerApi.history` студента с автором-преподавателем и названием активности; `reconcile` без расхождений.
  - `/budgets` — доступные группы и группы своих активностей; месяц по умолчанию — текущий по Москве.
  - `/me/awards` — только свои, новые сверху, курсор без дублей; фильтр группы и месяца; чужой фильтр в курсоре — `400`.
  - `/me/awards/summary` — сумма по группе равна `spent` её бюджета за месяц.

### U4. Профиль и история (мастер-U10)

- **Goal.** `getUserProfile` и `listLedgerEntries`.
- **Requirements.** П8, П9; KTD6, KTD7; мастер-R5, R6.
- **Dependencies.** шов `StudentAssignments` (PR U7).
- **Files:** `backend/src/main/java/ru/campusrating/profile/api/ProfileController.java`, `backend/src/main/java/ru/campusrating/profile/internal/ProfileService.java`, `backend/src/main/java/ru/campusrating/ledger/api/LedgerEntriesController.java`; `backend/src/test/java/ru/campusrating/profile/ProfileIT.java`, `backend/src/test/java/ru/campusrating/ledger/LedgerEntriesIT.java`.
- **Test scenarios:**
  - Профиль студента: ФИО, группа и факультет, балансы, активные (`OVERDUE` первыми) и завершённые задачи.
  - Чужой профиль — `PUBLIC` без `assignmentId`; свой — `OWN`; администратор — `ADMIN`; ни в одном представлении нет ссылки, файла и комментария возврата.
  - Профиль преподавателя — кафедра и факультет; администратора — ФИО и роль; несуществующий — `404`; гость — `401`.
  - История: начисление с автором-преподавателем, штраф с источником-точкой и автором `null`; фильтр валюты; пагинация на 120 записях без дублей и потерь; неизвестный id — пустая страница; битый курсор — `400`.

### U5. Лента наград и рейтинг группы (мастер-U11)

- **Goal.** `listAwardsFeed` и `getGroupRating`.
- **Requirements.** П10, П11; KTD8; мастер-R22, R23.
- **Files:** `backend/src/main/java/ru/campusrating/feed/api/{AwardsFeedController,GroupRatingsController}.java`, `backend/src/main/java/ru/campusrating/feed/internal/{AwardsFeedService,GroupRatingService}.java`; `backend/src/test/java/ru/campusrating/feed/{AwardsFeedIT,GroupRatingsIT}.java`.
- **Test scenarios:**
  - Новое начисление первым; штраф, семестр и стартовые баллы в ленте не появляются.
  - Курсор грузит следующую страницу без дублей; ФИО студента — текущее, автора — снимок.
  - Рейтинг по АР, БУА и БОА; при равенстве — по ФИО, затем id; `PENDING`-студента нет.
  - Группа вне доступных — `403 GROUP_NOT_ACCESSIBLE`; группа другого факультета, открытая выдачей, — доступна; несуществующая — `404`.
  - Рейтинг не читает `ledger_entry` (KTD8).

---

## Verification Contract

| Ворота | Команда | Когда |
|---|---|---|
| Backend | `./backend/mvnw -f backend/pom.xml verify` (с `ContractCompatibilityIT` и `ModularityTest`) | каждый PR |
| Мутации и CRAP | `./backend/mvnw -f backend/pom.xml -Pquality verify`, `node tools/crap.mjs backend/target/site/jacoco/jacoco.xml` | перед PR |
| Обвязка | `node tools/check-agent-docs.mjs`, `node --test "tools/test/*.test.mjs"` | каждый PR |
| Стек | `docker compose up` в demo, экраны PR #34–#36 без MSW | после U3, U4, U5 |

## Definition of Done

- Сценарии U1–U5 зелёные; PIT `activity.domain` ≥ 70 %, CRAP ≤ 10, JaCoCo ≥ 80 % строк по затронутым модулям.
- 15 операций в контракте — `implemented`, `ContractCompatibilityIT` зелёный.
- Демо: преподаватель видит остаток бюджета до и после подтверждения; лента показывает начисление; профиль на демо-данных собирается.
- Q1 решён; запись в `docs/agent-log.md`.
