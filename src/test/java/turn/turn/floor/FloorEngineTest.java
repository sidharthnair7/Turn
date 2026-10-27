package turn.turn.floor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import turn.turn.config.TurnProperties;
import turn.turn.config.TurnProperties.ServerConfig;
import turn.turn.config.TurnProperties.TableConfig;
import turn.turn.config.TurnProperties.Timing;

import java.time.*;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FloorEngineTest {
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-26T23:00:00Z");

        void advance(double seconds) { now = now.plusMillis((long) (seconds * 1000)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    // seatAfter 2, leaveAfter 5, greet 10, check-in 30, respond 10, ready 10, avg dining 45 min
    static final Timing T = new Timing(2, 5, 10, 30, 10, 10, 45);
    TestClock clock;
    FloorEngine engine;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        TurnProperties props = new TurnProperties("demo", "lib", "model.onnx", 0.5, T, T,
                List.of(new ServerConfig(1, "Priya"), new ServerConfig(2, "Marco")),
                List.of(new TableConfig(1, "Table 1", 2, 0, 0, 10, 10), new TableConfig(2, "Table 2", 4, 20, 0, 10, 10)), 30.0);
        engine = new FloorEngine(props, clock);
    }

    /** Feed the same observation every half second for the given number of seconds. */
    void see(double seconds, TableObservation... obs) {
        for (double s = 0; s < seconds; s += 0.5) {
            engine.tick(List.of(obs), clock.instant());
            clock.advance(0.5);
        }
    }

    FloorSnapshot.TableView table(String id) {
        return engine.snapshot().tables().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void guestsWhoStayTwoSecondsAreSeated() {
        see(1, TableObservation.of("T1", 2, Set.of()));
        assertEquals("EMPTY", table("T1").state());
        see(2, TableObservation.of("T1", 2, Set.of()));
        assertEquals("SEATED", table("T1").state());
    }

    @Test
    void unvisitedTableRaisesNotGreetedAndABadgeVisitClearsItAndClaimsTheTable() {
        see(13, TableObservation.of("T1", 2, Set.of()));
        var alerts = engine.snapshot().alerts();
        assertEquals(1, alerts.size());
        assertEquals("NOT_GREETED", alerts.get(0).type());
        assertTrue(alerts.get(0).suggested(), "no owner yet, so Turn suggests the lightest-loaded server");

        see(1, TableObservation.of("T1", 2, Set.of(2)));
        assertTrue(engine.snapshot().alerts().isEmpty());
        assertEquals("Marco", table("T1").server());
        assertEquals(1, engine.snapshot().servers().stream().filter(s -> s.name().equals("Marco")).findFirst().orElseThrow().load());
    }

    @Test
    void shiftInsightsTrackGreetTimeAndAnswers() {
        see(13, TableObservation.of("T1", 2, Set.of()));
        see(1, TableObservation.of("T1", 2, Set.of(1)));
        var i = engine.snapshot().insights();
        assertEquals(2, i.covers());
        assertEquals(1, i.alertsRaised());
        assertEquals(1, i.alertsAnswered());
        assertEquals(11, i.avgGreetSec(), "seated at 2 s, first badge seen at 13 s");
        assertTrue(i.tips().get(0).contains("over the 10 s target"));
    }

    @Test
    void checkCardRaisesOnceAndClearsOnVisit() {
        see(3, TableObservation.of("T1", 2, Set.of(1)));
        see(2, new TableObservation("T1", 2, Set.of(), false, true));
        assertEquals(List.of("CHECK"), engine.snapshot().alerts().stream().map(FloorSnapshot.AlertView::type).toList());
        see(1, new TableObservation("T1", 2, Set.of(1), false, true));
        assertTrue(engine.snapshot().alerts().isEmpty(), "visit clears it even if the card is still up");
    }

    @Test
    void guestsLeavingNeedsBussingThenABadgeMakesItReady() {
        see(3, TableObservation.of("T1", 2, Set.of(1)));
        see(6, TableObservation.of("T1", 0, Set.of()));
        assertEquals("BUSSING", table("T1").state());
        assertEquals("BUSSING", engine.snapshot().alerts().get(0).type());
        see(1, TableObservation.of("T1", 0, Set.of(1)));
        assertEquals("READY", table("T1").state());
        assertEquals(1, engine.snapshot().completedSeatings());
    }

    @Test
    void guestsWhoSitAtADirtyTableMakeItSeatedAgain() {
        see(3, TableObservation.of("T1", 2, Set.of(1)));
        see(6, TableObservation.of("T1", 0, Set.of()));
        assertEquals("BUSSING", table("T1").state());
        see(3, TableObservation.of("T1", 2, Set.of()));
        assertEquals("SEATED", table("T1").state(), "new guests sat down before anyone bussed it");
        assertTrue(engine.snapshot().alerts().stream().noneMatch(a -> a.type().equals("BUSSING")));
    }

    @Test
    void doubleSeatingIsBlocked() {
        see(3, TableObservation.of("T2", 4, Set.of()));
        Party p = engine.join("Lena", 4);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> engine.seatParty(p.id(), "T2"));
        assertTrue(e.getMessage().contains("Double-seating"));
    }

    @Test
    void seatPicksTheSmallestFreeTableThatFits() {
        Party p = engine.join("Amir", 2);
        assertEquals("Table 1", engine.seatParty(p.id(), null));
        Party big = engine.join("Okafor", 3);
        assertEquals("Table 2", engine.seatParty(big.id(), null));
    }

    @Test
    void aSmallPartyAheadDoesNotBlockABiggerTableThatIsFree() {
        engine.join("Amir", 2);
        engine.join("Lena", 4);
        var list = engine.snapshot().waitlist();
        assertEquals(0, list.get(0).etaMin(), "Amir takes the free 2-top");
        assertEquals(0, list.get(1).etaMin(), "Lena still gets the free 4-top");
        assertEquals(47, engine.estimateForNewParty(2), "both tables now spoken for: next 2-top frees after a full meal");
    }

    @Test
    void waitIsZeroWhileATableIsFreeAndGrowsWhenFull() {
        assertEquals(0, engine.estimateForNewParty(2));
        see(3, TableObservation.of("T1", 2, Set.of(1)), TableObservation.of("T2", 4, Set.of(2)));
        // Both tables just sat down: 45 min average + 2 min to turn the table.
        assertEquals(47, engine.estimateForNewParty(2));
        assertNull(engine.estimateForNewParty(8), "no table seats eight");
    }

    @Test
    void aBlockedTableIsLeftOutOfSeatingAndWaitTimes() {
        assertTrue(engine.toggleBlock("T1"));
        assertEquals("blocked", table("T1").tone());
        assertEquals(1, engine.snapshot().counts().blocked());
        Party p = engine.join("Amir", 2);
        assertEquals("Table 2", engine.seatParty(p.id(), null), "the blocked 2-top is skipped");
        Party lena = engine.join("Lena", 2);
        assertThrows(IllegalStateException.class, () -> engine.seatParty(lena.id(), "T1"));
        engine.leaveWaitlist(lena.id());
        assertEquals(47, engine.estimateForNewParty(2), "only the 4-top counts, and it's taken");
        assertFalse(engine.toggleBlock("T1"), "unblocking puts it back");
        assertEquals(0, engine.estimateForNewParty(2));
    }

    @Test
    void aTableWithGuestsCantBeBlocked() {
        see(3, TableObservation.of("T1", 2, Set.of()));
        assertThrows(IllegalStateException.class, () -> engine.toggleBlock("T1"));
    }

    @Test
    void anArrivingReservationGoesFirstAndIsSeatedWhenATableFits() {
        engine.join("Amir", 2, "Birthday");
        var r = engine.reserve("Patel", 4, clock.instant().plusSeconds(600), "Anniversary");
        assertEquals(1, engine.snapshot().reservations().size());
        assertEquals("Table 2", engine.arrive(r.id()));
        assertTrue(engine.snapshot().reservations().isEmpty());
        assertEquals("Birthday", engine.snapshot().waitlist().get(0).note(), "walk-in notes travel with the party");
    }

    @Test
    void eachWaitingPartyIsSuggestedADifferentFreeTableAndServer() {
        engine.join("Amir", 2);
        engine.join("Kim", 2);
        var list = engine.snapshot().waitlist();
        assertEquals("Table 1", list.get(0).suggestedTable());
        assertEquals("Table 2", list.get(1).suggestedTable(), "a 4-top is fine for a couple, and Table 1 is already offered");
        assertNotNull(list.get(0).suggestedServer());
    }

    @Test
    void shiftFlowRecommendsSeatingAndBussingAndCountsIdleSeats() {
        see(3, TableObservation.of("T2", 4, Set.of(1)));
        see(6, TableObservation.of("T2", 0, Set.of()));
        engine.join("Amir", 2);
        var flow = engine.flow();
        var titles = flow.actions().stream().map(FloorSnapshot.Action::title).toList();
        assertTrue(titles.contains("Seat Amir at Table 1"), titles.toString());
        assertTrue(titles.contains("Bus Table 2"), titles.toString());
        assertEquals(4, flow.capacity().seatsToTurn());
        assertEquals(4 * 30.0, flow.capacity().idleValue(), 0.01);
        assertEquals(4, flow.covers());
        assertEquals(1, flow.tablesTurned());
    }
}
