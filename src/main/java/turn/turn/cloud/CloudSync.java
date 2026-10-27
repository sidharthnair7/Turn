package turn.turn.cloud;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.ObjectMapper;
import turn.turn.config.TurnProperties;
import turn.turn.floor.FloorEngine;
import turn.turn.floor.FloorSnapshot;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The restaurant laptop sits behind the restaurant's router, so guests' phones can't reach it.
 * CloudSync pushes a small, anonymous wait board to S3 every few seconds; S3 serves it publicly with the guest page.
 * Only counts and estimates leave the building: no frames, no faces, no guest names.
 * Shift summaries go to a private prefix so the owner keeps a history after the laptop is closed.
 */
@Component
public class CloudSync {
    private static final Logger log = LoggerFactory.getLogger(CloudSync.class);

    private final AwsProperties aws;
    private final TurnProperties props;
    private final FloorEngine engine;
    private final ObjectMapper json = new ObjectMapper();
    private ScheduledExecutorService timer;
    private S3Client s3;
    private volatile String lastError;

    public CloudSync(AwsProperties aws, TurnProperties props, FloorEngine engine) {
        this.aws = aws;
        this.props = props;
        this.engine = engine;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!aws.enabled()) {
            log.info("Cloud sync off (set turn.aws.bucket to publish the guest wait board to S3)");
            return;
        }
        s3 = S3Client.builder().region(Region.of(aws.region())).build();
        try {
            put("public/index.html", new ClassPathResource("static/cloud/index.html").getContentAsByteArray(), "text/html; charset=utf-8");
        } catch (Exception e) {
            log.warn("Could not upload the guest page: {}", e.getMessage());
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "cloud-sync"));
        timer.scheduleWithFixedDelay(this::publish, 0, Math.max(2, aws.everySec()), TimeUnit.SECONDS);
        log.info("Cloud sync on: s3://{}/public/ every {} s", aws.bucket(), aws.everySec());
    }

    void publish() {
        try {
            FloorSnapshot snap = engine.snapshot();
            put("public/wait.json", json.writeValueAsBytes(waitBoard(snap)), "application/json");
            Map<String, Object> shift = new LinkedHashMap<>();
            shift.put("updatedAt", Instant.now().toString());
            shift.put("source", snap.source());
            shift.put("insights", snap.insights());
            put("shifts/" + LocalDate.now() + ".json", json.writeValueAsBytes(shift), "application/json");
            if (lastError != null) log.info("Cloud sync recovered");
            lastError = null;
        } catch (Exception e) {
            // Never let the cloud take the floor down: the local Floor keeps running, we just retry next tick.
            if (!String.valueOf(e.getMessage()).equals(lastError)) log.warn("Cloud sync failed, will retry: {}", e.getMessage());
            lastError = String.valueOf(e.getMessage());
        }
    }

    /** The public board: an estimate per party size and how many parties are waiting. Nothing personal. */
    Map<String, Object> waitBoard(FloorSnapshot snap) {
        int biggest = props.tables().stream().mapToInt(TurnProperties.TableConfig::seats).max().orElse(4);
        Map<String, Integer> eta = new LinkedHashMap<>();
        for (int size = 1; size <= Math.max(biggest, 6); size++) eta.put(String.valueOf(size), engine.estimateForNewParty(size));
        Map<String, Object> board = new LinkedHashMap<>();
        board.put("updatedAt", Instant.now().toString());
        board.put("source", snap.source());
        board.put("waiting", snap.waitlist().size());
        board.put("freeTables", snap.tables().stream().filter(t -> "EMPTY".equals(t.state())).count());
        board.put("etaMinBySize", eta);
        return board;
    }

    private void put(String key, byte[] body, String type) {
        s3.putObject(PutObjectRequest.builder().bucket(aws.bucket()).key(key).contentType(type)
                .cacheControl("no-cache, max-age=0").build(), RequestBody.fromBytes(body));
    }

    @PreDestroy
    public void stop() {
        if (timer != null) timer.shutdownNow();
        if (s3 != null) s3.close();
    }
}
