# Инструменты ub-mode в этом проекте

Команды появятся в мастер-U2; до этого — только чтение.

| Принцип | Backend (Java) | Frontend (TypeScript) |
|---|---|---|
| Тест до кода | JUnit 6, `./mvnw test`; интеграция — Testcontainers, `*IT` через Failsafe | Vitest, `npm run check` |
| CRAP ≤ 10 | JaCoCo XML → `python3 tools/crap.py backend/target/site/jacoco/jacoco.xml` (формула `CC² × (1 − покрытие)³ + CC`, порог для `*.domain`) | ESLint `complexity` как суррогат |
| Дубли | PMD CPD в `./mvnw verify`, порог 100 токенов | — |
| Мутации | PIT: `./mvnw -Pquality verify` для `ledger.domain`, `checkpoint.domain`, `activity.domain`, порог 70% | — |
| Покрытие | JaCoCo ≥ 80% строк в `*.domain` | — |
| Границы модулей | `ModularityTest` (`ApplicationModules.verify()`) | — |

На Windows вместо `./mvnw` — `mvnw.cmd`.

Выжившие мутанты читаются из отчёта PIT `backend/target/pit-reports/`: для каждого либо тест, который его убивает, либо удаление мёртвого кода.
