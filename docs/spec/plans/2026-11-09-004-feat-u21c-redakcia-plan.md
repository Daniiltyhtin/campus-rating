---
title: U21c — тема «Редакция» и шаблоны
type: feat
date: 2026-11-09
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: docs/spec/plans/2026-10-31-001-feat-campus-rating-mvp-plan.md
execution: code
---

# U21c — «Редакция», белый и синий

## Goal Capsule

- **Цель.** Выбранный человеком стиль из ADR009 работает как тема Mantine, четыре адаптивных шаблона и `/styleguide`. Продуктовые auth-экраны сюда не входят.
- **Источники.** Мастер-U21c, R40; ADR009, предоставленная витрина «Редакция»; UX-карта и glossary; frontend/AGENTS.md.
- **Зависимости.** Frontend U2 уже слит; выбор человека сделан в сессии. Изменения ADR и решения U3 оформляются отдельно от реализации. Исполнитель не коммитит и не пушит.

## Files

- `frontend/src/theme/`: Mantine theme, смысловые CSS variables, общий CSS, запрет цветовых литералов и его тест.
- `frontend/src/app/layouts/`: PageLayout, ListLayout, CardLayout, FormLayout, TeacherTableLayout, AsyncContent и тесты.
- `frontend/src/features/styleguide/`: витрина темы и состояний, тесты.
- Подключения: `frontend/src/main.tsx`, `frontend/src/app/router.tsx`, `frontend/src/test/render.tsx` (тот же провайдер темы), `frontend/eslint.config.js`, `frontend/package.json` и lockfile, `frontend/AGENTS.md`.
- `docs/spec/architecture-decisions/adr009-visual-style.md` и журнал — решения и результаты, не runtime.

## Design Contract

Финальный образец человека от 10 ноября — `docs/spec/ux/style-tiles/final/index.html`. Светлая схема: бумага `#f5f6f8`, поля `#ffffff`, синий `#2450c4`, текст `#161b22`, вторичный `#57606a`, линейки `#d6dbe1`. Тёмная: бумага `#0f1419`, поля `#161c23`, текст `#e6edf3`, вторичный `#9da7b3`, ссылки `#8fb1ff`; кнопка `#7aa2ff` с текстом `#0b1530`. Высокий контраст: белый фон, чёрный текст, синий `#0033a0`, текст ≥7:1, увеличенный шрифт и цели ≥52 px. Выбор одной из трёх тем сохраняется локально. Цветовые литералы живут только в theme.

Literata 600/700 — заголовки/крупные баллы, PT Sans 400/700 — интерфейс. Шрифты поставляются локально через fontsource, Google Fonts в runtime нет. Иерархию создают типографика и линейки; статусы — мягкие пилюли. Результат действия — зелёная галочка и первые слова без фона. Панель выбора серая, выбранная строка — чекбокс и нижняя линия без заливки. Все интервалы, радиусы и цвета компонентов — из темы или смысловых переменных. Мобильные интерактивные цели ≥44 px (контраст ≥52 px), reduced-motion выключает движение.

Смысловые цвета статусов и валют допускают красный/зелёный/бирюзовый и всегда сопровождаются текстом. Цвет нельзя использовать как единственный носитель статуса. Контраст каждой реально используемой пары ≥4.5:1 в светлой/тёмной схемах и ≥7:1 в высококонтрастной.

## Implementation Units

### Тема

Test-first проверить матрицу контраста и выбранные шрифты. Создать createTheme + CSSVariablesResolver, 10-ступенчатую синюю шкалу с primaryShade 6 и локальные шрифты. В main и тестовом renderApp используется одинаковый provider. Базовые Mantine Button/Input/ActionIcon/Tab/SegmentedControl/NavLink получают цель ≥44 px.

### Шаблоны

PageLayout — заголовок, пояснение и действия; ListLayout — список; CardLayout — карточка; FormLayout — форма; TeacherTableLayout — таблица преподавателя. Общий AsyncContent имеет discriminated union состояний ready/loading/empty/error; error обязательно содержит сообщение и retry. Не создавать отдельные несовместимые блоки ошибок в каждом шаблоне.

Таблица на узком экране отображает строки карточками с подписями колонок, сохраняя семантическую таблицу; горизонтальная прокрутка не используется как адаптивное решение. Все шаблоны работают без backend. Тесты проверяют состояния, callback повторной загрузки, подписи формы и состав строк таблицы.

### Styleguide и линтер

`/styleguide` — публичный технический маршрут витрины без пользовательских данных. Он не обходит охранник продуктовых страниц и показывает обе схемы, набор компонентов, статусы/валюты и четыре шаблона. Видна смена состояний loading/empty/error; retry возвращает ready.

ESLint проверяет TS/TSX/CSS в features: hex, rgb/rgba, hsl/hsla и буквальные базовые CSS-цвета не допускаются; CSS variables разрешены. Processor CSS проверяет исходный текст, не пытаясь разбирать CSS как TypeScript. Тест использует настоящий ESLint и проверяет отказ на литерале и успех на токене.

## Acceptance Matrix

| R40 / критерий | Проверка |
|---|---|
| Шрифты и основные цвета из ADR009 | Тест theme и запущенная витрина |
| Состояния шаблонов | Testing Library: загрузка, пусто, ошибка, retry, готовые данные |
| Линтер ловит литерал | Настоящий ESLint lintText: TSX/CSS с hex отклонён; var разрешён |
| Контраст ≥4.5:1 | Независимый расчёт формулы WCAG для пар light/dark; браузерная сверка фактических токенов |
| Цели ≥44 px | Измерения в Playwright на 360 px, в том числе сегменты и переключатель схемы |
| Адаптивность | Приложение открыто на 360 и 1280 px: document.scrollWidth ≤ clientWidth, таблица читается, скриншоты |
| Регрессия | Старые router/AppShell тесты, frontend check и build |

## Verification Contract

`npm --prefix frontend run check`, `npm --prefix frontend run build`; browser QA `/styleguide` на 360/1280 и в обеих схемах; скриншоты и фактические размеры. `node tools/check-agent-docs.mjs`, обвязка и запись журнала. Рендер не заменяет тесты, компиляция не заменяет браузерную проверку.
