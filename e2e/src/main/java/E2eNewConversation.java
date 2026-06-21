import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "New conversation" must reset the server's conversation memory AND open a new I/O-log session
 * (DELETE /api/history); "Clear view" only empties the screen.
 *
 * Deterministic check (no dependence on turn2 rendering): teach a secret in turn1, click New
 * conversation, send turn2; the new session's LLM request must NOT contain the secret (memory was
 * reset) and the session id must be higher (new session boundary). The view must be cleared on click.
 */
public class E2eNewConversation {

    static final String BASE = "http://localhost:18090/";
    static final String SECRET = "Strawberry";

    public static void main(String[] args) {
        boolean turn1Replied;
        boolean displayCleared;
        boolean newSession;
        boolean memoryReset;

        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.setDefaultTimeout(15000);

            page.request().delete(BASE + "api/history");
            page.navigate(BASE);
            page.waitForSelector("#send-btn");
            Locator assistants = page.locator("#chat-area .message.assistant:not(.streaming)");

            // turn1: teach the secret (so it would be in memory if not reset)
            page.fill("#prompt-input", "Remember this: my secret word is " + SECRET + ".");
            page.click("#send-btn");
            turn1Replied = waitForCount(assistants, 1, 40000, page);
            page.waitForTimeout(1500);
            long sessionBefore = maxSessionId(page.request().get(BASE + "api/sessions").text());

            // New conversation: reset server memory + new session, and clear the view
            page.click("#new-conversation-btn");
            page.waitForTimeout(2500);
            displayCleared = assistants.count() == 0;

            // turn2 in the fresh conversation
            page.fill("#prompt-input", "What is my secret word?");
            page.click("#send-btn");

            // Wait for the new session to appear, then inspect its LLM request from the persistent log.
            long sessionAfter = -1;
            String reqMsg = "";
            long deadline = System.currentTimeMillis() + 40000;
            while (System.currentTimeMillis() < deadline) {
                sessionAfter = maxSessionId(page.request().get(BASE + "api/sessions").text());
                if (sessionAfter > sessionBefore) {
                    String logs = page.request().get(BASE + "api/sessions/" + sessionAfter + "/logs").text();
                    if (logs.contains("REQUEST:")) { reqMsg = logs; break; }
                }
                page.waitForTimeout(500);
            }

            newSession = sessionAfter > sessionBefore;
            // The new turn's request must not carry the previous conversation (the secret).
            memoryReset = !reqMsg.isEmpty() && !reqMsg.contains(SECRET);

            System.out.println("[check] turn1 replied            = " + turn1Replied);
            System.out.println("[check] view cleared on New conv  = " + displayCleared);
            System.out.println("[check] new session opened        = " + newSession
                    + "  (" + sessionBefore + " -> " + sessionAfter + ")");
            System.out.println("[check] memory reset (secret gone from new request) = " + memoryReset);

            browser.close();
        }

        boolean pass = turn1Replied && displayCleared && newSession && memoryReset;
        System.out.println(pass ? "=== E2E PASS ===" : "=== E2E FAIL ===");
        System.exit(pass ? 0 : 1);
    }

    static long maxSessionId(String json) {
        Matcher m = Pattern.compile("\"sessionId\"\\s*:\\s*(\\d+)").matcher(json);
        long max = -1;
        while (m.find()) {
            max = Math.max(max, Long.parseLong(m.group(1)));
        }
        return max;
    }

    static boolean waitForCount(Locator loc, int n, long timeoutMs, Page page) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (loc.count() >= n) return true;
            page.waitForTimeout(300);
        }
        return loc.count() >= n;
    }
}
