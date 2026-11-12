Licenses of the native libraries in this jar
===========================================

The Skiko files at the top level of this jar (skiko-*.dll, libskiko-*.so or libskiko-*.dylib, and
icudtl.dat) were built by JetBrains. RenGL repackages them as published and changes nothing in them.
Each part stays under its own license. Copies of the license texts are in this folder
(META-INF/licenses/rengl/).

Library                                  License                  File
---------------------------------------  -----------------------  -----------------------------------
Skiko (skiko-*)                          Apache-2.0               Apache-2.0.txt
Skia (inside the Skiko library)          BSD-3-Clause             Skia-BSD-3-Clause.txt
ICU data (icudtl.dat)                    Unicode License V3       Unicode-License-V3-ICU-data.txt

Skiko's library also contains other projects under permissive licenses; see
https://github.com/JetBrains/skiko and https://skia.org for the complete list.

The LWJGL natives this artifact depends on are not part of this jar. They come from LWJGL's own
artifacts (org.lwjgl:*:natives-*) on Maven Central, each with its licenses inside.

The full list, with the exact versions RenGL uses, is in THIRD-PARTY-NOTICES.md in the
RenGL repository: https://github.com/piitex/RenGL/blob/main/THIRD-PARTY-NOTICES.md
