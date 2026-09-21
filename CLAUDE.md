# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

MineAi is a Minecraft **NeoForge 1.21.1** mod (Java 21) that adds a server-side `/ai <prompt>` command
forwarding prompts to an AI provider. Mod id `mineai`, package `io.github.nikitaaovramenko.mineai`.

Two providers are supported, selected by the `provider` config key:

| `provider` | Endpoint | Key / model options |
|---|---|---|
| `anthropic` (default) | `POST https://api.anthropic.com/v1/messages` | `anthropicApiKey`, `anthropicModel` |
| `openai` | `POST https://api.openai.com/v1/responses` | `openaiApiKey`, `openaiModel` |

There is no backend, no conversation history, and no client/server packets of its own: every `/ai`
invocation is an independent HTTP request made from the server (or the integrated server in
single-player), and the answer is sent back only to the requesting player.

## Commands

```bash
./gradlew build          # compile + test + jar -> build/libs/mineai-1.0.0.jar
./gradlew test           # JUnit 4 unit tests only (no Minecraft launched)
./gradlew runClient      # launch dev client (working dir: run/)
./gradlew runServer      # launch dev dedicated server
./gradlew runData        # data generators -> src/generated/resources
./gradlew runGameTestServer  # run gametests headless, then exit
```

Windows: `.\gradlew.bat <task>`. Single test: `./gradlew test --tests '*AnthropicClientTest.marksTruncatedAnswers'`.
`gradlew --refresh-dependencies` after dependency changes; `gradlew clean` to reset build outputs.

Gradle configuration cache is enabled in `gradle.properties`, so build-script edits force a
reconfigure — expect the first run after touching `build.gradle` to be slower.

## Architecture

The package splits along one deliberate line: **Minecraft-coupled classes vs. provider clients that
import no Minecraft at all**, so the wire-format parsing is testable with plain JUnit.

Minecraft side:

- **`MineAi.java`** — `@Mod` main class. Registers `/ai` via `RegisterCommandsEvent`, resolves the
  configured `AiProvider`, and holds `pendingPrompts` (one in-flight request per player UUID).
- **`Config.java`** — `ModConfigSpec` registered as `ModConfig.Type.COMMON`, so keys live on the
  server (`config/mineai-common.toml`). `Config.apiKey(provider)` / `Config.model(provider)` map a
  provider to its option, so callers never switch on the provider themselves.
- **`MineAiClient.java`** — `dist = Dist.CLIENT` only; registers the NeoForge config screen. Never
  reference client classes outside this file.

Provider side (**no Minecraft imports — keep it that way**):

- **`AiProvider.java`** — the enum of providers. Owns each provider's id, display name, config option
  names, and default model, and dispatches `ask(...)` to the right client. Adding a provider means
  adding a constant here, a client, its two `Config` options, and the `Config` switch arms.
- **`AiClients.java`** — shared `HttpClient`, the system prompt, the truncation note, and
  `httpFailure(...)`, the single place HTTP status codes become player-visible text.
- **`OpenAiClient.java` / `AnthropicClient.java`** — request building plus a package-private static
  `parseResponse(int status, String body)` that the tests drive directly.
- **`RequestException.java`** — a message already safe to show a player.

### Invariants that are easy to break

**Threading.** `HttpClient` completion callbacks run off the server thread. All player/level/server
access must be wrapped in `server.execute(...)`, as `askAi` does. The callback also guards with
`server.isStopped()` and re-looks-up the player by UUID (`getPlayerList().getPlayer(playerId) != player`)
so a disconnect or relog during a request can't leak a message to the wrong player object.

**Error messages must never contain the API response body.** `AiClients.httpFailure` maps status codes
to fixed strings; raw bodies can echo back credentials or request details. Both
`errorsDoNotExposeResponseBodies` tests assert this — don't "improve" errors by appending the body.

**The model is a free-form config string, so keep request bodies model-agnostic.** The Anthropic
request deliberately sends only `model`, `max_tokens`, `system`, `messages`. `output_config.effort`
400s on Haiku 4.5, and `fallbacks` only works on Opus-5-class models — either would break a perfectly
valid `anthropicModel`. Anything model-specific needs a config option and a documented default.

**Response shapes differ and both interleave non-answer items.**
- OpenAI: `output[]` mixes `reasoning` and `message` items; message `content` parts are `output_text`
  or `refusal`; top-level `"status": "incomplete"` means truncation.
- Anthropic: `content[]` mixes `thinking` and `text` blocks (thinking arrives empty unless the request
  asks for a summary); `stop_reason` carries `max_tokens` for truncation and `refusal` for a policy
  decline — **a decline is HTTP 200 with an empty `content` array**, so blank text is explained by
  `stop_reason`, never by the status code.

Empty or unparseable output raises `RequestException` rather than returning a blank chat line.

`AnthropicClient` uses a larger `MAX_OUTPUT_TOKENS` (4000 vs. OpenAI's 800) and a longer timeout
(90s vs. 60s) because current Claude models think adaptively by default and that thinking shares the
output budget and the wall clock with the reply.

Gson is supplied by Minecraft at runtime; plain unit tests get their own copy via the `testRuntimeOnly`
declaration in `build.gradle`. There is no provider SDK dependency by design — a mod jar would have to
bundle it via jarJar/shadow and risk classloader conflicts with Minecraft's own libraries.

### Metadata and resources

`src/main/templates/META-INF/neoforge.mods.toml` is a **template** — the `generateModMetadata` task
expands `${mod_id}`, `${mod_version}`, `${neo_version}` etc. from `gradle.properties` into
`build/generated/sources/modMetadata`. Edit the template and `gradle.properties`, never the generated copy.
Mod id, version, MC/Neo versions, and the Parchment mappings version all live in `gradle.properties`.

Every config option needs a `mineai.configuration.<key>` entry in
`src/main/resources/assets/mineai/lang/en_us.json` or it shows up unlabeled in the config screen.

## Template leftovers

This repo was generated from the NeoForge MDK template and the boilerplate was never stripped.
`EXAMPLE_BLOCK`, `EXAMPLE_ITEM`, `EXAMPLE_TAB`, the `logDirtBlock` / `magicNumber` / `items` config
options, and the `HELLO FROM ...` log lines are template demo code, not mod features. `Config.java`
and `MineAi.java` also carry several unused imports from it. Treat all of it as removable; don't build
new behaviour on top of it.
