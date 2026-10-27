package turn.turn.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import turn.turn.vision.VisionService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** The privacy-blurred camera preview, as a single JPEG or a live MJPEG stream (used by the Floor page). */
@RestController
public class StreamController {
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);

    private final VisionService vision;

    public StreamController(VisionService vision) {
        this.vision = vision;
    }

    @GetMapping(value = "/camera.jpg", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> frame() {
        byte[] jpg = vision.latestJpeg();
        if (jpg == null) return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(jpg);
    }

    /** The same preview as a smooth MJPEG stream: one part per new frame, until the page closes. */
    @GetMapping("/camera.mjpg")
    public ResponseEntity<StreamingResponseBody> stream() {
        StreamingResponseBody body = out -> {
            long sent = -1;
            try {
                while (true) {
                    long n = vision.frameNo();
                    byte[] jpg = vision.latestJpeg();
                    if (n == sent || jpg == null) {
                        Thread.sleep(10);
                        continue;
                    }
                    sent = n;
                    String head = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + jpg.length + "\r\n\r\n";
                    out.write(head.getBytes(StandardCharsets.US_ASCII));
                    out.write(jpg);
                    out.write(CRLF);
                    out.flush();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException closed) {
                // The viewer left; nothing to clean up.
            }
        };
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .contentType(MediaType.parseMediaType("multipart/x-mixed-replace; boundary=frame"))
                .body(body);
    }
}
