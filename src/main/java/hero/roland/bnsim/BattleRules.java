package hero.roland.bnsim;

/**
 * The optional "house rule" toggles for a battle, all defaulting to on. A single
 * instance is owned by {@link BattleSimulator} and shared by everything that needs
 * to consult a rule; the UnitMenu's checkboxes flip these through
 * {@link hero.roland.bnsim.ui.BattleField}. Keeping them here means the rule state
 * lives in one place rather than as static flags scattered across the model.
 *
 * <ul>
 * <li>{@code combatRulesEnabled} — whether ammo, reloads, cooldowns and prep time
 *     are tracked. Snapshotted per unit at {@link PlacedUnit#startBattle}; since the
 *     UnitMenu is hidden during a battle, a change only takes effect next battle.
 * <li>{@code enforceTargetTypes} — whether an ability may only hit the unit types in
 *     its targetable list (see {@link hero.roland.bnsim.model.Ability#canTarget}).
 * <li>{@code enforceImmunities} — whether units' status-effect immunities apply
 *     (see {@link hero.roland.bnsim.model.Unit#isImmuneTo}).
 * </ul>
 */
public class BattleRules {

	private boolean combatRulesEnabled = true;
	private boolean enforceTargetTypes = true;
	private boolean enforceImmunities = true;

	/** Whether ammo, reloads, cooldowns and prep time are tracked this battle. */
	public boolean isCombatRulesEnabled() {
		return combatRulesEnabled;
	}

	public void setCombatRulesEnabled(boolean enabled) {
		this.combatRulesEnabled = enabled;
	}

	/** Whether abilities are restricted to the unit types in their targetable list. */
	public boolean isEnforceTargetTypes() {
		return enforceTargetTypes;
	}

	public void setEnforceTargetTypes(boolean enforce) {
		this.enforceTargetTypes = enforce;
	}

	/** Whether units' status-effect immunities are enforced. */
	public boolean isEnforceImmunities() {
		return enforceImmunities;
	}

	public void setEnforceImmunities(boolean enforce) {
		this.enforceImmunities = enforce;
	}
}
