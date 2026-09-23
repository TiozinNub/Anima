package dev.luizloyola.anima.core.brain.history;

/**
 * How long ago, as a line says it. Bucketed on elapsed ticks rather than on the day's clock: a body
 * does not know what time it is, only how long it has been.
 */
public enum When {
    JUST_NOW("anima.when.just_now", 2_400),
    EARLIER("anima.when.earlier", 24_000),
    YESTERDAY("anima.when.yesterday", 48_000),
    DAYS_AGO("anima.when.days_ago", Long.MAX_VALUE);

    private final String langKey;
    private final long under;

    When(String langKey, long under) {
        this.langKey = langKey;
        this.under = under;
    }

    public String langKey() {
        return langKey;
    }

    public static When of(long ageTicks) {
        for (When when : values()) {
            if (ageTicks < when.under) {
                return when;
            }
        }
        return DAYS_AGO;
    }
}
