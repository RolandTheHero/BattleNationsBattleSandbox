package hero.roland.bnsim.model;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import hero.roland.bnsim.gamefiles.GameFiles;
import hero.roland.bnsim.model.Ability.TargetSquare;

public class Unit implements Comparable<Unit> {
    public static final int NONE = 0, PARTIAL = 1, BLOCKING = 2;

	/** The bundle this unit was loaded from. */
	private final GameFiles gf;

    private int blocking;
	private String id, nameId, shortNameId, side;
	private String backAnimName, frontAnimName, deathAnimName;
	private Rank[] ranks;
	private Weapon[] weapons;
    private UnitTag[] tags;
	private Set<StatusEffect.StatusFamily> statusEffectImmunities;
	private String deathSpawnedUnit;

	/**
	 * Whether {@link #isImmuneTo} enforces units' status-effect immunities. When
	 * false no unit is immune, so any status effect can be applied to any unit.
	 * Toggled from the UnitMenu's "Status immunities" setting.
	 */
	private static boolean enforceImmunities = true;

	/** The unit with the given id from the active bundle, or {@code null}. */
	public static Unit get(String id) {
		return GameFiles.active().getUnit(id);
	}

	/** Every unit in the active bundle, sorted (by name then id). */
	public static Unit[] getAll() {
		return GameFiles.active().getUnits();
	}

	public Unit(GameFiles gf, String id, JSONObject json) {
		this.gf = gf;
		this.id = id;
		nameId = json.optString("name", null);
		shortNameId = json.optString("shortName", null);
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
                this.tags[i] = gf.getUnitTag(tags.getString(i));
        }
		JSONArray statusEffectImmunitiesJson = json.optJSONArray("statusEffectImmunities", new JSONArray());
		statusEffectImmunities = new HashSet<StatusEffect.StatusFamily>();
		for (int i = 0; i < statusEffectImmunitiesJson.length(); i++) {
			statusEffectImmunities.add(gf.getStatusFamily(statusEffectImmunitiesJson.getString(i)));
		}
		deathSpawnedUnit = json.optString("deathSpawnedUnit", null);
		deathAnimName = json.optString("deathAnimationName", "troopdeath");
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
		String name = gf.getText(nameId);
		if (name == null) name = id;
		if (name.startsWith("Speciment ")) // fix game file typo
			name = "Specimen" + name.substring(9);
		return name;
	}

	public String getShortName() {
		String shortName = gf.getText(shortNameId);
		if (shortName == null) shortName = getName();
		return shortName;
	}

	public String toString() {
		return getName();
	}

	public String getSide() {
		return side;
	}

	/** How strongly this unit blocks line of fire: {@link #NONE},
	 * {@link #PARTIAL} or {@link #BLOCKING}. */
	public int getBlocking() {
		return blocking;
	}

	/**
	 * This unit's directly-assigned tags (e.g. {@code Ground}, {@code Tank}), in
	 * file order, never {@code null}. These are the unit's own tags only; the
	 * supertypes they imply via the tag hierarchy are not included (see
	 * {@link #hasTag} for hierarchy-aware membership).
	 */
	public UnitTag[] getTags() {
		return tags == null ? new UnitTag[0] : tags.clone();
	}

	/**
	 * Whether this unit has the given tag, honouring the tag hierarchy: a unit
	 * tagged with a subtype also counts as having the supertype. For example a
	 * unit tagged {@code Soldier}, {@code Vehicle} or {@code Tank} all "have" the
	 * {@code Ground} tag, so an ability that targets {@code Ground} can
	 * hit them. A {@code null} query tag matches nothing.
	 */
	public boolean hasTag(UnitTag tag) {
		if (tag == null || tags == null)
			return false;
		for (UnitTag t : tags)
			if (t != null && t.isA(tag))
				return true;
		return false;
	}

	/**
	 * Sets whether {@link #isImmuneTo} enforces units' status-effect immunities.
	 * When disabled no unit is immune. Driven by the UnitMenu's "Status immunities"
	 * toggle.
	 */
	public static void setEnforceImmunities(boolean enforce) {
		enforceImmunities = enforce;
	}

	/**
	 * Whether this unit is immune to status effects of the given family. A
	 * {@code null} family is never immune (an effect with no family cannot match).
	 * When immunity enforcement is disabled (see {@link #setEnforceImmunities}) no
	 * unit is ever immune.
	 */
	public boolean isImmuneTo(StatusEffect.StatusFamily family) {
		return enforceImmunities && family != null && statusEffectImmunities.contains(family);
	}

	/**
	 * The status-effect families this unit is innately immune to, as an
	 * unmodifiable view. Unaffected by the {@link #setEnforceImmunities} toggle,
	 * which only governs whether those immunities are applied in combat.
	 */
	public Set<StatusEffect.StatusFamily> getStatusImmunities() {
		return Collections.unmodifiableSet(statusEffectImmunities);
	}

	public Animation getBackAnimation() throws IOException {
		return Animation.get(backAnimName);
	}

	public Animation getFrontAnimation() throws IOException {
		return Animation.get(frontAnimName);
	}

	/** The animation played in place of this unit's sprite while it dies. */
	public Animation getDeathAnimation() throws IOException {
		return Animation.get(deathAnimName);
	}

	/** The id of the unit that takes this unit's tile when it dies, or {@code null}. */
	public String getDeathSpawnedUnit() {
		return deathSpawnedUnit;
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
		int cmp = this.getName().compareTo(that.getName());
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

	public static record UnitTag(String name, UnitTag parentTag) {
		public boolean isA(UnitTag other) {
			if (other == null) return false;
			if (this.equals(other)) return true;
			return parentTag != null && parentTag.isA(other);
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
		private String nameId, tag;
		private String frontAnimationName, backAnimationName;
		private Attack[] attacks;
		private int hitDelay;
		private int minDamage, maxDamage;
		private int rangeBonus;
        private String firesound;
        private int firesoundFrame;
		private int ammo; // Infinite if -1
		private int reloadTime; // Turns to reload the ammo back to full
		private int baseAttack; // Added to the ability's attack when calculating total offense
		private double baseCritRate;

		protected Weapon() {
			tag = "none";
			attacks = new Attack[0];
		}
		protected Weapon(String tag, JSONObject json) {
			this.tag = tag;
			nameId = json.optString("name", null);
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
			ammo = json.optInt("ammo", -1);
			reloadTime = json.optInt("reloadTime", 0);
			baseAttack = json.optInt("base_ATK", 0);
			baseCritRate = json.optDouble("base_critPercent", 0d) / 100;
		}
		public String getName() {
			String name = gf.getText(nameId);
			if (name != null) return name;
			return "none".equals(tag) ? "(None)" : tag;
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
		public int getAmmo() {
			return ammo;
		}
		public int getReloadTime() {
			return reloadTime;
		}
		/** Added to the ability's attack when computing the attacker's total offense. */
		public int getBaseAttack() {
			return baseAttack;
		}
		public double getBaseCritical() {
			return baseCritRate;
		}
		public String toString() {
			return getName();
		}
	}

	public class Attack {
		private Ability ability;
		private Weapon weapon;
		//private Prerequisites prereq;
		protected Attack(String tag, Weapon weapon) {
			ability = gf.getAbility(tag);
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