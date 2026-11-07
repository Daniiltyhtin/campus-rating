# Швы между модулями

Сигнатуры и гарантии публичных интерфейсов, через которые модули разных дорожек вызывают друг друга. HTTP-контракт — [`openapi.yaml`](openapi.yaml), здесь — Java-уровень внутри монолита. Источник решений — план U3 (`docs/spec/plans/2026-11-07-002-feat-u3-api-contract-plan.md`, KTD15) и мастер-план (KTD2, KTD3, KTD5, KTD11, KTD23, диаграмма зависимостей модулей).

## Правила

- **Где живёт шов.** Интерфейс и типы его сигнатуры — в корне пакета модуля-владельца (`ru.campusrating.<module>`): по KTD17 плана U2 корень — публичный API модуля, вложенные пакеты закрыты. Внутренние `domain` и `internal` соседям не нужны. Поэтому `Currency` и `EntryKind` лежат в корне `ledger`, а не в `ledger.domain`, как в списке файлов мастер-U6.
- **Кто пишет код.** Интерфейс создаёт владелец в своём юните. До готовности реализации потребитель в своих тестах использует двойник по сигнатуре из этого документа: Mockito-мок или `@MockitoBean` в `*IT`. В `src/main` заглушек нет.
- **Направления зависимостей** — только по диаграмме мастер-плана: `ledger` → `settings`; `identity` → `directory`, `ledger`; `checkpoint` → `identity`, `ledger`, `settings`; `activity` → `identity`, `ledger`, `settings`; `profile` → `identity`, `ledger`, `checkpoint`, `directory`; `feed` → `ledger`, `identity`. Названия групп и ФИО модули `checkpoint`, `activity` и `feed` берут у `identity` (`GroupMembers`): у них нет зависимости от `directory`. Нарушение ловит `ModularityTest` (мастер-U2).
- **Идентификаторы** в сигнатурах — `long`. Общих типов вроде `StudentId` между модулями нет, чтобы модули не тянули зависимость ради типа.
- **Ошибки** — исключение `ru.campusrating.shared.error.ApiException` с `ProblemCode` из контракта (зеркало перечисления `ProblemCode`). Его и `@RestControllerAdvice`, превращающий исключение в `application/problem+json`, создаёт мастер-U4: это первый юнит с API, а мастер-U5 от него зависит. Шов бросает `ApiException` с кодом, указанным в его описании. Контроллер код не переводит.
- **Время** — `Instant` из инжектируемого `Clock` (мастер-KTD6), месяц и дата по Москве — `shared.domain.MoscowTime` (план U2).
- **Изменение шва** — правкой этого документа в PR с ревью владельца и потребителей, как у контракта.

## Протокол транзакции начисления

Мастер-KTD5: блокировки в одном порядке, баланс последним.

1. Модуль-владелец (`checkpoint`, `activity`, `identity`) открывает транзакцию (`@Transactional`).
2. Он берёт `SELECT … FOR UPDATE` своих строк по возрастанию id. Назначения — для сдачи, приёма, возврата и штрафа. Строки бюджета `(преподаватель, группа, месяц)` — по возрастанию id группы, только `activity`.
3. Под блокировкой он перепроверяет состояние и пишет доменные изменения.
4. Последним шагом он вызывает `LedgerApi.post(...)` одной пачкой. `LedgerApi` присоединяется к транзакции вызывающего (`Propagation.MANDATORY`): без открытой транзакции он падает, собственного commit не делает. Балансы он блокирует сам, по возрастанию `(studentId, currency)`.
5. Commit делает вызывающий. Любое исключение откатывает и доменную запись, и проводки.

Нарушение порядка, например баланс раньше бюджета, — повод для `request changes` на ревью (мастер-KTD5).

## 1. `LedgerApi` — журнал проводок

- **Владелец:** `ledger`, мастер-U6, дорожка A.
- **Потребители:** `identity` (U5 — `INITIAL`), `checkpoint` (U7, U8 — `PENALTY`, АР для блока штрафа), `activity` (U9 — `ACTIVITY_AWARD`), `profile` (U10 — балансы и история), `feed` (U11 — лента, рейтинг). Внутри дорожки A его вызывает ещё начисление семестра (U8).

```java
package ru.campusrating.ledger;

public interface LedgerApi {

    /** Только внутри транзакции вызывающего (MANDATORY). Результаты — в порядке входа. */
    List<PostingResult> post(List<Posting> postings);

    Balances balances(long studentId);                          // нет строки — 0, 0, 0
    Map<Long, Balances> balances(Collection<Long> studentIds);  // ключ — каждый запрошенный id

    LedgerPage<LedgerEntryView> history(long studentId, Optional<Currency> currency,
                                        Optional<String> cursor, int limit);
    LedgerPage<AwardEntryView> awards(Optional<String> cursor, int limit); // только ACTIVITY_AWARD

    List<BalanceMismatch> reconcile();
}

public enum Currency { AR, BUA, BOA }
public enum EntryKind { INITIAL, SEMESTER_ACCRUAL, PENALTY, ACTIVITY_AWARD }
public enum SourceType { REGISTRATION, SEMESTER, CHECKPOINT, ACTIVITY }

public record Posting(long studentId, Currency currency, EntryKind kind, int nominalAmount,
                      Source source, @Nullable Author author, String idempotencyKey) {}
public record Source(SourceType type, @Nullable Long id, String title) {}   // title — снимок
public record Author(long userId, String fullName) {}                       // снимок ФИО

public sealed interface PostingResult {
    record Posted(long entryId, int amount, int balanceAfter) implements PostingResult {}
    record AlreadyPosted(long entryId, int amount) implements PostingResult {} // тот же ключ
    record Zero() implements PostingResult {}                                  // срезано до 0, не записано
}

public record Balances(int ar, int bua, int boa) {}
public record LedgerEntryView(long id, Instant createdAt, Currency currency, int amount, EntryKind kind,
                              Source source, @Nullable Author author) {}
public record AwardEntryView(long entryId, Instant createdAt, long studentId, int amount,
                             Currency currency, Source source, Author author) {}
public record LedgerPage<T>(List<T> items, @Nullable String nextCursor) {}
public record BalanceMismatch(long studentId, Currency currency, int balance, int ledgerSum) {}
```

**Гарантии и правила.**
- **Знак и срезка.** `nominalAmount` знаковый: штраф отрицательный, остальное положительное. Фактическую сумму под блокировкой баланса считает `BalanceRules`. АР не выходит за 0…1000 (мастер-R9, R11). БУА и БОА не срезаются. Сумма, срезанная до 0, даёт `Zero`, проводки нет. По `Posted.amount` штраф узнаёт, сколько реально списано, и добавляет модуль к `charged` назначения.
- **Идемпотентность.** Уникальный `idempotencyKey` (мастер-KTD3). Повтор возвращает `AlreadyPosted` с исходной суммой и баланс не меняет. Формат ключей:
  - `initial:{studentId}`;
  - `semester:{semesterId}:{studentId}`;
  - `penalty:{assignmentId}:{slot}`;
  - `award:{activityId}:{studentId}`.
- **Снимки.** `Source.title` и `Author.fullName` записываются в проводку при её создании. Журнал не зависит от `checkpoint`, `activity` и `identity` и не читает их при показе истории: переименование точки или смена ФИО прошлое не меняют. Автор `null` означает систему: штраф, семестр, стартовые баллы.
- **Только через `post`.** Запись в таблицы журнала и балансов закрыта границей модуля (мастер-KTD3). Чтение балансов для рейтинга и профиля идёт из снимка, без `SUM` по журналу.
- **Курсоры** `history` и `awards` — непрозрачные строки по `(createdAt desc, id desc)`. Битый курсор — `ApiException(VALIDATION_FAILED)` по полю `cursor`.
- **Тестовый двойник** для дорожки B до мастер-U6: мок `post` возвращает `Posted` с `amount = nominalAmount`; `balances` — фиксированные значения из примеров контракта.

## 2. `CurrentUser` — кто вошёл

- **Владелец:** `identity`, мастер-U5, дорожка B.
- **Потребители:** все модули с контроллерами (U7, U9, U10, U11).

```java
package ru.campusrating.identity;

public interface CurrentUser {
    AuthenticatedUser require();            // нет сессии — ApiException(UNAUTHENTICATED)
    Optional<AuthenticatedUser> find();
}

public enum Role { STUDENT, TEACHER, ADMIN }
public enum MembershipState { PENDING, ACTIVE }

public record AuthenticatedUser(long id, Role role, String fullName, boolean mustChangePassword,
                                boolean teacherApproved,            // false у не-преподавателя
                                @Nullable Membership membership) {} // только у студента
public record Membership(long groupId, MembershipState state, @Nullable Instant activatedAt) {}
```

- **Гарантии.** Значения берутся из сессии и базы на момент запроса. Блокировку по `mustChangePassword` (`403 PASSWORD_CHANGE_REQUIRED`) делает фильтр безопасности `identity`, а не модули: при работающем фильтре `require()` пользователя с этим флагом в чужой модуль не вернёт. Роль операции проверяет `SecurityConfig` по `x-roles` контракта. Принадлежность ресурса проверяет модуль-владелец сам: автор точки, автор активности, владелец назначения.
- **Тестовый двойник:** мок `require()` с демо-пользователями из примеров контракта (1001 — студент, 2001 — допущенный преподаватель, 2002 — без допуска, 3001 — администратор).

## 3. `TeacherScope` — доступные группы

- **Владелец:** `identity`, мастер-U5, дорожка B. Правило — мастер-KTD23, ADR007.
- **Потребители:** `checkpoint` (U7), `activity` (U9), `feed` (U11); позже `poll`, `analytics`.

```java
package ru.campusrating.identity;

public interface TeacherScope {
    Set<Long> accessibleGroupIds(long teacherId);          // без допуска — пусто
    boolean isAccessible(long teacherId, long groupId);
    /** Все группы доступны, иначе ApiException(GROUP_NOT_ACCESSIBLE) с названием первой недоступной. */
    void requireAccessible(long teacherId, Collection<Long> groupIds);
}
```

**Где проверять доступные группы, а где авторство** (KTD12 плана U3):

| Действие | Проверка |
|---|---|
| Создание точки, активности | `requireAccessible` по всем `groupIds` |
| Рейтинг группы | `requireAccessible` по группе |
| Приём, возврат, чтение назначений и сдач своей точки | авторство точки, `TeacherScope` не вызывается |
| Участники, предпросмотр и подтверждение участия своей активности | авторство активности |
| Бюджет | `accessibleGroupIds` ∪ группы своих активностей |

Автор сохраняет работу со своими ресурсами после потери доступа к группе (мастер-KTD23).

- **Тестовый двойник:** мок с набором групп преподавателя 2001 = {11, 12}; у 2002 — пусто.

## 4. `GroupMembers` — состав групп и подписи

- **Владелец:** `identity`, мастер-U5, дорожка B.
- **Потребители:** `checkpoint` (U7 — назначения и встречная сверка), `activity` (U9 — участники, допуск к подтверждению, группировка бюджета), `profile` (U10), `feed` (U11 — рейтинг и подписи ленты).

```java
package ru.campusrating.identity;

public interface GroupMembers {
    /** ACTIVE-студенты групп по (groupId, fullName, id). */
    List<StudentMember> activeStudents(Collection<Long> groupIds);
    /** Членство студента в любом состоянии; пусто — не студент. */
    Optional<StudentMember> membership(long studentId);
    Map<Long, StudentMember> memberships(Collection<Long> studentIds);
    /** ФИО для подписей; ключ — каждый найденный id. */
    Map<Long, UserRef> users(Collection<Long> userIds);
    /** Название и факультет группы для ответов API; читает directory. */
    Map<Long, GroupRef> groups(Collection<Long> groupIds);
}

public record StudentMember(long studentId, String fullName, long groupId, MembershipState state,
                            @Nullable Instant activatedAt) {}
public record UserRef(long id, String fullName) {}
public record GroupRef(long id, String name, long facultyId, String facultyCode, String facultyName) {}
```

- **Гарантии.** Минимальные таблицы `app_user` и `group_membership` создаёт мастер-U4 (A), мастер-U5 их расширяет. Методы только читают и работают и в транзакции вызывающего, и без неё. Для студента с `PENDING`-членством `activeStudents` его не вернёт, а `membership` вернёт с `state = PENDING`: по нему `activity` отвечает `STUDENT_NOT_ELIGIBLE` или `MEMBERSHIP_NOT_ACTIVE`.
- **Тестовый двойник:** мок по демо-данным контракта — ИВТ-21 (11): 1001, 1002, 1003 `ACTIVE`, 1004 `PENDING`.

## 5. `SettingsApi` — настройки

- **Владелец:** `settings`, мастер-U4, дорожка A.
- **Потребители:** `checkpoint` (U7, U8), `activity` (U9), `ledger` (U8, начисление семестра).

```java
package ru.campusrating.settings;

public interface SettingsApi {
    int penaltyStep();                 // по умолчанию 20
    Duration penaltyInterval();        // по умолчанию 1 минута
    Duration penaltyPollPeriod();      // campus.penalty.poll-period, по умолчанию 1 с
    Duration minPenaltyInterval();     // 2 × penaltyPollPeriod (мастер-KTD4)
    int semesterAccrual();             // N, по умолчанию 200
    int monthlyBudgetLimit();          // по умолчанию 1000
}
```

- **Гарантии.** Значение читается при каждом вызове: изменение администратора действует без рестарта (мастер-KTD10). Кэшировать значения в потребителе нельзя. Шаг и интервал копируются в точку при её создании.
- **Тестовый двойник:** мок со значениями по умолчанию; e2e ускоряет штраф через `docker-compose.e2e.yml`.

## 6. `StudentAssignments` — задачи студента для профиля

- **Владелец:** `checkpoint`, мастер-U7, дорожка A.
- **Потребитель:** `profile` (U10, дорожка B).

```java
package ru.campusrating.checkpoint;

public interface StudentAssignments {
    /** OPEN и SUBMITTED: сначала OVERDUE, затем по дедлайну. */
    List<StudentAssignmentView> active(long studentId);
    /** ACCEPTED, от недавних. */
    List<StudentAssignmentView> lastCompleted(long studentId, int limit);
    int completedCount(long studentId);
}

public enum AssignmentStatus { IN_PROGRESS, SUBMITTED, OVERDUE, ACCEPTED }

public record StudentAssignmentView(long assignmentId, String title, Instant deadline,
                                    AssignmentStatus status, int charged) {}
```

- **Гарантии.** Статус вычисляется на `Clock` вызова (мастер-KTD7). Материалов сдачи и комментария возврата в типе нет: профиль не может их отдать по ошибке (KTD9 плана U3). Представление `PUBLIC`, `OWN` или `ADMIN` выбирает `profile` по смотрящему: `assignmentId` он отдаёт только себе и администратору.
- **Тестовый двойник:** мок по примерам `StudentProfile` контракта.

## 7. События

Публикуются через `ApplicationEventPublisher` в транзакции источника. Слушатели — `@ApplicationModuleListener`: асинхронно, после commit, в своей транзакции, с реестром публикаций (мастер-KTD11, ADR005).

```java
package ru.campusrating.identity;
public record MembershipActivated(long studentId, long groupId, Instant activatedAt) {}

package ru.campusrating.checkpoint;
public record CheckpointCreated(long checkpointId) {}   // внутри дорожки A
```

| Событие | Публикует | Слушает | Что делает слушатель |
|---|---|---|---|
| `MembershipActivated` | `identity`: при регистрации студента, пока нет мастер-U15, членство сразу `ACTIVE`; потом — при подтверждении членства | `checkpoint` (U7) | создаёт назначения по точкам группы с дедлайном позже `activatedAt` (мастер-R16, AE11) |
| `CheckpointCreated` | `checkpoint` при создании точки | `checkpoint` (U7) | читает `GroupMembers.activeStudents(groups)` и создаёт недостающие назначения |

- **Гонка точки и активации** (мастер-U7). Точка создаётся, а членство активируется параллельно. Каждая сторона видит данные другой только после её commit, поэтому одна из двух сверок обязательно увидит пару (точка, студент). Обе вставляют назначение с `ON CONFLICT (checkpoint_id, student_id) DO NOTHING`, так что двойная сверка безопасна. Проверка — тест гонки с барьерами в `CheckpointIT`.
- **Повтор доставки.** Реестр публикаций может доставить событие повторно после сбоя слушателя. Оба слушателя идемпотентны по той же уникальной паре.

## Кто чей шов ждёт

| Потребитель | Ждёт | До готовности |
|---|---|---|
| U5 (B): стартовые 1000 АР | `LedgerApi` (U6, A, 0,5 дня) | мок `post` |
| U7 (A): создание точки, назначения, доступ | `CurrentUser`, `TeacherScope`, `GroupMembers` (U5, B) | моки; `@MockitoBean` в `CheckpointIT` |
| U9 (B): начисления, бюджет | `LedgerApi` (U6), `SettingsApi` (U4) | моки |
| U10 (B): профиль | `StudentAssignments` (U7, A), `LedgerApi` | моки по примерам контракта |
| U11 (B): лента и рейтинг | `LedgerApi` | мок `awards`, `balances` |

Стыковка — вечер 09.11 (мастер-Sequencing): двойники заменяются реализациями, интеграционные тесты потребителей гоняются против настоящих бинов.
