package turn.turn.eval;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;
import turn.turn.vision.MarkerDetector;
import turn.turn.vision.NativeLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * How reliably does Turn read a staff badge in a real dining room? Pastes badge #2 into frames from a real
 * restaurant clip at random spots, at several sizes and conditions, and runs Turn's own MarkerDetector on each.
 * Also runs the detector on the untouched frames to count false badges (should be zero).
 * Usage: java ... turn.turn.eval.BadgeEval clip.mp4 out.csv [frames]
 */
public class BadgeEval {
    record Condition(String name, double tilt, double light, int blur) {
    }

    public static void main(String[] args) throws Exception {
        NativeLoader.load("lib");
        String clip = args[0];
        Path out = Path.of(args[1]);
        int want = args.length > 2 ? Integer.parseInt(args[2]) : 40;

        List<Mat> frames = new ArrayList<>();
        VideoCapture cap = new VideoCapture(clip);
        Mat f = new Mat();
        for (int n = 0; frames.size() < want && cap.read(f); n++) if (n % 8 == 0) frames.add(f.clone());
        cap.release();

        MarkerDetector detector = new MarkerDetector();
        int falseBadges = 0;
        for (Mat frame : frames) falseBadges += detector.detect(frame).size();

        int[] sides = {16, 24, 32, 40, 56, 80};
        List<Condition> conditions = List.of(
                new Condition("flat", 0, 1.0, 0),
                new Condition("turned 30°", 30, 1.0, 0),
                new Condition("turned 45°", 45, 1.0, 0),
                new Condition("turned 60°", 60, 1.0, 0),
                new Condition("dim light (45%)", 0, 0.45, 0),
                new Condition("motion blur", 0, 1.0, 9));
        Mat card = BadgePaste.card(2);
        Random rnd = new Random(7);

        List<String> rows = new ArrayList<>();
        rows.add("condition,side_px,frames,found,rate");
        StringBuilder table = new StringBuilder("| Condition | " + String.join(" | ", Arrays.stream(sides).mapToObj(s -> s + " px").toList()) + " |\n");
        table.append("|---".repeat(sides.length + 1)).append("|\n");
        for (Condition c : conditions) {
            table.append("| ").append(c.name());
            for (int side : sides) {
                int found = 0;
                for (Mat frame : frames) {
                    Mat img = frame.clone();
                    double x = 100 + rnd.nextDouble() * (img.cols() - 300), y = 300 + rnd.nextDouble() * (img.rows() - 500);
                    BadgePaste.place(img, card, x, y, side, c.tilt());
                    if (c.light() < 1) img.convertTo(img, -1, c.light(), 0);
                    if (c.blur() > 0) {
                        Mat k = Mat.zeros(c.blur(), c.blur(), CvType.CV_32F);
                        k.row(c.blur() / 2).setTo(new Scalar(1.0 / c.blur()));
                        Imgproc.filter2D(img, img, -1, k);
                    }
                    if (detector.detect(img).stream().anyMatch(m -> m.id() == 2)) found++;
                }
                double rate = found / (double) frames.size();
                rows.add(String.format(Locale.ROOT, "%s,%d,%d,%d,%.2f", c.name(), side, frames.size(), found, rate));
                table.append(String.format(Locale.ROOT, " | %d%%", Math.round(rate * 100)));
            }
            table.append(" |\n");
        }
        Files.write(out, rows);
        System.out.printf("%d real frames from %s, badge #2 at random spots%n%n%s%nFalse badges on the untouched frames: %d%n",
                frames.size(), Path.of(clip).getFileName(), table, falseBadges);
    }
}
