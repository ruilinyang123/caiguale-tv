package com.fongmi.android.tv.api;

import java.util.concurrent.atomic.AtomicLong;

/** Owns one independently cancellable stream of manual danmaku search requests. */
public final class DanmakuSearchRequestOwner {

    private static final AtomicLong IDS = new AtomicLong();

    private final String tag = DanmakuSearchRequestOwner.class.getSimpleName() + "-" + IDS.incrementAndGet();
    private final AtomicLong generation = new AtomicLong();

    public Token begin() {
        return new Token(generation.incrementAndGet());
    }

    public void invalidate() {
        generation.incrementAndGet();
    }

    public boolean isCurrent(Token token) {
        return token != null && token.generation() == generation.get();
    }

    public String tag() {
        return tag;
    }

    public record Token(long generation) {
    }
}
