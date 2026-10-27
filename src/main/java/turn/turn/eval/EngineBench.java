package turn.turn.eval;

import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.dnn.Dnn;
import org.opencv.videoio.VideoCapture;
import turn.turn.vision.NativeLoader;
import turn.turn.vision.PeopleDetector;

import java.util.*;

/**
 * OpenCV 5 ships a rewritten dnn graph engine next to the classic one. This times both on Turn's two people
 * detectors over the same real frames, and checks they find the same number of people.
 * Usage: java ... turn.turn.eval.EngineBench clip.mp4 [frames]
 */
public class EngineBench {
    public static void main(String[] args) {
        NativeLoader.load("lib");
        int want = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        List<Mat> frames = new ArrayList<>();
        VideoCapture cap = new VideoCapture(args[0]);
        Mat f = new Mat();
        for (int n = 0; frames.size() < want && cap.read(f); n++) if (n % 10 == 0) frames.add(f.clone());
        cap.release();

        String[][] models = {
                {"YOLOX-S", "lib/models/object_detection_yolox_2022nov.onnx", "0.35"},
                {"NanoDet", "lib/models/object_detection_nanodet_2022nov.onnx", "0.5"}};
        Map<String, Integer> engines = new LinkedHashMap<>();
        engines.put("classic", Dnn.ENGINE_CLASSIC);
        engines.put("new", Dnn.ENGINE_NEW);
        engines.put("auto", Dnn.ENGINE_AUTO);

        System.out.println("| Model | Engine | ms p50 | ms p95 | people/frame | same counts as classic |");
        System.out.println("|---|---|---|---|---|---|");
        for (String[] m : models) {
            int[] classicCounts = null;
            for (var e : engines.entrySet()) {
                PeopleDetector det;
                try {
                    det = PeopleDetector.forModel(m[1], Double.parseDouble(m[2]), e.getValue());
                    det.detect(frames.get(0)); // warm up
                } catch (Exception ex) {
                    System.out.printf("| %s | %s | failed: %s |%n", m[0], e.getKey(), ex.getMessage().lines().findFirst().orElse(""));
                    continue;
                }
                double[] ms = new double[frames.size()];
                int[] counts = new int[frames.size()];
                for (int i = 0; i < frames.size(); i++) {
                    long t = System.nanoTime();
                    List<Rect> people = det.detect(frames.get(i));
                    ms[i] = (System.nanoTime() - t) / 1e6;
                    counts[i] = people.size();
                }
                if (classicCounts == null) classicCounts = counts;
                int same = 0;
                for (int i = 0; i < counts.length; i++) if (counts[i] == classicCounts[i]) same++;
                double[] sorted = ms.clone();
                Arrays.sort(sorted);
                System.out.printf(Locale.ROOT, "| %s | %s | %.0f | %.0f | %.1f | %d/%d frames |%n", m[0], e.getKey(),
                        sorted[sorted.length / 2], sorted[(int) Math.ceil(0.95 * sorted.length) - 1],
                        Arrays.stream(counts).average().orElse(0), same, counts.length);
            }
        }
    }
}
