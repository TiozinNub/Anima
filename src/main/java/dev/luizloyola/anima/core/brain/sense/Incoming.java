package dev.luizloyola.anima.core.brain.sense;

import java.util.ArrayList;
import java.util.List;

/**
 * How much damage a second a body really takes from everything hitting it at once. Not the sum of
 * each attacker's rate: vanilla's hurt immunity ({@code LivingEntity.hurtServer}) lets a blow land
 * in full at most once every 10 ticks, and a blow inside that window lands only what it has over
 * the last one. Five zombies deal 6 a second, not 15; a poison tick inside a bite's window is lost.
 *
 * <p>Simulated tick by tick over a few staggered starts and averaged, since how the attackers'
 * rhythms fall against each other decides what gets through. See the mob attack reference
 * (2026-09-30) for the rule.
 */
public final class Incoming {

    /** Hurt immunity: a full blow sets 20 ticks, and only the first 10 of them shut others out. */
    static final int IMMUNE_TICKS = 20;
    static final int SHUT_OUT_ABOVE = 10;
    static final int TICKS = 400;
    static final int STARTS = 4;
    /** What a tick of poison, wither or fire deals. */
    static final double LINGER_DAMAGE = 1.0;

    private record Stream(double damage, double interval, double phase) {
    }

    private Incoming() {
    }

    /** Damage a second this body takes from {@code attackers}, wearing this much armour. */
    public static double perSecond(List<Combatant> attackers, double armor, double toughness) {
        List<double[]> streams = new ArrayList<>(); // {damage, interval ticks}
        for (Combatant them : attackers) {
            double hit = them.hit(armor, toughness);
            if (hit > 0.0 && them.hitsPerSecond() > 0.0) {
                streams.add(new double[]{hit, 20.0 / them.hitsPerSecond()});
            }
            if (them.lingerTicks() > 0) {
                streams.add(new double[]{LINGER_DAMAGE, them.lingerTicks()});
            }
        }
        if (streams.isEmpty()) {
            return 0.0;
        }
        double total = 0.0;
        for (int start = 0; start < STARTS; start++) {
            List<Stream> run = new ArrayList<>();
            for (int i = 0; i < streams.size(); i++) {
                double interval = streams.get(i)[1];
                double spread = (i * 0.6180339887 + start / (double) STARTS) % 1.0;
                run.add(new Stream(streams.get(i)[0], interval, spread * interval));
            }
            total += simulate(run);
        }
        return total / STARTS / TICKS * 20.0;
    }

    private static double simulate(List<Stream> streams) {
        int immune = 0;
        double last = 0.0;
        double taken = 0.0;
        double[] next = new double[streams.size()];
        for (int i = 0; i < next.length; i++) {
            next[i] = streams.get(i).phase();
        }
        for (int tick = 0; tick < TICKS; tick++) {
            if (immune > 0) {
                immune--;
            }
            for (int i = 0; i < next.length; i++) {
                while (next[i] < tick + 1) {
                    double blow = streams.get(i).damage();
                    if (immune > SHUT_OUT_ABOVE) {
                        if (blow > last) {
                            taken += blow - last;
                            last = blow;
                        }
                    } else {
                        taken += blow;
                        last = blow;
                        immune = IMMUNE_TICKS;
                    }
                    next[i] += streams.get(i).interval();
                }
            }
        }
        return taken;
    }
}
