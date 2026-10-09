package nhcm.capitalmc.i18n;

import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Server-side translations, requiring no language pack on the client. */
public final class I18n {
    private static final String DEFAULT_LANGUAGE = "en_us";
    private static final Map<String, Map<String, String>> TRANSLATIONS = Map.of(
            "en_us", load("en_us"),
            "zh_cn", load("zh_cn"));

    private I18n() {}

    /** Accepts either a translation key or literal text, optionally with format arguments. */
    public static Component text(String language, String keyOrText, Object... args) {
        return Component.literal(format(language, keyOrText, args));
    }

    /** Resolves known keys; otherwise uses the supplied text as a String.format template. */
    public static String format(String language, String keyOrText, Object... args) {
        String normalized = language == null ? DEFAULT_LANGUAGE
                : language.toLowerCase(Locale.ROOT).replace('-', '_');
        Map<String, String> fallback = TRANSLATIONS.get(DEFAULT_LANGUAGE);
        Map<String, String> translations = TRANSLATIONS.getOrDefault(normalized, fallback);
        String template = translations.getOrDefault(keyOrText, fallback.getOrDefault(keyOrText, keyOrText));
        return args.length == 0 ? template : String.format(Locale.ROOT, template, args);
    }

    private static Map<String, String> load(String language) {
        String path = "/assets/nhcm_capitalmc/lang/" + language + ".json";
        var stream = I18n.class.getResourceAsStream(path);
        if (stream == null) {
            throw new IllegalStateException("Missing language resource: " + path);
        }
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            Map<String, String> entries = new HashMap<>();
            JsonParser.parseReader(reader).getAsJsonObject().entrySet()
                    .forEach(entry -> entries.put(entry.getKey(), entry.getValue().getAsString()));
            return Map.copyOf(entries);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load language resource: " + path, exception);
        }
    }
}
