package me.piitex.engine.packager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WindowsVersionTest {
    @Test
    void aVersionBecomesFourNumbers() {
        assertArrayEquals(new int[] {1, 2, 3, 0}, WindowsVersion.numbers("1.2.3"));
        assertArrayEquals(new int[] {1, 0, 0, 0}, WindowsVersion.numbers("1.0.0-beta.2"));
        assertArrayEquals(new int[] {2024, 5, 0, 0}, WindowsVersion.numbers(" 2024.05 "));
        assertArrayEquals(new int[] {1, 2, 3, 4}, WindowsVersion.numbers("1.2.3.4.5"));
        assertArrayEquals(new int[] {0, 0, 0, 0}, WindowsVersion.numbers(""));
        assertArrayEquals(new int[] {0xFFFF, 1, 0, 0}, WindowsVersion.numbers("70000.1"));
    }

    @Test
    void theResourceIsOneAlignedBlockThatNamesItself() {
        byte[] resource = WindowsVersion.build(new WindowsVersion.Info("Sample App", "1.2.3", "Acme", "Does things", "Sample App.exe"));
        java.nio.ByteBuffer in = java.nio.ByteBuffer.wrap(resource).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(resource.length, in.getShort(0) & 0xFFFF, "the root's length covers the whole resource");
        assertEquals(52, in.getShort(2) & 0xFFFF, "and its value is the fixed info");
        assertEquals("VS_VERSION_INFO", new String(resource, 6, 30, StandardCharsets.UTF_16LE));
        assertEquals(0xFEEF04BD, in.getInt(40), "the fixed info starts after the key, on a 4-byte boundary");
        assertEquals(1 << 16 | 2, in.getInt(48));
        assertEquals(3 << 16, in.getInt(52));
    }

    @Test
    void windowsReadsBackWhatWasWritten(@TempDir Path dir) throws Throwable {
        assumeTrue(WindowsVersion.available(), "the Win32 resource API only exists on Windows");
        Path exe = dir.resolve("Sample App.exe");
        try (InputStream launcher = ImageBuilder.class.getResourceAsStream("launcher-windows-x64.exe")) {
            Files.copy(launcher, exe);
        }
        WindowsVersion.apply(exe, new WindowsVersion.Info("Sample App", "1.2.3", "Acme Ltd", "Does things\nacross two lines", "Sample App.exe"));

        Linker linker = Linker.nativeLinker();
        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup version = SymbolLookup.libraryLookup("version", arena);
            MethodHandle size = linker.downcallHandle(version.find("GetFileVersionInfoSizeW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle load = linker.downcallHandle(version.find("GetFileVersionInfoW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            MethodHandle query = linker.downcallHandle(version.find("VerQueryValueW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            MemorySegment path = arena.allocateFrom(exe.toString(), StandardCharsets.UTF_16LE);
            int bytes = (int) size.invoke(path, MemorySegment.NULL);
            assertTrue(bytes > 0, "Windows finds a version resource in the exe");
            MemorySegment block = arena.allocate(bytes);
            assertEquals(1, (int) load.invoke(path, 0, bytes, block), "and loads it");

            assertEquals("Sample App", text(arena, query, block, "\\StringFileInfo\\040904B0\\ProductName"));
            assertEquals("Acme Ltd", text(arena, query, block, "\\StringFileInfo\\040904B0\\CompanyName"));
            assertEquals("Does things across two lines", text(arena, query, block, "\\StringFileInfo\\040904B0\\FileDescription"));
            assertEquals("1.2.3", text(arena, query, block, "\\StringFileInfo\\040904B0\\FileVersion"));
            assertEquals("Sample App.exe", text(arena, query, block, "\\StringFileInfo\\040904B0\\OriginalFilename"));
            assertEquals("Copyright Acme Ltd", text(arena, query, block, "\\StringFileInfo\\040904B0\\LegalCopyright"));

            MemorySegment holder = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment length = arena.allocate(ValueLayout.JAVA_INT);
            assertEquals(1, (int) query.invoke(block, arena.allocateFrom("\\", StandardCharsets.UTF_16LE), holder, length));
            MemorySegment fixed = holder.get(ValueLayout.ADDRESS, 0).reinterpret(52);
            assertEquals(1 << 16 | 2, fixed.get(ValueLayout.JAVA_INT, 8), "the file version, high half");
            assertEquals(3 << 16, fixed.get(ValueLayout.JAVA_INT, 12), "and low half");
        }
    }

    private static String text(Arena arena, MethodHandle query, MemorySegment block, String name) throws Throwable {
        MemorySegment holder = arena.allocate(ValueLayout.ADDRESS);
        MemorySegment length = arena.allocate(ValueLayout.JAVA_INT);
        int ok = (int) query.invoke(block, arena.allocateFrom(name, StandardCharsets.UTF_16LE), holder, length);
        assertEquals(1, ok, name + " is in the resource");
        int chars = length.get(ValueLayout.JAVA_INT, 0);
        return holder.get(ValueLayout.ADDRESS, 0).reinterpret(chars * 2L).getString(0, StandardCharsets.UTF_16LE);
    }
}
