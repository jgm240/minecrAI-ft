# minecrAI-ft

Fabric mod (Minecraft 1.21.4, Java 21) that adds fake players driven by an AI model (Claude).

## Setup
1. Build (`./gradlew build`) and put `build/libs/minecraift-*.jar` plus Fabric API in `mods/`.
2. Start once to generate `config/minecraift.json`, then set `api_key` (or export `ANTHROPIC_API_KEY`).
3. `/ai reload` after editing the config.

## Commands (op level 2)
| Command | |
|---|---|
| `/ai spawn <name>` | Spawn an AI player at your position |
| `/ai remove <name>` | Remove it |
| `/ai persona <name> <text>` | Set its personality |
| `/ai list` | List bots |
| `/ai reload` | Reload config |

## Behaviour
- Bots hear chat within `chat_radius` blocks. Say a bot's name to address it; otherwise the nearest bot answers.
- Replies come from the model as JSON and can include actions: **follow** a player, **stay**, or **wander**.
- Bots never run commands; they only chat and move. Per-bot cooldown is `cooldown_seconds`.
- Each reply is one API call (default model `claude-haiku-4-5-20251001`), so mind your usage on busy servers.
