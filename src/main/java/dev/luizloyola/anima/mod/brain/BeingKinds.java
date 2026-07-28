package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.core.brain.sense.Being;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.NeutralMob;

/**
 * How a glimpsed body is sorted into a {@link Being.Kind}, and how a consuming mod teaches the
 * sense to recognise a kind of its own: Anima answers from a short ladder over vanilla's type
 * hierarchy, and a mod that perceives a distinction vanilla has no class for — a tamed companion,
 * a mount, a construct — registers a classifier here beside its {@code Kind}.
 *
 * <p><b>Consumers are asked first, in reverse registration order</b>, since a mod loaded later is
 * usually the more specific one. Anima's vanilla ladder is the floor and always answers, so
 * classification never fails.
 *
 * <p>NeutralMob is tested before Enemy because an enderman and a zombified piglin are both, and
 * neutral is the truer story about them.
 */
public final class BeingKinds {

    /** Consumer classifiers, newest first. Copy-on-write: registered at init, read per sighting. */
    private static final List<Classifier> REGISTERED = new CopyOnWriteArrayList<>();

    private BeingKinds() {
    }

    /** Recognises bodies a general rule would misclassify. Return empty to defer. */
    @FunctionalInterface
    public interface Classifier {
        Optional<Being.Kind> classify(LivingEntity body);
    }

    /** Call during mod initialization; the most recently registered classifier is asked first. */
    public static void register(Classifier classifier) {
        REGISTERED.add(0, classifier);
    }

    /** What this body is, to an observer. Never empty — the vanilla ladder is the floor. */
    public static Being.Kind of(LivingEntity body) {
        for (Classifier classifier : REGISTERED) {
            Optional<Being.Kind> said = classifier.classify(body);
            if (said.isPresent()) {
                return said.get();
            }
        }
        return vanilla(body);
    }

    private static Being.Kind vanilla(LivingEntity body) {
        if (body instanceof NeutralMob) {
            return Being.Kind.NEUTRAL;
        }
        if (body instanceof Enemy) {
            return Being.Kind.MONSTER;
        }
        if (body instanceof AbstractVillager) {
            return Being.Kind.VILLAGER;
        }
        return Being.Kind.PASSIVE;
    }

    /** Forgets every registration — test teardown only. */
    public static void reset() {
        REGISTERED.clear();
    }
}
