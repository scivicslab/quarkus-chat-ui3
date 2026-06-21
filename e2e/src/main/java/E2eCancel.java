import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/**
 * Cancel must actually stop the in-flight LLM turn (server-side), not just the UI.
 *
 * Steps:
 *   1. send a prompt that produces a long stream
 *   2. once streaming has started, click Cancel
 *   3. assert the assistant text STOPS GROWING after cancel (server really stopped)
 *   4. assert the UI returned to Idle (cancel-btn disabled) and showed "Cancelled"
 *   5. assert a subsequent send still works (not stuck)
 */
public class E2eCancel {

    static final String BASE = "http://localhost:18090/";

    public static void main(String[] args) {
        boolean stoppedGrowing = false;
        boolean cancelDisabled = false;
        boolean cancelledShown = false;
        boolean resumeOk = false;

        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.setDefaultTimeout(15000);
            page.navigate(BASE);
            page.waitForSelector("#send-btn");
            System.out.println("[step] page loaded");

            // 1. long-generating prompt
            page.fill("#prompt-input", "Count from 1 to 300, one number per line.");
            page.click("#send-btn");

            // 2. wait until streaming actually started (assistant has some text)
            Locator assistants = page.locator("#chat-area .message.assistant");
            long deadline = System.currentTimeMillis() + 15000;
            boolean started = false;
            while (System.currentTimeMillis() < deadline) {
                if (assistants.count() >= 1 && assistants.last().innerText().length() > 8) { started = true; break; }
                page.waitForTimeout(100);
            }
            System.out.println("[step] streaming started = " + started);

            // 3. cancel, then check the text stops growing
            page.click("#cancel-btn");
            int lenJustAfter = assistants.last().innerText().length();
            page.waitForTimeout(3000);                       // give any un-stopped stream time to grow
            int lenLater = assistants.last().innerText().length();
            stoppedGrowing = (lenLater - lenJustAfter) <= 8; // allow at most a tiny in-flight delta
            System.out.println("[check] text stopped growing after cancel = " + stoppedGrowing
                    + "  (len " + lenJustAfter + " -> " + lenLater + ", expect ~no growth)");

            // 4. UI back to Idle + "Cancelled" shown
            cancelDisabled = page.locator("#cancel-btn").isDisabled();
            cancelledShown = page.locator("#chat-area").innerText().contains("Cancelled");
            System.out.println("[check] cancel-btn disabled = " + cancelDisabled + "  (expect: true)");
            System.out.println("[check] 'Cancelled' shown   = " + cancelledShown + "  (expect: true)");

            // 5. subsequent send still works
            int before = assistants.count();
            page.fill("#prompt-input", "Reply with one word: ok");
            page.click("#send-btn");
            long d2 = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < d2) {
                if (assistants.count() > before
                        && assistants.last().innerText().toLowerCase().contains("ok")) { resumeOk = true; break; }
                page.waitForTimeout(200);
            }
            System.out.println("[check] subsequent send works = " + resumeOk + "  (expect: true)");

            browser.close();
        }

        boolean pass = stoppedGrowing && cancelDisabled && cancelledShown && resumeOk;
        System.out.println(pass ? "=== E2E PASS ===" : "=== E2E FAIL ===");
        System.exit(pass ? 0 : 1);
    }
}
