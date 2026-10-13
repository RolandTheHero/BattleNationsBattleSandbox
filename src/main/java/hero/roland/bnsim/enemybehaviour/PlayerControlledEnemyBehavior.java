package hero.roland.bnsim.enemybehaviour;

import hero.roland.bnsim.BattleSimulator;
import hero.roland.bnsim.EnemyBehavior;

/**
 * This enemy behaviour hands the enemy's turn to the player, who selects enemy
 * units and attacks with them exactly as they do with their own.
 */
public class PlayerControlledEnemyBehavior implements EnemyBehavior {
    @Override
	public Move decideMove(BattleSimulator sim) { return null; }

    @Override
    public boolean isPlayerControlled() { return true; }

    @Override
    public String name() { return "Player Controlled"; }

    @Override
    public String description() { return "The player selects enemy units and attacks with them on the enemy's turn."; }
}
