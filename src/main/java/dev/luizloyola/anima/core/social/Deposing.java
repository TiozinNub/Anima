package dev.luizloyola.anima.core.social;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;

/**
 * Whether a party gives up on a player leader who stays away (group-up spec, decision 17): time away
 * and the party's distress both weigh, with a minimum time away, unless the times are very bad. A
 * leader who is present is never deposed, nor a settler leader.
 */
public final class Deposing {

    private Deposing() {
    }

    /**
     * @param awayDays  in-game days the leader has been away, now
     * @param discontent the whole discontent ledger, the away line included
     * @param distress  what the ledger holds besides the away line
     */
    public static boolean deposes(double awayDays, double discontent, double distress, AgentProfile weigher) {
        if (awayDays <= 0) {
            return false;
        }
        boolean longEnough = awayDays >= weigher.i(ProfileAspect.SOCIAL_DEPOSE_MIN_AWAY_DAYS);
        return longEnough && discontent >= weigher.d(ProfileAspect.SOCIAL_DEPOSE_THRESHOLD)
                || distress >= weigher.d(ProfileAspect.SOCIAL_DEPOSE_VERY_BAD);
    }
}
