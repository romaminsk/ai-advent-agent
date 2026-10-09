# Приватный локальный LLM-сервис

Архитектура: `Mac -> SSH-туннель -> nginx 127.0.0.1:8080 -> Ollama 127.0.0.1:11434`.

## Состояние

Файлы сервиса подготовлены в `deploy/private-llm/`. Серверная часть не выполнена: на `ai-agent-01` SSH доступ есть, но `sudo -n` требует пароль. Поэтому железо, модель, установка и проверки не замерены.

## Выполнить на VPS

После предоставления passwordless sudo выполнить с Mac:

```bash
scp -r deploy/private-llm ai-agent-01:~/private-llm-deploy/
KEY=$(openssl rand -hex 32)
printf '%s\n' "$KEY" | ssh ai-agent-01 '~/private-llm-deploy/install.sh'
```

Ключ не передавать аргументом команды и не выводить. Перед установкой выбрать модель по `free -m`: `qwen2.5:3b` при available не менее 3500 МБ, иначе `qwen2.5:1.5b`; при available менее 1800 МБ остановиться. Для выбранной модели обновить `Modelfile` и строку `ollama pull` в `install.sh` до запуска.

Не включать `ufw`. Сервис Git Monitor не останавливать и не менять.

## Запуск

На Mac записать ключ и URL в `.env` с правами `600`:

```text
PRIVATE_LLM_KEY=<локальный hex-ключ>
PRIVATE_LLM_URL=http://127.0.0.1:8080
```

Открыть туннель:

```bash
ssh -f -N -L 8080:127.0.0.1:8080 ai-agent-01
```

Проверить и запустить чат:

```bash
```

В чате `/exit` завершает работу.

## Лимиты

10 запросов в минуту на IP, burst 3, максимум 2 соединения, тело до 8 КБ, очередь Ollama 4, контекст 2048 токенов, ответ 256 токенов. Внешние порты не открываются: nginx и Ollama слушают только loopback.

## Смена ключа

Сгенерировать новый ключ на Mac, заменить `PRIVATE_LLM_KEY` в `.env`, затем повторно передать его установщику через переменную окружения SSH-сессии. Не печатать ключ и не хранить его в Git.

## Откат

На VPS удалить `/etc/nginx/conf.d/private-llm.conf`, удалить `/etc/systemd/system/ollama.service.d/private-llm.conf`, выполнить `sudo nginx -t && sudo systemctl reload nginx && sudo systemctl daemon-reload && sudo systemctl restart ollama`.
