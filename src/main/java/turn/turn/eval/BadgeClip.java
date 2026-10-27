package turn.turn.eval;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.VideoWriter;
import org.opencv.videoio.Videoio;
import turn.turn.vision.NativeLoader;

/**
 * Builds an end-to-end test clip: the real dining-room footage with a staff badge and a HELP card appearing
 * at Booth 2 at known times, so the whole pipeline (detection, zones, FloorEngine) can be checked on real
 * video. The overlay is drawn on, and the clip is labelled as a test in its corner.
 * Timeline: badge #2 (Marco) 22-25 s, HELP card for table 2 at 32-36 s, badge #2 again 42-45 s. Loops the source.
 * Usage: java ... turn.turn.eval.BadgeClip source.mp4 out.avi [seconds]
 */
public class BadgeClip {
    public static void main(String[] args) {
        NativeLoader.load("lib");
        String src = args[0], out = args[1];
        double seconds = args.length > 2 ? Double.parseDouble(args[2]) : 54;

        VideoCapture cap = new VideoCapture(src);
        double fps = cap.get(Videoio.CAP_PROP_FPS);
        if (fps <= 1) fps = 25;
        Mat frame = new Mat();
        cap.read(frame);
        Size size = frame.size();
        VideoWriter writer = new VideoWriter(out, VideoWriter.fourcc('M', 'J', 'P', 'G'), fps, size);
        Mat badge = BadgePaste.card(2), help = BadgePaste.card(22);

        int total = (int) (seconds * fps);
        for (int i = 0; i < total; i++) {
            if (!cap.read(frame) || frame.empty()) {
                cap.set(Videoio.CAP_PROP_POS_FRAMES, 0);
                cap.read(frame);
            }
            double t = i / fps;
            Mat img = frame.clone();
            // A server leaning in at Booth 2 (zone x 1060-1280, y 500-930): badge on the chest, slightly turned.
            if ((t >= 22 && t < 25) || (t >= 42 && t < 45)) BadgePaste.place(img, badge, 1110, 760, 60, 20);
            // The guest at Booth 2 flips the HELP card on the table.
            if (t >= 32 && t < 36) BadgePaste.place(img, help, 1150, 820, 56, 0);
            Imgproc.putText(img, "TEST CLIP: badge and card drawn in", new Point(24, size.height - 24),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.9, new Scalar(255, 255, 255), 2);
            writer.write(img);
        }
        writer.release();
        cap.release();
        System.out.printf("Wrote %s: %d frames at %.1f fps (%.0f s)%n", out, total, fps, seconds);
    }
}
