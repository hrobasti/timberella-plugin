# Timberella 🌲

Timberella is a Paper 1.21.x plugin that packages tree felling, leaf decay, and replanting into cleanly separated modules. You can toggle each feature individually and even define custom log-to-leaf mappings so species never overlap.

## ✨ Highlights
- Configurable sneak mode: only fell trees when sneaking, when not sneaking or always 
- Sequential log breaking with particle feedback and configurable durability cost.
- Independent modules for timber, species-aware leaves decay that skips neighboring crowns, and soil-aware replanting (incl. waterlogged mangroves while muddy roots stay intact).
- Species-specific safety limits keep huge crowns (jungle, mangrove, mushrooms, etc.) under control without nerfing other trees.
- Config watcher merges defaults on the fly; localized messages.
- Update checker covers Modrinth & Hangar for gentle upgrade nudges.
- Customizable `leaf_mappings.yml` keeps leaf pruning scoped to the species you define.
- Languages: en_US, de_DE / Machine translated: ar_SA, es_ES, fr_FR, it_IT, ja_JP, ko_KR, nl_NL, pl_PL, pt_PT, tr_TR, uk_UA, zh_CN

## 🚀 Quick Start
1. Drop the release JAR into `plugins/`.
2. Start Paper once to generate `config.yml` + `lang/` files.
3. Adjust `plugins/Timberella/config.yml` and run `/timberella reload`.

> ℹ️ Detailed usage guides, permissions, and command matrices live in the internal wiki (kept outside of this repo/JAR).

## ⚙️ Default Config
<details>
<summary>config.yml</summary>

```yaml
#################################################
# General
#################################################

# language: messages locale file to load from /lang (default: en_US)
language: en_US

# Update check options
check_updates: true

# 0 = Modrinth + Hangar, 1 = only Modrinth, 2 = only Hangar
update_provider: 0

# Interval (in hours) for automatic update checks (minimum 1)
update_check_interval_hours: 24

# Notify about pre-release builds (beta/alpha). Requires support from provider APIs.
update_include_prereleases: false

# Config watcher toggle (auto-reload & merge every ~5s if enabled)
config_watch_enabled: true

# Enable anonymous usage metrics via bStats (https://bstats.org)
metrics_enabled: true

#################################################
# Modules
#################################################

# Enable timber ability
enable_timber: true

# Enable replanting of saplings after tree felling
enable_replant: true

# Enable leaves decay after tree felling
# if true, leaves around felled logs will start decaying, netherless of enable_timber and enable_replant
# doesn't require timber permission to function - it's a global setting
enable_leaves_decay: true

#################################################
# Timber settings
#################################################

# When should trees be felled automatically?
# 0 = only when sneaking
# 1 = only when NOT sneaking
# 2 = sneaking or not (always)
sneak_mode: 0

# Tree connection detection:
# true = consider diagonal neighbors, false = only 6 orthogonal faces
include_diagonals: true

# Interval (in ticks) between breaking subsequent blocks when felling sequentially
break_interval_ticks: 2

tools:
  # Which axes are allowed to trigger tree felling

  allowed_axes:
    - STONE_AXE
    - IRON_AXE
    - GOLDEN_AXE
    - DIAMOND_AXE
    - NETHERITE_AXE
  
  # Minimum remaining durability required before felling starts (protection)
  min_remaining_durability: 10

  # Durability mode: "first" counts only the first block / "all" counts all broken blocks
  durability_mode: all

  # Multiplier for durability cost when mode is "all" (total_cost = round(broken_blocks * multiplier))
  durability_multiplier: 0.5

# Maximum number of log blocks to break in one go (safety)
max_blocks: 1024

# Species-specific safety limits (radius = Chebyshev X/Z distance; vertical = |Y| offset)
species_limits:
  mangrove:
    enabled: true
    max_blocks: 128
    max_horizontal_radius: 9
    max_vertical_radius: 32
  jungle:
    enabled: true
    max_horizontal_radius: 8
    max_vertical_radius: 32
  spruce:
    enabled: true
    max_horizontal_radius: 5
    max_vertical_radius: 32
  oak:
    enabled: true
    max_horizontal_radius: 6
    max_vertical_radius: 24
  pale_oak:
    enabled: true
    max_horizontal_radius: 5
    max_vertical_radius: 16
  dark_oak:
    enabled: true
    max_horizontal_radius: 6
    max_vertical_radius: 12
  birch:
    enabled: true
    max_horizontal_radius: 2
    max_vertical_radius: 12
  acacia:
    enabled: true
    max_horizontal_radius: 8
    max_vertical_radius: 12
  cherry:
    enabled: true
    max_horizontal_radius: 9
    max_vertical_radius: 12
  mushroom_brown:
    enabled: true
    max_horizontal_radius: 4
    max_vertical_radius: 12
  mushroom_red:
    enabled: true
    max_horizontal_radius: 2
    max_vertical_radius: 12
  warped:
    enabled: true
    max_horizontal_radius: 6
    max_vertical_radius: 32
  crimson:
    enabled: true
    max_horizontal_radius: 6
    max_vertical_radius: 32

# Categories with per-material toggles (set true/false). Unknown materials are ignored safely.
categories:
  logs:
    OAK_LOG: true
    PALE_OAK_LOG: true
    SPRUCE_LOG: true
    BIRCH_LOG: true
    JUNGLE_LOG: true
    ACACIA_LOG: true
    DARK_OAK_LOG: true
    MANGROVE_LOG: true
    MANGROVE_ROOTS: true
    CHERRY_LOG: true
    CRIMSON_STEM: true
    WARPED_STEM: true
    MUSHROOM_STEM: true
    BROWN_MUSHROOM_BLOCK: true
    RED_MUSHROOM_BLOCK: true
  stripped_logs:
    STRIPPED_OAK_LOG: false
    STRIPPED_PALE_OAK_LOG: false
    STRIPPED_SPRUCE_LOG: false
    STRIPPED_BIRCH_LOG: false
    STRIPPED_JUNGLE_LOG: false
    STRIPPED_ACACIA_LOG: false
    STRIPPED_DARK_OAK_LOG: false
    STRIPPED_MANGROVE_LOG: false
    STRIPPED_CHERRY_LOG: false
    STRIPPED_CRIMSON_STEM: false
    STRIPPED_WARPED_STEM: false
  woods:
    OAK_WOOD: false
    PALE_OAK_WOOD: false
    SPRUCE_WOOD: false
    BIRCH_WOOD: false
    JUNGLE_WOOD: false
    ACACIA_WOOD: false
    DARK_OAK_WOOD: false
    MANGROVE_WOOD: false
    CHERRY_WOOD: false
  stripped_woods:
    STRIPPED_OAK_WOOD: false
    STRIPPED_PALE_OAK_WOOD: false
    STRIPPED_SPRUCE_WOOD: false
    STRIPPED_BIRCH_WOOD: false
    STRIPPED_JUNGLE_WOOD: false
    STRIPPED_ACACIA_WOOD: false
    STRIPPED_DARK_OAK_WOOD: false
    STRIPPED_MANGROVE_WOOD: false
    STRIPPED_CHERRY_WOOD: false
  fences:
    OAK_FENCE: false
    SPRUCE_FENCE: false
    BIRCH_FENCE: false
    JUNGLE_FENCE: false
    ACACIA_FENCE: false
    DARK_OAK_FENCE: false
    MANGROVE_FENCE: false
    CHERRY_FENCE: false
    CRIMSON_FENCE: false
    WARPED_FENCE: false
  additions:
    BEE_NEST: true
    BEEHIVE: true
    CREAKING_HEART: true

#################################################
# Replant settings
#################################################

replant:

  # Enable replanting of saplings after tree felling
  enabled: true

  # List of saplings to replant (must match item IDs)
  saplings:
    - OAK_SAPLING
    - PALE_OAK_SAPLING
    - SPRUCE_SAPLING
    - BIRCH_SAPLING
    - JUNGLE_SAPLING
    - ACACIA_SAPLING
    - DARK_OAK_SAPLING
    - CHERRY_SAPLING
    - MANGROVE_PROPAGULE

#################################################
# Leaves decay settings
#################################################

leaves_decay:
  # Search radius around felled logs used to discover candidate leaves (flood-fill depth)
  # Minimum accepted value: 0 (values below are clamped)
  decay_radius: 5

  # Hard safety buffer: leaves farther than this (3D distance) from every felled log stay untouched
  # Minimum accepted value: 1
  max_distance: 4

  # Interval (in ticks) between leaf removal batches
  # Minimum accepted value: 1 tick
  batch_interval_ticks: 2

  # Maximum number of leaves removed per batch
  # Minimum accepted value: 1
  batch_size: 20
</details>

## 🌐 Species Safety Limits

Use the `species_limits` section in `config.yml` to tune block caps and search radii per tree family. Each entry provides:

- `max_blocks`: optional override for how many logs/related blocks Timberella will break for that species (omit or set `-1` to fall back to the global `max_blocks`).
- `max_horizontal_radius`: Chebyshev distance on the X/Z plane around the first log; keeps sprawling crowns local without affecting vertical reach.
- `max_vertical_radius`: absolute Y distance above/below the first log; perfect for limiting flat species (birch, dark oak) while letting tall fungi stretch upward.

Adjust the defaults to match your server’s biome generation. If a species is disabled (`enabled: false`), Timberella reverts to the global safety cap for that family.

<details>
<summary>leaf_mappings.yml</summary>

```yaml
# Maps log (or related wood) materials to the leaf blocks that belong to the same tree species.
# You can add or remove entries as needed; Timberella will only decay leaves listed for the
# logs that were actually felled.
log_to_leaves:
	OAK_LOG:
		- OAK_LEAVES
	STRIPPED_OAK_LOG:
		- OAK_LEAVES
	OAK_WOOD:
		- OAK_LEAVES
	STRIPPED_OAK_WOOD:
		- OAK_LEAVES

	SPRUCE_LOG:
		- SPRUCE_LEAVES
	STRIPPED_SPRUCE_LOG:
		- SPRUCE_LEAVES
	SPRUCE_WOOD:
		- SPRUCE_LEAVES
	STRIPPED_SPRUCE_WOOD:
		- SPRUCE_LEAVES

	BIRCH_LOG:
		- BIRCH_LEAVES
	STRIPPED_BIRCH_LOG:
		- BIRCH_LEAVES
	BIRCH_WOOD:
		- BIRCH_LEAVES
	STRIPPED_BIRCH_WOOD:
		- BIRCH_LEAVES

	JUNGLE_LOG:
		- JUNGLE_LEAVES
	STRIPPED_JUNGLE_LOG:
		- JUNGLE_LEAVES
	JUNGLE_WOOD:
		- JUNGLE_LEAVES
	STRIPPED_JUNGLE_WOOD:
		- JUNGLE_LEAVES

	ACACIA_LOG:
		- ACACIA_LEAVES
	STRIPPED_ACACIA_LOG:
		- ACACIA_LEAVES
	ACACIA_WOOD:
		- ACACIA_LEAVES
	STRIPPED_ACACIA_WOOD:
		- ACACIA_LEAVES

	DARK_OAK_LOG:
		- DARK_OAK_LEAVES
	STRIPPED_DARK_OAK_LOG:
		- DARK_OAK_LEAVES
	DARK_OAK_WOOD:
		- DARK_OAK_LEAVES
	STRIPPED_DARK_OAK_WOOD:
		- DARK_OAK_LEAVES

	CHERRY_LOG:
		- CHERRY_LEAVES
	STRIPPED_CHERRY_LOG:
		- CHERRY_LEAVES
	CHERRY_WOOD:
		- CHERRY_LEAVES
	STRIPPED_CHERRY_WOOD:
		- CHERRY_LEAVES

	MANGROVE_LOG:
		- MANGROVE_LEAVES
	STRIPPED_MANGROVE_LOG:
		- MANGROVE_LEAVES
	MANGROVE_WOOD:
		- MANGROVE_LEAVES
	STRIPPED_MANGROVE_WOOD:
		- MANGROVE_LEAVES

	CRIMSON_STEM:
		- NETHER_WART_BLOCK
	STRIPPED_CRIMSON_STEM:
		- NETHER_WART_BLOCK
	CRIMSON_HYPHAE:
		- NETHER_WART_BLOCK
	STRIPPED_CRIMSON_HYPHAE:
		- NETHER_WART_BLOCK

	WARPED_STEM:
		- WARPED_WART_BLOCK
	STRIPPED_WARPED_STEM:
		- WARPED_WART_BLOCK
	WARPED_HYPHAE:
		- WARPED_WART_BLOCK
	STRIPPED_WARPED_HYPHAE:
		- WARPED_WART_BLOCK
```

</details>

Leaf decay additionally enforces a configurable 3D buffer (`leaves_decay.max_distance`, default 4) from every log block in the felled trunk, so even tightly packed trees of the same species keep their crowns as long as each leaf stays within the configured range of at least one harvested log. Use `leaves_decay.decay_radius` to control how far the flood-fill searches, and rely on `max_distance` as the non-negotiable safety bubble.
All leaf-related values clamp to the documented minimums to avoid destabilizing the scheduler.

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
Timberella itself ships under the Apache License 2.0 (see `LICENSE`). Embedded third-party code inside the shaded JAR:
- `com.github.johnrengelman.shadow` — Apache License 2.0
- `net.kyori:adventure-text-minimessage` — MIT License
- `com.google.code.gson:gson` — Apache License 2.0
- `bStats Metrics` (relocated `Metrics.java`) — MIT License

Full texts live under `licenses/` in the plugin JAR and inside this repository.
