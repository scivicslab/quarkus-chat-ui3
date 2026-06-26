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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.logging.Logger;

/**
 * The {@code web_search} tool: searches the web via DuckDuckGo's HTML endpoint AND deterministically
 * fetches the top result pages' actual content (not just snippets), so the agent never has to decide
 * to call {@code fetch} afterwards. Returns title + URL + page-content excerpt per result, as text the
 * agent feeds back as an Observation. No API key required.
 *
 * <p>The endpoint, headers, CSS selectors, and the {@code uddg=} redirect decoding are reused verbatim
 * from the existing DuckDuckGo tools; only jsoup is added as a dependency. Page content is fetched with
 * {@link FetchTool}.</p>
 */
public final class WebSearchTool {

    private WebSearchTool() {}

    private static final Logger LOG = Logger.getLogger(WebSearchTool.class.getName());
    static final int DEFAULT_MAX_RESULTS = 10;
    /** How many top results to fetch full content for, and how much of each to include. */
    static final int FETCH_TOP_N = 10;
    static final int PER_RESULT_CHARS = 650;   // ~10 * (650 + overhead) stays within the observation budget

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** One organic search result. */
    record Result(String title, String url, String snippet) {}

    /**
     * Searches the web and retrieves the top results' page content (deterministic: always fetches).
     * For each of the top {@code maxResults} organic results it fetches the page with {@link FetchTool}
     * (in parallel) and includes a {@code perResultChars}-char excerpt; falls back to the search snippet
     * when a page cannot be fetched.
     */
    public static String searchAndFetch(String query, int maxResults, int perResultChars) {
        if (query == null || query.isBlank()) return "error: query required";
        int limit = maxResults > 0 ? maxResults : FETCH_TOP_N;
        List<Result> results;
        try {
            results = searchResults(query, limit);
        } catch (Exception e) {
            LOG.warning("web_search failed for '" + query + "': " + e.getMessage());
            return "error: searching for '" + query + "': " + e.getMessage();
        }
        if (results.isEmpty()) return "No results found.";

        // Fetch every result's page content in parallel; preserve result order.
        LOG.info("web_search: fetching " + results.size() + " page(s) for: " + query);
        List<String> bodies = results.parallelStream()
                .map(r -> contentFor(r, perResultChars))
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            Result r = results.get(i);
            sb.append(i + 1).append(". ").append(r.title()).append("\n");
            if (!r.url().isBlank()) sb.append("   URL: ").append(r.url()).append("\n");
            sb.append("   ").append(bodies.get(i).replace("\n", "\n   ")).append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    /** Convenience overload using the default top-N and per-result size. */
    public static String searchAndFetch(String query) {
        return searchAndFetch(query, FETCH_TOP_N, PER_RESULT_CHARS);
    }

    /** Fetches one result's page content (trimmed); falls back to the snippet if the fetch fails. */
    private static String contentFor(Result r, int perResultChars) {
        if (r.url().isBlank()) {
            return r.snippet().isBlank() ? "(no URL)" : r.snippet();
        }
        String body = FetchTool.fetch(r.url());
        if (body == null || body.isBlank() || body.startsWith("error")) {
            return r.snippet().isBlank() ? "(could not fetch page)" : r.snippet() + " [page fetch failed]";
        }
        return trim(body, perResultChars);
    }

    private static String trim(String s, int max) {
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) + " …" : t;
    }

    /** Snippet-only search (titles/URLs/snippets), retained for callers that do not want page content. */
    public static String search(String query, int maxResults) {
        if (query == null || query.isBlank()) return "error: query required";
        try {
            List<Result> results = searchResults(query, maxResults > 0 ? maxResults : DEFAULT_MAX_RESULTS);
            if (results.isEmpty()) return "No results found.";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < results.size(); i++) {
                Result r = results.get(i);
                sb.append(i + 1).append(". ").append(r.title()).append("\n");
                if (!r.url().isBlank()) sb.append("   URL: ").append(r.url()).append("\n");
                if (!r.snippet().isBlank()) sb.append("   ").append(r.snippet()).append("\n");
                sb.append("\n");
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            LOG.warning("web_search failed for '" + query + "': " + e.getMessage());
            return "error: searching for '" + query + "': " + e.getMessage();
        }
    }

    /** Runs the DuckDuckGo query and parses the organic results (up to {@code maxResults}). */
    static List<Result> searchResults(String query, int maxResults) throws Exception {
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
        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + response.statusCode());
        }
        return parseResults(response.body(), maxResults);
    }

    private static List<Result> parseResults(String html, int maxResults) {
        Document doc = Jsoup.parse(html);
        Elements results = doc.select(".result");
        if (results.isEmpty()) results = doc.select(".web-result");

        List<Result> out = new ArrayList<>();
        for (Element result : results) {
            if (out.size() >= maxResults) break;
            String title = text(result, ".result__title, .result__a");
            String href = extractUrl(result, ".result__url, .result__a");
            String snippet = text(result, ".result__snippet");
            if (title.isBlank() && href.isBlank()) continue;
            // Skip DuckDuckGo ad results (ad redirect URLs, not real organic results).
            if (href.contains("duckduckgo.com/y.js") || href.contains("ad_provider=")) continue;
            out.add(new Result(title, href, snippet));
        }
        return out;
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
