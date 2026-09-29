package hero.roland.bnsim.model;

/**
 * A sound held in memory: the bytes of an audio file and its format, named by
 * the file extension it would have ({@code mp3}, {@code caf} or {@code wav}).
 */
public record Sound(String format, byte[] data) {
}
