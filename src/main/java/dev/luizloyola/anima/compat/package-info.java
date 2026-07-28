/**
 * Version-compatibility layer: the only home of version-specific code in Anima.
 *
 * <p>This package defines a version-neutral facade (world queries, block probing, inventories,
 * saved data, gizmo drawing…) named for what a thinking body needs — not for Minecraft
 * internals. {@link dev.luizloyola.anima.core} and the consuming mod's wiring use these; every
 * Minecraft version difference is absorbed here.
 *
 * <p>Stonecutter preprocessor comments ({@code //? if …}) are allowed only in this package
 * (and in mixins, which are part of the compat surface).
 *
 * <p>Like the rest of Anima, nothing here may name a Person or any other specific body: a
 * facade is written for the capability, not for the creature that uses it.
 */
package dev.luizloyola.anima.compat;
