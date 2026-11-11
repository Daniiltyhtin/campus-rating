# Frontend — правила для агентов

React 19, Vite, TypeScript, Mantine, React Router, TanStack Query, Vitest. Дополняет корневой `AGENTS.md`.

## API

- Контракт — `docs/spec/api/openapi.yaml`. Типы генерируются командой `npm run api:gen` в `src/api/schema.d.ts`; руками этот файл не правится.
- Нужен новый эндпоинт или поле — сначала правка контракта, потом генерация, потом код.
- Пока backend не готов, экраны работают против моков MSW, собранных из примеров контракта.
- Доменные ошибки приходят как `ProblemDetail` с полем `code`; пользователь видит понятный текст по коду, а не сырой ответ.

## UI

- Компоненты — Mantine; свои стили только там, где Mantine не хватает.
- Тема — финальная «Редакция» в цветах «Кафедры» (ADR009, `docs/spec/ux/style-tiles/final/index.html`): светлая, тёмная, высокий контраст. Статусы — пилюли, результат действия — текст без заливки, панель выбора — серая. Цвета, отступы и радиусы — только токены темы и CSS-переменные. ESLint запрещает цветовые литералы в `features/` (TSX и CSS).
- Точные размеры и палитры — `docs/spec/ux/style-tiles/README.md`; новые экраны собираются из общей темы и шаблонов, без собственного варианта стиля.
- Интерфейс на русском, термины — из `docs/spec/glossary.md`.
- Каждый экран работает от 360 px без горизонтальной прокрутки; навигация: боковая панель на десктопе, нижняя на мобильном.
- Состояния загрузки, пустые и ошибки — обязательны для каждого списка и формы.
- Структура: `src/features/<фича>/` — экраны и хуки фичи, `src/api/` — клиент и типы, `src/app/` — каркас.

## Данные

- Серверные данные — только через TanStack Query; обновление — инвалидацией запросов, без ручного копирования в локальный стейт.
- Ленты и история — бесконечная прокрутка по курсору из API.
- Время с сервера в UTC, показ — Europe/Moscow.

## Проверка

- После правки экрана: открыть его на 360 и 1280 px (Playwright MCP или вручную) и приложить скриншоты к PR.
- «Готово» — экран открыт и сценарий пройден, а не «компилируется».

## Команды

Из корня репозитория (Node ≥ 22.22, версия в `.nvmrc`):

| Что | Команда |
|---|---|
| Установка зависимостей | `npm --prefix frontend ci` |
| ESLint, `tsc --noEmit`, Vitest | `npm --prefix frontend run check` |
| Сборка в `frontend/dist` | `npm --prefix frontend run build` |
| Генерация типов и разыменованных примеров из контракта | `npm --prefix frontend run api:gen` |
| Dev-сервер с MSW до рендера, без backend | `VITE_API_MOCKS=true npm --prefix frontend run dev` |
| Dev-сервер, `/api` → `http://localhost:8081` | `npm --prefix frontend run dev` |

Результаты `api:gen` (`src/api/schema.d.ts`, `src/api/problemCodes.generated.ts`, `src/mocks/examples.generated.ts`) генерируются без дат и проверяются в CI; бинарные поля имеют тип `Blob`. Компоненты с `useMediaQuery` тестируются через `setViewportWidth` из `src/test/setup.ts`; страницы — через `renderApp` из `src/test/render.tsx`.

## Уроки

- Выбор участников сохранялся при переходе на другую уже закешированную активность → воспроизвёл тест с двумя заполненными query keys → состояние формы подтверждения привязывать к activity.id через key; проверять переход и на холодные, и на прогретые данные (`src/features/activities/ConfirmParticipationPage.tsx`, `navigation.test.tsx`).
- Autosize Textarea падала в jsdom из-за отсутствующего FontFaceSet → tester прочитал подписку Mantine на document.fonts → в test-only helper давать EventTarget с ready/check/load, не менять production-компонент ради среды (`src/test/activitiesAcceptance.tsx`).
- Default-кнопка при смене disabled смешивала background и backgroundColor → заметили ошибку React в браузере и воспроизвели rerender-тестом → в одной ветке стилей использовать одно longhand-свойство (`src/theme/theme.ts`, `button.test.tsx`).

- Подсветка активного пункта меню и провайдер TanStack Query в тестовом рендере не проверялись — все тесты проходили и без них → нашёл ревьюер PR #10 → каждое состояние, меняющее вид, проверять тестом через доступный атрибут (`aria-current`, `aria-disabled`), а `renderApp` держать с теми же провайдерами, что `main.tsx` (`src/app/AppShell.tsx`, `src/test/render.tsx`).
- Рамка поля в светлой теме прошла по контрасту текста, но дала 2.81 к фону при норме 3:1 для нетекстовых элементов → нашлось при сверке токенов U21b → контраст рамок полей, линий-индикаторов и фокуса проверять тем же скриптом, что и текст (`docs/spec/ux/style-tiles/README.md`).
- Общий API-клиент захватил native fetch до запуска MSW и в UI-тесте получил HTML вместо JSON → обнаружил bootstrap-тест реальных routes → fetch общего клиента разрешать в момент запроса; сохранять проверку старта клиента до server.listen (`src/api/client.ts`, `src/features/auth/bootstrap.test.tsx`).
- PasswordInput получил размер только внешней обёртки, а внутренний input остался меньше цели и кнопка показа имела tabindex=-1 → обнаружили измерения и проход Tab в браузере → измерять innerInput и кнопку, проверять доступность показа пароля клавиатурой и токены подсказок/ошибок (`src/features/auth/AuthLayout.tsx`).
- Открытое меню в jsdom имело display:none из-за нулевой геометрии, хотя aria-expanded=true → выяснил tester по Mantine Popover → для UI-тестов меню задавать MantineProvider env="test" и ждать видимый menuitem, не нажимать скрытый (`src/test/authAcceptance.tsx`).
