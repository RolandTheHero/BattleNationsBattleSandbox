package hero.roland.bnsim;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

public class StatusEffect {
    private static Map<String, StatusEffect> statusEffects = new HashMap<>();

    private int duration; // How many turns the effect lasts
    private double durationDamageMultiplier; // Every turn, the damage of the effect is multiplied by this
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

    public static void load() throws IOException {
		try {
			loadJson("StatusEffectsConfig.json");
		} catch (JSONException e) {
			throw new IllegalArgumentException("Json type error", e);
		}
	}
    public static void loadJson(String filename) throws IOException {
        JSONObject json = GameFiles.readJson(filename);
        for (String key : json.keySet()) {
            statusEffects.put(key, new StatusEffect(json.getJSONObject(key)));
        }
    }
    public static StatusEffect get(String id) {
        return statusEffects.get(id);
    }

    private StatusEffect(JSONObject stats) {
        duration = stats.optInt("duration", 1);
        boolean diminishing = stats.optBoolean("dot_Diminishing", true);
        if (!diminishing) {
            durationDamageMultiplier = 1.0;
        } else {
            durationDamageMultiplier = stats.optDouble("dot_AbilityDamageMult", 0.5);
        }
        String damageTypeStr = stats.optString("dot_DamageType", null);
        if (damageTypeStr != null) {
            damageType = Ability.DamageType.fromString(damageTypeStr);
        }
        family = StatusFamily.get(stats.optString("family", null));
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

    public static class StatusFamily {
        private static Map<String, StatusFamily> families = new HashMap<>();

        public static void load() throws IOException {
            JSONObject json = GameFiles.readJson("StatusEffectFamiliesConfig.json");
            for (String key : json.keySet()) {
                families.put(key, new StatusFamily(json.getJSONObject(key)));
            }
        }
        public static StatusFamily get(String id) { return families.get(id); }

        private String colorHex; // Colour of the pulse for the afflicted unit in hex code (without hashtag)
        private String displayName; // Name of the effect
        private String effectIcon; // Name of the icon file for the effect
        private double pulseSpeed; // How fast the colour of the afflicted unit pulses in seconds
        private String sound; // Name of the sound file
        private String uiIcon; // Name of the icon file

        private StatusFamily(JSONObject json) {
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
