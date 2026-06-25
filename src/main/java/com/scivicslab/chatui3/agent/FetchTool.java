package com.scivicslab.chatui3.agent;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * The {@code fetch} tool: retrieves a URL and returns its readable content as text. The complement to
 * {@code web_search} — search finds URLs, fetch reads the page behind one — so the agent can answer from
 * page content, not just search snippets. For HTML it extracts the main content and renders it as
 * Markdown-ish text; non-HTML (e.g. JSON from an API) is returned as-is.
 *
 * <p>Logic reused from the existing {@code plugin-web} {@code FetchActor} / mcp-gateway {@code FetchTool};
 * only jsoup is needed (already a dependency). Note: pages whose content is rendered by JavaScript return
 * only their static HTML skeleton here — for those, fetch a data/JSON endpoint instead (e.g. the JMA
 * forecast API for weather).</p>
 */
public final class FetchTool {

    private FetchTool() {}

    private static final Logger LOG = Logger.getLogger(FetchTool.class.getName());
    private static final int DEFAULT_MAX_LENGTH = 5000;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Fetches {@code url} and returns extracted text, or an {@code error: ...} string. */
    public static String fetch(String url) {
        if (url == null || url.isBlank()) return "error: url required";
        String u = url.trim();
        try {
            LOG.info("fetch: " + u);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(u))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Mozilla/5.0 (compatible; chat-ui3/1.0; fetch)")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return "error: HTTP " + response.statusCode() + ": " + truncate(response.body(), 500);
            }
            String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase();
            String text = contentType.contains("html") ? extractText(response.body(), u) : response.body();
            return truncate(text, DEFAULT_MAX_LENGTH);
        } catch (Exception e) {
            LOG.warning("fetch failed for " + u + ": " + e.getMessage());
            return "error: fetching " + u + ": " + e.getMessage();
        }
    }

    private static String extractText(String html, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);
        doc.select("script, style, nav, footer, header, aside, [role=navigation]").remove();

        Element main = doc.selectFirst("main, article, [role=main], #content, .content, #main");
        Element root = main != null ? main : doc.body();
        if (root == null) return doc.text();

        StringBuilder sb = new StringBuilder();
        for (Element block : root.select("h1,h2,h3,h4,h5,h6,p,li,pre,blockquote,td,th")) {
            String tag = block.tagName();
            String t = block.text().trim();
            if (t.isEmpty()) continue;
            if (tag.startsWith("h")) {
                sb.append("#".repeat(tag.charAt(1) - '0')).append(" ").append(t).append("\n\n");
            } else if ("pre".equals(tag)) {
                sb.append("```\n").append(block.wholeText().trim()).append("\n```\n\n");
            } else if ("li".equals(tag)) {
                sb.append("- ").append(t).append("\n");
            } else {
                sb.append(t).append("\n\n");
            }
        }
        return sb.isEmpty() ? root.text() : sb.toString().stripTrailing();
    }

    private static String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "\n[truncated " + text.length() + " chars total]";
    }
}
