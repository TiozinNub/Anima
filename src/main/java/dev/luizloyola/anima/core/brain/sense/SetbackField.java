package dev.luizloyola.anima.core.brain.sense;

import java.util.List;
import java.util.Optional;

/**
 * How much trouble a place has already given this body — one number per cell, over everywhere its
 * legs have recently been beaten.
 *
 * <p>The sibling of {@link DangerField}, not the same thing: that field prices what a
 * body is AFRAID of, this one what has already <em>defeated</em> it. Two vocabularies, summed only
 * where the search adds costs anyway.
 *
 * <p>A re-path used to run from the same position over an essentially identical snapshot with the
 * same request, so three retries were one attempt made three times. With the trouble on the record
 * the next request is a different question and the route bends around the place that beat it.
 *
 * <p>Same snapshot discipline as the danger field: built on the server thread when a request is
 * made, immutable, safe for the search worker to read.
 */
public final class SetbackField {

    /**
     * Beyond this many blocks a setback contributes nothing. Small, because of the falloff below:
     * past two cells the term is already a rounding error, and carrying it further would only cost
     * arithmetic.
     */
    public static final double REACH = 4.0;

    /** Nothing has gone wrong anywhere — what a body that has been having a good day carries. */
    public static final SetbackField NONE = new SetbackField(List.of());

    /** One place that beat this body: where, what kind of trouble, and how much it is worth now. */
    public record Source(Pos at, Setbacks.Kind kind, double weight) {
    }

    private final List<Source> sources;

    SetbackField(List<Source> sources) {
        this.sources = List.copyOf(sources);
    }

    /**
     * How much trouble this place has given us. Zero where nothing remembered reaches it, which is
     * the answer nearly everywhere and costs nothing to get.
     */
    public double at(int x, int y, int z) {
        double total = 0.0;
        for (Source source : this.sources) {
            double dx = source.at().x() - x;
            double dy = source.at().y() - y;
            double dz = source.at().z() - z;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq > REACH * REACH) {
                continue;
            }
            total += falloff(source.weight(), distanceSq);
        }
        return total;
    }

    /**
     * How much of a setback's weight is felt this far from it: full on the cell itself, a quarter
     * one step away, a ninth diagonally, a twenty-fifth two cells out, nothing much beyond.
     *
     * <p>Sharp, unlike {@link DangerField}: fear radiates, so that field is
     * inverse-square over two dozen blocks, but trouble does not — a doorway that wedged this body
     * says nothing about the doorway beside it. Measured: with an inverse-square field the detour
     * around a bad doorway cost more than the doorway, the field having blanketed the detour too.
     *
     * <p>The tail remains because what gets recorded is the body's own FEET cell, and the thing
     * that beat it is usually the cell next door.
     */
    private static double falloff(double weight, double distanceSq) {
        double spread = 1.0 + distanceSq;
        return weight / (spread * spread);
    }

    /** Whether there is anything to weigh at all — the common case is no, and it is free. */
    public boolean isEmpty() {
        return this.sources.isEmpty();
    }

    /** Everything contributing, for a readout that wants to say what went wrong where. */
    public List<Source> sources() {
        return this.sources;
    }

    /** The worst trouble reaching this place, for a readout — empty when nothing does. */
    public Optional<Source> worstAt(int x, int y, int z) {
        Source worst = null;
        double best = 0.0;
        for (Source source : this.sources) {
            double dx = source.at().x() - x;
            double dy = source.at().y() - y;
            double dz = source.at().z() - z;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq > REACH * REACH) {
                continue;
            }
            double contribution = falloff(source.weight(), distanceSq);
            if (contribution > best) {
                best = contribution;
                worst = source;
            }
        }
        return Optional.ofNullable(worst);
    }

    @Override
    public String toString() {
        return "SetbackField(" + this.sources.size() + " source(s))";
    }
}
