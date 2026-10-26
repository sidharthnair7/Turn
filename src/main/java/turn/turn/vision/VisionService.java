package turn.turn.vision;

import jakarta.annotation.PreDestroy;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import turn.turn.config.TurnProperties;
import turn.turn.config.TurnProperties.TableConfig;
import turn.turn.floor.FloorEngine;
import turn.turn.floor.FloorSnapshot;
import turn.turn.floor.TableObservation;
import turn.turn.vision.MarkerDetector.Marker;

import java.time.Instant;
import java.util.*;

/**
 * Reads the camera or a clip, finds people and markers with OpenCV 5, turns them into one
 * observation per table for the FloorEngine, and keeps a preview frame with every face blurred.
 * Raw frames never leave this class and are never written to disk.
 */
@Service
public class VisionService {
    private static final Logger log = LoggerFactory.getLogger(VisionService.class);
    private static final long CHECK_EVERY_MS = 200;

    private final TurnProperties props;
    private final FloorEngine engine;
    private volatile boolean running;
    private volatile byte[] latestJpeg;
    private FaceBlurrer faces;
    private Thread worker;

    public VisionService(TurnProperties props, FloorEngine engine) {
        this.props = props;
        this.engine = engine;
    }

    public byte[] latestJpeg() {
        return latestJpeg;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (props.isDemo()) return;
        running = true;
        worker = new Thread(this::run, "turn-vision");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
    }

    private void run() {
        try {
            NativeLoader.load(props.opencvDir());
            PersonDetector people = new PersonDetector(props.modelPath(), props.personScore());
            MarkerDetector markers = new MarkerDetector();
            // YuNet face model sits next to the person model (OpenCV zoo, face_detection_yunet_2023mar.onnx)
            faces = new FaceBlurrer(java.nio.file.Path.of(props.modelPath()).resolveSibling("face_detection_yunet_2023mar.onnx").toString());
            String src = props.source();
            boolean isFile = src.startsWith("file:");
            VideoCapture cap = isFile ? new VideoCapture(src.substring(5))
                    : new VideoCapture(Integer.parseInt(src.substring(src.indexOf(':') + 1)));
            if (!cap.isOpened()) {
                engine.setSourceStatus("Camera not available (" + src + ")");
                log.warn("Could not open video source {}", src);
                return;
            }
            double fps = cap.get(Videoio.CAP_PROP_FPS);
            long frameMs = isFile && fps > 1 ? (long) (1000 / fps) : 0;
            engine.setSourceStatus("Live: " + src);

            Mat frame = new Mat();
            long lastCheck = 0;
            while (running) {
                long t0 = System.currentTimeMillis();
                if (!cap.read(frame) || frame.empty()) {
                    if (isFile) { // loop the clip
                        cap.set(Videoio.CAP_PROP_POS_FRAMES, 0);
                        continue;
                    }
                    engine.setSourceStatus("Camera stopped sending frames");
                    Thread.sleep(500);
                    continue;
                }
                if (t0 - lastCheck >= CHECK_EVERY_MS) {
                    lastCheck = t0;
                    analyse(frame, people.detect(frame), markers.detect(frame));
                }
                if (frameMs > 0) {
                    long spare = frameMs - (System.currentTimeMillis() - t0);
                    if (spare > 0) Thread.sleep(spare);
                }
            }
            cap.release();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            engine.setSourceStatus("Vision error: " + e.getMessage());
            log.error("Vision loop failed", e);
        }
    }

    void analyse(Mat frame, List<Rect> people, List<Marker> found) {
        List<TableConfig> tables = engine.tableConfigs();
        Map<String, Integer> guests = new HashMap<>();
        Map<String, Set<Integer>> staff = new HashMap<>();
        Set<Integer> cards = new HashSet<>();
        for (TableConfig t : tables) {
            guests.put(t.id(), 0);
            staff.put(t.id(), new HashSet<>());
        }

        List<Marker> badges = found.stream().filter(m -> MarkerDetector.isStaffBadge(m.id())).toList();
        found.stream().filter(m -> !MarkerDetector.isStaffBadge(m.id())).forEach(m -> cards.add(m.id()));

        Map<Rect, Integer> staffPeople = new HashMap<>();
        for (Rect p : people) {
            Rect grown = grow(p, 0.1);
            for (Marker b : badges) if (grown.contains(b.centre())) staffPeople.put(p, b.id());
        }

        for (Rect p : people) {
            Point centre = new Point(p.x + p.width / 2.0, p.y + p.height / 2.0);
            TableConfig t = tableAt(tables, centre);
            if (t == null) continue;
            if (staffPeople.containsKey(p)) staff.get(t.id()).add(staffPeople.get(p));
            else guests.merge(t.id(), 1, Integer::sum);
        }
        // A badge inside a zone counts as a staff visit even if the detector missed the person wearing it.
        for (Marker b : badges) {
            TableConfig t = tableAt(tables, b.centre());
            if (t != null) staff.get(t.id()).add(b.id());
        }

        List<TableObservation> observations = new ArrayList<>();
        for (TableConfig t : tables) {
            observations.add(new TableObservation(t.id(), guests.get(t.id()), staff.get(t.id()),
                    cards.contains(t.helpMarker()), cards.contains(t.checkMarker())));
        }
        engine.tick(observations, Instant.now());
        latestJpeg = preview(frame, people, staffPeople, badges);
    }

    private static TableConfig tableAt(List<TableConfig> tables, Point p) {
        for (TableConfig t : tables) if (new Rect(t.x(), t.y(), t.w(), t.h()).contains(p)) return t;
        return null;
    }

    private static Rect grow(Rect r, double by) {
        int dx = (int) (r.width * by), dy = (int) (r.height * by);
        return new Rect(r.x - dx, r.y - dy, r.width + 2 * dx, r.height + 2 * dy);
    }

    /** Privacy view: every face blurred, zones coloured by table state, staff labelled by badge only. */
    private byte[] preview(Mat frame, List<Rect> people, Map<Rect, Integer> staffPeople, List<Marker> badges) {
        Mat out = frame.clone();
        Rect bounds = new Rect(0, 0, out.cols(), out.rows());
        if (faces != null) faces.blurFaces(out, people);
        for (Rect p : people) {
            Rect r = intersect(p, bounds);
            if (r.width < 2 || r.height < 2) continue;
            boolean isStaff = staffPeople.containsKey(p);
            Scalar c = isStaff ? new Scalar(255, 200, 80) : new Scalar(220, 220, 220);
            Imgproc.rectangle(out, r, c, 2);
            Imgproc.putText(out, isStaff ? "STAFF #" + staffPeople.get(p) : "GUEST", new Point(r.x, Math.max(14, r.y - 4)),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, c, 1);
        }
        for (Marker b : badges) Imgproc.circle(out, b.centre(), 6, new Scalar(255, 200, 80), -1);

        FloorSnapshot snap = engine.snapshot();
        for (TableConfig t : engine.tableConfigs()) {
            String tone = snap.tables().stream().filter(v -> v.id().equals(t.id())).findFirst().map(FloorSnapshot.TableView::tone).orElse("empty");
            Scalar c = switch (tone) {
                case "ok" -> new Scalar(94, 197, 34);
                case "attention" -> new Scalar(11, 158, 245);
                case "overdue" -> new Scalar(68, 68, 239);
                case "bussing" -> new Scalar(246, 130, 59);
                case "ready" -> new Scalar(53, 230, 163);
                default -> new Scalar(128, 114, 107);
            };
            Rect zone = new Rect(t.x(), t.y(), t.w(), t.h());
            Imgproc.rectangle(out, zone, c, 3);
            Imgproc.putText(out, t.name() == null ? t.id() : t.name(), new Point(t.x() + 6, t.y() + 22), Imgproc.FONT_HERSHEY_SIMPLEX, 0.7, c, 2);
        }
        MatOfByte jpg = new MatOfByte();
        Imgcodecs.imencode(".jpg", out, jpg, new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 75));
        return jpg.toArray();
    }

    private static Rect intersect(Rect a, Rect b) {
        int x1 = Math.max(a.x, b.x), y1 = Math.max(a.y, b.y);
        int x2 = Math.min(a.x + a.width, b.x + b.width), y2 = Math.min(a.y + a.height, b.y + b.height);
        return new Rect(x1, y1, Math.max(0, x2 - x1), Math.max(0, y2 - y1));
    }
}
