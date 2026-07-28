package dev.luizloyola.anima.core.agent;

/**
 * The tier of an agent's identity that <b>anyone can have just by looking</b> — and which
 * therefore travels freely to every client that can see the body.
 *
 * <p>Anima declares no members: what is publicly visible about a body is the consuming
 * mod's business. This interface names the <em>tier</em>, not its contents.
 *
 * <p>A type rather than a convention, because public-is-free and {@link PrivateIdentity}-is-earned
 * is the load-bearing rule of the whole social layer: "may this observer have this?" needs a place
 * to live.
 *
 * @see PrivateIdentity the half that must be earned
 */
public interface PublicIdentity {
}
