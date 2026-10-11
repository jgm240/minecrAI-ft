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
| `/ai train <name\|all> <info>` | Teach a bot a fact or rule; it goes into the bot's prompt and is saved (max 40 notes) |
| `/ai notes <name>` / `/ai forget <name>` | List / clear what it was taught |
| `/ai say <name> <text>` | Make it say something in chat |
| `/ai goal <name> <text>` | Give it a standing goal it keeps working on (e.g. "get an iron pickaxe") |
| `/ai do <name> <json>` | Run one action by hand, e.g. `{"type":"mine","x":1,"y":64,"z":2}` |
| `/ai list` / `/ai reload` | List bots / reload config |

## What bots can do
Bots are real players with real inventories, so they do things the normal way (reach, mining time, tool speed, tool requirements,
spawn protection and gamemode rules all apply). The model can chain up to 8 steps per reply.

**Skills** (high-level, so even small local models can use them):
`gather` (logs, stone, any ore or block: walks there, mines it, picks up the drops), `craft` (works out intermediates itself:
logs -> planks -> sticks -> pickaxe, and places a crafting table it carries or crafts), `smelt` (furnace + fuel), `hunt` (cows,
zombies, any hostile), `portal` (builds and lights a Nether portal from 10 obsidian + flint and steel), `collect`, `eat`.

**Primitives:** `goto` (A* pathfinding: detours around walls, steps up, drops), `mine`, `place`, `use_block`, `use_item`, `select`,
`drop`, `attack`, `follow`, `stay`, `wander`, `command`.

**Goals:** the model can set a standing `goal` ("get an iron pickaxe"); the bot keeps asking for next steps with the outcomes until it
says "done", gets stuck 3 plans in a row, or hits 40 steps. Bots also eat on their own when hungry.

**Knowledge ("training"):** the prompt includes a condensed Minecraft survival guide (tool tiers, progression, food, mobs, Nether
portal recipe, safety). On top of that, `/ai train <bot|all> <info>` adds your own notes; they're saved per bot, survive deaths and
restarts, and override the general guide. This teaches the bot facts and rules; it doesn't change the model's weights.

Before each reply the model is shown a snapshot: position, health/food, held item, inventory, the block it is looking at, the nearest
block of each type within 6 blocks (with coordinates), and nearby mobs/items/players.

### Safety rules
- **Commands:** a bot may run normal commands, but never the ones in `blocked_commands` (stop, kill, ban, op, fill, setblock, clone,
  execute, gamerule, summon, ... see `config/minecraift.json`). The check ignores a leading `/` and `minecraft:` and looks at the first word.
  Bots are also not operators unless you `/op` them, so vanilla permissions apply on top.
- **PvP:** bots can fight mobs but won't attack players unless `"allow_pvp": true`.
- **Chat is never a command:** a reply starting with `/` has the slash stripped.
- **Limits:** no inventory GUIs beyond clicking blocks, no bridging or digging through terrain while pathfinding, no boats/elytra/
  redstone. Pathfinding plans up to about 4000 nodes (~60 blocks); farther targets are approached in stages. Tall trees are mined from
  the ground (reach is about 4.5 blocks).

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
