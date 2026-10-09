package nhcm.capitalmc.exchange;

import nhcm.advmatch.command.read.ReadSymbol;
import nhcm.advmatch.model.*;
import nhcm.advmatch.object.Symbol;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class SymbolUtil {
    private SymbolUtil() {}
    public static CompletableFuture<Optional<SymbolView>> find(long id) {
        QueryUtil.positive(id);
        return QueryUtil.exchange().submit(new ReadSymbol(new ObservationQuery().withSymbolId(id))).thenApply(QueryUtil::one);
    }
    public static CompletableFuture<Boolean> exists(long id) { return find(id).thenApply(Optional::isPresent); }
    public static CompletableFuture<Symbol> getSymbol(long id) {
        QueryUtil.positive(id);
        return QueryUtil.exchange().symbols().find(id);
    }
    public static CompletableFuture<List<SymbolView>> allSymbols() {
        return QueryUtil.all(query -> QueryUtil.exchange().symbols().list(query.withIncludeInactive(true)));
    }
    public static CompletableFuture<List<SymbolView>> findByName(String name) {
        Objects.requireNonNull(name, "name");
        return allSymbols().thenApply(values -> values.stream().filter(value -> name.equals(value.name())).toList());
    }
}
