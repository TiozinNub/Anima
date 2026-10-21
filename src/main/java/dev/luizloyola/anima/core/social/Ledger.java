package dev.luizloyola.anima.core.social;

/**
 * The three things a party weighs about itself (group-up spec, decisions 7–9 and 17): whether to
 * take people in, whether to join another party, and how it feels about an absent leader.
 */
public enum Ledger {
    OPENNESS("openness"),
    JOINING("joining"),
    DISCONTENT("discontent");

    private final String key;

    Ledger(String key) {
        this.key = key;
    }

    /** The saved name, and the tail of the readout's lang key {@code anima.ledger.<key>}. */
    public String key() {
        return key;
    }

    public static Ledger of(String key) {
        for (Ledger ledger : values()) {
            if (ledger.key.equals(key)) {
                return ledger;
            }
        }
        throw new IllegalArgumentException("no ledger " + key);
    }
}
