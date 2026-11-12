---
title: U13 — реализация оставшегося администрирования
type: feat
date: 2026-11-12
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
execution: code
---

# U13 — реализация оставшегося администрирования

## Goal Capsule

Закрыть остаток мастер-U13 по требованиям PR #53. Источники поведения — планы
`2026-11-12-004`, `005`, `006`, `007` в этой папке; их R и AE не перенумеровываются.
Справочники и backend допуска преподавателей уже реализованы. Незакоммиченный frontend
допуска преподавателей в другом checkout не переносится без проверки его готовности.
Старосты остаются в U15 согласно плану `004`.

Исполнение: контракт → независимые приёмочные тесты `write-tests` → реализация
`implement-unit`/`ub-mode` → проверки. Исполнитель не изменяет тесты tester и не коммитит.
По прямому поручению человека включена необходимая часть U15: подтверждение email,
нейтральный повтор письма, проверка доступа в required и автодопуск по подтверждённому
email из списка. Членство и старосты U15 остаются вне scope. Без SMTP required
по-прежнему отклоняется при старте; временный пароль email не подтверждает.

## Planning Contract

### Владельцы и данные

- Семестры принадлежат `ledger`; настройки — `settings`; агрегаты расходов — `activity`.
  Контроллеры этих модулей защищаются существующим ADMIN matcher, не создают зависимость
  `ledger → identity` или `settings → activity`. Предупреждение о снижении бюджета
  запрашивает отдельный endpoint `activity` перед сохранением настроек.
- Пользователи, временные пароли, аудит, импорт студентов и списка преподавателей —
  `identity`. Начальные проводки создаёт только `LedgerApi` внутри транзакции импорта.
  Для импорта групп используются публичные методы `directory`, описанные в швах.
- Новые миграции добавляют фиксированное N семестра, аудит временных паролей и
  одноразовые токены установки пароля. Применённые миграции не изменяются.
- Изменения семестров сериализуются общей транзакционной блокировкой: проверка соседей
  и изменение выполняются атомарно. Job фиксирует N однократно под той же блокировкой.
  Проводки и обработка нулевого начисления сохраняют идемпотентность U8.
- Пароли генерируются `SecureRandom`, сохраняются только BCrypt-хешем. Токены —
  случайные, в БД только SHA-256, проверяются и расходуются атомарно. Истечение — `Clock`.
  Завершение сессий и отправка письма выполняются после успешного commit.
- CSV полностью разбирается Commons CSV, XLSX — POI с ограничением распаковки;
  размеры и число записей проверяются до сохранения. Валидные строки одного файла
  сохраняются одной транзакцией; конфликты/повторы внутри файла видят предыдущие строки.

### API

Все пути ниже имеют префикс `/api/v1`. Контракт в `docs/spec/api/openapi.yaml`
описывает коды ошибок, тела и роли до production-кода; типы генерируются `api:gen`.

| Операции | Владелец | Ответ |
|---|---|---|
| GET/POST `/admin/semesters`; PUT/DELETE `/admin/semesters/{semesterId}`; POST `…/{semesterId}/start` | ledger | список `{items}` или семестр `{id,title,startsAt,started,accrual}`; DELETE 204 |
| GET/PUT `/admin/settings` | settings | `penaltyStep,penaltyIntervalSeconds,minPenaltyIntervalSeconds,semesterAccrual,monthlyBudgetLimit`; минимум только в ответе |
| GET `/admin/budgets?month=YYYY-MM`; GET `/admin/budgets/impact?limit=N` | activity | `{items:[{teacher,group,limit,spent,remaining}]}`; `{count}` |
| GET `/admin/users?search=&role=&cursor=` | identity | `{items:[{id,fullName,email,role,group,department,passwordSetAt}],nextCursor}`; 50 строк |
| GET `/groups/{groupId}/students` | identity | `{items:[{id,fullName,email,membershipState,passwordSetAt}]}` |
| POST `/users/{userId}/temporary-password`; GET `/me/password-audit` | identity | `{password}`; `{actor,createdAt}` или 204; Cache-Control no-store |
| POST `/admin/import/{kind}` multipart `file` | identity | kind GROUPS/STUDENTS/TEACHER_ALLOWLIST; `{created,skipped,rejected:[{row,key,reason}]}` |
| GET/DELETE `/admin/teacher-allowlist` (DELETE `?email=`) | identity | `{items:[{email,department,registered}]}`; 204 |
| POST `/auth/password/set` | identity | `{token,password}` → 204; публично, с CSRF |
| POST `/auth/email/verification`, POST `/auth/email/confirmation`, GET `/auth/email/status` | identity | `{email}` → 204 без раскрытия аккаунта; `{token}` → 204; `{required,verified}` |

`GET /auth/email/status` — только метаданные собственного доступа, как `GET /auth/me`;
разрешён в ограниченной сессии смены пароля. Отправка ссылки и её подтверждение
доступны публично с CSRF. `required` блокирует защищённые операции до подтверждения;
сессия временного пароля позволяет сначала сменить пароль, затем подтвердить email.
Публичные маршруты: `/email/confirm/:token`, `/password/set/:token`.

Вспомогательные файлы исполнения: `identity/internal/EmailVerificationFilter.java`,
`identity/api/SetPasswordController.java`, `identity/internal/PasswordSetupService.java`,
`shared/mail/MailModeValidator.java`, `frontend/src/features/auth/EmailVerification.tsx`,
`frontend/src/app/routeElements.tsx`, `backend/src/main/resources/application.yml`,
`.env.example`, `docs/spec/api/module-seams.md`, сгенерированные API-типы и MSW-примеры.

## Implementation Units

### U1. Семестры, настройки и бюджеты — план 004

**Files:** `backend/src/main/java/ru/campusrating/ledger/{api/AdminSemestersController.java,internal/SemesterRepository.java,internal/SemesterAccrualJob.java,internal/AdminSemesterService.java}`;
`backend/src/main/java/ru/campusrating/settings/{api/AdminSettingsController.java,internal/AdminSettingsService.java}`;
`backend/src/main/java/ru/campusrating/activity/{api/AdminBudgetsController.java,internal/AdminBudgetService.java}`;
`backend/src/main/resources/db/changelog/ledger/202611121800-admin-semesters.sql`;
`frontend/src/features/admin/{semesters,settings,budgets}/**`, `frontend/src/app/{navigation.ts,router.tsx}`;
`backend/src/test/java/ru/campusrating/admin/U13AdminSemestersIT.java`, соответствующие frontend tests.

- 004/R1 → AE1 → полное меню → navigation test.
- 004/R2–R8 → AE2–AE7 → CRUD, соседи, нормализация названия, запуск ближайшего,
  фиксированное N и начисление с пределом → `U13AdminSemestersIT`, `SemesterAccrualIT`.
- Дополнительные границы: начало равно now/соседу; N=0; нулевое начисление и повтор;
  два конкурентных запуска; смена N между пачками; регистрация после начала.
- 004/R9–R11 → AE8–AE10 → атомарное сохранение четырёх настроек, целые границы,
  минимум интервала с округлением вверх, отмена предупреждения → backend IT и UI tests.
- 004/R12–R14 → AE11–AE12 → только потраченные пары, исторический месяц с текущим
  лимитом, отсутствие прав и обязательная смена пароля → IT/UI tests.

### U2. Пользователи и временные пароли — план 006

**Files:** `backend/src/main/java/ru/campusrating/identity/{api/AdminUsersController.java,internal/AdminUserService.java,internal/TemporaryPassword.java,internal/SessionService.java}`;
`backend/src/main/resources/db/changelog/identity/202611121810-temporary-passwords.sql`;
`frontend/src/features/admin/users/**`, `frontend/src/features/groups/**`,
`frontend/src/features/activities/BudgetPage.tsx`, `frontend/src/features/profile/ProfilePage.tsx`,
`frontend/src/app/router.tsx`; `backend/src/test/java/ru/campusrating/admin/U13AdminUsersIT.java`.

- 006/R1–R5 → AE1–AE5 → курсор `(full_name,id)`, поиск от двух символов,
  сброс страниц при смене фильтра, ACTIVE/PENDING → IT и UI tests.
- 006/R6–R10 → AE3–AE9 → подтверждение, однократный показ, 12 допустимых символов,
  завершение всех сессий, запрет повторного пароля и аудит только себе → IT/UI tests.
- 006/R11 → AE10 → сохраняется зависимость required от U15 до решения человека.
- Дополнительные границы: отсутствующий пользователь, администратор как цель,
  чужая группа, недопущенный преподаватель, гонка двух сбросов, отмена и отсутствие
  секретов в списках, логах и query cache после закрытия диалога.

### U3. Импорт и установка пароля — план 005

**Files:** `backend/pom.xml`; `backend/src/main/java/ru/campusrating/identity/{api/AdminImportController.java,api/SetPasswordController.java,internal/AdminImportService.java,internal/ImportFileReader.java,internal/PasswordSetupService.java,internal/RegistrationService.java,internal/SecurityConfig.java}`;
публичный шов `directory/DirectoryImport.java` и его реализация в `directory/internal`;
`backend/src/main/resources/db/changelog/identity/202611121820-password-setup.sql`;
`frontend/src/features/admin/import/**`, `frontend/src/features/auth/SetPasswordPage.tsx`,
`frontend/src/app/router.tsx`; `docs/spec/import/*`;
`backend/src/test/java/ru/campusrating/admin/U13AdminImportIT.java`.

- 005/R1–R5 → AE1–AE6,AE11 → обе кодировки, BOM, разделители, quoted newline,
  XLSX, 5 МБ/5000 строк, первая ошибка по колонкам, совпадения → IT.
- 005/R6,R9–R12 → AE1,AE3,AE4,AE9,AE10 → INITIAL один раз, ACTIVE,
  список преподавателей, конфликты и запрет удаления зарегистрированного → IT.
- 005/R7–R8 → AE7–AE8 → письмо после commit, отказ отправки без отката,
  24 часа, повтор/расходование, инвалидирование временным паролем → IT с mail fixture.
- Дополнительные границы: ровно 5 МБ/5000 строк, пустой лист/файл, неверное число колонок,
  полностью пустые записи, год 1999/2000/2100/2101, zip-bomb, rollback всех принятых
  строк при ошибке сохранения, конкурентный импорт одного email, два расходования токена.

### U4. Материалы сдачи — план 007

**Files:** `frontend/src/features/admin/submissions/AdminSubmissionPage.tsx`,
`frontend/src/features/tasks/TaskPage.tsx`, `frontend/src/app/router.tsx`,
`frontend/src/features/profile/ProfilePage.tsx`;
`backend/src/main/java/ru/campusrating/checkpoint/internal/SubmissionService.java`
(только если тест выявит разрешённую запись ADMIN);
`backend/src/test/java/ru/campusrating/admin/U13AdminSubmissionIT.java`, UI tests.

- 007/R1–R4 → AE1–AE5 → роль проверяется до загрузки, история всех версий,
  развёрнутые замечания, ссылки/файлы, пустое/404, отсутствие действий → UI tests.
- 007/R3 → AE3,AE5 → submit/withdraw/accept/return от ADMIN всегда 403 → IT.

### U5. Допуск преподавателей и сквозная приёмка

Проверить frontend из плана `2026-11-11-004` на актуальном backend. Существующую
незакоммиченную работу сохранять; переносить только после чтения diff и проверок,
без коммита или сброса исходного checkout. Полная готовность U13 включает этот экран.

## Verification Contract

- До реализации каждого доменного блока tester фиксирует red. Исполнитель не меняет его тесты.
- Backend: `backend/mvnw.cmd -f backend/pom.xml -Pquality verify`, затем
  `node tools/crap.mjs backend/target/site/jacoco/jacoco.xml`; в том числе
  `ContractCompatibilityIT`, `ModularityTest`, регрессии U5/U8/U9/U12.
- Frontend: `npm --prefix frontend run api:gen`, `npm --prefix frontend run check`,
  `npm --prefix frontend run build`; генерация повторяется без diff.
- Обвязка: `node --test "tools/test/*.test.mjs"`, `node tools/check-agent-docs.mjs`.
- e2e: полный стек `campus-e2e`, `npm --prefix e2e run check`, `npm --prefix e2e test`;
  новые сценарии `e2e/tests/admin.spec.ts` связаны с `docs/spec/test-plan.md`.
- Каждый новый экран открыт в приложении на 360 и 1280 px, пройдены положительный
  и негативный сценарии, скриншоты в `docs/reviews/u13-admin/`.
- Итоги и непроверенное — в `docs/agent-log.md`. Коммит, PR и независимое ревью —
  отдельные действия по поручению человека; до них работа не объявляется принятой.
