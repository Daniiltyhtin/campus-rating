# Архитектурные решения

Мастер-план — владелец решений: [Key Technical Decisions](../plans/2026-10-31-001-feat-campus-rating-mvp-plan.md#key-technical-decisions). ADR раскрывают их контекст, последствия и проверки. Статус `accepted` означает принятое решение, а не завершённую реализацию.

| Номер | Решение | Статус | KTD мастера |
|---|---|---|---|
| [ADR001](adr001-ledger-with-balance-snapshot.md) | Журнал проводок и снимок баланса | accepted | KTD3 |
| [ADR002](adr002-penalty-slots.md) | Штраф по слотам с параметрами точки | accepted | KTD4 |
| [ADR003](adr003-server-sessions.md) | Серверные сессии | accepted | KTD8 |
| [ADR004](adr004-api-contract-first.md) | API contract-first | accepted | KTD9 |
| [ADR005](adr005-module-events-outbox.md) | Доменные события и реестр публикаций | accepted | KTD11 |
| [ADR006](adr006-liquibase.md) | Liquibase и formatted SQL | accepted | KTD13 |
| [ADR007](adr007-teacher-access.md) | Доступ преподавателя: список почт и доступные группы | accepted | KTD23 |
| [ADR008](adr008-run-modes-and-mail.md) | Режимы запуска и почта | accepted | KTD22 |
| [ADR009](adr009-visual-style.md) | Визуальный стиль: «Редакция» в цветах «Кафедры» | accepted | — (U21b) |

Новые ADR оформляются по [шаблону](adr000-template.md). По KTD16 при замене решения новый ADR получает статус `supersedes ADRxxx`, заменённый — `superseded by ADRyyy`. Старый документ не удаляется; соответствующий KTD мастер-плана и индекс обновляются в том же PR.
