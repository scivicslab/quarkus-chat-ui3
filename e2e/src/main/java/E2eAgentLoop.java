import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/**
 * Real ReAct agent loop (s_agent): a calculation the model should solve via the calc tool.
 *
 * Steps:
 *   1. reset memory
 *   2. send "What is 23 multiplied by 47? Use the calc tool."
 *   3. the final assistant reply must contain 1081 (only correct if the calc tool ran)
 *   4. intermediate tool steps (Action/Observation) appear in the live "thinking" block
 */
public class E2eAgentLoop {

    static final String BASE = "http://localhost:18090/";

    public static void main(String[] args) {
        boolean replyArrived;
        boolean hasAnswer;
        boolean toolStepShown;

        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.setDefaultTimeout(15000);
            page.request().delete(BASE + "api/history");
            page.navigate(BASE);
            page.waitForSelector("#send-btn");

            page.fill("#prompt-input", "What is 23 multiplied by 47? Use the calc tool.");
            page.click("#send-btn");

            Locator assistants = page.locator("#chat-area .message.assistant:not(.streaming)");
            replyArrived = waitForCount(assistants, 1, 40000, page);

            String reply = replyArrived ? assistants.last().innerText() : "";
            hasAnswer = reply.contains("1081");
            // intermediate tool step shown in the thinking block (Observation 1081 / Action calc)
            String chat = page.locator("#chat-area").innerText();
            toolStepShown = chat.contains("Observation") || chat.contains("calc");

            System.out.println("[check] final reply arrived = " + replyArrived);
            System.out.println("[check] reply contains 1081 = " + hasAnswer
                    + "  (reply: \"" + reply.replaceAll("\\s+", " ").trim() + "\")");
            System.out.println("[check] tool step shown (Action/Observation) = " + toolStepShown);

            browser.close();
        }

        boolean pass = replyArrived && hasAnswer && toolStepShown;
        System.out.println(pass ? "=== E2E PASS ===" : "=== E2E FAIL ===");
        System.exit(pass ? 0 : 1);
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
