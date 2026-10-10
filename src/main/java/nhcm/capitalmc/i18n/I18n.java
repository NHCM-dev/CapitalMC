package nhcm.capitalmc.i18n;

import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Server-side translations, requiring no language pack on the client.
 */
public class I18n
{
    private static final String DEFAULT_LANGUAGE = "en_us";
    private static final Map<String, Map<String, String>> TRANSLATIONS = Map.of(
            "en_us", load("en_us"),
            "zh_cn", load("zh_cn"));

    private I18n()
    {
    }

    /**
     * Accepts either a translation key or literal text, optionally with format arguments.
     */
    public static Component text(String language, String keyOrText, Object... args)
    {
        return styled(format(language, keyOrText, args));
    }

    /**
     * Legacy &/section-sign colors and formatting, plus &#RRGGBB, become actual component styles.
     */
    public static Component styled(String text)
    {
        var result = Component.empty();
        var part = new StringBuilder();
        Style style = Style.EMPTY;
        for (int i = 0; i < text.length(); i++)
        {
            char marker = text.charAt(i);
            if ((marker == '&' || marker == '\u00a7') && i + 1 < text.length())
            {
                var code = ChatFormatting.getByCode(Character.toLowerCase(text.charAt(i + 1)));
                int hex = -1;
                if (text.charAt(i + 1) == '#' && i + 7 < text.length() && text.substring(i + 2, i + 8).matches("[0-9a-fA-F]{6}"))
                {
                    try
                    {
                        hex = Integer.parseInt(text.substring(i + 2, i + 8), 16);
                    } catch (NumberFormatException ignored)
                    {
                    }
                }
                if (code != null || hex >= 0)
                {
                    if (!part.isEmpty())
                    {
                        result.append(Component.literal(part.toString()).setStyle(style));
                        part.setLength(0);
                    }
                    style = hex >= 0 ? Style.EMPTY.withColor(hex) : style.applyLegacyFormat(code);
                    i += hex >= 0 ? 7 : 1;
                    continue;
                }
            }
            part.append(marker);
        }
        if (!part.isEmpty())
        {
            result.append(Component.literal(part.toString()).setStyle(style));
        }
        return result;
    }

    public static List<Component> lines(String language, String keyOrText, Object... args)
    {
        var lines = new ArrayList<Component>();
        var current = new MutableComponent[]{Component.empty()};
        text(language, keyOrText, args).visit((style, value) -> {
            String[] parts = value.split("\n", -1);
            for (int i = 0; i < parts.length; i++)
            {
                if (i > 0)
                {
                    lines.add(current[0]);
                    current[0] = Component.empty();
                }
                if (!parts[i].isEmpty())
                {
                    current[0].append(Component.literal(parts[i]).setStyle(style));
                }
            }
            return Optional.empty();
        }, Style.EMPTY);
        lines.add(current[0]);
        return List.copyOf(lines);
    }

    /**
     * Resolves known keys; otherwise uses the supplied text as a String.format template.
     */
    public static String format(String language, String keyOrText, Object... args)
    {
        String normalized = language == null ? DEFAULT_LANGUAGE
                : language.toLowerCase(Locale.ROOT).replace('-', '_');
        Map<String, String> fallback = TRANSLATIONS.get(DEFAULT_LANGUAGE);
        Map<String, String> translations = TRANSLATIONS.getOrDefault(normalized, fallback);
        String template = translations.getOrDefault(keyOrText, fallback.getOrDefault(keyOrText, keyOrText));
        return args.length == 0 ? template : String.format(Locale.ROOT, template, args);
    }

    private static Map<String, String> load(String language)
    {
        String path = "/assets/nhcm_capitalmc/lang/" + language + ".json";
        var stream = I18n.class.getResourceAsStream(path);
        if (stream == null)
        {
            throw new IllegalStateException("Missing language resource: " + path);
        }
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8))
        {
            Map<String, String> entries = new HashMap<>();
            JsonParser.parseReader(reader).getAsJsonObject().entrySet()
                      .forEach(entry -> entries.put(entry.getKey(), entry.getValue().getAsString()));
            return Map.copyOf(entries);
        } catch (IOException exception)
        {
            throw new IllegalStateException("Cannot load language resource: " + path, exception);
        }
    }
}
