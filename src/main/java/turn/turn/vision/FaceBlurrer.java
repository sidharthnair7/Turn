package turn.turn.vision;

import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.FaceDetectorYN;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Blurs faces only, so the preview still shows bodies and tables. Uses OpenCV zoo's YuNet face detector.
 * If a detected person has no face found inside their box (turned away, too small), the top of their box
 * is blurred instead, so nobody is left identifiable when face detection misses.
 */
public class FaceBlurrer {
    private static final double HEAD_SHARE = 0.3; // fallback: top 30% of a person box

    private final FaceDetectorYN yunet;

    public FaceBlurrer(String modelPath) {
        this.yunet = Files.exists(Path.of(modelPath))
                ? FaceDetectorYN.create(modelPath, "", new Size(320, 320), 0.6f, 0.3f, 50)
                : null;
    }

    public void blurFaces(Mat out, List<Rect> people) {
        Rect bounds = new Rect(0, 0, out.cols(), out.rows());
        List<Rect> faces = detectFaces(out);
        for (Rect f : faces) blur(out, clip(grow(f, 0.2), bounds));
        for (Rect p : people) {
            boolean hasFace = faces.stream().anyMatch(f -> p.contains(new Point(f.x + f.width / 2.0, f.y + f.height / 2.0)));
            if (!hasFace) blur(out, clip(new Rect(p.x, p.y, p.width, (int) (p.height * HEAD_SHARE)), bounds));
        }
    }

    private List<Rect> detectFaces(Mat frame) {
        List<Rect> found = new ArrayList<>();
        if (yunet == null) return found;
        Mat faces = new Mat();
        synchronized (yunet) {
            yunet.setInputSize(frame.size());
            yunet.detect(frame, faces);
        }
        for (int i = 0; i < faces.rows(); i++) {
            float[] row = new float[faces.cols()];
            faces.get(i, 0, row);
            found.add(new Rect((int) row[0], (int) row[1], (int) row[2], (int) row[3]));
        }
        return found;
    }

    private static void blur(Mat out, Rect r) {
        if (r.width < 2 || r.height < 2) return;
        Mat roi = out.submat(r);
        Imgproc.blur(roi, roi, new Size(Math.max(15, r.width / 2), Math.max(15, r.height / 2)));
    }

    private static Rect grow(Rect r, double by) {
        int dx = (int) (r.width * by), dy = (int) (r.height * by);
        return new Rect(r.x - dx, r.y - dy, r.width + 2 * dx, r.height + 2 * dy);
    }

    private static Rect clip(Rect a, Rect b) {
        int x1 = Math.max(a.x, b.x), y1 = Math.max(a.y, b.y);
        int x2 = Math.min(a.x + a.width, b.x + b.width), y2 = Math.min(a.y + a.height, b.y + b.height);
        return new Rect(x1, y1, Math.max(0, x2 - x1), Math.max(0, y2 - y1));
    }
}
