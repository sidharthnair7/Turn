package turn.turn.floor;

import turn.turn.config.TurnProperties.TableConfig;

import java.time.Instant;

/** Live state of one table. Mutated only by FloorEngine while holding its lock. */
class Table {
    final TableConfig config;
    TableState state = TableState.EMPTY;
    Instant stateSince;
    int guests;
    Instant presentSince;
    Instant absentSince;
    Instant seatedAt;
    Instant lastVisitAt;
    Integer serverBadge;
    String partyName;
    Party reservedFor;
    Instant reservedAt;
    boolean helpVisible;
    boolean checkVisible;
    boolean blocked; // set by the host: out of rotation (broken, held, closed section)

    Table(TableConfig config, Instant now) {
        this.config = config;
        this.stateSince = now;
    }

    String id() {
        return config.id();
    }

    void moveTo(TableState next, Instant now) {
        state = next;
        stateSince = now;
    }
}
