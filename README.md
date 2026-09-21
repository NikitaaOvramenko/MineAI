MineAi setup
=======

Build with `./gradlew build` (Windows: `.\gradlew.bat build`) and install
`build/libs/mineai-1.0.0.jar` in your Minecraft 1.21.1 NeoForge instance.
Launch once, close Minecraft, then edit `config/mineai-common.toml` in the
instance folder. Pick a provider and fill in the key and model for that one;
the other provider's settings are ignored.

OpenAI:

```toml
provider = "openai"
openaiApiKey = "your-api-key"
openaiModel = "gpt-4.1-mini"
```

Claude (the default):

```toml
provider = "anthropic"
anthropicApiKey = "your-api-key"
anthropicModel = "claude-opus-5"
```

`claude-opus-5` is the default and thinks before it answers, which costs more
tokens and takes longer than a plain chat model; `claude-haiku-4-5` is the
cheap, fast option.

Restart Minecraft and use `/ai your question` in a world. Each command sends
an independent prompt straight to OpenAI's Responses API or Anthropic's
Messages API asynchronously; there is no conversation history or separate
backend. Replies are private to the requesting player. Only one request per
player can be pending at a time.

For multiplayer, install and configure the mod on the server. All players can
use `/ai`, and requests use the server's key. Keep the config file private;
do not include it when sharing your mod. Requests use your own API quota.

Installation information
=======

This template repository can be directly cloned to get you started with a new
mod. Simply create a new repository cloned from this one, by following the
instructions provided by [GitHub](https://docs.github.com/en/repositories/creating-and-managing-repositories/creating-a-repository-from-a-template).

Once you have your clone, simply open the repository in the IDE of your choice. The usual recommendation for an IDE is either IntelliJ IDEA or Eclipse.

If at any point you are missing libraries in your IDE, or you've run into problems you can
run `gradlew --refresh-dependencies` to refresh the local cache. `gradlew clean` to reset everything 
{this does not affect your code} and then start the process again.

Mapping Names:
============
By default, the MDK is configured to use the official mapping names from Mojang for methods and fields 
in the Minecraft codebase. These names are covered by a specific license. All modders should be aware of this
license. For the latest license text, refer to the mapping file itself, or the reference copy here:
https://github.com/NeoForged/NeoForm/blob/main/Mojang.md

Additional Resources: 
==========
Community Documentation: https://docs.neoforged.net/  
NeoForged Discord: https://discord.neoforged.net/
