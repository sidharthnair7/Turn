package turn.turn.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import turn.turn.demo.DemoSource;
import turn.turn.floor.FloorEngine;
import turn.turn.floor.FloorSnapshot;
import turn.turn.floor.Party;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class FloorController {
    private final FloorEngine engine;
    private final DemoSource demo;

    public FloorController(FloorEngine engine, DemoSource demo) {
        this.engine = engine;
        this.demo = demo;
    }

    @GetMapping("/floor")
    public FloorSnapshot floor() {
        return engine.snapshot();
    }

    /** Guest view: the estimate for a new party, plus the guest's own place if they joined. */
    @GetMapping("/wait")
    public Map<String, Object> waitFor(@RequestParam(defaultValue = "2") int size,
                                       @RequestParam(required = false) String party) {
        FloorSnapshot snap = engine.snapshot();
        Integer eta = engine.estimateForNewParty(size);
        var mine = party == null ? null : snap.waitlist().stream().filter(p -> p.id().equals(party)).findFirst().orElse(null);
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("size", size);
        map.put("etaMin", eta);
        map.put("waiting", snap.waitlist().size());
        map.put("source", snap.source());
        map.put("mine", mine);
        map.put("seated", party != null && mine == null);
        return map;
    }

    /** The owner's Shift Flow page. */
    @GetMapping("/flow")
    public FloorSnapshot.Flow flow() {
        return engine.flow();
    }

    /** note is optional: the host adds one for walk-ins ("birthday", "high chair"). */
    public record JoinRequest(String name, int size, String note) {
    }

    @PostMapping("/waitlist")
    public Party join(@RequestBody JoinRequest req) {
        return engine.join(req.name(), req.size(), req.note());
    }

    /** time is "HH:mm" today, in the restaurant's time zone. */
    public record ReserveRequest(String name, int size, String time, String note) {
    }

    @PostMapping("/reservations")
    public Map<String, String> reserve(@RequestBody ReserveRequest req) {
        LocalTime time;
        try {
            time = LocalTime.parse(req.time());
        } catch (Exception e) {
            throw new IllegalArgumentException("Pick a time for the reservation.");
        }
        ZoneId zone = ZoneId.systemDefault();
        var at = LocalDate.now(zone).atTime(time).atZone(zone).toInstant();
        return Map.of("id", engine.reserve(req.name(), req.size(), at, req.note()).id());
    }

    @DeleteMapping("/reservations/{id}")
    public void cancelReservation(@PathVariable String id) {
        engine.cancelReservation(id);
    }

    @PostMapping("/reservations/{id}/arrive")
    public Map<String, String> arrive(@PathVariable String id) {
        String table = engine.arrive(id);
        return table == null ? Map.of() : Map.of("table", table);
    }

    @PostMapping("/tables/{id}/block")
    public Map<String, Boolean> block(@PathVariable String id) {
        return Map.of("blocked", engine.toggleBlock(id));
    }

    @DeleteMapping("/waitlist/{id}")
    public void leave(@PathVariable String id) {
        engine.leaveWaitlist(id);
    }

    public record SeatRequest(String partyId, String tableId) {
    }

    @PostMapping("/seat")
    public Map<String, String> seat(@RequestBody SeatRequest req) {
        return Map.of("table", engine.seatParty(req.partyId(), req.tableId()));
    }

    @PostMapping("/demo/restart")
    public ResponseEntity<Void> restartDemo() {
        if (!demo.isActive()) return ResponseEntity.status(HttpStatus.CONFLICT).build();
        demo.restart();
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> refused(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
