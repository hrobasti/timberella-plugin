# Timberella 🌲

Drop-in quality-of-life plugin for Paper 26.1.x servers: instant tree felling, tidy leaf cleanup, and automatic replanting with safety rails so mega-builds stay intact.

![Timberella demo](img/timber_01.gif)

## Why server owners love it

- ⚡ One axe swing can clear an entire tree while damage, particles, and timing stay configurable.
- 🌱 Optional replant + soil checks keep forests alive.
- 🛡️ Species-aware limits (per-tree caps, radii, durability rules) stop griefing before it starts.
- 🔁 Live config + locale reloads merge new defaults automatically—no manual diffing.
- 🔔 Update checker (Modrinth + Hangar) and join reminders keep your fleet current.
- 🌐 MiniMessage-powered localization lets you style player + console feedback exactly the way you like.

## Server requirements

- Paper 26.1, 26.1.1, or 26.1.2 (Spigot/vanilla forks are not supported).
- Java 25 runtime.
- Optional: a permissions plugin (LuckPerms, etc.) for fine-grained access to `/timberella` commands.

## Setup in 3 steps

1. Copy the latest `timberella-paper-<version>.jar` into `plugins/`.
2. Boot the server once—`config.yml`, `lang/`, and `leaf_mappings.yml` appear automatically.
3. Tweak `plugins/Timberella/config.yml` (modules, safety caps, labels) and run `/timberella reload`.

That’s it. The async watcher keeps configs + locales synced, and every reload revalidates permissions, caches, and message bundles.

## Everyday tips

- Use `sneak-mode` to decide when timbering should trigger (only sneaking, only not sneaking, or always).
- Flip specific logs/woods/fences on or off in `categories.*`. Unknown materials are ignored safely.
- Watch the console after reloads: Timberella prints how many saplings, soils, and species are active plus any typos it auto-fixed.
- Update notifications surface in console and (optionally) to ops with `timberella.update.notify`; set `update-check.notify-console-always-shown` true if you still want “no update” provider summaries every cycle, and leave `update-check.filter-by-server-version` true so Modrinth/Hangar matches your server build.

## Supported languages

Timberella bundles each locale as a MiniMessage YAML file so you can recolor or restyle them freely:

- 🇺🇸 English (en_US)
- 🇩🇪 German (de_DE)
- 🇸🇦 Arabic (ar_SA)
- 🇪🇸 Spanish (es_ES)
- 🇫🇷 French (fr_FR)
- 🇮🇹 Italian (it_IT)
- 🇯🇵 Japanese (ja_JP)
- 🇰🇷 Korean (ko_KR)
- 🇳🇱 Dutch (nl_NL)
- 🇵🇱 Polish (pl_PL)
- 🇵🇹 Portuguese (pt_PT)
- 🇹🇷 Turkish (tr_TR)
- 🇺🇦 Ukrainian (uk_UA)
- 🇨🇳 Simplified Chinese (zh_CN)

Missing keys fall back to English, and `/timberella reload` hot-reloads both config + language edits.

## Need deeper guidance?

This README keeps things short on purpose. All developer and deep-dive documentation (config matrices, command charts, operations guides) lives in the project wiki. Start there whenever you need advanced workflows or contribution notes.

## License & Credits

- Timberella is released under the Apache License 2.0 (see `LICENSE`).
- Third-party components such as MiniMessage (MIT), Gson (Apache 2.0), Shadow (Apache 2.0), and bStats (MIT) ship with their respective notices inside `THIRD_PARTY_LICENSES.md` and the packaged `licenses/` folder.
- Parts of this plugin and documentation were produced with AI assistance (e.g., GitHub Copilot) and reviewed by the maintainer before release.
