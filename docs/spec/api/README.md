# API-контракт

| Файл | Что внутри |
|---|---|
| [`openapi.yaml`](openapi.yaml) | HTTP-контракт модулей 1–4, входа, регистрации и сессии (OpenAPI 3.1) — источник правды для backend и frontend (ADR004) |
| [`redocly.yaml`](redocly.yaml) | Правила линтера контракта |
| [`module-seams.md`](module-seams.md) | Java-интерфейсы между модулями разных дорожек, протокол транзакции начисления, события |

## Как читать контракт

- Начните с `info.description`: там соглашения по путям, типам, ошибкам, CSRF и смене пароля.
- Каждая операция:
  - `x-roles` — какие роли с сессией её вызывают; публичная операция вместо него помечена `x-public: true`;
  - строка «Доступ:» в описании — правило принадлежности ресурса (мастер-R3, KTD23);
  - `x-status` — `planned` или `implemented`.
- Ошибки — `Problem` с `code` из `ProblemCode`; таблица кодов и статусов — в описании `ProblemCode`. Варианты с обязательными данными: `ValidationProblem` (`fieldErrors`), `BudgetProblem` (`budgets`), `StudentsProblem` (`studentIds`).
- Примеры собраны на одних демо-данных, согласованных с каркасами:
  - ФИТ (id 1), группы ИВТ-21 (11) и ИВТ-22 (12), кафедра ПИ (101);
  - студенты: Смирнова А. О. — 1001, Кузнецов Д. А. — 1002, Иванова И. И. — 1003, Орлов Н. С. — 1004, членство `PENDING`;
  - преподаватели: Петров П. П. — 2001, допущен; Волков М. И. — 2002, без допуска;
  - администратор — 3001;
  - точка «Лабораторная 3» — 501, стоимость 60;
  - активности 601–603.
- Блок штрафа (`Penalty`) приведён в пяти фазах, на числах мастер-AE1–AE4: примеры у `getAssignment`.

## Проверка

```bash
npx --yes @redocly/cli@2.76.0 lint --config docs/spec/api/redocly.yaml docs/spec/api/openapi.yaml
```

Правила проекта (`rule/*` в `redocly.yaml`) — все с `severity: error`. Они требуют:
- у каждой операции `operationId`, `x-status` (строка `planned` или `implemented`) и ровно одно из двух: `x-roles` — массив из `AUTHENTICATED`, `STUDENT`, `TEACHER`, `ADMIN` — или `x-public: true`;
- у публичной операции — `security` без сессии: `[]` для чтения, `[{csrf: []}]` для входа и регистрации;
- у непубличной — явный непустой `security` с сессией и ответы `401`, `403`; глобальный `security` — только запасной;
- у изменяющей — непустой `security` с CSRF; у чтения — без CSRF;
- у успешного ответа — пример.

`x-public` отделён от `x-roles`, чтобы смешанный массив вроде `[PUBLIC, STUDENT]` не выпадал из обеих групп правил. Форма расширений проверяется по длине: `maxLength: 4` у `x-roles` отличает массив от строки-роли, `minLength: 7` у `x-status` — строку от массива.

Примеры проверяются по схемам, и лишнее поле в них — ошибка.

**Предел проверки примеров.** Лишнее поле не ловится в схемах, собранных через `allOf`; отсутствие обязательного поля ловится. Поэтому схемы, где лишнее поле означает утечку, плоские, без `allOf`:
- представления профиля и задач в нём; у них ещё и `additionalProperties: false`, так что `PublicProfileTask` не может получить `assignmentId` или ссылку и по самой схеме;
- варианты `CurrentUser`.

Через `allOf` собраны только варианты `Problem`.

Каждое правило проверено негативным прогоном на копии контракта: 29 нарушений, на каждом линтер падал с ненулевым кодом. В частности, ловятся:
- операция без `x-roles` и `x-public` или с обоими; роль вне перечня, `PUBLIC` в `x-roles`, роль строкой, `x-status` массивом, `x-public: false`;
- непубличная операция без явного `security`, с `security: []`, без сессии, без `403`;
- публичная с сессией или без `security`;
- `POST` с `security: []` или без CSRF; `GET` с CSRF;
- пример не того типа, с лишним полем, без обязательного поля, без обязательного поля варианта `Problem`;
- `url` или `assignmentId` в задаче чужого профиля, сдача с неверным `kind`;
- неизвестный `code`.

## Как расширять

1. Правка `openapi.yaml` в PR юнита до кода: операция с `x-status: planned`, роли, «Доступ:», ответы, примеры на демо-данных. Новый код ошибки — только в `ProblemCode` и его таблице.
2. Линтер зелёный.
3. После мастер-U2 и U7 плана U3: `npm --prefix frontend run api:gen` перегенерирует типы и примеры для моков; пустой `git diff` проверяет CI.
4. Реализация. Юнит, реализовавший операцию, переводит её в `x-status: implemented`. `ContractCompatibilityIT` требует присутствия операции в springdoc и проверяет совместимость исходных схем OpenAPI 3.1 через oasdiff. DTO явно описывает обязательность, nullable и ограничения из контракта: например, `@Schema(requiredProperties = ...)` и Bean Validation. Проверяются параметры, тела запросов и успешные ответы без понижения формата и удаления ограничений ответа. Перед verify установите закреплённый инструмент: `node tools/install-oasdiff.mjs`.
5. Изменение шва — правкой `module-seams.md` с ревью владельца и потребителей.

Администрирование (мастер-U13), подтверждение email и членства (мастер-U15) и модули P2–P4 добавляют свои операции так же.

## Матрица сценариев

Как требования базы мастер-плана покрыты контрактом и швами. «Владелец проверки» — юнит мастер-плана, в котором лежит тест сценария. Матрицу доступа «операция × роль × принадлежность» с негативным тестом на каждую строку строит мастер-U12 по `x-roles` и строкам «Доступ:».

| R | Сценарий (мастер-U) | Операции контракта | Шов, событие, фон | Владелец проверки |
|---|---|---|---|---|
| R1 | Регистрация студента; группы фильтруются по факультету; группа не того факультета — ошибка поля (U5) | `registerStudent`, `listFaculties`, `listFacultyGroups` | `DirectoryApi.group` | U5: `RegistrationIT`, `RegisterForm.test` |
| R2 | Преподаватель регистрируется без допуска, плашка «ждёт подтверждения»; email из списка не регистрируется студентом (U5) | `registerTeacher`, `listFacultyDepartments`, `getCurrentUser` (`teacherApproved`), `registerStudent` (`EMAIL_RESERVED_FOR_TEACHER`), `listAccessibleGroups` (пусто) | `TeacherScope` пуст без допуска; `DirectoryApi.department` | U5 |
| R3 | Роли и принадлежность ресурса: чужой преподаватель, студент чужой группы (U5, U7, U9, U11) | все операции: `x-roles`, «Доступ:», `403 FORBIDDEN`, `GROUP_NOT_ACCESSIBLE` | `CurrentUser`, `TeacherScope`; авторство проверяет модуль-владелец | каждый юнит; сводно — U12 |
| R4 | Справочники загружены в demo (U4) | `listFaculties`, `listFacultyGroups`, `listFacultyDepartments` | сид Liquibase `context: demo` | U4: `DirectoryApiIT` |
| R5 | Профиль открыт всем вошедшим; задача в чужом профиле без перехода, у администратора — «Материалы сдачи» (U10) | `getUserProfile` (`PUBLIC`, `OWN`, `ADMIN`), `getAssignment` | `GroupMembers.card`, `StudentAssignments`, `LedgerApi.balances`, `DirectoryApi.department` | U10: `ProfileIT` |
| R6 | Строка истории в формате R6; штраф — источник точка, автор «Система» (U10) | `listLedgerEntries` | `LedgerApi.history`, снимки источника и автора | U10: `ProfileIT`, `HistoryItem.test` |
| R7 | Точка на две группы: назначения только `ACTIVE`; шаг и интервал зафиксированы; недоступная группа — `403`; автор меняет название и описание, остальное — `400` (U7) | `createCheckpoint`, `getCheckpointDefaults`, `getCheckpoint`, `updateCheckpoint` | `TeacherScope.requireAccessible`, `GroupMembers.activeStudents`, `SettingsApi`, `CheckpointCreated` | U7: `CheckpointIT` |
| R8 | Регистрация студента даёт `INITIAL` +1000 (U5) | `registerStudent` | `LedgerApi.post` | U5: `RegistrationIT` |
| R9 | Начисление семестра со срезкой, повтор ничего не меняет (U8) | — (запуск вручную — мастер-U13) | `SemesterAccrualJob` → `LedgerApi.post` | U8: `SemesterAccrualIT` |
| R10 | Сдача из `OPEN`, в том числе после дедлайна, останавливает штраф; повторная — `INVALID_STATE`; файл 11 МБ или `.exe` отклоняется; каждая сдача — новая версия, все версии видят владелец, автор точки и администратор (U7) | `submitAssignment`, `getAssignment` (`submissions`), `downloadSubmissionFile`, `downloadSubmissionVersionFile` | — | U7: `CheckpointIT` |
| R11 | Списание по слотам; блок штрафа показывает следующее списание (U8) | `getAssignment`, `listMyAssignments` (`penalty`) | `PenaltyJob` → `LedgerApi.post` | U8: `PenaltyPolicyTest`, `PenaltyJobIT` |
| R12 | Потолок по фактически списанному; возврат не обнуляет (U8) | `returnAssignment`, `penalty.charged` | `PenaltyJob` | U8 |
| R13 | Пропущенные слоты не списываются (U8) | — | `PenaltyJob` | U8: AE7 |
| R14 | Страница точки, массовый приём с итогом, возврат с комментарием (U7) | `listMyCheckpoints`, `listCheckpointAssignments`, `acceptAssignments`, `returnAssignment` | — | U7: `CheckpointIT` |
| R15 | Мои задачи со статусами; при `PENDING` — «Членство ждёт подтверждения» (U7) | `listMyAssignments`, `getAssignment` | — | U7: `AssignmentStatusPolicyTest`, `TaskList.test` |
| R16 | Новичок получает назначения только по точкам с дедлайном позже активации; гонка с созданием точки; отложенная доставка `CheckpointCreated` (U7) | — | `MembershipActivated`, `CheckpointCreated`, правило `activatedAt` < дедлайна | U7: `CheckpointIT` (AE11, гонка, задержка) |
| R17 | Создание активности: максимум ≥ 2; недоступная группа — `403`; студент другой группы не видит (U9) | `createActivity`, `listActivities`, `getActivity` | `TeacherScope.requireAccessible` | U9: `ActivityIT` |
| R18 | Запись и отмена на `SOCIAL` до начала (U9) | `enrollInActivity`, `cancelEnrollment` | `GroupMembers.membership` | U9: `ActivityIT` |
| R19 | Подтверждение после начала, округление, только записавшиеся на `SOCIAL`, повтор с тем же уровнем безопасен (U9) | `listActivityParticipants`, `previewParticipation`, `confirmParticipation` | `GroupMembers`, `LedgerApi.post` | U9: `AwardCalculatorTest`, `ActivityIT` |
| R20 | Бюджет на группу в месяц по Москве; превышение отклоняет целиком с остатками (U9) | `previewParticipation`, `confirmParticipation` (`BUDGET_EXCEEDED`), `listBudgets` | `SettingsApi.monthlyBudgetLimit`, протокол транзакции начисления | U9: `BudgetPolicyTest`, `ActivityIT` |
| R21 | Остаток, история и статистика начислений; сумма за месяц равна расходу (U9) | `listBudgets`, `listMyAwards`, `getMyAwardsSummary` | — | U9: `ActivityIT` |
| R22 | Рейтинг доступной группы по АР, БУА, БОА, ничья по ФИО; недоступная — `403` (U11) | `getGroupRating` | `TeacherScope`, `GroupMembers.activeStudents`, `LedgerApi.balances` | U11: `GroupRatingsIT` |
| R23 | Лента наград: только `ACTIVITY_AWARD`, новые сверху, курсор без дублей (U11) | `listAwardsFeed` | `LedgerApi.awards`, `GroupMembers.users` | U11: `AwardsFeedIT` |
| R31 | Вход после подтверждения email; при `off` подтверждения нет (U15, P2) | `login` (`EMAIL_NOT_VERIFIED`), `registerStudent`, `registerTeacher` (`nextStep`), `getAuthConfig` | подтверждение по ссылке — мастер-U15 расширяет контракт | U15; до него U5 при `off` |
| R47 | Пометка «Демо-стенд»; данные поверх сида исчезают (U5, U7) | `getAuthConfig` (`demo`) | профиль `demo`, очистка тома файлов | U5; U7 (том) |
| R48 | Пометка «Подтверждение email выключено» (U5) | `getAuthConfig` (`emailVerification`) | `MailModeValidator` (мастер-U2) | U5 |
| R49 | Обязательная смена пароля: до смены — только смена, `me`, выход; смена завершает другие сессии (U5) | `changePassword`, `getCurrentUser` (`mustChangePassword`), `403 PASSWORD_CHANGE_REQUIRED` | выставление временного пароля — мастер-U13 | U5: `RegistrationIT` |
| R50 | Первый администратор в prod сменяет пароль при первом входе (U5) | `login`, `changePassword` | старт `prod` с переменными окружения | U5 |
| — | Сверка «снимок = сумма журнала» (U6) | `reconcileLedger` | `LedgerApi.reconcile` | U6: `LedgerIT` |
