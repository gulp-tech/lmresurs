package ru.voidpe.items;

import org.bukkit.Material;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.Map;

/** Один кастомный предмет из config.yml. */
public final class CustomItem {

    public enum Kind { TRAP, DUST, DISORIENT, LOM, POTION, ARMOR, GEAR }

    public record EffectDef(PotionEffectType type, int amplifier, int durationSeconds) {}
    public record EnchantDef(String key, int level) {}
    public record RecipeDef(boolean enabled, List<String> shape, Map<Character, Material> ingredients) {}

    private final String id;
    private final Kind kind;
    private final boolean enabled;
    private final String displayName;
    private final Material material;
    private final List<String> lore;
    private final boolean glow;
    private final RecipeDef recipe;
    private final List<EnchantDef> enchants;
    private final String trimMaterial;
    private final String trimPattern;
    private final org.bukkit.configuration.ConfigurationSection section;

    public CustomItem(String id, Kind kind, boolean enabled, String displayName, Material material,
                      List<String> lore, boolean glow, RecipeDef recipe,
                      List<EnchantDef> enchants, String trimMaterial, String trimPattern,
                      org.bukkit.configuration.ConfigurationSection section) {
        this.id = id;
        this.kind = kind;
        this.enabled = enabled;
        this.displayName = displayName;
        this.material = material;
        this.lore = lore;
        this.glow = glow;
        this.recipe = recipe;
        this.enchants = enchants;
        this.trimMaterial = trimMaterial;
        this.trimPattern = trimPattern;
        this.section = section;
    }

    public String id() { return id; }
    public Kind kind() { return kind; }
    public boolean enabled() { return enabled; }
    public String displayName() { return displayName; }
    public Material material() { return material; }
    public List<String> lore() { return lore; }
    public boolean glow() { return glow; }
    public RecipeDef recipe() { return recipe; }
    public List<EnchantDef> enchants() { return enchants; }
    public String trimMaterial() { return trimMaterial; }
    public String trimPattern() { return trimPattern; }

    /** Значение из секции предмета в конфиге (radius, cooldown-seconds и т.д.). */
    public double num(String key, double def) {
        return section == null ? def : section.getDouble(key, def);
    }

    public String str(String key, String def) {
        return section == null ? def : section.getString(key, def);
    }

    public boolean bool(String key, boolean def) {
        return section == null ? def : section.getBoolean(key, def);
    }

    public List<EffectDef> effects(String key) {
        java.util.List<EffectDef> result = new java.util.ArrayList<>();
        if (section == null) {
            return result;
        }
        for (Map<?, ?> raw : section.getMapList(key)) {
            String typeName = String.valueOf(raw.get("type"));
            PotionEffectType type = ItemsService.potionType(typeName);
            if (type == null) {
                continue;
            }
            Object ampRaw = raw.get("amplifier");
            Object durRaw = raw.get("duration");
            int amp = ampRaw instanceof Number ? ((Number) ampRaw).intValue() : 0;
            int dur = durRaw instanceof Number ? ((Number) durRaw).intValue() : 60;
            result.add(new EffectDef(type, amp, dur));
        }
        return result;
    }
}
