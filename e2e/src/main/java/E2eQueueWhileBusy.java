import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/**
 * Queue-while-busy reproduction: send a 2nd message while the 1st is still streaming,
 * and verify the two turns run ONE AT A TIME, in order — never concurrently.
 *
 * Steps:
 *   1. open the UI
 *   2. send "FIRST", then immediately send "SECOND" (2nd lands while 1st is Busy → queued)
 *   3. while they run, sample the number of streaming assistant messages — must never exceed 1
 *   4. assert both turns complete: 2 user + 2 assistant messages, in order FIRST then SECOND
 *   5. assert no "Request failed" error
 */
public class E2eQueueWhileBusy {

    static final String BASE = "http://localhost:18090/";

    public static void main(String[] args) {
        boolean neverConcurrent = true;
        boolean bothReplies;
        boolean orderOk;
        boolean noError;

        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.setDefaultTimeout(15000);
            page.navigate(BASE);
            page.waitForSelector("#send-btn");
            System.out.println("[step] page loaded");

            // Send two messages back-to-back; the 2nd lands while the 1st turn is Busy.
            page.fill("#prompt-input", "FIRST count to three");
            page.click("#send-btn");
            page.fill("#prompt-input", "SECOND say hello");
            page.click("#send-btn");
            System.out.println("[step] sent FIRST then SECOND (2nd while busy)");

            // While both turns drain, the streaming assistant count must never exceed 1.
            Locator streaming = page.locator("#chat-area .message.assistant.streaming");
            long deadline = System.currentTimeMillis() + 30000;
            int maxStreaming = 0;
            while (System.currentTimeMillis() < deadline) {
                int s = streaming.count();
                if (s > maxStreaming) maxStreaming = s;
                if (s > 1) neverConcurrent = false;
                int done = page.locator("#chat-area .message.assistant:not(.streaming)").count();
                if (done >= 2) break;
                page.waitForTimeout(150);
            }
            System.out.println("[check] max concurrent streaming assistants = " + maxStreaming + "  (expect: <= 1)");

            bothReplies = page.locator("#chat-area .message.assistant:not(.streaming)").count() >= 2;
            System.out.println("[check] both replies arrived = " + bothReplies + "  (expect: true)");

            // Order: the two user messages must be FIRST then SECOND.
            Locator users = page.locator("#chat-area .message.user");
            String u0 = users.count() > 0 ? users.nth(0).innerText() : "";
            String u1 = users.count() > 1 ? users.nth(1).innerText() : "";
            orderOk = u0.contains("FIRST") && u1.contains("SECOND");
            System.out.println("[check] user order FIRST,SECOND = " + orderOk
                    + "  (got: [" + u0.replaceAll("\\s+", " ").trim() + "] , [" + u1.replaceAll("\\s+", " ").trim() + "])");

            noError = !page.locator("#chat-area").innerText().contains("Request failed");
            System.out.println("[check] no 'Request failed' = " + noError + "  (expect: true)");

            browser.close();
        }

        boolean pass = neverConcurrent && bothReplies && orderOk && noError;
        System.out.println(pass ? "=== E2E PASS ===" : "=== E2E FAIL ===");
        System.exit(pass ? 0 : 1);
    }
}
