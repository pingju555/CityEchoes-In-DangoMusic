# Third-Party Notices

This project (CityEchoes-In-DangoMusic) is licensed under **GPL-3.0-only**. See [LICENSE](LICENSE).

It is built on, and at runtime modifies, other people's work. Those components and their
licenses are listed below.

---

## 1. DangoMusic

- **License:** GNU General Public License v3.0 (GPL-3.0)
- **Author / copyright holder:** Sorihiko
- **Source:** https://github.com/Sorihiko/DangoMusic

This addon is a companion mod for DangoMusic and is not a fork of it:

- At **compile time** it only links against the published DangoMusic artifact as
  `modCompileOnly`. No DangoMusic class files, resources, or assets are copied into this
  project or into the jar it produces.
- At **runtime** it requires DangoMusic to be installed, and it patches four of its classes
  through SpongePowered Mixin (`DangoMusic`, `SettingsPage`, `I18n`, `LyricsModule`) in order to
  register the City Echoes module and to add a third tab to DangoMusic's settings screen.

Because those runtime patches modify a GPL-3.0 program, **this project as a whole is distributed
under GPL-3.0-only**, and the complete corresponding source is published at the repository this
notice ships with.

DangoMusic's own license text is not reproduced here; refer to the link above. Every release of
this addon requires the user to install DangoMusic separately.

---

## 2. idea-from-limbus-company-dancing-lyrics (City Echoes)

- **License:** MIT License
- **Copyright:** Copyright (c) 2026 Celine258
- **Source:** https://github.com/Celine258/idea-from-limbus-company-dancing-lyrics

The "City Echoes" lyric performance concept — the per-letter motion model, the ripple / fall
wave behaviours, the singing-emphasis and scramble-exit timings — originates from this project.
Portions of the performance logic in `me.cityechoes.CityEchoes` were ported from it to Minecraft.

The MIT license is compatible with GPL-3.0: the ported portions remain available under MIT, and
the combined work is distributed under GPL-3.0-only. The MIT permission notice is reproduced
below as required:

```
MIT License

Copyright (c) 2026 Celine258

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 3. Bundled runtime libraries (shaded into the jar)

The release jar bundles the following, relocated to `me.cityechoes.shadow.*` so they cannot
conflict with the same libraries provided by other mods or by DangoMusic's own shading:

| Library | Version | License | Relocated to |
|---|---|---|---|
| Kotlin standard library (`kotlin-stdlib`) | 2.2.x | Apache License 2.0 | `me.cityechoes.shadow.kotlin` |

Apache-2.0 is compatible with GPL-3.0. The Kotlin standard library's license text and NOTICE
are available at https://github.com/JetBrains/kotlin.

## 4. Build-time only dependencies (not distributed)

These are used to compile the mod and are **not** present in the released jar:

| Component | License |
|---|---|
| SpongePowered Mixin | MIT |
| Architectury Loom | LGPL-3.0 |
| Gradle | Apache-2.0 |

---

## Trademarks

"Minecraft" is a trademark of Mojang Synergies AB. This project is not affiliated with, endorsed
by, or associated with Mojang or Microsoft.
