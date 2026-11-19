# Источники цифр презентации

Все цифры о проекте взяты на одном срезе — коммите `origin/main`, от которого создана ветка презентации. Цифра без строки здесь на слайд не попадает. Токены считаются на дату подсчёта, без границы по срезу.

| Значение | Слайд | Что это | Как получить | Срез |
|---|---|---|---|---|
| c3b1c12 | 7, 10 | срез, дата коммита 15.11.2026 МСК | `git log -1 --format='%h %cd' <срез>` | c3b1c12 |
| 379 | 7 | коммитов в main | `git rev-list --count <срез>` | c3b1c12 |
| 71 | 7 | PR, слитых в main | `gh pr list --state merged --base main --limit 300` — число строк; у #73 merge-коммит переписан при чистке истории 13.11, изменения в main есть | c3b1c12 |
| 117 | 7 | записей журнала агентов | `node tools/agent-stats.mjs --merge` на срезе, строка «Записей журнала» (считает `docs/agent-log.md`) | c3b1c12 |
| 74 | 7 | кругов ревью в журнале | там же, строка «Кругов ревью»: сумма числа в начале поля «Кругов ревью» по всем записям, в том числе вида «1. …» и «0; …» | c3b1c12 |
| 9 | 5 | ADR | файлы `docs/spec/architecture-decisions/adrNNN-*.md` без `adr000-template.md` | c3b1c12 |
| 11 млн | 7 | токенов на вход и выход без кэша, нижняя оценка | `docs/presentation/agent-stats.md`, блок «Для слайда»; сбор — раздел «Сбор данных» там же | 19.11.2026 |
| 248 млн | 7 | токенов с учётом кэша, нижняя оценка | там же | 19.11.2026 |
| 1026 | 10 | тестов backend | `./backend/mvnw -f backend/pom.xml -Pquality verify` на JDK 25: 406 модульных (surefire) и 620 интеграционных (failsafe), BUILD SUCCESS | c3b1c12 |
| 797 | 10 | тестов frontend | `npx vitest run` в `frontend/`: 796 passed и 1 skipped | c3b1c12 |
| 72 | 10 | e2e-проверок | `npx playwright test --list` в `e2e/`: итог по проектам mobile и desktop | c3b1c12 |

Отметки модулей на слайде 3 сверены с кодом на срезе: в модулях `notification` и `ai` только `package-info.java`; модуль 6 — U14 (`frontend/src/features/feed/PersonalFeed.tsx`), модуль 10 — U17 (`frontend/src/features/analytics/`).

Версии стека на слайде 4 — из `backend/pom.xml`, `frontend/package.json`, `docker-compose.yml` на срезе.
