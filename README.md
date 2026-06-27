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
| `get_summary`   | —                                   | `entity_id`, `period`, `metric` | aggregated min/max/avg/last + point count over the collector's stored data |
| `configure_collection` | —                            | `entities`, `interval` | view/update what is tracked and how often (persisted) |
| `save_report`   | `content`                           | `filename`, `format` | writes the text to a file under `OUTPUT_DIR`; returns the saved path + size |

Each tool has a human-readable `description` and a strict `inputSchema`
(required params enforced; the server returns a readable error instead of a
stack trace on bad input, unreachable HA, 401, or unknown entity).

### Endpoints proxied

- `GET  /api/states`                    — all entities
- `GET  /api/states/<entity_id>`        — one entity
- `POST /api/services/<domain>/<service>` — call a service (body `{"entity_id": "..."}` plus optional `data`)
- `GET  /api/`                          — auth/health probe

### Background collector & scheduler (day 18)

The server runs a **24/7 background collector**: a coroutine scheduler
(`Collector.kt`, own `SupervisorJob` scope) that every `COLLECT_INTERVAL` persists
readings to **SQLite** (`Storage.kt`). For tracked entities it pulls HA's **History
API** (`GET /api/history/period`) for the window `[lastEnd, now]` and stores **every
state change** HA's Recorder logged since the last tick — so short-lived changes
between ticks are captured, not sampled over (zero loss, bounded by Recorder
retention). It resumes from the newest stored row after a restart, backfilling the
downtime gap; overlapping windows are de-duplicated via a `UNIQUE(entity_id, ts)`
index + `INSERT OR IGNORE`. With **no `TRACKED_ENTITIES`** (track-all) it falls back
to a cheap `GET /api/states` snapshot, since History without an entity filter would
return every entity's full change log each cycle. It keeps running with **no agent
connected**, and one failed cycle (HA briefly unreachable) just logs to stderr and
retries on the next tick — it never tears the loop down. Optional `RETENTION_DAYS`
prunes old rows each cycle. Data lives on disk (`DB_PATH`), so it **survives
restarts**.

Two new MCP tools sit on top of that store:

- **`get_summary`** — `{ entity_id?, period?, metric? }`. Aggregates the stored
  series over a window (`period`: `1h`/`24h`/`7d`/`30m`/`60s`, default `24h`) and
  returns `min/max/avg/last` + point count. Omit `entity_id` to summarise every
  tracked entity; `metric` (`avg`/`min`/`max`/`last`) chooses the headline figure.
  This is the tool an agent calls **on a schedule** to emit a periodic digest.
- **`configure_collection`** — `{ entities?, interval? }`. Reads or updates what is
  tracked and how often. Changes apply on the next cycle and are **persisted to the
  DB** (`config` table), so they survive restarts. Call with no args to just read
  current settings.

Storage schema: `measurements(entity_id, state, value, ts, attributes)` —
`value` is the numeric parse of `state` (null for `on`/`home`/… states), `ts` is
epoch **millis** (preserves sub-second changes), unique per `(entity_id, ts)`. A
`config(id=1, entities, interval_seconds)` single row holds the persisted collection
settings.

### Composing tools into a pipeline (day 19)

The tools are designed to **chain** — the agent (not any hard-coded sequence) picks
the order from the user's request and feeds one tool's output into the next:

```
get_summary  →  (aggregated text)  →  save_report  →  file on disk
   fetch/process step                    save step
```

- **`save_report`** — `{ content, filename?, format? }`. Writes `content` verbatim
  (UTF-8) to a file under `OUTPUT_DIR`, creating the dir if needed, and returns the
  absolute path + byte count. `filename` is sanitised to a **bare basename** (any
  `/`, `\` or `..` is rejected — no path traversal); omit it for a timestamped
  `report-YYYYMMDD-HHmmss.<format>`. `format` is `md` (default), `txt` or `json` and
  only picks the extension — the server writes exactly what it is given and **never
  calls an LLM**.

A single natural request such as *"summarise the temperature sensors over the last
hour and save it to a file"* makes the agent call `get_summary` then `save_report`,
passing the summary text as `content` — the chain runs automatically.

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

Collector knobs (non-secret, all optional with sane defaults):

| Var               | Default                         | Meaning                                                        |
|-------------------|---------------------------------|----------------------------------------------------------------|
| `COLLECT_INTERVAL`| `60s`                           | Catch-up/persist cadence. Forms: `60s`, `5m`, `1h`. **Short for demos.** |
| `TRACKED_ENTITIES`| *(empty = all entities)*        | Comma-separated entity ids — history-backed (zero loss). Empty = all entities via snapshot fallback. |
| `DB_PATH`         | `<server>/data/measurements.db` | SQLite file (created if missing; survives restarts).           |
| `RETENTION_DAYS`  | *(unset = keep forever)*        | Prune measurements older than N days each cycle.               |
| `OUTPUT_DIR`      | `<server>/reports`              | Directory `save_report` writes report files into (created if missing). |

> Persisted `configure_collection` settings (in the DB) win over `COLLECT_INTERVAL`/
> `TRACKED_ENTITIES` env on the next start — the env values only seed the first run.

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

### Run the 24/7 collector (short interval for a demo)

The collector starts automatically with the server (both `--stdio` and `--sse`).
For a video you don't want to wait an hour between snapshots — set a short interval
so the scheduler **visibly fires**:

```bash
export HA_BASE_URL="http://homeassistant.local:8123"
export HA_TOKEN="eyJhbGciOiJ..."
export COLLECT_INTERVAL="60s"                                  # demo: once a minute
export TRACKED_ENTITIES="sensor.outdoor_temperature,sensor.living_room_humidity"
export DB_PATH="server/data/measurements.db"

java -jar server/build/libs/server-all.jar --stdio
# stderr shows, every minute (history-backed for tracked entities):
#   [collector] persisted 3 change(s) over [2026-06-25T18:59:00Z, 2026-06-25T19:00:00Z]
```

It records changes **even with no agent attached**. Leave it running, then point an
agent at the same jar; the persisted history is already there.

#### Agent pulls a summary on a schedule

The agent's own cron/timer (or a manual trigger) calls `get_summary` periodically and
uses the result — e.g. every few minutes during the demo:

```text
> give me the last 5 minutes summary
# · get_summary {"period":"5m"}
#   Summary over last 5m (metric=avg):
#   - sensor.outdoor_temperature: avg=21.3  (min=20.8, max=21.9, avg=21.3, last=21.5, n=5)
#   - sensor.living_room_humidity: avg=47   (min=46, max=49, avg=47, last=47, n=5)
# "Over the last 5 minutes the outdoor temp averaged 21.3 °C (20.8–21.9) and humidity 47%."
```

`configure_collection` lets the agent retarget the collector live:
`{"entities":["sensor.power"],"interval":"30s"}` → applies next cycle, persists.

### Run in Docker (local, no VPS needed)

A multi-stage `Dockerfile` lives at the repo root — it builds the fat jar with the
Gradle wrapper (no local JDK needed) and ships a JRE-only runtime image that runs as
a non-root user. The build context is the repo root because `settings.gradle.kts`
includes both modules.

```bash
docker build -t ha-mcp .
docker run --rm -p 3001:3001 \
  -e HA_BASE_URL="http://homeassistant.local:8123" \
  -e HA_TOKEN="eyJhbGciOiJ..." \
  -e COLLECT_INTERVAL="60s" \
  -e TRACKED_ENTITIES="sensor.outdoor_temperature" \
  -v ha_mcp_data:/data \
  ha-mcp
# …or pass the whole .env file at once:
docker run --rm -p 3001:3001 --env-file server/.env -v ha_mcp_data:/data ha-mcp
```

Defaults: SSE transport on `:3001` (`CMD ["--sse","3001"]`), SQLite at
`DB_PATH=/data/measurements.db` on the `/data` volume — so measurements **survive
restarts**. Secrets are never baked into the image; pass them at runtime via `-e` /
`--env-file`. Override the command for stdio if your client launches the container as
a subprocess:

```bash
docker run --rm -i --env-file server/.env -v ha_mcp_data:/data ha-mcp --stdio
```

#### Or with Docker Compose

A `compose.yaml` at the repo root wraps the same image: it builds on first run,
reads secrets from `server/.env`, pins `DB_PATH` into the `ha_mcp_data` volume, and
adds `restart: unless-stopped`.

```bash
cp server/.env.example server/.env   # fill in HA_BASE_URL / HA_TOKEN
docker compose up -d                 # build + run SSE on :3001
docker compose logs -f               # watch the collector tick
docker compose down                  # stop (volume kept)
```

The named volume keeps the SQLite file across restarts; `docker compose down -v`
removes it.

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
  6 tools listed with their parameter schemas.
- Run the live scenario: `list_entities` → pick a light → `call_service` turn_on
  → `get_state` shows `on`. That proves the agent calls a tool **and uses the result**.
- Optionally pull the token / show the server refusing to start without
  `HA_BASE_URL`/`HA_TOKEN`, to demonstrate secrets-from-env-only.

**Day 18 (scheduler) — what to show:**

- Start the server with `COLLECT_INTERVAL=60s` and watch stderr: every minute a
  `[collector] persisted N change(s) over […]` line proves the background task fires on
  schedule and persists — **with no agent connected**.
- Have the agent call `get_summary` (e.g. `{"period":"5m"}`) and read back the
  min/max/avg/last digest of the accumulated data — the agent **using the result**.
- Stop and restart the server, then call `get_summary` again: the earlier points are
  still there — proving data (and `configure_collection` settings) **survive restarts**.

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

#### Connect to the server running in Docker

`:client` talks only over **stdio** — it spawns the server command as a subprocess.
To use the Docker image, point it at `docker run` in stdio mode. Two things differ
from the bare-jar form: append `--stdio` (the image's default `CMD` is `--sse`,
which the client can't speak), and keep `docker run`'s `-i` so the container's stdin
stays open. HA secrets ride in via `--env-file`; that path is resolved against the
client's cwd (`client/`), so step up one with `..`.

```bash
# Build the image first (from the repo root):
docker build -t ha-mcp .

# Then have :client launch the container as its stdio server:
./gradlew :client:run --args="docker run --rm -i --env-file ../server/.env -v ha_mcp_data:/data ha-mcp --stdio"
```

To reuse the **Compose** definition (its `env_file` + `ha_mcp_data` volume) instead
of spelling out the flags, spawn a one-off `docker compose run` in stdio mode. The
detached `docker compose up -d` stack is SSE-only, so it can't back the client — use
`run`, not `up`. `compose.yaml` sits at the repo root, so point `-f` up one from the
client's cwd, and override the default `--sse` command with `--stdio`:

```bash
./gradlew :client:run --args="docker compose -f ../compose.yaml run --rm ha-mcp --stdio"
```

Both the `up -d` SSE stack and this one-off `run` share the `ha_mcp_data` volume, so
the collector's history is the same DB either way.

Agent mode still keys off `client/.env` (DeepSeek), independent of the container's
own env — the same `client/.env` setup below applies.

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
- SQLite via `org.xerial:sqlite-jdbc` for the background collector's persistent store.
- Coroutine scheduler (`SupervisorJob` scope) for the 24/7 background collection loop.
- `dotenv-kotlin` for local `.env` secrets (process env takes precedence).
- Transports: `StdioServerTransport` (default) and Ktor SSE (`mcp { … }` plugin).
- DeepSeek API (`deepseek-v4-pro`, OpenAI-compatible function calling) via Ktor client for the agent loop.
- Mordant (`mordant-markdown`) for terminal markdown rendering (ANSI on a TTY, plain text when piped).
