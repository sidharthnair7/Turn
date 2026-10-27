package turn.turn.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class StaffAccessTest {
    static boolean allowed(StaffAccess a, String method, String path) throws Exception {
        return a.preHandle(new MockHttpServletRequest(method, path), new MockHttpServletResponse(), new Object());
    }

    @Test
    void withNoPinEverythingIsOpen() throws Exception {
        StaffAccess open = new StaffAccess("");
        assertTrue(allowed(open, "GET", "/floor"));
        assertTrue(allowed(open, "POST", "/api/seat"));
    }

    @Test
    void withAPinGuestsGetTheWaitPageOnly() throws Exception {
        StaffAccess a = new StaffAccess("4821");
        assertTrue(allowed(a, "GET", "/wait"));
        assertTrue(allowed(a, "GET", "/api/wait"));
        assertTrue(allowed(a, "POST", "/api/waitlist"), "guests can join the list");
        assertFalse(allowed(a, "GET", "/floor"));
        assertFalse(allowed(a, "GET", "/camera.mjpg"));
        assertFalse(allowed(a, "POST", "/api/seat"));
        assertFalse(allowed(a, "POST", "/api/tables/T1/block"));
    }

    @Test
    void theRightPinGivesACookieThatOpensTheFloor() throws Exception {
        StaffAccess a = new StaffAccess("4821");
        assertFalse(a.check("1234"));
        assertTrue(a.check("4821"));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/floor");
        req.setCookies(a.cookie());
        assertTrue(a.preHandle(req, new MockHttpServletResponse(), new Object()));
        assertFalse(a.cookie().getValue().contains("4821"), "the cookie never holds the PIN");
    }
}
