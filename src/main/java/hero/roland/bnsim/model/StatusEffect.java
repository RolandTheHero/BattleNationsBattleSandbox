package hero.roland.bnsim.model;

import java.util.HashMap;
import java.util.Map;

import hero.roland.bnsim.gamefiles.GameFiles;

public class StatusEffect {
    protected int duration; // How many turns the effect lasts. The effect is removed after this amount of turns has passed
    private boolean diminishing; // If true, the damage of the effect is multiplied by 0.5^(n-1) where n is the number of turns this effect has lasted
    private double abilityDamageMultiplier; // The starting damage of this status effect is this number multiplied by the damage done to the target by the ability
    private double armorPiercingRate; // Proportion of effect's damage goes directly to HP
    protected Ability.DamageType damageType; // Type of damage for resistance calculations
    private int bonusDamage; // Flat damage added to each tick of the effect, after all multipliers
    protected StatusFamily family;

    // Stuns/Freezes
    private boolean blockAction; // If true, afflicted unit cannot use abilities
    private boolean blockMovement; // If true, afflicted unit's cooldown and reload timers are frozen
    private boolean damageBreak; // Ignore for now
    private Map<Ability.DamageType, Double> damageMods = new HashMap<>(); // Each damage type in the map replaces the HP resistances for that type of the afflicted unit for the duration of this effect. If the unit is afflicted by multiple status effects that affect the same damage type, the higher one applies
    private Map<Ability.DamageType, Double> armorDamageMods = new HashMap<>(); // Same as above but for armor.

    // Suppression only
    protected int offenseDown = 0; // Flat reduction to the unit's offense stat for the duration of this effect

    /** The status effect with the given id from the active bundle, or {@code null}. */
    public static StatusEffect get(String id) {
        return GameFiles.active().getStatusEffect(id);
    }

    /**
     * A status effect's data as parsed from the game files. A {@link GameFiles}
     * loader fills one in and passes it to {@link StatusEffect#StatusEffect(Definition)};
     * see the matching fields of {@link StatusEffect} for what each value means.
     */
    public static class Definition {
        public int duration = 1;
        public boolean diminishing = true;
        public double abilityDamageMultiplier;
        public double armorPiercingRate;
        /** {@code null} for an effect that deals no damage. */
        public Ability.DamageType damageType;
        public int bonusDamage;
        public StatusFamily family;
        public boolean blockAction, blockMovement, damageBreak;
        public Map<Ability.DamageType, Double> damageMods = new HashMap<>();
        public Map<Ability.DamageType, Double> armorDamageMods = new HashMap<>();
    }

    /** Builds a status effect from its parsed data. */
    public StatusEffect(Definition def) {
        duration = def.duration;
        diminishing = def.diminishing;
        abilityDamageMultiplier = def.abilityDamageMultiplier;
        armorPiercingRate = def.armorPiercingRate;
        damageType = def.damageType;
        bonusDamage = def.bonusDamage;
        family = def.family;
        blockAction = def.blockAction;
        blockMovement = def.blockMovement;
        damageBreak = def.damageBreak;
        damageMods.putAll(def.damageMods);
        armorDamageMods.putAll(def.armorDamageMods);
    }
    protected StatusEffect() {}

    public int getDuration() { return duration; }
    public boolean isDiminishing() { return diminishing; }
    public double getAbilityDamageMultiplier() { return abilityDamageMultiplier; }
    public double getArmorPiercingRate() { return armorPiercingRate; }
    public Ability.DamageType getDamageType() { return damageType; }
    public int getBonusDamage() { return bonusDamage; }
    public StatusFamily getFamily() { return family; }
    public boolean isBlockAction() { return blockAction; }
    public boolean isBlockMovement() { return blockMovement; }
    public int getOffenseDown() { return offenseDown; }
    /** This effect's HP resistance override for the given damage type, or
     * {@code null} if it does not change resistance for that type. */
    public Double getDamageMod(Ability.DamageType type) { return damageMods.get(type); }

    /** This effect's armor resistance override for the given damage type, or
     * {@code null} if it does not change resistance for that type. */
    public Double getArmorDamageMod(Ability.DamageType type) { return armorDamageMods.get(type); }

    public static class Suppression extends StatusEffect {
        public Suppression() {
            duration = 2;
            family = new SuppressionFamily();
            offenseDown = 20;
        }
    }

    public static class StatusFamily {
        /** The status-effect family with the given id from the active bundle, or {@code null}. */
        // public static StatusFamily get(String id) {
        //     return GameFiles.active().getStatusFamily(id);
        // }

        protected String colorHex; // Colour of the pulse for the afflicted unit in hex code (without hashtag)
        protected String displayName; // Name of the effect
        protected String effectIcon; // Name of the icon file for the effect
        protected double pulseSpeed; // How fast the colour of the afflicted unit pulses in seconds
        protected String sound; // Name of the sound file
        protected String uiIcon; // Name of the icon file

        public StatusFamily(String colorHex, String displayName, String effectIcon,
                double pulseSpeed, String sound, String uiIcon) {
            this.colorHex = colorHex;
            this.displayName = displayName;
            this.effectIcon = effectIcon;
            this.pulseSpeed = pulseSpeed;
            this.sound = sound;
            this.uiIcon = uiIcon;
        }
        protected StatusFamily() {}

        public String getColorHex() { return colorHex; }
        public String getDisplayName() { return displayName; }
        public String getEffectIcon() { return effectIcon; }
        public double getPulseSpeed() { return pulseSpeed; }
        public String getSound() { return sound; }
        public String getUiIcon() { return uiIcon; }
        public String toString() { return displayName; }
    }
    public static class SuppressionFamily extends StatusFamily {
        public SuppressionFamily() {
            colorHex = "#000000";
            displayName = "seSuppression";
            effectIcon = "unitStat_suppress_icon@2x.png";
            pulseSpeed = 1d;
            sound = null;
            uiIcon = "unitStat_suppress_icon@2x.png";
        }
    }
}
