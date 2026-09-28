package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Yaws as Minecraft counts them: 0 faces +Z (south), 90 faces west, -90 east. */
class StickInputTest {

    private static final float EPS = 1e-5F;

    @Test
    void walkingTheWayItFacesIsAllForward() {
        StickInput stick = StickInput.toward(90.0F, 90.0F, 1.0F);
        assertEquals(1.0F, stick.forward(), EPS);
        assertEquals(0.0F, stick.left(), EPS);
    }

    @Test
    void facingSouthEastIsOnTheLeft() {
        // Vanilla turns xxa = +1 at yaw 0 into +X: left is positive.
        StickInput stick = StickInput.toward(0.0F, -90.0F, 1.0F);
        assertEquals(0.0F, stick.forward(), EPS);
        assertEquals(1.0F, stick.left(), EPS);
    }

    @Test
    void awayFromTheFacingIsAStepBackAtTheThrottle() {
        StickInput stick = StickInput.toward(0.0F, 180.0F, 0.3F);
        assertEquals(-0.3F, stick.forward(), EPS);
        assertEquals(0.0F, stick.left(), EPS);
    }
}
