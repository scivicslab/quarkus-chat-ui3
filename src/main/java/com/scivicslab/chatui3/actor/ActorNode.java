package com.scivicslab.chatui3.actor;

import java.util.List;

/**
 * One node of the actor tree exposed by {@code GET /api/actors}.
 *
 * <p>{@code name} is the actor name, {@code type} the wrapped POJO's simple class name,
 * {@code alive} the actor's liveness, and {@code children} its child actors (already sorted).
 * Serialized to JSON by Jackson as-is.</p>
 */
public record ActorNode(String name, String type, boolean alive, List<ActorNode> children) {}
