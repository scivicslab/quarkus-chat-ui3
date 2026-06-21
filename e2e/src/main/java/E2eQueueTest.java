import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/**
 * End-to-end reproduction of the "second message stuck in Queue / Cancel stays red" bug.
 *
 * Scenario:
 *   1. open the chat UI
 *   2. send "hello", wait for the assistant reply
 *   3. assert the turn finished: #cancel-btn is disabled (busy cleared)
 *   4. send "how are ya?" and assert a SECOND assistant reply arrives (the queue drained)
 *
 * With the bug, the result event carries no busy flag, so busy is never cleared:
 * step 3 fails (cancel still enabled) and step 4 times out (second turn never sent).
 */
public class E2eQueueTest {

    static final String BASE = "http://localhost:18090/";

    public static void main(String[] args) {
        boolean cancelDisabledAfter1;
        boolean secondReplyArrived;
        boolean noRequestFailed;

        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.setDefaultTimeout(15000);

            page.navigate(BASE);
            page.waitForSelector("#send-btn");
            page.waitForSelector("#prompt-input");
            System.out.println("[step] page loaded");

            // 1st turn
            page.fill("#prompt-input", "へんじはどうした？");
            page.click("#send-btn");
            boolean firstArrived = waitForAssistantCount(page, 1, 30000);
            System.out.println("[step] first assistant reply arrived = " + firstArrived);

            // no client-side "Request failed" error should appear (POST must return valid JSON)
            String chatText = page.locator("#chat-area").innerText();
            noRequestFailed = !chatText.contains("Request failed");
            System.out.println("[check] no 'Request failed' error = " + noRequestFailed + "  (expect: true)");

            // assert busy cleared after the first turn completes
            cancelDisabledAfter1 = page.locator("#cancel-btn").isDisabled();
            System.out.println("[check] cancel-btn disabled after reply 1 = " + cancelDisabledAfter1
                    + "  (expect: true)");

            // 2nd turn — should send and produce a second reply if the queue drained
            page.fill("#prompt-input", "how are ya?");
            page.click("#send-btn");
            secondReplyArrived = waitForAssistantCount(page, 2, 20000);
            System.out.println("[check] second assistant reply arrived = " + secondReplyArrived
                    + "  (expect: true)");

            page.screenshot(new Page.ScreenshotOptions().setPath(java.nio.file.Paths.get("/tmp/chatui3-e2e.png")));
            browser.close();
        }

        boolean pass = cancelDisabledAfter1 && secondReplyArrived && noRequestFailed;
        System.out.println(pass ? "=== E2E PASS ===" : "=== E2E FAIL (bug reproduced) ===");
        System.exit(pass ? 0 : 1);
    }

    /** Polls until at least {@code n} finalized assistant messages exist, or the deadline passes. */
    static boolean waitForAssistantCount(Page page, int n, long timeoutMs) {
        Locator finalized = page.locator("#chat-area .message.assistant:not(.streaming)");
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (finalized.count() >= n) {
                return true;
            }
            page.waitForTimeout(300);
        }
        return finalized.count() >= n;
    }
}
