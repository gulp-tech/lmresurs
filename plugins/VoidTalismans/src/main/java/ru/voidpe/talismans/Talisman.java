package ru.voidpe.talismans;

import org.bukkit.Material;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.potion.PotionEffectType;

import java.util.List;

/** Один талисман из config.yml. Как на FunTime: лежит в инвентаре — даёт плюсы и минусы к характеристикам. */
public final class Talisman {

    public enum Where { INVENTORY, HOTBAR, OFFHAND }

    public record EffectDef(PotionEffectType type, int amplifier) {}
    public record AttributeDef(String attributeKey, double amount, AttributeModifier.Operation operation) {}
    public record RecipeDef(boolean enabled, List<String> shape, java.util.Map<Character, Material> ingredients) {}

    private final String id;
    private final boolean enabled;
    private final String displayName;
    private final Material material;
    private final List<String> lore;
    private final boolean glow;
    private final Where where;
    private final String permission;
    private final List<EffectDef> effects;
    private final List<AttributeDef> attributes;
    private final RecipeDef recipe;
    private final int customModelData;
    private final String itemModel;

    public Talisman(String id, boolean enabled, String displayName, Material material, List<String> lore, boolean glow,
                    Where where, String permission, List<EffectDef> effects,
                    List<AttributeDef> attributes, RecipeDef recipe, int customModelData, String itemModel) {
        this.id = id;
        this.enabled = enabled;
        this.displayName = displayName;
        this.material = material;
        this.lore = lore;
        this.glow = glow;
        this.where = where;
        this.permission = permission;
        this.effects = effects;
        this.attributes = attributes;
        this.recipe = recipe;
        this.customModelData = customModelData;
        this.itemModel = itemModel;
    }

    public String id() { return id; }
    public boolean enabled() { return enabled; }
    public String displayName() { return displayName; }
    public Material material() { return material; }
    public List<String> lore() { return lore; }
    public boolean glow() { return glow; }
    public Where where() { return where; }
    public String permission() { return permission; }
    public List<EffectDef> effects() { return effects; }
    public List<AttributeDef> attributes() { return attributes; }
    public RecipeDef recipe() { return recipe; }
    public int customModelData() { return customModelData; }
    public String itemModel() { return itemModel; }

    public boolean hasPermission(org.bukkit.entity.Player player) {
        return permission == null || permission.isBlank() || player.hasPermission(permission);
    }
}
