import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/**
 * Logs tab level dropdown: selecting a level filters the shown log lines (threshold semantics).
 *
 * The in-memory log buffer always has INFO entries (startup, vLLM I/O). Selecting WARNING+ must
 * drop the INFO lines, leaving only WARNING/SEVERE.
 */
public class E2eLogLevelFilter {

    static final String BASE = "http://localhost:18090/";

    public static void main(String[] args) {
        boolean allPositive;
        boolean filteredFewer;
        boolean onlyWarnSevere;

        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.setDefaultTimeout(15000);
            page.navigate(BASE);
            page.waitForSelector("#logs-list");
            page.locator("#logs-refresh").click();

            Locator lines = page.locator("#logs-list .log-line");
            long deadline = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < deadline && lines.count() == 0) {
                page.waitForTimeout(300);
            }
            int all = lines.count();

            // Filtering is by the backend's numeric level value (Level.intValue()), independent of
            // the level name. The buffer always holds INFO (800) lines, so we use them as the pivot:
            // selecting INFO (800) must KEEP them (800 >= 800); selecting WARN (900) must DROP them
            // (800 < 900). The same lines flipping across the threshold proves the numeric compare.
            page.locator("#logs-level").selectOption("800");   // INFO threshold
            page.waitForTimeout(400);
            int atInfo = lines.count();

            page.locator("#logs-level").selectOption("900");   // WARN threshold
            page.waitForTimeout(400);
            int atWarn = lines.count();

            boolean everyKeptAtOrAbove900 = true;
            for (int i = 0; i < atWarn; i++) {
                String lv = lines.nth(i).getAttribute("data-lv");
                if (lv == null || Integer.parseInt(lv.trim()) < 900) {
                    everyKeptAtOrAbove900 = false;
                }
            }

            allPositive = all > 0 && atInfo == all;   // INFO lines kept at the 800 threshold
            filteredFewer = atWarn < atInfo;          // the same INFO lines dropped at 900
            onlyWarnSevere = everyKeptAtOrAbove900;   // nothing below 900 survives, by numeric value

            System.out.println("[check] all lines = " + all + ", kept at INFO(800) = " + atInfo
                    + "  (expect equal, > 0)");
            System.out.println("[check] kept at WARN(900) = " + atWarn + "  (< INFO count = " + filteredFewer + ")");
            System.out.println("[check] every line kept at WARN has data-lv >= 900 = " + onlyWarnSevere);

            browser.close();
        }

        boolean pass = allPositive && filteredFewer && onlyWarnSevere;
        System.out.println(pass ? "=== E2E PASS ===" : "=== E2E FAIL ===");
        System.exit(pass ? 0 : 1);
    }
}
