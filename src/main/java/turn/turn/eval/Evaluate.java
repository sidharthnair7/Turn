package turn.turn.eval;

import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;
import turn.turn.vision.NativeLoader;
import turn.turn.vision.PeopleDetector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Offline evaluation on a recorded clip: people found per frame and time per frame for NanoDet and YOLOX,
 * plus annotated frames (boxes from both detectors) so the counts can be checked by hand.
 * Usage: java ... turn.turn.eval.Evaluate clip.mp4 outDir [everyNFrames] [maxSamples]
 */
public class Evaluate {
    public static void main(String[] args) throws Exception {
        NativeLoader.load("lib");
        String clip = args[0];
        Path out = Path.of(args[1]);
        int every = args.length > 2 ? Integer.parseInt(args[2]) : 30;
        int max = args.length > 3 ? Integer.parseInt(args[3]) : 30;
        Files.createDirectories(out);

        PeopleDetector nano = PeopleDetector.forModel("lib/models/object_detection_nanodet_2022nov.onnx", 0.5);
        PeopleDetector yolox = PeopleDetector.forModel("lib/models/object_detection_yolox_2022nov.onnx", 0.35);

        VideoCapture cap = new VideoCapture(clip);
        Mat frame = new Mat();
        List<String> rows = new ArrayList<>();
        rows.add("frame,nanodet_people,nanodet_ms,yolox_people,yolox_ms");
        List<Double> nanoMs = new ArrayList<>(), yoloMs = new ArrayList<>();
        int n = 0, samples = 0, nanoTotal = 0, yoloTotal = 0;
        // Warm up both nets so the first timing isn't model loading.
        if (cap.read(frame)) { nano.detect(frame); yolox.detect(frame); }
        while (samples < max && cap.read(frame)) {
            if (n++ % every != 0) continue;
            long t0 = System.nanoTime();
            List<Rect> a = nano.detect(frame);
            long t1 = System.nanoTime();
            List<Rect> b = yolox.detect(frame);
            long t2 = System.nanoTime();
            double ma = (t1 - t0) / 1e6, mb = (t2 - t1) / 1e6;
            nanoMs.add(ma);
            yoloMs.add(mb);
            nanoTotal += a.size();
            yoloTotal += b.size();
            rows.add(String.format("%d,%d,%.1f,%d,%.1f", n - 1, a.size(), ma, b.size(), mb));
            if (samples % 6 == 0) {
                Mat o = frame.clone();
                for (Rect r : b) Imgproc.rectangle(o, r, new Scalar(0, 200, 0), 3);
                for (Rect r : a) Imgproc.rectangle(o, r, new Scalar(0, 0, 230), 2);
                Imgproc.putText(o, "frame " + (n - 1) + "  green=YOLOX " + b.size() + "  red=NanoDet " + a.size(),
                        new Point(20, 50), Imgproc.FONT_HERSHEY_SIMPLEX, 1.4, new Scalar(255, 255, 255), 3);
                Imgcodecs.imwrite(out.resolve("frame_" + (n - 1) + ".jpg").toString(), o);
            }
            samples++;
        }
        Files.write(out.resolve("detections.csv"), rows);
        System.out.printf("%s: %d sampled frames%n", Path.of(clip).getFileName(), samples);
        System.out.printf("NanoDet: %.1f people/frame, p50 %.0f ms, p95 %.0f ms%n", nanoTotal / (double) samples, pct(nanoMs, 50), pct(nanoMs, 95));
        System.out.printf("YOLOX:   %.1f people/frame, p50 %.0f ms, p95 %.0f ms%n", yoloTotal / (double) samples, pct(yoloMs, 50), pct(yoloMs, 95));
    }

    private static double pct(List<Double> v, int p) {
        double[] a = v.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(a);
        return a.length == 0 ? 0 : a[Math.min(a.length - 1, (int) Math.ceil(p / 100.0 * a.length) - 1)];
    }
}
