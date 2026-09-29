/*
 * Battle Nations Battle Sandbox
 *
 * Adapted from Battle Nations Animation Grabber (BaNG),
 * https://github.com/bobmath/BattleNationsAnimation
 * Copyright (C) 2014 Robert Mathews. Licensed under the GNU General Public
 * License version 2; see the LICENSE file.
 *
 * Modified 2026 by RolandTheHero; the git history records each change and
 * its date.
 */

package hero.roland.bnsim.model;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import hero.roland.bnsim.gamefiles.GameFiles;
import hero.roland.bnsim.model.Unit.UnitTag;

public class Ability {
	public static final int LOF_CONTACT = 0, LOF_DIRECT = 1,
			LOF_PRECISE = 2, LOF_INDIRECT = 3;

	public static final Ability NO_ABILITY = new Ability();

	/** The bundle this ability was loaded from ({@code null} for {@link #NO_ABILITY}). */
	private GameFiles gf;

	private String tag, nameId;
	private String icon;
	private String frontAnimationName, backAnimationName;
	private double damageFromWeapon, damageFromUnit;
    private double armorPiercingRate;
	private int damageBonus;
	/** Shots resolved within one attack; an ability fires {@link #attacksPerUse}
	 * such attacks in sequence per use. */
	private int shotsPerAttack, attacksPerUse;
	private int minRange, maxRange;
	private int lineOfFire;
	private boolean capture;
	private int aoeDelay;
	private TargetType targetType;
    private String infantryHitSound, vehicleHitSound;
    private DamageType damageType;
    private AttackDirection attackDirection;
	private boolean randomTarget;
	private TargetSquare[] targetArea, damageArea;
	private StatusEffectChance[] statusEffects;
	private double baseCritical;
	/** Extra critical chance (0-1) added when the target has the keyed unit type. */
	private Map<UnitTag, Double> criticalBonuses;
	//private Map<String, Prerequisites> prereqs;
	private int cooldown, globalCooldown, ammoRequired, prepTime;
	private Set<UnitTag> targetableTags;
	private int attack; // Base offense stat, added to unit's rank accuracy for total offense
	private double secondaryDamageRatio; // Multiplier to damage when grazed
	private double damageDistraction;
	private int damageDistractionBonus;

	/**
	 * An ability's data as parsed from the game files. A {@link GameFiles} loader
	 * fills one in and passes it to {@link Ability#Ability(GameFiles, String, Definition)};
	 * the ability copies the values, so the definition is not retained. Rates and
	 * chances are fractions (0-1), not the percentages the files author them as.
	 */
	public static class Definition {
		public String nameId;
		/** Name of the ability's icon image. */
		public String icon;
		public String frontAnimName, backAnimName;
		public String infantryHitSound, vehicleHitSound;
		public double damageFromWeapon = 1, damageFromUnit = 1;
		public double armorPiercingRate;
		public int damageBonus;
		public int shotsPerAttack = 1, attacksPerUse = 1;
		public int minRange = 1, maxRange = 1;
		/** One of the {@code LOF_*} constants. */
		public int lineOfFire;
		public boolean capture;
		/** Frames (at 20 per second) between successive squares of the target area. */
		public int aoeDelay;
		/** {@code null} when the ability has no target area. */
		public TargetType targetType;
		public DamageType damageType;
		public AttackDirection attackDirection = AttackDirection.FRONT;
		public boolean randomTarget;
		/** {@code null} when the ability has none. */
		public TargetSquare[] targetArea, damageArea;
		public List<StatusEffectChance> statusEffects = new ArrayList<>();
		public double baseCritical;
		/** Extra critical chance added when the target has the keyed unit type. */
		public Map<UnitTag, Double> criticalBonuses = new HashMap<>();
		public int cooldown, globalCooldown, ammoRequired, prepTime;
		/** Empty when the ability may target anything. */
		public Set<UnitTag> targetableTags = new HashSet<>();
		public int attack;
		public double secondaryDamageRatio;
		public double damageDistraction;
		public int damageDistractionBonus;
	}

	private Ability() {
		tag = "none";
		minRange = 1;
		maxRange = 5;
		statusEffects = new StatusEffectChance[0];
	}

	/** Builds an ability from its parsed data. */
	public Ability(GameFiles gf, String tag, Definition def) {
		this.gf = gf;
		this.tag = tag;
		nameId = def.nameId;
		icon = def.icon;
		frontAnimationName = def.frontAnimName;
		backAnimationName = def.backAnimName;
		infantryHitSound = def.infantryHitSound;
		vehicleHitSound = def.vehicleHitSound;
		damageFromWeapon = def.damageFromWeapon;
		damageFromUnit = def.damageFromUnit;
		armorPiercingRate = def.armorPiercingRate;
		damageBonus = def.damageBonus;
		shotsPerAttack = def.shotsPerAttack;
		attacksPerUse = def.attacksPerUse;
		minRange = def.minRange;
		maxRange = def.maxRange;
		lineOfFire = def.lineOfFire;
		capture = def.capture;
		aoeDelay = def.aoeDelay;
		targetType = def.targetType;
		damageType = def.damageType;
		attackDirection = def.attackDirection;
		randomTarget = def.randomTarget;
		targetArea = def.targetArea == null ? null : def.targetArea.clone();
		damageArea = def.damageArea == null ? null : def.damageArea.clone();
		statusEffects = def.statusEffects.toArray(new StatusEffectChance[0]);
		baseCritical = def.baseCritical;
		criticalBonuses = new HashMap<>(def.criticalBonuses);
		cooldown = def.cooldown;
		globalCooldown = def.globalCooldown;
		ammoRequired = def.ammoRequired;
		prepTime = def.prepTime;
		targetableTags = new HashSet<>(def.targetableTags);
		attack = def.attack;
		secondaryDamageRatio = def.secondaryDamageRatio;
		damageDistraction = def.damageDistraction;
		damageDistractionBonus = def.damageDistractionBonus;
	}

	/**
	 * Whether this ability's target list allows it to hit {@code unit}: true when the
	 * unit has at least one of the ability's targetable tags. An ability with no
	 * configured targets (an empty {@link #targetableTags}) is unrestricted and hits
	 * anything. This is the raw target-type rule; whether it is enforced at all is the
	 * caller's decision (the UnitMenu's "Target types" toggle).
	 */
	public boolean canTarget(Unit unit) {
		if (targetableTags == null || targetableTags.isEmpty())
			return true;
		for (UnitTag tag : targetableTags)
			if (unit.hasTag(tag))
				return true;
		return false;
	}

	/**
	 * The unit types this ability is allowed to target, as an unmodifiable view.
	 * Empty when the ability is unrestricted and may target anything (see
	 * {@link #canTarget}).
	 */
	public Set<UnitTag> getTargetableTags() {
		return targetableTags == null
			? Collections.emptySet()
			: Collections.unmodifiableSet(targetableTags);
	}

	/** The ability with the given tag from the active bundle, or {@code null}. */
	public static Ability get(String tag) {
		return GameFiles.active().getAbility(tag);
	}

	public String getTag() {
		return tag;
	}

	public String getName() {
		String name = (gf == null) ? null : gf.getText(nameId);
		if (name != null) return name;
		return "none".equals(tag) ? "(None)" : tag;
	}

	/** Name of this ability's icon image, or {@code null}. */
	public String getIcon() {
		return icon;
	}

	public String toString() {
		return getName();
	}

	public Animation getFrontAnimation() throws IOException {
		return Animation.get(frontAnimationName);
	}

	public Animation getBackAnimation() throws IOException {
		return Animation.get(backAnimationName);
	}

	// public Prerequisites getPrereqs(String unit) {
	// 	return (prereqs == null) ? null : prereqs.get(unit);
	// }

	public int adjustDamage(int damage, int power) {
		return (int) ((Math.floor(damage * damageFromWeapon)
				+ damageBonus)
				* (1 + power * damageFromUnit / 50));
	}

	public int getMinRange() {
		return minRange;
	}

	public int getMaxRange() {
		return maxRange;
	}

	public int getLineOfFire() {
		return lineOfFire;
	}

	public int getShotsPerAttack() {
		return shotsPerAttack;
	}

	public int getAttacksPerUse() {
		return attacksPerUse;
	}

	public int getAoeDelay() {
		return aoeDelay;
	}

	public boolean getRandomTarget() {
		return randomTarget;
	}

	public TargetType getTargetType() {
		return targetType;
	}

    public String getInfantryHitSound() {
        return infantryHitSound;
    }

    public String getVehicleHitSound() {
        return vehicleHitSound;
    }

    /** The hit sound for the target type ({@code metal} = vehicle), with a
     * fallback to the other if one is missing. */
    public String getHitSound(boolean metal) {
        String sound = metal ? vehicleHitSound : infantryHitSound;
        return sound != null ? sound : (metal ? infantryHitSound : vehicleHitSound);
    }

    public DamageType getDamageType() {
        return damageType;
    }

    public double getArmorPiercingRate() {
        return armorPiercingRate;
    }

    /** This ability's base offense stat, added to the attacker's rank accuracy
     * for its total offense (used in the defender's graze chance). */
    public int getAttack() {
        return attack;
    }

    /** Fraction (0-1) of a hit's damage that lands when the defender grazes it;
     * 0 means a graze negates the hit entirely (a dodge). */
    public double getSecondaryDamageRatio() {
        return secondaryDamageRatio;
    }

    /** Multiplier applied to the damage this ability deals a unit when checking
     * whether the hit distracts (suppresses) it; see {@link #causesDistraction}. */
    public double getDamageDistraction() {
        return damageDistraction;
    }

    /** Flat amount added to a hit's scaled damage when checking distraction-based
     * suppression. */
    public int getDamageDistractionBonus() {
        return damageDistractionBonus;
    }

    /** Whether this ability can suppress a unit through distraction — it has a
     * non-zero distraction multiplier or bonus. When true, a hit suppresses the
     * defender if {@code damage * distraction + distractionBonus} exceeds the
     * defender's bravery. */
    public boolean causesDistraction() {
        return damageDistraction != 0 || damageDistractionBonus != 0;
    }

	/** This ability's base critical-hit chance (0-1), before any per-target bonus. */
	public double getBaseCritical() {
		return baseCritical;
	}

	/**
	 * This ability's critical-hit chance (0-1) against {@code target}: the base
	 * chance plus any "criticalBonuses" configured for unit types the target has.
	 * A {@code null} target (e.g. an empty splash tile) gets just the base chance.
	 * The bonus rules aren't finalised; matching bonuses are summed for now.
	 */
	public double getCriticalRate(Unit target) {
		double rate = baseCritical;
		if (target != null && criticalBonuses != null) {
			for (Map.Entry<UnitTag, Double> bonus : criticalBonuses.entrySet()) {
				if (target.hasTag(bonus.getKey()))
					rate += bonus.getValue();
			}
		}
		return rate;
	}

	/**
	 * The extra critical-hit chance (0-1) this ability gains against each unit
	 * type, keyed by the {@link UnitTag} that triggers it, as an unmodifiable
	 * view. Empty when the ability has no tag-specific bonuses. The full chance
	 * against a target with one of these tags is {@link #getBaseCritical} plus
	 * the bonus — see {@link #getCriticalRate}.
	 */
	public Map<UnitTag, Double> getCriticalBonuses() {
		return criticalBonuses == null
			? Collections.emptyMap()
			: Collections.unmodifiableMap(criticalBonuses);
	}

    /** The status effects this ability can inflict, each with its chance (percent). */
    public StatusEffectChance[] getStatusEffects() {
        return statusEffects.clone();
    }

	/** Whether range and blocking are measured from the defender's front or back. */
	public AttackDirection getAttackDirection() {
		return attackDirection;
	}

	public boolean getCapture() {
		return capture;
	}

	/** Turns this ability is unusable after it is used. */
	public int getCooldown() {
		return cooldown;
	}

	/** Turns the weapon's <em>other</em> abilities are unusable after this one is used. */
	public int getGlobalCooldown() {
		return globalCooldown;
	}

	/** Ammo this ability spends from its weapon's pool each time it is used. */
	public int getAmmoRequired() {
		return ammoRequired;
	}

	/** Turns this ability must charge at the start of a battle before its first use. */
	public int getPrepTime() {
		return prepTime;
	}

	public TargetSquare[] getDamageArea() {
		return (damageArea == null) ? null : damageArea.clone();
	}

	public TargetSquare[] getTargetArea() {
		return (targetArea == null) ? null : targetArea.clone();
	}

	public static class TargetSquare implements Comparable<TargetSquare> {
		public static final TargetSquare SINGLE_TARGET = new TargetSquare();
		private double value, chance;
		private int x, y, order;
		private TargetSquare() {
			value = chance = 1;
		}
		/**
		 * A square at offset ({@code x}, {@code y}) from the target, hit in AOE
		 * sequence {@code order}, taking fraction {@code value} of the damage with
		 * probability {@code chance} (both 0-1).
		 */
		public TargetSquare(int x, int y, int order, double value, double chance) {
			this.x = x;
			this.y = y;
			this.order = order;
			this.value = value;
			this.chance = chance;
		}
		private TargetSquare(TargetSquare a, TargetSquare b) {
			x = a.x + b.x;
			y = a.y + b.y;
			order = a.order + b.order;
			value = a.value * b.value;
			chance = a.chance * b.chance;
		}
		public double getValue() {
			return value;
		}
		public double getChance() {
			return chance;
		}
		public int getX() {
			return x;
		}
		public int getY() {
			return y;
		}
		public int getOrder() {
			return order;
		}
		@Override
		public int compareTo(TargetSquare that) {
			// sort ascending y, descending x, ascending order
			if (this.y != that.y) return that.y - this.y;
			if (this.x != that.x) return this.x - that.x;
			return that.order - this.order;
		}

		public static TargetSquare[] convolution(TargetSquare[] in1,
				TargetSquare[] in2) {
			TargetSquare[] out = new TargetSquare[in1.length * in2.length];
			int i = 0;
			for (int j = 0; j < in1.length; j++)
				for (int k = 0; k < in2.length; k++)
					out[i++] = new TargetSquare(in1[j], in2[k]);

			Arrays.sort(out);
			i = 1;
			TargetSquare a = out[0];
			for (int j = 1; j < out.length; j++) {
				TargetSquare b = out[j];
				if (a.x == b.x && a.y == b.y) {
					a.value += b.value;
					a.chance += b.chance;
				}
				else
					out[i++] = a = b;
			}

			return i == out.length ? out : Arrays.copyOf(out, i);
		}

		public static int width(TargetSquare[] area) {
			int xMin = 0, xMax = 0;
			for (int i = 0; i < area.length; i++) {
				int x = area[i].x;
				if (x < xMin)
					xMin = x;
				else if (x > xMax)
					xMax = x;
			}
			return xMax - xMin + 1;
		}
	}
    /** A status effect this ability can inflict, with its application chance (0-1). */
    public record StatusEffectChance(StatusEffect effect, double chance) {
    }

	public enum TargetType {
		TARGET, WEAPON;
		public static TargetType fromString(String s) {
			for (TargetType type : TargetType.values()) {
				if (type.name().equalsIgnoreCase(s))
					return type;
			}
			throw new IllegalArgumentException("Unknown target type: " + s);
		}
	}
    public enum AttackDirection {
        FRONT, BACK;
    }
    public enum DamageType {
        PIERCING("Piercing", "damageBullet@2x.png"),
        EXPLOSIVE("Explosive", "damageExplosion@2x.png"),
        FIRE("Fire", "damagePoison_icon@2x.png"),
        CRUSHING("Crushing", "damageMelee@2x.png"),
        COLD("Cold", "damageCold@2x.png"),
        TORPEDO("Torpedo", "damageTorpedo@2x.png"),
        DEPTH_CHARGE("DepthCharge", "damageDepthCharge@2x.png");

        private String name;
		private String icon;

        DamageType(String name, String icon) {
            this.name = name;
            this.icon = icon;
        }
		public String getIcon() { return icon; }
        public static DamageType fromString(String s) {
            for (DamageType type : DamageType.values()) {
                if (type.name.equalsIgnoreCase(s)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown damage type: " + s);
        }
    }
}