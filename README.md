# minecrAI-ft

Fabric mod (Minecraft 1.21.4, Java 21) that adds fake players driven by an AI model (Claude).

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
- Bots hear chat within `chat_radius` blocks. Say a bot's name to address it; otherwise the nearest bot answers.
- Replies come from the model as JSON and can include actions: **follow** a player, **stay**, or **wander**.
- Bots never run commands; they only chat and move. Per-bot cooldown is `cooldown_seconds`.
- Each reply is one model call. With Ollama it's free but uses your CPU/GPU; small models sometimes ignore the JSON format, in which case their raw text is used as speech.
