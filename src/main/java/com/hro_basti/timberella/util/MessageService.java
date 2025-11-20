package com.hro_basti.timberella.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class MessageService {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
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
    private String prefixRaw = "<gold>[Timberella]</gold>";

    public MessageService(JavaPlugin plugin) {
        this.plugin = plugin;
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
        mergeMissingLangKeys(langFile, locale);
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(langFile);
        for (String key : cfg.getKeys(false)) {
            messages.put(key, cfg.getString(key, key));
        }
        this.prefixRaw = messages.getOrDefault("prefix", "<gold>[Timberella]</gold>");
    }

    private void ensureBundledLocales() {
        for (String locale : BUNDLED_LOCALES) {
            saveLangIfAbsent(locale + ".yml");
        }
    }

    private void saveLangIfAbsent(String name) {
        File out = new File(plugin.getDataFolder(), "lang" + File.separator + name);
        if (!out.getParentFile().exists()) {
            //noinspection ResultOfMethodCallIgnored
            out.getParentFile().mkdirs();
        }
        if (!out.exists()) {
            plugin.saveResource("lang/" + name, false);
        }
    }

    private void mergeMissingLangKeys(File langFile, String locale) {
        YamlConfiguration target = YamlConfiguration.loadConfiguration(langFile);
        YamlConfiguration defaults = loadDefaultLang(locale);
        if (defaults == null) {
            return;
        }
        boolean changed = mergeSections(target, defaults);
        if (changed) {
            try {
                target.save(langFile);
            } catch (IOException e) {
                plugin.getLogger().fine("Failed to merge lang keys for " + langFile.getName() + ": " + e.getMessage());
            }
        }
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

    private boolean mergeSections(ConfigurationSection target, ConfigurationSection defaults) {
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
                changed |= mergeSections(targetSection, defSection);
                continue;
            }
            if (!target.contains(key)) {
                target.set(key, defVal);
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
}
