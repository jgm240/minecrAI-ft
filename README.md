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

### Specialised fast model: `minecraift-bot` (recommended with Ollama)
A stock model has to read the whole action schema and survival guide on every request. The Modelfile bakes that in once, so each
request only carries the bot's name, personality, taught notes and current state. That means shorter prompts and faster replies.

**1. Install Ollama and start it** (skip if it's already running)
```
brew install ollama          # or download it from https://ollama.com
ollama serve                 # leave this running, or use the Ollama app / `brew services start ollama`
```

**2. Pull the base model** (about 2 GB, once)
```
ollama pull llama3.2
```

**3. Build `minecraift-bot` from the Modelfile**

Use the `minecraift.Modelfile` in this repo, or generate one that always matches your installed mod version from in game:
```
/ai modelfile                # writes config/minecraift.Modelfile  (or: /ai modelfile qwen2.5:7b for a different base)
```
then, from the folder containing the file:
```
ollama create minecraift-bot -f minecraift.Modelfile
ollama list                  # minecraift-bot should appear
```

**4. Check that it answers** (optional)
```
ollama run minecraift-bot
>>> Your state: holding nothing, inventory empty, a tree 3 blocks away. A player says: get some wood
```
It should reply with a single JSON object containing `say` and `actions` (e.g. a `gather` of logs).

**5. Point the mod at it**

In `config/minecraift.json`:
```json
{
  "provider": "ollama",
  "ollama_url": "http://localhost:11434",
  "ollama_model": "minecraift-bot"
}
```
then run `/ai reload` and `/ai spawn Bob`. Slim prompts switch on automatically for any model whose name starts with `minecraift`
(or set `"ollama_slim_prompt": true` for a differently-named one).

**Why it's more than a long prompt**
- The Modelfile includes seven worked examples (build a portal, wooden pickaxe, stone pickaxe, follow, chat, hunt food) in the exact
  format the mod sends, so even a 3B model produces valid plans.
- With a specialised model the mod sends **no system message** (Ollama would let it replace the baked-in one) and puts the bot's name,
  personality and notes at the top of the user message instead.
- The mod adds short situation hints to each request ("you carry the 10 obsidian and flint and steel a portal needs, so `portal` does
  it in one step"), which help small models pick the right skill.

**Tips**
- **Rebuild after updating the mod.** The action list lives inside the model, so when you update the mod run `/ai modelfile` and
  `ollama create minecraift-bot -f ...` again, or the model won't know about new actions.
- **Bigger base, smarter plans.** `FROM llama3.2` (3B) is fast; `qwen2.5:7b` or `llama3.1:8b` follow multi-step plans better but are
  slower. Change the `FROM` line (or run `/ai modelfile <base>`) and re-run `ollama create`.
- **Keep it loaded.** The mod asks Ollama to keep the model in memory (`ollama_keep_alive`, default `30m`), so only the first reply is slow.
- **Ollama on another machine?** Set `ollama_url` to it; it just needs to be reachable from the Minecraft server.
- **Portals:** bots can walk through a lit portal with `enter_portal` (and back again). They step out of the portal they arrived through
  first, as vanilla requires.
- **Not working?** With `"debug": true` the server log shows what bots hear and plan. "AI request failed" with a hint means Ollama
  isn't reachable or the model name doesn't match `ollama list`.

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
zombies, any hostile), `portal` (builds and lights a Nether portal from 10 obsidian + flint and steel), `enter_portal` (walks through it), `collect`, `eat`.

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
