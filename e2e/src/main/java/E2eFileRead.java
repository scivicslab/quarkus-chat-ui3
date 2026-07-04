import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The `read` tool: "read this file and report" must actually read the file. We drop a sentinel file
 * under the server's working directory (~/works), ask the agent to read it, then confirm the
 * PERSISTENT log holds a read-tool entry containing the sentinel (proof the tool ran and read it).
 */
public class E2eFileRead {

    static final String BASE = "http://localhost:18090/";
    // The server process runs from ~/works; the read tool is confined to that directory.
    static final Path FILE = Path.of(System.getProperty("user.home"), "works", "e2e-readtest.md");
    static final String SENTINEL = "SENTINEL_READTOOL_8842";

    public static void main(String[] args) throws Exception {
        Files.writeString(FILE, "Project note.\n" + SENTINEL + "\nEnd of note.\n");

        boolean replyArrived;
        boolean sessionExists;
        boolean toolRanRead;
        boolean sentinelInLog;
        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.setDefaultTimeout(15000);

            page.request().delete(BASE + "api/history");
            page.navigate(BASE);
            page.waitForSelector("#send-btn");

            page.fill("#prompt-input", "Read the file e2e-readtest.md and report its contents. Do nothing else.");
            page.click("#send-btn");

            Locator assistants = page.locator("#chat-area .message.assistant:not(.streaming)");
            replyArrived = waitForCount(assistants, 1, 40000, page);
            page.waitForTimeout(2500);   // async DB writes + H2 batch flush

            String sessionsJson = page.request().get(BASE + "api/sessions").text();
            long sessionId = maxSessionId(sessionsJson);
            sessionExists = sessionId >= 0;
            String logsJson = sessionExists
                    ? page.request().get(BASE + "api/sessions/" + sessionId + "/logs").text()
                    : "";

            toolRanRead   = logsJson.contains("TOOL: read");
            sentinelInLog = logsJson.contains(SENTINEL);

            System.out.println("[check] reply arrived            = " + replyArrived);
            System.out.println("[check] session persisted (id)    = " + sessionId);
            System.out.println("[check] read tool ran             = " + toolRanRead);
            System.out.println("[check] file sentinel in I/O log  = " + sentinelInLog);

            browser.close();
        } finally {
            Files.deleteIfExists(FILE);
        }

        boolean pass = replyArrived && sessionExists && toolRanRead && sentinelInLog;
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
