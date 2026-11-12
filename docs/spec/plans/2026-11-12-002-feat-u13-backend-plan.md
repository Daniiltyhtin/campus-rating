---
title: U13 Backend — Teacher Approval, Grants and Directory Administration - Plan
type: feat
date: 2026-11-12
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: docs/spec/plans/2026-10-31-001-feat-campus-rating-mvp-plan.md
origin: docs/spec/plans/2026-10-31-001-feat-campus-rating-mvp-plan.md
execution: code
---

# U13 Backend: допуск преподавателей, выдачи доступа и справочники - Plan

## Goal Capsule

- **Цель.** Серверная часть мастер-U13 для готовых экранов: 8 операций допуска и выдач (контракт PR #42) и 12 операций справочников (контракт PR #40, экраны PR #41) переходят из `planned` в `implemented`. Сценарий защиты «преподаватель регистрируется → ждёт допуска → администратор допускает» работает без сида.
- **Иерархия источников.** Контракт `docs/spec/api/openapi.yaml` (операции `/api/v1/admin/teachers/**` и `/api/v1/admin/directory/**`; описания операций — источник кодов, порядка и повторов) → `docs/spec/api/module-seams.md` → мастер-план (U13, R2, R3, R24; KTD23; ADR007) → планы фронта U13 `docs/spec/plans/2026-11-11-003-feat-u13-directory-frontend-plan.md`, `docs/spec/plans/2026-11-11-004-feat-u13-teachers-frontend-plan.md`.
- **Профиль исполнения.** Приёмочные тесты по Test scenarios пишет отдельная сессия `write-tests` до реализации; исполнитель их не меняет. В контракте меняется только `x-status`; швы не меняются.
- **Остановись и спроси, если:** нужно изменить контракт сверх `x-status`, сигнатуру шва или зависимость модулей вне диаграммы (`identity` → `directory` разрешена).

---

## Product Contract

### Summary

Модуль `identity`: список и карточка преподавателей с заявками, допуск с выбором кафедры, отклонение заявки, дополнительные выдачи доступа (группа, факультет, курс факультета, курс) и итоговые доступные группы по `TeacherScope`. Модуль `directory`: создание, правка и удаление факультетов, групп и кафедр.

### Правила

| № | Правило | Источник |
|---|---|---|
| П1 | Все операции — только `ADMIN`; до смены временного пароля — `403 PASSWORD_CHANGE_REQUIRED` (фильтр `identity`) | контракт |
| П2 | Допуск: кафедра должна существовать (`400` по `departmentId`); повтор с той же кафедрой — `200` без второго эффекта; другая кафедра у допущенного — `409 INVALID_STATE` | контракт `approveAdminTeacher` |
| П3 | Отклонение — только недопущенного: аккаунт и его записи доступа удаляются, сессии завершаются, email можно зарегистрировать снова (запись списка преподавателей сохраняется); допущенный — `409`; нет или уже отклонён — `404`; не преподаватель — `404` | контракт `rejectAdminTeacher` |
| П4 | Выдача — ровно одно сочетание полей по виду; курс 1…6; неизвестные id — `400` по полю; удаление чужой или отсутствующей выдачи — `404` | контракт `createAdminTeacherGrant`, `deleteAdminTeacherGrant` |
| П5 | Доступные группы — серверный `TeacherScope`: факультет кафедры ∪ выдачи; у недопущенного — пусто; курс по году набора и учебному году с границей 1 сентября по Europe/Moscow | контракт, мастер-KTD23, ADR007 |
| П6 | Справочники: код и название — `trim`; код уникален в своём справочнике (повтор — `409 DIRECTORY_CODE_TAKEN`); `facultyId` группы и кафедры должен существовать (`400`) | контракт `createAdmin*` |
| П7 | Удаление без каскада: запись с зависимостями (группа со студентами или ссылками точек, активностей, выдач; факультет с группами или кафедрами; кафедра с преподавателями или записями списка) — `409 DIRECTORY_IN_USE`, ничего не удалено; повтор после удаления — `404` | контракт `deleteAdmin*`, мастер-U13 |
| П8 | Списки — целиком, `name asc`, затем `id asc` | контракт `listAdmin*` |

---

## Planning Contract

### Key Technical Decisions

- KTD1. **Владельцы.** Допуск и выдачи — `identity` (`identity/api/AdminTeachersController`, `identity/internal/AdminTeacherService`, репозиторий на `JdbcClient`); справочники — `directory` (`directory/api/AdminDirectoryController`, `directory/internal/AdminDirectoryService`). Названия кафедр, факультетов и групп `identity` берёт через `DirectoryApi`.
- KTD2. **Транзакции.** Каждая изменяющая операция — одна транзакция; допуск и отклонение берут `SELECT … FOR UPDATE` строки `app_user` преподавателя, чтобы параллельные допуск и отклонение не разошлись.
- KTD3. **Завершение сессий** при отклонении — через `SessionRegistry`: все сессии принципала с этим id — `expireNow()`.
- KTD4. **Удаление с зависимостями** — явная проверка перед удалением и перевод нарушения внешнего ключа в `409 DIRECTORY_IN_USE` как страховка; каскадов в схеме нет. Коды `DIRECTORY_CODE_TAKEN` и `DIRECTORY_IN_USE` добавляются в `shared.error.ProblemCode` (зеркало контракта).
- KTD6. **Шов `DirectoryApi`** дополняется методом `Optional<FacultyInfo> faculty(long facultyId)` — выдаче `FACULTY` нужен факультет без групп; правка `module-seams.md` в этом же PR.
- KTD5. **Контракт** — `x-status: implemented` у 20 операций; расхождения генератора схемы закрываются конвертером в тестовой поддержке.

## Implementation Units

### U1. Допуск преподавателей и выдачи доступа

- **Goal.** `listAdminTeachers`, `getAdminTeacher`, `approveAdminTeacher`, `rejectAdminTeacher`, `listAdminTeacherGrants`, `createAdminTeacherGrant`, `deleteAdminTeacherGrant`, `listAdminTeacherAccessibleGroups`.
- **Files:** `backend/src/main/java/ru/campusrating/identity/api/AdminTeachersController.java`, `backend/src/main/java/ru/campusrating/identity/internal/{AdminTeacherService,AdminTeacherRepository}.java`; `backend/src/test/java/ru/campusrating/identity/AdminTeachersIT.java`; `docs/spec/api/openapi.yaml` (только `x-status`).
- **Test scenarios:**
  - Не администратор — `403`; гость — `401`; администратор до смены пароля — `403 PASSWORD_CHANGE_REQUIRED`.
  - Список: все преподаватели, сначала по имени, затем id; недопущенный отмечен; счётчик заявок = число недопущенных.
  - Допуск с кафедрой: преподаватель получает доступ к группам факультета кафедры (видно в `/me/accessible-groups` и `accessible-groups` карточки); повтор с той же кафедрой — `200` без изменений; другая кафедра — `409`; несуществующая кафедра — `400` по `departmentId`.
  - Сценарий защиты: регистрация преподавателя → `teacherApproved=false`, создание точки — отказ → допуск администратором → создание точки на группу факультета — успех.
  - Отклонение недопущенного: аккаунт удалён, его сессия больше не авторизована (`401`), повторная регистрация тем же email — успех; допущенный — `409`; отсутствующий и повтор — `404`; студент по этому адресу — `404`.
  - Выдачи: каждое из четырёх сочетаний создаётся и расширяет доступные группы; лишнее поле, неизвестный id, курс 0 и 7 — `400` по полю; удаление — доступ сужается; чужая и отсутствующая выдача — `404`.
  - Курс: группа набора 2026 — первый курс с 01.09.2026 00:00 по Москве, до этого — вне курсов (часы двигает `MutableClock`).

### U2. Справочники

- **Goal.** 12 операций `listAdmin*`, `createAdmin*`, `updateAdmin*`, `deleteAdmin*` для факультетов, групп и кафедр.
- **Files:** `backend/src/main/java/ru/campusrating/directory/api/AdminDirectoryController.java`, `backend/src/main/java/ru/campusrating/directory/internal/{AdminDirectoryService,AdminDirectoryRepository}.java`; `backend/src/test/java/ru/campusrating/directory/AdminDirectoryIT.java`; `docs/spec/api/openapi.yaml` (только `x-status`).
- **Test scenarios:**
  - Доступ — как в U1.
  - Создание факультета, группы, кафедры: `trim` кода и названия; созданное видно в публичных `GET /directory/**`; повтор кода — `409 DIRECTORY_CODE_TAKEN`, второй записи нет; неизвестный `facultyId` — `400` по полю.
  - Правка — полная замена полей; смена факультета группы меняет связь, студенты группы остаются в ней.
  - Удаление: пустая запись — `204`, повтор — `404`; группа со студентом, с точкой или выдачей; факультет с группой или кафедрой; кафедра с преподавателем — `409 DIRECTORY_IN_USE`, запись на месте.
  - Списки — по имени, затем id.

## Verification Contract

| Ворота | Команда | Когда |
|---|---|---|
| Backend | `./backend/mvnw -f backend/pom.xml -Pquality verify` (с `ContractCompatibilityIT`, `ModularityTest`) | U1, U2 |
| Frontend | `npm --prefix frontend run check`, `npm --prefix frontend run build` | PR |
| Обвязка | `node tools/check-agent-docs.mjs` | PR |

## Definition of Done

- Сценарии U1–U2 зелёные; 20 операций — `implemented`; запись в `docs/agent-log.md`.
