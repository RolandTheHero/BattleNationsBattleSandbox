package hero.roland.bnsim.model;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import hero.roland.bnsim.gamefiles.GameFiles;
import hero.roland.bnsim.model.Unit.UnitTag;

public class Ability {
	public static final int LOF_CONTACT = 0, LOF_DIRECT = 1,
			LOF_PRECISE = 2, LOF_INDIRECT = 3;

	public static final Ability NO_ABILITY = new Ability();

	/** The bundle this ability was loaded from ({@code null} for {@link #NO_ABILITY}). */
	private GameFiles gf;

	private String tag, name;
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

	private Ability() {
		tag = "none";
		name = "(None)";
		minRange = 1;
		maxRange = 5;
	}

	public Ability(GameFiles gf, String tag, JSONObject json, JSONObject dmgAnim) {
		this.gf = gf;
		this.tag = tag;
		name = gf.getText(json.optString("name", null));
		icon = json.getString("icon");
		if (!icon.endsWith(".png")) icon += "@2x.png";
		if (name == null) name = tag;
		infantryHitSound = json.optString("inf_hitsound", null);
        vehicleHitSound = json.optString("veh_hitsound", null);
		initAnimation(json, dmgAnim);
		initStats(json.getJSONObject("stats"));
		//initPrereqs(json.getJSONObject("reqs"));
	}

	private void initAnimation(JSONObject json, JSONObject dmgAnim) {
		String animType = json.optString("damageAnimationType", null);
		if (animType == null) return;
		JSONObject dmg = dmgAnim.getJSONObject(animType);
		if (dmg == null) return;
		frontAnimationName = dmg.optString("front", null);
		backAnimationName = dmg.optString("back", null);
	}

	private void initStats(JSONObject stats) {
		if (stats == null) return;
		damageBonus = stats.optInt("damage", 0);
		damageFromWeapon = getDouble(stats, "damageFromWeapon", 1);
		damageFromUnit = getDouble(stats, "damageFromUnit", 1);
		minRange = stats.optInt("minRange", 1);
		maxRange = stats.optInt("maxRange", 1);
		shotsPerAttack = stats.optInt("shotsPerAttack", 1);
		attacksPerUse = stats.optInt("attacksPerUse", 1);
		lineOfFire = stats.optInt("lineOfFire", 0);
		capture = stats.getBoolean("capture");
        String attackDirectionStr = stats.optString("attackDirection", "front");
        if (attackDirectionStr.equalsIgnoreCase("front"))
            attackDirection = AttackDirection.FRONT;
        else attackDirection = AttackDirection.BACK;
        damageType = DamageType.fromString(stats.getJSONArray("damageType").getString(0));
		damageArea = initArea(stats.optJSONObject("damageArea"), false);
		JSONObject targ = stats.optJSONObject("targetArea");
		if (targ != null) {
			String targetTypeStr = targ.optString("type", "Target");
			targetType = TargetType.fromString(targetTypeStr);
			randomTarget = targ.getBoolean("random");
			aoeDelay = (int) Math.round(getDouble(targ, "aoeOrderDelay", 0) * 20);
			targetArea = initArea(targ, randomTarget);
		}
        armorPiercingRate = stats.optDouble("armorPiercingPercent", 0);
		// "statusEffects" maps each effect id to its chance (percent) of applying.
		JSONObject statusEffectsJSON = stats.optJSONObject("statusEffects");
		if (statusEffectsJSON == null) {
			statusEffects = new StatusEffectChance[0];
		} else {
			statusEffects = new StatusEffectChance[statusEffectsJSON.length()];
			int i = 0;
			for (String effectId : statusEffectsJSON.keySet()) {
				StatusEffect effect = gf.getStatusEffect(effectId);
				double chance = statusEffectsJSON.optDouble(effectId, 0d) / 100.0; // Chance is from 0 to 100, so normalise to 0-1.
				statusEffects[i++] = new StatusEffectChance(effect, chance);
			}
		}
		// Base critical-hit chance, stored 0-1 (authored as a percent). "criticalBonuses"
		// maps a unit-type name (a UnitTag, e.g. "Tank") to extra chance (percent) added
		// when the target has that tag — see getCriticalRate. How these ultimately combine
		// isn't settled yet; for now matching bonuses are summed onto the base.
		baseCritical = stats.optDouble("criticalHitPercent", 5d) / 100;
		criticalBonuses = new HashMap<>();
		JSONObject criticalBonusesJson = stats.optJSONObject("criticalBonuses");
		if (criticalBonusesJson != null) {
			for (String key : criticalBonusesJson.keySet())
				criticalBonuses.put(UnitTag.fromString(key),
						criticalBonusesJson.getDouble(key) / 100);
		}
		cooldown = stats.optInt("abilityCooldown", 0);
		globalCooldown = stats.optInt("globalCooldown", 0);
		ammoRequired = stats.optInt("ammoRequired", 0);
		prepTime = stats.optInt("chargeTime", 0);
		initTargets(stats.optJSONArray("targets"));
	}

	// private void initPrereqs(JSONObject json) {
	// 	if (json == null || json.isEmpty()) return;
	// 	prereqs = new HashMap<String,Prerequisites>();
	// 	for (Map.Entry<String,JsonValue> item : json.entrySet()) {
	// 		JsonObject reqs = (JsonObject) item.getValue();
	// 		Prerequisites pre = Prerequisites.create(
	// 				reqs.getJsonObject("prereq"));
	// 		if (pre != null)
	// 			prereqs.put(item.getKey(), pre);
	// 	}
	// }

	private static TargetSquare[] initArea(JSONObject area, boolean random) {
		if (area == null) return null;
		JSONArray data = area.optJSONArray("data");
		if (data == null) return null;

		double weight = 0;
		if (random) {
			for (Object item : data)
				weight += getDouble((JSONObject) item, "weight", 0);
		}

		TargetSquare[] squares = new TargetSquare[data.length()];
		for (int i = 0; i < squares.length; i++)
			squares[i] = new TargetSquare(data.getJSONObject(i), weight);
		return squares;
	}

	private void initTargets(JSONArray arr) {
		targetableTags = new HashSet<>();
		if (arr == null) return;
		for (int i = 0; i < arr.length(); i++) {
			UnitTag tag = UnitTag.fromString(arr.getString(i));
			if (tag != null)
				targetableTags.add(tag);
		}
	}

	/**
	 * Whether this ability is allowed to hit {@code unit}: true when the unit has
	 * at least one of the ability's targetable tags. An ability with no configured
	 * targets (an empty {@link #targetableTags}) is unrestricted and hits anything.
	 */
	public boolean canTarget(Unit unit) {
		if (targetableTags == null || targetableTags.isEmpty())
			return true;
		for (UnitTag tag : targetableTags)
			if (unit.hasTag(tag))
				return true;
		return false;
	}

	protected static double getDouble(JSONObject json, String name,
			double defaultVal) {
		return json.optDouble(name, defaultVal);
	}

	/** The ability with the given tag from the active bundle, or {@code null}. */
	public static Ability get(String tag) {
		return GameFiles.active().getAbility(tag);
	}

	public String getTag() {
		return tag;
	}

	public String getName() {
		return name;
	}

	/** Bundle-relative path to this ability's icon image, or {@code null}. */
	public String getIcon() {
		return icon;
	}

	public String toString() {
		return name;
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
		protected TargetSquare(JSONObject json, double weight) {
			if (weight == 0) {
				value = getDouble(json, "damagePercent", 100d) / 100;
				chance = 1;
				order = json.optInt("order", 0);
			}
			else
				value = chance = getDouble(json, "weight", 0) / weight;
			JSONObject pos = json.optJSONObject("pos");
			if (pos != null) {
				x = pos.optInt("x", 0);
				y = pos.optInt("y", 0);
			}
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