package nhcm.capitalmc.gui;

import com.google.gson.*;
import nhcm.advmatch.model.*;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.service.*;
import net.minecraft.server.permissions.Permissions;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Guided management input; produces arguments for the existing administration service.
 */
public class AdminInput
{
    private static final Gson JSON = new Gson();

    public static CompletableFuture<Optional<String[]>> collect(TradingGui gui, String action, String preset, SymbolView symbol)
    {
        if (symbol == null && Set.of("rules", "schedule", "reference", "lot", "fees", "funding-percent", "price-tick", "quantity-step", "min-quantity", "min-notional", "mark", "deliver").contains(action))
        {
            throw new IllegalStateException("ui.loading");
        }
        if (Set.of("rules", "schedule", "reference").contains(action))
        {
            if (symbol == null)
            {
                throw new IllegalStateException("ui.loading");
            }
            CompletableFuture<JsonObject> original;
            if (action.equals("rules"))
            {
                original = CompletableFuture.completedFuture(JSON.toJsonTree(symbol.tradingRules()).getAsJsonObject());
            }
            else
            {
                original = CapitalMC.exchange.contracts().market(CapitalMC.exchange.symbols().get(symbol.symbolId())).thenApply(view -> {
                    Object settings = action.equals("reference") ? view.referenceConfig()
                            : new ContractSchedule().withFundingInterval(view.spec().fundingInterval()).withNextFundingTimestamp(view.nextFundingTimestamp())
                                                    .withMaxFundingRate(view.spec().maxFundingRate()).withExpiryTimestamp(view.spec().expiryTimestamp());
                    return JSON.toJsonTree(settings).getAsJsonObject();
                });
            }
            long epoch = CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID());
            var account = gui.getUser().map(user -> user.getUuid()).orElse(null);
            var world = gui.getPlayer().level();
            return original.thenCompose(object -> GameServices.get().onServer(() -> {
                if (gui.getPlayer().hasDisconnected() || !gui.isOpen() || gui.getCurrentPage() != PageType.ADMIN || gui.getPlayer().level() != world
                    || !Objects.equals(account, gui.getUser().map(user -> user.getUuid()).orElse(null))
                    || epoch != CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID()))
                {
                    throw new IllegalStateException("ui.session.changed");
                }
                if (!gui.getPlayer().permissions().hasPermission(Permissions.COMMANDS_OWNER))
                {
                    throw new SecurityException("ui.admin.denied");
                }
                return patch(gui, preset, symbol, object, new ArrayList<>());
            })).thenCompose(future -> future);
        }
        List<String> fields = switch (action)
        {
            case "peg-human", "peg-config" ->
                    List.of("base", "quote", "baseAmount", "quoteAmount", "feePercent", "minimum", "maximum", "buyEnabled", "sellEnabled", "enabled");
            case "asset-units" -> List.of("unitSize", "minimumDeposit", "minimumWithdrawal");
            case "lot" -> List.of("lotSize", "minimumLots", "lotStep");
            case "fees" -> List.of("makerFeeRate", "takerFeeRate");
            case "reserve-in", "reserve-out" -> List.of("username", "asset", "amount");
            case "symbol" -> List.of("marketType", "marketName", "base", "quote", "initialPrice", "expiryTimestamp");
            case "display" -> List.of("displayName");
            case "funding-percent" -> List.of("fundingRate");
            case "price-tick" -> List.of("priceTick");
            case "quantity-step" -> List.of("quantityStep");
            case "min-quantity" -> List.of("minQuantity");
            case "min-notional" -> List.of("minOrderNotional");
            case "mark", "deliver" -> List.of("initialPrice");
            case "refund" -> preset.isBlank() ? List.of("operationId") : List.of();
            case "mint", "burn" -> List.of("amount");
            default -> List.of();
        };
        var answers = new ArrayList<String>();
        if (!preset.isBlank())
        {
            answers.add(preset);
        }
        return step(gui, action, preset, symbol, fields, 0, answers);
    }

    private static CompletableFuture<Optional<String[]>> step(TradingGui gui, String action, String preset, SymbolView symbol,
                                                              List<String> fields, int index, List<String> answers)
    {
        int count = fields.size();
        if (action.equals("symbol") && answers.size() >= 1)
        {
            count = answers.getFirst().equals("spot") ? 4 : answers.getFirst().equals("future") ? 6 : 5;
        }
        if (index == count)
        {
            StringBuilder review = new StringBuilder(GuiWidgets.text(gui, "ui.form.review"));
            int offset = preset.isBlank() ? 0 : 1;
            for (int i = 0; i < count; i++)
            {
                String value = answers.get(offset + i);
                if (Set.of("buyEnabled", "sellEnabled", "enabled").contains(fields.get(i)))
                {
                    value = GuiWidgets.text(gui, Boolean.parseBoolean(value) ? "ui.form.on" : "ui.form.off");
                }
                if (fields.get(i).equals("expiryTimestamp"))
                {
                    value = Instant.ofEpochMilli(Long.parseLong(value)).toString();
                }
                review.append("\n&7").append(GuiWidgets.text(gui, "ui.form.field." + fields.get(i))).append("：&b").append(value);
            }
            GuiActions.message(gui, review.toString());
            if (action.equals("symbol") && answers.getFirst().equals("future"))
            {
                var spec = new JsonObject();
                spec.addProperty("expiryTimestamp", Long.parseLong(answers.getLast()));
                answers.set(5, spec.toString());
            }
            return CompletableFuture.completedFuture(Optional.of(answers.toArray(String[]::new)));
        }
        String field = fields.get(index);
        String label = GuiWidgets.text(gui, "ui.form.field." + field);
        String hint = hint(gui, action, preset, symbol, field, answers);
        String question = GuiWidgets.text(gui, "ui.form.step", index + 1, count, label) + "\n" + hint;
        return GuiActions.input(gui, question, text -> {
            try
            {
                return parse(action, preset, symbol, field, answers, text);
            } catch (ArithmeticException error)
            {
                throw new IllegalArgumentException("ui.input.precision");
            }
        }, value -> {
        }).thenCompose(value -> {
            if (value.isEmpty())
            {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            answers.add(value.get());
            return step(gui, action, preset, symbol, fields, index + 1, answers);
        });
    }

    private static String hint(TradingGui gui, String action, String preset, SymbolView symbol, String field, List<String> answers)
    {
        if (Set.of("base", "quote", "asset").contains(field))
        {
            String examples = GameServices.get().assets.all().stream().limit(2)
                                                       .map(asset -> asset.name() + " (" + asset.displayName() + ")").reduce((a, b) -> a + ", " + b).orElse("");
            return GuiWidgets.text(gui, "ui.form.assets", examples);
        }
        if (Set.of("buyEnabled", "sellEnabled", "enabled").contains(field))
        {
            return GuiWidgets.text(gui, "ui.form.boolean");
        }
        if (field.equals("marketType"))
        {
            return GuiWidgets.text(gui, "ui.form.market_types");
        }
        if (field.equals("expiryTimestamp"))
        {
            return GuiWidgets.text(gui, "ui.form.timestamp");
        }
        if (Set.of("feePercent", "makerFeeRate", "takerFeeRate", "fundingRate").contains(field))
        {
            return GuiWidgets.text(gui, "ui.form.hint." + field);
        }
        String unit = "";
        if (action.equals("asset-units") && !field.equals("unitSize"))
        {
            return GuiWidgets.text(gui, "ui.form.new_units", answers.get(1));
        }
        if (field.equals("minimumLots") || field.equals("lotStep"))
        {
            return GuiWidgets.text(gui, "ui.form.lots");
        }
        if (action.startsWith("peg-"))
        {
            if (Set.of("baseAmount", "minimum", "maximum").contains(field) && !answers.isEmpty())
            {
                unit = asset(answers.getFirst()).displayName();
            }
            if (field.equals("quoteAmount") && answers.size() > 1)
            {
                unit = asset(answers.get(1)).displayName();
            }
        }
        else if (symbol != null)
        {
            String baseName = asset(Long.toString(symbol.baseAssetId())).displayName();
            if (Set.of("lotSize", "minQuantity", "quantityStep").contains(field))
            {
                unit = baseName;
            }
            if (field.equals("minOrderNotional"))
            {
                unit = asset(Long.toString(symbol.quoteAssetId())).displayName();
            }
            if (Set.of("initialPrice", "priceTick").contains(field))
            {
                return GuiWidgets.text(gui, "ui.form.price", asset(Long.toString(symbol.quoteAssetId())).displayName(), baseName);
            }
            if (Set.of("minQuantity", "quantityStep").contains(field) && GameServices.get().markets.lotSize(symbol.symbolId()) != 0)
            {
                return GuiWidgets.text(gui, "ui.form.lots");
            }
        }
        else if (action.equals("symbol") && field.equals("initialPrice"))
        {
            return GuiWidgets.text(gui, "ui.form.price", asset(answers.get(3)).displayName(), asset(answers.get(2)).displayName());
        }
        else if (field.equals("amount"))
        {
            unit = asset(action.startsWith("reserve-") ? answers.get(1) : preset).displayName();
        }
        if (!unit.isEmpty())
        {
            return GuiWidgets.text(gui, "ui.form.amount", unit);
        }
        return GuiWidgets.text(gui, "ui.form.hint." + field);
    }

    private static String parse(String action, String preset, SymbolView symbol, String field, List<String> answers, String text)
    {
        text = text.strip();
        if (text.contains("|") && !Set.of("displayName", "marketName").contains(field))
        {
            throw new IllegalArgumentException("ui.form.single_value");
        }
        if (Set.of("base", "quote", "asset").contains(field))
        {
            var selected = asset(text);
            if (field.equals("quote") && selected.name().equals(answers.get(action.equals("symbol") ? 2 : 0)))
            {
                throw new IllegalArgumentException("ui.form.different_assets");
            }
            return selected.name();
        }
        if (Set.of("buyEnabled", "sellEnabled", "enabled").contains(field))
        {
            return booleanValue(text);
        }
        if (field.equals("marketType"))
        {
            return switch (text.toLowerCase(Locale.ROOT))
            {
                case "1", "现货", "spot" -> "spot";
                case "2", "永续", "永续合约", "perpetual" -> "perpetual";
                case "3", "交割", "交割合约", "future" -> "future";
                default -> throw new IllegalArgumentException("ui.form.market_types");
            };
        }
        if (field.equals("username"))
        {
            return CapitalMC.serverUserManager.findByUsername(text).orElseThrow(() -> new IllegalArgumentException("ui.access.unknown_user")).getUsername();
        }
        if (field.equals("operationId"))
        {
            try
            {
                return UUID.fromString(text).toString();
            } catch (IllegalArgumentException error)
            {
                throw new IllegalArgumentException("ui.form.operation_invalid");
            }
        }
        if (field.equals("expiryTimestamp"))
        {
            long timestamp = timestamp(text);
            if (timestamp <= System.currentTimeMillis())
            {
                throw new IllegalArgumentException("ui.form.future_time");
            }
            return Long.toString(timestamp);
        }
        if (field.equals("unitSize"))
        {
            var size = decimal(text);
            new DecimalUnits.Unit(asset(preset).scale(), size);
            return size.toPlainString();
        }
        if (action.equals("asset-units"))
        {
            new DecimalUnits.Unit(asset(preset).scale(), decimal(answers.get(1))).parse(text);
        }
        else if (action.startsWith("peg-") && Set.of("baseAmount", "quoteAmount", "minimum", "maximum").contains(field))
        {
            var selected = asset(answers.get(field.equals("quoteAmount") ? 1 : 0));
            long amount = selected.parse(text);
            if (field.equals("maximum") && amount < selected.parse(answers.get(5)))
            {
                throw new IllegalArgumentException("ui.form.range_invalid");
            }
        }
        else if (Set.of("feePercent", "makerFeeRate", "takerFeeRate", "fundingRate").contains(field))
        {
            BigDecimal value = decimal(text);
            value.movePointRight(4).longValueExact();
            if (!field.equals("fundingRate") && (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) >= 0))
            {
                throw new IllegalArgumentException("ui.form.percent_invalid");
            }
        }
        else if (action.equals("symbol") && field.equals("initialPrice"))
        {
            asset(answers.get(2)).units().parsePrice(text, asset(answers.get(3)).units());
        }
        else if (symbol != null && Set.of("initialPrice", "priceTick").contains(field))
        {
            asset(Long.toString(symbol.baseAssetId())).units().parsePrice(text, asset(Long.toString(symbol.quoteAssetId())).units());
        }
        else if (symbol != null && Set.of("minQuantity", "quantityStep").contains(field))
        {
            GameServices.get().markets.quantityUnits(symbol).parse(text);
        }
        else if (field.equals("minOrderNotional") && !text.equals("0"))
        {
            asset(Long.toString(symbol.quoteAssetId())).parse(text);
        }
        else if (field.equals("amount"))
        {
            asset(action.startsWith("reserve-") ? answers.get(1) : preset).parse(text);
        }
        else if (Set.of("lotSize", "minimumLots", "lotStep").contains(field))
        {
            if (!positiveFraction(text))
            {
                throw new IllegalArgumentException("ui.input.precision");
            }
        }
        return text;
    }

    public static McAssetRegistry.Asset asset(String input)
    {
        var registry = GameServices.get().assets;
        var exact = registry.find(input);
        if (exact.isPresent())
        {
            return exact.get();
        }
        try
        {
            var id = registry.find(Long.parseLong(input));
            if (id.isPresent())
            {
                return id.get();
            }
        } catch (NumberFormatException ignored)
        {
        }
        var matches = registry.all().stream().filter(asset -> asset.displayName().equalsIgnoreCase(input)).toList();
        if (matches.size() != 1)
        {
            throw new IllegalArgumentException("ui.form.asset_invalid");
        }
        return matches.getFirst();
    }

    public static String booleanValue(String input)
    {
        return switch (input.toLowerCase(Locale.ROOT))
        {
            case "1", "是", "开", "开启", "启用", "yes", "true", "on" -> "true";
            case "2", "否", "关", "关闭", "禁用", "no", "false", "off" -> "false";
            default -> throw new IllegalArgumentException("ui.form.boolean");
        };
    }

    private static BigDecimal decimal(String text)
    {
        try
        {
            var value = new BigDecimal(text);
            if (value.precision() > 24 || Math.abs((long) value.scale()) > 18)
            {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException error)
        {
            throw new IllegalArgumentException("ui.input.precision");
        }
    }

    private static boolean positiveFraction(String text)
    {
        String[] parts = text.split("/", -1);
        return parts.length <= 2 && decimal(parts[0]).signum() > 0 && (parts.length == 1 || decimal(parts[1]).signum() > 0);
    }

    private static long timestamp(String text)
    {
        return UtcTime.parse(text);
    }

    private record Change(JsonPrimitive raw, String input)
    {
    }

    private static CompletableFuture<Optional<String[]>> patch(TradingGui gui, String preset, SymbolView symbol, JsonObject object, List<String> path)
    {
        var fields = new ArrayList<>(object.keySet());
        StringBuilder menu = new StringBuilder(GuiWidgets.text(gui, "ui.form.choose_setting"));
        for (int i = 0; i < fields.size(); i++)
        {
            menu.append("\n&e").append(i + 1).append(". &f").append(GuiWidgets.text(gui, "ui.form.field." + fields.get(i)));
        }
        return GuiActions.input(gui, menu.toString(), text -> {
            try
            {
                int selected = Integer.parseInt(text);
                if (selected >= 1 && selected <= fields.size())
                {
                    return selected - 1;
                }
            } catch (NumberFormatException ignored)
            {
            }
            throw new IllegalArgumentException("ui.form.choose_number");
        }, value -> {
        }).thenCompose(choice -> {
            if (choice.isEmpty())
            {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            String field = fields.get(choice.get());
            var value = object.get(field);
            var next = new ArrayList<>(path);
            next.add(field);
            if (value.isJsonObject())
            {
                return patch(gui, preset, symbol, value.getAsJsonObject(), next);
            }
            String unit = patchUnit(gui, symbol, field);
            String question = GuiWidgets.text(gui, "ui.form.setting_value", GuiWidgets.text(gui, "ui.form.field." + field), unit);
            return GuiActions.input(gui, question, text -> {
                try
                {
                    return new Change(patchValue(symbol, field, value.getAsJsonPrimitive(), text), text.strip());
                } catch (ArithmeticException error)
                {
                    throw new IllegalArgumentException("ui.input.precision");
                }
            }, v -> {
            }).thenApply(input -> {
                if (input.isEmpty())
                {
                    return Optional.empty();
                }
                var delta = new JsonObject();
                var current = delta;
                for (int i = 0; i < next.size() - 1; i++)
                {
                    var nested = new JsonObject();
                    current.add(next.get(i), nested);
                    current = nested;
                }
                current.add(field, input.get().raw());
                GuiActions.message(gui, "ui.form.change", GuiWidgets.text(gui, "ui.form.field." + field), input.get().input());
                return Optional.of(new String[]{preset, delta.toString()});
            });
        });
    }

    public static String patchUnit(TradingGui gui, SymbolView symbol, String field)
    {
        if (field.endsWith("Timestamp"))
        {
            return GuiWidgets.text(gui, "ui.form.timestamp");
        }
        if (field.equals("fundingInterval") || field.equals("maxAge"))
        {
            return GuiWidgets.text(gui, "ui.form.duration");
        }
        if (field.endsWith("Rate"))
        {
            return GuiWidgets.text(gui, "ui.form.percent");
        }
        if (Set.of("priceTick").contains(field))
        {
            return GuiWidgets.text(gui, "ui.form.price", asset(Long.toString(symbol.quoteAssetId())).displayName(), asset(Long.toString(symbol.baseAssetId())).displayName());
        }
        if (field.toLowerCase(Locale.ROOT).contains("notional"))
        {
            return GuiWidgets.text(gui, "ui.form.amount", asset(Long.toString(symbol.quoteAssetId())).displayName());
        }
        if (field.equals("quantityStep") || field.equals("minQuantity") || field.equals("maxOrderQuantity"))
        {
            return GameServices.get().markets.lotSize(symbol.symbolId()) == 0
                    ? GuiWidgets.text(gui, "ui.form.amount", asset(Long.toString(symbol.baseAssetId())).displayName()) : GuiWidgets.text(gui, "ui.form.lots");
        }
        return GuiWidgets.text(gui, "ui.form.hint." + field);
    }

    public static JsonPrimitive patchValue(SymbolView symbol, String field, JsonPrimitive previous, String text)
    {
        if (previous.isBoolean())
        {
            return new JsonPrimitive(Boolean.parseBoolean(booleanValue(text)));
        }
        if (previous.isString())
        {
            var options = SelfTradePrevention.values();
            for (int i = 0; i < options.length; i++)
            {
                if (options[i].name().equalsIgnoreCase(text) || text.equals(Integer.toString(i + 1)))
                {
                    return new JsonPrimitive(options[i].name());
                }
            }
            throw new IllegalArgumentException("ui.form.hint.selfTradePrevention");
        }
        if (field.endsWith("Timestamp"))
        {
            return new JsonPrimitive(field.equals("expiryTimestamp") ? UtcTime.future(text) : text.equals("0") ? 0 : timestamp(text));
        }
        if (field.equals("fundingInterval") || field.equals("maxAge"))
        {
            var match = Pattern.compile("([0-9]+)\\s*(ms|s|m|h|d)", Pattern.CASE_INSENSITIVE).matcher(text);
            if (!match.matches())
            {
                throw new IllegalArgumentException("ui.form.duration");
            }
            long factor = switch (match.group(2).toLowerCase(Locale.ROOT))
            {
                case "s" -> 1000;
                case "m" -> 60_000;
                case "h" -> 3_600_000;
                case "d" -> 86_400_000;
                default -> 1;
            };
            return new JsonPrimitive(Math.multiplyExact(Long.parseLong(match.group(1)), factor));
        }
        if (field.endsWith("Rate"))
        {
            return new JsonPrimitive(decimal(text).movePointRight(4).longValueExact());
        }
        if (field.equals("priceTick"))
        {
            return new JsonPrimitive(asset(Long.toString(symbol.baseAssetId())).units().parsePrice(text, asset(Long.toString(symbol.quoteAssetId())).units()));
        }
        if (field.equals("maxOrderQuantity") && text.equals("0"))
        {
            return new JsonPrimitive(0);
        }
        if (field.equals("quantityStep") || field.equals("minQuantity") || field.equals("maxOrderQuantity"))
        {
            return new JsonPrimitive(GameServices.get().markets.quantityUnits(symbol).parse(text));
        }
        if (field.toLowerCase(Locale.ROOT).contains("notional"))
        {
            return new JsonPrimitive(text.equals("0") ? 0 : asset(Long.toString(symbol.quoteAssetId())).parse(text));
        }
        try
        {
            long value = Long.parseLong(text);
            if (value < 0)
            {
                throw new NumberFormatException();
            }
            return new JsonPrimitive(value);
        } catch (NumberFormatException error)
        {
            throw new IllegalArgumentException("gui.capitalmc.input.positive_integer");
        }
    }
}
