# CityEchoes-In-DangoMusic

**English** | [简体中文](README.zh-CN.md)

An **addon mod** for [DangoMusic](https://github.com/Sorihiko/DangoMusic) that ships the
"City Echoes" (都市回响) 3D lyric performance as a standalone jar instead of a part of the main mod.

The point is decoupling. DangoMusic is actively developed, and this performance used to live inside
its jar — every refactor upstream could break it. As an addon, the two can be updated independently
as long as the injection points still line up.

- Host mod: <https://github.com/Sorihiko/DangoMusic>
- Original effect: <https://github.com/Celine258/idea-from-limbus-company-dancing-lyrics>

## What it does

Adds **one extra way to present lyrics**, on top of the stock 3D lyrics: letters float in the world
one by one, with wave motion, jumps, tilt, a scrambled exit and singing emphasis. Optional deep glow,
a translation line, and rainbow colouring.

It does **not** touch the player, playlists, audio sources or any other existing feature of the host
mod — it only adds a third tab to DangoMusic's settings screen.

## Requirements

| | |
|---|---|
| Minecraft | 1.20.1 (Forge 47.x) or 1.21.1 (NeoForge 21.1.x) |
| Java | 17 for 1.20.1 / 21 for 1.21.1 |
| DangoMusic | **required**, 1.7.0 or newer, matching your loader |
| Side | client only — nothing to install on the server |

## Installation

1. Install DangoMusic first.
2. Drop `CityEchoes-In-DangoMusic-<version>+<mc>-<loader>.jar` into `mods/`.
3. In game, open the DangoMusic music screen → settings. A third tab, **City Echoes**, is there.

Everything configurable lives in that tab. Each group header carries the module's master switch —
turning it off stops rendering entirely, exactly like a built-in module. Values are written to the
host mod's `config/dangomusic/config.json`, the same file as its own modules.

## How it works (and why it is fragile)

The addon does not patch DangoMusic's source. It injects six points at runtime with
SpongePowered Mixin:

| Target | Injection |
|---|---|
| `DangoMusic#init` | register `CityEchoesModule` before the host scans its modules |
| `SettingsPage#buildSettings` | append a third tab to the returned list |
| `SettingsPage#drawTabs` | draw three labels instead of two |
| `SettingsPage#<init>` | add this module's dropdown labels to the choice map |
| `I18n#getChinese` | add Chinese translations |
| `LyricsModule` | take over stock 3D lyric and HUD rendering while the module is enabled |

The cost is explicit: all of those are `internal` / package-private internals of the host. If
DangoMusic ever refactors `SettingsPage`, the injection stops applying.

That is why every injection uses `require = 0`. **A failed injection only means the third tab is
missing; it will never crash the game through a failed Mixin apply.** Search `logs/latest.log` for
`[CityEchoes]` to see the actual state of each injection point after launch.

## Building

You need **JDK 21** (Loom uses it for the 1.21.1 target and lowers the toolchain to 17 for 1.20.1, so
21 alone is enough) and **Gradle 8.11**. The wrapper is committed, so you don't have to install
Gradle — note that 9.x is incompatible with Architectury Loom 1.13.

Dependency jars are **not** committed (see `.gitignore`). Prepare `libs/` yourself:

```
libs/
├── DangoMusic-1.7.3+1.20.1-forge.jar      # from DangoMusic Releases
├── DangoMusic-1.7.3+1.21.1-neoforge.jar   # same, matching loader
└── mixin-patched-0.8.5.12.jar             # SpongePowered Mixin 0.8.5 (patched)
```

Then:

```bash
./gradlew build                  # both targets
./gradlew :1.20.1-forge:build    # one target
```

Output lands in `versions/<target>/build/libs/`; the `remapJar` result is the jar you ship.

`init.gradle.kts` exists for restricted networks (mainland China). Loom pins the Forge / Mojang
repositories with `exclusiveContent`, so adding a mirror through the normal `repositories` block gets
bypassed; the script instead rewrites repository URLs to BMCLAPI after they are registered. Skip it
on a normal connection:

```bash
./gradlew build --init-script init.gradle.kts
```

The first build takes a few minutes for the two targets combined — Loom has to download and remap
Minecraft and NeoForge.

## Layout

```
common/src/main/kotlin/me/cityechoes/
├── CityEchoes.kt              performance core: per-letter kinematics, waves, tilt, exit
├── DangoBridge.kt             bridge to the host's public API (incl. shaded-type handling)
├── mods/CityEchoesModule.kt   the module: 44 @ModuleValue options + two render hooks
├── ui/SettingsModel.kt        reflects @ModuleValue into a group/row model
├── ui/DangoSettingsBridge.kt  translates that model into the host's Group/Row via reflection
├── ui/DangoSettingsZh.kt      Chinese translations
├── ui/CityLog.kt              single logging entry point
└── mixin/                     the four mixin classes (six injection points)
versions/1.20.1-forge/         Forge entry point + mods.toml + mixin config
versions/1.21.1-neoforge/      NeoForge entry point + neoforge.mods.toml + mixin config
```

## License

**GNU General Public License v3.0 (GPL-3.0-only)**, full text in [LICENSE](LICENSE).

This is not arbitrary. At runtime the addon **rewrites DangoMusic's bytecode** through Mixin and
links its classes inside the same JVM, which under GPLv3 §5 makes it a work based on that program —
so distribution must be under GPLv3 as a whole. DangoMusic grants no "or any later version" clause,
hence `GPL-3.0-only` rather than `-or-later`.

In practice: **use it, modify it, redistribute it freely — but if you ship a binary, you must ship
the complete source too.**

Third-party components, attribution and their respective licenses are listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## AI disclosure

The code in this repository was written largely by an **AI coding assistant** — an LLM agent running
inside [WorkBuddy](https://www.workbuddy.cn) — working under human direction. Being as specific as
possible about the split:

| Done by the AI | Done by a human |
|---|---|
| All Kotlin sources under `common/` and `versions/` | The requirement itself: pull the performance out into a decoupled addon |
| Disassembling DangoMusic 1.7.3's bytecode with `javap` to locate the six injection points | Choosing the approach: public API only, `require = 0` on every injection, no Access Transformer |
| The mixin classes, the reflection bridge, the Gradle setup | Choosing the license, the project name, and the shape of the third settings tab |
| Both READMEs, `THIRD_PARTY_NOTICES.md`, commit messages | In-game verification, and every decision to keep or drop a feature |

This says nothing about the upstream projects. DangoMusic and Celine258's original City Echoes are
human-written, and nothing under `libs/` is AI output.

What that means if you are going to run, fork or audit this thing:

- The code was **verified in game on 1.20.1** (settings tab, sliders, config persistence) and its
  1.21.1 injection targets were checked against that jar's bytecode — but it has **not** been through
  a line-by-line human code review.
- There is no test suite. Minecraft mods rarely have a meaningful one, and this project does not.
- Expect the usual failure modes of generated code: over-defensive `try`/`catch`, comments that
  describe behaviour slightly older than the code, redundant abstraction layers
  (`DangoBridge.guarded` is dead code today).

Corrections are welcome, including where this document and the shipped behaviour disagree.

## Credits

- [Sorihiko/DangoMusic](https://github.com/Sorihiko/DangoMusic) — the host mod
- [Celine258/idea-from-limbus-company-dancing-lyrics](https://github.com/Celine258/idea-from-limbus-company-dancing-lyrics) — the original City Echoes performance

Not affiliated with Mojang or Microsoft. "Minecraft" is a trademark of Mojang Synergies AB.
