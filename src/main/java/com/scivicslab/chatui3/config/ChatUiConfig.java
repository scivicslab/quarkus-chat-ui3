package com.scivicslab.chatui3.config;

import java.util.Map;

/**
 * Runtime-configurable parameters for vLLM calls. Held by ChatActor; updated via POST /api/config.
 * Only vLLM I/O parameters live here. Loop control (iterations, tools, system prompt)
 * is the caller's responsibility and arrives in the prompt body.
 */
public class ChatUiConfig {

    private String vllmBaseUrl;
    private String modelId;
    private double temperature;
    private int maxTokens;

    public ChatUiConfig(String vllmBaseUrl, String modelId, double temperature, int maxTokens) {
        this.vllmBaseUrl = vllmBaseUrl;
        this.modelId     = modelId;
        this.temperature = temperature;
        this.maxTokens   = maxTokens;
    }

    /** Applies only the keys present in the patch map; absent keys keep their current value. */
    public void applyPatch(Map<String, Object> patch) {
        if (patch.containsKey("vllmBaseUrl")) vllmBaseUrl = (String) patch.get("vllmBaseUrl");
        if (patch.containsKey("modelId"))     modelId     = (String) patch.get("modelId");
        if (patch.containsKey("temperature")) temperature = toDouble(patch.get("temperature"));
        if (patch.containsKey("maxTokens"))   maxTokens   = toInt(patch.get("maxTokens"));
    }

    private static double toDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString());
    }

    private static int toInt(Object v) {
        return v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString());
    }

    public String getVllmBaseUrl() { return vllmBaseUrl; }
    public String getModelId()     { return modelId; }
    public double getTemperature() { return temperature; }
    public int    getMaxTokens()   { return maxTokens; }
}
