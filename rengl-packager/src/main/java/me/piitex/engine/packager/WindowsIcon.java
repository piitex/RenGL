package me.piitex.engine.packager;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Puts a {@code .ico} into an {@code .exe} as its icon, through the Win32 resource-update API. That API only exists
 * on Windows, so building a Windows image on another OS leaves the launcher's default icon in place.
 */
final class WindowsIcon {
    private static final int RT_ICON = 3;
    private static final int RT_GROUP_ICON = 14;

    private WindowsIcon() {
    }

    static boolean available() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    static void apply(Path exe, Path ico) throws Throwable {
        ByteBuffer in = ByteBuffer.wrap(Files.readAllBytes(ico)).order(ByteOrder.LITTLE_ENDIAN);
        if (in.getShort(0) != 0 || in.getShort(2) != 1) throw new IOException(ico + " is not a .ico file");
        int count = in.getShort(4) & 0xFFFF;

        Linker linker = Linker.nativeLinker();
        SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32", Arena.global());
        MethodHandle begin = linker.downcallHandle(kernel.find("BeginUpdateResourceW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        MethodHandle update = linker.downcallHandle(kernel.find("UpdateResourceW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_SHORT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        MethodHandle end = linker.downcallHandle(kernel.find("EndUpdateResourceW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment path = arena.allocateFrom(exe.toAbsolutePath().toString(), java.nio.charset.StandardCharsets.UTF_16LE);
            MemorySegment handle = (MemorySegment) begin.invoke(path, 0);
            if (handle.equals(MemorySegment.NULL)) throw new IOException("Could not open " + exe + " to set its icon");

            // RT_GROUP_ICON: a directory (reserved, type, count) of 14-byte entries that point at the RT_ICON images by id.
            ByteBuffer group = ByteBuffer.allocate(6 + 14 * count).order(ByteOrder.LITTLE_ENDIAN);
            group.putShort((short) 0).putShort((short) 1).putShort((short) count);
            for (int i = 0; i < count; i++) {
                int entry = 6 + 16 * i;
                int size = in.getInt(entry + 8);
                int offset = in.getInt(entry + 12);
                group.put(in.get(entry)).put(in.get(entry + 1)).put(in.get(entry + 2)).put(in.get(entry + 3));
                group.putShort(in.getShort(entry + 4)).putShort(in.getShort(entry + 6));
                group.putInt(size).putShort((short) (i + 1));

                MemorySegment image = arena.allocate(size);
                image.copyFrom(MemorySegment.ofArray(in.array()).asSlice(offset, size));
                put(update, handle, RT_ICON, i + 1, image, size);
            }
            MemorySegment directory = arena.allocate(group.capacity());
            directory.copyFrom(MemorySegment.ofArray(group.array()));
            put(update, handle, RT_GROUP_ICON, 1, directory, group.capacity());

            if ((int) end.invoke(handle, 0) == 0) throw new IOException("Could not write the icon into " + exe);
        }
    }

    /** MAKEINTRESOURCE: a small integer passed where a string pointer is expected. */
    private static void put(MethodHandle update, MemorySegment handle, int type, int id, MemorySegment data, int size) throws Throwable {
        int ok = (int) update.invoke(handle, MemorySegment.ofAddress(type), MemorySegment.ofAddress(id), (short) 0, data, size);
        if (ok == 0) throw new IOException("UpdateResource failed for resource " + type + "/" + id);
    }
}
