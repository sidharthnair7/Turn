package turn.turn.eval;

import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;
import turn.turn.vision.NativeLoader;
import turn.turn.vision.PeopleDetector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * What Turn actually needs from the detector: is each table occupied? Runs NanoDet and YOLOX-S on sampled frames
 * of the dining clip, decides occupancy per table zone the same way VisionService does (a person's box centre
 * inside the zone), and scores it against hand labels.
 * Usage: java ... turn.turn.eval.OccupancyEval clip.mp4 outDir "Counter 1=1;Booth 2=1;Booth 3=1;Booth 4=0"
 * Labels are 1 (occupied) or 0, checked by eye on the start, middle and end frames written to outDir.
 */
public class OccupancyEval {
    record Zone(String name, Rect rect) {
    }

    // The four table zones from application-dining.yml, plus two empty areas.
    static final List<Zone> ZONES = List.of(
            new Zone("Counter 1", new Rect(330, 600, 400, 400)),
            new Zone("Booth 2", new Rect(1060, 500, 220, 430)),
            new Zone("Booth 3", new Rect(1290, 560, 150, 220)),
            new Zone("Booth 4", new Rect(780, 520, 270, 140)),
            // Two empty areas, to catch a detector that calls empty seats occupied.
            new Zone("Empty seats", new Rect(60, 640, 250, 360)),
            new Zone("Empty aisle", new Rect(1460, 640, 220, 330)));

    public static void main(String[] args) throws Exception {
        NativeLoader.load("lib");
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        Map<String, Boolean> truth = new LinkedHashMap<>();
        for (String kv : args[2].split(";")) truth.put(kv.split("=")[0].strip(), kv.split("=")[1].strip().equals("1"));

        List<Mat> frames = new ArrayList<>();
        VideoCapture cap = new VideoCapture(args[0]);
        Mat f = new Mat();
        for (int n = 0; cap.read(f); n++) if (n % 10 == 0) frames.add(f.clone());
        cap.release();
        for (int i : new int[]{0, frames.size() / 2, frames.size() - 1}) {
            Mat img = frames.get(i).clone();
            for (Zone z : ZONES) {
                Imgproc.rectangle(img, z.rect(), new Scalar(0, 220, 255), 3);
                Imgproc.putText(img, z.name(), new Point(z.rect().x + 6, z.rect().y + 28), Imgproc.FONT_HERSHEY_SIMPLEX, 0.9, new Scalar(0, 220, 255), 2);
            }
            Imgcodecs.imwrite(out.resolve("zones_" + i + ".jpg").toString(), img);
        }

        String[][] models = {
                {"NanoDet", "lib/models/object_detection_nanodet_2022nov.onnx", "0.5"},
                {"YOLOX-S", "lib/models/object_detection_yolox_2022nov.onnx", "0.35"}};
        System.out.printf("%d frames (every 10th). Hand labels: %s%n%n", frames.size(), truth);
        System.out.println("| Model | " + String.join(" | ", ZONES.stream().map(Zone::name).toList()) + " | All tables |");
        System.out.println("|---".repeat(ZONES.size() + 2) + "|");
        for (String[] m : models) {
            PeopleDetector det = PeopleDetector.forModel(m[1], Double.parseDouble(m[2]));
            int[] right = new int[ZONES.size()];
            for (Mat frame : frames) {
                List<Rect> people = det.detect(frame);
                for (int z = 0; z < ZONES.size(); z++) {
                    Rect zone = ZONES.get(z).rect();
                    boolean occupied = people.stream().anyMatch(p -> zone.contains(new Point(p.x + p.width / 2.0, p.y + p.height / 2.0)));
                    if (occupied == truth.get(ZONES.get(z).name())) right[z]++;
                }
            }
            StringBuilder row = new StringBuilder("| " + m[0]);
            int all = 0;
            for (int r : right) {
                row.append(String.format(Locale.ROOT, " | %d%%", Math.round(100.0 * r / frames.size())));
                all += r;
            }
            row.append(String.format(Locale.ROOT, " | **%d%%** |", Math.round(100.0 * all / (frames.size() * ZONES.size()))));
            System.out.println(row);
        }
    }
}
