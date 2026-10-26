package turn.turn.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import turn.turn.vision.VisionService;

/** The privacy-blurred camera preview, as a single JPEG (polled by the Floor page). */
@RestController
public class StreamController {
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
}
