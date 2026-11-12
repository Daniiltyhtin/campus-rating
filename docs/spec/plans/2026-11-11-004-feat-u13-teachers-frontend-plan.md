---
title: U13 frontend — допуск преподавателей и выдачи доступа
type: feat
date: 2026-11-11
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: docs/spec/plans/2026-10-31-001-feat-campus-rating-mvp-plan.md
execution: code
---

# U13 frontend — допуск преподавателей и выдачи доступа

## Goal Capsule

- **Цель.** Администратор из «Справочников» открывает преподавателей, рассматривает заявку с проверкой/исправлением кафедры, отклоняет с явным предупреждением об удалении аккаунта, выдаёт/снимает доступ четырёх видов и видит доступные группы из API.
- **Источники.** Мастер-U13, R2/R3/R24, KTD17/KTD23, ADR007, glossary, screen-map; контракт admin teachers/teacher grants. R40/R41/ADR009 для вида и доступности. R/KTD не меняются.
- **Граница.** Только frontend и предварительный контракт. Импорт/TeacherAllowlist, пароли, семестры/настройки/старосты/материалы — следующие части U13. Backend/БД не меняются. API planned, явный MSW без fallback после ошибки. Реальная авторизация и удаление сессий отклонённого аккаунта — backend, MSW не доказывает это.
- **База.** `spec-u13-teachers-api` от `937c1c5` (PR41) → отдельный contract PR до production. Затем `u13-teachers-frontend` с PR в contract branch. На старте PR40/41 OPEN, CI4/4 SUCCESS. После принятия родителей — main и повтор интеграционных ворот.
- **Процесс.** implement-unit/ub-mode, отдельный разрешённый tester по write-tests до production. Исполнитель acceptance не меняет. Готовый зелёный результат разрешено коммитить/пушить/публиковать; без self-approve/merge.

## Files

- Контрактный PR: `docs/spec/api/openapi.yaml`, `README.md`, generated `frontend/src/api/schema.d.ts`, `problemCodes.generated.ts`, `frontend/src/mocks/examples.generated.ts` только через api:gen, этот план, `docs/reviews/u13-teachers-contract/`, журнал. YAML без anchors (урок PR40).
- Frontend: `frontend/src/features/admin/teachers/` — TeacherListPage/TeacherPage, диалоги, hooks/parsers/validation/styles, собственные тесты; `teachers.acceptance.test.tsx` — tester.
- `frontend/src/app/router.tsx`: admin/directory/teachers и admin/directory/teachers/:id внутри SessionGate; AppShell.tsx — badge количества заявок только ADMIN, общий запрос; без изменения навигационных маршрутов/ролей.
- `frontend/src/features/admin/directory/DirectoryPage.tsx`: ссылка «Преподаватели»; остальной CRUD не менять.
- `frontend/src/mocks/teachers.ts`, `teachers.test.ts`, `directory.ts`, `handlers.ts`: общий mock directory state для каталогов/teacher lookups, readUser/validCsrf — единственный auth state. Допустимый отдельный pure mock-scope helper, если необходим.
- `frontend/src/test/teachersAcceptance.tsx` — tester; вспомогательный `src/test/authAcceptance.tsx` — tester добавляет нейтральный teachers GET fixture, чтобы старые изолированные admin-сессии не попадали в cookie-based mocks при появлении badge. Старые acceptance ожидания не менять. При необходимости fixture render.tsx — tester.
- `docs/reviews/u13-teachers-frontend/`, журнал/связанные уроки frontend/AGENTS.md в пределах бюджета.

## Контракт и наблюдаемое поведение

1. **Доступ.** Только ADMIN. Guest/password — SessionGate до вызовов, non-admin — «Нет доступа», без admin feature-запросов. id — positive safe integer; неверный id — «Преподаватель не найден» без detail/grants/scope запросов (общий запрос badge может работать). API404 — такой же результат;403 — «Нет доступа», malformed/network500 — ошибка/retry, не пустые данные.
2. **HTTP.** GET `/admin/teachers` — полный список всех преподавателей (name/id), без студентов/администраторов, DTO id/fullName/email/department/teacherApproved. GET `/{teacherId}` — один преподаватель. POST `/{teacherId}/approval` с departmentId →200, DELETE `/{teacherId}/application` →204. GET/POST `/{teacherId}/grants`, DELETE `/{teacherId}/grants/{grantId}`; GET `/{teacherId}/accessible-groups` — серверный TeacherScope. Все planned/ADMIN, writes session+csrf.
3. **Список/счётчик.** `/admin/directory/teachers`, заголовок «Преподаватели», вкладки «Заявки» (default) и «Все преподаватели». Pending — teacherApproved=false; данные не выводить из email/department. ФИО со ссылкой в detail, email, кафедра/факультет, понятный статус. Loading/empty/error/retry; порядок сервера сохраняется. Badge у пункта «Справочники» — число pending из этого же query key, только когда достоверно >0; при ошибке не выдавать вымышленный0, сама ошибка/retry доступны на списке. Non-admin badge не делает запрос. Приёмка проверяет и badge после approve/reject.
4. **Карточка.** `/admin/directory/teachers/:id`, заголовок ФИО, email, кафедра/факультет и статус «Ждёт допуска»/«Допущен». Кнопки «Допустить» и «Отклонить заявку» только pending; approved не получает кнопок изменения кафедры/отклонения в рамках этой части. Переход на нового id полностью сбрасывает формы/ошибки; AbortSignal и query keys viewer/id исключают поздние старые ответы.
5. **Допуск.** Диалог «Допустить преподавателя», поля «Кафедра» (NativeSelect), исходная кафедра выбрана из server detail. Список — полный admin departments из PR40. Ошибка/пустота каталога объясняется, есть retry, нельзя отправить несуществующий departmentId. Явно написать «Проверьте кафедру: её факультет определит доступные группы». POST только departmentId, без faculty/email/teacherApproved из клиента. Повтор уже принятой заявки с той же кафедрой возвращает текущее200 (без второго эффекта), с другой кафедрой409 INVALID_STATE — обновить данные и объяснить устаревшее состояние.
6. **Отклонение.** Диалог «Отклонить заявку» с ФИО/email и текстом «Аккаунт будет удалён. Пользователь сможет зарегистрироваться заново». Только явная кнопка подтверждения; cancel/Esc не отправляют DELETE. После204 — список заявок, инвалидация list/detail/badge; без автоматической повторной отправки. Approved нельзя отклонить (409 INVALID_STATE).404 после гонки — объяснить, что заявка/аккаунт уже отсутствует, обновить список.
7. **Выдачи доступа.** В detail отдельный список «Выдачи доступа» и кнопка «Выдать доступ», для обеих стадий преподавателя; pending сопровождается пояснением «До допуска доступных групп нет». Вид из API: GROUP с group ref, FACULTY с faculty ref, FACULTY_COURSE с faculty/course, COURSE только course. Показывать названия и вид без технических enum в пользовательском тексте. Пустое/загрузка/ошибка/retry независимы от карточки/scope. Снятие — диалог «Снять доступ» с описанием target, подтверждение/отмена.
8. **Форма выдачи.** NativeSelect «Вид доступа»: «Группа», «Факультет», «Курс факультета», «Курс университета». GROUP — только «Группа»; FACULTY — только «Факультет»; FACULTY_COURSE — факультет + «Курс»; COURSE — только курс. При смене вида очищать несоответствующие поля/ошибки, в payload их нет, не null. ids выбираются из полных admin каталогов PR40; course — целое1…6. До HTTP валидировать, ошибки доступны у полей. В пустом/ошибочном каталоге не отправлять фиктивный id; retry каталога. Нельзя ограничивать выбор факультетом кафедры преподавателя. Повтор точно такой же выдачи идемпотентен: существующая запись200, новая201; frontend не повторяет writes автоматически.
9. **Снятие/права.** DELETE пары teacherId/grantId; чужой grant по этой паре404, чужую выдачу не удалять. После204 query invalidation, не копировать серверный список в local state. GET scope — единственный источник отображаемых доступных групп; не суммировать выдачи и базовый факультет в UI, не вычислять курс/год. Снятие дополнительной выдачи не обещает закрыть группу, доступную ещё по другому основанию. Текст поясняет: «Группы определяет сервер по кафедре, выдачам и текущему учебному году». Scope loading/empty/error/retry независимы; pending возвращает пустой scope и пояснение.
10. **Запись и ошибки.** retry=false, pending блокирует повтор submit/delete/close и конфликтующие кнопки; успешный write + GET500 повторяет только GET. CSRF_INVALID — отдельное «Обновить защиту», без replay;403/404/409/fieldErrors/network500/malformed — понятные тексты, ввод сохраняется, raw ProblemDetail не показывается. INVALID_STATE означает «Данные изменились. Обновите карточку» с действием чтения. Неверный success/id — не показывать ложный успех, предложить обновление перед повтором. Итог role=status без заливки.
11. **Парсинг/данные.** Generated discriminated unions, safe ids, boolean teacherApproved, refs/непустые строки/email; строгое сочетание variant полей и course1…6. Лишние поля другого варианта не принимаются. Write validation — чистые функции. Query keys включают viewer/id, AbortSignal; после мутаций инвалидируются teachers/list/detail/grants/scope, профиль и существующие scope-dependent queries. Серверное состояние — TanStack Query.
12. **MSW.** Teacher seeds из generated CurrentTeacher/teacherNotApproved, каталоги — общий state текущего directory mock, без второго auth state. Scope вычисляет только fake API по Clock/Europe/Moscow и KTD23 (faculty кафедры ∪ выдачи, course1…6 с границей1сентября), никогда UI. Четыре варианта проверяются mock unit на чужом факультете и границе года. Approve/reject/grant removal отражаются в следующих admin GET, pending scope пуст. Состояния account/login/почты старого auth mock не объявлять полной backend моделью; реальное завершение сессий/повторная регистрация — непроверено здесь. Напрямую возвращать teacher groups в auth /me для другой сессии не требуется.
13. **Вид/клавиатура.** Mantine/общие токены/три темы,360/1280 без overflow, длинные ФИО/email/targets переносятся, обе стороны целей>=44/52, текст>=4.5/7. Диалоги focus trap/видимый focus/Esc/cancel/возврат к opener; после reject — focus заголовка списка. U23 не объявляется выполненным.

## Цепочка требований

| R/KTD | Пример/граница | Проверка |
|---|---|---|
| R3/R24 | ADMIN/прочие/guest/password, bad id/403/404 | guards/нет feature-запросов; acceptance |
| R2/R24 | Pending2002, approved2001, badge1→0 | server statuses/list/detail, approve/reject +invalidation; acceptance |
| R2/KTD23 | Approval изменяет departmentId101→другой факультет | точный body, каталог/error/empty, server scope; acceptance/mock |
| R2/R24 | Reject confirm/cancel/409/404 | удаление только заявки/понятный результат/без replay; acceptance |
| R24/KTD23 | GROUP/FACULTY/FACULTY_COURSE/COURSE | payload ровно нужных полей,1/6/0/7/fraction, чужой факультет; acceptance/validation/mock |
| R24 | create duplicate200/remove204/wrong owner404 | правильный teacher/grant, подтверждение, одна mutation; acceptance/mock |
| R3/KTD23 | scope ответ сервера не равен простой сумме UI | показать ответ без вычисления; acceptance |
| KTD23 | Moscow31августа/1сентября, course вне1…6 | fake API pure scope +Clock tests; реальный backend не доказывается |
| R24 | loading/empty/500/malformed/retry независимых списков | acceptance/parser |
| R24 | CSRF/403/409/fieldErrors/late reply/double click/write success GET500 | ошибки/изоляция/чтение без повторной записи; acceptance |
| R40/ADR009 |360/1280 ×3темы, dialogs/long/focus | browser screenshots/метрики |

## Порядок и ворота

1. Отдельный contract PR planned операций, схемы/примеры, Redocly + заведомо плохие variant/course examples, api:gen дважды (идентично), frontend check/build/tools/docs/diff, без backend. До frontend production контракт опубликован.
2. Tester через реальные routes/MSW/isolated QueryClient+auth/theme: сначала Red на placeholder, без импорта будущего production. Test-only фикстуры старых admin-сессий расширяет tester; ожидания не ослабляет.
3. Собственные parser/validation/mock tests Red → production → независимые acceptance Green. Условия готовности1…7 из implement-unit закрыты явными правилами выше; продуктовые R/KTD не додумываются.
4. Frontend check/build; tools140+docs/diff, backend/API/generated diff относительно контракта пуст. Явный MSW browser все сценарии на360/1280 ×3темы и снимки; Docker доступен — compose/e2e и cleanup.
5. Отчёт/матрица/handoff, agent-log и уроки, зелёный коммит явных путей, stat, push/PR в contract branch. Независимый reviewer/человек; без approve/merge исполнителем.
