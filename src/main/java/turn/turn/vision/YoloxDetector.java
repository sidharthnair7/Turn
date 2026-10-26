package turn.turn.vision;

import org.opencv.core.*;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds people with OpenCV zoo's YOLOX-S (COCO, 640x640). Slower than NanoDet (about 180 ms a frame on a laptop)
 * but it sees seated diners across a whole dining room, which NanoDet missed in testing.
 * Pre/post-processing ported from opencv_zoo/models/object_detection_yolox/yolox.py.
 */
public class YoloxDetector implements PeopleDetector {
    private static final int SIZE = 640, COLS = 85; // 4 box + objectness + 80 classes
    private static final int[] STRIDES = {8, 16, 32};
    private static final float IOU = 0.5f;

    private final Net net;
    private final float scoreThreshold;

    public YoloxDetector(String modelPath, double scoreThreshold) {
        this.net = Dnn.readNet(modelPath);
        this.scoreThreshold = (float) scoreThreshold;
    }

    @Override
    public List<Rect> detect(Mat bgr) {
        // RGB, scaled to fit 640x640 and padded with grey (114) at the bottom/right, raw 0-255 values.
        Mat rgb = new Mat();
        Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB);
        double ratio = Math.min(SIZE / (double) rgb.rows(), SIZE / (double) rgb.cols());
        Mat resized = new Mat();
        Imgproc.resize(rgb, resized, new Size((int) (rgb.cols() * ratio), (int) (rgb.rows() * ratio)));
        Mat padded = new Mat(SIZE, SIZE, CvType.CV_8UC3, new Scalar(114, 114, 114));
        resized.copyTo(padded.submat(new Rect(0, 0, resized.cols(), resized.rows())));
        padded.convertTo(padded, CvType.CV_32F);

        Mat out;
        synchronized (net) {
            net.setInput(Dnn.blobFromImage(padded));
            out = net.forward();
        }
        float[] d = new float[(int) out.total()];
        out.reshape(1, 1).get(0, 0, d);
        int rows = d.length / COLS;

        List<Rect2d> boxes = new ArrayList<>();
        List<Float> scores = new ArrayList<>();
        int i = 0;
        for (int stride : STRIDES) {
            int grid = SIZE / stride;
            for (int y = 0; y < grid; y++) {
                for (int x = 0; x < grid && i < rows; x++, i++) {
                    int b = i * COLS;
                    float person = d[b + 4] * d[b + 5]; // objectness x P(person)
                    if (person < scoreThreshold) continue;
                    double cx = (d[b] + x) * stride, cy = (d[b + 1] + y) * stride;
                    double w = Math.exp(d[b + 2]) * stride, h = Math.exp(d[b + 3]) * stride;
                    boxes.add(new Rect2d((cx - w / 2) / ratio, (cy - h / 2) / ratio, w / ratio, h / ratio));
                    scores.add(person);
                }
            }
        }

        List<Rect> people = new ArrayList<>();
        if (boxes.isEmpty()) return people;
        MatOfRect2d mBoxes = new MatOfRect2d();
        mBoxes.fromList(boxes);
        MatOfFloat mScores = new MatOfFloat();
        mScores.fromList(scores);
        MatOfInt keep = new MatOfInt();
        Dnn.NMSBoxes(mBoxes, mScores, scoreThreshold, IOU, keep);
        for (int k : keep.toArray()) {
            Rect2d r = boxes.get(k);
            people.add(new Rect((int) r.x, (int) r.y, (int) r.width, (int) r.height));
        }
        return people;
    }
}
