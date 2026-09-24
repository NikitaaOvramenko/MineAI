# MineAI

[![Build](https://github.com/NikitaaOvramenko/MineAI/actions/workflows/build.yml/badge.svg)](https://github.com/NikitaaOvramenko/MineAI/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
![Minecraft 1.21.1](https://img.shields.io/badge/Minecraft-1.21.1-brightgreen)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.251-orange)
![Java 21](https://img.shields.io/badge/Java-21-blue)

**Talk to Claude from inside Minecraft.** MineAI is a server-side NeoForge
mod that adds a `/ai <prompt>` command: type a question or request in chat,
and an LLM answers — with the ability to call back into the live game world
through function calling (checking the seed, locating structures, reading
your inventory, searching player-built storage, even placing a block).

> 🚧 **Work in progress.** Core chat + tool calling works today; memory and
> retrieval-augmented context are on the [roadmap](#roadmap).

## Demo

<!--
  TODO: drop real media here, e.g.:
  ![MineAI demo](docs/media/demo.gif)
  <img src="docs/media/screenshot-tools.png" width="600" alt="MineAI locating a village">
-->
_Screenshots and a demo video coming soon._

## What it does

- **`/ai <prompt>`** — any player can ask anything, no permission node
  required. The reply comes back privately to that player only, as a normal
  chat message.
- **No backend, no history.** Every command is one independent, asynchronous
  HTTP request straight from the server (or the integrated server in
  single-player) to the configured provider's API. Nothing is stored between
  requests, and nothing is broadcast to other players.
- **One request at a time per player** — a second `/ai` while one is still
  in flight gets a "please wait" message instead of queuing or racing.
- **Function calling into the running world.** With a LangChain4j-backed
  provider, the model can call tools to look things up or act — see below.

## Tools (function calling)

Only the LangChain4j-routed providers (`anthropic-lc4j`, `google`) can call
tools. Every tool that mirrors a vanilla command re-checks that command's own
permission via the live command dispatcher (e.g. a tool wrapping `/locate`
is unusable by a player who couldn't run `/locate` themselves), so the AI
never sees or does more than the player already could.

| Tool | What it lets the AI do |
|---|---|
| `getWorldSeed` | Returns the world seed — gated like `/seed`. |
| `findNearestStructure` | Finds the nearest structure of a given type (village, stronghold, etc.) — gated like `/locate`. |
| `placeBlock` | Places a single block near the player, relative to where they're facing — gated like `/setblock`, and only into empty/replaceable space. |
| `getInventoryItems` | Lists everything currently in the player's own inventory. |
| `getRecipesForItem` | Looks up recipes (vanilla and modded) that produce a given item, including ingredients and output count. |
| `listNearbyContainers` | Lists nearby storage the *player* placed — chests, barrels, shulker boxes, storage minecarts/boats, chested tamed animals — and everything inside, skipping naturally-generated loot chests. |
| `findItemInStorage` | Searches that same tracked storage for a specific item and reports how much is available and where. |
| `highlightStorage` | Marks a storage location with particles, visible only to the requesting player, for about 10 seconds. |

Storage tools only ever see player-placed containers: a NeoForge block-place
listener tags each chest/barrel/shulker box (and chest minecart/boat) with
its placer's UUID the moment it enters the world, so an unopened dungeon
chest is never exposed to the model and never has its loot generated early.

## Providers

| Provider | `provider` value | Transport | Function calling |
|---|---|---|---|
| **Anthropic (Claude)** — default | `anthropic` | Direct HTTPS to the Messages API | No |
| OpenAI | `openai` | Direct HTTPS to the Responses API | No |
| Anthropic via LangChain4j *(spike)* | `anthropic-lc4j` | [LangChain4j](https://github.com/langchain4j/langchain4j) | Yes |
| Google Gemini | `google` | LangChain4j | Yes |

Claude is the default and primary provider. The two direct-HTTP clients
(`anthropic`, `openai`) are deliberately minimal and model-agnostic; the two
LangChain4j-routed providers share one tool-calling loop that runs the
model's requested tools and feeds the results back until it answers.

## Architecture

The codebase splits along one line: Minecraft-coupled code vs. the
`providers` package, which **imports no Minecraft classes at all** — so the
HTTP request/response parsing for each provider is covered by plain JUnit
tests, no Minecraft launch required.

- Requests run asynchronously off the server thread; the reply is hopped
  back via `server.execute(...)` and guarded against the player having
  disconnected or relogged mid-request.
- Tools run on the server thread through NeoForge's task executor so they
  can touch the world directly, with a timeout so a stopping server can't
  strand a pending request.
- LangChain4j is bundled into the jar via NeoForge Jar-in-Jar (~3.1 MB) so
  the mod has no external runtime dependency beyond the game itself.

## Getting started

**Prerequisites:** Java 21 (the version Minecraft 1.21.1 itself ships to
players).

```bash
./gradlew build          # Windows: .\gradlew.bat build
```

This produces `build/libs/mineai-1.0.0.jar`. Install it in a NeoForge
1.21.1 instance, launch once so the config file is generated, close
Minecraft, then edit `config/mineai-common.toml` (or use the in-game
**Mods → MineAI → Config** screen). Pick one provider and fill in its key
and model — the other providers' settings are ignored:

```toml
# Claude (default)
provider = "anthropic"
anthropicApiKey = "your-api-key"
anthropicModel = "claude-opus-5"
```

```toml
# OpenAI
provider = "openai"
openaiApiKey = "your-api-key"
openaiModel = "gpt-4.1-mini"
```

```toml
# Claude with tool calling, via LangChain4j
provider = "anthropic-lc4j"
anthropicApiKey = "your-api-key"
anthropicModel = "claude-opus-5"
```

```toml
# Google Gemini, with tool calling
provider = "google"
googleApiKey = "your-api-key"
googleModel = "gemini-3.8-flash"
```

Restart Minecraft, then use `/ai your question` in a world.

For multiplayer, install and configure the mod on the server only — any
player can then use `/ai`, and every request draws on the server's own API
key and quota. Keep `mineai-common.toml` private; don't share it or commit
it with a real key in it.

## Configuration reference

| Key | Default | Notes |
|---|---|---|
| `provider` | `anthropic` | One of `anthropic`, `openai`, `anthropic-lc4j`, `google`. |
| `anthropicApiKey` | *(empty)* | Shared by `anthropic` and `anthropic-lc4j`. |
| `anthropicModel` | `claude-opus-5` | `claude-haiku-4-5` is the cheaper, faster option. |
| `anthropicWorkspaceId` | *(empty)* | Optional, for keys not already scoped to a workspace. |
| `openaiApiKey` | *(empty)* | |
| `openaiModel` | `gpt-4.1-mini` | |
| `googleApiKey` | *(empty)* | A Google AI Studio key ([aistudio.google.com/apikey](https://aistudio.google.com/apikey)). |
| `googleModel` | `gemini-3.8-flash` | `gemini-3.5-flash-lite` is the cheaper option. |

## Development commands

| Command | What it does |
|---|---|
| `./gradlew build` | Compile, run unit tests, verify the embedded LangChain4j jars, and produce the mod jar. |
| `./gradlew test` | JUnit unit tests only — no Minecraft launched. |
| `./gradlew runClient` | Launch a dev client (working directory `run/`). |
| `./gradlew runServer` | Launch a dev dedicated server. |
| `./gradlew runData` | Run data generators into `src/generated/resources`. |
| `./gradlew runGameTestServer` | Run gametests headless, then exit. |
| `./gradlew checkJarJar` | Verify the jar's embedded dependency list matches what LangChain4j actually needs at runtime. |

On Windows, use `.\gradlew.bat`. Run a single test with
`./gradlew test --tests '*AnthropicClientTest.marksTruncatedAnswers'`.

## Roadmap

- **Memory** — persisting context across `/ai` calls instead of treating
  every prompt as independent.
- **RAG** — retrieval-augmented answers grounded in world/player data beyond
  what a single tool call returns.
- More tools, and broader provider/tool-calling support as LangChain4j and
  the underlying provider APIs mature.

## License

MineAI's own code is licensed under the [MIT License](LICENSE). The
original NeoForge MDK template files this project was scaffolded from remain
under their own license — see [TEMPLATE_LICENSE.txt](TEMPLATE_LICENSE.txt).

## Author

Built by [Nikita Ovramenko](https://github.com/NikitaaOvramenko).
