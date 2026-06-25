# learnmcp — MCP on Kotlin/JVM

Multi-module Gradle project. Two independent runnables:

| Module     | What it is                                                                 | Run                       |
|------------|----------------------------------------------------------------------------|---------------------------|
| `:server`  | Own MCP server around the **Home Assistant** REST API.                     | `./gradlew :server:run`   |
| `:client`  | Minimal MCP **client** (handshake + `tools/list` + `tools/call` over stdio). | `./gradlew :client:run`   |

The wrapper (`./gradlew`) lives at the repo root; each module builds and runs on its own.

---

## Home Assistant MCP server (`:server`)

A self-written MCP server (no `mcpany`/`openmcp` wrapper) that proxies the Home
Assistant REST API and exposes smart-home tools to an agent.

### Tools

| Tool            | Required params                     | Optional   | Returns                                            |
|-----------------|-------------------------------------|------------|----------------------------------------------------|
| `list_entities` | —                                   | `domain`   | entity_id + friendly_name + state (filtered)       |
| `get_state`     | `entity_id`                         | —          | state + attributes of one entity                   |
| `call_service`  | `domain`, `service`, `entity_id`    | `data`     | execution status + new state (when HA reports it)  |
| `get_sensor`    | `entity_id`                         | —          | numeric value + unit                               |

Each tool has a human-readable `description` and a strict `inputSchema`
(required params enforced; the server returns a readable error instead of a
stack trace on bad input, unreachable HA, 401, or unknown entity).

### Endpoints proxied

- `GET  /api/states`                    — all entities
- `GET  /api/states/<entity_id>`        — one entity
- `POST /api/services/<domain>/<service>` — call a service (body `{"entity_id": "..."}` plus optional `data`)
- `GET  /api/`                          — auth/health probe

### Prerequisites

- **JDK 17+** (tested on JDK 21). Gradle not needed — use the wrapper.
- A reachable **Home Assistant** instance.
- A **Long-Lived Access Token**: in HA, click your profile (bottom-left) →
  **Security** tab → **Long-Lived Access Tokens** → **Create Token**. Copy it once.

### Configuration (secrets via env only)

Either drop the values in a `.env` file (auto-loaded at startup), or export them in
your shell. A real process env var always wins over the `.env` file.

```bash
cp server/.env.example server/.env   # then edit — picked up automatically
# …or export instead:
export HA_BASE_URL="http://homeassistant.local:8123"   # with or without trailing /api
export HA_TOKEN="eyJhbGciOiJ..."                       # the Long-Lived token
```

The server loads its own `server/.env` (located via the running jar), falling back to
the working directory. A real process env var always wins over either file.
No secret is ever read from code or args — only `HA_BASE_URL` and `HA_TOKEN`.

### Run locally (stdio)

```bash
# Gradle (reads env from your shell):
HA_BASE_URL=... HA_TOKEN=... ./gradlew :server:run --args="--stdio"

# Or build a fat jar and run it directly:
./gradlew :server:shadowJar
java -jar server/build/libs/server-all.jar --stdio
```

`--stdio` is the default (so bare `:server:run` works too). Service logs go to
**stderr**; **stdout** carries only the MCP protocol.

### Connect it to a code assistant (stdio MCP)

Point the assistant's MCP config at the jar. Example (`mcpServers` shape used by
Claude Code / most clients):

```json
{
  "mcpServers": {
    "home-assistant": {
      "command": "java",
      "args": ["-jar", "/abs/path/to/server-all.jar", "--stdio"],
      "env": {
        "HA_BASE_URL": "http://homeassistant.local:8123",
        "HA_TOKEN": "eyJhbGciOiJ..."
      }
    }
  }
}
```

### Demo scenario (agent uses the result)

1. Agent calls `list_entities` with `{"domain": "light"}` → finds `light.kitchen [off]`.
2. Agent calls `call_service` `{"domain":"light","service":"turn_on","entity_id":"light.kitchen"}`
   → server returns `Called light/turn_on on light.kitchen — OK. New state: on`.
3. Agent calls `get_state` `{"entity_id":"light.kitchen"}` → reads back `state: on` and brightness attributes.

### Test with MCP Inspector (before deploy)

```bash
./gradlew :server:shadowJar
# stdio mode — Inspector launches the jar as a subprocess:
npx @modelcontextprotocol/inspector \
  -e HA_BASE_URL=http://homeassistant.local:8123 \
  -e HA_TOKEN=eyJhbGciOiJ... \
  java -jar server/build/libs/server-all.jar --stdio
```

Open the Inspector URL, hit **List Tools**, then call `list_entities` /
`call_service` and watch the JSON results.

### Deploy on a VPS (HTTP/SSE transport)

The same server exposes an SSE transport for remote use:

```bash
java -jar server-all.jar --sse 3001        # endpoint: http://0.0.0.0:3001/sse
```

On the VPS: copy `server-all.jar`, set `HA_BASE_URL`/`HA_TOKEN` in the unit
environment, run under systemd, and put a TLS reverse proxy (Caddy/Nginx) in
front of `:3001`. Point your agent's SSE MCP config at `https://<vps>/sse`.

> Note: the bundled SSE setup allows any origin for convenience — restrict CORS
> and require TLS before exposing it publicly.

### What to show in the demo video

- Start the server and connect from MCP Inspector (or your assistant); show the
  4 tools listed with their parameter schemas.
- Run the live scenario: `list_entities` → pick a light → `call_service` turn_on
  → `get_state` shows `on`. That proves the agent calls a tool **and uses the result**.
- Optionally pull the token / show the server refusing to start without
  `HA_BASE_URL`/`HA_TOKEN`, to demonstrate secrets-from-env-only.

---

## MCP client (`:client`)

Minimal stdio MCP client: spawns a server, runs `initialize` + `tools/list`,
prints the tools, then **calls a tool and uses the result**. It picks
`list_entities` against this project's server (and chains the first entity into a
`get_state` call), or `echo` against the reference server, or any no-required-param
tool otherwise. Defaults to `@modelcontextprotocol/server-everything` (needs
Node/npx), or point it at any stdio server — including this project's own:

```bash
# Build the server jar first:
./gradlew :server:shadowJar

# `:client:run` runs with cwd = the client/ module dir, so step up one with `..`
# to reach the server jar at the repo root.
./gradlew :client:run --args="java -jar ../server/build/libs/server-all.jar --stdio"
```

### Agent mode (DeepSeek LLM drives the tools)

With a DeepSeek key present, `:client` becomes an interactive **LLM agent**: it
lists the server's tools, hands them to DeepSeek as function-calling tools, then
loops — the model picks a tool, the client calls it over MCP, the result is fed
back, and the model uses it to act and answer.

```bash
cp client/.env.example client/.env   # then set DEEPSEEK_API_KEY (model defaults to deepseek-v4-pro)
./gradlew :server:shadowJar
./gradlew :client:run --args="java -jar ../server/build/libs/server-all.jar --stdio"
> turn on the kitchen light
# · list_entities {"domain":"light"}  -> finds light.kitchen [off]
# · call_service {"domain":"light","service":"turn_on","entity_id":"light.kitchen"}
# · get_state {"entity_id":"light.kitchen"}
# "Kitchen light is now on."
```

Switching a light on/off/toggle is the existing `call_service` tool
(`service` = `turn_on` | `turn_off` | `toggle`) — no extra tool needed. Without
`DEEPSEEK_API_KEY`, `:client` runs the deterministic demo instead.

The agent's answers (and tool results in the demo) are rendered as **terminal
markdown** via Mordant: headings, bold, lists, and code show as ANSI on an
interactive TTY, and auto-downgrade to clean plain text when stdout is piped or
redirected (markers stripped, no escape codes). Rendering goes through a single
`renderMarkdown` seam that falls back to a plain print if the widget throws, so
an answer is never silently dropped.

## Stack

- Kotlin/JVM, Gradle (Kotlin DSL, multi-module), coroutines.
- Official MCP Kotlin SDK `io.modelcontextprotocol:kotlin-sdk:0.13.0`.
- Ktor client (CIO) + kotlinx.serialization for the Home Assistant API.
- `dotenv-kotlin` for local `.env` secrets (process env takes precedence).
- Transports: `StdioServerTransport` (default) and Ktor SSE (`mcp { … }` plugin).
- DeepSeek API (`deepseek-v4-pro`, OpenAI-compatible function calling) via Ktor client for the agent loop.
- Mordant (`mordant-markdown`) for terminal markdown rendering (ANSI on a TTY, plain text when piped).
