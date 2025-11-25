# Third-Party Licenses

| Dependency | Version (current) | License | Upstream | Notes |
| --- | --- | --- | --- | --- |
| com.github.johnrengelman.shadow | 8.1.1 | Apache License 2.0 | https://github.com/johnrengelman/shadow | Gradle plugin that produces the shaded JAR. |
| net.kyori:adventure-text-minimessage | 4.17.0 | MIT License | https://github.com/KyoriPowered/adventure | Provides MiniMessage formatting for messages. |
| com.google.code.gson:gson | 2.10.1 | Apache License 2.0 | https://github.com/google/gson | Used for lightweight JSON parsing (update checker). |
| bStats Metrics (embedded class) | 3.1.0 | MIT License | https://github.com/Bastian/bStats-Metrics | `Metrics.java` relocated into `com.github.hrobasti.timberella.metrics` for telemetry. |

The full license texts are bundled within the plugin under `licenses/`:
- `licenses/apache-2.0.txt`
- `licenses/mit.txt`

> ℹ️ Timberella also includes the in-house `turtle-lib` project via a composite build. Because it is proprietary and maintained by the same author, it is documented in the main `README.md` rather than in this third-party list. Refer to `../turtle-lib/LICENSE` for its exact terms.

Please retain these notices if you redistribute Timberella or derivative works.

