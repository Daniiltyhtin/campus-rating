---
title: U14 Personal Feed - Plan
type: feat
date: 2026-11-12
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: docs/spec/plans/2026-10-31-001-feat-campus-rating-mvp-plan.md
origin: docs/spec/plans/2026-10-31-001-feat-campus-rating-mvp-plan.md
execution: code
---

# U14 Персональная лента: события студента и преподавателя на главной - Plan

## Goal Capsule

- **Objective:** главная `/` студента и преподавателя показывает персональную ленту событий R28–R30; события доходят через доменные события Modulith с реестром публикаций (KTD11).
- **Authority:** мастер-план (R16, R28–R30, KTD11, раздел U14) → правила этого плана → код.
- **Open blockers:** нет.

---

## Product Contract

### Summary

Модули `checkpoint` и `activity` публикуют доменные события; модуль `feed` слушает их и записывает по одной строке ленты на получателя. Задание раз в минуту создаёт напоминание о дедлайне. Экран `/` — блоки «иконка, текст, время», новые сверху, курсорная подгрузка как в «Ленте наград».

### Requirements

- R1. События студента и тексты (предложение, согласовано 12.11):

| Событие | Когда | Текст | Ссылка |
|---|---|---|---|
| Новая задача | создано назначение студенту — при создании точки и при позднем вступлении в группу (R16), по одному на назначение | «Новая задача «<точка>» · до <дата и время по Москве>» | `/tasks/:id` |
| Напоминание | до дедлайна ≤ 24 ч, назначение в статусе `IN_PROGRESS` (не сдано, в том числе возвращено); один раз на назначение | «До дедлайна «<точка>» меньше суток» | `/tasks/:id` |
| Работу приняли | приём назначения | «Работа «<точка>» принята» | `/tasks/:id` |
| Работу вернули | возврат назначения | «Работу «<точка>» вернули на доработку» | `/tasks/:id` |
| Начислены баллы | подтверждено участие с начислением | «Начислено <N> БУА|БОА за «<активность>»» | `/users/<свой id>` |
| Новая активность | создана активность для группы студента | «Новая активность «<активность>» для вашей группы» | `/activities` |

- R2. События преподавателя: «Сдана работа «<точка>» · <ФИО студента>» — автору точки при каждой сдаче (в том числе повторной после возврата), ссылка `/checkpoints/:id`. События опросов — U16.
- R3. Получатели «Новой активности» — студенты групп активности с членством `ACTIVE` на момент создания. Студент, вступивший позже, событие не получает.
- R4. Блок события: иконка по типу, текст, время — `ДД.ММ.ГГГГ ЧЧ:ММ` по Москве; новые сверху; по 20 событий, «Загрузить ещё» по курсору. Пустая лента — «Событий пока нет».
- R5. Текст события фиксируется в момент события: переименование точки или активности старые события не меняет.
- R6. Видит только свою ленту. Администратору `/` — как сейчас; API ленты администратору отвечает пустой страницей.
- R7. Повторная доставка события (повтор из реестра публикаций, повторный проход задания) не создаёт дубля в ленте.

### Acceptance Examples

Стенд demo, время — Москва.

- AE1. **Covers R1, R4.** Петров создаёт точку «Эссе» для ИВТ-21 с дедлайном 20.11.2026 18:00. Тогда у Смирновой первым блоком — «Новая задача «Эссе» · до 20.11.2026 18:00», ссылка на её задачу; у студентов ИВТ-22 и у Петрова этого события нет.
- AE2. **Covers R1.** Назначение «Эссе» в `IN_PROGRESS`, до дедлайна 23 ч; задание прошло 10 раз. Тогда у Смирновой ровно одно «До дедлайна «Эссе» меньше суток». У студента, сдавшего «Эссе» до прохода задания, напоминания нет.
- AE3. **Covers R2.** Смирнова сдаёт «Эссе». Тогда у Петрова — «Сдана работа «Эссе» · Смирнова Анна Олеговна» со ссылкой на точку; после возврата и повторной сдачи — второе такое событие.
- AE4. **Covers R1.** Петров возвращает работу, затем принимает. Тогда у Смирновой «Работу «Эссе» вернули на доработку», затем «Работа «Эссе» принята».
- AE5. **Covers R1, R3.** Петров создаёт активность «Субботник» для ИВТ-21 и подтверждает участие Смирновой на 5 БОА. Тогда у Смирновой — «Новая активность «Субботник» для вашей группы» и «Начислено 5 БОА за «Субботник»»; студент с членством `PENDING` в ИВТ-21 «Новой активности» не получает.
- AE6. **Covers R1.** Орлов вступил в ИВТ-21 после создания «Эссе» и получил назначение (R16). Тогда у него «Новая задача «Эссе» · …».
- AE7. **Covers R5.** «Эссе» переименовано в «Эссе 2». Тогда старое событие по-прежнему «Эссе».
- AE8. **Covers R7.** Слушатель ленты упал на событии сдачи. Тогда после повтора из реестра событие у Петрова есть, и оно одно.
- AE9. **Covers R6.** Смирнова запрашивает API ленты — только свои события; администратор — пустая страница.
- AE10. **Covers R4.** 25 событий у студента. Тогда 20 и «Загрузить ещё»; после нажатия — 25, без дублей; новый студент — «Событий пока нет».

### Scope Boundaries

- Не входит: события опросов (U16), email-уведомления (U18), отметка «прочитано» и счётчик непрочитанных, лента администратора, удаление событий.
- Не входит: события о штрафах и начислении семестра — их нет в R28.

---

## Planning Contract

**Product Contract preservation:** мастер-план не меняется; тексты и правила R1–R7 — уточнения раздела U14, согласованы 12.11.

### Key Technical Decisions

- KTD1. **Строка ленты — снимок.** Таблица `user_event`: получатель, тип, время, данные для текста (названия, сумма, валюта, дедлайн, ФИО) и id цели ссылки. Тексты собирает фронтенд по типу и данным. Обеспечивает R5 без соединений с чужими модулями при чтении.
- KTD2. **Идемпотентность — уникальный ключ.** `unique (recipient_id, dedup_key)`, вставка с `on conflict do nothing`. Ключи: `assignment:{id}`, `reminder24h:{assignmentId}`, `submitted:{submissionId}`, `accepted:{assignmentId}`, `returned:{submissionId}`, `award:{ledgerEntryId}`, `activity:{activityId}`. Обеспечивает R7 и однократность напоминания.
- KTD3. **События публикуются в транзакции действия и несут всё для снимка.** Получатели вычисляются издателем (`checkpoint`, `activity`), чтобы `feed` не читал чужие таблицы: `AssignmentCreated` публикуется только при фактической вставке назначения; `ActivityCreated` несёт id студентов с `ACTIVE` на момент создания (R3); `PointsAwarded` — по одному на начисление.
- KTD4. **Повтор упавших публикаций.** Задание раз в минуту переотправляет незавершённые публикации старше 1 минуты (`IncompleteEventPublications`); завершённые публикации удаляются (`spring.modulith.events.completion-mode=delete`) — KTD11 требует чистки реестра.
- KTD5. **Напоминание — задание в `feed`, данные — через API `checkpoint`.** Задание раз в минуту берёт назначения `IN_PROGRESS` с дедлайном в `(now, now + 24 ч]` через публичный API модуля `checkpoint` и вставляет события с ключом `reminder24h`. Назначение, созданное меньше чем за сутки до дедлайна, получает напоминание при ближайшем проходе.
- KTD6. **API чтения.** `GET /api/v1/feed/personal?cursor&limit` (limit 1–100, по умолчанию 20), курсор — как у `/api/v1/feed/awards` (время + id). Порядок — `created_at desc, id desc`.

### High-Level Technical Design

```mermaid
flowchart LR
  CP[checkpoint: создание точки, R16, сдача, приём, возврат] -- AssignmentCreated / Submitted / Accepted / Returned --> REG[(реестр публикаций)]
  ACT[activity: создание, подтверждение участия] -- ActivityCreated / PointsAwarded --> REG
  REG --> L[feed: слушатели]
  J[feed: задание напоминаний, 1 мин] -- назначения IN_PROGRESS, дедлайн ≤ 24 ч --> CPAPI[checkpoint API]
  L --> UE[(user_event)]
  J --> UE
  UE --> API[GET /api/v1/feed/personal] --> UI[/ Главная]
```

---

## Implementation Units

### U1. Хранилище и чтение ленты

- **Goal:** таблица `user_event`, API чтения, повтор и чистка реестра публикаций.
- **Requirements:** R4, R6, R7; KTD1, KTD2, KTD4, KTD6.
- **Dependencies:** нет.
- **Files:** `backend/src/main/resources/db/changelog/feed/<ts>-user-event.sql`, `backend/src/main/java/ru/campusrating/feed/internal/UserEventRepository.java`, `backend/src/main/java/ru/campusrating/feed/internal/PersonalFeedService.java`, `backend/src/main/java/ru/campusrating/feed/api/PersonalFeedController.java`, `backend/src/main/java/ru/campusrating/feed/internal/IncompletePublicationsJob.java`, `backend/src/main/resources/application.yml`, `backend/src/test/java/ru/campusrating/feed/PersonalFeedIT.java`.
- **Approach:** индекс `(recipient_id, created_at desc, id desc)`; данные снимка — `jsonb` или явные колонки на усмотрение исполнителя; ответ API — дискриминированный по `kind` тип в OpenAPI, как варианты версии сдачи (`kind: LINK|FILE`).
- **Patterns to follow:** `feed/api/AwardsFeedController.java` (курсор, лимит, OpenAPI), `ledger/internal/SemesterAccrualJob.java` (регистрация задания).
- **Test scenarios:**
  - Covers AE10. 25 строк — страница 20 и курсор; вторая страница — 5, курсора нет; пересечений нет.
  - Covers AE9. Пользователь видит только свои строки; администратор — пустая страница; без входа — 401.
  - Повторная вставка с тем же `(recipient_id, dedup_key)` не меняет число строк.
  - Неверный курсор — `VALIDATION_FAILED`; `limit=0` и `101` — отказ.
  - Завершённая публикация удалена из реестра.
- **Verification:** `PersonalFeedIT` зелёный; `schema.d.ts` перегенерирован.

### U2. События назначений и сдач

- **Goal:** `checkpoint` публикует `AssignmentCreated`, `AssignmentSubmitted`, `AssignmentAccepted`, `AssignmentReturned`; `feed` пишет события R1 и R2.
- **Requirements:** R1, R2, R5, R7; KTD2, KTD3.
- **Dependencies:** U1.
- **Files:** `backend/src/main/java/ru/campusrating/checkpoint/{AssignmentCreated,AssignmentSubmitted,AssignmentAccepted,AssignmentReturned}.java`, `checkpoint/internal/AssignmentReconciliation.java`, `checkpoint/internal/CheckpointRepository.java` (признак фактической вставки), `checkpoint/internal/SubmissionService.java`, `backend/src/main/java/ru/campusrating/feed/internal/UserEventListeners.java`, `backend/src/test/java/ru/campusrating/feed/PersonalFeedEventsIT.java`.
- **Approach:** событие несёт id назначения или сдачи, id получателя, название точки, дедлайн, для сдачи — id автора точки и ФИО студента. Пакетный приём публикует событие на каждое принятое назначение. Отзыв сдачи событий не создаёт.
- **Patterns to follow:** `checkpoint/CheckpointCreated.java`, `checkpoint/internal/CheckpointListeners.java`.
- **Test scenarios:**
  - Covers AE1. Точка для ИВТ-21 — по событию каждому `ACTIVE` студенту ИВТ-21; студентам ИВТ-22 и автору — нет.
  - Covers AE6. Активация членства после создания точки — событие «Новая задача» по полученному назначению; повторная активация, не создавшая назначения, события не даёт.
  - Covers AE3. Сдача и повторная сдача после возврата — два события автору точки.
  - Covers AE4. Возврат и приём — события студенту в порядке действий; пакетный приём трёх работ — три события.
  - Covers AE7. Переименование точки не меняет текст старого события.
  - Covers AE8. Слушатель бросил исключение на первой попытке — после повтора задания из U1 событие одно.
- **Verification:** `PersonalFeedEventsIT` зелёный; `ModularityTest` проходит.

### U3. События активностей и баллов

- **Goal:** `activity` публикует `ActivityCreated` и `PointsAwarded`; `feed` пишет события R1.
- **Requirements:** R1, R3, R5, R7; KTD2, KTD3.
- **Dependencies:** U1.
- **Files:** `backend/src/main/java/ru/campusrating/activity/{ActivityCreated,PointsAwarded}.java`, `activity/internal/ActivityService.java`, `activity/internal/ParticipationService.java`, `feed/internal/UserEventListeners.java`, `backend/src/test/java/ru/campusrating/feed/PersonalFeedActivityIT.java`.
- **Approach:** `ActivityCreated` несёт id студентов групп с `ACTIVE` на момент создания; `PointsAwarded` — id проводки, студента, сумму, валюту (БУА/БОА по типу активности), название. Подтверждение, отклонённое бюджетом, событий не публикует.
- **Test scenarios:**
  - Covers AE5. Активность для ИВТ-21 — событие `ACTIVE` студентам, не `PENDING`; подтверждение на 5 БОА — «Начислено 5 БОА…».
  - Подтверждение, отклонённое `BUDGET_EXCEEDED`, событий не создаёт.
  - Активность для двух групп — студент каждой получает одно событие.
- **Verification:** `PersonalFeedActivityIT` зелёный.

### U4. Напоминание о дедлайне

- **Goal:** задание раз в минуту создаёт напоминание R1.
- **Requirements:** R1, R7; KTD2, KTD5.
- **Dependencies:** U1.
- **Files:** `backend/src/main/java/ru/campusrating/checkpoint/StudentAssignments.java` (или новый метод публичного API), `feed/internal/DeadlineReminderJob.java`, `backend/src/test/java/ru/campusrating/feed/DeadlineReminderIT.java`.
- **Test scenarios:**
  - Covers AE2. Дедлайн через 23 ч, `IN_PROGRESS` — 10 проходов дают одно событие.
  - Дедлайн через 25 ч — события нет; через 24 ч ровно — есть.
  - `SUBMITTED`, `ACCEPTED`, `OVERDUE` — события нет; возвращённое (`IN_PROGRESS`) — есть.
  - Время задаётся фиксированными часами теста, не реальным временем.
- **Verification:** `DeadlineReminderIT` зелёный.

### U5. Экран «Главная» и демо

- **Goal:** `/` у студента и преподавателя — персональная лента; демо-история содержит события всех типов.
- **Requirements:** R1, R2, R4, R6.
- **Dependencies:** U1–U4.
- **Files:** `frontend/src/features/feed/PersonalFeed.tsx`, `frontend/src/features/feed/contracts.ts`, `frontend/src/features/feed/queries.ts`, `frontend/src/features/feed/presentation.ts`, `frontend/src/app/router.tsx`, `frontend/src/mocks/feed.ts`, `frontend/src/features/feed/personal-feed.acceptance.test.tsx`, `backend/src/main/resources/db/changelog/demo/<ts>-demo-feed.sql`, `e2e/tests/base/feed.spec.ts`.
- **Approach:** `index`-маршрут рендерит ленту для `STUDENT` и `TEACHER`, администратору — прежний компонент. Иконки — из набора, уже используемого в `FeedShared.tsx`. Демо-строки — для Смирновой (все шесть типов) и Петрова (сдача) с ключами `demo:*`, чтобы не конфликтовать с живыми.
- **Patterns to follow:** `features/feed/AwardsFeed.tsx`, `FeedShared.tsx`, разбор контрактов в `features/feed/contracts.ts`.
- **Test scenarios:**
  - Covers AE1, AE10. Тексты всех семи типов и ссылки совпадают с R1/R2; «Загрузить ещё» догружает без дублей; пустая лента — «Событий пока нет».
  - Ответ с неизвестным `kind` — ошибка разбора контракта, экран показывает состояние ошибки, а не пустую ленту.
  - Администратор на `/` видит прежний экран, запроса ленты нет.
  - e2e (360 и 1280): студент сдаёт работу — у преподавателя на `/` появляется «Сдана работа…».
- **Verification:** фронт-тесты и e2e зелёные; на стенде demo Смирнова видит события всех шести типов.

---

## Verification Contract

- Приёмочные тесты U1–U5 пишет отдельная сессия до реализации; red фиксируется запуском.
- `./mvnw -Pquality verify` с Docker, `npm run check` во `frontend`, e2e базы — зелёные.
- `ModularityTest`: `feed` не обращается к внутренним пакетам `checkpoint` и `activity`.

## Definition of Done

- AE1–AE10 покрыты тестами и проходят; CI зелёный; запись в `docs/agent-log.md`; `docs/spec/test-plan.md` дополнен разделом ленты.
