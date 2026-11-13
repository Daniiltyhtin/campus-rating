# Стенд на сервере

Демо-стенд работает в профиле `demo` за nginx хоста: nginx терминирует TLS и проксирует на контейнер `web`, который слушает только `127.0.0.1`. Postgres и бэкенд наружу не публикуются.

**На сервере данные переживают перезапуск.** Локальный `docker compose up` без override при каждом старте пересоздаёт базу и файлы сдач из демо-данных (мастер-R47); на сервере это выключено: `CAMPUS_DEMO_RESET=false`. Сброс к демо-данным — только явно, по флагу (раздел «Сброс к демо-данным»). Все сервисы стенда запускаются с `restart: unless-stopped` — после падения контейнера или перезагрузки сервера Docker поднимает их сам; остановленный командой `down` или `stop` стек остаётся остановленным.

## Что нужно на сервере

- Docker Engine и Docker Compose 2.24 или новее (`docker compose version`).
- nginx и certbot (`apt install nginx certbot python3-certbot-nginx`).
- DNS: запись `A` домена стенда на IP сервера.
- Свободный порт на `127.0.0.1` для `web` — `WEB_PORT`; проверьте по `ss -tln`, что его никто не слушает.

## Первый запуск

1. Положите код нужного коммита на сервер (`git clone` или копия `git archive`) и перейдите в каталог.
2. Окружение:
   ```bash
   cp deploy/server.env.example deploy/server.env
   ```
   Заполните `POSTGRES_PASSWORD` и `DEMO_PASSWORD_PREFIX` (по 16 случайных символов, команда — в комментарии файла), `CAMPUS_PUBLIC_URL` (`https://<домен>`), `EMAIL_VERIFICATION` (`off` без почты), `WEB_PORT`. Дайте файлу права `600`. Файл `deploy/server.env` не коммитится.

   Без `DEMO_PASSWORD_PREFIX` стенд не запустится: демо-аккаунты на сервере входят только паролями с префиксом — `demo` в паролях из корневого README заменяется префиксом (`<префикс>-admin-1`). Раздайте префикс команде отдельно от репозитория.
3. Стек:
   ```bash
   docker compose --env-file deploy/server.env -f docker-compose.yml -f docker-compose.server.yml up -d --build --wait
   ```
   Без обязательных переменных compose остановится с сообщением, какой не хватает.
4. nginx и сертификат. Конфиг стенда — отдельный файл; чужие файлы в `/etc/nginx` не меняются, `nginx -t` — перед каждым `reload`.
   1. Временный конфиг только с портом 80 — чтобы certbot прошёл проверку домена:
      ```nginx
      server {
          listen 80;
          server_name <домен>;
          location / { proxy_pass http://127.0.0.1:<WEB_PORT>; }
      }
      ```
      ```bash
      sudoedit /etc/nginx/sites-available/campus.conf   # вставьте блок выше
      sudo ln -s /etc/nginx/sites-available/campus.conf /etc/nginx/sites-enabled/
      sudo nginx -t && sudo systemctl reload nginx
      ```
   2. Сертификат без правки конфигов:
      ```bash
      sudo certbot certonly --nginx -d <домен> --deploy-hook "nginx -t && systemctl reload nginx"
      ```
   3. Полный конфиг из шаблона: замените `campus.example.ru` на домен и `WEB_PORT` на значение из `deploy/server.env`.
      ```bash
      sudo cp deploy/nginx/campus.conf.example /etc/nginx/sites-available/campus.conf   # затем правка
      sudo nginx -t && sudo systemctl reload nginx
      ```
5. Проверка:
   - `curl -fsS http://127.0.0.1:<WEB_PORT>/ >/dev/null && echo ok` — web отвечает;
   - `https://<домен>/login` открывается, сертификат валиден, `http://` отвечает 301 на `https://`;
   - вход `admin@campus.test` паролем `<префикс>-admin-1`; пароль `demo-admin-1` отклоняется.

## Ограничение частоты входа и регистрации

nginx хоста ограничивает `POST` с одного IP: вход (`/api/v1/auth/login`) — 60 в минуту, всплеск до 60 сверх нормы, чтобы класс или жюри за общим NAT входили без отказов; регистрация, письма и пароли (`/api/v1/auth/register/…`, `/api/v1/auth/email/…`, `/api/v1/auth/password…`) — 10 в минуту, всплеск до 10. Сверх лимита nginx отвечает `429` с кодом `RATE_LIMITED`, не передавая запрос приложению, а экран показывает «Слишком много попыток. Подождите минуту и попробуйте снова.»: это защита от перебора паролей и потока регистраций, а не сбой. `GET` (например, опрос `/api/v1/auth/email/status`) и остальные пути не ограничиваются. Внутренний nginx контейнера `web` ничего не ограничивает, поэтому локальный стек и e2e работают без лимита. Если весь класс выходит из одной сети с общим IP, поднимите `rate` или `burst` в `/etc/nginx/sites-available/campus.conf` и сделайте `nginx -t && systemctl reload nginx`.

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
| Перезапуск (данные сохраняются) | `docker compose --env-file deploy/server.env -f docker-compose.yml -f docker-compose.server.yml up -d --build --wait --force-recreate` |
| Обновление версии | `git pull`, затем команда перезапуска (данные сохраняются, новые миграции применяются) |
| Логи бэкенда | `docker compose --env-file deploy/server.env -f docker-compose.yml -f docker-compose.server.yml logs -f backend` |
| Остановка | `docker compose --env-file deploy/server.env -f docker-compose.yml -f docker-compose.server.yml down` |
| Полное удаление с томами | то же с `down -v` |

### Сброс к демо-данным

Стирает всё, что создано на стенде, и возвращает базу и файлы сдач к демо-данным:

1. В `deploy/server.env` поставьте `CAMPUS_DEMO_RESET=true` и выполните команду перезапуска — бэкенд пересоздаст базу и очистит файлы сдач.
2. Сразу верните `CAMPUS_DEMO_RESET=false` и выполните команду перезапуска ещё раз. Пока стоит `true`, каждый перезапуск — в том числе автоматический после падения — снова стирает данные.

**Пароли демо-аккаунтов** задаёт только `DEMO_PASSWORD_PREFIX`: при каждом старте бэкенд возвращает их к `<префикс>-<роль>-1`, даже если пароль меняли в интерфейсе. Чтобы сменить пароли (например, префикс стал известен посторонним), поменяйте префикс в `deploy/server.env` и перезапустите стенд.

**Применённые демо-миграции не меняются.** На сервере база не пересоздаётся, поэтому правка уже применённого changeset в `backend/src/main/resources/db/changelog/demo/` остановит обновление стенда ошибкой контрольной суммы Liquibase, а автоперезапуск будет поднимать сломанный бэкенд по кругу. Новые демо-данные — только новым changeset с `ON CONFLICT DO NOTHING`.

Все команды `docker compose` для стенда — с обоими файлами: без `docker-compose.server.yml` порты бэкенда и базы откроются на `127.0.0.1`, а настройки прокси не применятся.
