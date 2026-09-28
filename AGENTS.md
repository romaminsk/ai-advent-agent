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
- `src/test/java/com/example` — локальный self-test harness (`SelfTest`), MCP stub и writer для процессных тестов.

## Run And Build
- Запуск установленного CLI: `ai-agent` (launcher загружает настройки из `.env`).
- Сборка и установка: `mvn -q package && ./install-cli.sh`.
- Справка без запуска диалога: `ai-agent --help`; plain UI: `ai-agent --plain`.

## Tests
- Группа: `mvn -q test-compile exec:java@self-test -Dexec.args=<аргумент>`.
- Самостоятельные аргументы: `orchestration`, `orchestration-live`, `mcp`, `index`, `ux`.
- `mcp` запускает обе группы MCP и MCP Pipeline; `orchestration-live` обращается к настроенному провайдеру.
- Группы полного suite: Диалог, Хранилища, Состояние задачи, Контекст, Команды, InvariantGuard, UX, MCP, MCP Pipeline, Orchestration, Worker планировщик, Worker процесс, Store и runner отказы, Измерения, Тихий вывод, Интеграция.
- Остальные группы не имеют отдельного аргумента и запускаются полным suite.
- Полный прогон: `mvn -q test-compile exec:java@self-test`; выполнять один раз в конце задачи перед слиянием, если это требуется.
- SelfTest использует локальные HTTPS/MCP stubs и `expect`; добавляйте тесты в существующую тематическую группу.

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
1. Добавьте тематические `check...` методы в `SelfTest` и вызов `group("...")` в основном списке.
2. Для отдельного запуска добавьте `args[0]` маршрут с временным каталогом и гарантированной очисткой; тот же метод группы вызывайте из полного suite.
3. Используйте фейковый embedder/локальный HTTP или MCP stub; не делайте платные запросы в обычной группе.

### Add An MCP Tool
1. Добавьте инструмент и обработчик в подходящий сервер (`PipelineMcpServer`, `GitMcpServer` или `GitMonitorMcpServer`); вход валидируйте, результат возвращайте структурированно.
2. Обновите схему/валидацию в `McpRegistry` и `ToolRouter`, если инструменту нужны новые аргументы или ограничения корня.
3. Для нового сервера добавьте его команду в `McpRegistry.open`; добавьте MCP SelfTest через `McpStubServer` или локальный stdio-процесс.
4. Выбор инструмента остаётся явным и ограниченным каталогом; не выводите токены и секреты.

## Reports
- Основной формат: таблица `Команда | Exit | Длительность | Результат`.
- Ошибка: команда, exit, длительность, stack trace, последние 80 строк вывода, stdout/stderr и пути к артефактам.
