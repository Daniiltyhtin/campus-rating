# U13 frontend — справочники

## Объём

Первая часть R24/U13: `/admin/directory`, три справочника, CRUD с диалогами, полный admin DTO, состояния и проверки доступа. [План](../../spec/plans/2026-11-11-003-feat-u13-directory-frontend-plan.md). База — `spec-u13-directory-api` / [PR40](https://github.com/Daniiltyhtin/campus-rating/pull/40), контрактный коммит `47bc2da`. PR40 сначала опубликован отдельно, затем реализован frontend. Его финальный CI4/4 SUCCESS после исправления YAML aliases; backend/БД не менялись.

Остальные части U13 (преподаватели/допуск/выдачи, импорт, настройки/семестры, старосты, временные пароли, материалы сдачи) остаются отдельной работой. Здесь они не объявляются готовыми.

## Независимые тесты и Red

Tester по write-tests менял только `directory.acceptance.test.tsx` и `test/directoryAcceptance.tsx`. Initial45:43 failed/2 guards passed, ожидаемая заглушка. Targeted Red — нет heading «Справочники», без импорта будущих модулей. Два позднее добавленных сценария впервые встретили production и прошли; их Red не заявляется. Финальный набор расширен двумя focus-регрессиями до49, все49 passed (45.95s), ESLint обоих файлов exit0.

Test-only исправления делал tester: неподдерживаемый ByRole exact удалён, длинный Unicode вводится paste вместо сотен key events (границы64/255 и тело HTTP сохранены), ошибка кода проверяется через aria-invalid/accessible description. Независимые ожидания исполнитель не менял. Первая попытка focus-тестов использовала неверный matcher toBeFocused вместо jest-dom toHaveFocus; валидные jsdom прогоны были после фикса, поэтому для них jsdom Red не заявляется.

Собственные15 parser/validation +4 mock проверки сначала падали на отсутствующих модулях, затем19/19 прошли. Browser Red выявил реальный дефект: opener отключался до того, как Mantine запомнил активный элемент, после Esc фокус терялся. Исправление: явная ссылка на opener до disable, возврат после commit/включения кнопки, для успешного delete — кнопка добавления. Browser и независимые focus-регрессии зелёные.

## Проверки

| Команда | Итог |
|---|---|
| `npm --prefix frontend run check` | exit0,351/351 Vitest +11/11 lint-rule, lint/typecheck |
| `npm --prefix frontend run build` | exit0,1042 modules |
| `npx vitest run src/features/admin/directory/directory.acceptance.test.tsx` из frontend | exit0,49/49, независимый tester |
| Собственные contracts/mock tests | exit0,19/19 |
| `node --test "tools/test/*.test.mjs"` | exit0,140/140 |
| `node tools/check-agent-docs.mjs`, `git diff --check` | exit0 |
| `git diff --name-only -- backend docs/spec/api frontend/src/api frontend/src/mocks/examples.generated.ts` | пустой вывод относительно контрактной базы |
| Изолированный compose down/up --build --wait | exit0,postgres/backend/web healthy; финальная пересборка после focus-фикса |
| `npm --prefix e2e test` | exit0,4/4 mobile/desktop smoke; затем compose down -v cleanup |
| `node docs/reviews/u13-directory-frontend/browser-qa.mjs` | exit0,48 снимков360/1280 ×3темы, ошибок приложения/overflow/малых целей0 |

Сборка сообщает предупреждение Vite о chunk больше500kB (index≈554kB); пороги и конфиг сборки не ослаблялись. Следующая оптимизация загрузки — отдельная задача, здесь не выполнялась.

## Матрица требований

| R/KTD | Критерий → реализация | Доказательство |
|---|---|---|
| R3/R24 | ADMIN-only DirectoryPage; guards раньше HTTP |4роли/guest/password, нет неразрешённых запросов; acceptance |
| R24 | Три вкладки/полные списки, серверный порядок | caption/code/name/faculty/year, loading/empty/network500/malformed/retry; acceptance |
| R24/KTD17 | contracts.ts parse/validate | Unicode64/255, blank, year1/9999/fraction, связи; own unit/acceptance |
| R24 | EditDirectory + useDirectoryAction | create201/edit200 body/CSRF, предзаполнение, сброс отменённого ввода; acceptance/browser |
| R24 | DeleteDirectory | confirm/cancel/409 occupied/204/404, без ложного удаления; acceptance/mock/browser |
| R24 | ActionError и отдельный csrf refresh | fieldErrors/code conflict/403/CSRF/500/malformed, поля сохраняются, нет auto-replay; acceptance |
| R24 | Mutation retry=false + pending controls | double submit/delete — одна отправка; acceptance |
| R24 | QueryClient invalidation | write success + GET500 → повтор чтения, не мутации; acceptance |
| R24 | Контекстный directory MSW | auth/role/password/CSRF/FK/unique, CRUD→public read;4mock tests |
| R40/ADR009 | DirectoryPage/Dialogs CSS + theme |48 screenshots, геометрия/контраст, Tab/Esc/return focus; browser +2acceptance |

## Browser QA

Явный `VITE_API_MOCKS=true` Vite dev-web, без backend fallback. Настоящая форма admin login, обязательная смена временного пароля; далее все три справочника, длинные значения, create→edit→delete, занятая группа, validation, Tab внутри диалога, Esc и return focus. На каждой ширине360/1280 во всех трёх темах. Снимки диалогов — viewport, таблиц — full page; reduced motion и ожидание темы исключают промежуточные измерения.

[Воспроизводимый скрипт](browser-qa.mjs), [JSON](browser-result.json). Снимки: [группы360](groups-360-light.png), [форма360 high contrast](groups-form-360-contrast.png), [отказ удаления1280 dark](occupied-group-1280-dark.png), остальные рядом. Минимальный измеренный контраст активного содержимого нового экрана/диалогов5.91; в high contrast не ниже7. Все48 измерений без ошибок. Выборочные снимки проверены визуально. Это не завершённый U23 и не аудит всего приложения.

## Ограничения

Реальные planned admin API, FK, uniqueness, транзакции и изменение TeacherScope не проверены MSW. Старые mocks профилей/auth snapshots не являются полной моделью всех связей: новый handler отражает изменения в публичных directory GET, но не переписывает аккаунты/профили. ИВТ-21/ПИ — явно занятые seeds; ИВТ-22 дополнен синтетическими code/name/year из существующего public example. Перед production интеграцией нужно проверить реальные данные и ограничения backend.

Независимое ревью реализации ещё требуется. Approve/merge исполнитель не выполнял.
