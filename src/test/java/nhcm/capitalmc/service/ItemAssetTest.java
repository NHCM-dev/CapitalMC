package nhcm.capitalmc.service;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.*;
import net.minecraft.core.registries.*;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.enchantment.*;
import nhcm.capitalmc.account.ServerUser;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ItemAssetTest
{
    private static HolderLookup.Provider lookup;

    @BeforeAll
    static void bootstrap()
    {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        lookup = VanillaRegistries.createWorldLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(lookup).forEach(DataComponentInitializers.PendingComponents::apply);
    }

    @Test
    void withdrawalKeepsProtectionFourAndAllowsSixtyFourBooksThroughSerialization()
    {
        var protection = lookup.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION);
        var enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        enchantments.set(protection, 4);
        var original = new ItemStack(Items.ENCHANTED_BOOK);
        original.set(DataComponents.STORED_ENCHANTMENTS, enchantments.toImmutable());
        var asset = new McAssetRegistry.Asset(1, "protection4", "Protection IV", 0, false, original);
        var withdrawal = asset.stack(64);
        assertEquals(64, withdrawal.getCount());
        assertEquals(64, withdrawal.getMaxStackSize());
        assertEquals(4, withdrawal.get(DataComponents.STORED_ENCHANTMENTS).getLevel(protection));
        var ops = lookup.createSerializationContext(JsonOps.INSTANCE);
        var saved = ItemStack.CODEC.encodeStart(ops, withdrawal).getOrThrow();
        var restored = ItemStack.CODEC.parse(ops, saved).getOrThrow();
        assertEquals(64, restored.getCount());
        assertEquals(64, restored.getMaxStackSize());
        assertEquals(4, restored.get(DataComponents.STORED_ENCHANTMENTS).getLevel(protection));
        assertEquals(1, original.getCount());
        assertEquals(1, original.getMaxStackSize());
        withdrawal.set(DataComponents.CUSTOM_NAME, Component.literal("changed"));
        assertFalse(asset.prototype().has(DataComponents.CUSTOM_NAME));
    }

    @Test
    void electronicAssetHasAnIconButCannotCreatePhysicalCurrency()
    {
        var asset = new McAssetRegistry.Asset(2, "cash", "Cash", 2, true, new ItemStack(Items.PAPER));
        assertEquals(Items.PAPER, asset.prototype().getItem());
        assertEquals("1.25", asset.format(125));
        assertThrows(IllegalStateException.class, () -> asset.stack(64));
    }

    @Test
    void matchingRejectsDamageAndExtraComponentsButNormalizesCountAndWithdrawalStackLimit()
    {
        var template = new ItemStack(Items.DIAMOND_SWORD);
        var damaged = template.copy();
        damaged.setDamageValue(1);
        assertFalse(McAssetRegistry.matches(template, damaged));
        var renamed = template.copy();
        renamed.set(DataComponents.CUSTOM_NAME, Component.literal("different"));
        assertFalse(McAssetRegistry.matches(template, renamed));
        var larger = template.copyWithCount(64);
        larger.set(DataComponents.MAX_STACK_SIZE, 64);
        assertTrue(McAssetRegistry.matches(template, larger));
        assertFalse(McAssetRegistry.matches(template, ItemStack.EMPTY));
    }

    @Test
    void matchingRejectsAChangedEnchantmentAndDepositRejectsHiddenContainerContents()
    {
        var protection = lookup.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION);
        var enchants = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        enchants.set(protection, 4);
        var template = new ItemStack(Items.ENCHANTED_BOOK);
        template.set(DataComponents.STORED_ENCHANTMENTS, enchants.toImmutable());
        var altered = template.copy();
        var wrongEnchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        wrongEnchantments.set(protection, 5);
        altered.set(DataComponents.STORED_ENCHANTMENTS, wrongEnchantments.toImmutable());
        assertFalse(McAssetRegistry.matches(template, altered));
        assertTrue(McAssetRegistry.matches(template, template.copyWithCount(64)));
        var nested = new ItemStack(Items.SHULKER_BOX);
        nested.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND))));
        assertThrows(IllegalArgumentException.class, () -> McAssetRegistry.validateDepositStack(nested));
    }

    @Test
    void creatorProfileSnapshotKeepsItsIdentityAndTextureWhenTurnedIntoAnAccountHead()
    {
        UUID creator = UUID.randomUUID();
        var texture = new Property("textures", "creator-texture", "signature");
        var properties = new PropertyMap(ImmutableMultimap.of("textures", texture));
        var profile = ResolvableProfile.createResolved(new GameProfile(creator, "Creator", properties));
        var encoded = ResolvableProfile.CODEC.encodeStart(JsonOps.INSTANCE, profile).getOrThrow().getAsJsonObject();
        var user = new ServerUser("Account", "account", "", UUID.randomUUID(), creator).withProfile(encoded);
        encoded.addProperty("name", "Visitor");
        var restored = ResolvableProfile.CODEC.parse(JsonOps.INSTANCE, user.getProfile().orElseThrow()).getOrThrow();
        var head = new ItemStack(Items.PLAYER_HEAD);
        head.set(DataComponents.PROFILE, restored);
        var saved = ItemStack.CODEC.encodeStart(lookup.createSerializationContext(JsonOps.INSTANCE), head).getOrThrow();
        var decoded = ItemStack.CODEC.parse(lookup.createSerializationContext(JsonOps.INSTANCE), saved).getOrThrow();
        var accountProfile = decoded.get(DataComponents.PROFILE).partialProfile();
        assertEquals(creator, accountProfile.id());
        assertEquals("Creator", accountProfile.name());
        assertEquals(texture, accountProfile.properties().get("textures").iterator().next());
    }
}
