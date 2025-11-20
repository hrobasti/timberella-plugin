# Timberella 🌲

Paper 1.21.x plugin that fells whole trees, prunes their leaves, and replants saplings with minimal configuration.

## ✨ Highlights
- Sequential log breaking with particle feedback and configurable durability cost.
- Independent modules for timber, species-aware leaves decay that skips neighboring crowns, and soil-aware replanting (incl. waterlogged mangroves).
- Config watcher merges defaults on the fly; localized messages.
- Update checker covers Modrinth & Hangar for gentle upgrade nudges.
- Customizable `leaf_mappings.yml` keeps leaf pruning scoped to the species you define.

## 🚀 Quick Start
1. Drop the release JAR into `plugins/`.
2. Start Paper once to generate `config.yml` + `lang/` files.
3. Adjust `plugins/Timberella/config.yml` and run `/timberella reload`.

> ℹ️ Detailed usage guides, permissions, and command matrices live in the internal wiki (kept outside of this repo/JAR).

## ⚙️ Configuration Snapshot
Key toggles inside `plugins/Timberella/config.yml`:
- `enable_timber`, `enable_leaves_decay`, `enable_replant` (leaves decay respects species mappings even without permissions)
- `include_diagonals`, `break_interval_ticks`, `max_blocks`
- `tools.*` for allowed axes & durability model
- `leaves_decay.*` for radius + batch tuning (instant flood-fill)
- `replant.saplings` whitelist with soil validation
- `metrics_enabled` to opt out of bStats telemetry if desired
- `check_updates`, `update_check_interval_hours`, `update_provider` for the automatic version checker
- `update_include_prereleases` to decide whether beta/alpha builds should trigger notifications

Separated `leaf_mappings.yml` to map any log/wood material to the leaves that should decay with it; unmapped species fall back to vanilla decay ranges.

## 📦 Build Requirements
- JDK 21+
- Git + Gradle Wrapper (already checked in)
- Internet access to `https://repo.papermc.io/`

## 🧱 Build Instructions
```
# wrapper already present, command shown for reference
gradle wrapper --gradle-version 8.10.2

# produce shaded plugin jar
./gradlew clean build
```
Resulting artifacts land in `build/libs/` (shadowed JAR only).

## 🧭 Further Docs
- Internal wiki ➜ rollout, permissions, and support playbooks
- `wiki/build-guide.md` ➜ extended build & release workflow
- `THIRD_PARTY_LICENSES.md` ➜ dependency overview & links

## 📜 Licenses
Embedded third-party code inside the shaded JAR:
- `com.github.johnrengelman.shadow` — Apache License 2.0
- `net.kyori:adventure-text-minimessage` — MIT License
- `com.google.code.gson:gson` — Apache License 2.0
- `bStats Metrics` (relocated `Metrics.java`) — MIT License

Full texts live under `licenses/` in the plugin JAR and inside this repository.
