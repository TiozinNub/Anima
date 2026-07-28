/**
 * Pure simulation layer: AI, planning, navigation, perception, data structures.
 *
 * <p>Rules for this package and everything below it:
 * <ul>
 *   <li>No {@code net.minecraft} or {@code net.fabricmc} imports — ever.</li>
 *   <li>No Stonecutter preprocessor comments.</li>
 *   <li>Java 17 language level while 1.20.1 remains a target Anima intends to return to.</li>
 *   <li>Unit-testable with plain JUnit, no Minecraft instance.</li>
 *   <li>NOTHING here may name a Person, a wolf, or any other specific body: a type that needs
 *       to know it is driving a humanoid belongs in the consuming mod instead.</li>
 * </ul>
 *
 * <p>The game world is reached only through the actuator and percept contracts
 * ({@link dev.luizloyola.anima.core.brain.act}, {@link dev.luizloyola.anima.core.brain.sense})
 * and the compat layer's version-neutral facades.
 */
package dev.luizloyola.anima.core;
