package hero.roland.bnsim.model;

import java.util.HashMap;
import java.util.Map;

import org.json.JSONObject;

import hero.roland.bnsim.gamefiles.GameFiles;

public class StatusEffect {

    private int duration; // How many turns the effect lasts. The effect is removed after this amount of turns has passed
    private boolean diminishing; // If true, the damage of the effect is multiplied by 0.5^(n-1) where n is the number of turns this effect has lasted
    private double abilityDamageMultiplier; // The starting damage of this status effect is this number multiplied by the damage done to the target by the ability
    private double armorPiercingRate; // Proportion of effect's damage goes directly to HP
    private Ability.DamageType damageType; // Type of damage for resistance calculations
    private int bonusDamage; // Flat damage added to each tick of the effect, after all multipliers
    private StatusFamily family;

    // Stuns/Freezes
    private boolean blockAction; // If true, afflicted unit cannot use abilities
    private boolean blockMovement; // If true, afflicted unit's cooldown and reload timers are frozen
    private boolean damageBreak; // Ignore for now
    private Map<Ability.DamageType, Double> damageMods = new HashMap<>(); // Each damage type in the map replaces the HP resistances for that type of the afflicted unit for the duration of this effect. If the unit is afflicted by multiple status effects that affect the same damage type, the higher one applies
    private Map<Ability.DamageType, Double> armorDamageMods = new HashMap<>(); // Same as above but for armor.

    /** The status effect with the given id from the active bundle, or {@code null}. */
    public static StatusEffect get(String id) {
        return GameFiles.active().getStatusEffect(id);
    }

    public StatusEffect(GameFiles gf, JSONObject stats) {
        duration = stats.optInt("duration", 1);
        diminishing = stats.optBoolean("dot_Diminishing", true);
        abilityDamageMultiplier = stats.optDouble("dot_AbilityDamageMult", 0d);
        diminishing = stats.optBoolean("dot_Diminishing", true);
        String damageTypeStr = stats.optString("dot_DamageType", null);
        if (damageTypeStr != null) {
            damageType = Ability.DamageType.fromString(damageTypeStr);
        }
        family = gf.getStatusFamily(stats.optString("family", null));
        armorPiercingRate = stats.optDouble("dot_apPercent", 0);
        bonusDamage = stats.optInt("dot_BonusDamage", 0);

        // Stun/Freeze
        blockAction = stats.optBoolean("stun_BlockAction", false);
        blockMovement = stats.optBoolean("stun_BlockMovement", false);
        damageBreak = stats.optBoolean("stun_DamageBreak", false);
        JSONObject stunDamageModsJson = stats.optJSONObject("stun_DamageMods");
        if (stunDamageModsJson != null) {
            for (String key : stunDamageModsJson.keySet()) {
                damageMods.put(Ability.DamageType.fromString(key), stunDamageModsJson.getDouble(key));
            }
        }
        JSONObject armorDamageModsJson = stats.optJSONObject("stun_ArmorDamageMods");
        if (armorDamageModsJson != null) {
            for (String key : armorDamageModsJson.keySet()) {
                armorDamageMods.put(Ability.DamageType.fromString(key), armorDamageModsJson.getDouble(key));
            }
        }
    }

    public int getDuration() { return duration; }
    public boolean isDiminishing() { return diminishing; }
    public double getAbilityDamageMultiplier() { return abilityDamageMultiplier; }
    public double getArmorPiercingRate() { return armorPiercingRate; }
    public Ability.DamageType getDamageType() { return damageType; }
    public int getBonusDamage() { return bonusDamage; }
    public StatusFamily getFamily() { return family; }
    public boolean isBlockAction() { return blockAction; }
    public boolean isBlockMovement() { return blockMovement; }

    /** This effect's HP resistance override for the given damage type, or
     * {@code null} if it does not change resistance for that type. */
    public Double getDamageMod(Ability.DamageType type) { return damageMods.get(type); }

    /** This effect's armor resistance override for the given damage type, or
     * {@code null} if it does not change resistance for that type. */
    public Double getArmorDamageMod(Ability.DamageType type) { return armorDamageMods.get(type); }

    public static class StatusFamily {

        /** The status-effect family with the given id from the active bundle, or {@code null}. */
        public static StatusFamily get(String id) {
            return GameFiles.active().getStatusFamily(id);
        }

        private String colorHex; // Colour of the pulse for the afflicted unit in hex code (without hashtag)
        private String displayName; // Name of the effect
        private String effectIcon; // Name of the icon file for the effect
        private double pulseSpeed; // How fast the colour of the afflicted unit pulses in seconds
        private String sound; // Name of the sound file
        private String uiIcon; // Name of the icon file

        public StatusFamily(JSONObject json) {
            colorHex = json.optString("colorHex", "#FFFFFF");
            displayName = json.optString("displayName", "seUnknown");
            effectIcon = json.optString("effectIcon", "suppressor_firemod_icon") + "@2x.png";
            pulseSpeed = json.optDouble("pulseSpeed", 1d);
            sound = json.optString("sound", null);
            uiIcon = json.optString("uiIcon", "BN_iconFireMod") + "@2x.png";
        }

        public String getColorHex() { return colorHex; }
        public String getDisplayName() { return displayName; }
        public String getEffectIcon() { return effectIcon; }
        public double getPulseSpeed() { return pulseSpeed; }
        public String getSound() { return sound; }
        public String getUiIcon() { return uiIcon; }
    }
}
