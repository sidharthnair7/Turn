package turn.turn.floor;

import java.util.Set;

/** What the camera (or the demo script) saw at one table in one check. */
public record TableObservation(String tableId, int guests, Set<Integer> staffBadges, boolean helpCard, boolean checkCard) {

    public static TableObservation of(String tableId, int guests, Set<Integer> staffBadges) {
        return new TableObservation(tableId, guests, staffBadges, false, false);
    }
}
