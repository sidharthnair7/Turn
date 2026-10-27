package turn.turn.vision;

import org.junit.jupiter.api.Test;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import turn.turn.config.TurnProperties.TableConfig;
import turn.turn.floor.TableObservation;
import turn.turn.vision.MarkerDetector.Marker;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The glue between OpenCV detections and the floor: zones, badges and cards. Pure Java, no native library. */
class VisionObserveTest {
    static final List<TableConfig> TABLES = List.of(
            new TableConfig(1, "Table 1", 2, 0, 0, 100, 100),
            new TableConfig(2, "Table 2", 4, 200, 0, 100, 100));

    static TableObservation table(VisionService.Frame f, String id) {
        return f.observations().stream().filter(o -> o.tableId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void peopleCountForTheTableWhoseZoneHoldsTheirCentre() {
        var f = VisionService.observe(TABLES, List.of(new Rect(20, 20, 40, 60), new Rect(220, 10, 30, 50), new Rect(500, 500, 40, 40)), List.of());
        assertEquals(1, table(f, "T1").guests());
        assertEquals(1, table(f, "T2").guests());
        assertTrue(f.staffPeople().isEmpty());
    }

    @Test
    void aBadgeMarksOnlyTheNearestPersonAsStaff() {
        // A seated guest and a server leaning over them: both boxes hold the badge, the server's centre is nearer.
        Rect guest = new Rect(10, 10, 80, 80);
        Rect server = new Rect(40, 20, 40, 70);
        var badge = new Marker(2, new Point(60, 50), 30);
        var f = VisionService.observe(TABLES, List.of(guest, server), List.of(badge));
        assertEquals(Integer.valueOf(2), f.staffPeople().get(server));
        assertFalse(f.staffPeople().containsKey(guest), "the guest stays a guest");
        assertEquals(1, table(f, "T1").guests());
        assertEquals(Set.of(2), table(f, "T1").staffBadges());
    }

    @Test
    void aBadgeInAZoneCountsAsAVisitEvenIfNobodyWasDetected() {
        var f = VisionService.observe(TABLES, List.of(), List.of(new Marker(3, new Point(250, 50), 30)));
        assertEquals(Set.of(3), table(f, "T2").staffBadges());
    }

    @Test
    void tableCardsRaiseHelpAndCheckForTheirOwnTable() {
        var f = VisionService.observe(TABLES, List.of(), List.of(new Marker(21, new Point(400, 400), 30), new Marker(32, new Point(10, 10), 30)));
        assertTrue(table(f, "T1").helpCard());
        assertFalse(table(f, "T1").checkCard());
        assertTrue(table(f, "T2").checkCard());
        assertTrue(table(f, "T2").staffBadges().isEmpty(), "cards are not badges");
    }
}
