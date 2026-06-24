package com.scivicslab.chatui3.agent;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * The {@code web_search} tool: searches the web via DuckDuckGo's HTML endpoint and returns titles,
 * URLs, and snippets as text the agent feeds back as an Observation. No API key required.
 *
 * <p>The endpoint, headers, CSS selectors, and the {@code uddg=} redirect decoding are reused verbatim
 * from the existing DuckDuckGo tools (quarkus-mcp-gateway {@code WebSearchTool} and the {@code plugin-web}
 * {@code WebSearchActor}); only jsoup is added as a dependency, rather than depending on the shaded
 * plugin jar (which would duplicate pojo-actor / turing-workflow classes on the uber-jar classpath).</p>
 */
public final class WebSearchTool {

    private WebSearchTool() {}

    private static final Logger LOG = Logger.getLogger(WebSearchTool.class.getName());
    static final int DEFAULT_MAX_RESULTS = 10;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Searches DuckDuckGo for {@code query}; returns formatted results, or an {@code error: ...}/ message. */
    public static String search(String query, int maxResults) {
        if (query == null || query.isBlank()) return "error: query required";
        int limit = maxResults > 0 ? maxResults : DEFAULT_MAX_RESULTS;
        try {
            LOG.info("web_search: " + query);
            String encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://html.duckduckgo.com/html/?q=" + encoded))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (compatible; chat-ui3/1.0)")
                    .header("Accept-Language", "ja,en;q=0.9")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) return "error: search failed: HTTP " + response.statusCode();
            return parseResults(response.body(), limit);
        } catch (Exception e) {
            LOG.warning("web_search failed for '" + query + "': " + e.getMessage());
            return "error: searching for '" + query + "': " + e.getMessage();
        }
    }

    private static String parseResults(String html, int maxResults) {
        Document doc = Jsoup.parse(html);
        Elements results = doc.select(".result");
        if (results.isEmpty()) results = doc.select(".web-result");
        if (results.isEmpty()) return "No results found.";

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Element result : results) {
            if (count >= maxResults) break;
            String title = text(result, ".result__title, .result__a");
            String href = extractUrl(result, ".result__url, .result__a");
            String snippet = text(result, ".result__snippet");
            if (title.isBlank() && href.isBlank()) continue;
            // Skip DuckDuckGo ad results (ad redirect URLs, not real organic results) — they are noise
            // that would only pollute the model's context.
            if (href.contains("duckduckgo.com/y.js") || href.contains("ad_provider=")) continue;
            sb.append(count + 1).append(". ").append(title).append("\n");
            if (!href.isBlank()) sb.append("   URL: ").append(href).append("\n");
            if (!snippet.isBlank()) sb.append("   ").append(snippet).append("\n");
            sb.append("\n");
            count++;
        }
        return count == 0 ? "No results found." : sb.toString().stripTrailing();
    }

    private static String text(Element parent, String selector) {
        Element el = parent.selectFirst(selector);
        return el != null ? el.text().strip() : "";
    }

    private static String extractUrl(Element parent, String selector) {
        Element el = parent.selectFirst(selector);
        if (el == null) return "";
        String val = el.attr("href");
        if (val.contains("uddg=")) {
            int start = val.indexOf("uddg=") + 5;
            int end = val.indexOf('&', start);
            String enc = end < 0 ? val.substring(start) : val.substring(start, end);
            try { return URLDecoder.decode(enc, StandardCharsets.UTF_8); } catch (Exception ignored) {}
        }
        return val.startsWith("http") ? val : "";
    }
}
