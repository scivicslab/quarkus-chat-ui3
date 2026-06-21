import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/**
 * Thinking block must not duplicate the final answer. The step that produces the final answer is
 * dropped from the thinking block (shown in the answer bubble instead), so no thinking block ever
 * contains a "Final Answer:" line. Verified on a tool-eligible arithmetic turn.
 */
public class E2eThinkingNoDup {

    static final String BASE = "http://localhost:18090/";

    public static void main(String[] args) {
        boolean replyArrived;
        boolean hasAnswer;
        boolean noFinalAnswerInThinking;

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

            // Collect all thinking blocks (may be zero if the model one-shot the answer).
            Locator blocks = page.locator("#chat-area .thinking-block");
            StringBuilder thinking = new StringBuilder();
            for (int i = 0; i < blocks.count(); i++) {
                thinking.append(blocks.nth(i).innerText()).append("\n");
            }
            noFinalAnswerInThinking = !thinking.toString().contains("Final Answer:");

            System.out.println("[check] final reply arrived = " + replyArrived);
            System.out.println("[check] reply contains 1081 = " + hasAnswer);
            System.out.println("[check] thinking blocks = " + blocks.count()
                    + ", none contains 'Final Answer:' = " + noFinalAnswerInThinking);

            browser.close();
        }

        boolean pass = replyArrived && hasAnswer && noFinalAnswerInThinking;
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
