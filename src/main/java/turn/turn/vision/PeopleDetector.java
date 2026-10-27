package turn.turn.vision;

import org.opencv.core.Mat;
import org.opencv.core.Rect;

import java.util.List;

/** Finds people in a frame. NanoDet is fast; YOLOX sees more of a full dining room. */
public interface PeopleDetector {
    List<Rect> detect(Mat bgr);

    /** Picks the detector from the model file name (turn.model-path). */
    static PeopleDetector forModel(String modelPath, double score) {
        return forModel(modelPath, score, org.opencv.dnn.Dnn.ENGINE_AUTO);
    }

    /** Same, with an explicit OpenCV 5 dnn engine (Dnn.ENGINE_CLASSIC, ENGINE_NEW, ENGINE_AUTO). */
    static PeopleDetector forModel(String modelPath, double score, int engine) {
        return modelPath.contains("yolox") ? new YoloxDetector(modelPath, score, engine) : new PersonDetector(modelPath, score, engine);
    }
}
