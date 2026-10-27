package turn.turn.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Exchanges the staff PIN for a signed-in cookie. */
@RestController
public class LoginController {
    private final StaffAccess access;

    public LoginController(StaffAccess access) {
        this.access = access;
    }

    public record LoginRequest(String pin) {
    }

    @PostMapping("/api/login")
    public ResponseEntity<Map<String, Boolean>> login(@RequestBody LoginRequest req, HttpServletResponse res) {
        if (!access.enabled()) return ResponseEntity.ok(Map.of("ok", true));
        if (!access.check(req.pin())) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("ok", false));
        res.addCookie(access.cookie());
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
