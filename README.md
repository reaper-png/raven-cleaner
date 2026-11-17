# Raven Cleaner v0.2.1 — NeoForge 1.21.1 / Java 21

**Server-side only**, requires **FTB Chunks 2101+** on the server. Minecraft clients do not install this mod.

## Changes since v0.1.0

- Fixes invalid `TamableAnimal` import for NeoForge 1.21.1.
- Dropped items are **not protected by FTB Chunks claims or nearby players**. Drops from a broken mob farm in a player's own claim can be removed.
- Manual `/cleaner clean items` and `/cleaner clean all` ignore minimum item age **by default**, so testing with freshly dropped items works. Normal automatic clean uses configurable item age; emergency clean bypasses age by default.
- Introduces safe, independently configurable **boats and plain minecarts** cleanup: only empty/unnamed vanilla types, outside all FTB claims and far from any player. Storage carts, TNT carts and chest boats are never touched.
- Adds **per-chunk mob overload**: at **50+ total Mob entities** in one chunk, if **one exact entity type exceeds 20**, remove **all eligible mobs of that type**. Example: **10 zombies + 30 witches + 10 skeletons -> remove 30 witches**, preserve the rest. Protected mobs are never deleted. Works in FTB claims and player chunks (both switches configurable); per-entity hard protections cannot be disabled.
- Adds editable server-side languages, default **English** `en_us`, optional Brazilian Portuguese `pt_br`.
- Every notification, time unit, status, mode label and results message now belongs to a language file. Supports `&` color/style codes, `&#RRGGBB`, `<gradient:#HEX:#HEX>...</gradient>`, and documented placeholders.
- Uses English-only commands; Portuguese command aliases were removed.
- Keeps scheduled warnings, manual cleanup, emergency item monitoring, per-tick removal limits, `/cleaner reload`, and analysis.

## Files created by the mod (server only)

On **first server start** (with a new config):

```text
world/
  serverconfig/
    ravencleaner.toml
    ravencleaner/
      lang/
        en_us.toml
        pt_br.toml
```

Edit `world/serverconfig/ravencleaner.toml`:

```toml
[language]
code = "pt_br"  # or en_us (default)
messagesEnabled = true
```

Edit the selected file in `world/serverconfig/ravencleaner/lang/`, then run `/cleaner reload` (OP 2 by default). **Config and translations reload without restarting.** Templates with extensive comments are in `examples/` and bundled as resources.

### Updating an existing v0.1.0 installation

If you already have `world/serverconfig/ravencleaner.toml`, the mod **keeps your existing file unchanged** to avoid overriding custom settings. New options have safe defaults, even if absent from the file. On startup, the language files are generated.

**To get the complete English-commented v0.2.1 config**, make a backup, rename the old `ravencleaner.toml` to `ravencleaner-v0.1-backup.toml`, then start/restart the server; the new config will be generated. Copy your desired intervals and other customized values back into the new config. The legacy `[messages]` section is no longer read; use the language files instead. Alternatively, copy the new sections from `examples/ravencleaner.toml` into the existing file, taking care to avoid duplicate TOML sections.

## Commands

| Command | Meaning |
| --- | --- |
| `/cleaner clean items` | Clear eligible dropped items, including fresh drops and player claims |
| `/cleaner clean mobs` | Clean distant safe hostiles + per-chunk overloaded safe types |
| `/cleaner clean vehicles` | Clean eligible boats and ordinary minecarts |
| `/cleaner clean all` | All three cleanup categories |
| `/cleaner analyze` | Dry-run eligible counts; removes nothing |
| `/cleaner status` | View time to next cycle |
| `/cleaner pause` | Pause normal schedule only, not emergency systems |
| `/cleaner resume` | Resume normal schedule |
| `/cleaner reload` | Reload config and language files |

## Item and claim safety

- Drops from **all loaded chunks** are eligible, including **FTB-claimed, force-loaded and occupied** chunks. Exclusions: `items.protectedIds` and item age when not ignored.
- **Normal** distant mob cleaning always checks FTB claims and minimum player distance. Only a restricted vanilla hostile allow-list can be removed.
- **Mob overload** deliberately ignores claim and player proximity when `includeClaimedChunks=true` and `includePlayerChunks=true`. Its trigger counts all Mob entities in a chunk, but actual removals are **only safe, ordinary, nonpersistent vanilla hostiles** in `mobs.removableIds`.
- All other mobs — including named, pets, tamed, villagers, bosses, NPCs, modded entities and marked/persistent mobs — remain protected, even if they contribute to triggering overload.
- **Vehicle** cleaning always respects FTB claims and nearby players. Only vanilla non-chest boats/rafts and ordinary empty minecarts qualify.
- Only currently loaded entities are scanned; nothing loads a chunk to clean it. Item stacks are counted as one drop entity for emergency thresholds; the final report shows both drop entities and item units.
- If an FTB claim lookup fails, ordinary mob/vehicle removal is skipped or stopped. Independent drops and overload-in-claims-mode can still be cleaned.

## Compiling on Windows

Place this source tree in your already working **NeoForge 1.21.1 ModDevGradle/MDK** project, keeping its `gradlew.bat`, `gradlew` and `gradle/wrapper/` files. Use Java 21:

```cmd
gradlew.bat build
```

Copy the resulting `build/libs/ravencleaner-0.2.1.jar` to the **server** `mods/` folder and remove the previous version. The server needs FTB Chunks. No client JAR is required.

## Validation performed

- Checked all bundled `.toml` files using a TOML parser.
- Executed Java 21 pure-logic tests of `ChunkOverloadPolicy` using the 10/30/10 example and boundary cases, and the existing warning-countdown test.
- **Full NeoForge build and integration testing have not been performed here.** Test on a backup world before deploying to production, and verify FTB claims with `/ftbchunks info` and actual entity counts.

---

## Raven Cleaner v0.2.1 — TAB 5.5.0 integration

This version adds an **optional server-side bridge to TAB 5.5.0 for NeoForge 1.21.1**. It uses TAB's public placeholder registration methods at runtime, with no external Gradle dependency or TAB jar bundled in this project. Raven Cleaner still runs without TAB installed. The bridge does not change TAB's files.

### Placeholder list

| Identifier | Result / example |
| --- | --- |
| `%ravencleaner_status%` | `&aActive`, `&ePaused`, `&bCleaning`, `&cDisabled` (text from language file) |
| `%ravencleaner_running%` | `true` / `false` (a cleanup job is executing) |
| `%ravencleaner_next_cleanup%` | `04:32` (MM:SS), `01:02:03` (HH:MM:SS), or `--:--` if not scheduled |
| `%ravencleaner_next_seconds%` | Seconds until next automatic cleanup, `-1` if not scheduled |
| `%ravencleaner_last_drops%` | Removed dropped-item *entities* in the last completed cleanup |
| `%ravencleaner_last_units%` | Removed units across those ItemStacks |
| `%ravencleaner_last_mobs%` | Removed mobs in the last completed cleanup |
| `%ravencleaner_last_vehicles%` | Removed vehicles in the last completed cleanup |
| `%ravencleaner_last_total%` | Drops + mobs + vehicles removed in the last completed cleanup |

Last-cleanup statistics are **per server session** and reset to `0` when the world/server restarts. These placeholders do not perform entity scans; the server updates one immutable cache per second and TAB reads the cached strings. All placeholders are **server placeholders** (same value for all players). Values use the language selected by `[language].code` for status text; identifiers never change with locale.

### Server-side configuration

The auto-generated config is `world/serverconfig/ravencleaner.toml`. On existing worlds, add the following section yourself; there is no need to delete your existing configuration:

```toml
[integrations.tab]
enabled = true
refreshIntervalMs = 1000
enableStatus = true
enableCountdown = true
enableStatistics = true
```

- `enabled=false` unregisters only Raven Cleaner's TAB placeholders.
- Disable one of the groups if you don't want its placeholders registered.
- Valid `refreshIntervalMs`: 250–60000; values are produced once per second regardless.
- `/cleaner reload` reloads this section and the language files without restarting.
- The TAB `TabLoadEvent` listener re-registers placeholders after `/tab reload`.
- Without TAB, Raven Cleaner starts normally; the integration remains inactive.

The updated language templates contain `tabStatusActive`, `tabStatusPaused`, `tabStatusRunning` and `tabStatusDisabled`; customize those keys in `world/serverconfig/ravencleaner/lang/en_us.toml` or `pt_br.toml` and run `/cleaner reload`. Old language files remain valid because missing keys fall back to the bundled defaults.

### Example: TAB scoreboard

Edit the `lines:` property under your selected scoreboard in TAB's `config.yml`:

```yaml
lines:
  - ' &d&l✦ SERVER '
  - ' &7Online: &f%online% '
  - ' &7Cleanup: &b%ravencleaner_next_cleanup% '
  - ' &7Status: %ravencleaner_status% '
  - ' &7Last drops: &e%ravencleaner_last_drops% '
  - ' &7Last mobs: &e%ravencleaner_last_mobs% '
  - ' &8&m---------------- '
```

You can also use the same placeholders in TAB header/footer or bossbar text. After updating the mod and the TAB scoreboard, restart the server (to load the new mod jar), then run `/tab reload` if the scoreboard was changed. Test `/cleaner clean items` and verify that the last-cleanup counters update. TAB 5.5.0 is an optional **server** mod; no TAB jar is added as a Raven Cleaner Gradle dependency.

### Other changes carried forward

- `Boat` is used for NeoForge 1.21.1 in place of the nonexistent `AbstractBoat` class.
- The deprecated Portuguese command aliases are removed; `/cleaner status`, `/cleaner analyze`, `/cleaner clean items|mobs|vehicles|all`, `/cleaner pause`, `/cleaner resume`, and `/cleaner reload` remain.
- This v0.2.1 is source code. It passed isolated Java tests of placeholder registration, simulated TAB reload, and cleanup of registrations; **the full NeoForge build and live game test still need to run on your Windows machine**.
