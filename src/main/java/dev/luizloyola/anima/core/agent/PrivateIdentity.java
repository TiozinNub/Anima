package dev.luizloyola.anima.core.agent;

/**
 * The tier of an agent's identity that must be <b>earned</b> — known only to those who were told,
 * and never synced to a client merely because it can see the body.
 *
 * <p>One member, because one is all Anima needs: a {@link #name()}, for the journal it writes and
 * the thoughts it speaks. Anything else a consumer considers private is added by extending this.
 * Whether an observer may use a name is the contact book's question, and the naming culture is the
 * consuming mod's.
 *
 * @see PublicIdentity the half that is free
 */
public interface PrivateIdentity {

    /** What this agent is called, to whoever has earned the right to know it. */
    String name();
}
