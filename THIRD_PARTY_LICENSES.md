# Third-Party Licenses

| Dependency                              | Version (current)                    | License            | Upstream                                    | Notes                                                                                                                                                                           |
| --------------------------------------- | ------------------------------------ | ------------------ | ------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| turtle-lib:turtle-lib-paper (TurtleLib) | 2.0.0                                | MIT License        | <https://github.com/hrobasti/turtle-lib>    | Library by the same author (kroet.net). Shaded into this jar; the test-only `LocaleFileChecker` is left out. Copyright (c) 2025-2026 kroet.net.                                 |
| org.bstats:bstats-bukkit                | 3.2.1                                | MIT License        | <https://github.com/Bastian/bStats-Metrics> | Telemetry. Shaded and relocated into `net.kroet.timberella.bstats`, as bStats requires its classes to live in the plugin's own package. Copyright (c) 2021 Bastian Oppermann.   |
| net.kyori:adventure-text-minimessage    | provided by Paper (currently 5.2.0)  | MIT License        | <https://github.com/KyoriPowered/adventure> | MiniMessage formatting for all messages. Comes in through `paper-api` (`compileOnly`), which fixes its version; Paper provides it at runtime, so it is not bundled in this jar. |
| com.google.code.gson:gson               | provided by Paper (currently 2.14.0) | Apache License 2.0 | <https://github.com/google/gson>            | JSON parsing for the update checker. Comes in through `paper-api` (`compileOnly`), which fixes its version; Paper provides it at runtime, so it is not bundled in this jar.     |

## Bundled license texts

The two libraries bundled in the jar ship their license texts under `licenses/`, each a verbatim copy of the upstream `LICENSE`:

- `licenses/turtle-lib-MIT.txt`: TurtleLib (MIT, Copyright (c) 2025-2026 kroet.net)
- `licenses/bstats-MIT.txt`: bStats Metrics (MIT, Copyright (c) 2021 Bastian Oppermann)

Adventure/MiniMessage and Gson come in through `paper-api` at compile time only and are not bundled in the jar, so no license text ships for them.

## TurtleLib

TurtleLib is a separate library by the same author (kroet.net), released under the MIT License. Timberella shades its compiled classes into this jar and leaves out the test-only `LocaleFileChecker`, which the MIT License allows. The canonical license text is <https://github.com/hrobasti/turtle-lib/blob/default/LICENSE>.

Both bundled libraries are MIT-licensed, which requires their copyright and permission notices to stay with every copy: please keep `licenses/` and this file when you redistribute Timberella or a modified version of it.
