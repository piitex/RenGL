package me.piitex.engine.packager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The right-click entries and Open with registrations: how the option is read, what goes into {@code setup.ini}, the
 * Info.plist the macOS side writes, and, on Windows, a real silent install and uninstall against the current user's registry.
 */
class ShellRegistrationTest {
    @TempDir
    Path dir;

    // ---- --shell-verb ------------------------------------------------------------------------------------------------

    @Test
    void aVerbIsReadFromItsFourParts() {
        AppSpec.ShellVerb verb = AppSpec.ShellVerb.parse("Directory|OpenZip.Compress1Zip|Compress to ZIP|--compress=zip \"%1\"");
        assertEquals("Directory", verb.target());
        assertEquals("OpenZip.Compress1Zip", verb.id());
        assertEquals("Compress to ZIP", verb.label());
        assertEquals("--compress=zip \"%1\"", verb.args());
    }

    @Test
    void anExtensionGoesUnderSystemFileAssociationsAndAClassDoesNot() {
        assertEquals("SystemFileAssociations\\.zip\\shell\\OpenZip.Open", AppSpec.ShellVerb.parse(".ZIP|OpenZip.Open|Open with OpenZip|\"%1\"").registryKey());
        assertEquals("Directory\\shell\\OpenZip.Compress2Options", AppSpec.ShellVerb.parse("Directory|OpenZip.Compress2Options|Compress with OpenZip...|\"%1\"").registryKey());
        assertEquals("Directory\\Background\\shell\\Here", AppSpec.ShellVerb.parse("Directory\\Background|Here|Here|.").registryKey());
    }

    @Test
    void theArgumentsMayHoldBars() {
        assertEquals("a | b", AppSpec.ShellVerb.parse(".zip|id|Label|a | b").args());
    }

    @Test
    void badVerbsSayWhatIsWrong() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AppSpec.ShellVerb.parse(".zip|id|Label")).getMessage().contains("<target>|<id>|<label>|<args>"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AppSpec.ShellVerb.parse("zip|id|Label|%1")).getMessage().contains("target"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AppSpec.ShellVerb.parse("HKEY_LOCAL_MACHINE|id|Label|%1")).getMessage().contains("target"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AppSpec.ShellVerb.parse(".zip|a\\b|Label|%1")).getMessage().contains("id"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AppSpec.ShellVerb.parse(".zip|id||%1")).getMessage().contains("label"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AppSpec.ShellVerb.parse(".zip|id|Label|%1\nmore")).getMessage().contains("one line"));
    }

    @Test
    void setupIniListsEveryVerbAndTypeInOrder() {
        AppSpec app = app("me.rengl.open-zip",
                List.of(AppSpec.ShellVerb.parse(".zip|OpenZip.Open|Open with OpenZip|\"%1\""),
                        AppSpec.ShellVerb.parse("Directory|OpenZip.Compress1Zip|Compress to ZIP|--compress=zip \"%1\"")),
                List.of(new AppSpec.FileAssociation("ZIP", "application/zip", "ZIP archive"), new AppSpec.FileAssociation("7z", "", "7z | archive")), "");
        assertEquals("""
                verb=.zip|OpenZip.Open|Open with OpenZip|"%1"
                verb=Directory|OpenZip.Compress1Zip|Compress to ZIP|--compress=zip "%1"
                assoc=.zip|ZIP archive
                assoc=.7z|7z / archive
                """, WindowsSetup.registrations(app));
    }

    // ---- --document-types --------------------------------------------------------------------------------------------

    private static final String TYPES = """
            <array>
              <dict>
                <key>CFBundleTypeName</key><string>ZIP archive</string>
                <key>CFBundleTypeRole</key><string>Viewer</string>
                <key>LSHandlerRank</key><string>Alternate</string>
                <key>LSItemContentTypes</key><array><string>public.zip-archive</string></array>
              </dict>
            </array>
            """;

    @Test
    void anArrayGetsTheDocumentTypesKey() {
        String entries = PlistFragment.documentTypes(TYPES);
        assertTrue(entries.startsWith("<key>CFBundleDocumentTypes</key>\n<array>"));
        assertTrue(entries.contains("public.zip-archive"));
    }

    @Test
    void keyEntriesAreTakenAsTheyAre() {
        String text = "<key>CFBundleDocumentTypes</key>" + TYPES + "<key>UTImportedTypeDeclarations</key><array/>";
        assertEquals(text, PlistFragment.documentTypes(text));
    }

    @Test
    void theInfoPlistUsesTheSuppliedTypesInsteadOfTheGeneratedOnes() {
        AppSpec app = app("me.rengl.open-zip", List.of(), List.of(new AppSpec.FileAssociation("zip", "", "ZIP file")), PlistFragment.documentTypes(TYPES));
        String plist = new ImageBuilder(app, Platform.MACOS_ARM64, List.of(), dir).infoPlist("");
        assertTrue(plist.contains("<string>Alternate</string>"));
        assertTrue(plist.contains("public.zip-archive"));
        assertFalse(plist.contains("CFBundleTypeExtensions"), "the generated type is replaced, not added to");
        assertEquals(1, plist.split("CFBundleDocumentTypes", -1).length - 1);
        assertTrue(plist.endsWith("</dict>\n</plist>\n"));
    }

    @Test
    void withoutSuppliedTypesTheInfoPlistListsTheAssociations() {
        AppSpec app = app("me.rengl.open-zip", List.of(), List.of(new AppSpec.FileAssociation("zip", "", "ZIP file")), "");
        String plist = new ImageBuilder(app, Platform.MACOS_ARM64, List.of(), dir).infoPlist("");
        assertTrue(plist.contains("<key>CFBundleTypeExtensions</key><array><string>zip</string></array>"));
    }

    @Test
    void badDocumentTypesAreRefusedWithTheFileName() throws IOException {
        for (String bad : List.of("", "<?xml version=\"1.0\"?><plist><dict/></plist>", "<string>x</string>", "<array><dict></array>", "<key>NSHumanReadableCopyright</key><string>x</string>")) {
            Path file = Files.writeString(dir.resolve("types.plist"), bad, StandardCharsets.UTF_8);
            String message = assertThrows(IllegalArgumentException.class, () -> PlistFragment.documentTypes(file)).getMessage();
            assertTrue(message.contains("--document-types") && message.contains("types.plist"), message);
        }
        assertThrows(IllegalArgumentException.class, () -> PlistFragment.documentTypes(dir.resolve("missing.plist")));
    }

    // ---- a real install --------------------------------------------------------------------------------------------

    /**
     * Runs the setup this packager builds, silently, for the current user, and looks in the registry with {@code reg}:
     * the entries are there with the right values, and the uninstaller takes exactly them away. The names are made up
     * (the {@code .renglt} extension, ids starting with {@code RenglTest}), so nothing real is touched; the Directory entry
     * is the one that shows in Explorer, for the seconds the test runs.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aSilentInstallRegistersTheEntriesAndTheUninstallerRemovesThem() throws Exception {
        String appId = "me.rengl.test.shellregistration";
        Path image = Files.createDirectories(dir.resolve("image"));
        Files.write(image.resolve("RenglTest.exe"), new byte[] {1, 2, 3});
        AppSpec app = new AppSpec("RenglTest", "1.0.0", "Test", "Test", appId, dir, "app.jar", "Main", List.of(), Map.of(),
                List.of(new AppSpec.FileAssociation("renglt", "", "Rengl test file")), List.of(),
                List.of(AppSpec.ShellVerb.parse(".renglt|RenglTest.Open|Open with Rengl test|\"%1\""),
                        AppSpec.ShellVerb.parse("Directory|RenglTest.Compress|Rengl test compress|--compress=zip \"%1\"")), "");
        Path setup = WindowsSetup.build(app, Platform.WINDOWS_X64, image, null, Files.createDirectories(dir.resolve("out")));
        Path install = dir.resolve("installed");

        String open = "HKCU\\Software\\Classes\\SystemFileAssociations\\.renglt\\shell\\RenglTest.Open";
        String compress = "HKCU\\Software\\Classes\\Directory\\shell\\RenglTest.Compress";
        String progId = "HKCU\\Software\\Classes\\" + appId + ".renglt";
        String withProgids = "HKCU\\Software\\Classes\\.renglt\\OpenWithProgids";
        String uninstallEntry = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\" + appId;
        try {
            assertEquals(0, run(setup.toString(), "/S", "/DIR=" + install, "/DESKTOP=0", "/STARTMENU=0"));
            String exe = "\"" + install.resolve("RenglTest.exe") + "\"";

            String openKey = query(open);
            assertTrue(openKey.contains("(Default)    REG_SZ    Open with Rengl test"), openKey);
            assertTrue(openKey.contains("Icon    REG_SZ    " + exe), openKey);
            assertTrue(query(open + "\\command").contains("REG_SZ    " + exe + " \"%1\""), query(open + "\\command"));
            assertTrue(query(compress).contains("Rengl test compress"));
            assertTrue(query(compress + "\\command").contains("REG_SZ    " + exe + " --compress=zip \"%1\""));

            assertTrue(query(progId).contains("Rengl test file"));
            assertTrue(query(progId + "\\shell\\open\\command").contains("REG_SZ    " + exe + " \"%1\""));
            assertTrue(query(withProgids).contains(appId + ".renglt"), query(withProgids));

            String data = Files.readString(install.resolve("uninstall.dat"), StandardCharsets.UTF_8);
            assertTrue(data.contains("regkey=Software\\Classes\\SystemFileAssociations\\.renglt\\shell\\RenglTest.Open\n"), data);
            assertTrue(data.contains("regkey=Software\\Classes\\Directory\\shell\\RenglTest.Compress\n"), data);
            assertTrue(data.contains("regvalue=Software\\Classes\\.renglt\\OpenWithProgids|" + appId + ".renglt\n"), data);

            run(install.resolve("uninstall.exe").toString(), "/S");   // hands the work to a copy in the temp folder
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (exists(open) && System.nanoTime() < deadline) Thread.sleep(100);

            assertFalse(exists(open));
            assertFalse(exists(compress));
            assertFalse(exists(progId));
            assertFalse(query(withProgids).contains(appId), "only the app's own value goes");
            assertTrue(exists("HKCU\\Software\\Classes\\Directory"), "the class itself stays");
        } finally {
            for (String key : List.of(open, compress, progId, uninstallEntry)) delete(key);
            delete(withProgids, appId + ".renglt");
            delete("HKCU\\Software\\Classes\\.renglt");
            delete("HKCU\\Software\\Classes\\SystemFileAssociations\\.renglt");
        }
    }

    private static int run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException(command[0] + " did not finish");
        }
        return process.exitValue();
    }

    private static String query(String key) throws Exception {
        Process process = new ProcessBuilder("reg", "query", key).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        return out;
    }

    private static boolean exists(String key) throws Exception {
        Process process = new ProcessBuilder("reg", "query", key).redirectErrorStream(true).start();
        process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        return process.waitFor() == 0;
    }

    private static void delete(String key) {
        delete(key, null);
    }

    private static void delete(String key, String value) {
        try {
            var command = value == null ? List.of("reg", "delete", key, "/f") : List.of("reg", "delete", key, "/v", value, "/f");
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            process.waitFor();
        } catch (Exception ignored) {
            // cleanup only
        }
    }

    private static AppSpec app(String identifier, List<AppSpec.ShellVerb> verbs, List<AppSpec.FileAssociation> types, String documentTypes) {
        return new AppSpec("OpenZip", "1.0.0", "Acme", "Opens zips", identifier, Path.of("."), "app.jar", "Main", List.of(), Map.of(), types, List.of(), verbs, documentTypes);
    }
}
