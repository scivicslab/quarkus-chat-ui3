package com.scivicslab.chatui3.agent;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * In-memory Lucene full-text index over the user's Turing Workflow YAML files
 * ({@code ~/works/workflow/*.yaml}). Built once at startup via {@link #build()}; can be
 * rebuilt on demand (e.g. after new workflows are added) by calling {@link #build()} again.
 *
 * <p>Indexed fields (all with {@link StandardAnalyzer}):
 * <ul>
 *   <li>{@code name}        — workflow name (indexed + stored)</li>
 *   <li>{@code description} — description text (indexed + stored)</li>
 *   <li>{@code tags}        — space-joined tag list (indexed + stored)</li>
 * </ul>
 * Stored-only fields (not searchable):
 * <ul>
 *   <li>{@code params} — pipe-delimited "paramName|paramDescription" lines for display</li>
 * </ul>
 *
 * <p>{@link #search(String, int)} runs a {@link MultiFieldQueryParser} query across all three
 * indexed fields and returns formatted results including param names and descriptions, so the
 * LLM knows what to pass to {@code run_workflow}. When no query terms match, all indexed
 * workflows are returned (up to the limit) so the LLM can browse the full catalogue.</p>
 */
@ApplicationScoped
public class WorkflowIndex {

    private static final Logger LOG = Logger.getLogger(WorkflowIndex.class.getName());
    static final int DEFAULT_MAX_RESULTS = 10;

    private final StandardAnalyzer analyzer = new StandardAnalyzer();
    /** Swapped atomically by build(); null until first build completes. */
    private volatile IndexSearcher searcher;

    @PostConstruct
    void init() {
        try {
            build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "WorkflowIndex: index build failed at startup", e);
        }
    }

    /**
     * (Re)builds the in-memory index from all {@code *.yaml} files in {@code ~/works/workflow/}.
     * Safe to call at runtime (e.g. after adding new workflow files); swaps the searcher atomically.
     */
    public synchronized void build() throws IOException {
        Path wfDir = workflowDir();
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        int count = 0;
        try (IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(analyzer))) {
            if (Files.isDirectory(wfDir)) {
                try (var stream = Files.list(wfDir)) {
                    for (Path p : stream.filter(p -> p.getFileName().toString().endsWith(".yaml"))
                                       .sorted().toList()) {
                        try {
                            addDocument(writer, p);
                            count++;
                        } catch (Exception e) {
                            LOG.warning("WorkflowIndex: skipping " + p.getFileName() + ": " + e.getMessage());
                        }
                    }
                }
            }
        }
        this.searcher = new IndexSearcher(DirectoryReader.open(dir));
        LOG.info("WorkflowIndex: indexed " + count + " workflows from " + wfDir);
    }

    /**
     * Searches the index using {@code queryStr} across the name, description, and tags fields.
     * When no terms match, returns all indexed workflows so the LLM can browse the catalogue.
     *
     * @param queryStr natural-language query, e.g. "arXiv PDF summarize"
     * @param maxResults maximum number of results to return
     * @return formatted text listing matching workflows with their params
     */
    public String search(String queryStr, int maxResults) {
        IndexSearcher s = this.searcher;
        if (s == null) {
            return "Workflow index is not available (no workflows in ~/works/workflow/ or build failed).";
        }
        if (queryStr == null || queryStr.isBlank()) return "error: query required";

        int limit = maxResults > 0 ? maxResults : DEFAULT_MAX_RESULTS;
        try {
            MultiFieldQueryParser parser = new MultiFieldQueryParser(
                    new String[]{"name", "description", "tags"}, analyzer);
            parser.setDefaultOperator(QueryParser.Operator.OR);
            // QueryParser.escape prevents parse errors from special chars in the query string.
            TopDocs hits = s.search(parser.parse(QueryParser.escape(queryStr.trim())), limit);

            if (hits.totalHits.value == 0) {
                return searchAll(s, limit, queryStr);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Workflows matching '").append(queryStr).append("':\n\n");
            appendDocs(sb, s, hits.scoreDocs);
            appendRunHint(sb);
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "WorkflowIndex search failed: " + queryStr, e);
            return "error: search failed: " + e.getMessage();
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private static Path workflowDir() {
        String home = System.getProperty("user.home", System.getenv().getOrDefault("HOME", "~"));
        return Path.of(home, "works", "workflow");
    }

    @SuppressWarnings("unchecked")
    private static void addDocument(IndexWriter writer, Path yamlFile) throws Exception {
        String yaml = Files.readString(yamlFile);
        Object loaded = new Yaml().load(yaml);
        if (!(loaded instanceof Map<?, ?> doc)) return;

        String fileName = yamlFile.getFileName().toString().replaceFirst("\\.yaml$", "");
        String name     = Objects.toString(doc.get("name"), fileName);
        String desc     = Objects.toString(doc.get("description"), "").strip();

        List<String> tagList = new ArrayList<>();
        if (doc.get("tags") instanceof List<?> tl) {
            for (Object t : tl) tagList.add(String.valueOf(t));
        }
        String tags = String.join(" ", tagList);

        // Build params display: "paramName|description\n" lines (pipe as delimiter to avoid JSON escaping)
        StringBuilder params = new StringBuilder();
        if (doc.get("params") instanceof Map<?, ?> paramsMap) {
            for (Map.Entry<?, ?> e : paramsMap.entrySet()) {
                String pName = String.valueOf(e.getKey());
                String pDesc = "";
                if (e.getValue() instanceof Map<?, ?> meta) {
                    pDesc = Objects.toString(meta.get("description"), "").strip();
                    Object def = meta.get("default");
                    if (def != null) pDesc += " (default: " + def + ")";
                }
                params.append(pName).append("|").append(pDesc).append("\n");
            }
        }

        Document d = new Document();
        d.add(new StoredField("fileName", fileName));
        d.add(new TextField("name",        name, Field.Store.YES));
        d.add(new TextField("description", desc, Field.Store.YES));
        d.add(new TextField("tags",        tags, Field.Store.YES));
        d.add(new StoredField("params",    params.toString()));
        writer.addDocument(d);
    }

    private static String searchAll(IndexSearcher s, int limit, String queryStr) throws IOException {
        TopDocs all = s.search(new MatchAllDocsQuery(), limit);
        if (all.scoreDocs.length == 0) return "No workflows found in ~/works/workflow/.";
        StringBuilder sb = new StringBuilder();
        sb.append("No exact matches for '").append(queryStr).append("'. All available workflows:\n\n");
        appendDocs(sb, s, all.scoreDocs);
        appendRunHint(sb);
        return sb.toString().stripTrailing();
    }

    private static void appendDocs(StringBuilder sb, IndexSearcher s, ScoreDoc[] docs)
            throws IOException {
        for (int i = 0; i < docs.length; i++) {
            Document doc = s.storedFields().document(docs[i].doc);
            sb.append(i + 1).append(". **").append(doc.get("name")).append("**\n");
            String desc = doc.get("description");
            if (desc != null && !desc.isBlank()) {
                String firstLine = desc.lines().filter(l -> !l.isBlank()).findFirst().orElse("").trim();
                sb.append("   ").append(firstLine).append("\n");
            }
            String tags = doc.get("tags");
            if (tags != null && !tags.isBlank()) {
                sb.append("   tags: ").append(tags.replace(" ", ", ")).append("\n");
            }
            String params = doc.get("params");
            if (params != null && !params.isBlank()) {
                sb.append("   params:\n");
                for (String line : params.split("\n")) {
                    int sep = line.indexOf('|');
                    if (sep < 0) continue;
                    String pName = line.substring(0, sep);
                    String pDesc = line.substring(sep + 1);
                    sb.append("     \"").append(pName).append("\"");
                    if (!pDesc.isBlank()) sb.append(" — ").append(pDesc);
                    sb.append("\n");
                }
            }
            sb.append("\n");
        }
    }

    private static void appendRunHint(StringBuilder sb) {
        sb.append("To run: run_workflow(workflow=\"<name>\", params=\"{\\\"key\\\": \\\"value\\\"}\")");
    }
}
