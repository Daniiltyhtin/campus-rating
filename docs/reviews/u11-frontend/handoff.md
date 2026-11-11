# U11 frontend — продолжение

Ветка `u11-feed-frontend` от `b58d949` U10; PR базируется на `u10-profile-frontend`, пока PR33/34/35 открыты. На старте все три OPEN, CI harness/backend/frontend/e2e SUCCESS. Ссылку на новый PR сообщает итоговый ответ; основной код и доказательства — в этой ветке.

Готовы `/awards`, курсор/дедупликация/новые награды без автоматической вставки, `/groups/:id/ratings` с AR/BUA/BOA, snapshot balances, права/ошибки/отмена запросов; ссылки из доступных групп и контекстный MSW. Backend/API/generated/БД не менялись. Planned операции работают в dev только при явном MSW, production не делает fallback.

План: [U11](../../spec/plans/2026-11-11-002-feat-u11-feed-frontend-plan.md). Подробности и матрица — [verification.md](verification.md). Независимые acceptance36/36, начальный Red31failed/3passed +2failed; ошибки fixture исправлял tester. Финальный frontend check283/283 Vitest +11 lint-rule; build1037 modules; tools140/140; agent-docs/diff — exit0. Compose пересобран/healthy, e2e4/4; изолированный стенд после тестов удалён.

Browser QA:48 снимков360/1280 ×3 темы, teacher/student/admin, длинные поля, poll banner, профили, сортировка, клавиатура. Ошибки приложения/overflow/малые цели/нарушения контраста:0; минимум контраста5.57, high contrast8.74. [JSON](browser-result.json), [скрипт](browser-qa.mjs). Скриншоты визуально проверены выборочно. Dev-web — прямой Vite с явным MSW; для QA используется loopback URL из переменной U11_WEB_URL или значение по умолчанию в скрипте. Реальные backend лента/рейтинги и SQL пока не проверены.

Следующий шаг — независимое ревью PR. Не self-approve/merge. После принятия родителей — перебазирование PR на main и повтор интеграционных ворот. Не менять независимые acceptance исполнителем; отдельный tester разрешён человеком. Коммит/пуш готового результата также разрешены человеком.
