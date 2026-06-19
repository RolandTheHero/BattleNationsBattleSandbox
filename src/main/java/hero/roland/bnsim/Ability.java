package hero.roland.bnsim;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

public class Ability {
	public static final int LOF_CONTACT = 0, LOF_DIRECT = 1,
			LOF_PRECISE = 2, LOF_INDIRECT = 3;

	public static final Ability NO_ABILITY = new Ability();

	private static Map<String, Ability> abilities;

	private String tag, name;
	private String icon;
	private String frontAnimationName, backAnimationName;
	private double damageFromWeapon, damageFromUnit;
    private double armorPiercingRate;
	private int damageBonus;
	private int numAttacks;
	private int minRange, maxRange;
	private int lineOfFire;
	private boolean capture;
	private int aoeDelay;
	private String targetType;
    private String infantryHitSound, vehicleHitSound;
    private DamageType damageType;
    private AttackDirection attackDirection;
	private boolean randomTarget;
	private TargetSquare[] targetArea, damageArea;
	//private Map<String, Prerequisites> prereqs;

	public static void load() throws IOException {
		abilities = new HashMap<String,Ability>();
		JSONObject damageAnim = GameFiles.readJson("DamageAnimConfig.json");
		JSONObject json = GameFiles.readJson("BattleAbilities.json");
		for (String key : json.keySet()) {
			Ability abil = new Ability(key, json.getJSONObject(key), damageAnim);
			abilities.put(key, abil);
		}
	}

	private Ability() {
		tag = "none";
		name = "(None)";
		minRange = 1;
		maxRange = 5;
	}

	private Ability(String tag, JSONObject json, JSONObject dmgAnim) {
		this.tag = tag;
		name = Text.get(json.optString("name", null));
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
		numAttacks = stats.optInt("shotsPerAttack", 1)
                        * stats.optInt("attacksPerUse", 1);
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
			targetType = targ.optString("type", null);
			randomTarget = targ.getBoolean("random");
			aoeDelay = (int) Math.round(getDouble(targ, "aoeOrderDelay", 0) * 20);
			targetArea = initArea(targ, randomTarget);
		}
        armorPiercingRate = stats.optDouble("armorPiercingPercent", 0);
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

	protected static double getDouble(JSONObject json, String name,
			double defaultVal) {
		return json.optDouble(name, defaultVal);
	}

	public static Ability get(String tag) {
		return abilities.get(tag);
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

	public int getNumAttacks() {
		return numAttacks;
	}

	public int getAoeDelay() {
		return aoeDelay;
	}

	public boolean getRandomTarget() {
		return randomTarget;
	}

	public String getTargetType() {
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

	/** Whether range and blocking are measured from the defender's front or back. */
	public AttackDirection getAttackDirection() {
		return attackDirection;
	}

	public boolean getCapture() {
		return capture;
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
				value = getDouble(json, "damagePercent", 100) / 100;
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
    public enum AttackDirection {
        FRONT, BACK
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