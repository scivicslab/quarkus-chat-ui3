import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Entry A (headless single-shot, used by Turing Workflow) is logged to H2 via the shared VllmClient
 * point, just like the browser path. Send POST /api/chat {message} and confirm a "chatui3-workflow"
 * session appears with the full request+response in the persistent log.
 */
public class E2eEntryALog {

    static final String BASE = "http://localhost:18090/";

    public static void main(String[] args) {
        boolean workflowSessionExists;
        boolean hasRequest;
        boolean hasResponse;

        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            var page = browser.newPage();
            page.setDefaultTimeout(15000);

            page.request().delete(BASE + "api/history");

            // Entry A: headless single-shot prompt (the {message} shape, as ChatUi3Actor sends).
            page.request().post(BASE + "api/chat", RequestOptions.create()
                    .setHeader("Content-Type", "application/json")
                    .setData("{\"message\":\"In one short sentence, what is an actor?\"}"));

            // Poll the persistent log until the entry-A (chatui3-workflow) session has an entry.
            String sessionsJson = "";
            String logsJson = "";
            long deadline = System.currentTimeMillis() + 40000;
            while (System.currentTimeMillis() < deadline) {
                sessionsJson = page.request().get(BASE + "api/sessions").text();
                if (sessionsJson.contains("chatui3-workflow")) {
                    long sid = maxSessionId(sessionsJson);
                    APIResponse r = page.request().get(BASE + "api/sessions/" + sid + "/logs");
                    logsJson = r.text();
                    if (logsJson.contains("REQUEST:")) break;
                }
                page.waitForTimeout(500);
            }

            workflowSessionExists = sessionsJson.contains("chatui3-workflow");
            hasRequest  = logsJson.contains("REQUEST:");
            hasResponse = logsJson.contains("RESPONSE:");

            System.out.println("[check] entry-A (chatui3-workflow) session exists = " + workflowSessionExists);
            System.out.println("[check] full REQUEST stored  = " + hasRequest);
            System.out.println("[check] full RESPONSE stored = " + hasResponse);

            browser.close();
        }

        boolean pass = workflowSessionExists && hasRequest && hasResponse;
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
}
