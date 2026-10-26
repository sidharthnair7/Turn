package turn.turn.floor;

import java.time.Instant;

/** An open task on the floor. Cleared by the next staff badge visit (or by the table changing state). */
class Alert {
    final long id;
    final String tableId;
    final AlertType type;
    final Instant raisedAt;

    Alert(long id, String tableId, AlertType type, Instant raisedAt) {
        this.id = id;
        this.tableId = tableId;
        this.type = type;
        this.raisedAt = raisedAt;
    }
}
