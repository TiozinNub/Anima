package dev.luizloyola.anima.core.agent;

/**
 * How to refer to one agent in narration — the three forms a journal line or a spoken thought
 * needs, and nothing else.
 *
 * <p>Three words rather than a gender, because Anima does not know what kind of thing an agent is:
 * a settlement of people has genders where a golem has none.
 *
 * <p><b>Nothing that narrates an agent may spell a pronoun itself</b> — a hardcoded "her" in a task
 * misgenders somebody in a player's chat. Asking here stays correct when a consumer introduces a
 * form this interface's implementors did not have on day one.
 *
 * @see #THEY the neutral default, for any body with nothing more specific to say
 */
public interface Pronouns {

    /** The subject form — "<b>he</b> had heard something". */
    String subject();

    /** The object form — "the column beat <b>him</b>". */
    String object();

    /** The possessive form — "<b>his</b> beliefs". */
    String possessive();

    /**
     * The neutral default: they / them / their — an unidentified body, a construct, an animal a
     * consumer would rather not gender.
     */
    Pronouns THEY = of("they", "them", "their");

    /** A fixed set of forms, for implementors that just want to state the three words. */
    static Pronouns of(String subject, String object, String possessive) {
        return new Pronouns() {
            @Override
            public String subject() {
                return subject;
            }

            @Override
            public String object() {
                return object;
            }

            @Override
            public String possessive() {
                return possessive;
            }
        };
    }
}
