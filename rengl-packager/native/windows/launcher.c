/*
 * RenGL Windows launcher: the MyApp.exe of an app-image.
 *
 * It does what jpackage's launcher does and nothing more: find runtime\bin\server\jvm.dll next to itself, start the
 * JVM in-process through JNI and call the main class. The same exe works under any name; it reads app\<its own name>.cfg
 *
 *     main-class=com.example.Main
 *     cp=myapp.jar                (repeat; relative to the app folder)
 *     java-option=-Xmx512m        (repeat; $APPDIR and $ROOTDIR are expanded)
 *
 * Build with build-launcher.cmd. The output is checked in under src/main/resources, so using the packager needs no compiler.
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shellapi.h>
#include <stdio.h>
#include <stdlib.h>
#include <wchar.h>
#include <jni.h>

#define BIG 32768
#define MAX_OPTIONS 128
#define MAX_CLASSPATH 512

typedef jint (JNICALL *CreateJavaVMFn)(JavaVM **, void **, void *);

static wchar_t exePath[BIG];
static wchar_t rootDir[BIG];
static wchar_t baseName[MAX_PATH];
static wchar_t mainClass[1024];
static wchar_t *classPathEntries[MAX_CLASSPATH];
static int classPathCount;
static wchar_t *javaOptions[MAX_OPTIONS];
static int javaOptionCount;
static int exitCode = 1;

static void fail(const wchar_t *message, const wchar_t *detail) {
    wchar_t text[BIG];
    swprintf(text, BIG, L"%ls\n\n%ls", message, detail ? detail : L"");
    MessageBoxW(NULL, text, baseName[0] ? baseName : L"Application", MB_OK | MB_ICONERROR);
}

/* A GUI-subsystem exe has no console. When started from one, attach to it so System.out and System.err are visible. */
static void attachParentConsole(void) {
    HANDLE out = GetStdHandle(STD_OUTPUT_HANDLE);
    if (out != NULL && out != INVALID_HANDLE_VALUE) return; /* redirected to a file or pipe already */
    if (!AttachConsole(ATTACH_PARENT_PROCESS)) return;
    HANDLE con = CreateFileW(L"CONOUT$", GENERIC_WRITE, FILE_SHARE_WRITE, NULL, OPEN_EXISTING, 0, NULL);
    if (con != INVALID_HANDLE_VALUE) {
        SetStdHandle(STD_OUTPUT_HANDLE, con);
        SetStdHandle(STD_ERROR_HANDLE, con);
    }
}

static wchar_t *replaceAll(const wchar_t *src, const wchar_t *needle, const wchar_t *with) {
    size_t n = wcslen(needle), w = wcslen(with), count = 0;
    for (const wchar_t *p = src; (p = wcsstr(p, needle)) != NULL; p += n) count++;
    wchar_t *out = malloc((wcslen(src) + count * (w > n ? w - n : 0) + 1) * sizeof(wchar_t));
    wchar_t *o = out;
    while (*src) {
        if (wcsncmp(src, needle, n) == 0) {
            wcscpy(o, with);
            o += w;
            src += n;
        } else {
            *o++ = *src++;
        }
    }
    *o = 0;
    return out;
}

static int readConfig(void) {
    wchar_t cfg[BIG];
    swprintf(cfg, BIG, L"%ls\\app\\%ls.cfg", rootDir, baseName);
    HANDLE f = CreateFileW(cfg, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, 0, NULL);
    if (f == INVALID_HANDLE_VALUE) {
        fail(L"The application's configuration file is missing:", cfg);
        return 0;
    }
    DWORD size = GetFileSize(f, NULL), read = 0;
    char *bytes = malloc(size + 1);
    ReadFile(f, bytes, size, &read, NULL);
    CloseHandle(f);
    bytes[read] = 0;
    char *text = bytes;
    if (read >= 3 && (unsigned char) text[0] == 0xEF && (unsigned char) text[1] == 0xBB && (unsigned char) text[2] == 0xBF) text += 3;

    wchar_t appDir[BIG];
    swprintf(appDir, BIG, L"%ls\\app", rootDir);

    for (char *line = strtok(text, "\r\n"); line != NULL; line = strtok(NULL, "\r\n")) {
        if (line[0] == '#' || line[0] == 0) continue;
        char *eq = strchr(line, '=');
        if (!eq) continue;
        *eq = 0;
        int len = MultiByteToWideChar(CP_UTF8, 0, eq + 1, -1, NULL, 0);
        wchar_t *value = malloc(len * sizeof(wchar_t));
        MultiByteToWideChar(CP_UTF8, 0, eq + 1, -1, value, len);

        if (strcmp(line, "main-class") == 0) {
            wcsncpy(mainClass, value, 1023);
        } else if (strcmp(line, "cp") == 0 && classPathCount < MAX_CLASSPATH) {
            classPathEntries[classPathCount++] = wcsdup(value);
        } else if (strcmp(line, "java-option") == 0 && javaOptionCount < MAX_OPTIONS) {
            wchar_t *a = replaceAll(value, L"$APPDIR", appDir);
            javaOptions[javaOptionCount++] = replaceAll(a, L"$ROOTDIR", rootDir);
            free(a);
        }
        free(value);
    }
    free(bytes);
    if (!mainClass[0]) {
        fail(L"The configuration file has no main-class:", cfg);
        return 0;
    }
    return 1;
}

static char *toAnsi(const wchar_t *w) {
    int len = WideCharToMultiByte(CP_ACP, 0, w, -1, NULL, 0, NULL, NULL);
    char *s = malloc(len);
    WideCharToMultiByte(CP_ACP, 0, w, -1, s, len, NULL, NULL);
    return s;
}

static int fitsAnsi(const wchar_t *w) {
    BOOL lossy = FALSE;
    char buffer[BIG];
    WideCharToMultiByte(CP_ACP, 0, w, -1, buffer, BIG, "?", &lossy);
    return !lossy;
}

static jstring newString(JNIEnv *env, const wchar_t *w) {
    return (*env)->NewString(env, (const jchar *) w, (jsize) wcslen(w));
}

static DWORD WINAPI launchJvm(LPVOID unused) {
    (void) unused;
    wchar_t jvmDll[BIG], binDir[BIG], originalDir[BIG];
    swprintf(binDir, BIG, L"%ls\\runtime\\bin", rootDir);
    swprintf(jvmDll, BIG, L"%ls\\runtime\\bin\\server\\jvm.dll", rootDir);

    SetDllDirectoryW(binDir);
    HMODULE jvm = LoadLibraryW(jvmDll);
    if (!jvm) {
        fail(L"The bundled Java runtime could not be loaded:", jvmDll);
        return 1;
    }
    CreateJavaVMFn create = (CreateJavaVMFn) GetProcAddress(jvm, "JNI_CreateJavaVM");
    if (!create) {
        fail(L"The bundled Java runtime is not a JVM:", jvmDll);
        return 1;
    }

    /* The JVM reads option strings in the ANSI code page. When the install path fits it, the class path is absolute
       and the working directory is left alone. When it does not (a folder named in another script), the class path is
       relative and the VM starts from the install folder, which turns each entry into an absolute path while it boots.
       The cost is that user.dir is then the install folder rather than where the app was started. */
    int relative = !fitsAnsi(rootDir);
    GetCurrentDirectoryW(BIG, originalDir);
    if (relative) SetCurrentDirectoryW(rootDir);

    wchar_t *classPath = malloc(BIG * sizeof(wchar_t));
    classPath[0] = 0;
    for (int i = 0; i < classPathCount; i++) {
        if (i) wcscat(classPath, L";");
        if (!relative) {
            wcscat(classPath, rootDir);
            wcscat(classPath, L"\\");
        }
        wcscat(classPath, L"app\\");
        wcscat(classPath, classPathEntries[i]);
    }

    JavaVMOption options[MAX_OPTIONS + 2];
    int n = 0;
    char *cp = toAnsi(classPath);
    size_t cpLen = strlen(cp) + 32;
    char *cpOption = malloc(cpLen);
    snprintf(cpOption, cpLen, "-Djava.class.path=%s", cp);
    options[n++].optionString = cpOption;
    for (int i = 0; i < javaOptionCount; i++) options[n++].optionString = toAnsi(javaOptions[i]);

    JavaVMInitArgs vmArgs = {0};
    vmArgs.version = JNI_VERSION_10;
    vmArgs.nOptions = n;
    vmArgs.options = options;
    vmArgs.ignoreUnrecognized = JNI_FALSE;

    JavaVM *vm = NULL;
    JNIEnv *env = NULL;
    jint result = create(&vm, (void **) &env, &vmArgs);
    if (relative) SetCurrentDirectoryW(originalDir);
    if (result != JNI_OK) {
        wchar_t code[64];
        swprintf(code, 64, L"JNI_CreateJavaVM returned %d", (int) result);
        fail(L"The Java virtual machine could not be started.", code);
        return 1;
    }

    /* Where the app was started from, in full Unicode: AppEnvironment reads this to know it is an installed build. */
    jclass system = (*env)->FindClass(env, "java/lang/System");
    jmethodID setProperty = (*env)->GetStaticMethodID(env, system, "setProperty", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    (*env)->CallStaticObjectMethod(env, system, setProperty, newString(env, L"jpackage.app-path"), newString(env, exePath));

    char *className = toAnsi(mainClass);
    for (char *c = className; *c; c++) if (*c == '.') *c = '/';
    jclass main = (*env)->FindClass(env, className);
    if (!main) {
        (*env)->ExceptionDescribe(env);
        fail(L"The main class could not be found:", mainClass);
        goto done;
    }
    /* The entry points java.exe accepts since Java 25: main(String[]) or main(), static or on an instance. */
    jmethodID withArgs = (*env)->GetStaticMethodID(env, main, "main", "([Ljava/lang/String;)V");
    if (!withArgs) (*env)->ExceptionClear(env);
    jmethodID noArgs = withArgs ? NULL : (*env)->GetStaticMethodID(env, main, "main", "()V");
    if (!withArgs && !noArgs) (*env)->ExceptionClear(env);
    jmethodID instanceWithArgs = NULL, instanceNoArgs = NULL, constructor = NULL;
    if (!withArgs && !noArgs) {
        constructor = (*env)->GetMethodID(env, main, "<init>", "()V");
        if (!constructor) (*env)->ExceptionClear(env);
        instanceWithArgs = constructor ? (*env)->GetMethodID(env, main, "main", "([Ljava/lang/String;)V") : NULL;
        if (constructor && !instanceWithArgs) (*env)->ExceptionClear(env);
        instanceNoArgs = constructor && !instanceWithArgs ? (*env)->GetMethodID(env, main, "main", "()V") : NULL;
        if (constructor && !instanceWithArgs && !instanceNoArgs) (*env)->ExceptionClear(env);
    }
    if (!withArgs && !noArgs && !instanceWithArgs && !instanceNoArgs) {
        fail(L"The main class has no main method:", mainClass);
        goto done;
    }

    int argc = 0;
    wchar_t **argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    jobjectArray args = (*env)->NewObjectArray(env, argc > 1 ? argc - 1 : 0, stringClass, NULL);
    for (int i = 1; i < argc; i++) (*env)->SetObjectArrayElement(env, args, i - 1, newString(env, argv[i]));

    if (withArgs) {
        (*env)->CallStaticVoidMethod(env, main, withArgs, args);
    } else if (noArgs) {
        (*env)->CallStaticVoidMethod(env, main, noArgs);
    } else {
        jobject instance = (*env)->NewObject(env, main, constructor);
        if (instance && instanceWithArgs) (*env)->CallVoidMethod(env, instance, instanceWithArgs, args);
        else if (instance) (*env)->CallVoidMethod(env, instance, instanceNoArgs);
    }
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
    } else {
        exitCode = 0;
    }

done:
    /* Waits for the last non-daemon thread, like java.exe. */
    (*vm)->DetachCurrentThread(vm);
    (*vm)->DestroyJavaVM(vm);
    return 0;
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR commandLine, int show) {
    (void) instance; (void) previous; (void) commandLine; (void) show;
    attachParentConsole();

    GetModuleFileNameW(NULL, exePath, BIG);
    wcscpy(rootDir, exePath);
    wchar_t *slash = wcsrchr(rootDir, L'\\');
    if (!slash) return 1;
    wcscpy(baseName, slash + 1);
    *slash = 0;
    wchar_t *dot = wcsrchr(baseName, L'.');
    if (dot) *dot = 0;

    if (!readConfig()) return 1;

    /* The JVM runs on its own thread, as in java.exe, so it does not depend on this thread's stack. */
    HANDLE thread = CreateThread(NULL, 0, launchJvm, NULL, 0, NULL);
    if (!thread) return 1;
    WaitForSingleObject(thread, INFINITE);
    return exitCode;
}
