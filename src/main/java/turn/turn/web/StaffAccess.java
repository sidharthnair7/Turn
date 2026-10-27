package turn.turn.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;

/**
 * Optional staff PIN (turn.staff-pin). Guests on the restaurant Wi-Fi may open the wait page and join the list,
 * but the Floor, Shift flow, camera stream and host actions need the PIN. Empty PIN = open, which is how the
 * public hosted demo runs.
 */
@Component
public class StaffAccess implements HandlerInterceptor {
    static final String COOKIE = "turn_staff";
    private static final Set<String> GUEST_PAGES = Set.of("/", "/index.html", "/wait", "/wait.html", "/login", "/login.html",
            "/api/wait", "/api/login", "/api/health", "/favicon.ico", "/error");

    private final String pin;
    private final String token;

    public StaffAccess(@Value("${turn.staff-pin:}") String pin) {
        this.pin = pin == null ? "" : pin.strip();
        // A random salt per run: restarting Turn signs everyone out, and the cookie never contains the PIN.
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        this.token = this.pin.isEmpty() ? "" : hash(HexFormat.of().formatHex(salt) + this.pin);
    }

    public boolean enabled() {
        return !pin.isEmpty();
    }

    boolean check(String candidate) {
        return enabled() && candidate != null && MessageDigest.isEqual(pin.getBytes(StandardCharsets.UTF_8),
                candidate.strip().getBytes(StandardCharsets.UTF_8));
    }

    Cookie cookie() {
        Cookie c = new Cookie(COOKIE, token);
        c.setHttpOnly(true);
        c.setPath("/");
        c.setMaxAge(14 * 3600); // one long shift
        c.setAttribute("SameSite", "Lax");
        return c;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if (!enabled() || isGuestAllowed(req) || hasToken(req)) return true;
        if (req.getRequestURI().startsWith("/api/") || req.getRequestURI().startsWith("/camera")) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Staff PIN needed");
        } else {
            res.sendRedirect("/login?next=" + java.net.URLEncoder.encode(req.getRequestURI(), StandardCharsets.UTF_8));
        }
        return false;
    }

    private boolean isGuestAllowed(HttpServletRequest req) {
        String path = req.getRequestURI();
        String method = req.getMethod();
        if (GUEST_PAGES.contains(path)) return true;
        if (path.startsWith("/assets/") || path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/cloud/")) return true;
        // Guests join the list and can leave it; everything else at /api/waitlist is the host's.
        if (path.equals("/api/waitlist") && method.equals("POST")) return true;
        return path.startsWith("/api/waitlist/") && method.equals("DELETE");
    }

    private boolean hasToken(HttpServletRequest req) {
        if (req.getCookies() == null) return false;
        for (Cookie c : req.getCookies()) if (COOKIE.equals(c.getName()) && MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), c.getValue().getBytes(StandardCharsets.UTF_8))) return true;
        return false;
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
