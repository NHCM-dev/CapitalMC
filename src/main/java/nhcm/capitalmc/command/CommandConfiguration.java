package nhcm.capitalmc.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import nhcm.advmatch.model.ContractSchedule;
import nhcm.advmatch.model.ReferencePriceConfig;
import nhcm.advmatch.model.TradingRules;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Arrays;

/**
 * A field command produces a validated patch; JSON is never part of user syntax.
 */
class CommandConfiguration
{
    static Class<?> model(String kind)
    {
        return switch (kind)
        {
            case "rules" -> TradingRules.class;
            case "schedule" -> ContractSchedule.class;
            case "reference" -> ReferencePriceConfig.class;
            default -> throw new IllegalArgumentException("cli.help.unknown");
        };
    }

    static Map<String, Class<?>> fields(Class<?> model)
    {
        var result = new LinkedHashMap<String, Class<?>>();
        for (var component : model.getRecordComponents())
        {
            if (component.getType().isRecord())
            {
                fields(component.getType()).forEach((key, type) -> result.put(component.getName() + "." + key, type));
            }
            else if (component.getType().isPrimitive() || component.getType().isEnum())
            {
                result.put(component.getName(), component.getType());
            }
        }
        return result;
    }

    static JsonObject patch(Class<?> model, String field, String input)
    {
        Class<?> type = fields(model).get(field);
        if (type == null)
        {
            throw new IllegalArgumentException("cli.field.unknown");
        }
        JsonElement value;
        try
        {
            if (type == boolean.class)
            {
                if (!input.equals("true") && !input.equals("false"))
                {
                    throw new IllegalArgumentException("cli.field.boolean");
                }
                value = new JsonPrimitive(Boolean.parseBoolean(input));
            }
            else if (type.isEnum())
            {
                Object selected = Arrays.stream(type.getEnumConstants()).filter(v -> v.toString().equalsIgnoreCase(input))
                                        .findFirst().orElseThrow(() -> new IllegalArgumentException("cli.field.enum"));
                value = new JsonPrimitive(selected.toString());
            }
            else
            {
                BigDecimal number = new BigDecimal(input);
                if (type == int.class)
                {
                    number.intValueExact();
                }
                else
                {
                    number.longValueExact();
                }
                value = new JsonPrimitive(number);
            }
        } catch (ArithmeticException | NumberFormatException error)
        {
            throw new IllegalArgumentException("cli.field.integer");
        }
        var patch = new JsonObject();
        var node = patch;
        String[] path = field.split("\\.");
        for (int i = 0; i < path.length - 1; i++)
        {
            var child = new JsonObject();
            node.add(path[i], child);
            node = child;
        }
        node.add(path[path.length - 1], value);
        return patch;
    }
}
