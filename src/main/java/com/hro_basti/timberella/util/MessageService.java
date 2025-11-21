package com.hro_basti.timberella.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MessageService {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final String DEFAULT_LOCALE = "en_US";
    private static final String[] BUNDLED_LOCALES = {
            "en_US",
            "de_DE",
            "ar_SA",
            "es_ES",
            "fr_FR",
            "it_IT",
            "ja_JP",
            "ko_KR",
            "nl_NL",
            "pl_PL",
            "pt_PT",
            "tr_TR",
            "uk_UA",
            "zh_CN"
    };

    private final JavaPlugin plugin;
    private final Map<String, String> messages = new HashMap<>();
    private String prefixRaw = "<light_purple>[Timberella]</light_purple>";

    public MessageService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public static List<String> getBundledLocales() {
        return List.of(BUNDLED_LOCALES);
    }

    public void load(String locale) {
        messages.clear();
        ensureBundledLocales();

        File langFile = new File(plugin.getDataFolder(), "lang" + File.separator + locale + ".yml");
        if (!langFile.exists()) {
            // fallback to en_US
            locale = DEFAULT_LOCALE;
            langFile = new File(plugin.getDataFolder(), "lang" + File.separator + DEFAULT_LOCALE + ".yml");
        }
        syncLocaleFile(locale);
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(langFile);
        for (String key : cfg.getKeys(false)) {
            messages.put(key, cfg.getString(key, key));
        }
        this.prefixRaw = messages.getOrDefault("prefix", "<light_purple>[Timberella]</light_purple>");
    }

    private void ensureBundledLocales() {
        for (String locale : BUNDLED_LOCALES) {
            saveLangIfAbsent(locale + ".yml");
        }
    }

    private File saveLangIfAbsent(String name) {
        File out = new File(plugin.getDataFolder(), "lang" + File.separator + name);
        if (!out.getParentFile().exists()) {
            //noinspection ResultOfMethodCallIgnored
            out.getParentFile().mkdirs();
        }
        if (!out.exists()) {
            try {
                plugin.saveResource("lang/" + name, false);
            } catch (IllegalArgumentException ex) {
                try {
                    if (!out.createNewFile()) {
                        plugin.getLogger().fine("Could not create " + name + " (already exists?).");
                    }
                } catch (IOException ioException) {
                    plugin.getLogger().fine("Could not create language file " + name + ": " + ioException.getMessage());
                }
            }
        }
        return out;
    }

    public List<String> syncLocaleFile(String locale) {
        if (locale == null || locale.isBlank()) {
            return Collections.emptyList();
        }
        if (locale.endsWith(".yml")) {
            locale = locale.substring(0, locale.length() - 4);
        }
        File langFile = saveLangIfAbsent(locale + ".yml");
        return mergeMissingLangKeys(langFile, locale);
    }

    private List<String> mergeMissingLangKeys(File langFile, String locale) {
        YamlConfiguration target = YamlConfiguration.loadConfiguration(langFile);
        YamlConfiguration defaults = loadDefaultLang(locale);
        if (defaults == null) {
            return Collections.emptyList();
        }
        List<String> addedKeys = new ArrayList<>();
        boolean changed = mergeSections(target, defaults, "", addedKeys);
        if (changed) {
            try {
                target.save(langFile);
            } catch (IOException e) {
                plugin.getLogger().fine("Failed to merge lang keys for " + langFile.getName() + ": " + e.getMessage());
            }
        }
        return addedKeys;
    }

    private YamlConfiguration loadDefaultLang(String locale) {
        YamlConfiguration defaults = loadLocaleResource(locale);
        if (defaults == null && !DEFAULT_LOCALE.equals(locale)) {
            defaults = loadLocaleResource(DEFAULT_LOCALE);
        }
        return defaults;
    }

    private YamlConfiguration loadLocaleResource(String locale) {
        try (InputStream in = plugin.getResource("lang/" + locale + ".yml")) {
            if (in == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    private boolean mergeSections(ConfigurationSection target, ConfigurationSection defaults, String pathPrefix, List<String> addedKeys) {
        boolean changed = false;
        for (String key : defaults.getKeys(false)) {
            Object defVal = defaults.get(key);
            if (defVal instanceof ConfigurationSection defSection) {
                ConfigurationSection targetSection;
                if (target.isConfigurationSection(key)) {
                    targetSection = target.getConfigurationSection(key);
                } else if (!target.contains(key)) {
                    targetSection = target.createSection(key);
                } else {
                    continue;
                }
                String fullKey = pathPrefix == null || pathPrefix.isEmpty() ? key : pathPrefix + "." + key;
                changed |= mergeSections(targetSection, defSection, fullKey, addedKeys);
                continue;
            }
            if (!target.contains(key)) {
                target.set(key, defVal);
                if (addedKeys != null) {
                    String fullKey = pathPrefix == null || pathPrefix.isEmpty() ? key : pathPrefix + "." + key;
                    addedKeys.add(fullKey);
                }
                changed = true;
            }
        }
        return changed;
    }

    public Component component(String key) {
        String raw = messages.getOrDefault(key, key);
        TagResolver resolver = TagResolver.resolver(
                Placeholder.parsed("prefix", prefixRaw)
        );
        return MINI.deserialize(raw, resolver);
    }

    public Component format(String key, Map<String, String> replacements) {
        String raw = messages.getOrDefault(key, key);
        TagResolver.Builder builder = TagResolver.builder();
        builder.resolver(Placeholder.parsed("prefix", prefixRaw));
        for (Map.Entry<String, String> e : replacements.entrySet()) {
            // Unparsed verhindert, dass Werte als Tags interpretiert werden
            builder.resolver(Placeholder.unparsed(e.getKey(), e.getValue()));
        }
        return MINI.deserialize(raw, builder.build());
    }

    public String plain(String key) {
        return PLAIN.serialize(component(key));
    }

    public String plain(String key, Map<String, String> replacements) {
        if (replacements == null || replacements.isEmpty()) {
            return plain(key);
        }
        return PLAIN.serialize(format(key, replacements));
    }
}
