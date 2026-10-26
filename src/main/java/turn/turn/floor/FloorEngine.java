package turn.turn.floor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import turn.turn.config.TurnProperties;
import turn.turn.config.TurnProperties.ServerConfig;
import turn.turn.config.TurnProperties.TableConfig;
import turn.turn.config.TurnProperties.Timing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The floor's brain: per-table state machine, alerts routed to servers, server load, waitlist
 * and wait estimates. Inputs come from the camera (VisionService) or the demo script (DemoSource)
 * through {@link #tick}; it never sees images, only counts and badge IDs.
 */
@Service
public class FloorEngine {
    private static final double TURN_TABLE_MIN = 2.0;   // time to clear and reset a table
    private static final double MIN_REMAINING_MIN = 2.0; // never promise a table sooner than this

    private final TurnProperties props;
    private final Timing timing;
    private final Clock clock;
    private final Map<String, Table> tables = new LinkedHashMap<>();
    private final Map<Integer, String> serverNames = new LinkedHashMap<>();
    private final List<Alert> alerts = new ArrayList<>();
    private final List<Party> waitlist = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong();
    private double avgDiningMin;
    private int completedSeatings;
    private String sourceStatus = "starting";

    @Autowired
    public FloorEngine(TurnProperties props) {
        this(props, Clock.systemUTC());
    }

    FloorEngine(TurnProperties props, Clock clock) {
        this.props = props;
        this.timing = props.activeTiming();
        this.clock = clock;
        reset();
    }

    public synchronized void reset() {
        Instant now = clock.instant();
        tables.clear();
        for (TableConfig tc : props.tables()) tables.put(tc.id(), new Table(tc, now));
        serverNames.clear();
        for (ServerConfig sc : props.servers()) serverNames.put(sc.badge(), sc.name());
        alerts.clear();
        waitlist.clear();
        avgDiningMin = timing.avgDiningMin();
        completedSeatings = 0;
    }

    public synchronized void setSourceStatus(String status) {
        this.sourceStatus = status;
    }

    public List<TableConfig> tableConfigs() {
        return props.tables();
    }

    // ---------------------------------------------------------------- observations

    public synchronized void tick(Collection<TableObservation> observations, Instant now) {
        for (TableObservation obs : observations) {
            Table t = tables.get(obs.tableId());
            if (t != null) observe(t, obs, now);
        }
        for (Table t : tables.values()) raiseTimedAlerts(t, now);
    }

    private void observe(Table t, TableObservation obs, Instant now) {
        t.guests = obs.guests();
        if (obs.guests() > 0) {
            t.absentSince = null;
            if (t.presentSince == null) t.presentSince = now;
        } else {
            t.presentSince = null;
            if (t.absentSince == null) t.absentSince = now;
        }

        switch (t.state) {
            case EMPTY, READY -> {
                if (t.presentSince != null && secondsSince(t.presentSince, now) >= timing.seatAfter()) seat(t, now);
                else if (t.state == TableState.READY && secondsSince(t.stateSince, now) >= timing.readyFor())
                    t.moveTo(TableState.EMPTY, now);
            }
            case SEATED -> {
                if (t.absentSince != null && secondsSince(t.absentSince, now) >= timing.leaveAfter()) leave(t, now);
            }
            case BUSSING -> {
                if (!obs.staffBadges().isEmpty() && obs.guests() == 0) {
                    t.moveTo(TableState.READY, now);
                    clearAlerts(t, EnumSet.of(AlertType.BUSSING));
                }
            }
        }

        // Guest cards raise an alert when they appear, not for as long as they stay up.
        if (t.state == TableState.SEATED) {
            if (obs.helpCard() && !t.helpVisible) raise(t, AlertType.HELP, now);
            if (obs.checkCard() && !t.checkVisible) raise(t, AlertType.CHECK, now);
        }
        t.helpVisible = obs.helpCard();
        t.checkVisible = obs.checkCard();

        if (!obs.staffBadges().isEmpty() && t.state == TableState.SEATED) {
            t.lastVisitAt = now;
            if (t.serverBadge == null) t.serverBadge = Collections.min(obs.staffBadges());
            clearAlerts(t, EnumSet.of(AlertType.NOT_GREETED, AlertType.CHECK_IN, AlertType.HELP, AlertType.CHECK));
        }
    }

    private void seat(Table t, Instant now) {
        t.moveTo(TableState.SEATED, now);
        t.seatedAt = now;
        t.lastVisitAt = null;
        t.serverBadge = null;
        t.partyName = t.reservedFor != null ? t.reservedFor.name() + " (" + t.reservedFor.size() + ")" : null;
        t.reservedFor = null;
        t.reservedAt = null;
        clearAlerts(t, EnumSet.allOf(AlertType.class));
    }

    private void leave(Table t, Instant now) {
        double minutes = secondsSince(t.seatedAt, now) / 60.0;
        avgDiningMin = (avgDiningMin * completedSeatings + minutes) / (completedSeatings + 1);
        completedSeatings++;
        clearAlerts(t, EnumSet.allOf(AlertType.class));
        t.moveTo(TableState.BUSSING, now);
        t.partyName = null;
        raise(t, AlertType.BUSSING, now);
    }

    private void raiseTimedAlerts(Table t, Instant now) {
        if (t.state != TableState.SEATED) return;
        if (t.lastVisitAt == null) {
            if (secondsSince(t.seatedAt, now) >= timing.greetWithin()) raise(t, AlertType.NOT_GREETED, now);
        } else if (secondsSince(t.lastVisitAt, now) >= timing.checkInEvery()) {
            raise(t, AlertType.CHECK_IN, now);
        }
        // A host reservation that nobody sat at expires after five minutes.
        if (t.reservedAt != null && secondsSince(t.reservedAt, now) > 300) t.reservedFor = null;
    }

    private void raise(Table t, AlertType type, Instant now) {
        boolean open = alerts.stream().anyMatch(a -> a.tableId.equals(t.id()) && a.type == type);
        if (!open) alerts.add(new Alert(ids.incrementAndGet(), t.id(), type, now));
    }

    private void clearAlerts(Table t, Set<AlertType> types) {
        alerts.removeIf(a -> a.tableId.equals(t.id()) && types.contains(a.type));
    }

    // ---------------------------------------------------------------- host and guest actions

    public synchronized Party join(String name, int size) {
        if (size < 1 || size > 20) throw new IllegalArgumentException("Party size must be between 1 and 20.");
        String clean = name == null || name.isBlank() ? "Guest" : name.strip();
        if (clean.length() > 30) clean = clean.substring(0, 30);
        Party p = new Party(UUID.randomUUID().toString().substring(0, 8), clean, size, clock.instant());
        waitlist.add(p);
        return p;
    }

    public synchronized void leaveWaitlist(String partyId) {
        waitlist.removeIf(p -> p.id().equals(partyId));
    }

    /** Seat a waitlisted party. tableId may be null to let Turn pick the best free table. */
    public synchronized String seatParty(String partyId, String tableId) {
        Party party = waitlist.stream().filter(p -> p.id().equals(partyId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("That party is no longer on the waitlist."));
        Table table;
        if (tableId == null || tableId.isBlank()) {
            table = bestFreeTable(party.size())
                    .orElseThrow(() -> new IllegalStateException("No free table fits a party of " + party.size() + " yet."));
        } else {
            table = tables.get(tableId);
            if (table == null) throw new IllegalArgumentException("Unknown table " + tableId + ".");
            if (!table.state.isFree() || table.reservedFor != null)
                throw new IllegalStateException(table.config.name() + " is occupied. Double-seating blocked.");
            if (table.config.seats() < party.size())
                throw new IllegalStateException(table.config.name() + " seats " + table.config.seats() + ", party is " + party.size() + ".");
        }
        table.reservedFor = party;
        table.reservedAt = clock.instant();
        waitlist.remove(party);
        return table.config.name();
    }

    private Optional<Table> bestFreeTable(int size) {
        return tables.values().stream()
                .filter(t -> t.state.isFree() && t.reservedFor == null && t.config.seats() >= size)
                .min(Comparator.comparingInt(t -> t.config.seats()));
    }

    // ---------------------------------------------------------------- reading

    public synchronized FloorSnapshot snapshot() {
        Instant now = clock.instant();
        Map<Integer, Integer> load = new LinkedHashMap<>();
        Map<Integer, List<String>> owned = new LinkedHashMap<>();
        serverNames.keySet().forEach(b -> { load.put(b, 0); owned.put(b, new ArrayList<>()); });
        for (Table t : tables.values()) {
            if (t.serverBadge != null && t.state == TableState.SEATED && load.containsKey(t.serverBadge)) {
                load.merge(t.serverBadge, 1, Integer::sum);
                owned.get(t.serverBadge).add(t.config.name());
            }
        }
        Integer suggested = load.entrySet().stream().min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        String suggestedName = suggested == null ? null : serverNames.get(suggested);

        List<FloorSnapshot.TableView> tableViews = new ArrayList<>();
        for (Table t : tables.values()) {
            tableViews.add(new FloorSnapshot.TableView(
                    t.id(), t.config.number(), t.config.name(), t.config.seats(), t.state.name(), t.guests,
                    t.state == TableState.SEATED ? (long) secondsSince(t.seatedAt, now) : null,
                    t.state == TableState.SEATED && t.lastVisitAt != null ? (long) secondsSince(t.lastVisitAt, now) : null,
                    t.serverBadge == null ? null : serverNames.getOrDefault(t.serverBadge, "Badge " + t.serverBadge),
                    t.partyName,
                    t.reservedFor == null ? null : t.reservedFor.name() + " (" + t.reservedFor.size() + ")",
                    t.helpVisible, t.checkVisible, tone(t, now)));
        }

        List<FloorSnapshot.AlertView> alertViews = alerts.stream()
                .map(a -> {
                    Table t = tables.get(a.tableId);
                    long age = (long) secondsSince(a.raisedAt, now);
                    boolean overdue = age >= overdueAfter(a.type);
                    String owner = t.serverBadge == null ? null : serverNames.getOrDefault(t.serverBadge, "Badge " + t.serverBadge);
                    return new FloorSnapshot.AlertView(a.id, a.tableId, t.config.name(), a.type.name(), a.type.label,
                            age, overdue, owner != null ? owner : suggestedName, owner == null);
                })
                .sorted(Comparator.comparing(FloorSnapshot.AlertView::overdue).reversed()
                        .thenComparing(Comparator.comparingLong(FloorSnapshot.AlertView::ageSec).reversed()))
                .toList();

        List<FloorSnapshot.ServerView> serverViews = serverNames.entrySet().stream()
                .map(e -> new FloorSnapshot.ServerView(e.getKey(), e.getValue(), load.get(e.getKey()), owned.get(e.getKey())))
                .toList();

        List<FloorSnapshot.PartyView> partyViews = new ArrayList<>();
        for (int i = 0; i < waitlist.size(); i++) {
            Party p = waitlist.get(i);
            partyViews.add(new FloorSnapshot.PartyView(p.id(), p.name(), p.size(), i + 1,
                    (long) secondsSince(p.joinedAt(), now), estimateMinutes(waitlist.subList(0, i), p.size(), now)));
        }

        return new FloorSnapshot(props.isDemo() ? "DEMO" : "CAMERA", sourceStatus, now.toEpochMilli(), tableViews,
                alertViews, serverViews, suggestedName, partyViews, round1(avgDiningMin), completedSeatings);
    }

    /** Estimate for a party not yet on the list: everyone already waiting is seated first. */
    public synchronized Integer estimateForNewParty(int size) {
        return estimateMinutes(waitlist, size, clock.instant());
    }

    /**
     * Minutes until this party likely gets a table. Simulates the host seating the parties ahead, in order,
     * each at the soonest table that fits (smallest table on a tie). A busy table frees up after the average
     * dining time minus how long it has been seated, plus time to turn it. Null if no table fits the party.
     */
    Integer estimateMinutes(List<Party> ahead, int size, Instant now) {
        if (tables.values().stream().noneMatch(t -> t.config.seats() >= size)) return null;
        record Slot(int seats, double freeInMin) {
        }
        List<Slot> slots = new ArrayList<>();
        for (Table t : tables.values()) {
            double freeIn = switch (t.state) {
                case EMPTY, READY -> t.reservedFor == null ? 0 : avgDiningMin + TURN_TABLE_MIN;
                case SEATED -> Math.max(MIN_REMAINING_MIN, avgDiningMin - secondsSince(t.seatedAt, now) / 60.0) + TURN_TABLE_MIN;
                case BUSSING -> TURN_TABLE_MIN;
            };
            slots.add(new Slot(t.config.seats(), freeIn));
        }
        Comparator<Slot> soonestThenSmallest = Comparator.comparingDouble(Slot::freeInMin).thenComparingInt(Slot::seats);
        for (Party p : ahead) {
            slots.stream().filter(sl -> sl.seats() >= p.size()).min(soonestThenSmallest).ifPresent(taken -> {
                slots.remove(taken);
                slots.add(new Slot(taken.seats(), taken.freeInMin() + avgDiningMin + TURN_TABLE_MIN));
            });
        }
        double minutes = slots.stream().filter(sl -> sl.seats() >= size).min(soonestThenSmallest).orElseThrow().freeInMin();
        return (int) Math.ceil(minutes);
    }

    private String tone(Table t, Instant now) {
        boolean anyOverdue = alerts.stream().anyMatch(a -> a.tableId.equals(t.id()) && secondsSince(a.raisedAt, now) >= overdueAfter(a.type));
        boolean anyOpen = alerts.stream().anyMatch(a -> a.tableId.equals(t.id()) && a.type != AlertType.BUSSING);
        return switch (t.state) {
            case EMPTY -> t.reservedFor != null ? "reserved" : "empty";
            case READY -> "ready";
            case BUSSING -> anyOverdue ? "overdue" : "bussing";
            case SEATED -> anyOverdue ? "overdue" : anyOpen ? "attention" : "ok";
        };
    }

    private double overdueAfter(AlertType type) {
        return switch (type) {
            case NOT_GREETED -> timing.greetWithin();
            case CHECK_IN -> timing.checkInEvery() / 2;
            default -> timing.respondWithin();
        };
    }

    private static double secondsSince(Instant then, Instant now) {
        return then == null ? 0 : Duration.between(then, now).toMillis() / 1000.0;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
