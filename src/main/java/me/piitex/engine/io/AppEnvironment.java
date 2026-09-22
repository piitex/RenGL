package me.piitex.engine.io;

import me.piitex.engine.config.Config;
import me.piitex.engine.utils.FileUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Decides <i>where things live</i> so the rest of the program never has to know whether it is running
 * from the IDE or from an installed build. No launch flags: the mode is detected from how the code was
 * loaded (a jar or jpackage launcher means {@link Mode#RELEASE}, a folder of classes means {@link Mode#DEV}).
 * <p>
 * Two roots, with the same meaning in both modes:
 * <ul>
 *   <li><b>Data directory</b> - writable, per-user: configs, saves, downloaded files.</li>
 *   <li><b>Asset directory</b> - shipped with the app and treated as read-only: icons, default configs.</li>
 * </ul>
 * <pre>
 *                 DEV                              RELEASE
 * data     &lt;working dir&gt;/run                     per-user app data / appName   (see FileUtil.getAppData)
 * assets   &lt;working dir&gt;/src/main/resources     the folder holding the jar (jpackage: the "app" folder)
 * </pre>
 * Dev data lands in {@code run/} inside the project, so testing never touches your real app data (add
 * {@code run/} to .gitignore). Override the detected mode with {@code -Drengl.mode=dev} or {@code =release},
 * or move either root with the {@link Builder}, e.g. {@code builder("app").devDirectory(Path.of("app")).build()}.
 * <pre>{@code
 * AppEnvironment env = AppEnvironment.create("chat-app");
 * Path logo   = env.getAssetPath("logo.png");
 * Path chars  = env.getDirectory("characters");       // created if missing
 * Config cfg  = env.loadOrCreateConfig("settings.cfg", c -> c.setDefault("engine-vsync", true));
 * env.saveConfig("settings.cfg", cfg);                // always writes to the data directory
 * }</pre>
 */
public final class AppEnvironment {
    private static final Logger log = LogManager.getLogger("Engine");

    public enum Mode {DEV, RELEASE}

    private final String name;
    private final Mode mode;
    private final Path dataDirectory;
    private final Path assetDirectory;
    private final AssetManager assets;

    private AppEnvironment(String name, Mode mode, Path dataDirectory, Path assetDirectory) throws IOException {
        this.name = name;
        this.mode = mode;
        this.dataDirectory = Files.createDirectories(dataDirectory);
        this.assetDirectory = assetDirectory;
        this.assets = new AssetManager(assetDirectory.toString());
        log.info("Environment '{}' running in {} mode (data: {}, assets: {})", name, mode, dataDirectory, assetDirectory);
    }

    /**
     * Detects the mode and uses the default locations.
     */
    public static AppEnvironment create(String appName) throws IOException {
        return builder(appName).build();
    }

    public static Builder builder(String appName) {
        return new Builder(appName);
    }

    /**
     * Detects the mode: the {@code rengl.mode} system property wins, otherwise jar/launcher means release.
     */
    public static Mode detectMode() {
        String forced = System.getProperty("rengl.mode");
        if (forced != null) {
            try {
                return Mode.valueOf(forced.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                log.warn("Ignoring unknown rengl.mode '{}' (use dev or release)", forced);
            }
        }
        if (System.getProperty("jpackage.app-path") != null) return Mode.RELEASE;
        try {
            CodeSource source = AppEnvironment.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null
                    && Files.isRegularFile(Path.of(source.getLocation().toURI()))) {
                return Mode.RELEASE;
            }
        } catch (URISyntaxException | RuntimeException ignored) {
            // treat as dev
        }
        return Mode.DEV;
    }

    public String getName() {
        return name;
    }

    public Mode getMode() {
        return mode;
    }

    public boolean isDev() {
        return mode == Mode.DEV;
    }

    /**
     * The writable per-user root. Always exists.
     */
    public Path getDataDirectory() {
        return dataDirectory;
    }

    /**
     * The read-only shipped-files root. May not exist if the app ships no loose assets.
     */
    public Path getAssetDirectory() {
        return assetDirectory;
    }

    /**
     * A subfolder of the data directory, created if missing.
     */
    public Path getDirectory(String first, String... more) throws IOException {
        return Files.createDirectories(dataDirectory.resolve(Path.of(first, more)).normalize());
    }

    /**
     * A path inside the data directory. Nothing is created.
     */
    public Path getDataPath(String relative) {
        return inside(dataDirectory, relative);
    }

    /**
     * A path inside the asset directory (e.g. {@code "logo.png"}). Nothing is checked or created.
     */
    public Path getAssetPath(String relative) {
        return inside(assetDirectory, relative);
    }

    /**
     * An {@link AssetManager} over the asset directory, for pack (.pak) and buffer loading.
     */
    public AssetManager getAssets() {
        return assets;
    }

    /**
     * Loads a config: the user's saved copy in the data directory if there is one, otherwise the shipped
     * default from the assets (loose or packed), otherwise an empty config.
     */
    public Config loadConfig(String relative) throws IOException {
        Path saved = getDataPath(relative);
        Config config = Files.exists(saved) ? Config.load(saved) : assets.loadConfigOrEmpty(relative);
        return config.setSourcePath(saved); // even a shipped default saves to the data directory
    }

    /**
     * True if the user already has a saved copy of this config. False on a first run, even if a shipped default exists.
     */
    public boolean hasSavedConfig(String relative) {
        return Files.exists(getDataPath(relative));
    }

    /**
     * First-run helper: loads the config (saved copy, else shipped default, else empty), lets {@code defaults}
     * fill in anything missing, and saves it to the data directory if there was no saved copy or the defaults
     * added something. New settings you add in a later version therefore appear in existing users' files too.
     */
    public Config loadOrCreateConfig(String relative, Consumer<Config> defaults) throws IOException {
        boolean existed = hasSavedConfig(relative);
        Config config = loadConfig(relative);
        String before = config.toString();
        defaults.accept(config);
        if (!existed || !before.equals(config.toString())) saveConfig(relative, config);
        return config;
    }

    /**
     * Saves a config into the data directory, never into the (possibly read-only) install location. A config
     * from {@link #loadConfig} already knows this path, so {@link Config#save()} does the same thing.
     */
    public void saveConfig(String relative, Config config) throws IOException {
        config.save(getDataPath(relative));
    }

    private static Path inside(Path base, String relative) {
        Path resolved = base.resolve(relative.startsWith("/") || relative.startsWith("\\") ? relative.substring(1) : relative).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("Path escapes its directory: " + relative);
        }
        return resolved;
    }

    public static final class Builder {
        private final String name;
        private Mode mode;
        private Path dataDirectory;
        private Path assetDirectory;
        private Path devDataDirectory;
        private Path devAssetDirectory;

        private Builder(String name) {
            if (name == null || name.isBlank() || name.contains("/") || name.contains("\\") || name.equals("..")) {
                throw new IllegalArgumentException("Invalid application name: '" + name + "'");
            }
            this.name = name;
        }

        /**
         * Forces a mode instead of detecting it.
         */
        public Builder mode(Mode mode) {
            this.mode = mode;
            return this;
        }

        /**
         * Overrides the data directory in <i>every</i> mode. To change only DEV, use {@link #devDataDirectory}.
         */
        public Builder dataDirectory(Path dir) {
            this.dataDirectory = dir.toAbsolutePath().normalize();
            return this;
        }

        /**
         * Overrides the asset directory in <i>every</i> mode. To change only DEV, use {@link #devAssetDirectory}.
         */
        public Builder assetDirectory(Path dir) {
            this.assetDirectory = dir.toAbsolutePath().normalize();
            return this;
        }

        /**
         * Uses one folder for both roots, in DEV mode only. Relative paths resolve against the working
         * directory, so {@code devDirectory(Path.of("app"))} means {@code <project>/app}.
         */
        public Builder devDirectory(Path dir) {
            return devDataDirectory(dir).devAssetDirectory(dir);
        }

        /**
         * Sets the data directory in DEV mode only. Release keeps its default.
         */
        public Builder devDataDirectory(Path dir) {
            this.devDataDirectory = dir.toAbsolutePath().normalize();
            return this;
        }

        /**
         * Sets the asset directory in DEV mode only. Release keeps its default.
         */
        public Builder devAssetDirectory(Path dir) {
            this.devAssetDirectory = dir.toAbsolutePath().normalize();
            return this;
        }

        public AppEnvironment build() throws IOException {
            Mode m = mode != null ? mode : detectMode();
            Path data = dataDirectory;
            Path assets = assetDirectory;
            if (data == null && m == Mode.DEV) data = devDataDirectory;
            if (assets == null && m == Mode.DEV) assets = devAssetDirectory;
            if (data == null) {
                data = m == Mode.DEV ? FileUtil.getWorkingDirectory().resolve("run") : FileUtil.getAppData().resolve(name);
            }
            if (assets == null) {
                assets = m == Mode.DEV
                        ? FileUtil.getWorkingDirectory().resolve("src").resolve("main").resolve("resources")
                        : FileUtil.getApplicationDirectory();
            }
            return new AppEnvironment(name, m, data, assets);
        }
    }
}
