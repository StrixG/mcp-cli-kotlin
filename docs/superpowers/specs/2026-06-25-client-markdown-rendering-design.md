# Markdown rendering for the `:client` CLI

**Date:** 2026-06-25
**Status:** Approved

## Problem

The `:client` agent prints LLM answers (`reply.content` in `Agent.kt`) and tool
result text straight to stdout. That content is markdown source — headings,
`**bold**`, bullet lists, fenced code blocks — shown raw, so the terminal output
is noisy and hard to read.

## Goal

Render markdown answers as formatted ANSI terminal output, while keeping stdout
safe to pipe/redirect (plain text when not a TTY).

## Approach

Use the **Mordant** library (com.github.ajalt.mordant) — it ships a `Markdown`
widget that renders markdown → ANSI in the terminal, auto-enables Windows virtual
terminal sequences, handles word-wrap, and auto-downgrades to plain text when the
output is not an interactive TTY (pipe/redirect). This avoids hand-writing an AST
visitor on top of flexmark/commonmark.

No mature one-shot markdown→ANSI JVM library exists other than Mordant's widget,
so this is the lightest correct option.

## Scope

Render as markdown:
- Agent final answers (`reply.content`) in `Agent.kt` `runTurn`.
- Tool result text printed to the user in `Main.kt` `runDeterministicDemo`
  (the printed `result.text()` / `state.text()` output).

Stay plain (NOT markdown):
- Connection banner, tool listing (`printTool`), all stderr diagnostics.
- The markdown history fed back to the model — the model wants raw markdown, not
  ANSI escapes, so history is untouched.

## Components

### New dependency (`client/build.gradle.kts`)
```
implementation("com.github.ajalt.mordant:mordant-markdown:3.0.2")
```
Pulls Mordant core transitively. 3.0.2 is the latest stable.

### New file `client/src/main/kotlin/Render.kt`
- Holds a single `Terminal` instance (Mordant). Default construction auto-detects
  ANSI capability and terminal width.
- `fun renderMarkdown(text: String)`:
  - `terminal.println(Markdown(text))`.
  - Wrapped in try/catch: on any parser/render exception, fall back to a plain
    `terminal.println(text)` (or `println(text)`) so an answer is never dropped.
- The `Terminal` is injectable (default parameter = the real auto-detected one) so
  tests can supply a recorder-backed, ANSI-NONE terminal.

### Wire-in
- `Agent.kt` `runTurn`: replace
  `println(reply.content?.trim().orEmpty().ifEmpty { "(no answer)" })`
  with rendering through `renderMarkdown(...)`, preserving the `(no answer)`
  fallback for blank content.
- `Main.kt` `runDeterministicDemo`: route the printed tool result text
  (`out.ifBlank { "(empty)" }` and the follow-up `state.text()`) through
  `renderMarkdown(...)`, preserving the `(empty)` fallback. The `Result:` /
  `Using result ->` labels stay plain `println`.

## Data flow

Unchanged except the final print step. Tool calls, history accumulation, and the
messages sent to DeepSeek are identical. Only the user-facing print of the final
answer / demo result swaps `println` → `renderMarkdown`.

## Error handling

- Render/parse exception → catch → plain `println(text)` fallback.
- Empty/blank content → existing `(no answer)` / `(empty)` fallbacks preserved
  (checked before/around the render call).
- Non-TTY stdout (pipe/redirect) → Mordant auto-outputs plain text; no special
  handling needed.

## Testing (TDD)

Use Mordant's `TerminalRecorder` with `AnsiLevel.NONE` to capture deterministic,
escape-free output:
- `renderMarkdown` with an injected recorder terminal.
- `**bold**` → output contains `bold`, excludes `**`.
- A heading (`# Title`) → output contains `Title`, excludes `#`.
- Inline code / a bullet list → marker characters stripped, text retained.

Tests are deterministic (no real ANSI, no real terminal). Existing
`DeepSeekSerializationTest` and `ToolBridgeTest` are untouched.

## Out of scope

- Styling the connection banner or tool listing.
- Rendering the markdown history sent to the model.
- Configurable themes / color schemes.
