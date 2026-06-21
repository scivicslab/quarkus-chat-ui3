package com.scivicslab.chatui3.rest;

/**
 * SSE event payload. The {@code type} field drives browser-side rendering.
 */
public class ChatEvent {

    public final String type;
    public final String text;    // delta, result — entry A (Turing Workflow) vocabulary
    public final String message; // error message — entry A vocabulary
    public final String content; // browser UI (chat-ui app.js) vocabulary: delta/result text or error message

    private ChatEvent(String type, String text, String message, String content) {
        this.type    = type;
        this.text    = text;
        this.message = message;
        this.content = content;
    }

    public static ChatEvent delta(String fragment) {
        return new ChatEvent("delta", fragment, null, fragment);
    }

    public static ChatEvent result(String fullText) {
        return new ChatEvent("result", fullText, null, fullText);
    }

    public static ChatEvent error(String msg) {
        return new ChatEvent("error", null, msg, msg);
    }

    /** Intermediate agent-loop step (Thought / Action / Observation), shown as an info line. */
    public static ChatEvent info(String text) {
        return new ChatEvent("info", null, null, text);
    }

    /** A streamed fragment of the agent's reasoning, shown live in the per-turn "thinking" block
     *  (kept separate from the final answer's delta/result). */
    public static ChatEvent thinking(String fragment) {
        return new ChatEvent("thinking", fragment, null, fragment);
    }

    /** Marks the start of a new agent step in the thinking stream, so the UI can later drop just
     *  this step if it turns out to be the final answer (avoids duplicating it in the answer bubble). */
    public static ChatEvent thinkingStep() {
        return new ChatEvent("thinking_step", null, null, null);
    }

    /** The current step resolved to the final answer: the UI removes this step's streamed text from
     *  the thinking block (the answer is shown in the bubble instead). */
    public static ChatEvent thinkingDrop() {
        return new ChatEvent("thinking_drop", null, null, null);
    }
}
