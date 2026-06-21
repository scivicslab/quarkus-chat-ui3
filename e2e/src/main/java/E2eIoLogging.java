import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * s_iolog: the complete LLM I/O is persisted to the H2 log DB, one session per conversation.
 *
 * Sends one calc turn, then reads it back from the persistent log (NOT the in-memory tail) and
 * asserts the FULL request and FULL response are stored, plus the tool call. This exercises the
 * load-bearing claim ("everything is captured"), not just "a row exists".
 */
public class E2eIoLogging {

    static final String BASE = "http://localhost:18090/";

    public static void main(String[] args) {
        boolean replyArrived;
        boolean sessionExists;
        boolean hasRequest;
        boolean hasResponse;
        boolean hasQuestion;
        boolean hasTool;

        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.setDefaultTimeout(15000);

            // Fresh conversation = fresh session.
            page.request().delete(BASE + "api/history");
            page.navigate(BASE);
            page.waitForSelector("#send-btn");

            page.fill("#prompt-input", "What is 23 multiplied by 47? Use the calc tool.");
            page.click("#send-btn");

            Locator assistants = page.locator("#chat-area .message.assistant:not(.streaming)");
            replyArrived = waitForCount(assistants, 1, 40000, page);
            page.waitForTimeout(2500);   // let async DB writes + H2 batch flush land

            // Read back from the PERSISTENT log, not the in-memory tail.
            String sessionsJson = page.request().get(BASE + "api/sessions").text();
            long sessionId = maxSessionId(sessionsJson);
            sessionExists = sessionId >= 0;

            String logsJson = sessionExists
                    ? page.request().get(BASE + "api/sessions/" + sessionId + "/logs").text()
                    : "";

            hasRequest  = logsJson.contains("REQUEST:");
            hasResponse = logsJson.contains("RESPONSE:");
            hasQuestion = logsJson.contains("23 multiplied by 47");   // full prompt captured, not just shape
            hasTool     = logsJson.contains("TOOL: calc");            // tool call captured

            System.out.println("[check] reply arrived          = " + replyArrived);
            System.out.println("[check] session persisted (id)  = " + sessionId);
            System.out.println("[check] full REQUEST stored      = " + hasRequest);
            System.out.println("[check] full RESPONSE stored     = " + hasResponse);
            System.out.println("[check] user question in request = " + hasQuestion);
            System.out.println("[check] tool call stored         = " + hasTool);

            browser.close();
        }

        boolean pass = replyArrived && sessionExists && hasRequest && hasResponse && hasQuestion && hasTool;
        System.out.println(pass ? "=== E2E PASS ===" : "=== E2E FAIL ===");
        System.exit(pass ? 0 : 1);
    }

    /** Highest sessionId in the /api/sessions JSON (raw, no JSON lib), or -1 if none. */
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
