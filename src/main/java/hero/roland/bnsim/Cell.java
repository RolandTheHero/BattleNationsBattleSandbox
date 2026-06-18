package hero.roland.bnsim;

/**
 * A logical square on one side of the battlefield, identified by its column
 * and row (both zero-based). Purely a coordinate; it carries no pixel or
 * side information.
 */
public record Cell(int col, int row) {
}
