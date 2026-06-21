import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/**
 * Conversation memory (s_context): the second turn must reference the first.
 *
 * Steps:
 *   1. reset server memory (DELETE /api/history)
 *   2. turn 1: "My name is Taro."
 *   3. turn 2: "What is my name?" → the reply must contain "Taro"
 */
public class E2eContextMemory {

    static final String BASE = "http://localhost:18090/";

    public static void main(String[] args) {
        boolean firstReply;
        boolean secondHasName;

        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.setDefaultTimeout(15000);

            // reset the server-side conversation memory before starting
            page.request().delete(BASE + "api/history");

            page.navigate(BASE);
            page.waitForSelector("#send-btn");
            System.out.println("[step] page loaded");

            Locator assistants = page.locator("#chat-area .message.assistant:not(.streaming)");

            // turn 1
            page.fill("#prompt-input", "My name is Taro.");
            page.click("#send-btn");
            firstReply = waitForCount(assistants, 1, 30000, page);
            System.out.println("[step] turn 1 reply arrived = " + firstReply);

            // turn 2 — must remember the name from turn 1
            page.fill("#prompt-input", "What is my name? Answer with just the name.");
            page.click("#send-btn");
            boolean second = waitForCount(assistants, 2, 30000, page);

            String last = second ? page.locator("#chat-area .message.assistant").last().innerText() : "";
            secondHasName = last.toLowerCase().contains("taro");
            System.out.println("[check] second reply contains 'Taro' = " + secondHasName
                    + "  (last reply: \"" + last.replaceAll("\\s+", " ").trim() + "\")");

            browser.close();
        }

        boolean pass = firstReply && secondHasName;
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
