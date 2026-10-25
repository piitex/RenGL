package me.piitex.engine.packager;

import java.io.ByteArrayOutputStream;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Gives an {@code .exe} its version resource: the "Details" tab of its properties, the name Task Manager and the
 * "Open file" security prompt show, and what a virus scanner reads to tell a program from an unknown file. Like
 * {@link WindowsIcon} it goes through the Win32 resource-update API, so it only works when building on Windows.
 */
final class WindowsVersion {
    private static final int RT_VERSION = 16;
    private static final int ENGLISH_US = 0x0409;
    private static final int UNICODE = 0x04B0;   // code page 1200
    private static final int FIXED_INFO_SIZE = 52;

    /** What the resource says. */
    record Info(String name, String version, String vendor, String description, String fileName) {
    }

    private WindowsVersion() {
    }

    static boolean available() {
        return WindowsIcon.available();
    }

    static void apply(Path exe, Info info) throws Throwable {
        byte[] resource = build(info);
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
            MemorySegment path = arena.allocateFrom(exe.toAbsolutePath().toString(), StandardCharsets.UTF_16LE);
            MemorySegment handle = (MemorySegment) begin.invoke(path, 0);
            if (handle.equals(MemorySegment.NULL)) throw new IOException("Could not open " + exe + " to set its version");
            MemorySegment data = arena.allocate(resource.length);
            data.copyFrom(MemorySegment.ofArray(resource));
            // MAKEINTRESOURCE: small integers passed where a string pointer is expected.
            int ok = (int) update.invoke(handle, MemorySegment.ofAddress(RT_VERSION), MemorySegment.ofAddress(1), (short) ENGLISH_US, data, resource.length);
            if (ok == 0) {
                end.invoke(handle, 1);   // discard
                throw new IOException("UpdateResource failed for the version of " + exe);
            }
            if ((int) end.invoke(handle, 0) == 0) throw new IOException("Could not write the version into " + exe);
        }
    }

    // ---- the resource ---------------------------------------------------------------------------------------------

    /** The four numbers of a version such as {@code 1.2.3-beta}: the leading digits of each dot-separated part, 0 where there are none. */
    static int[] numbers(String version) {
        int[] parts = new int[4];
        String[] pieces = version.strip().split("\\.");
        for (int i = 0; i < 4 && i < pieces.length; i++) {
            int end = 0;
            while (end < pieces[i].length() && end < 9 && Character.isDigit(pieces[i].charAt(end))) end++;
            parts[i] = end == 0 ? 0 : Math.min(0xFFFF, Integer.parseInt(pieces[i].substring(0, end)));
            if (end < pieces[i].length()) break;   // "0-beta" ends the version
        }
        return parts;
    }

    /** A VS_VERSIONINFO structure (see the Win32 documentation) holding the fixed numbers and the strings. */
    static byte[] build(Info info) {
        int[] v = numbers(info.version());
        ByteBuffer fixed = ByteBuffer.allocate(FIXED_INFO_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        fixed.putInt(0xFEEF04BD).putInt(0x00010000);              // signature, structure version
        fixed.putInt(v[0] << 16 | v[1]).putInt(v[2] << 16 | v[3]); // file version
        fixed.putInt(v[0] << 16 | v[1]).putInt(v[2] << 16 | v[3]); // product version
        fixed.putInt(0x3F).putInt(0);                              // flags mask, flags
        fixed.putInt(0x00040004).putInt(1).putInt(0);              // VOS_NT_WINDOWS32, VFT_APP, no subtype
        fixed.putInt(0).putInt(0);                                 // date

        String text = info.version();
        String name = info.name();
        String copyright = info.vendor().isBlank() ? "" : "Copyright " + info.vendor();
        List<String[]> strings = List.of(
                new String[] {"CompanyName", info.vendor()},
                new String[] {"FileDescription", info.description().isBlank() ? name : info.description()},
                new String[] {"FileVersion", text},
                new String[] {"InternalName", name},
                new String[] {"LegalCopyright", copyright},
                new String[] {"OriginalFilename", info.fileName()},
                new String[] {"ProductName", name},
                new String[] {"ProductVersion", text});

        List<byte[]> entries = strings.stream().map(s -> block(s[0], 1, utf16(clip(s[1])), clip(s[1]).length() + 1, List.of())).toList();
        byte[] table = block(String.format("%04X%04X", ENGLISH_US, UNICODE), 1, new byte[0], 0, entries);
        byte[] stringInfo = block("StringFileInfo", 1, new byte[0], 0, List.of(table));

        byte[] translation = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(UNICODE << 16 | ENGLISH_US).array();
        byte[] variable = block("Translation", 0, translation, translation.length, List.of());
        byte[] varInfo = block("VarFileInfo", 1, new byte[0], 0, List.of(variable));

        return block("VS_VERSION_INFO", 0, fixed.array(), FIXED_INFO_SIZE, List.of(stringInfo, varInfo));
    }

    /** Values are limited so a long description cannot push the resource past what the loader reads. */
    private static String clip(String value) {
        String single = value.replace('\r', ' ').replace('\n', ' ').strip();
        return single.length() > 1000 ? single.substring(0, 1000) : single;
    }

    private static byte[] utf16(String value) {
        byte[] text = value.getBytes(StandardCharsets.UTF_16LE);
        byte[] withNull = new byte[text.length + 2];
        System.arraycopy(text, 0, withNull, 0, text.length);
        return withNull;
    }

    /**
     * One node of the structure: length, value length (bytes for binary values, characters for text), type, the key, the
     * value, then the children, each part starting on a 4-byte boundary. A node's length leaves out the padding after it.
     */
    private static byte[] block(String key, int type, byte[] value, int valueLength, List<byte[]> children) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[6]);   // the three 16-bit fields, filled in below
        out.writeBytes(utf16(key));
        pad(out);
        out.writeBytes(value);
        for (byte[] child : children) {
            pad(out);
            out.writeBytes(child);
        }
        byte[] bytes = out.toByteArray();
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(0, (short) bytes.length).putShort(2, (short) valueLength).putShort(4, (short) type);
        return bytes;
    }

    private static void pad(ByteArrayOutputStream out) {
        while (out.size() % 4 != 0) out.write(0);
    }
}
