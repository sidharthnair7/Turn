package turn.turn.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import turn.turn.cloud.CloudSync;
import turn.turn.floor.FloorEngine;
import turn.turn.vision.VisionService;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/** One place to check that Turn is healthy: the video source, detection speed and the last AWS sync. */
@RestController
public class HealthController {
    private final FloorEngine engine;
    private final VisionService vision;
    private final CloudSync cloud;

    public HealthController(FloorEngine engine, VisionService vision, CloudSync cloud) {
        this.engine = engine;
        this.vision = vision;
        this.cloud = cloud;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        var snap = engine.snapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        boolean cloudOk = !cloud.enabled() || cloud.lastError() == null;
        out.put("status", cloudOk ? "ok" : "degraded");
        out.put("source", snap.source());
        out.put("sourceStatus", snap.sourceStatus());
        out.put("uptimeSec", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        out.put("lastDetectMs", vision.lastDetectMs() < 0 ? null : vision.lastDetectMs());
        out.put("previewFrames", vision.frameNo());
        Map<String, Object> aws = new LinkedHashMap<>();
        aws.put("enabled", cloud.enabled());
        aws.put("lastSyncAt", cloud.lastSyncAt() == null ? null : cloud.lastSyncAt().toString());
        aws.put("lastError", cloud.lastError());
        out.put("aws", aws);
        out.put("tables", snap.tables().size());
        out.put("openAlerts", snap.alerts().size());
        return out;
    }
}
