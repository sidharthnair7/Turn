package turn.turn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("turn")
public record TurnProperties(
        String source,
        String opencvDir,
        String modelPath,
        double personScore,
        Timing timing,
        Timing demoTiming,
        List<ServerConfig> servers,
        List<TableConfig> tables) {

    public boolean isDemo() {
        return source == null || source.isBlank() || source.equals("demo");
    }

    public Timing activeTiming() {
        return isDemo() ? demoTiming : timing;
    }

    public record Timing(double seatAfter, double leaveAfter, double greetWithin, double checkInEvery,
                         double respondWithin, double readyFor, double avgDiningMin) {
    }

    public record ServerConfig(int badge, String name) {
    }

    public record TableConfig(int number, String name, int seats, int x, int y, int w, int h) {
        public String id() {
            return "T" + number;
        }

        public int helpMarker() {
            return 20 + number;
        }

        public int checkMarker() {
            return 30 + number;
        }
    }
}
