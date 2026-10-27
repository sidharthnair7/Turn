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
    private final List<Reservation> reservations = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong();
    private double avgDiningMin;
    private int completedSeatings;
    private ShiftStats stats;
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
        reservations.clear();
        avgDiningMin = timing.avgDiningMin();
        completedSeatings = 0;
        // Hourly buckets for a real shift; the two-minute demo uses 20 s buckets so its timeline has shape.
        stats = new ShiftStats(props.isDemo() ? 20 : 3600, now);
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
                    stats.turned(secondsSince(t.stateSince, now));
                    answer(t, EnumSet.of(AlertType.BUSSING), now);
                    t.moveTo(TableState.READY, now);
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
            if (t.lastVisitAt == null) stats.greeted(t.config.name(), secondsSince(t.seatedAt, now));
            t.lastVisitAt = now;
            if (t.serverBadge == null) t.serverBadge = Collections.min(obs.staffBadges());
            answer(t, EnumSet.of(AlertType.NOT_GREETED, AlertType.CHECK_IN, AlertType.HELP, AlertType.CHECK), now);
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
        t.blocked = false; // people sat there anyway; the camera wins
        clearAlerts(t, EnumSet.allOf(AlertType.class));
        stats.seated(t.guests, now);
    }

    private void leave(Table t, Instant now) {
        double minutes = secondsSince(t.seatedAt, now) / 60.0;
        avgDiningMin = (avgDiningMin * completedSeatings + minutes) / (completedSeatings + 1);
        completedSeatings++;
        stats.finished(now);
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
        if (!open) {
            alerts.add(new Alert(ids.incrementAndGet(), t.id(), type, now));
            stats.raised();
        }
    }

    /** Staff dealt with these alerts: record how long each took, then clear them. */
    private void answer(Table t, Set<AlertType> types, Instant now) {
        for (Alert a : alerts) {
            if (!a.tableId.equals(t.id()) || !types.contains(a.type)) continue;
            double secs = secondsSince(a.raisedAt, now);
            stats.answered(a.type, secs, secs >= overdueAfter(a.type));
        }
        clearAlerts(t, types);
    }

    private void clearAlerts(Table t, Set<AlertType> types) {
        alerts.removeIf(a -> a.tableId.equals(t.id()) && types.contains(a.type));
    }

    // ---------------------------------------------------------------- host and guest actions

    public synchronized Party join(String name, int size) {
        return join(name, size, null);
    }

    /** A guest joining from their phone, or a walk-in the host adds. The note is optional ("birthday"). */
    public synchronized Party join(String name, int size, String note) {
        checkSize(size);
        Party p = new Party(newId(), clean(name, "Guest", 30), size, clock.instant(), clean(note, null, 40));
        waitlist.add(p);
        return p;
    }

    public synchronized Reservation reserve(String name, int size, Instant at, String note) {
        checkSize(size);
        Reservation r = new Reservation(newId(), clean(name, "Guest", 30), size, at, clean(note, null, 40));
        reservations.add(r);
        reservations.sort(Comparator.comparing(Reservation::at));
        return r;
    }

    public synchronized void cancelReservation(String id) {
        reservations.removeIf(r -> r.id().equals(id));
    }

    /**
     * A booked party is at the door. They go to the front of the waitlist, and if a table that fits is free
     * they're seated straight away. Returns the table name, or null if they have to wait for one.
     */
    public synchronized String arrive(String reservationId) {
        Reservation r = reservations.stream().filter(x -> x.id().equals(reservationId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("That reservation is gone."));
        reservations.remove(r);
        Party p = new Party(r.id(), r.name(), r.size(), clock.instant(), r.note() == null ? "Reservation" : "Reservation · " + r.note());
        waitlist.add(0, p);
        return bestFreeTable(r.size()).isPresent() ? seatParty(p.id(), null) : null;
    }

    /** Host takes a free table out of rotation (broken, held, closed section), or puts it back. */
    public synchronized boolean toggleBlock(String tableId) {
        Table t = tables.get(tableId);
        if (t == null) throw new IllegalArgumentException("Unknown table " + tableId + ".");
        if (!t.blocked && (!t.state.isFree() || t.reservedFor != null))
            throw new IllegalStateException(t.config.name() + " has guests. Only a free table can be blocked.");
        t.blocked = !t.blocked;
        return t.blocked;
    }

    private static void checkSize(int size) {
        if (size < 1 || size > 20) throw new IllegalArgumentException("Party size must be between 1 and 20.");
    }

    private static String clean(String s, String fallback, int max) {
        if (s == null || s.isBlank()) return fallback;
        String c = s.strip();
        return c.length() > max ? c.substring(0, max) : c;
    }

    private static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
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
            if (table.blocked) throw new IllegalStateException(table.config.name() + " is blocked. Unblock it first.");
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
                .filter(t -> t.state.isFree() && t.reservedFor == null && !t.blocked && fits(t.config.seats(), size))
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
            TableConfig c = t.config;
            tableViews.add(new FloorSnapshot.TableView(
                    t.id(), c.number(), c.name(), c.seats(), t.state.name(), t.guests,
                    t.state == TableState.SEATED ? (long) secondsSince(t.seatedAt, now) : null,
                    t.state == TableState.SEATED && t.lastVisitAt != null ? (long) secondsSince(t.lastVisitAt, now) : null,
                    serverName(t.serverBadge),
                    t.partyName,
                    t.reservedFor == null ? null : t.reservedFor.name() + " (" + t.reservedFor.size() + ")",
                    t.helpVisible, t.checkVisible, tone(t, now),
                    t.blocked, (long) secondsSince(t.stateSince, now), c.x(), c.y(), c.w(), c.h()));
        }

        List<FloorSnapshot.AlertView> alertViews = alerts.stream()
                .map(a -> {
                    Table t = tables.get(a.tableId);
                    long age = (long) secondsSince(a.raisedAt, now);
                    boolean overdue = age >= overdueAfter(a.type);
                    String owner = serverName(t.serverBadge);
                    return new FloorSnapshot.AlertView(a.id, a.tableId, t.config.name(), a.type.name(), a.type.label,
                            age, overdue, owner != null ? owner : suggestedName, owner == null);
                })
                .sorted(Comparator.comparing(FloorSnapshot.AlertView::overdue).reversed()
                        .thenComparing(Comparator.comparingLong(FloorSnapshot.AlertView::ageSec).reversed()))
                .toList();

        List<FloorSnapshot.ServerView> serverViews = serverNames.entrySet().stream()
                .map(e -> new FloorSnapshot.ServerView(e.getKey(), e.getValue(), load.get(e.getKey()), owned.get(e.getKey()),
                        e.getKey().equals(suggested)))
                .toList();

        // Suggested tables are handed out in list order, so two parties are never offered the same table.
        Set<String> offered = new HashSet<>();
        List<FloorSnapshot.PartyView> partyViews = new ArrayList<>();
        for (int i = 0; i < waitlist.size(); i++) {
            Party p = waitlist.get(i);
            Optional<Table> fit = tables.values().stream()
                    .filter(t -> t.state.isFree() && t.reservedFor == null && !t.blocked && fits(t.config.seats(), p.size())
                            && !offered.contains(t.id()))
                    .min(Comparator.comparingInt(t -> t.config.seats()));
            fit.ifPresent(t -> offered.add(t.id()));
            partyViews.add(new FloorSnapshot.PartyView(p.id(), p.name(), p.size(), i + 1,
                    (long) secondsSince(p.joinedAt(), now), estimateMinutes(waitlist.subList(0, i), p.size(), now),
                    p.note(), fit.map(t -> t.config.name()).orElse(null), fit.isPresent() ? suggestedName : null));
        }

        List<FloorSnapshot.ReservationView> reservationViews = reservations.stream()
                .map(r -> new FloorSnapshot.ReservationView(r.id(), r.name(), r.size(), r.at().toEpochMilli(),
                        Duration.between(now, r.at()).toSeconds(), r.note()))
                .toList();

        return new FloorSnapshot(props.isDemo() ? "DEMO" : "CAMERA", sourceStatus, now.toEpochMilli(), tableViews,
                alertViews, serverViews, suggestedName, partyViews, reservationViews, counts(), round1(avgDiningMin),
                completedSeatings, stats.view(timing.greetWithin(), (int) alertViews.stream().filter(FloorSnapshot.AlertView::overdue).count()));
    }

    private FloorSnapshot.Counts counts() {
        int open = 0, seated = 0, dirty = 0, blocked = 0;
        for (Table t : tables.values()) {
            if (t.blocked) blocked++;
            else if (t.state == TableState.SEATED) seated++;
            else if (t.state == TableState.BUSSING) dirty++;
            else if (t.reservedFor == null) open++;
        }
        return new FloorSnapshot.Counts(open, seated, dirty, blocked);
    }

    private String serverName(Integer badge) {
        return badge == null ? null : serverNames.getOrDefault(badge, "Badge " + badge);
    }

    /**
     * The owner's view of the shift: totals, a timeline, and the next best actions worked out from the live floor.
     * Everything here is per table, per party or per alert type. Nothing ranks a person.
     */
    public synchronized FloorSnapshot.Flow flow() {
        Instant now = clock.instant();
        FloorSnapshot snap = snapshot();

        // Capacity: seats stuck behind bussing, guests waiting, and tables about to open.
        int seatsToTurn = 0, tablesToTurn = 0, openingSoon = 0;
        Integer nextOpen = null;
        String nextOpenTable = null;
        for (Table t : tables.values()) {
            if (t.state == TableState.BUSSING) {
                seatsToTurn += t.config.seats();
                tablesToTurn++;
            } else if (t.state == TableState.SEATED) {
                double left = Math.max(0, avgDiningMin - secondsSince(t.seatedAt, now) / 60.0);
                if (left <= avgDiningMin / 3) openingSoon++;
                int mins = (int) Math.ceil(Math.max(MIN_REMAINING_MIN, left) + TURN_TABLE_MIN);
                if (nextOpen == null || mins < nextOpen) {
                    nextOpen = mins;
                    nextOpenTable = t.config.name();
                }
            }
        }
        int coversWaiting = waitlist.stream().mapToInt(Party::size).sum();
        double check = props.check();
        var capacity = new FloorSnapshot.Capacity(seatsToTurn, tablesToTurn, coversWaiting, openingSoon, nextOpen,
                nextOpenTable, seatsToTurn * check, coversWaiting * check);

        List<FloorSnapshot.Action> actions = new ArrayList<>();
        for (FloorSnapshot.AlertView a : snap.alerts()) {
            if (!a.overdue() || actions.size() >= 2) continue;
            actions.add(new FloorSnapshot.Action("overdue", "Send " + (a.routedTo() == null ? "someone" : a.routedTo()) + " to " + a.tableName(),
                    a.label() + " for " + ShiftStats.time(a.ageSec()) + ". It's overdue."));
        }
        if (!snap.waitlist().isEmpty()) {
            FloorSnapshot.PartyView first = snap.waitlist().get(0);
            if (first.suggestedTable() != null) {
                actions.add(new FloorSnapshot.Action("seat", "Seat " + first.name() + " at " + first.suggestedTable(),
                        "Party of " + first.size() + ", waiting " + ShiftStats.time(first.waitedSec()) + "."
                                + (snap.suggestedServer() == null ? "" : " " + snap.suggestedServer() + " has the lightest section.")));
            } else if (first.etaMin() != null) {
                actions.add(new FloorSnapshot.Action("quote", "Quote " + first.name() + " about " + first.etaMin() + " min",
                        "No free table fits a party of " + first.size() + " yet."
                                + (nextOpenTable == null ? "" : " " + nextOpenTable + " should open first.")));
            }
        }
        tables.values().stream().filter(t -> t.state == TableState.BUSSING)
                .sorted(Comparator.comparing((Table t) -> t.stateSince))
                .limit(2)
                .forEach(t -> actions.add(new FloorSnapshot.Action("bus", "Bus " + t.config.name(),
                        t.config.seats() + " seats waiting to turn for " + ShiftStats.time(secondsSince(t.stateSince, now)) + ".")));
        if (!waitlist.isEmpty()) {
            int parties = waitlist.size();
            tables.values().stream().filter(t -> t.blocked).findFirst().ifPresent(t -> actions.add(new FloorSnapshot.Action("blocked",
                    "Unblock " + t.config.name() + "?", t.config.seats() + " seats are out of rotation while "
                            + parties + (parties == 1 ? " party waits." : " parties wait."))));
        }
        if (actions.isEmpty()) actions.add(new FloorSnapshot.Action("calm", "Floor is covered", "No table is waiting on anyone right now."));

        // Live signals: only the tables worth a glance, not a copy of the whole floor.
        List<FloorSnapshot.Signal> signals = new ArrayList<>();
        for (FloorSnapshot.TableView t : snap.tables()) {
            String title = null, detail = null;
            switch ("BUSSING".equals(t.state()) ? "bussing" : t.tone()) {
                case "overdue", "attention" -> {
                    title = snap.alerts().stream().filter(a -> a.tableId().equals(t.id())).map(FloorSnapshot.AlertView::label)
                            .findFirst().orElse("Needs attention");
                    detail = t.guests() + (t.guests() == 1 ? " guest, " : " guests, ")
                            + (t.server() == null ? "no server yet." : t.server() + "'s table.");
                }
                case "bussing" -> {
                    title = "Dirty " + ShiftStats.time(t.stateSec());
                    detail = "Clear it to open " + t.seats() + " seats.";
                }
                case "empty", "ready" -> {
                    title = "Open now";
                    detail = "Fits " + t.seats() + ". Next party goes to "
                            + (snap.suggestedServer() == null ? "the host" : snap.suggestedServer()) + ".";
                }
                case "blocked" -> {
                    title = "Blocked";
                    detail = t.seats() + " seats out of rotation.";
                }
                default -> {
                    if (t.check()) {
                        title = "Check card up";
                        detail = "The guests want to pay.";
                    }
                }
            }
            if (title != null) signals.add(new FloorSnapshot.Signal(t.name(), title, detail, t.tone()));
        }

        return new FloorSnapshot.Flow(now.toEpochMilli(), snap.source(), stats.start().toEpochMilli(), snap.insights().covers(),
                snap.insights().seatings(), completedSeatings, completedSeatings == 0 ? null : round1(avgDiningMin),
                stats.peakStart(), stats.bucketSec(), stats.timeline(now), actions, signals, capacity, snap.servers(),
                snap.insights(), snap.counts(), check);
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
        if (tables.values().stream().noneMatch(t -> !t.blocked && fits(t.config.seats(), size))) return null;
        record Slot(int seats, double freeInMin) {
        }
        List<Slot> slots = new ArrayList<>();
        for (Table t : tables.values()) {
            if (t.blocked) continue;
            double freeIn = switch (t.state) {
                case EMPTY, READY -> t.reservedFor == null ? 0 : avgDiningMin + TURN_TABLE_MIN;
                case SEATED -> Math.max(MIN_REMAINING_MIN, avgDiningMin - secondsSince(t.seatedAt, now) / 60.0) + TURN_TABLE_MIN;
                case BUSSING -> TURN_TABLE_MIN;
            };
            slots.add(new Slot(t.config.seats(), freeIn));
        }
        Comparator<Slot> soonestThenSmallest = Comparator.comparingDouble(Slot::freeInMin).thenComparingInt(Slot::seats);
        for (Party p : ahead) {
            slots.stream().filter(sl -> fits(sl.seats(), p.size())).min(soonestThenSmallest).ifPresent(taken -> {
                slots.remove(taken);
                slots.add(new Slot(taken.seats(), taken.freeInMin() + avgDiningMin + TURN_TABLE_MIN));
            });
        }
        double minutes = slots.stream().filter(sl -> fits(sl.seats(), size)).min(soonestThenSmallest).orElseThrow().freeInMin();
        return (int) Math.ceil(minutes);
    }

    /**
     * Hosts don't put a couple at a six-top while a six is waiting. A table fits a party if it has enough seats and
     * at most two spare, unless the restaurant has no table that size at all (then any big-enough table will do).
     */
    boolean fits(int seats, int size) {
        if (seats < size) return false;
        if (seats <= size + 2) return true;
        return tables.values().stream().noneMatch(t -> t.config.seats() >= size && t.config.seats() <= size + 2);
    }

    private String tone(Table t, Instant now) {
        boolean anyOverdue = alerts.stream().anyMatch(a -> a.tableId.equals(t.id()) && secondsSince(a.raisedAt, now) >= overdueAfter(a.type));
        boolean anyOpen = alerts.stream().anyMatch(a -> a.tableId.equals(t.id()) && a.type != AlertType.BUSSING);
        return switch (t.state) {
            case EMPTY -> t.blocked ? "blocked" : t.reservedFor != null ? "reserved" : "empty";
            case READY -> t.blocked ? "blocked" : "ready";
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
