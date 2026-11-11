---
title: U8 Penalty Scheduler and Semester Accrual - Plan
type: feat
date: 2026-11-11
artifact_contract: ce-unified-plan/v1
artifact_readiness: draft
product_contract_source: docs/spec/plans/2026-10-31-001-feat-campus-rating-mvp-plan.md
origin: docs/spec/plans/2026-10-31-001-feat-campus-rating-mvp-plan.md
execution: code
---

# U8 Планировщик штрафов и начисление семестра - Plan

## Goal Capsule

- **Цель.** АР списывается за просрочку и начисляется с началом семестра автоматически, ровно один раз и без гонок со сдачей. Блок штрафа в ответах U7 начинает показывать реальные `charged` и `lastPenaltySlot`.
- **Иерархия источников.** Мастер-план (раздел U8; R9, R11–R13, R26; AE1–AE7; KTD3–KTD6, KTD10, KTD19), ADR002, `docs/spec/api/module-seams.md` («Протокол транзакции начисления», разделы `LedgerApi` и `SettingsApi`), план U7 `docs/spec/plans/2026-11-09-002-feat-u7-checkpoints-plan.md` (схема `assignment`, `PenaltySlot`, `PenaltyView`, блокировки KTD6). Здесь R, П, KTD и U — локальные, ссылки на мастер помечены «мастер-».
- **Профиль исполнения.** U1–U3 пишет агент-исполнитель test-first, ревьюер гоняет `*IT` с Docker. Контракт не меняется: схема `Penalty` уже описывает результат.
- **Остановись и спроси, если:**
  - нужно изменить сигнатуру `module-seams.md` (например, новый шов «все студенты» в `identity`);
  - тест зеленеет только ценой записи мимо `LedgerApi` или нарушения порядка блокировок мастер-KTD5;
  - источник дат семестра (Q1) решён иначе, чем в KTD5 этого плана.
- **Зависимости и ветки.** Опирается на PR U7 (#26–#29): таблица `assignment` с `charged` и `last_penalty_slot`, `PenaltySlot`, `AssignmentTransitions`. Ветка `u8-penalty` от `main` после слияния #26–#29 (или стопкой на `u7-reading`), ветка `u8-semester` от `main` — независима от U7.

---

## Product Contract

### Summary

Два фоновых задания. Штраф — модуль `checkpoint`: раз в poll-period берёт просроченные открытые назначения и за текущий слот пишет проводку `PENALTY` через `LedgerApi`. Семестр — модуль `ledger`: при наступлении даты начала семестра каждому студенту один раз пишет `SEMESTER_ACCRUAL` и фиксирует факт обработки даже при нуле.

### Правила

| № | Правило | Источник |
|---|---|---|
| П1 | Штраф только для назначения в `OPEN` и строго после дедлайна; при `now = deadline` штрафа нет | мастер-R11, AE1 |
| П2 | Обрабатывается только текущий слот `floor((now − deadline) / interval)` с шагом и интервалом точки; пропущенные слоты не списываются | мастер-R13, AE7, ADR002 |
| П3 | Номинал списания — `min(step, cost − charged)`; пол АР 0 применяет `LedgerApi` (`BalanceRules`); фактическая сумма из `Posted.amount` прибавляется к `charged` | мастер-R11, R12, швы §1 |
| П4 | Слот обработан, даже если списано 0 (`Zero`): `last_penalty_slot` = слот; пополнение АР внутри того же слота штрафа за него не даёт | мастер-AE4, KTD4 |
| П5 | Ключ проводки — `penalty:{assignmentId}:{slot}`; повтор (`AlreadyPosted`) ничего не меняет | мастер-KTD3, AE6 |
| П6 | Сдача и штраф сериализуются блокировкой назначения; после сдачи списаний нет; возврат не обнуляет `charged`, потолок общий | мастер-KTD5, AE2, AE3 |
| П7 | Начисление семестра — `N` из `SettingsApi.semesterAccrual()` со срезкой до 1000; каждому студенту с балансом АР ровно один раз на семестр; факт `(семестр, студент, начислено)` пишется и при 0 | мастер-R9, AE5 |
| П8 | Ключ проводки семестра — `semester:{semesterId}:{studentId}`; повторный запуск того же семестра ничего не меняет | мастер-AE5, швы §1 |
| П9 | Параметры задания читаются из `SettingsApi` на каждом проходе: смена poll-period действует без рестарта | мастер-KTD10 |

### Не входит

- Экран и операции администрирования семестров и кнопка «Начать семестр сейчас» — отдельным юнитом, если останется время (Q1).
- Ускоренный стек e2e (`docker-compose.e2e.yml`) — мастер-U12.

---

## Planning Contract

### Key Technical Decisions

- KTD1. **`PenaltyPolicy` — чистая функция в `checkpoint.domain`.** Вход: состояние, дедлайн, интервал, шаг, стоимость, `charged`, `lastPenaltySlot`, `now`. Выход: `Charge(slot, nominal)` или `Skip`. `Skip`, если не `OPEN`, `now <= deadline`, `charged >= cost` или `lastPenaltySlot >= текущий слот`. Слот считает `PenaltySlot` из U7 — одна формула для блока штрафа и задания. АР в политику не входит: срезку делает журнал под блокировкой баланса.
- KTD2. **Задание штрафа — `PenaltyJob` в `checkpoint.internal`.** `SchedulingConfigurer` с триггером, который берёт задержку из `SettingsApi.penaltyPollPeriod()` перед каждым проходом (П9). Выборка кандидатов — по индексу U7 `(state, checkpoint_id)` с соединением на `checkpoint`: `state = 'OPEN'`, `deadline < now`, `charged < cost`, `last_penalty_slot` пуст или меньше текущего слота (вычисляется в SQL той же формулой). Пачка до 500 по возрастанию id; каждое назначение — в своей транзакции (`TransactionTemplate`), ошибка одного не мешает другим.
- KTD3. **Транзакция штрафа по протоколу швов.** `SELECT … FOR UPDATE` назначения → `PenaltyPolicy` на свежих данных → `LedgerApi.post(PENALTY, −nominal, ключ П5, источник CHECKPOINT с id и названием точки)` → по результату: `Posted` — `charged += amount`, `last_penalty_slot = slot`; `Zero` — только `last_penalty_slot = slot`; `AlreadyPosted` — только `last_penalty_slot = max(текущий, slot)`. Баланс блокирует сам `LedgerApi`, последним.
- KTD4. **Мастер-KTD19: без распределённой блокировки.** Два прохода одновременно безопасны: блокировка назначения сериализует их, второй видит обработанный слот (`Skip`) или получает `AlreadyPosted`.
- KTD5. **Семестр — в `ledger`.** Таблицы `semester(id, title, starts_at)` и `semester_accrual(semester_id, student_id, accrued, processed_at, primary key (semester_id, student_id))`. Студенты — строки `ledger_balance` с `currency = 'AR'`: у каждого зарегистрированного студента есть баланс АР со стартовой проводки, поэтому шов в `identity` не нужен. `SemesterAccrualJob` раз в минуту берёт семестры с `starts_at <= now` и необработанных студентов пачкой до 500; на студента — транзакция: `post(SEMESTER_ACCRUAL, N, ключ П8)` и вставка `semester_accrual` с фактической суммой (`Zero` → 0) с `ON CONFLICT DO NOTHING`. Даты семестров по умолчанию — сидом Liquibase (Q1).
- KTD6. **Время — `Clock`.** Оба задания берут `now` из `Clock` (мастер-KTD6); IT двигают `MutableClock` и вызывают проход задания напрямую, а не ждут расписание.

### Resolved Questions

- Q1. **Кто и когда начинает семестр без мастер-U13.** Решено 11.11: даты задаются сидом Liquibase в контексте `default` («Осень 2026» — 01.09.2026, «Весна 2027» — 09.02.2027). В `demo` при старте текущий семестр уже начат, и задание при первом проходе отмечает всех демо-студентов: у них по 1000 АР, начисление даёт 0. Ручного «Начать сейчас» нет. Экран семестров — отдельным юнитом, если останется время до заморозки.

### Open Questions

- Q2. **Студент, зарегистрированный после начала семестра.** По KTD5 он попадает в выборку и получает начисление со срезкой. После стартовых 1000 АР это всегда 0, но факт обработки запишется. Предложение — оставить так (мастер-R9: «каждому зарегистрированному студенту»).

### Risks and Dependencies

| Риск | Митигация |
|---|---|
| Гонка «штраф против сдачи» | KTD3: блокировка назначения и перепроверка; IT с барьером — сдача и проход одновременно |
| Двойной проход задания | KTD4 и уникальный ключ проводки; IT с двумя параллельными проходами (мастер-AE6) |
| Задание тормозит на большой выборке | Индекс U7, пачки по 500, условие на `last_penalty_slot` в SQL |
| e2e с реальным временем нестабильны | Мастер-U12: ускоренный стек и опрос состояния |

---

## Implementation Units

### U1. `PenaltyPolicy`

- **Goal.** Решение «какой слот и какой номинал списать» — чистая функция.
- **Requirements.** П1–П4; KTD1.
- **Files:** `backend/src/main/java/ru/campusrating/checkpoint/domain/PenaltyPolicy.java`; `backend/src/test/java/ru/campusrating/checkpoint/domain/PenaltyPolicyTest.java`.
- **Test scenarios:**
  - Ровно в дедлайн — `Skip`; через 1 нс — `Charge(0, 20)`.
  - Граница слота: за 1 мс до `deadline + interval` — слот 0, ровно на границе — слот 1.
  - `lastPenaltySlot` = текущий — `Skip`; меньше — `Charge(текущий)`; простой: последний 0, сейчас слот 5 — `Charge(5)` (мастер-AE7).
  - Номинал `min(step, cost − charged)`: стоимость 60, списано 50 → 10; списано 60 — `Skip`.
  - `SUBMITTED`, `ACCEPTED` — `Skip`.
- **Verification.** PIT `checkpoint.domain` ≥ 70 %, CRAP ≤ 10.

### U2. Задание штрафа

- **Goal.** Просроченные назначения теряют АР по слотам ровно один раз.
- **Requirements.** П1–П6, П9; KTD2–KTD4, KTD6.
- **Dependencies.** U1; PR U7.
- **Files:** `backend/src/main/java/ru/campusrating/checkpoint/internal/PenaltyJob.java`, репозиторий кандидатов; `backend/src/test/java/ru/campusrating/checkpoint/PenaltyJobIT.java`.
- **Test scenarios:**
  - Covers мастер-AE1. Проходы в 12:00:00, 12:00:01, 12:01:01, 12:02:01, 12:03:01 → три проводки по −20, АР 940, `charged` 60.
  - Covers мастер-AE2. Сдача в 12:01:30 → списано 40, дальше ничего.
  - Covers мастер-AE3. Стоимость 100, возврат в 12:05:10 → слот 5 при ближайшем проходе, затем 6 и 7; итого 100, АР 900.
  - Covers мастер-AE4. АР 10 → −10 в слоте 0, в слоте 1 проводки нет (`last_penalty_slot` = 1), начисление +200 внутри слота 1 штрафа не даёт, слот 2 → −20; списано 30.
  - Covers мастер-AE6. Два прохода параллельно (барьер) → одна проводка за слот.
  - Covers мастер-AE7. Часы сдвинуты на 5 интервалов → списан только текущий слот.
  - Гонка: сдача и проход одновременно → либо штраф и `SUBMITTED`, либо только `SUBMITTED`; после сдачи списаний нет.
  - `getAssignment` после проходов показывает `charged` и фазу `RUNNING`, `ZERO_BALANCE` или `CAP_REACHED` по фактическим данным.
  - Смена poll-period в настройках подхватывается следующим проходом (П9).
- **Verification.** `verify`; демо — точка с дедлайном через 2 минуты, АР падает поминутно и останавливается на потолке.

### U3. Начисление семестра

- **Goal.** С началом семестра каждый студент один раз получает `N` АР со срезкой, факт обработки сохраняется.
- **Requirements.** П7, П8; KTD5, KTD6.
- **Dependencies.** нет (только `ledger`).
- **Files:** `backend/src/main/resources/db/changelog/ledger/<ts>-semester.sql` (таблицы и сид семестров по Q1), `backend/src/main/java/ru/campusrating/ledger/internal/SemesterAccrualJob.java`; `backend/src/test/java/ru/campusrating/ledger/SemesterAccrualIT.java`.
- **Test scenarios:**
  - Covers мастер-AE5. A с АР 900 → +100; B с АР 1000 → проводки нет, строка `semester_accrual` с 0; B оштрафован до 980, повторный проход того же семестра → B остаётся 980.
  - Семестр с будущей датой не обрабатывается; после сдвига часов — обрабатывается.
  - Два прохода параллельно → одна проводка и одна строка на студента.
  - Студент с `PENDING`-членством тоже получает начисление (мастер-R9: «независимо от состояния членства»).
- **Verification.** `verify`; `LedgerApi.reconcile` без расхождений после прохода.

---

## Verification Contract

| Ворота | Команда | Когда |
|---|---|---|
| Backend | `./backend/mvnw -f backend/pom.xml verify` | U1–U3 |
| Мутации и CRAP | `./backend/mvnw -f backend/pom.xml -Pquality verify`, `node tools/crap.mjs backend/target/site/jacoco/jacoco.xml` | перед PR |
| Обвязка | `node tools/check-agent-docs.mjs`, `node --test "tools/test/*.test.mjs"` | каждый PR |
| Стек | `docker compose up`, точка с дедлайном через 2 минуты | U2 |

## Definition of Done

- Сценарии U1–U3 зелёные; PIT `checkpoint.domain` ≥ 70 %, CRAP ≤ 10, JaCoCo ≥ 80 % строк по затронутым модулям.
- На демо-стеке видно поминутное списание и остановку на потолке; повторный запуск backend не создаёт повторных проводок.
- Сид семестров по Q1 есть в changeset U3; запись в `docs/agent-log.md`.
