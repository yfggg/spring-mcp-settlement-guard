package io.mcpguard.settlement;

import java.util.UUID;

/** Builds idempotency keys that will not collide with another test in the same database. */
public final class IdempotencyKeys {

    private IdempotencyKeys() {
    }

    public static String next(String prefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return prefix + "-" + suffix;
    }
}
