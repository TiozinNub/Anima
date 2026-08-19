package dev.luizloyola.anima.mod.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.social.ContactBook;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ContactData#freshSides}, checkable with no {@link net.minecraft.server.MinecraftServer}
 * and no loaded body: the company-gauge boost itself needs both (there is no fake
 * {@code AgentBody}/{@code LivingEntity} anywhere in this suite to observe {@code Company.met()}
 * fire), but which side(s) {@link ContactData#introduce} decides to fill is pure book-reading, and
 * that decision is exactly what regressed — {@link ContactBook#introduce}'s single combined OR
 * cannot tell a genuine new acquaintance from a side that already knew.
 */
class ContactDataTest {

    private final ContactBook book = new ContactBook();
    private final AgentId alice = AgentId.random();
    private final AgentId bob = AgentId.random();

    @Test
    void bothSidesAreFreshOnAGenuineFirstIntroduction() {
        assertEquals(List.of(alice, bob), ContactData.freshSides(book, alice, bob));
    }

    @Test
    void neitherSideIsFreshOnARepeatIntroduction() {
        ContactData.freshSides(book, alice, bob);
        assertTrue(ContactData.freshSides(book, alice, bob).isEmpty());
    }

    /**
     * The exact scenario the boost bug lived in: {@code /anima contacts forget} is one-sided, so a
     * re-introduction after one only relearns the side that actually lost the name. Before this
     * fix, {@link ContactData#introduce} read {@link ContactBook#introduce}'s single OR'd boolean
     * and filled BOTH gauges here — alice's, for an acquaintance she never lost and so never
     * remade.
     */
    @Test
    void afterAOneSidedForgetOnlyTheForgottenSideIsFreshOnReintroduction() {
        book.introduce(alice, bob);
        assertTrue(book.forget(alice, bob), "alice loses bob's name; bob keeps hers");

        assertEquals(List.of(alice), ContactData.freshSides(book, alice, bob),
                "only alice relearned anything — bob never lost what he already had");
    }
}
