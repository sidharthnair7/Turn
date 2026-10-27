package turn.turn.cloud;

import org.junit.jupiter.api.Test;
import turn.turn.config.TurnProperties;
import turn.turn.config.TurnProperties.ServerConfig;
import turn.turn.config.TurnProperties.TableConfig;
import turn.turn.config.TurnProperties.Timing;
import turn.turn.floor.FloorEngine;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CloudSyncTest {
    static final Timing T = new Timing(2, 5, 10, 30, 10, 10, 45);

    @Test
    void publicBoardHasEstimatesAndCountsButNoGuestNames() {
        TurnProperties props = new TurnProperties("demo", "lib", "model.onnx", 0.5, T, T,
                List.of(new ServerConfig(1, "Priya")),
                List.of(new TableConfig(1, "Table 1", 2, 0, 0, 10, 10), new TableConfig(2, "Table 2", 4, 20, 0, 10, 10)), 30.0);
        FloorEngine engine = new FloorEngine(props);
        engine.join("Sid", 2);
        CloudSync sync = new CloudSync(new AwsProperties("", "us-east-1", 10, null), props, engine);

        Map<String, Object> board = sync.waitBoard(engine.snapshot());

        assertEquals(1, board.get("waiting"));
        assertEquals(2L, board.get("freeTables"));
        @SuppressWarnings("unchecked") Map<String, Integer> eta = (Map<String, Integer>) board.get("etaMinBySize");
        assertEquals(6, eta.size());       // sizes 1 to 6
        assertNull(eta.get("6"));          // no table seats 6
        assertFalse(board.toString().contains("Sid"));
    }
}
