package com.scivicslab.chatui3.actor;

/**
 * Marker root of the chatui3 persistent actor tree. The application's persistent actors
 * (sse-batch, sse, chat) are created as its children via {@code createChild}, so the whole
 * set forms a single tree from one root — inspectable via {@code GET /api/actors}.
 *
 * <p>Holds no state and processes no messages; it exists only to give the actor set a root
 * for management/inspection.</p>
 */
public class RootActor {
    public String describe() {
        return "chatui3 root";
    }
}
