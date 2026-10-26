package turn.turn.floor;

import java.time.Instant;

/** A party on the waitlist. */
public record Party(String id, String name, int size, Instant joinedAt) {
}
