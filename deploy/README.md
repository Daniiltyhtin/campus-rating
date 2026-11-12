# Стенд на сервере

Демо-стенд работает в профиле `demo` за nginx хоста: nginx терминирует TLS и проксирует на контейнер `web`, который слушает только `127.0.0.1`. Postgres и бэкенд наружу не публикуются.

**В профиле `demo` каждый запуск стека пересоздаёт базу и файлы сдач** — всё, что создано поверх демо-данных, исчезает (мастер-R47). Перезапускайте осознанно: перед показом — да, во время показа — нет.

## Что нужно на сервере

- Docker Engine и Docker Compose 2.24 или новее (`docker compose version`).
- nginx и certbot (`apt install nginx certbot python3-certbot-nginx`).
- DNS: запись `A` домена стенда на IP сервера.
- Свободный порт `8080` на `127.0.0.1` (или другой — `WEB_PORT`).

## Первый запуск

1. Склонируйте репозиторий и перейдите в него.
2. Окружение:
   ```bash
   cp deploy/server.env.example deploy/server.env
   ```
   Заполните `POSTGRES_PASSWORD` (длинный случайный), `CAMPUS_PUBLIC_URL` (`https://<домен>`), `EMAIL_VERIFICATION` (`off` без почты). Файл `deploy/server.env` не коммитится.
3. Стек:
   ```bash
   docker compose --env-file deploy/server.env -f docker-compose.yml -f docker-compose.server.yml up -d --build --wait
   ```
   Без обязательных переменных compose остановится с сообщением, какой не хватает.
4. nginx:
   ```bash
   sudo cp deploy/nginx/campus.conf.example /etc/nginx/sites-available/campus.conf
   # замените campus.example.ru на домен; WEB_PORT, если меняли
   sudo ln -s /etc/nginx/sites-available/campus.conf /etc/nginx/sites-enabled/
   sudo certbot --nginx -d <домен>
   sudo nginx -t && sudo systemctl reload nginx
   ```
5. Проверка:
   - `curl -fsS http://127.0.0.1:8080/ >/dev/null && echo ok` — web отвечает;
   - `https://<домен>/login` открывается, сертификат валиден;
   - вход `admin@campus.test` / `demo-admin-1` (демо-аккаунты — в корневом README).

## Почта (SMTP Яндекса)

1. В аккаунте Яндекса включите пароли приложений и создайте пароль для почты.
2. Раскомментируйте в `deploy/server.env`:
   ```
   SPRING_MAIL_HOST=smtp.yandex.ru
   SPRING_MAIL_PORT=465
   SPRING_MAIL_USERNAME=<ящик>@yandex.ru
   SPRING_MAIL_PASSWORD=<пароль приложения>
   SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_ENABLE=true
   CAMPUS_MAIL_FROM=<ящик>@yandex.ru
   ```
   Отправитель должен совпадать с ящиком — иначе Яндекс отклонит письмо.
3. Для подтверждения email поставьте `EMAIL_VERIFICATION=required` (без SMTP бэкенд не стартует).
4. Перезапустите стек командой из шага 3 первого запуска.
5. Проверка: зарегистрируйте студента на свой ящик — придёт письмо со ссылкой подтверждения; ссылка ведёт на `CAMPUS_PUBLIC_URL`.

Письма уходят от ящика Яндекса, поэтому записи SPF/DKIM домена стенда для отправки не нужны. Если хотите принимать почту на адреса домена стенда, включите Cloudflare Email Routing (Cloudflare сам добавит MX и SPF для приёма) и перенаправьте адреса на свой ящик.

## Обычные операции

| Что | Команда |
|---|---|
| Перезапуск с возвратом к демо-данным | `docker compose --env-file deploy/server.env -f docker-compose.yml -f docker-compose.server.yml up -d --build --wait --force-recreate` |
| Обновление версии | `git pull`, затем команда перезапуска (данные пересоздаются) |
| Логи бэкенда | `docker compose --env-file deploy/server.env -f docker-compose.yml -f docker-compose.server.yml logs -f backend` |
| Остановка | `docker compose --env-file deploy/server.env -f docker-compose.yml -f docker-compose.server.yml down` |
| Полное удаление с томами | то же с `down -v` |

Все команды `docker compose` для стенда — с обоими файлами: без `docker-compose.server.yml` порты бэкенда и базы откроются на `127.0.0.1`, а настройки прокси не применятся.
