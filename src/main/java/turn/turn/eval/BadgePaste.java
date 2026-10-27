package turn.turn.eval;

import org.opencv.core.*;
import org.opencv.geometry.Geometry;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.Objdetect;

/**
 * Puts a printed-looking ArUco marker into a real video frame, at a given size, turned away from the camera
 * by a given angle, so badge and table-card detection can be tested on real restaurant backgrounds.
 */
final class BadgePaste {
    private BadgePaste() {
    }

    /** The marker with a white quiet zone, as a printed card would look (BGR). */
    static Mat card(int id) {
        Mat marker = new Mat();
        Objdetect.generateImageMarker(Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50), id, 400, marker);
        Mat withBorder = new Mat();
        Core.copyMakeBorder(marker, withBorder, 60, 60, 60, 60, Core.BORDER_CONSTANT, new Scalar(255));
        Imgproc.cvtColor(withBorder, withBorder, Imgproc.COLOR_GRAY2BGR);
        return withBorder;
    }

    /**
     * Paste the card so its black square is {@code side} px wide, top-left at (x, y), turned {@code tiltDeg}
     * around the vertical axis (horizontal squash plus a little perspective). Modifies {@code frame}.
     */
    static void place(Mat frame, Mat card, double x, double y, int side, double tiltDeg) {
        double full = side * (card.cols() / 400.0);
        double squash = Math.cos(Math.toRadians(tiltDeg));
        double w = full * squash, skew = full * 0.08 * Math.sin(Math.toRadians(tiltDeg));
        MatOfPoint2f src = new MatOfPoint2f(new Point(0, 0), new Point(card.cols(), 0),
                new Point(card.cols(), card.rows()), new Point(0, card.rows()));
        MatOfPoint2f dst = new MatOfPoint2f(new Point(x, y), new Point(x + w, y + skew),
                new Point(x + w, y + full - skew), new Point(x, y + full));
        Mat h = Geometry.getPerspectiveTransform(src, dst);
        Mat warped = new Mat();
        Imgproc.warpPerspective(card, warped, h, frame.size(), Imgproc.INTER_AREA, Core.BORDER_CONSTANT, new Scalar(0, 0, 0));
        Mat mask = new Mat();
        Imgproc.warpPerspective(new Mat(card.size(), CvType.CV_8UC1, new Scalar(255)), mask, h, frame.size());
        warped.copyTo(frame, mask);
    }
}
