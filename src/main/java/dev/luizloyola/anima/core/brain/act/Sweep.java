package dev.luizloyola.anima.core.brain.act;

import dev.luizloyola.anima.core.social.PartyId;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Who a sword's sweep may catch besides its target (decision: Luiz, combat spec, 2026-09-27). An
 * agent never hits another agent, or a player, unless on purpose:
 *
 * <ul>
 *   <li>a blow at anything else — a pig, a zombie — catches no agent and no player;</li>
 *   <li>a blow at an agent or a player catches the agents and players of the target's party, and
 *       nobody else's, the striker's own included;</li>
 *   <li>a player in no party is party 0 ({@code null} here): a blow at any player catches them too,
 *       a blow at an agent does not.</li>
 * </ul>
 *
 * <p>Everything that is neither an agent nor a player is swept as vanilla sweeps it.
 */
public final class Sweep {

    /** What a body is, as far as the sweep cares. */
    public enum Kind {
        /** Neither an agent nor a player — a mob, an animal. */
        OTHER,
        /** An agent body. Always in a party: a loner is a party of one. */
        AGENT,
        /** A player, who may be in no party at all. */
        PLAYER
    }

    private Sweep() {
    }

    /**
     * Whether a sweep aimed at the target may catch the bystander.
     *
     * @param targetParty the target's party; {@code null} only for a player in none
     * @param bystanderParty the bystander's party; {@code null} only for a player in none
     */
    public static boolean catches(Kind target, @Nullable PartyId targetParty,
                                  Kind bystander, @Nullable PartyId bystanderParty) {
        if (bystander == Kind.OTHER) {
            return true;
        }
        if (target == Kind.OTHER) {
            return false;
        }
        if (Objects.equals(targetParty, bystanderParty)) {
            return true;
        }
        return target == Kind.PLAYER && bystander == Kind.PLAYER && bystanderParty == null;
    }
}
