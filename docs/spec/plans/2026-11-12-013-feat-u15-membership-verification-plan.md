---
title: U15 — подтверждение членства, старосты и добор подтверждения email - Plan
type: feat
date: 2026-11-12
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: docs/spec/plans/2026-10-31-001-feat-campus-rating-mvp-plan.md
origin: docs/spec/plans/2026-10-31-001-feat-campus-rating-mvp-plan.md
execution: code
---

# U15: подтверждение членства, старосты и добор подтверждения email - Plan

## Goal Capsule

- **Цель.** Закрыть мастер-U15: студент после регистрации ждёт подтверждения членства, преподаватель доступной группы или староста подтверждает или отклоняет заявку, подтверждение публикует `MembershipActivated`. Администратор назначает старосту группы (сценарий мастер-U13, перенесён сюда планом `2026-11-12-008`). Подтверждение email из мастер-U15 уже сделано в PR #55; здесь — недостающие проверки и e2e полного пути с почтовым ящиком.
- **Зависимость.** PR #55 (U13) слит: он вводит `PasswordSetupService`, `EmailVerificationFilter`, операции `/auth/email/**`, `listGroupStudents` и снимает запрет старта с `required`. Этот план опирается на их поведение и не повторяет его.
- **Иерархия источников.** Мастер-план (U15, R2, R16, R31, R32, R48; KTD11, KTD22, KTD23; AE11; сценарий старосты из мастер-U13) → `docs/spec/glossary.md` → `docs/spec/api/module-seams.md` → контракт `docs/spec/api/openapi.yaml` после U0 этого плана → план `2026-11-12-008-feat-u13-admin-implementation-plan.md` (что из U15 уже сделано).
- **Профиль исполнения.** U0 (контракт и спека) — отдельным коммитом до тестов. Приёмочные тесты U1–U4 пишет отдельная сессия `write-tests` до реализации; исполнитель (`implement-unit`) их не меняет и не коммитит.
- **Остановись и спроси, если:** нужно менять контракт сверх U0, сигнатуру шва вне перечисленных, зависимость модулей вне диаграммы, правку журнала проводок или применённого changeset'а.

---

## Product Contract

### Что уже сделано в PR #55 и не входит сюда

Подтверждение email по ссылке (одноразовый токен, 24 часа), нейтральный повтор письма, `403 EMAIL_NOT_VERIFIED` при входе и в защищённых операциях под `required`, автодопуск преподавателя по подтверждённому email из списка с кафедрой из записи, ссылка установки пароля при импорте, снятие запрета старта с `required` (KTD22). Экран подтверждения — `ConfirmEmailPage` в `frontend/src/features/auth/EmailVerification.tsx`, а не `VerifyEmailPage.tsx` из мастер-Files: U0 правит мастер-Files под фактическое имя.

### Правила

| № | Правило | Источник |
|---|---|---|
| П1 | Регистрация студента создаёт членство `PENDING` с временем заявки и по-прежнему проводку `INITIAL`; события нет. Импортированный администратором студент — `ACTIVE` с событием, как сейчас | мастер-U15, мастер-U13 |
| П2 | Подтверждает и отклоняет заявку преподаватель, у которого группа в `TeacherScope`, или староста этой группы. Остальным — `403`; гостю — `401` | R32, KTD23 |
| П3 | Подтверждение: `PENDING` → `ACTIVE`, `activatedAt` = сейчас по `Clock`, публикуется `MembershipActivated` в той же транзакции. Повтор для `ACTIVE` — `204` без второго события. `REJECTED` — `409 INVALID_STATE` | R16, KTD11 |
| П4 | Отклонение: `PENDING` → `REJECTED`, группа в записи сохраняется. Повтор — `204`. `ACTIVE` — `409 INVALID_STATE`. Аккаунт, проводки и сессии не трогаются | R32, решение человека 2026-11-12 |
| П5 | Студент с `REJECTED` выбирает факультет и группу заново: `REJECTED` → `PENDING` в новой группе (можно ту же), время заявки обновляется. Из `PENDING` и `ACTIVE` — `409 INVALID_STATE`. Проверка факультета и группы — как при регистрации (`400` по полю) | решение человека 2026-11-12 |
| П6 | Без `ACTIVE` назначений нет, на активности не записывается, `ACTIVITY_AWARD` не получает; начисление семестра и `INITIAL` получает. Эти ограничения уже есть в коде (`ParticipationService`, `ActivityService`, `StudentAssignmentsService`, `AssignmentReconciliation`) и считают `REJECTED` неактивным — U15 только проверяет это тестами | R32 |
| П7 | Староста — один на группу, назначает и снимает администратор. Назначить можно только студента с `ACTIVE`-членством в этой группе (иначе `400` по `studentId`); новое назначение заменяет прежнего старосту. Снятие при отсутствии старосты — `404` | глоссарий, решение человека 2026-11-12 |
| П8 | Права старосты — только на свою группу и только пока он староста; после снятия — `403` | мастер-U13 |
| П9 | Список заявок — все `PENDING` в группах, где текущий пользователь может подтверждать, целиком, по времени заявки, затем id. Недопущенный преподаватель и не-староста получают пустой список | R32, KTD23 |
| П10 | Подтверждение email — по правилам PR #55; при `off` письмо при регистрации не отправляется, вход сразу; подтверждение членства работает в обоих режимах | R31, R48 |

---

## Planning Contract

### Key Technical Decisions

- KTD1. **Отклонение — состояние, а не удаление.** `GroupMembershipState` получает `REJECTED`; строка `group_membership` остаётся с группой, поэтому `CurrentStudent.group` по-прежнему обязательна и экраны, читающие группу студента, не ломаются. Удаление аккаунта отвергнуто: у студента уже есть `INITIAL` в неизменяемом журнале.
- KTD2. **Схема — новым changeset'ом** `backend/src/main/resources/db/changelog/identity/<ts>-membership-approval.sql`: замена CHECK состояния на `PENDING | ACTIVE | REJECTED` (`activated_at` есть только у `ACTIVE`); колонка `requested_at timestamptz NOT NULL` (существующим строкам — `activated_at`, а без него — время миграции); таблица `group_head(group_id PK → study_group, student_id UNIQUE → app_user, assigned_at)`. Применённые changeset'ы не меняются.
- KTD3. **Владелец — `identity`.** `identity/internal/MembershipApproval.java` (заявки, подтверждение, отклонение, повторная заявка), `identity/internal/GroupHeads.java` (назначение и снятие), контроллеры в `identity/api`. Право подтверждать — одна функция `MembershipApproval.canDecide(user, groupId)`: `TeacherScope` ∪ «староста этой группы». Других модулей не касается.
- KTD4. **Гонки.** Подтверждение, отклонение и повторная заявка берут `SELECT … FOR UPDATE` строки членства; назначение старосты — строки `study_group` через `group_head` upsert в одной транзакции с проверкой `ACTIVE`. Параллельные подтверждение и отклонение дают ровно один исход.
- KTD5. **Событие.** `MembershipActivated` публикуется через Modulith внутри транзакции подтверждения (KTD11); слушатель `checkpoint` уже создаёт назначения по правилу «`activatedAt` раньше дедлайна» (AE11). Сигнатура события не меняется; меняется только описание публикатора в `module-seams.md`.
- KTD6. **e2e-стек с почтой.** `docker-compose.e2e.yml` переводит backend на `required` и добавляет контейнер GreenMail (образ с зафиксированной версией) как SMTP; Mailpit по-прежнему не используется, и в основном `docker-compose.yml` остаётся `off` (R48). Чтение писем — `e2e/fixtures/greenmail.ts` через HTTP API GreenMail; прежде чем строить на нём помощники, проверить API на запущенном образе заведомо пустым и заведомо непустым ящиком (урок KTD14 плана U3). Общие помощники `e2e/tests/base/support.ts` (`registerStudent`, `registerTeacher`) подтверждают email по письму и членство — через API допущенного преподавателя группы, чтобы существующие спеки остались зелёными без правки.
- KTD7. **Демо.** Демо-сид назначает старосту ИВТ-21 из существующих `ACTIVE`-студентов; демо-пользователи при `required` уже подтвердили email (сделано в PR #55). README: строка старосты в таблице демо-аккаунтов и шаг сценария показа «староста подтверждает заявку Орлова».

### Контракт (U0)

| Операция | Роли | Запрос → ответ |
|---|---|---|
| `GET /api/v1/memberships/pending` (`listPendingMemberships`) | `TEACHER`, `STUDENT` | → `{items:[{studentId, fullName, email, group: StudyGroupRef, requestedAt}]}` |
| `POST /api/v1/memberships/{studentId}/approval` (`approveMembership`) | `TEACHER`, `STUDENT` | → `204`; `403`, `404` (нет студента или членства), `409 INVALID_STATE` |
| `POST /api/v1/memberships/{studentId}/rejection` (`rejectMembership`) | `TEACHER`, `STUDENT` | → `204`; `403`, `404`, `409 INVALID_STATE` |
| `PUT /api/v1/me/membership` (`requestMembership`) | `STUDENT` | `{facultyId, groupId}` → `200 CurrentUser` (вариант студента); `400` по полю, `409 INVALID_STATE` |
| `GET /api/v1/admin/group-heads` (`listGroupHeads`) | `ADMIN` | → `{items:[{groupId, student: UserRef}]}` |
| `PUT /api/v1/admin/group-heads/{groupId}` (`assignGroupHead`) | `ADMIN` | `{studentId}` → `200 {groupId, student: UserRef}`; `400` по `studentId`, `404` группы |
| `DELETE /api/v1/admin/group-heads/{groupId}` (`removeGroupHead`) | `ADMIN` | → `204`; `404` |

Схемы: `GroupMembershipState` += `REJECTED`; `CurrentStudent` += обязательное `groupHead: boolean`; старосты читаются отдельной операцией `identity`, а не полем `AdminStudyGroup`: иначе `directory` зависел бы от `identity` по кругу; `listGroupStudents` += роль `ADMIN` (выбор старосты из студентов группы). Новые операции — `x-status: planned` до U1–U2. Фронтовые моки и `examples.generated.ts` обновляются генератором.

## Implementation Units

### U0. Контракт и спека

- **Goal.** Контракт из таблицы выше, правки спеки под решения этого плана.
- **Files:** `docs/spec/api/openapi.yaml`, `docs/spec/api/module-seams.md` (публикатор `MembershipActivated`; права старосты), `docs/spec/glossary.md` (`REJECTED`, «Заявка в группу»), мастер-план: U15 Files (`EmailVerification.tsx` вместо `VerifyEmailPage.tsx`, новые файлы этого плана), строка KTD22 про `docker-compose.server.yml` не меняется — она за U22.
- **Verification.** Линтер контракта и `ContractCompatibilityIT` зелёные; `npm --prefix frontend run api:gen`; `node tools/check-agent-docs.mjs`.

### U1. Подтверждение и отклонение членства (backend)

- **Goal.** П1–П6, П9: регистрация в `PENDING`, список заявок, подтверждение, отклонение, повторная заявка.
- **Files:** `backend/src/main/java/ru/campusrating/identity/internal/MembershipApproval.java`, `backend/src/main/java/ru/campusrating/identity/api/MembershipsController.java`, `backend/src/main/java/ru/campusrating/identity/internal/{RegistrationService,IdentityRepository}.java`, `backend/src/main/java/ru/campusrating/identity/GroupMembershipState.java`, `backend/src/main/resources/db/changelog/identity/<ts>-membership-approval.sql`; тест `backend/src/test/java/ru/campusrating/identity/MembershipApprovalIT.java`.
- **Test scenarios:**
  - Регистрация студента: `membershipState: PENDING`, `INITIAL` 1000 АР в журнале, назначений нет, события `MembershipActivated` нет.
  - Covers AE11. Точка A с дедлайном вчера, B — завтра; преподаватель подтверждает → назначение только по B; в `/auth/me` — `ACTIVE`.
  - Повторное подтверждение — `204`, второго события и второго назначения нет.
  - Преподаватель подтверждает студента доступной группы; группа вне `TeacherScope` — `403`; недопущенный преподаватель — `403`, его список заявок пуст.
  - Староста подтверждает студента своей группы, но не чужой (`403`); обычный студент — `403`.
  - Отклонение: `REJECTED`, группа сохранена; подтверждение после отклонения — `409`; отклонение `ACTIVE` — `409`.
  - Повторная заявка из `REJECTED` в другую группу — `PENDING` в новой группе, видна в списке её преподавателя и не видна в прежней; из `PENDING` — `409`; группа не того факультета — `400` по `groupId`.
  - Параллельные подтверждение и отклонение одной заявки: ровно один успех, второй — `409`; при успехе подтверждения событие одно.
  - `PENDING`- и `REJECTED`-студента нельзя подтвердить участником активности (`422 STUDENT_NOT_ELIGIBLE` со списком студентов), но начисление семестра он получает.
  - Импортированный студент — `ACTIVE` сразу (регрессия U13).
  - Список заявок: только `PENDING` доступных групп, по времени заявки, затем id.

### U2. Старосты (backend)

- **Goal.** П7–П8: список, назначение и снятие старост, `groupHead` в `/auth/me`.
- **Files:** `backend/src/main/java/ru/campusrating/identity/internal/GroupHeads.java`, `backend/src/main/java/ru/campusrating/identity/api/AdminGroupHeadsController.java`, правка чтения `CurrentStudent`; тест `backend/src/test/java/ru/campusrating/identity/GroupHeadIT.java`.
- **Test scenarios:**
  - Не администратор — `403`; гость — `401`.
  - Назначение `ACTIVE`-студента группы: `groupHead: true` у него, он есть в `listGroupHeads`; он подтверждает заявку своей группы.
  - Назначение другого студента заменяет прежнего: прежний получает `403` на подтверждение, `groupHead: false`.
  - `PENDING`-студент, студент другой группы, преподаватель — `400` по `studentId`; несуществующая группа — `404`.
  - Снятие: староста теряет права (`403`); повторное снятие — `404`.
  - Назначенный староста получает права своей группы и только её (сценарий мастер-U13).

### U3. Экраны (frontend)

- **Goal.** Заявки для преподавателя и старосты, повторная заявка студента, назначение старосты администратором.
- **Files:** `frontend/src/features/groups/PendingMembers.tsx` (маршрут `/memberships` без пункта меню — у ролей уже пять пунктов; вход по `screen-map.md`: у преподавателя — ссылка «Заявки: N» в «Группах», у старосты — карточка «Заявки в группу <группа>» с числом заявок и кнопкой «Проверить заявки» на «Главной»), `frontend/src/features/auth/RequestMembership.tsx` (плашка «Заявка в {группа} отклонена» и выбор факультета и группы на «Главной»), правка `frontend/src/features/admin/directory/DirectoryPage.tsx` (колонка «Староста», диалог назначения из студентов группы, снятие), `frontend/src/app/{router,routeElements}.tsx`, `frontend/src/features/auth/contracts.ts`, моки `frontend/src/mocks/**`; приёмочный тест `frontend/src/features/groups/memberships.acceptance.test.tsx`.
- **Test scenarios:**
  - Преподаватель видит заявки доступных групп с ФИО, email, группой и временем; «Подтвердить» убирает строку; «Отклонить» — через подтверждение в диалоге.
  - Староста видит на «Главной» карточку «Заявки в группу <группа>» с числом заявок и пояснением, по кнопке «Проверить заявки» — только свою группу; при N = 0 карточки нет; обычный студент карточки не видит, прямой переход на `/memberships` — «Нет доступа». Преподаватель видит «Заявки: N» в «Группах».
  - Пустой список — «Новых заявок нет»; ошибка загрузки — сообщение и «Повторить».
  - Студент с `REJECTED` видит плашку и форму; после отправки — текст ожидания `PENDING` (уже есть в `routeElements.tsx`).
  - Администратор назначает старосту из `ACTIVE`-студентов группы, видит его в колонке, снимает.
  - Скриншоты 360 и 1280 px для `/memberships`, плашки отклонения и диалога старосты.

### U4. Добор проверок подтверждения email

- **Goal.** Сценарии мастер-U15 про email, которых нет в `U13RequiredMailIT` (PR #55). Код правится, только если сценарий красный.
- **Files:** `backend/src/test/java/ru/campusrating/identity/VerificationIT.java`; при красном — точечные правки `identity/internal/PasswordSetupService.java`, `SessionService.java`.
- **Test scenarios:**
  - `required`: вход до подтверждения — `403 EMAIL_NOT_VERIFIED` с понятным текстом, только после верного пароля; неверный пароль — `401` как обычно.
  - Просроченный (25 часов по `MutableClock`) и использованный токен подтверждения отклоняются, email остаётся неподтверждённым.
  - `off`: регистрация студента и преподавателя — письмо не отправлено, вход сразу.
  - Преподаватель при `required`: вход только после подтверждения; email из списка после подтверждения — допуск и кафедра из записи списка; email вне списка — ждёт допуска администратора.
  - Переключение `off` → `required` (два контекста на одной базе): пользователь, созданный при `off`, получает `EMAIL_NOT_VERIFIED`, запрашивает ссылку, подтверждает и входит.

### U5. e2e полного пути

- **Goal.** Стек `docker-compose.e2e.yml` с `required` и GreenMail; полный путь из мастер-U15.
- **Files:** `docker-compose.e2e.yml`, `e2e/fixtures/greenmail.ts`, `e2e/tests/base/support.ts` (вспомогательный), `e2e/tests/base/registration.spec.ts`.
- **Test scenarios:**
  - Регистрация студента в UI → письмо в GreenMail → переход по ссылке → вход → «ждёт подтверждения» → староста подтверждает в `/memberships` → у студента назначение по открытой точке, по просроченной — нет.
  - Существующие спеки `e2e/tests/base/*` зелёные на новом стеке без правок самих спек.

## Verification Contract

| Ворота | Команда | Когда |
|---|---|---|
| Backend | `./backend/mvnw -f backend/pom.xml verify`, `node tools/crap.mjs backend/target/site/jacoco/jacoco.xml`; перед PR — `-Pquality verify` | U0–U2, U4 |
| Frontend | `npm --prefix frontend run check`, затем `build` | U0, U3 |
| e2e | `docker compose -p campus-e2e -f docker-compose.yml -f docker-compose.e2e.yml down -v`, то же с `up -d --build --wait`, `npm --prefix e2e test` | U5 |
| Обвязка | `node tools/check-agent-docs.mjs` | PR |

## Risks

| Риск | Мера |
|---|---|
| `PENDING` после регистрации ломает существующие e2e и демо-сценарий | Помощники `support.ts` подтверждают членство; README и сид — KTD7 |
| HTTP API GreenMail отличается от ожидаемого | Проба на запущенном образе до помощников (KTD6) |
| Поведение из PR #55 отличается от описанного в разделе «Что уже сделано» | Перед U0 сверить раздел с кодом `main` |

## Definition of Done

- Сценарии U1–U5 зелёные; семь новых операций — `implemented`; скриншоты U3 на 360 и 1280 px; запись в `docs/agent-log.md` и уроки в той же ветке.
