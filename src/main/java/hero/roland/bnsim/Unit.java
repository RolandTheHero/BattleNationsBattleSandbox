package hero.roland.bnsim;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import hero.roland.bnsim.Ability.TargetSquare;
import hero.roland.bnsim.util.FileFormatException;

public class Unit implements Comparable<Unit> {
    public static final int NONE = 0, PARTIAL = 1, BLOCKING = 2;

	private static Map<String, Unit> units;

    private int blocking;
	private String id, name, shortName, side;
	private String backAnimName, frontAnimName;
	private Rank[] ranks;
	private Weapon[] weapons;
    private UnitTag[] tags;

	public static void load() throws IOException {
		units = new HashMap<String, Unit>();
		try {
			JSONObject json = GameFiles.readJson("BattleUnits.json");
			for (String key : json.keySet()) {
				units.put(key, new Unit(key, json.getJSONObject(key)));
			}
		}
		catch (JSONException e) {
			throw new FileFormatException("Json type error", e);
		}
	}

	public static Unit get(String id) {
		return units.get(id);
	}

	public static Unit[] getAll() {
		Unit[] array = units.values().toArray(new Unit[units.size()]);
		Arrays.sort(array);
		return array;
	}

	private Unit(String id, JSONObject json) {
		this.id = id;
		name = Text.get(json.optString("name", null));
		if (name == null) name = id;
		if (name.startsWith("Speciment ")) // fix game file typo
			name = "Specimen" + name.substring(9);
		shortName = Text.get(json.optString("shortName", null));
		if (shortName == null) shortName = name;
		side = json.optString("side", "Other");
		backAnimName = json.optString("backIdleAnimation", null);
		frontAnimName = json.optString("frontIdleAnimation", null);
        blocking = json.optInt("blocking", NONE);
		initWeapons(json.optJSONObject("weapons"));
		initRanks(json.getJSONArray("stats"));
        JSONArray tags = json.optJSONArray("tags");
        if (tags != null) {
            this.tags = new UnitTag[tags.length()];
            for (int i = 0; i < tags.length(); i++)
                this.tags[i] = UnitTag.fromString(tags.getString(i));
        }
	}

	private void initRanks(JSONArray json) {
		ranks = new Rank[json.length()];
		for (int i = 0; i < ranks.length; i++)
			ranks[i] = new Rank(json.getJSONObject(i));
	}

	private void initWeapons(JSONObject json) {
		if (json == null || json.isEmpty()) {
			this.weapons = new Weapon[0];
			return;
		}
		Map<String,Weapon> weapons = new HashMap<String,Weapon>();
		for (String key : json.keySet()) {
			Weapon weap = new Weapon(key, json.getJSONObject(key));
			String name = key;
			switch (name) {
			case "primary": name = "1primary"; break;
			case "secondary": name = "2secondary"; break;
			}
			weapons.put(name, weap);
		}
		String[] names = new String[weapons.size()];
		names = weapons.keySet().toArray(names);
		Arrays.sort(names);
		this.weapons = new Weapon[names.length];
		for (int i = 0; i < names.length; i++)
			this.weapons[i] = weapons.get(names[i]);
	}

	public String getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getShortName() {
		return shortName;
	}

	public String toString() {
		return name;
	}

	public String getSide() {
		return side;
	}

	/** How strongly this unit blocks line of fire: {@link #NONE},
	 * {@link #PARTIAL} or {@link #BLOCKING}. */
	public int getBlocking() {
		return blocking;
	}

	/** Whether this unit has the given tag (e.g. {@link UnitTag#METAL}). */
	public boolean hasTag(UnitTag tag) {
		if (tags != null)
			for (UnitTag t : tags)
				if (t == tag)
					return true;
		return false;
	}

	public Animation getBackAnimation() throws IOException {
		return Animation.get(backAnimName);
	}

	public Animation getFrontAnimation() throws IOException {
		return Animation.get(frontAnimName);
	}

	public Weapon[] getWeapons() {
		Weapon[] copy = new Weapon[weapons.length+1];
		copy[0] = new Weapon();
		for (int i = 0; i < weapons.length; i++)
			copy[i+1] = weapons[i];
		return copy;
	}

	@Override
	public int compareTo(Unit that) {
		int cmp = this.name.compareTo(that.name);
		if (cmp == 0)
			cmp = this.id.compareTo(that.id);
		return cmp;
	}

	public int getMaxRank() {
		return ranks.length;
	}

	public Rank getRank(int rank) {
		return ranks[rank-1];
	}

	public int getPower(int rank) {
		return ranks[rank-1].power();
	}

    public enum UnitTag {
        DEFENSE("Defense"),
        METAL("Metal"),
        SOLDIER("Soldier"),
        VEHICLE("Vehicle"),
        FAST("Fast"),
        ZOMBIE_CANDIDATE("ZombieCandidate"),
        GUNBOAT("Gunboat"),
        TANK("Tank"),
        SRB("SRB"),
        FIGHTER("Fighter"),
        VRB("VRB"),
        HELICOPTER("Helicopter"),
        BATTLESHIP("Battleship"),
        SHIP("Ship"),
        CRITTER("Critter"),
        CIVILIAN("Civilian"),
        SNIPER("Sniper"),
        IGNORABLE("Ignorable"),
        SPIDERWASP("Spiderwasp"),
        INFECTED("Infected"),
        AIR("Air"),
        OTHER("Other");

        private final String tagName;
        UnitTag(String tagName) {
            this.tagName = tagName;
        }

        public static UnitTag fromString(String str) {
            for (UnitTag tag : values()) {
                if (tag.tagName.equalsIgnoreCase(str)) {
                    return tag;
                }
            }
            System.out.println(str);
            return OTHER;
        }
		public boolean isVehicle() {
			return this == VEHICLE || this == TANK;
		}
		public boolean isInfantry() {
			return !isVehicle();
		}
    }

	public class Rank {
		private int power;
        private int accuracy;
        private int bravery;
        private int critical;
        private int defense;
        private int hp;
        private int armorHp;
        private int dodge;
        private Map<Ability.DamageType, Double> damageMods = new HashMap<>();
        private Map<Ability.DamageType, Double> armorDamageMods = new HashMap<>();
		//private Prerequisites prereq;
		protected Rank(JSONObject json) {
			power = json.optInt("power", 0);
            accuracy = json.optInt("accuracy", 0);
            bravery = json.optInt("bravery", 0);
            critical = json.optInt("critical", 0);
            defense = json.optInt("defense", 0);
            hp = json.optInt("hp", 10);
            armorHp = json.optInt("armorHp", 0);
            dodge = json.optInt("dodge", 0);
            JSONObject mods = json.optJSONObject("damageMods");
            if (mods != null) {
                for (String key : mods.keySet()) {
                    Ability.DamageType type = Ability.DamageType.fromString(key);
                    damageMods.put(type, mods.optDouble(key, 1));
                }
            }
            JSONObject armorMods = json.optJSONObject("armorDamageMods");
            if (armorMods != null) {
                for (String key : armorMods.keySet()) {
                    Ability.DamageType type = Ability.DamageType.fromString(key);
                    armorDamageMods.put(type, armorMods.optDouble(key, 1));
                }
            }
			//prereq = Prerequisites.create(json.optJSONObject("prereqsForLevel"));
		}
		public int power() { return power; }
        public int accuracy() { return accuracy; }
        public int bravery() { return bravery; }
        public int critical() { return critical; }
        public int defense() { return defense; }
        public int hp() { return hp; }
        public int armorHp() { return armorHp; }
        public int dodge() { return dodge; }
        public double damageMod(Ability.DamageType type) {
            return damageMods.getOrDefault(type, 1.0);
        }
        public double armorDamageMod(Ability.DamageType type) {
            return armorDamageMods.getOrDefault(type, 1.0);
        }
		// public int getMinLevel() {
		// 	return prereq == null ? 0 : prereq.getMinLevel();
		// }
	}

	public class Weapon {
		private String name, tag;
		private String frontAnimationName, backAnimationName;
		private Attack[] attacks;
		private int hitDelay;
		private int minDamage, maxDamage;
		private int rangeBonus;
        private String firesound;
        private int firesoundFrame;
		protected Weapon() {
			name = "(None)";
			tag = "none";
			attacks = new Attack[0];
		}
		protected Weapon(String tag, JSONObject json) {
			this.tag = tag;
			name = Text.get(json.optString("name", null));
			if (name == null) name = tag;
			frontAnimationName = json.optString("frontattackAnimation", null);
			backAnimationName = json.optString("backattackAnimation", null);
			firesoundFrame = json.optInt("firesoundFrame", 0);
			hitDelay = json.optInt("damageAnimationDelay", 0) + firesoundFrame;
            firesound = json.optString("firesound", null);
			initStats(json.optJSONObject("stats"));
			JSONArray abilities = json.getJSONArray("abilities");
			attacks = new Attack[abilities.length()];
			for (int i = 0; i < attacks.length; i++)
				attacks[i] = new Attack(abilities.optString(i, null), this);
		}
		private void initStats(JSONObject json) {
			if (json == null) return;
			minDamage = json.optInt("base_damage_min", 0);
			maxDamage = json.optInt("base_damage_max", 0);
			rangeBonus = json.optInt("rangeBonus", 0);
		}
		public String getName() {
			return name;
		}
		public String getTag() {
			return tag;
		}
		public Animation getFrontAnimation() throws IOException {
			return Animation.get(frontAnimationName);
		}
		public Animation getBackAnimation() throws IOException  {
			return Animation.get(backAnimationName);
		}
		public Attack[] getAttacks() {
			Attack[] array = new Attack[attacks.length+1];
			array[0] = new Attack(null, this);
			for (int i = 0; i < attacks.length; i++)
				array[i+1] = attacks[i];
			return array;
		}
		public int getHitDelay() {
			return hitDelay;
		}
        public String firesound() {
            return firesound;
        }
        /** Frames after the attack starts before the fire sound should play. */
        public int firesoundFrame() {
            return firesoundFrame;
        }
		public int getMinDamage() {
			return minDamage;
		}
		public int getMaxDamage() {
			return maxDamage;
		}
		public int getRangeBonus() {
			return rangeBonus;
		}
		public String toString() {
			return name;
		}
	}

	public class Attack {
		private Ability ability;
		private Weapon weapon;
		//private Prerequisites prereq;
		protected Attack(String tag, Weapon weapon) {
			ability = Ability.get(tag);
			if (ability == null)
				ability = Ability.NO_ABILITY;
			this.weapon = weapon;
			//prereq = ability.getPrereqs(Unit.this.getTag());
		}
		public String getName() {
			return ability.getName();
		}
		public String getTag() {
			return ability.getTag();
		}
		public String toString() {
			return ability.toString();
		}
		public Animation getFrontAnimation() throws IOException {
			return ability.getFrontAnimation();
		}
		public Animation getBackAnimation() throws IOException {
			return ability.getBackAnimation();
		}
		public int getMinDamage(int rank) {
			return ability.adjustDamage(weapon.getMinDamage(),
					getPower(rank));
		}
		public int getMaxDamage(int rank) {
			return ability.adjustDamage(weapon.getMaxDamage(),
					getPower(rank));
		}
		public double getAverageDamage(int rank) {
			return 0.5*(getMinDamage(rank) + getMaxDamage(rank));
		}
		public int getMinRange() {
			return ability.getMinRange();
		}
		public int getMaxRange() {
			return ability.getMaxRange() + weapon.getRangeBonus();
		}
		// public int getMinRank() {
		// 	return prereq == null ? 1 : Math.max(prereq.getMinRank(), 1);
		// }
		public int getMaxRank() {
			return Unit.this.getMaxRank();
		}
		public int getHitDelay() {
			TargetSquare[] area = ability.getTargetArea();
			int aoeDelay = ability.getAoeDelay();
			if (area != null && aoeDelay != 0) {
				for (TargetSquare sq : area)
					if (sq.getX() == 0)
						return weapon.getHitDelay()
								+ aoeDelay * (sq.getOrder() - 1);
			}
			return weapon.getHitDelay();
		}
		public Ability getAbility() {
			return ability;
		}
		public Weapon getWeapon() {
			return weapon;
		}
	}

}