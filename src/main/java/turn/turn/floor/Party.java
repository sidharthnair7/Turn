package turn.turn.floor;

import java.time.Instant;

/** A party on the waitlist. The note is what the host typed, like "birthday" or "high chair". */
public record Party(String id, String name, int size, Instant joinedAt, String note) {
}
