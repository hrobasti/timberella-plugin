# Timberella 🌲

Drop-in quality-of-life plugin for Paper 26.3.x servers: instant tree felling, tidy leaf cleanup, and automatic replanting with safety rails so mega-builds stay intact.

![Timberella demo](img/timber_01.gif)

## Why server owners love it

- ⚡ One axe swing can clear an entire tree while axe wear, block limits and timing stay configurable.
- 🌱 Optional replant + soil checks keep forests alive, underwater too with [UnderwaterTrees](https://github.com/hrobasti/underwatertrees-plugin).
- 🛡️ Species-aware limits (per-tree caps, radii, durability rules) stop griefing before it starts.
- 🏠 Builds stay safe: only logs of the hit tree's species with naturally grown foliage are felled, log walls and huts touching a tree stay standing, giant mushrooms only in their natural shape, player-placed leaves are never cleared away, and custom blocks from ItemsAdder, MMOItems, MythicCrucible and CraftEngine are left alone.
- 🏰 Respects land protection: every extra log and leaf goes through a regular block-break check and every replanted sapling through a block-place check, so WorldGuard, GriefPrevention & co. can deny them.
- 🔁 Live config + locale reloads merge new defaults automatically—no manual diffing: new settings are added in place with their comments, old-style keys are renamed in place, and the rest of your file stays exactly as you wrote it.
- 🔔 Update checker (Modrinth + Hangar) and join reminders keep your fleet current.
- 🌐 14 bundled languages, all MiniMessage-powered, so you can style player + console feedback exactly the way you like.

## Quick start

Timberella needs Paper 26.3.x and Java 25 (Spigot/vanilla are not supported).

1. Download the latest `timberella-paper-<version>.jar` from [Modrinth](https://modrinth.com/plugin/timberella) or [Hangar](https://hangar.papermc.io/hro_basti/timberella) and copy it into `plugins/`.
2. Boot the server once—`config.yml`, `lang/`, and `leaf_mappings.yml` appear automatically.
3. Tweak `plugins/Timberella/config.yml` (modules, safety caps, labels) and run `/timberella reload`.

Edits to `config.yml` are picked up automatically by the config watcher; after editing `lang/` or `leaf_mappings.yml`, run `/timberella reload`.

## Upgrading from 1.x

Swap the jar and start the server: your old files move to `plugins/Timberella/backup-1.x/`, fresh files are created with every setting you had changed carried over, and the console lists exactly what happened. Two defaults changed (`require_natural_leaves` and `replant.sapling_source`); the [upgrade notes](https://github.com/hrobasti/timberella-plugin/wiki#upgrading-from-1x) explain how to get the 1.x behavior back. Coming from 1.0 or 1.0.1, your files are backed up the same way, but their settings can't be carried over: re-apply your changes from the backup.

## Documentation

- [Wiki: admin guide](https://github.com/hrobasti/timberella-plugin/wiki): every setting, commands and permissions, languages, land protection, troubleshooting.
- [Wiki: developer guide](https://github.com/hrobasti/timberella-plugin/wiki/2-%E2%80%90-Developer-Guide): building from source, tests, releases, contributing.

## License & Credits

- Timberella is released under the MIT License (see `LICENSE`), Copyright (c) 2025-2026 [kroet.net](https://kroet.net). Releases before 2.0.0 were published under the Apache License 2.0; kroet.net additionally makes them available under the MIT License.
- The MIT License covers the code, not the name: forks should use a different plugin name and must not suggest they are the official Timberella.
- Includes TurtleLib by [kroet.net](https://kroet.net) (MIT); its license text ships at `licenses/turtle-lib-MIT.txt` inside the jar.
- Includes bStats Metrics (MIT, Copyright (c) 2021 Bastian Oppermann); its license text ships at `licenses/bstats-MIT.txt`. MiniMessage and Gson are provided by Paper at runtime. All bundled and runtime-provided libraries are listed in `THIRD_PARTY_LICENSES.md`.
- Parts of this plugin and its documentation were produced with AI assistance and reviewed by the maintainer before release.
