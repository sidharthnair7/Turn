package turn.turn.floor;

import java.util.List;

/** Everything the Floor and Wait pages draw, built in one consistent moment. */
public record FloorSnapshot(
        String source,
        String sourceStatus,
        long now,
        List<TableView> tables,
        List<AlertView> alerts,
        List<ServerView> servers,
        String suggestedServer,
        List<PartyView> waitlist,
        double avgDiningMin,
        int completedSeatings,
        Insights insights) {

    public record TableView(String id, int number, String name, int seats, String state, int guests,
                            Long seatedForSec, Long sinceVisitSec, String server, String party,
                            String reservedFor, boolean help, boolean check, String tone) {
    }

    public record AlertView(long id, String tableId, String tableName, String type, String label,
                            long ageSec, boolean overdue, String routedTo, boolean suggested) {
    }

    public record ServerView(int badge, String name, int load, List<String> tables) {
    }

    /** Shift numbers and tips: per table and per alert type, never per person. */
    public record Insights(int covers, int seatings, Long avgGreetSec, Long avgTurnSec, int alertsRaised,
                           int alertsAnswered, int overdue, List<ResponseView> responses, List<String> tips) {
    }

    public record ResponseView(String label, int count, long avgSec) {
    }

    public record PartyView(String id, String name, int size, int position, long waitedSec, Integer etaMin) {
    }
}
