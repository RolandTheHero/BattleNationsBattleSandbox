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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import hero.roland.bnsim.gamefiles.GameFiles;
import hero.roland.bnsim.model.Ability.TargetSquare;

public class Unit implements Comparable<Unit> {
    public static final int NONE = 0, PARTIAL = 1, BLOCKING = 2;

	/** The bundle this unit was loaded from. */
	private final GameFiles gf;

    private final int blocking;
	private final String id, nameId, shortNameId, side;
	private final String backAnimName, frontAnimName, deathAnimName;
	private final Rank[] ranks;
	private final Weapon[] weapons;
    private final UnitTag[] tags;
	private final Set<StatusEffect.StatusFamily> statusEffectImmunities;
	private final String deathSpawnedUnit;

	/**
	 * A unit's data as parsed from the game files. A {@link GameFiles} loader
	 * fills one in and passes it to {@link Unit#Unit(GameFiles, String, Definition)};
	 * the unit copies the values, so the definition is not retained.
	 */
	public static class Definition {
		public String nameId, shortNameId, side;
		public String backAnimName, frontAnimName, deathAnimName;
		/** One of {@link #NONE}, {@link #PARTIAL} or {@link #BLOCKING}. */
		public int blocking;
		/** The unit's own tags, in file order; a {@code null} entry is an unresolved tag. */
		public List<UnitTag> tags = new ArrayList<>();
		public Set<StatusEffect.StatusFamily> statusEffectImmunities = new HashSet<>();
		/** The id of the unit that takes this unit's tile when it dies, or {@code null}. */
		public String deathSpawnedUnit;
		/** Stats per rank, lowest rank first. */
		public List<Rank> ranks = new ArrayList<>();
		/** The unit's weapons, in display order. */
		public List<WeaponDefinition> weapons = new ArrayList<>();
	}

	/** A weapon's data as parsed from the game files; see {@link Definition}. */
	public static class WeaponDefinition {
		/** The weapon's slot name (e.g. {@code primary}), shown when it has no name. */
		public String tag;
		public String nameId;
		public String frontAnimName, backAnimName;
		/** Frames after the attack animation starts before the hit lands. */
		public int hitDelay;
		public String firesound;
		/** Frames after the attack starts before the fire sound plays. */
		public int firesoundFrame;
		public int minDamage, maxDamage;
		public int rangeBonus;
		/** Infinite if -1. */
		public int ammo;
		/** Turns to reload the ammo back to full. */
		public int reloadTime;
		/** Added to the ability's attack when computing total offense. */
		public int baseAttack;
		/** Base critical chance, 0-1. */
		public double baseCritRate;
		/** The tags of the abilities this weapon can use, in order; a
		 * {@code null} or unknown tag becomes {@link Ability#NO_ABILITY}. */
		public List<String> abilities = new ArrayList<>();
	}

	/** The unit with the given id from the active bundle, or {@code null}. */
	public static Unit get(String id) {
		return GameFiles.active().getUnit(id);
	}

	/** Every unit in the active bundle, sorted (by name then id). */
	public static Unit[] getAll() {
		return GameFiles.active().getUnits();
	}

	/** Builds a unit from its parsed data. Abilities its weapons use must already
	 * be loaded into {@code gf}. */
	public Unit(GameFiles gf, String id, Definition def) {
		this.gf = gf;
		this.id = id;
		nameId = def.nameId;
		shortNameId = def.shortNameId;
		side = def.side;
		backAnimName = def.backAnimName;
		frontAnimName = def.frontAnimName;
		deathAnimName = def.deathAnimName;
		blocking = def.blocking;
		tags = def.tags.toArray(new UnitTag[0]);
		statusEffectImmunities = new HashSet<>(def.statusEffectImmunities);
		deathSpawnedUnit = def.deathSpawnedUnit;
		ranks = def.ranks.toArray(new Rank[0]);
		weapons = new Weapon[def.weapons.size()];
		for (int i = 0; i < weapons.length; i++)
			weapons[i] = new Weapon(def.weapons.get(i));
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
		return tags.clone();
	}

	/**
	 * Whether this unit has the given tag, honouring the tag hierarchy: a unit
	 * tagged with a subtype also counts as having the supertype. For example a
	 * unit tagged {@code Soldier}, {@code Vehicle} or {@code Tank} all "have" the
	 * {@code Ground} tag, so an ability that targets {@code Ground} can
	 * hit them. A {@code null} query tag matches nothing.
	 */
	public boolean hasTag(UnitTag tag) {
		if (tag == null)
			return false;
		for (UnitTag t : tags)
			if (t != null && t.isA(tag))
				return true;
		return false;
	}

	/**
	 * Whether this unit is innately immune to status effects of the given family. A
	 * {@code null} family is never immune (an effect with no family cannot match).
	 * This is the unit's raw immunity rule; whether it is enforced at all is the
	 * caller's decision (the UnitMenu's "Status immunities" toggle).
	 */
	public boolean isImmuneTo(StatusEffect.StatusFamily family) {
		return family != null && statusEffectImmunities.contains(family);
	}

	/**
	 * The status-effect families this unit is innately immune to, as an unmodifiable
	 * view. This is the unit's raw immunity data; whether immunities are enforced in
	 * combat is a separate rule decided by the caller (the UnitMenu "Status
	 * immunities" toggle).
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

	/** A unit's stats at one rank. Damage-type modifiers not listed default to 1. */
	public record Rank(int power, int accuracy, int bravery, int critical, int defense,
			int hp, int armorHp, int dodge,
			Map<Ability.DamageType, Double> damageMods,
			Map<Ability.DamageType, Double> armorDamageMods) {
		public Rank {
			// Copied into HashMaps (not Map.copyOf) so a lookup with a null type
			// returns the default rather than throwing.
			damageMods = Collections.unmodifiableMap(new HashMap<>(damageMods));
			armorDamageMods = Collections.unmodifiableMap(new HashMap<>(armorDamageMods));
		}
        public double damageMod(Ability.DamageType type) {
            return damageMods.getOrDefault(type, 1.0);
        }
        public double armorDamageMod(Ability.DamageType type) {
            return armorDamageMods.getOrDefault(type, 1.0);
        }
	}

	public class Weapon {
		private final String nameId, tag;
		private final String frontAnimationName, backAnimationName;
		private final Attack[] attacks;
		private final int hitDelay;
		private final int minDamage, maxDamage;
		private final int rangeBonus;
        private final String firesound;
        private final int firesoundFrame;
		private final int ammo; // Infinite if -1
		private final int reloadTime; // Turns to reload the ammo back to full
		private final int baseAttack; // Added to the ability's attack when calculating total offense
		private final double baseCritRate;

		protected Weapon() {
			this(new WeaponDefinition());
		}
		protected Weapon(WeaponDefinition def) {
			tag = def.tag != null ? def.tag : "none";
			nameId = def.nameId;
			frontAnimationName = def.frontAnimName;
			backAnimationName = def.backAnimName;
			hitDelay = def.hitDelay;
            firesound = def.firesound;
			firesoundFrame = def.firesoundFrame;
			minDamage = def.minDamage;
			maxDamage = def.maxDamage;
			rangeBonus = def.rangeBonus;
			ammo = def.ammo;
			reloadTime = def.reloadTime;
			baseAttack = def.baseAttack;
			baseCritRate = def.baseCritRate;
			attacks = new Attack[def.abilities.size()];
			for (int i = 0; i < attacks.length; i++)
				attacks[i] = new Attack(def.abilities.get(i), this);
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