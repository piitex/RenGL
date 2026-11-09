Licenses of the native libraries in this jar
===========================================

The files in this jar's native-library folders (windows/, linux/, macos/ and the
Skiko files at the top level) were built by other projects. RenGL repackages the
binaries as published by LWJGL and JetBrains and changes nothing in them. Each one
stays under its own license, listed here. Copies of the license texts are in this
folder (META-INF/licenses/rengl/). Not every library below is in every jar: each
RenGL module carries only its own natives.

Library                                  License                  File
---------------------------------------  -----------------------  -----------------------------------
LWJGL (lwjgl, lwjgl_opengl, lwjgl_vma,   BSD-3-Clause             LWJGL-BSD-3-Clause.txt
  lwjgl_nfd, lwjgl_stb, lwjgl_zstd)
GLFW                                     zlib                     GLFW-zlib.txt
nativefiledialog-extended                zlib                     nativefiledialog-extended-zlib.txt
libffi (in the LWJGL core library)       MIT                      libffi-MIT.txt
liburing (in the LWJGL core library,     MIT                      liburing-MIT.txt
  Linux)
Khronos OpenGL and Vulkan headers        MIT-style                Khronos-headers-MIT.txt
stb                                      MIT or public domain     stb-MIT-or-public-domain.txt
OpenAL Soft                              LGPL 2.0                 OpenAL-Soft-LGPL-2.0.txt
Opus                                     BSD-3-Clause             Opus-BSD-3-Clause.txt
Assimp (and the libraries it bundles)    BSD-3-Clause and others  Assimp-BSD-3-Clause.txt
Draco (draco library next to Assimp)     Apache-2.0               Apache-2.0.txt
shaderc                                  Apache-2.0               Apache-2.0.txt
SPIRV-Cross                              Apache-2.0               Apache-2.0.txt
Vulkan Memory Allocator                  MIT                      VulkanMemoryAllocator-MIT.txt
Zstandard                                BSD-3-Clause             Zstandard-BSD-3-Clause.txt
MoltenVK (macOS)                         Apache-2.0               Apache-2.0.txt
Skiko (skiko-*)                          Apache-2.0               Apache-2.0.txt
Skia (inside the Skiko library)          BSD-3-Clause             Skia-BSD-3-Clause.txt
ICU data (icudtl.dat)                    Unicode License V3       Unicode-License-V3-ICU-data.txt

OpenAL Soft is a separate shared library (OpenAL.dll, libopenal.so, libopenal.dylib),
so you can replace it with your own build, as the LGPL requires you to be able to do.
The OpenAL Soft source code is at https://github.com/kcat/openal-soft.

Skiko's library also contains other projects under permissive licenses; see
https://github.com/JetBrains/skiko and https://skia.org for the complete list.

The full list, with the exact versions RenGL uses, is in THIRD-PARTY-NOTICES.md in the
RenGL repository: https://github.com/piitex/RenGL/blob/main/THIRD-PARTY-NOTICES.md
