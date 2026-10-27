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
        List<ReservationView> reservations,
        Counts counts,
        double avgDiningMin,
        int completedSeatings,
        Insights insights) {

    /** x, y, w, h are the table's zone in the camera frame; the floor map lays tables out from them. */
    public record TableView(String id, int number, String name, int seats, String state, int guests,
                            Long seatedForSec, Long sinceVisitSec, String server, String party,
                            String reservedFor, boolean help, boolean check, String tone,
                            boolean blocked, long stateSec, int x, int y, int w, int h) {
    }

    public record AlertView(long id, String tableId, String tableName, String type, String label,
                            long ageSec, boolean overdue, String routedTo, boolean suggested) {
    }

    /** next = this server should get the next party (lightest load). */
    public record ServerView(int badge, String name, int load, List<String> tables, boolean next) {
    }

    /** Open, seated, dirty (needs bussing) and blocked table counts, like a host stand's legend. */
    public record Counts(int open, int seated, int dirty, int blocked) {
    }

    /** Shift numbers and tips: per table and per alert type, never per person. */
    public record Insights(int covers, int seatings, Long avgGreetSec, Long avgTurnSec, int alertsRaised,
                           int alertsAnswered, int overdue, List<ResponseView> responses, List<String> tips) {
    }

    public record ResponseView(String label, int count, long avgSec) {
    }

    /** suggestedTable is a free table that fits now (null if none); etaMin is the quote. */
    public record PartyView(String id, String name, int size, int position, long waitedSec, Integer etaMin,
                            String note, String suggestedTable, String suggestedServer) {
    }

    public record ReservationView(String id, String name, int size, long at, long inSec, String note) {
    }

    /** The owner's Shift Flow page. */
    public record Flow(long now, String source, long shiftStart, int covers, int parties, int tablesTurned,
                       Double avgTurnMin, Long peakStart, long bucketSec, List<Bucket> timeline, List<Action> actions,
                       List<Signal> signals, Capacity capacity, List<ServerView> servers, Insights insights,
                       Counts counts, double avgCheck) {
    }

    public record Bucket(long start, int covers, int seated, int completed) {
    }

    /** kind: overdue, seat, bus, quote, blocked, calm */
    public record Action(String kind, String title, String detail) {
    }

    public record Signal(String table, String title, String detail, String tone) {
    }

    /**
     * seatsToTurn: seats at tables waiting to be bussed. coversWaiting: guests on the list.
     * openingSoon: seated tables likely to free up within a third of a typical meal. Dollar values are rough,
     * from the configured average check.
     */
    public record Capacity(int seatsToTurn, int tablesToTurn, int coversWaiting, int openingSoon,
                           Integer nextOpenMin, String nextOpenTable, double idleValue, double waitingValue) {
    }
}
