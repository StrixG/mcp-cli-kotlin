# mcp-cli-kotlin

Минимальный CLI-клиент для **Model Context Protocol (MCP)** на Kotlin/JVM.
Подключается к готовому MCP-серверу по STDIO, делает handshake и печатает
список доступных инструментов (`tools/list`).

## Что делает

1. Поднимает MCP-сервер как сабпроцесс (STDIO-транспорт).
2. Выполняет `initialize` (handshake) и `tools/list`.
3. Печатает имя сервера/версию и список инструментов: имя, описание,
   краткую схему входных параметров (`*` = обязательный).
4. Корректно закрывает соединение и завершает процесс.

## Предусловия

- **JDK 17+** (проверено на JDK 21).
- **Node.js / npx** — нужен только для дефолтного stdio-сервера
  `@modelcontextprotocol/server-everything` (скачивается через `npx -y`).
- Gradle ставить не нужно — используется wrapper (`./gradlew`).

## Запуск

Сервер по умолчанию (эталонный `server-everything`):

```bash
./gradlew run
```

Свой сервер — командой через `--args` (команда + её аргументы):

```bash
./gradlew run --args="npx -y @modelcontextprotocol/server-everything"
# любой другой stdio-сервер, например собственный jar:
./gradlew run --args="java -jar /path/to/your-mcp-server.jar"
```

На Windows запускать так же (`gradlew.bat run` или `./gradlew run` в Git Bash);
клиент сам оборачивает команду в `cmd /c`, чтобы найти `npx.cmd`.

## Пример вывода

```
Connected to: mcp-servers/everything 2.0.0
Available tools (13):
  1. echo — Echoes back the input string
     params: message: string*
  2. get-sum — Returns the sum of two numbers
     params: a: number*, b: number*
  ...
```

Служебные логи (старт сервера, SLF4J, ошибки) идут в **stderr**, поэтому
stdout содержит только результат.

## Стек

- Kotlin/JVM, Gradle (Kotlin DSL).
- Официальный MCP Kotlin SDK: `io.modelcontextprotocol:kotlin-sdk`.
- `kotlinx.coroutines` (`runBlocking` в `main`).
- Транспорт: `StdioClientTransport`.

## Демо-видео

Запустите `./gradlew run` и покажите строку `Connected to: …` вместе со
списком `Available tools (N)` — это доказывает, что соединение установлено,
протокол ответил и приложение чисто завершилось.
