# Third-party notices

RenGL itself is under the RenGL Open-Source-Game License (RSGL) 1.0, in [LICENSE](LICENSE).
The components below are not covered by it. Each stays under its own license, as Section 9 of
the RSGL says, and nothing here changes those licenses.

Versions are the ones the build uses at the time of writing (see the poms).

## Libraries that RenGL depends on

These are downloaded by Maven. They are not copied into the RenGL source tree.

| Component | Version | License | Used by |
|---|---|---|---|
| [LWJGL 3](https://www.lwjgl.org) (core, GLFW, OpenGL, OpenAL, Opus, STB, NFD, Assimp, Shaderc, SPIRV-Cross, VMA, Vulkan, Zstd) | 3.4.3 | BSD-3-Clause | most modules |
| [Skiko](https://github.com/JetBrains/skiko) (Skia for the JVM) | 0.154.0 | Apache-2.0 | rengl-core and the modules that draw |
| [Kotlin standard library](https://kotlinlang.org) | 2.3.20 | Apache-2.0 | rengl-core (needed by Skiko) |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | 1.8.0 | Apache-2.0 | rengl-core, at run time |
| [JetBrains Runtime API (jbr-api)](https://github.com/JetBrains/JetBrainsRuntime) | 1.5.0 | Apache-2.0 | rengl-core, at run time |
| [Apache Lucene](https://lucene.apache.org) (analysis-common) | 10.2.2 | Apache-2.0 | rengl-spellcheck |
| [Apache Log4j API](https://logging.apache.org/log4j/) | 2.26.1 | Apache-2.0 | rengl-core |
| [JLayer](https://www.javazoom.net/javalayer/javalayer.html) (`com.googlecode.soundlibs:jlayer`) | 1.0.1-1 | LGPL-2.1 | rengl-audio (MP3 decoding) |

The full Apache License 2.0 is in [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt). Apache
Lucene and Log4j ship their own `NOTICE` files inside their jars; if you redistribute those
jars, keep them.

### Skia inside rengl-core

The rengl-core jar contains the Skia API classes from Skiko (they are shaded in so that the
other modules can compile against them). Those classes are under the Apache License 2.0, and
Skiko's native library includes Skia and other projects under their own permissive licenses
(Skia is BSD-3-Clause, by The Skia Authors / Google). See the Skiko and Skia projects for the
complete list.

### LWJGL

LWJGL is Copyright (c) 2012-present Lightweight Java Game Library, under the BSD 3-Clause
license: <https://www.lwjgl.org/license>. The text is in
[licenses/LWJGL-BSD-3-Clause.txt](licenses/LWJGL-BSD-3-Clause.txt), copied from
<https://github.com/LWJGL/lwjgl3/blob/master/LICENSE.md>.

The LWJGL native libraries are built from other projects, each under its own license. LWJGL
keeps each license text next to the binding, as `modules/lwjgl/<module>/<project>_license.txt`
in its repository (<https://github.com/LWJGL/lwjgl3/tree/master/modules/lwjgl>), and that is
the authoritative source. This table was checked against those files for LWJGL 3.4.3. The
ones relevant to RenGL's modules are:

| Project | Used for | License |
|---|---|---|
| GLFW | windows and input | zlib |
| OpenAL Soft | audio | LGPL 2.0 (the GNU Library General Public License, version 2) |
| Opus | audio | BSD-3-Clause |
| Assimp | model loading | BSD-3-Clause (it also bundles some smaller libraries, such as Poly2Tri, under their own licenses) |
| shaderc | shader compilation | Apache-2.0 |
| Vulkan Memory Allocator | Vulkan memory | MIT |
| Zstandard | tile map compression | BSD-3-Clause (Zstandard is dual-licensed; LWJGL ships the BSD one) |
| nativefiledialog-extended | file dialogs | zlib |
| libffi | LWJGL core, native calls | MIT |
| liburing | LWJGL core, Linux only | MIT |
| Khronos OpenGL and Vulkan headers | OpenGL and Vulkan bindings | MIT-style Khronos license |
| stb, SPIRV-Cross | images and fonts; shader cross-compilation | not in LWJGL's repository; from the upstream projects: stb is public domain / MIT, SPIRV-Cross is Apache-2.0 |

The `natives-<platform>` jars that RenGL publishes contain these libraries' binaries. Each of
those jars carries the license texts in `META-INF/licenses/rengl/` (the sources are in
[licenses/natives](licenses/natives)), because several of the licenses require it.

OpenAL Soft is loaded by LWJGL as a separate shared library, so you can replace it with
your own build of OpenAL Soft. If you ship it, you must also pass on the LGPL's terms; the
license text is in LWJGL's repository at `modules/lwjgl/openal/openal_soft_license.txt`.

### JLayer and the LGPL

JLayer is used as an ordinary, unmodified, separate jar. RenGL does not shade, relocate or
modify it, so you can replace it with another version of JLayer, as the LGPL requires you to
be able to. If you package an application with RenGL's packager, JLayer stays a separate jar
in the application's `lib/app` folder. The license text is at
<https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html>, and the JLayer project is at
<https://www.javazoom.net/javalayer/javalayer.html>.

## Files that are copied into the RenGL repository

### Feather icons

`rengl-core/src/main/resources/icons/feather/` (287 SVG icons)

Copyright (c) 2013-2023 Cole Bemis. MIT License. The license text is in
`rengl-core/src/main/resources/icons/feather/LICENSE`, next to the icons, and must stay with
them. Source: <https://feathericons.com>.

### Hunspell en_US dictionary

`rengl-spellcheck/src/main/resources/dictionaries/en_US.dic` and `en_US.aff`

The English Hunspell dictionary derived from SCOWL (Spell Checker Oriented Word Lists),
version 2020.12.07, Copyright 2000-2018 by Kevin Atkinson, with the copyrights of the
sources it is built from. The dictionary's own license text, with all of those notices, is in
`rengl-spellcheck/src/main/resources/dictionaries/en_US-LICENSE.txt`, next to the dictionary,
and must stay with it. Source: <http://wordlist.sourceforge.net>.

### Roboto (examples only)

`rengl-examples/src/main/resources/Roboto-Regular.ttf`, version 3.015

Copyright 2011 The Roboto Project Authors (<https://github.com/googlefonts/roboto-classic>).
Licensed under the SIL Open Font License, Version 1.1. The font is used only by the example
applications. It is not part of the RenGL library jars.

The license text, which must be included with any copy of the font, follows.

```
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide development of
collaborative font projects, to support the font creation efforts of academic and linguistic
communities, and to provide a free and open framework in which fonts may be shared and
improved in partnership with others.

The OFL allows the licensed fonts to be used, studied, modified and redistributed freely as
long as they are not sold by themselves. The fonts, including any derivative works, can be
bundled, embedded, redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives, however, cannot be
released under any other type of license. The requirement for fonts to remain under this
license does not apply to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright Holder(s) under this
license and clearly marked as such. This may include source files, build scripts and
documentation.

"Reserved Font Name" refers to any names specified as such after the copyright statement(s).

"Original Version" refers to the collection of Font Software components as distributed by the
Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting, or substituting --
in part or in whole -- any of the components of the Original Version, by changing formats or
by porting the Font Software to a new environment.

"Author" refers to any designer, engineer, programmer, technical writer or other person who
contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining a copy of the Font
Software, to use, study, copy, merge, embed, modify, redistribute, and sell modified and
unmodified copies of the Font Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components, in Original or Modified
Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled, redistributed and/or
sold with any software, provided that each copy contains the above copyright notice and this
license. These can be included either as stand-alone text files, human-readable headers or in
the appropriate machine-readable metadata fields within text or binary files as long as those
fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font Name(s) unless
explicit written permission is granted by the corresponding Copyright Holder. This
restriction only applies to the primary font name as presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font Software shall not be
used to promote, endorse or advertise any Modified Version, except to acknowledge the
contribution(s) of the Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole, must be distributed
entirely under this license, and must not be distributed under any other license. The
requirement for fonts to remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
PURPOSE AND NONINFRINGEMENT OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT
SHALL THE COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, INCLUDING ANY
GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF THE USE OR INABILITY TO USE THE FONT
SOFTWARE OR FROM OTHER DEALINGS IN THE FONT SOFTWARE.
```

### Maven Wrapper

`.mvn/wrapper/maven-wrapper.jar`, `mvnw`, `mvnw.cmd`

The Apache Maven Wrapper, Apache License 2.0 ([licenses/Apache-2.0.txt](licenses/Apache-2.0.txt)).
It is a build tool and is not part of any RenGL jar.

## Not included in RenGL

RenGL does not contain or redistribute these. If you use them, you get them from their
vendors under their terms.

- NVIDIA DLSS, DLSS Ray Reconstruction, DLSS Frame Generation and Streamline
- AMD FidelityFX (FSR 3 and FSR 4)
- Apple MetalFX
- Microsoft Edge WebView2, and any Chrome, Edge, Chromium or Brave browser that the web view
  module drives
- GStreamer, WebKitGTK and WKWebView, which the video and web view modules load from the
  system

The rengl-upscale module carries no vendor binaries; the libraries are found at run time. It
is RenGL's own code that calls them, written against the interfaces that NVIDIA publishes with
Streamline (MIT-style license, Copyright (c) 2023 NVIDIA Corporation) and AMD publishes with the
FidelityFX SDK (MIT license, Copyright (C) Advanced Micro Devices, Inc.). It does not include
the source files of either. If you ship any of the libraries, their licenses are yours to follow; see
[Licenses and names](docs/3d/upscaling.md#licenses-and-names).

## Trademarks

NVIDIA, DLSS, RTX and GeForce are trademarks of NVIDIA Corporation. AMD, FidelityFX and FSR are
trademarks of Advanced Micro Devices, Inc. Apple, macOS and MetalFX are trademarks of Apple
Inc. Microsoft, Windows and Edge are trademarks of Microsoft Corporation. All other names
belong to their owners. They are used here only to say what RenGL works with, and RenGL is
not made, approved or endorsed by any of them.
