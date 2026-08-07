# Third-party software in this jar

Anima itself is **MPL-2.0** (see `LICENSE`, which travels in this jar beside this file). One
third-party library rides along inside it, under different terms, and this file is where those
terms are named — because the library's own jars carry no licence text, so without this file
nobody holding the jar could read them.

| Library | Version | Licence | Text | Source |
|---|---|---|---|---|
| night-config `core` | 3.9.0 | **LGPL-3.0-or-later** | [`licenses/LGPL-3.0.txt`](licenses/LGPL-3.0.txt), [`licenses/GPL-3.0.txt`](licenses/GPL-3.0.txt) | [TheElectronWill/night-config](https://github.com/TheElectronWill/night-config) |
| night-config `toml` | 3.9.0 | **LGPL-3.0-or-later** | as above | as above |

Both are at `META-INF/jars/` inside this jar, exactly as published to Maven Central, byte for
byte. Neither has been modified, and their complete corresponding source is the upstream release
of the same version — `com.electronwill.night-config:{core,toml}:3.9.0`, whose `-sources.jar` is
published alongside it.

The LGPL is written around the GNU GPL and incorporates it by reference, so both texts are here;
`LGPL-3.0.txt` is the operative one and reads as a set of additional permissions on top of
`GPL-3.0.txt`.

## What it does

night-config reads and writes `config/anima.toml` and `config/<consumer>-danger.toml`. Nothing
else in Anima uses it, no Anima API exposes its types, and no other mod has to know it is here.

## Replacing it

LGPL-3.0 §4 asks that you be able to run Anima against a modified or newer version of the
library, and Fabric Loader is what makes that true without recompiling anything: drop a
`night-config` jar into `mods/` and Loader resolves the higher version, in preference to the copy
nested here. Nothing in this mod pins, shades, or relocates it — the classes stay in their own
`com.electronwill.nightconfig` packages precisely so that substitution works.

## Why it is nested at all

Anima is a library, and its consumers are mods, and a mod that requires its users to hand-install
a Java dependency does not get installed. Nesting is the ecosystem's answer to that and Fabric
Loader de-duplicates it across mods, so a player running Anima beside anything else that carries
night-config ends up with one copy, not two.

This is deliberately the *only* thing Anima nests. Anima itself is never nested into another mod
— it is published as its own download. See `LICENSING.md` and `CLAUDE.md` in the repository for
that decision and the reasoning behind it.
