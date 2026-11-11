# U13 — контракт преподавателей и выдач доступа

[План](../../spec/plans/2026-11-11-004-feat-u13-teachers-frontend-plan.md). База `937c1c5` / PR41. Расширяются существующие R2/R3/R24/KTD23/ADR007, продуктовые требования не меняются; backend/БД не меняются.

Восемь planned ADMIN операций: список/карточка преподавателей; допуск с проверенным departmentId; отклонение только pending с удалением аккаунта; список/создание/снятие выдачи с привязкой teacherId/grantId; серверный TeacherScope. Writes требуют CSRF. Новые DTO плоские/strict, grant request/view — discriminated oneOf четырёх точных вариантов. Новые problem codes не добавляются. Повтор одинаковой выдачи возвращает существующую200, новая201; client не должен replay writes после ошибок. Повтор допуска той же кафедрой200, другой кафедрой уже допущенного409 INVALID_STATE. До допуска scope пуст.

Проверки:

- Redocly2.76.0 lint — exit0, без warnings после удаления неиспользуемого вспомогательного enum.
- Заведомо плохие request examples course0 и GROUP с лишним facultyId — оба exit1 по проверке схем, ожидаемый отказ.
- api:gen — exit0, повтор идентичен по SHA256 всех generated файлов. YAML stringify с aliasDuplicateObjects=false по уроку PR40.
- Frontend check351/351 Vitest +11lint-rule, lint/typecheck exit0; build1042 modules exit0.
- tools140/140, agent-docs/diff — exit0; backend diff пуст.

UI, MSW транзакции, реальные права и завершение сессий отклонённого аккаунта этим PR не проверяются. Frontend будет отдельным PR поверх этого контракта. Самостоятельного approve/merge нет; требуется независимое ревью.
