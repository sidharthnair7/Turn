package turn.turn.demo;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import turn.turn.config.TurnProperties;
import turn.turn.floor.FloorEngine;
import turn.turn.floor.TableObservation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Demo mode: a scripted two-minute dinner service that feeds the same FloorEngine the camera would.
 * Each line says what the "camera" sees at a table between two moments (seconds since start).
 * Labelled DEMO in the UI; it exists so anyone can try Turn without a camera.
 */
@Service
public class DemoSource {
    record Beat(String table, double from, double to, int guests, Set<Integer> badges, boolean help, boolean check) {
        static Beat guests(String table, double from, double to, int n) {
            return new Beat(table, from, to, n, Set.of(), false, false);
        }

        static Beat badge(String table, double from, double to, int badge) {
            return new Beat(table, from, to, 0, Set.of(badge), false, false);
        }

        static Beat help(String table, double from, double to) {
            return new Beat(table, from, to, 0, Set.of(), true, false);
        }

        static Beat check(String table, double from, double to) {
            return new Beat(table, from, to, 0, Set.of(), false, true);
        }
    }

    static final List<Beat> SCRIPT = List.of(
            // Table 1: a couple, greeted fast by Priya, asks for the check, leaves, gets bussed.
            Beat.guests("T1", 0, 92, 2),
            Beat.badge("T1", 6, 8, 1),
            Beat.badge("T1", 32, 34, 1),
            Beat.check("T1", 60, 72),
            Beat.badge("T1", 74, 76, 1),
            Beat.badge("T1", 100, 102, 2),
            // Table 2: a family of four nobody greets. The alert goes amber, then red, until Marco walks over.
            Beat.guests("T2", 10, 130, 4),
            Beat.badge("T2", 46, 48, 2),
            Beat.help("T2", 80, 86),
            Beat.badge("T2", 92, 94, 2),
            // Table 3: greeted by Priya, then left alone long enough to need a check-in. Jun covers it.
            Beat.guests("T3", 18, 130, 2),
            Beat.badge("T3", 22, 24, 1),
            Beat.badge("T3", 66, 68, 3),
            Beat.badge("T3", 100, 102, 3),
            // Table 4: a big party arrives late and is greeted by Jun.
            Beat.guests("T4", 70, 130, 6),
            Beat.badge("T4", 74, 76, 3),
            Beat.badge("T4", 106, 108, 3)
    );
    static final double LENGTH_SEC = 130;

    private final TurnProperties props;
    private final FloorEngine engine;
    private volatile Instant startedAt;
    private volatile boolean running;

    public DemoSource(TurnProperties props, FloorEngine engine) {
        this.props = props;
        this.engine = engine;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!props.isDemo()) return;
        restart();
        running = true;
        Thread t = new Thread(this::run, "turn-demo");
        t.setDaemon(true);
        t.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
    }

    public synchronized void restart() {
        engine.reset();
        engine.join("Amir", 2);
        engine.join("Lena", 4);
        engine.join("The Okafors", 6);
        startedAt = Instant.now();
        engine.setSourceStatus("Demo service running (scripted, no camera)");
    }

    public boolean isActive() {
        return props.isDemo();
    }

    private void run() {
        while (running) {
            Instant now = Instant.now();
            double t = (now.toEpochMilli() - startedAt.toEpochMilli()) / 1000.0;
            if (t > LENGTH_SEC) engine.setSourceStatus("Demo finished. Press Restart demo to run it again.");
            engine.tick(observationsAt(t), now);
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    static List<TableObservation> observationsAt(double t) {
        List<TableObservation> out = new ArrayList<>();
        for (String table : List.of("T1", "T2", "T3", "T4")) {
            int guests = 0;
            Set<Integer> badges = new HashSet<>();
            boolean help = false, check = false;
            for (Beat b : SCRIPT) {
                if (!b.table().equals(table) || t < b.from() || t >= b.to()) continue;
                guests += b.guests();
                badges.addAll(b.badges());
                help |= b.help();
                check |= b.check();
            }
            out.add(new TableObservation(table, guests, badges, help, check));
        }
        return out;
    }
}
