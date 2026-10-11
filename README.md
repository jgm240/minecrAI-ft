# minecrAI-ft

Fabric mod (Minecraft 1.21.4, Java 21) that adds **real fake players** driven by an AI model (local Ollama or Claude).

Since 2.0.0 the bots are genuine server-side players (`ServerPlayerEntity`) with a fake network connection, not custom mobs:
they show a join/leave message, appear in the player list and `/list`, can be `/msg`'d, `/tp`'d, `/ban`'d and killed, and
have an inventory and playerdata like anyone else. Clients don't need the mod installed.

## Setup
1. Build (`./gradlew build`) and put `build/libs/minecraift-*.jar` plus Fabric API in `mods/`.
2. Start once to generate `config/minecraift.json`. Edit it, then `/ai reload`.

### Free & local: Ollama (default)
```
ollama pull llama3.2     # any chat model works; bigger = smarter
ollama serve             # if it isn't already running
```
Config: `"provider": "ollama"`, `"ollama_url": "http://localhost:11434"`, `"ollama_model": "llama3.2"`.
Ollama must run on the machine hosting the Minecraft server (or point `ollama_url` elsewhere).

### Claude API
Set `"provider": "anthropic"` and `api_key` (or the `ANTHROPIC_API_KEY` environment variable). Costs API credits.

## Commands (op level 2)
| Command | |
|---|---|
| `/ai spawn <name>` | Spawn an AI player at your position |
| `/ai remove <name>` | Remove it |
| `/ai persona <name> <text>` | Set its personality |
| `/ai list` | List bots |
| `/ai reload` | Reload config |

## Behaviour
- `/ai spawn <name>` makes the bot "join the game". Names are 1-16 letters, digits or underscores. Bots use offline-style UUIDs,
  so pick names that no real player uses.
- Bots receive chat like players do. They answer when **named**, when **/msg'd** (they reply by /msg), or when they are the nearest bot
  to a speaker within `chat_radius`. Bots never answer other bots.
- The model replies in JSON and can **follow** a player, **stay**, or **wander**. Movement is simulated key presses, so bots walk,
  sprint, jump over obstacles and swim, but don't mine, build or fight.
- Bots never run commands themselves (the model's output is only ever chat).
- The roster (and personas) is saved in the world folder (`minecraift_bots.json`); bots rejoin on server start. A banned bot is not restored
  (`/pardon` it first). If a bot dies it leaves and rejoins a few seconds later.
- Each reply is one model call. With Ollama it's free but uses your CPU/GPU; small models sometimes ignore the JSON format, in which case
  their raw text is used as speech.
