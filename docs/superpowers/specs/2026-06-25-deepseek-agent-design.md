# DeepSeek LLM agent in `:client`

**Date:** 2026-06-25
**Status:** Approved (design)
**Module:** `:client`

## Goal

Turn the minimal deterministic MCP client into a real **LLM agent**: take a
natural-language goal, let an LLM decide which MCP tool to call, execute the
call over MCP, feed the result back, and let the LLM use that result to form the
next call and the final answer. This satisfies the task requirement —
*the agent calls a tool from the app and uses the result* — with an actual
reasoning agent rather than hardcoded tool selection.

## Decisions

- **Where:** extend the existing `:client` module (no new module).
- **Interaction:** interactive REPL (multi-turn chat on stdin).
- **Target server:** default to this repo's Home Assistant server jar; still
  overridable via `--args` exactly like the current client.
- **LLM:** DeepSeek, model `deepseek-v4-pro`. OpenAI-compatible API, supports
  function calling in all thinking modes. (Legacy aliases `deepseek-chat` /
  `deepseek-reasoner` retire 2026-07-24, so use the V4 name.) Model overridable
  via `DEEPSEEK_MODEL`, default `deepseek-v4-pro`.
- **Secrets:** `DEEPSEEK_API_KEY` from process env or `client/.env`
  (`dotenv-kotlin`, process env wins), matching the `:server` pattern. Never
  read from code or CLI args.

## Architecture

Three source files in `:client`:

- **`DeepSeek.kt`** — thin DeepSeek chat client.
  - Ktor CIO HTTP client + kotlinx.serialization.
  - Base URL `https://api.deepseek.com`, endpoint `POST /chat/completions`,
    `Authorization: Bearer <key>`.
  - DTOs: `ChatRequest` (model, messages, tools, optional tool_choice),
    `ChatMessage` (role, content?, tool_calls?, tool_call_id?, name?),
    `ToolDef` (type=function, function{name, description, parameters}),
    `ToolCall` (id, type, function{name, arguments-as-JSON-string}),
    `ChatResponse` (choices[0].message).
  - One public `suspend fun chat(messages, tools): ChatMessage`.
  - V4 Pro is a thinking model: replies may carry `reasoning_content`. Ignore
    it (only `content` + `tool_calls` drive the loop); configure the JSON parser
    with `ignoreUnknownKeys = true` so extra fields don't break deserialization.

- **`Agent.kt`** — the agent loop + bridge + REPL.
  - `toDeepSeekTools(tools: List<Tool>): List<ToolDef>` — map each MCP tool to a
    DeepSeek tool. MCP `inputSchema` is already JSON Schema, so it maps almost
    1:1 into `function.parameters`.
  - `runRepl(mcpClient, tools, deepseek)`:
    1. Seed message history with a short system prompt (you control a smart
       home via these tools; call tools to answer).
    2. Read a line from stdin (`> ` prompt). `exit`/`quit`/EOF ends.
    3. Append user message; run the **tool-calling loop**:
       - Call `deepseek.chat(history, tools)`.
       - If reply has `tool_calls`: for each, parse `arguments` JSON →
         `mcpClient.callTool(name, args)`; append a `role:"tool"` message with
         `tool_call_id` and the result text; loop again.
       - If reply has plain `content` and no tool_calls: print it, break to REPL.
       - Bound the loop at **8 iterations**; on overflow, stop and warn.

- **`Main.kt`** — unchanged server spawn + `initialize` + `tools/list`, then:
  - If `DEEPSEEK_API_KEY` present → build DeepSeek client, enter `runRepl`.
  - If absent → fall back to the existing deterministic demo call (current
    behavior and unit-test expectations preserved). Log why.

## Build changes (`client/build.gradle.kts`)

Add (versions aligned with SDK 0.13.0 / existing `:server`):
- `kotlin("plugin.serialization")`
- `org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0`
- `io.ktor:ktor-client-core:3.4.3`, `ktor-client-cio:3.4.3`,
  `ktor-client-content-negotiation:3.4.3`,
  `ktor-serialization-kotlinx-json:3.4.3`
- `io.github.cdimascio:dotenv-kotlin:6.5.1`

## Config files

- `client/.env.example` — `DEEPSEEK_API_KEY=` (+ comment, optional
  `DEEPSEEK_MODEL`).
- Confirm `client/.env` is gitignored (root `.gitignore` already ignores
  `.env`; verify it covers `client/.env`).

## Data flow

```
stdin goal
   -> history += user
   -> DeepSeek.chat(history, tools)
        -> tool_calls? --yes--> mcpClient.callTool(...) -> history += tool result --loop-->
        -> content?    --yes--> print, return to REPL
```

## Error handling

- Missing `DEEPSEEK_API_KEY` → deterministic fallback (not an error).
- DeepSeek HTTP error / 401 / network → readable stderr message; stay in REPL.
- Tool-call args fail to parse, or `callTool` errors → feed the error text back
  to the model as the tool result so it can recover; do not crash.
- Loop cap (8) reached → print a notice, return to REPL.

## Testing

- Keep existing `:client` runnable with no key (fallback path) so current
  behavior and any tests stay green.
- New pure-logic units worth a test: `toDeepSeekTools` mapping (MCP schema →
  DeepSeek `parameters`), and tool-call argument parsing. DeepSeek HTTP itself
  is exercised manually via the REPL (live key), not unit-tested.

## Demo (README addendum, later)

```
./gradlew :server:shadowJar
# put DEEPSEEK_API_KEY in client/.env
./gradlew :client:run --args="java -jar ../server/build/libs/server-all.jar --stdio"
> turn on the kitchen light
# agent: list_entities -> finds light.kitchen[off] -> call_service turn_on
#        -> get_state -> "Kitchen light is now on."
```

## Out of scope (YAGNI)

- Streaming responses.
- Multiple LLM providers / provider abstraction.
- Persisting chat history across runs.
- GUI. Terminal REPL only.
