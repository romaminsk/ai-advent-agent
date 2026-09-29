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
- Задачи/ограничения: `TaskState`, `TaskStage`, `TaskStatus`, `InvariantGuard`.
- MCP: `McpClientComponent`, `McpRegistry`, `McpOrchestrator`, `ToolRouter`, `GitMcpServer`, `GitMonitorMcpServer`, `PipelineMcpServer`, `PipelineRunner`.
- Monitor: `MonitorStore`, `MonitorRunner`, `MonitorWorker`, `MonitorSchedule`, `MonitorAggregator`.
- Pipeline utilities: `FileSearcher`, `SearchSummarizer`, `ResultFileWriter`, `PipelineCanonicalJson`.
- `com.example.index` — загрузка документов, chunking, embeddings, кэш, JSON-индекс, поиск и сравнение.
- `DocumentLoader` исключает `src/test/**`, `target/`, `artifacts/`, `.idea/`, `.git/`, скрытые каталоги и `.env*` из корпуса.
- `com.example.rag` — retrieval по structure-индексу, ограничение контекста, prompt и сравнение RAG/off.
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
- `/rag on|off|status` управляет RAG в обычном диалоге; `/rag retrieval [вопрос]` показывает structure top-5 одного запроса либо сравнивает fixed/structure по 9 контрольным вопросам без LLM; `/rag ask <вопрос>` печатает поиск и ответы off/on по мере готовности, `/rag eval` запускает 10 вопросов.
- RAG-запросы используют `max_tokens=4096` независимо от общего лимита и повторяют пустой ответ один раз.
- Отчёты eval: `~/.ai-advent-agent/rag-results/rag-eval-<дата>.md`; локальная копия — `artifacts/rag-eval-<дата>.md` (не коммитить).

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
