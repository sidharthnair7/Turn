package turn.turn.vision;

import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.objdetect.ArucoDetector;
import org.opencv.objdetect.Objdetect;

import java.util.ArrayList;
import java.util.List;

/** Reads ArUco markers: staff badges (IDs 0-9) and guest table cards (HELP 20+n, CHECK 30+n). */
public class MarkerDetector {
    public record Marker(int id, Point centre, double side) {
    }

    private final ArucoDetector detector =
            new ArucoDetector(Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50));

    public List<Marker> detect(Mat frame) {
        List<Mat> corners = new ArrayList<>();
        Mat ids = new Mat();
        detector.detectMarkers(frame, corners, ids);
        List<Marker> found = new ArrayList<>();
        if (ids.total() == 0) return found;
        int[] idArr = new int[(int) ids.total()];
        ids.reshape(1, 1).get(0, 0, idArr);
        for (int i = 0; i < idArr.length; i++) {
            float[] c = new float[8];
            corners.get(i).reshape(1, 1).get(0, 0, c);
            Point centre = new Point((c[0] + c[2] + c[4] + c[6]) / 4, (c[1] + c[3] + c[5] + c[7]) / 4);
            double side = Math.hypot(c[2] - c[0], c[3] - c[1]);
            found.add(new Marker(idArr[i], centre, side));
        }
        return found;
    }

    public static boolean isStaffBadge(int id) {
        return id >= 0 && id <= 9;
    }
}
