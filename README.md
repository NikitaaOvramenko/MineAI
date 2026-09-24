# MineAI

[![Build](https://github.com/NikitaaOvramenko/MineAI/actions/workflows/build.yml/badge.svg)](https://github.com/NikitaaOvramenko/MineAI/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
![Minecraft 1.21.1](https://img.shields.io/badge/Minecraft-1.21.1-brightgreen)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.251-orange)
![Java 21](https://img.shields.io/badge/Java-21-blue)

**Talk to Claude from inside Minecraft.** MineAI is a server-side NeoForge
mod that adds a `/ai <prompt>` command: type a request in chat, and an LLM
answers — with the ability to call back into the live game world through
function calling, from checking the seed to planning and building an entire
structure from a description.

> 🚧 **Work in progress.** Core chat, tool calling and the AI building system
> work today; memory and retrieval-augmented context are on the
> [roadmap](#roadmap).

## Demo

**Building a two-floor house from a single prompt**, answered by Claude
Haiku 4.5: the model designs a blueprint, plans it in front of the player as
a previewed outline, and only builds once the player confirms.

<video src="docs/media/demo-build-two-floor-house.webm" controls muted width="100%"></video>

**Finding crafting materials across player storage**: the model is asked for
everything needed to craft a pickaxe, calls the storage tool to search
nearby player-placed chests, and reports back what it found and where.

<video src="docs/media/demo-storage-tool-call.webm" controls muted width="100%"></video>

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
- **Function calling into the running world.** With the LangChain4j-backed
  provider, the model can call tools to look things up, place a block, or
  plan and build an entire structure — see below.

## Tools (function calling)

Only the LangChain4j-routed provider (`anthropic-lc4j`) can call tools. Every
tool that mirrors a vanilla command re-checks that command's own permission
via the live command dispatcher (e.g. a tool wrapping `/locate` is unusable
by a player who couldn't run `/locate` themselves), so the AI never sees or
does more than the player already could.

| Tool | What it lets the AI do |
|---|---|
| `getWorldSeed` | Returns the world seed — gated like `/seed`. |
| `findNearestStructure` | Finds the nearest structure of a given type (village, stronghold, etc.) — gated like `/locate`. |
| `placeBlock` | Places a single block near the player, relative to where they're facing — gated like `/setblock`, and only into empty/replaceable space. |
| `listNearbyContainers` | Lists nearby storage the *player* placed — chests, barrels, shulker boxes, storage minecarts/boats, chested tamed animals — and everything inside, skipping naturally-generated loot chests. |
| `planBuild` | Turns a described building into a blueprint, previews it in front of the player as an outline, and waits for confirmation — nothing is placed yet. |
| `getPendingBuild` | Returns the plan currently waiting for confirmation, so the model can revise it and resubmit. |
| `findBlocks` | Looks up block ids by keyword (including modded blocks), for when the model isn't sure what a block is actually called. |

Storage tools only ever see player-placed containers: a NeoForge block-place
listener tags each chest/barrel/shulker box (and chest minecart/boat) with
its placer's UUID the moment it enters the world, so an unopened dungeon
chest is never exposed to the model and never has its loot generated early.

### AI building: plan, preview, confirm, undo

`planBuild` is deliberately not "the model edits the world directly." Asking
for a build only ever stages a plan — the player sees the outline and a
[Confirm] button in chat, and nothing changes until they run
`/mineai confirm` (building requires the same rights as `/fill`). A finished
build can be taken back with `/mineai undo`, or a staged plan dropped with
`/mineai cancel`. `ADD` mode only fills empty space and never breaks a
block; `REPLACE` mode matches the blueprint exactly, clearing what's in the
way except containers.

## Providers

| Provider | `provider` value | Transport | Function calling |
|---|---|---|---|
| **Anthropic (Claude)** — default | `anthropic` | Direct HTTPS to the Messages API | No |
| OpenAI | `openai` | Direct HTTPS to the Responses API | No |
| Anthropic via LangChain4j | `anthropic-lc4j` | [LangChain4j](https://github.com/langchain4j/langchain4j) | Yes |

Claude is the default and primary provider. The two direct-HTTP clients
(`anthropic`, `openai`) are deliberately minimal and model-agnostic; the
LangChain4j-routed provider runs a tool-calling loop that executes the
model's requested tools and feeds the results back until it answers — this
is what powers both demos above. More providers (and tool calling for all
of them) are planned as the mod grows.

## Architecture

The codebase is split so the wire-format request/response parsing for each
provider is covered by plain JUnit tests, no Minecraft launch required.

- Requests run asynchronously off the server thread; the reply is hopped
  back via `server.execute(...)` and guarded against the player having
  disconnected or relogged mid-request.
- Tools run on the server thread through NeoForge's task executor so they
  can touch the world directly, with a timeout so a stopping server can't
  strand a pending request.
- A build is planned instantly (in memory, as a diff against the world) but
  built in batches over several ticks so placing hundreds of blocks never
  freezes the server, and every placed block is recorded so it can be
  undone later.
- LangChain4j is bundled into the jar via NeoForge Jar-in-Jar so the mod has
  no external runtime dependency beyond the game itself.

## Getting started

**Prerequisites:** Java 21 (the version Minecraft 1.21.1 itself ships to
players).

```bash
./gradlew build          # Windows: .\gradlew.bat build
```

This produces `build/libs/mineai-1.0.0.jar`. Install it in a NeoForge
1.21.1 instance, launch once so the config file is generated, close
Minecraft, then edit `config/mineai-common.toml`. Pick one provider and
fill in its key and model — the other providers' settings are ignored:

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
# Claude with tool calling and AI building, via LangChain4j
provider = "anthropic-lc4j"
anthropicApiKey = "your-api-key"
anthropicModel = "claude-opus-5"
```

Restart Minecraft, then use `/ai your question` in a world.

For multiplayer, install and configure the mod on the server only — any
player can then use `/ai`, and every request draws on the server's own API
key and quota. Keep `mineai-common.toml` private; don't share it or commit
it with a real key in it.

## Configuration reference

| Key | Default | Notes |
|---|---|---|
| `provider` | `anthropic` | One of `anthropic`, `openai`, `anthropic-lc4j`. |
| `anthropicApiKey` | *(empty)* | Shared by `anthropic` and `anthropic-lc4j`. |
| `anthropicModel` | `claude-opus-5` | `claude-haiku-4-5` is the cheaper, faster option — see the build demo above. |
| `anthropicWorkspaceId` | *(empty)* | Optional, for keys not already scoped to a workspace. |
| `openaiApiKey` | *(empty)* | |
| `openaiModel` | `gpt-4.1-mini` | |

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

On Windows, use `.\gradlew.bat`.

## Roadmap

- **Memory** — persisting context across `/ai` calls instead of treating
  every prompt as independent.
- **RAG** — retrieval-augmented answers grounded in world/player data beyond
  what a single tool call returns.
- More tools, and tool-calling support for more providers as LangChain4j and
  the underlying provider APIs mature.

## License

MineAI's own code is licensed under the [MIT License](LICENSE). The
original NeoForge MDK template files this project was scaffolded from remain
under their own license — see [TEMPLATE_LICENSE.txt](TEMPLATE_LICENSE.txt).

## Author

Built by [Nikita Ovramenko](https://github.com/NikitaaOvramenko).
