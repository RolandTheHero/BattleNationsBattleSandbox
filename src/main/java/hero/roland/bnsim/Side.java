package hero.roland.bnsim;

/**
 * Which half of the battlefield a unit belongs to. The player side is drawn
 * towards the bottom of the screen and uses each unit's back idle animation;
 * the enemy side is drawn towards the top and uses the front idle animation.
 */
public enum Side {
	PLAYER,
	ENEMY;
}
