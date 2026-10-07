package hero.roland.bnsim.enemybehaviour;

import hero.roland.bnsim.BattleSimulator;
import hero.roland.bnsim.EnemyBehavior;

/**
 * This enemy behaviour simply passes its turn. Useful for enemies not fighting back.
 */
public class DummyEnemyBehavior implements EnemyBehavior {
    @Override
	public Move decideMove(BattleSimulator sim) { return null; }
    
    @Override
    public String name() { return "Dummy"; }

    @Override
    public String description() { return "Passes every turn."; }
}
