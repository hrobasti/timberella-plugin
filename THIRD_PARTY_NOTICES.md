# Third-party notices

Timberella by kroet.net contains the following third-party software. Each component is used under the license named below. The full license texts follow the table, one section per license, headed by the name in the License column. Components under the same license share its text.

| Component      | Includes                                                                                                                                            | Version | License | Copyright              | Source                                    |
| -------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | ------- | ---------------------- | ----------------------------------------- |
| TurtleLib      | `turtle-lib:turtle-lib-paper` without the test-only `LocaleFileChecker`; license text `licenses/turtle_lib_MIT.txt` in the jar                      | 2.0.0   | MIT     | 2025-2026 kroet.net    | https://github.com/hrobasti/turtle-lib    |
| bStats Metrics | `org.bstats:bstats-bukkit`, `org.bstats:bstats-base`, relocated to `net.kroet.timberella.bstats`; license text `licenses/bstats_MIT.txt` in the jar | 3.2.1   | MIT     | 2021 Bastian Oppermann | https://github.com/Bastian/bStats-Metrics |

## MIT

Applies to the components marked MIT above, each with its own copyright holder.

```text
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

## Trademarks

- Minecraft and its names, blocks, textures and characters belong to Mojang Studios and Microsoft. Timberella is not an official Minecraft product and is not approved by or associated with Mojang or Microsoft.
- The animation in `img/` (README only, not in the jar) shows Minecraft gameplay; Minecraft's textures in it are not covered by Timberella's MIT license.
- Paper, Hangar, Modrinth, bStats, ItemsAdder, CraftEngine, MMOItems and MythicCrucible are named only to identify the platforms, services and plugins Timberella works with; the names belong to their owners.

## Not shipped

Never part of the plugin jar; versions in `gradle/libs.versions.toml`:

- Provided at runtime by Paper: the Paper API (Paper's own license terms) with Adventure and MiniMessage (MIT) and Gson (Apache-2.0).
- APIs of other plugins, used only to compile Timberella's custom-block guard, each under its publisher's terms: ItemsAdder API (MIT), CraftEngine (GPL-3.0), MMOItems API, MythicLib, MythicCrucible, MythicMobs.
- Used only for tests: JUnit 6 (Jupiter and Platform Launcher; EPL-2.0).
- Used only for the build's null check: the JSpecify annotations (Apache-2.0, compile-only) and the Eclipse compiler ECJ (EPL-2.0).
