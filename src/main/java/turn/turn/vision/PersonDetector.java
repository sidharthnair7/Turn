package turn.turn.vision;

import org.opencv.core.*;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds people with OpenCV zoo's NanoDet (COCO, 416x416) through OpenCV 5's dnn module.
 * Pre/post-processing is ported from opencv_zoo/models/object_detection_nanodet/nanodet.py.
 */
public class PersonDetector implements PeopleDetector {
    private static final int SIZE = 416, REG_MAX = 7, COCO_CLASSES = 80;
    private static final int[] STRIDES = {8, 16, 32, 64};
    private static final float IOU = 0.6f;

    private final Net net;
    private final float scoreThreshold;

    public PersonDetector(String modelPath, double scoreThreshold) {
        this(modelPath, scoreThreshold, Dnn.ENGINE_AUTO);
    }

    /** engine: one of OpenCV 5's Dnn.ENGINE_* values (AUTO tries the new graph engine, then falls back). */
    public PersonDetector(String modelPath, double scoreThreshold, int engine) {
        this.net = Dnn.readNet(modelPath, "", "", engine);
        this.scoreThreshold = (float) scoreThreshold;
    }

    @Override
    public List<Rect> detect(Mat bgr) {
        // Letterbox to 416x416 in RGB, then (pixel - mean) / std as the zoo model expects.
        Mat rgb = new Mat();
        Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB);
        int h = rgb.rows(), w = rgb.cols();
        int top = 0, left = 0, newH = SIZE, newW = SIZE;
        Mat boxed = new Mat();
        if (h > w) {
            newW = (int) (SIZE * (double) w / h);
            Imgproc.resize(rgb, boxed, new Size(newW, newH), 0, 0, Imgproc.INTER_AREA);
            left = (SIZE - newW) / 2;
            Core.copyMakeBorder(boxed, boxed, 0, 0, left, SIZE - newW - left, Core.BORDER_CONSTANT, new Scalar(0));
        } else {
            newH = (int) (SIZE * (double) h / w);
            Imgproc.resize(rgb, boxed, new Size(newW, newH), 0, 0, Imgproc.INTER_AREA);
            top = (SIZE - newH) / 2;
            Core.copyMakeBorder(boxed, boxed, top, SIZE - newH - top, 0, 0, Core.BORDER_CONSTANT, new Scalar(0));
        }
        boxed.convertTo(boxed, CvType.CV_32F);
        Core.subtract(boxed, new Scalar(103.53, 116.28, 123.675), boxed);
        Core.divide(boxed, new Scalar(57.375, 57.12, 58.395), boxed);

        List<Mat> outs = new ArrayList<>();
        synchronized (net) {
            net.setInput(Dnn.blobFromImage(boxed));
            net.forward(outs, net.getUnconnectedOutLayersNames());
        }

        List<Rect2d> boxes = new ArrayList<>();
        List<Float> scores = new ArrayList<>();
        double rw = w / (double) newW, rh = h / (double) newH;
        for (int stride : STRIDES) {
            int feat = SIZE / stride, cells = feat * feat;
            // OpenCV 5 returns outputs in a different order than the zoo's Python demo assumes; match by size.
            Mat clsMat = null, regMat = null;
            for (Mat o : outs) {
                if (o.total() == (long) cells * COCO_CLASSES) clsMat = o;
                if (o.total() == (long) cells * 4 * (REG_MAX + 1)) regMat = o;
            }
            if (clsMat == null || regMat == null) continue;
            float[] cls = floats(clsMat), reg = floats(regMat);
            for (int i = 0; i < cells; i++) {
                float person = cls[i * COCO_CLASSES]; // COCO class 0 = person
                if (person < scoreThreshold) continue;
                double cx = (i % feat) * stride + 0.5 * (stride - 1);
                double cy = (i / feat) * stride + 0.5 * (stride - 1);
                double[] d = new double[4];
                for (int side = 0; side < 4; side++) {
                    double sum = 0, expect = 0;
                    for (int k = 0; k <= REG_MAX; k++) {
                        double e = Math.exp(reg[i * 4 * (REG_MAX + 1) + side * (REG_MAX + 1) + k]);
                        sum += e;
                        expect += e * k;
                    }
                    d[side] = expect / sum * stride;
                }
                double x1 = clamp(cx - d[0]), y1 = clamp(cy - d[1]), x2 = clamp(cx + d[2]), y2 = clamp(cy + d[3]);
                double fx1 = Math.max((x1 - left) * rw, 0), fy1 = Math.max((y1 - top) * rh, 0);
                double fx2 = Math.min((x2 - left) * rw, w), fy2 = Math.min((y2 - top) * rh, h);
                boxes.add(new Rect2d(fx1, fy1, fx2 - fx1, fy2 - fy1));
                scores.add(person);
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
        for (int i : keep.toArray()) {
            Rect2d r = boxes.get(i);
            people.add(new Rect((int) r.x, (int) r.y, (int) r.width, (int) r.height));
        }
        return people;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(SIZE, v));
    }

    private static float[] floats(Mat m) {
        float[] a = new float[(int) m.total()];
        (m.isContinuous() ? m : m.clone()).reshape(1, 1).get(0, 0, a);
        return a;
    }
}
