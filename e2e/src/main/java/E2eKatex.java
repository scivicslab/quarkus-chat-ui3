import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/**
 * KaTeX math rendering: the page's marked instance (configured with the katex extension at startup)
 * must typeset LaTeX. We render a known expression in the browser and assert the output is KaTeX
 * HTML (contains the "katex" class), not the raw "$...$" source.
 */
public class E2eKatex {

    static final String BASE = "http://localhost:18090/";

    public static void main(String[] args) {
        boolean rendered;
        boolean notRaw;

        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.setDefaultTimeout(15000);
            page.navigate(BASE);
            page.waitForSelector("#send-btn");

            // Use the same global marked instance the chat renders with.
            Object html = page.evaluate("() => marked.parse('The mass-energy relation is $E = mc^2$.')");
            String out = String.valueOf(html);

            rendered = out.contains("katex");          // KaTeX produced typeset markup
            notRaw   = !out.contains("$E = mc^2$");     // the raw LaTeX source is gone

            System.out.println("[check] output contains katex markup = " + rendered);
            System.out.println("[check] raw $...$ source replaced     = " + notRaw);

            browser.close();
        }

        boolean pass = rendered && notRaw;
        System.out.println(pass ? "=== E2E PASS ===" : "=== E2E FAIL ===");
        System.exit(pass ? 0 : 1);
    }
}
