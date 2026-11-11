package me.piitex.engine.physx;

import me.piitex.engine.render3d.physics.PhysicsWorld;
import physx.PxTopLevelFunctions;
import physx.common.PxDefaultAllocator;
import physx.common.PxDefaultCpuDispatcher;
import physx.common.PxErrorCallbackImpl;
import physx.common.PxErrorCodeEnum;
import physx.common.PxFoundation;
import physx.common.PxTolerancesScale;
import physx.cooking.PxCookingParams;
import physx.physics.PxPhysics;

/**
 * NVIDIA PhysX for the 3D layer's physics. {@link #install()} makes every {@link PhysicsWorld} created afterwards step
 * with PhysX in place of the built-in solver, with nothing else in the game changing:
 * <pre>
 * if (PhysX.isAvailable()) PhysX.install();
 * </pre>
 * or give one world a {@link PhysXBackend} with {@link PhysicsWorld#setBackend}.
 * <p>
 * PhysX is a native library. The program has to have the {@code physx-jni} natives for its platform on the class path
 * (Windows, Linux and macOS on x86-64, and macOS on Apple silicon); {@link #isAvailable()} says whether it loaded.
 * The library is started once, on first use, and stays for the life of the program.
 */
public final class PhysX {
    private static PxFoundation foundation;
    private static PxPhysics physics;
    private static PxDefaultCpuDispatcher dispatcher;
    private static PxCookingParams cooking;
    private static PxTolerancesScale scale;
    private static PxErrorCallbackImpl errors;
    private static Throwable failure;
    private static int threads = Math.clamp(Runtime.getRuntime().availableProcessors() - 1, 0, 8);
    private static boolean quiet;

    private PhysX() {
    }

    /**
     * Whether PhysX loaded on this machine. The first call starts it.
     */
    public static synchronized boolean isAvailable() {
        try {
            start();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /**
     * Why {@link #isAvailable()} is false, or null: most often the natives for this platform aren't on the class path.
     */
    public static synchronized Throwable getFailure() {
        return failure;
    }

    /**
     * Makes PhysX the solver of every {@link PhysicsWorld} created from now on, the worlds of loaded scenes included.
     *
     * @throws IllegalStateException if PhysX can't be loaded
     */
    public static void install() {
        start();
        PhysicsWorld.setDefaultBackend(PhysXBackend::new);
    }

    /**
     * Goes back to the built-in solver for worlds created from now on.
     */
    public static void uninstall() {
        PhysicsWorld.setDefaultBackend(null);
    }

    /**
     * How many worker threads PhysX steps with, shared by every world (one less than the cores, up to 8, by default; 0
     * runs everything on the thread that steps the world). Set it before the first world is made.
     */
    public static synchronized void setThreads(int count) {
        if (physics != null) throw new IllegalStateException("PhysX has started already; set the threads before the first world");
        threads = Math.max(0, count);
    }

    /**
     * Whether PhysX's own warnings are kept off the error stream (they are printed by default).
     */
    public static void setQuiet(boolean quiet) {
        PhysX.quiet = quiet;
    }

    /**
     * The PhysX version, such as "5.6.1".
     */
    public static String version() {
        start();
        int v = PxTopLevelFunctions.getPHYSICS_VERSION();
        return (v >>> 24) + "." + ((v >>> 16) & 0xff) + "." + ((v >>> 8) & 0xff);
    }

    static synchronized PxPhysics physics() {
        start();
        return physics;
    }

    static synchronized PxDefaultCpuDispatcher dispatcher() {
        start();
        return dispatcher;
    }

    static synchronized PxCookingParams cooking() {
        start();
        return cooking;
    }

    static synchronized PxTolerancesScale scale() {
        start();
        return scale;
    }

    private static synchronized void start() {
        if (physics != null) return;
        if (failure != null) throw new IllegalStateException("PhysX is not available: " + failure, failure);
        try {
            int version = PxTopLevelFunctions.getPHYSICS_VERSION();
            errors = new PxErrorCallbackImpl() {
                @Override
                public void reportError(PxErrorCodeEnum code, String message, String file, int line) {
                    if (quiet || code == PxErrorCodeEnum.eDEBUG_INFO) return;
                    System.err.println("[PhysX] " + code + ": " + message);
                }
            };
            PxFoundation f = PxTopLevelFunctions.CreateFoundation(version, new PxDefaultAllocator(), errors);
            scale = new PxTolerancesScale();
            PxPhysics p = PxTopLevelFunctions.CreatePhysics(version, f, scale);
            PxTopLevelFunctions.InitExtensions(p);
            dispatcher = PxTopLevelFunctions.DefaultCpuDispatcherCreate(threads);
            cooking = new PxCookingParams(scale);
            foundation = f;
            physics = p;
        } catch (Throwable t) { // an UnsatisfiedLinkError when the natives are missing, among others
            failure = t;
            throw new IllegalStateException("PhysX is not available: " + t, t);
        }
    }
}
