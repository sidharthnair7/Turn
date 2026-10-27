package turn.turn.floor;

import java.time.Instant;

/** A booking for later today. When the guests arrive, the host moves them to the front of the list. */
public record Reservation(String id, String name, int size, Instant at, String note) {
}
