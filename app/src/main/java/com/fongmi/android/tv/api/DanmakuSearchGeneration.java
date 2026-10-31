package com.fongmi.android.tv.api;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Identifies asynchronous danmaku searches belonging to the current playback context. */
public final class DanmakuSearchGeneration {

    private final AtomicLong generation = new AtomicLong();
    private volatile String context = "";

    public Token begin(String context) {
        this.context = Objects.toString(context, "");
        return new Token(generation.incrementAndGet(), this.context);
    }

    public void invalidate() {
        context = "";
        generation.incrementAndGet();
    }

    public boolean isCurrent(Token token, String currentContext) {
        return token != null
                && token.generation() == generation.get()
                && token.context().equals(context)
                && token.context().equals(Objects.toString(currentContext, ""));
    }

    public record Token(long generation, String context) {
    }
}
