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
| `/ai spawn <name>` | Make a bot join the game at your position (console: world spawn) |
| `/ai remove <name>` | Make it leave |
| `/ai persona <name> <text>` | Set its personality (saved) |
| `/ai say <name> <text>` | Make it say something in chat |
| `/ai do <name> <json>` | Run one action by hand, e.g. `{"type":"mine","x":1,"y":64,"z":2}` |
| `/ai list` / `/ai reload` | List bots / reload config |

## What bots can do
Bots are real players with real inventories, so they do things the normal way (reach, mining time, tool speed, spawn protection and
gamemode rules all apply). The model can chain up to 8 of these per reply:

`goto`, `mine`, `place`, `use_block` (chests, doors, buttons), `use_item` (eat, throw), `select`, `drop`, `attack`, `follow`, `stay`,
`wander`, and `command`.

Before each reply the model is shown a snapshot: position, health/food, held item, inventory, the block it is looking at, the nearest
block of each type within 6 blocks (with coordinates), and nearby mobs/items/players.

### Safety rules
- **Commands:** a bot may run normal commands, but never the ones in `blocked_commands` (stop, kill, ban, op, fill, setblock, clone,
  execute, gamerule, summon, ... see `config/minecraift.json`). The check ignores a leading `/` and `minecraft:` and looks at the first word.
  Bots are also not operators unless you `/op` them, so vanilla permissions apply on top.
- **PvP:** bots can fight mobs but won't attack players unless `"allow_pvp": true`.
- **Chat is never a command:** a reply starting with `/` has the slash stripped.
- **Limits not covered yet:** bots can't craft, smelt, or use inventory GUIs beyond clicking blocks; there's no pathfinding, so they walk
  in straight lines and jump over small obstacles.

## Behaviour
- `/ai spawn <name>` makes the bot "join the game". Names are 1-16 letters, digits or underscores. Bots use offline-style UUIDs,
  so pick names that no real player uses.
- Bots receive chat like players do. They answer when **named**, when **/msg'd** (they reply by /msg), or when they are the nearest bot
  to a speaker within `chat_radius`. Bots never answer other bots.
- The roster (and personas) is saved in the world folder (`minecraift_bots.json`); bots rejoin on server start. A banned bot is not
  restored (`/pardon` it first). If a bot dies it leaves and rejoins a few seconds later.
- Each reply is one model call. With Ollama it's free but uses your CPU/GPU; small models sometimes ignore the JSON format, in which case
  their raw text is used as speech.
- Set `"debug": true` to log what bots hear and plan.
