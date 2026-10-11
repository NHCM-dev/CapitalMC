# CapitalMC development instructions

Read [the project requirements](docs/development-requirements.md) and the relevant existing code before implementing a feature. The document describes the target platform; check [the implementation status](docs/refactor-status.md) before assuming a target capability already exists.

- Evolve the existing ServerUserManager, GameServices, McAssetRegistry, ItemBankService, SettlementService, CurrencyService, GUI pages and command framework. Do not create parallel account, balance or trading frameworks.
- AdvMatch owns assets, users, balances, reservations, orders, trades, positions, risk and matching. CapitalMC owns Minecraft identity/session metadata, item bindings, physical custody operations, container eligibility and application audit. Store IDs and query immutable views for financial state.
- Inventory, ItemStack mutation/serialization with live registries, entities, containers, worlds and SGui belong to the Minecraft server thread. Never wait for a future on that thread. Hash credentials on ApplicationExecutors.CREDENTIALS; persist metadata on ApplicationExecutors.IO; marshal Minecraft completions through GameServices.onServer.
- General text input uses intercepted chat, not SignGui. Consume input before vanilla broadcast/logging; never echo or audit credentials. Empty input/cancel cancels; empty credentials are supported separately. Validate page, player and account context on asynchronous completion.
- Select drops before vanilla inventory removal/entity spawn. Deposit previews must be confirmed and revalidated against server inventory or container snapshots before custody. Preserve operation IDs, durable request IDs, phases and recovery evidence. Unknown results remain quarantined; never silently retry or guess about delivery.
- Minecraft world saves and AdvMatch WAL are separate stores. State each durability boundary and failure/recovery behavior. Do not claim atomic multi-asset custody unless the native API actually provides it.
- Use exact integer monetary units and asset/symbol scales; decimal input requires exact conversion. Centralize item matching in McAssetRegistry, including components and damage. New UI text needs en_us and zh_cn keys.
- Preserve existing data and unrelated local changes. Business data uses binary WAL and state snapshots; editable configuration may use JSON. Keep read-only migration for users.json version 1 and users.dat. Legacy services.json requires its adjacent services.json.wal for import and backups. See documentation/persistence.en.md for store pins and recovery boundaries.
- For a large change first explain reusable code, requirement interpretation, ownership/model, phases, threading, persistence/recovery, API changes and files. Test important business behavior and report untested Minecraft interactions clearly.

Build with JDK 25 using `./gradlew.bat build`. Minecraft 26.3, Fabric and the bundled AdvMatch Java API are the current integration targets.

## Code style

- Follow the user's existing Java style and IDEA formatting preferences. The locally inspected IntelliJIdea2026.2 `codestyles/Default.xml` uses next-line braces for classes, methods and control blocks, `else` on a new line, and alignment for multiline calls, chains and binary expressions. Existing `TradeSpotPage` code demonstrates the user's next-line brace style. Prefer project-specific settings if added later.
- Do not default to `final class`, final methods or private constructors. Use them only for a concrete invariant or access requirement, not as boilerplate for every helper or service. Preserve meaningful immutable fields and constants.
- Keep code readable: one statement per line, normal spaces around operators and after commas, and blank lines between logical sections. Avoid both compressed blocks and unnecessary line breaks between every argument or expression.
- Prefer straightforward control flow, descriptive local variables and small named methods. Avoid dense nested lambdas, clever syntax, and long asynchronous chains when intermediate steps can explain the operation more clearly. Use `var` where the type is obvious; otherwise write the type.
- Use imports instead of fully qualified class names in declarations and method bodies. Keep qualification only when needed to disambiguate conflicting type names or for a concrete language/API requirement.
- Match nearby user-authored code when choosing naming, method structure and formatting. Apply this style to new or touched code; do not reformat unrelated files wholesale.

## UI wording

- Player and administrator UI text must describe actual functions, current state and necessary actions. Do not repeat prompts, layout instructions, implementation rationale or claims about removed decorations. Empty, loading and failed states must be distinct; only actionable controls should instruct users to click. Apply this to both en_us and zh_cn.
- Keep the gray glass separator below top navigation on pages that list variable assets, pairs, users or records. Fixed settings layouts may use that space for controls.
- All Administration tabs, including status and record details, keep their top gray glass separator.
- The Exchange settings global management tab also keeps its top gray glass separator; place its controls below the separator.
