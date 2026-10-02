# Agent Guide

## Project
- Консольный AI-агент для диалога с LLM, локальной памяти, задач и MCP-инструментов.
- Язык: Java 21. Сборка: Maven. Хранение приложения и индексов — JSON.
- Зависимости: Jackson, JLine, MCP Java SDK. Spring и базы данных не используются.

## Package Map
- `com.example` — приложение, модели и основные функции.
- Entry/UI: `Main`, `TerminalUi`, `InteractiveTerminalUi`, `PlainTerminalUi`, `MarkdownTerminal`.
- LLM/config: `LlmAgent`, `Config`, `ModelSettings`, `RequestDiagnostics`.
- Контекст: `ContextBuilder`, `SummaryEngine`, `StrategyEngine`, `FactsBlock`, `BranchData`.
- Persistence: `JsonConversationStore`, `MemoryStore`, `ProfileStore`, `InvariantStore`.
- Состояние диалога (память задачи беседы): `DialogTaskState`, `DialogTaskStateTracker`; поле `dialogState` в файле истории, команды `/dialogstate` и `/dialogstate clear`; обновление детерминированное, без LLM. Чисто мета-сообщения («уточняю:», «ограничение:», «термины:» без «?») в RAG-режиме не идут в поиск и LLM — локальный ответ «Зафиксировано: …». Обогащение поискового запроса (goal+terms, `RagService.prepareChat`) — только для вопросов короче 10 слов (минимум по контрольным вопросам калибровки). Открытые вопросы — только отказы по теме (IDK_MODEL/UNVERIFIED после прохождения порогов), лимит 5.
- Задачи/ограничения: `TaskState`, `TaskStage`, `TaskStatus`, `InvariantGuard`.
- MCP: `McpClientComponent`, `McpRegistry`, `McpOrchestrator`, `ToolRouter`, `GitMcpServer`, `GitMonitorMcpServer`, `PipelineMcpServer`, `PipelineRunner`.
- Monitor: `MonitorStore`, `MonitorRunner`, `MonitorWorker`, `MonitorSchedule`, `MonitorAggregator`.
- Pipeline utilities: `FileSearcher`, `SearchSummarizer`, `ResultFileWriter`, `PipelineCanonicalJson`.
- `com.example.index` — загрузка документов, chunking, embeddings, кэш, JSON-индекс, поиск и сравнение.
- `DocumentLoader` исключает `src/test/**`, `target/`, `artifacts/`, `.idea/`, `.git/`, скрытые каталоги и `.env*` из корпуса.
- `com.example.rag` — retrieval по structure-индексу, ограничение контекста, prompt, реранкер и resumable eval (`RagEvalCheckpointStore`).
- `src/test/java/com/example` — локальные self-tests без платных запросов к LLM.
- `SelfTest.java` — короткий runner и реестр групп; `SelfTestSupport.java` — expect, counters, stores, stubs, локальные серверы и process helpers.
- Group files: `DialogChecks`, `StoreChecks`, `TaskStateChecks`, `ContextChecks`, `ContextStrategyChecks`, `CommandChecks`, `InvariantGuardChecks`, `UxChecks`, `McpChecks`, `McpPipelineChecks`, `OrchestrationChecks`, `OrchestrationLiveChecks`, `WorkerSchedulerChecks`, `WorkerProcessChecks`, `StoreFailureChecks`, `MeasurementChecks`, `QuietOutputChecks`, `IntegrationChecks`, `IndexChecks`, `RagChecks`.
- Другие test support: `McpStubServer`, `SelfTestStoreWriter`.

## Run And Build
- Запуск установленного CLI: `ai-agent` (launcher загружает настройки из `.env`).
- Сборка и установка: `mvn -q package && ./install-cli.sh`.
- Справка без запуска диалога: `ai-agent --help`; plain UI: `ai-agent --plain`.

## Tests
- Группа: `mvn -q test-compile exec:java@self-test -Dexec.args=<group[,group]>`; `-Dexec.args=list` печатает реестр.
- Группы полного suite: `dialog`, `stores`, `task-state`, `context`, `commands`, `invariant-guard`, `ux`, `mcp`, `mcp-pipeline`, `orchestration`, `worker-scheduler`, `worker-process`, `store-failures`, `measurements`, `quiet-output`, `integration`, `index`, `rag`.
- `mcp` — совместимый алиас для `mcp,mcp-pipeline`; `mcp-pipeline` можно запускать отдельно.
- `orchestration-live` запускается только явно и обращается к настроенному провайдеру; не включать его в обычный полный suite.
- После каждой группы runner печатает `group | passed | failed | ms`; targeted selectors принимают список через запятую.
- Полный прогон: `mvn -q test-compile exec:java@self-test`; выполнять один раз в конце задачи перед слиянием, если это требуется.
- Группы используют локальные HTTPS/MCP stubs и `expect`; индекс использует fake embedder.
- Контрольные RAG-вопросы находятся в `src/test/resources/rag/questions.json`; ресурс также включается в CLI JAR для `/rag eval`.
- `/rag on|off|status` управляет режимом обычного диалога; `/rag config` показывает настройки из `~/.ai-advent-agent/rag-settings.json`; `/rag set topk <до> <после>`, `/rag set threshold <0..1>`, `/rag set rerank on|off` и `/rag set rewrite on|off` меняют их атомарно. Рабочие значения по калибровке: topKBefore=20, topKAfter=5, minScore=0.50, relativeDelta=0.15, vectorWeight=1.00, lexicalWeight=0.00, diversity=off, rerank=on, rewrite=on, индекс structure.
- `/rag retrieval <вопрос>` выводит кандидатов до фильтра и итог после реранкинга без финальной LLM; `/rag retrieval` сравнивает fixed/structure по 9 вопросам. `/rag threshold-scan` без чат-модели сравнивает fixed и structure по всем 10 вопросам, выводит статистику top-20 и сетку 0.25–0.70 с шагом 0.05; нужные/мусорные значения считаются по чанкам. По калибровке 2026-09-30 порог 0.55 теряет 10/54 нужных чанков, а 0.50 — 1/54. При 0.50 ловушка телефона не отсекается threshold-фильтром; корректное поведение — отказ модели. Это указано в отчёте явно. Рабочее значение сохранено как minScore=0.50, relativeDelta=0.15, index=structure; отчёт сохраняется в `~/.ai-advent-agent/rag-results/threshold-scan-<дата>.md`.
- `/rag rerank-analysis` без LLM проверяет веса 0.70/0.30–1.00/0.00, diversity cap, precision и sourceHit по каждому вопросу; показывает ранги для history-lock, context-layers, mcp-agent-flow, fixed-limits и вопроса filter/rewrite. По проверке 2026-09-30 выбран vector=1.00, lexical=0.00, diversity cap=off: precision 0.439 при baseline 0.422 (требуемый минимум 0.430), sourceHit 7/9 без ухудшения ни на одном вопросе. Лексический вес 0.10 и cap не прошли оба ограничения; бонус имени файла 0.05 и удвоенный вес идентификаторов сохранены, стоп-слова исключаются.
- `/rag ask <вопрос>` показывает поиск, этапы и ответы off/on по мере готовности. `/rag eval A,B,C,D [--questions ids] [--resume] [--checkpoint filename.json]` принимает подмножество режимов и вопросов; по умолчанию — все режимы и 10 вопросов. `--checkpoint` создаёт/выбирает отдельный JSON в `rag-results/`, не затирая основной checkpoint; `--resume` пропускает сохранённые пары. Отчёт показывает `rewriteStatus` (`ok` или `fallback:<причина>`) и недостающие пары. Rewrite использует max_tokens=2048 и timeout=45 с; JSON/code-fence-ответ разбирается; причины fallback включают timeout, empty, invalid-json, missing-query, too-long, secret, error и interrupted. Один финальный LLM-вызов ограничен 120 секундами и получает статус timeout.
- RAG-запросы используют `max_tokens=4096` независимо от общего лимита и повторяют пустой ответ один раз; таймаут финального LLM-вызова (лимит 120 с на попытку) также повторяется один раз, после второго — статус timeout.
- Отчёты eval: `~/.ai-advent-agent/rag-results/rag-eval-<дата>.md`; локальная копия — `artifacts/rag-eval-<дата>.md` (не коммитить, кроме требуемой копии отчёта eval).

## Safety And Fast Mode
- Секреты хранить только в `.env`; права файла — `600` (`chmod 600 .env`). Не печатать ключи в логах, выводе команд, коде или индексах.
- Не изменять `.idea/vcs.xml` и `artifacts/`.
- Перед работой сверить branch/HEAD и рабочее дерево. Если HEAD известен и baseline не требуется, baseline не запускать.
- Fast Mode: запускать только затронутую тестовую группу; не использовать подагентов; `package` и `install-cli.sh` выполнять один раз в конце, когда это нужно задаче.
- Не пересобирать пользовательские индексы, если задача явно этого не требует.
- JSON-файлы с перезаписью сохранять атомарно (`tmp` в том же каталоге → move); сохранять текущие схемы и права доступа.

## Change Recipes
### Add A CLI Command
1. Разберите команду в `Main.handleCommand` или в тематическом обработчике; сохраняйте существующую передачу исходного текста и не вызывайте LLM для локальных команд.
2. Обновите `TerminalUi.chatCommandNames`, `chatIndex` и `chatCommandHelp`.
3. Добавьте проверки в `SelfTest` в группу UX или Команды; если нужна отдельная быстрая группа, добавьте аргументный маршрут и общий метод группы, не дублируя тесты.

### Add A Test Group
1. Добавьте static-проверки в отдельный `<Group>Checks.java` в `com.example`; общие helpers берите из `SelfTestSupport`.
2. Зарегистрируйте короткое имя, display name и `GroupChecks::run` в `SelfTest.groupRegistry`; `fullRun=true` добавляет группу в suite, иначе она остаётся explicit-only.
3. Внутри группы используйте общий `expect`; files/processes изолируйте через временный каталог и `SelfTestSupport`.
4. Используйте fake embedder/локальный HTTP или MCP stub; проверьте standalone и общий suite без изменения тел существующих проверок.

### Add An MCP Tool
1. Добавьте инструмент и обработчик в подходящий сервер (`PipelineMcpServer`, `GitMcpServer` или `GitMonitorMcpServer`); вход валидируйте, результат возвращайте структурированно.
2. Обновите схему/валидацию в `McpRegistry` и `ToolRouter`, если инструменту нужны новые аргументы или ограничения корня.
3. Для нового сервера добавьте его команду в `McpRegistry.open`; добавьте MCP SelfTest через `McpStubServer` или локальный stdio-процесс.
4. Выбор инструмента остаётся явным и ограниченным каталогом; не выводите токены и секреты.

## Reports
- Основной формат: таблица `Команда | Exit | Длительность | Результат`.
- Ошибка: команда, exit, длительность, stack trace, последние 80 строк вывода, stdout/stderr и пути к артефактам.
