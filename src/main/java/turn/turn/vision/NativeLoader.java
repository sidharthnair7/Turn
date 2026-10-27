package turn.turn.vision;

import java.nio.file.Files;
import java.nio.file.Path;

/** Loads OpenCV 5's native library once, from the folder named by turn.opencv-dir. */
public final class NativeLoader {
    private static boolean loaded;

    private NativeLoader() {
    }

    public static synchronized void load(String opencvDir) {
        if (loaded) return;
        Path dll = Path.of(opencvDir, System.mapLibraryName("opencv_java500")).toAbsolutePath();
        if (!Files.exists(dll)) {
            throw new IllegalStateException("OpenCV native library not found at " + dll
                    + ". Copy opencv_java500.dll from OpenCV 5.0.0's build/java/x64 folder (see README).");
        }
        System.load(dll.toString());
        loaded = true;
    }
}
