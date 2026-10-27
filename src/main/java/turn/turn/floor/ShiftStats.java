package turn.turn.floor;

import java.time.Instant;
import java.util.*;

/**
 * Running numbers for the current shift, and plain-language tips built from them.
 * Deliberately per table and per alert type, never per person: Turn doesn't score staff.
 */
class ShiftStats {
    private final long bucketSec;
    private final Instant start;
    /** Bucket start (epoch ms) to {covers, parties seated, parties finished}. */
    private final TreeMap<Long, int[]> timeline = new TreeMap<>();
    private int covers;
    private int seatings;
    private final List<Double> greetSecs = new ArrayList<>();
    private double slowestGreetSec;
    private String slowestGreetTable;
    private final List<Double> turnSecs = new ArrayList<>();
    private int raised;
    private int overdueAnswered;
    private final Map<AlertType, List<Double>> responses = new EnumMap<>(AlertType.class);

    ShiftStats(long bucketSec, Instant start) {
        this.bucketSec = bucketSec;
        this.start = start;
    }

    void seated(int guests, Instant now) {
        seatings++;
        covers += guests;
        int[] b = bucket(now);
        b[0] += guests;
        b[1]++;
    }

    void finished(Instant now) {
        bucket(now)[2]++;
    }

    private int[] bucket(Instant now) {
        long size = bucketSec * 1000;
        return timeline.computeIfAbsent(now.toEpochMilli() / size * size, k -> new int[3]);
    }

    /** Every bucket from the start of the shift to now, empty ones included, so the timeline has no gaps. */
    List<FloorSnapshot.Bucket> timeline(Instant now) {
        long size = bucketSec * 1000;
        List<FloorSnapshot.Bucket> out = new ArrayList<>();
        for (long b = start.toEpochMilli() / size * size; b <= now.toEpochMilli(); b += size) {
            int[] v = timeline.getOrDefault(b, new int[3]);
            out.add(new FloorSnapshot.Bucket(b, v[0], v[1], v[2]));
        }
        return out.size() > 24 ? out.subList(out.size() - 24, out.size()) : out;
    }

    Long peakStart() {
        return timeline.entrySet().stream().filter(e -> e.getValue()[0] > 0)
                .max(Comparator.comparingInt(e -> e.getValue()[0])).map(Map.Entry::getKey).orElse(null);
    }

    long bucketSec() {
        return bucketSec;
    }

    Instant start() {
        return start;
    }

    int turned() {
        return turnSecs.size();
    }

    void greeted(String table, double secs) {
        greetSecs.add(secs);
        if (secs > slowestGreetSec) {
            slowestGreetSec = secs;
            slowestGreetTable = table;
        }
    }

    void turned(double secs) {
        turnSecs.add(secs);
    }

    void raised() {
        raised++;
    }

    void answered(AlertType type, double secs, boolean wasOverdue) {
        responses.computeIfAbsent(type, k -> new ArrayList<>()).add(secs);
        if (wasOverdue) overdueAnswered++;
    }

    FloorSnapshot.Insights view(double greetTargetSec, int openOverdue) {
        List<FloorSnapshot.ResponseView> byType = responses.entrySet().stream()
                .map(e -> new FloorSnapshot.ResponseView(e.getKey().label, e.getValue().size(), Math.round(avg(e.getValue()))))
                .sorted(Comparator.comparingLong(FloorSnapshot.ResponseView::avgSec).reversed())
                .toList();
        int answered = responses.values().stream().mapToInt(List::size).sum();

        List<String> tips = new ArrayList<>();
        if (!greetSecs.isEmpty()) {
            double g = avg(greetSecs);
            tips.add(g > greetTargetSec
                    ? "First visits take " + time(g) + " on average, over the " + time(greetTargetSec) + " target. Send the lightest-loaded server to new tables."
                    : "Guests are greeted in " + time(g) + " on average, inside the " + time(greetTargetSec) + " target.");
            if (slowestGreetTable != null && greetSecs.size() > 1)
                tips.add("Slowest first visit: " + slowestGreetTable + " waited " + time(slowestGreetSec) + ".");
        }
        if (!byType.isEmpty() && byType.get(0).count() > 0 && byType.size() > 1)
            tips.add("Slowest to answer: " + byType.get(0).label() + " (" + time(byType.get(0).avgSec()) + " on average).");
        int overdue = overdueAnswered + openOverdue;
        if (overdue > 0) tips.add(overdue + " alert" + (overdue == 1 ? "" : "s") + " went overdue this shift.");
        if (!turnSecs.isEmpty()) tips.add("Tables are reset " + time(avg(turnSecs)) + " after guests leave, on average.");
        if (tips.isEmpty()) tips.add("Insights appear as the shift runs.");

        return new FloorSnapshot.Insights(covers, seatings,
                greetSecs.isEmpty() ? null : Math.round(avg(greetSecs)),
                turnSecs.isEmpty() ? null : Math.round(avg(turnSecs)),
                raised, answered, overdue, byType, tips);
    }

    private static double avg(List<Double> v) {
        return v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    static String time(double secs) {
        long s = Math.round(secs);
        if (s < 60) return s + " s";
        return (s / 60) + " min " + String.format("%02d", s % 60) + " s";
    }
}
