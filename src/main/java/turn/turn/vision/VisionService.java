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
    private volatile long frameNo;
    private volatile long lastDetectMs = -1;
    /** The latest detections, drawn onto every frame until the next detection replaces them. Null until the first. */
    private volatile Overlay overlay;
    private final java.util.concurrent.atomic.AtomicBoolean detecting = new java.util.concurrent.atomic.AtomicBoolean();

    private record Overlay(List<Rect> people, Map<Rect, Integer> staffPeople, List<Marker> badges, List<Rect> blur) {
    }
    private FaceBlurrer faces;
    private Thread worker;
    private java.util.concurrent.ExecutorService detector;

    public VisionService(TurnProperties props, FloorEngine engine) {
        this.props = props;
        this.engine = engine;
    }

    public byte[] latestJpeg() {
        return latestJpeg;
    }

    /** How long the last people detection took, or -1 before the first one. */
    public long lastDetectMs() {
        return lastDetectMs;
    }

    /** Goes up by one for every new preview frame, so a stream knows when to send. */
    public long frameNo() {
        return frameNo;
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
        if (detector != null) detector.shutdownNow();
    }

    private void run() {
        try {
            NativeLoader.load(props.opencvDir());
            PeopleDetector people = PeopleDetector.forModel(props.modelPath(), props.personScore());
            MarkerDetector markers = new MarkerDetector();
            // YuNet face model sits next to the person model (OpenCV zoo, face_detection_yunet_2023mar.onnx)
            faces = new FaceBlurrer(java.nio.file.Path.of(props.modelPath()).resolveSibling("face_detection_yunet_2023mar.onnx").toString());
            String src = props.source();
            boolean isFile = src.startsWith("file:");
            // camera:N is a local webcam; file:path is a clip (looped); anything else, like rtsp://user:pass@cctv/stream,
            // goes straight to OpenCV's FFmpeg backend, so an existing CCTV recorder's stream works too.
            VideoCapture cap = isFile ? new VideoCapture(src.substring(5))
                    : src.startsWith("camera:") ? new VideoCapture(Integer.parseInt(src.substring(7)))
                    : new VideoCapture(src);
            if (!cap.isOpened()) {
                engine.setSourceStatus("Camera not available (" + src + ")");
                log.warn("Could not open video source {}", src);
                return;
            }
            detector = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "turn-detect"));
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
                // Detection runs on its own thread so the preview keeps the clip's real frame rate.
                if (t0 - lastCheck >= CHECK_EVERY_MS && detecting.compareAndSet(false, true)) {
                    lastCheck = t0;
                    Mat still = frame.clone();
                    detector.submit(() -> {
                        try {
                            long d0 = System.nanoTime();
                            List<Rect> found = people.detect(still);
                            lastDetectMs = (System.nanoTime() - d0) / 1_000_000;
                            List<Marker> seen = markers.detect(still);
                            List<Rect> blur = faces.regions(still, found);
                            Map<Rect, Integer> staffPeople = analyse(found, seen);
                            overlay = new Overlay(found, staffPeople,
                                    seen.stream().filter(m -> MarkerDetector.isStaffBadge(m.id())).toList(), blur);
                        } catch (Exception e) {
                            log.warn("Detection failed on a frame: {}", e.getMessage());
                        } finally {
                            still.release();
                            detecting.set(false);
                        }
                    });
                }
                latestJpeg = preview(frame, overlay);
                frameNo++;
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

    /** Turns one frame's detections into table observations for the engine. Returns which people are staff. */
    Map<Rect, Integer> analyse(List<Rect> people, List<Marker> found) {
        Frame frame = observe(engine.tableConfigs(), people, found);
        engine.tick(frame.observations(), Instant.now());
        return frame.staffPeople();
    }

    /** One frame, read: what each table looks like, and which person boxes are staff (badge number). */
    record Frame(List<TableObservation> observations, Map<Rect, Integer> staffPeople) {
    }

    /**
     * Pure logic, no OpenCV calls: people boxes and markers in, one observation per table out. A person counts for
     * the table whose zone holds their box centre. A badge marks the one person wearing it as staff. A badge
     * inside a zone counts as a visit even if the detector missed its wearer. Cards count anywhere in view.
     */
    static Frame observe(List<TableConfig> tables, List<Rect> people, List<Marker> found) {
        Map<String, Integer> guests = new HashMap<>();
        Map<String, Set<Integer>> staff = new HashMap<>();
        Set<Integer> cards = new HashSet<>();
        for (TableConfig t : tables) {
            guests.put(t.id(), 0);
            staff.put(t.id(), new HashSet<>());
        }

        List<Marker> badges = found.stream().filter(m -> MarkerDetector.isStaffBadge(m.id())).toList();
        found.stream().filter(m -> !MarkerDetector.isStaffBadge(m.id())).forEach(m -> cards.add(m.id()));

        // Each badge marks one person as staff: the person whose box (grown 10%) holds the badge and whose centre is
        // nearest to it. A server leaning over a seated guest must not turn the guest into staff.
        Map<Rect, Integer> staffPeople = new HashMap<>();
        for (Marker b : badges) {
            Rect wearer = null;
            double best = Double.MAX_VALUE;
            for (Rect p : people) {
                if (staffPeople.containsKey(p) || !grow(p, 0.1).contains(b.centre())) continue;
                double d = Math.hypot(p.x + p.width / 2.0 - b.centre().x, p.y + p.height / 2.0 - b.centre().y);
                if (d < best) {
                    best = d;
                    wearer = p;
                }
            }
            if (wearer != null) staffPeople.put(wearer, b.id());
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
        return new Frame(observations, staffPeople);
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
    private byte[] preview(Mat frame, Overlay o) {
        if (o == null) return blurredWhole(frame);
        List<Rect> people = o.people();
        Map<Rect, Integer> staffPeople = o.staffPeople();
        List<Marker> badges = o.badges();
        Mat out = frame.clone();
        Rect bounds = new Rect(0, 0, out.cols(), out.rows());
        FaceBlurrer.blurRegions(out, o.blur());
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
        // 1280 wide is plenty for the Floor page and keeps encoding fast enough for every frame.
        if (out.cols() > 1280) Imgproc.resize(out, out, new Size(1280, out.rows() * 1280.0 / out.cols()), 0, 0, Imgproc.INTER_AREA);
        MatOfByte jpg = new MatOfByte();
        Imgcodecs.imencode(".jpg", out, jpg, new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 75));
        return jpg.toArray();
    }

    /** Until the first detection finishes, nobody's face has been found yet, so the whole frame is blurred. */
    private static byte[] blurredWhole(Mat frame) {
        Mat out = new Mat();
        Imgproc.resize(frame, out, new Size(frame.cols() / 8.0, frame.rows() / 8.0), 0, 0, Imgproc.INTER_AREA);
        Imgproc.GaussianBlur(out, out, new Size(9, 9), 0);
        Imgproc.resize(out, out, new Size(Math.min(1280, frame.cols()), Math.min(1280, frame.cols()) * frame.rows() / (double) frame.cols()));
        Imgproc.putText(out, "Starting detection...", new Point(24, 48), Imgproc.FONT_HERSHEY_SIMPLEX, 1.1, new Scalar(255, 255, 255), 2);
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
