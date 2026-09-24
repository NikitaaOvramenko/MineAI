# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

MineAi is a Minecraft **NeoForge 1.21.1** mod (Java 21) that adds a server-side `/ai <prompt>` command
forwarding prompts to an AI provider, plus `/mineai confirm|cancel|undo` for buildings the AI plans.
Mod id `mineai`, package `io.github.nikitaaovramenko.mineai`.

The `provider` config key picks one of these:

| `provider` | Endpoint | Key / model options |
|---|---|---|
| `anthropic` (default) | `POST https://api.anthropic.com/v1/messages` | `anthropicApiKey`, `anthropicModel` |
| `openai` | `POST https://api.openai.com/v1/responses` | `openaiApiKey`, `openaiModel` |
| `anthropic-lc4j` (spike) | the Anthropic endpoint, via LangChain4j 1.20.0 | same as `anthropic` |
| `google` | the Gemini API (Google AI Studio keys), via LangChain4j 1.20.0 | `googleApiKey`, `googleModel` |

Only the LangChain4j providers, `anthropic-lc4j` and `google`, let the model call tools (see
`tools/`). The jar embeds LangChain4j and its runtime dependencies through NeoForge Jar-in-Jar, which
adds about 3.1 MB.

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
./gradlew checkJarJar    # jarJar list vs. LangChain4j's runtime deps (build runs it too)
```

Windows: `.\gradlew.bat <task>`. Single test: `./gradlew test --tests '*AnthropicClientTest.marksTruncatedAnswers'`.
`gradlew --refresh-dependencies` after dependency changes; `gradlew clean` to reset build outputs.

Gradle configuration cache is enabled in `gradle.properties`, so build-script edits force a
reconfigure — expect the first run after touching `build.gradle` to be slower.

## Architecture

The code splits along one deliberate line: **Minecraft-coupled classes vs. the `providers` package,
which imports no Minecraft at all**, so the wire-format parsing is testable with plain JUnit.

Minecraft side:

- **`MineAi.java`** — `@Mod` main class. Registers `/ai` via `RegisterCommandsEvent`, resolves the
  configured `AiProvider`, and holds `pendingPrompts` (one in-flight request per player UUID).
- **`Config.java`** — `ModConfigSpec` registered as `ModConfig.Type.COMMON`, so keys live on the
  server (`config/mineai-common.toml`). `Config.apiKey(provider)` / `Config.model(provider)` map a
  provider to its option, so callers never switch on the provider themselves.
- **`MineAiClient.java`** — `dist = Dist.CLIENT` only; registers the NeoForge config screen. Never
  reference client classes outside this file.
- **`tools/`** — what the model may call. `ToolRegistry.create(...)` lists the tool objects: classes
  with LangChain4j `@Tool` methods, e.g. `WorldTools.getWorldSeed`. `ToolContext` gives them the
  server, the asking player and `canUseCommand(...)`. New tools go here. javac runs with `-parameters`
  so tool parameters reach the model by name instead of `arg0`. `PlacedContainers` tags player-made
  storage with a NeoForge data attachment (registered in `MineAi`'s constructor), since Minecraft
  doesn't record who placed anything: chests, barrels and shulker boxes get the placer's UUID from the
  block place event. Chest minecarts and boats have no place event, so they get the nil UUID when they
  join the level new *and* without a loot table. World generation also adds its mineshaft minecarts as
  new entities, but always with one. Chested donkeys, mules and llamas need no tag: only a tamed animal
  takes a chest, so it has an owner. `ToolContext.origin()` is where the player stood and faced when
  they sent `/ai` (`RequestOrigin`), captured before the model starts thinking.
- **`building/`** — the Minecraft half of AI building. `tools/BuildTools.planBuild` hands the model's
  `Blueprint` to `BuildPlanner`, which compiles it, puts it in a `BuildFrame` in front of the request's
  origin, resolves its blocks with the `/setblock` parser (`BlockResolver`) and diffs it against the
  world. The plan waits in `PendingBuilds` (a particle outline, chat buttons) until the player runs
  `/mineai confirm` (`BuildCommands`). `BuildJobs` then places it over several ticks, and `BuildHistory`
  keeps each player's last five builds for `/mineai undo`. All of it lives in memory.

Provider side, the `providers/` package (**no Minecraft imports — keep it that way**). Only
`AiProvider` and `RequestException` are public; the clients stay package-private:

- **`AiProvider.java`** — the enum of providers. Owns each provider's id, display name, config option
  names, and default model, and dispatches `ask(...)` to the right client. Adding a provider means
  adding a constant here, a client (or a `LangChain4jClient` model), its two `Config` options, the
  `Config` switch arms, and their `en_us.json` labels.
- **`AiClients.java`** — shared `HttpClient`, the system prompt, the truncation note, and
  `httpFailure(...)`, the single place HTTP status codes become player-visible text.
- **`OpenAiClient.java` / `AnthropicClient.java`** — request building plus a package-private static
  `parseResponse(int status, String body)` that the tests drive directly.
- **`LangChain4jClient.java`** — the providers that go through LangChain4j: one `ChatModel` builder
  each (`anthropic(...)` for the spike, `google(...)` for Gemini), plus the tool loop they share:
  `converse(...)` runs tools until the model answers, at most `MAX_TOOL_ROUNDS` times. Tests drive it
  with a scripted `ChatModel`. `failure(...)` turns the library's exceptions into `RequestException`s.
  Gemini's model has no async call in 1.20.0 (`chatAsync` fails with `AsyncNotSupportedException`), so
  `call(...)` falls back to its blocking call on a virtual thread. `GoogleProviderTest` runs the real
  Gemini model against a local stand-in server.
- **`LangChain4jTools.java`** — turns tool objects into `ToolSpecification`s and runs the model's calls
  against them (Gson binds the arguments; a throwing tool becomes an error result for the model). It
  needs only `langchain4j-core`; the main `langchain4j` artifact (`AiServices`) would pull in OpenNLP.
  LangChain4j shows the model a sealed interface as a choice of objects told apart by a `"type"`
  property naming the record; `PolymorphicAdapterFactory` reads and writes that with LangChain4j's own
  naming rules (`PolymorphicTypes`), so a tool's result can be sent back in as an argument.
- **`blueprint/`** — the Minecraft-free half of AI building: the `Blueprint` the model fills in (one
  sealed `BuildOperation` record per step, with `@Description`s as the model's only documentation),
  `BlueprintCompiler` (operations → `TargetGrid` of Place / Remove / keep) and `BuildDiff` (grid plus
  existing blocks plus `BuildMode` → changes, generic over the block-state type). All unit-tested.
- **`RequestException.java`** — a message already safe to show a player.

### Invariants that are easy to break

**Threading.** `HttpClient` completion callbacks run off the server thread. All player/level/server
access must be wrapped in `server.execute(...)`, as `askAi` does. The callback also guards with
`server.isStopped()` and re-looks-up the player by UUID (`getPlayerList().getPlayer(playerId) != player`)
so a disconnect or relog during a request can't leak a message to the wrong player object.

**Tools run on the server thread, through `server::executeIfPossible`.** `LangChain4jTools.executeAll`
submits each turn's calls as one task on that executor, so tool methods can touch the world directly.
Don't use `server::execute` here: once the server has stopped, it runs the task inline on the calling
network thread. A stopping server also drops queued tasks, hence the timeout on the tool stage;
without it the player's `pendingPrompts` entry would never clear.

**A tool must not reveal more than the player could find out themselves.** A tool that does what a
command does checks `ToolContext.canUseCommand(...)`, which asks the live command tree: `/seed` and
`/locate` are operator-only on a dedicated server, and `/locate` needs cheats in single-player.
`StorageTools` has no command to mirror, so it only reads player-made storage (see `PlacedContainers`)
near the player that is already loaded, and never reads an unopened loot chest, because that would
generate its loot. Storage placed before the tagging existed is invisible to it.

**AI building changes the world only through `BuildJobs`, after the player confirms.** Planning,
confirming and undoing all need `/fill` rights (`ToolContext.canUseCommand(source, "fill")`), and a
blueprint may cover at most the `commandModificationBlockLimit` game rule's blocks, like `/fill`. Other
rules:
- `BuildPlanner.requireInWorld` must pass before a block of the area is read, so no chunk is loaded.
- The diff is taken when the plan is made. Building skips every block that changed since, and undo skips
  every block that changed since the build, so neither overwrites what someone did in between.
- ADD mode never replaces a block that isn't replaceable. REPLACE never touches block entities,
  unbreakable blocks, or half of a door or bed.
- Blocks are set with `UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE`, and a final pass gives the neighbour and
  shape updates, as `/fill` and structure placement do. Updating while placing would let half a door
  pop off and drop.
- A build posts the break and place events a player's hand would. That lets claim mods refuse, and it is
  how `PlacedContainers` learns about the chests a build places.

Blueprint directions (block `[properties]`, stair and door facings) call forward "north" and right
"east". `BuildFrame.rotation()` turns them to the world, per block, with NeoForge's position-aware
`rotate`. Renaming a `BuildOperation` record or field changes what the model must send.

**The jarJar list in `build.gradle` must match LangChain4j's runtime dependencies.** ModDevGradle's
jarJar isn't transitive, so every embedded jar is listed by hand. The dev run and the tests get
LangChain4j from the `lc4j` configuration, never from the jar, so a missing jar would only show up as a
`NoClassDefFoundError` in a real install. `checkJarJar` (run by `build`) fails when the list and `lc4j`
drift apart, e.g. after a LangChain4j bump. Libraries Minecraft already ships, like slf4j, are excluded
from `lc4j` instead of embedded.

**Don't turn on `returnThinking` for Anthropic in `LangChain4jClient`.** Replayed tool-call turns go
back without their thinking blocks, which adaptive-thinking models accept. LangChain4j 1.20.0 can't
replay them correctly anyway: its Anthropic mapper drops empty (omitted-display) thinking blocks and
joins several blocks' signatures into one, and the API rejects modified blocks.

**Gemini is the opposite: keep `returnThinking` and `sendThinking` on for `google`.** Gemini puts a
thought signature on its function calls, and newer models reject a follow-up request that doesn't send
it back unchanged. Those two options are what keep and resend it; neither asks for thought text.

**Error messages must never contain the API response body.** `AiClients.httpFailure` maps status codes
to fixed strings; raw bodies can echo back credentials or request details. Both
`errorsDoNotExposeResponseBodies` tests assert this — don't "improve" errors by appending the body.
The LangChain4j providers go through it too: `LangChain4jClient.failure` finds the library's
`HttpException` among the causes. Google answers a bad key with 400 `API_KEY_INVALID` rather than 401,
which `httpFailure` recognizes from the body without showing it.

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
- LangChain4j providers: the library parses the response, and `converse` explains blank text by the
  finish reason in the same way (`CONTENT_FILTER` is a decline, `LENGTH` adds the truncation note).

Empty or unparseable output raises `RequestException` rather than returning a blank chat line.

`AnthropicClient` uses a larger `MAX_OUTPUT_TOKENS` (4000 vs. OpenAI's 800) and a longer timeout
(90s vs. 60s) because current Claude models think adaptively by default and that thinking shares the
output budget and the wall clock with the reply. `LangChain4jClient` goes further (16000 tokens, 180s),
because one of its replies can be a whole blueprint.

Gson and slf4j are supplied by Minecraft at runtime; plain unit tests get their own copies via the
`testRuntimeOnly` declarations in `build.gradle`. LangChain4j is the only provider library, bundled as
above; otherwise there is no provider SDK dependency by design, since each would need the same
bundling and risks classloader conflicts with Minecraft's own libraries.

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
