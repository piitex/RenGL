/*
 * RenGL Windows setup: the MyApp-1.0.0-windows-x64-setup.exe the packager makes with --installer.
 *
 * The packager appends a zip of the app-image to a copy of this program, so one file is both the installer and its
 * payload. The zip has a "setup.ini" first (name, version, vendor, description, id, exe) and then the files to install,
 * named relative to the install folder. Run as it is, it shows a wizard (folder, desktop shortcut, Start menu shortcut,
 * launch). Run with switches it installs without any window:
 *
 *     setup.exe /S [/DIR=C:\Some\Folder] [/DESKTOP=0|1] [/STARTMENU=0|1] [/ALLUSERS=0|1] [/LAUNCH] [/PROGRESS]
 *
 * /PROGRESS prints "progress=<percent>" lines, and "error=<text>" if it fails, on stdout.
 *
 * When setup.ini names a ui=<launcher> and the zip holds a ui/ folder, the wizard is replaced by that RenGL app (see
 * "the RenGL setup program" below). /NOUI forces the wizard anyway. The ui/ files are never installed.
 *
 * It installs for the current user (no administrator rights): by default into %LOCALAPPDATA%\Programs\<name>. With /ALLUSERS=1
 * it installs for everyone instead: into %ProgramFiles%\<name>, with the shortcuts in the shared folders and the Apps &
 * features entry in HKEY_LOCAL_MACHINE. That needs administrator rights, so it starts a second copy of itself with the
 * "runas" verb (Windows asks the person to confirm), which does the work and passes the progress back through a file. The
 * uninstaller does the same. It writes
 * uninstall.exe (a copy of this program without the payload) and uninstall.dat (every file it wrote) beside the app, and
 * an Apps & features entry. uninstall.exe removes exactly the files that list holds, so a folder chosen by mistake
 * (Documents, say) only ever loses what the installer put there.
 *
 * Build with build-setup.cmd. The output is checked in under src/main/resources, so using the packager needs no compiler.
 */
#define WIN32_LEAN_AND_MEAN
#define COBJMACROS
#include <windows.h>
#include <shellapi.h>
#include <shlobj.h>
#include <shobjidl.h>
#include <knownfolders.h>
#include <commctrl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

#define PATHLEN 2048
#define UNINSTALL_KEY L"Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\"

/* ---- application data ---------------------------------------------------------------------------------------------- */

static wchar_t appName[256] = L"App";
static wchar_t appVersion[64];
static wchar_t appVendor[256];
static wchar_t appDesc[512];
static wchar_t appId[256] = L"app";
static wchar_t appExe[MAX_PATH] = L"App.exe";
static wchar_t appUi[MAX_PATH];  /* the launcher of the RenGL setup program under ui/, when the payload carries one */
static int uiShare;              /* ui-share=1: that program runs on the app's own runtime/ and app/*.jar, which are unpacked beside it */
static int reportProgress;       /* /PROGRESS: say how the install is going on stdout, for the setup program that started us */
static wchar_t progressFile[PATHLEN]; /* /PROGRESSFILE=: say it in this file instead, for the elevated copy of this program */
static int noElevate;            /* /NOELEVATE: start the elevated copy as the current user. Only for testing the hand-over without a prompt */

static wchar_t selfPath[PATHLEN];
static const uint8_t *image;   /* this exe, mapped */
static size_t imageSize;

typedef struct {
    wchar_t *name;
    uint32_t method, crc, csize, usize, local;
} Entry;

static Entry *entries;
static int entryCount;
static size_t zipStart;        /* where the payload begins: the size of this program without it */

/* ---- small helpers ------------------------------------------------------------------------------------------------- */

static uint32_t rd16(const uint8_t *p) { return p[0] | (p[1] << 8); }
static uint32_t rd32(const uint8_t *p) { return p[0] | (p[1] << 8) | (p[2] << 16) | ((uint32_t) p[3] << 24); }

static wchar_t *widen(const char *s, int len) {
    int n = MultiByteToWideChar(CP_UTF8, 0, s, len, NULL, 0);
    wchar_t *w = malloc((n + 1) * sizeof(wchar_t));
    MultiByteToWideChar(CP_UTF8, 0, s, len, w, n);
    w[n] = 0;
    return w;
}

typedef struct { char *p; size_t n, cap; } Sb;

static void sbAdd(Sb *s, const char *t) {
    size_t l = strlen(t);
    if (s->n + l + 1 > s->cap) {
        s->cap = (s->n + l + 1) * 2;
        s->p = realloc(s->p, s->cap);
    }
    memcpy(s->p + s->n, t, l + 1);
    s->n += l;
}

static void sbAddW(Sb *s, const wchar_t *w) {
    int n = WideCharToMultiByte(CP_UTF8, 0, w, -1, NULL, 0, NULL, NULL);
    char *t = malloc(n);
    WideCharToMultiByte(CP_UTF8, 0, w, -1, t, n, NULL, NULL);
    sbAdd(s, t);
    free(t);
}

/* The value of "key=" in UTF-8 text of key=value lines, or an empty string. */
static void iniValue(const char *text, size_t len, const char *key, wchar_t *out, int cap) {
    size_t kl = strlen(key);
    out[0] = 0;
    for (size_t i = 0; i < len;) {
        size_t e = i;
        while (e < len && text[e] != '\n') e++;
        size_t le = e;
        if (le > i && text[le - 1] == '\r') le--;
        if (le - i > kl && memcmp(text + i, key, kl) == 0 && text[i + kl] == '=') {
            wchar_t *w = widen(text + i + kl + 1, (int) (le - i - kl - 1));
            wcsncpy_s(out, cap, w, _TRUNCATE);
            free(w);
            return;
        }
        i = e + 1;
    }
}

static void joinPath(wchar_t *out, const wchar_t *dir, const wchar_t *rel) {
    swprintf(out, PATHLEN, L"%ls\\%ls", dir, rel);
    for (wchar_t *p = out; *p; p++) if (*p == L'/') *p = L'\\';
}

static int safeName(const wchar_t *n) {
    if (!n[0] || n[0] == L'/' || n[0] == L'\\' || wcschr(n, L':')) return 0;
    for (const wchar_t *p = n; *p;) {
        const wchar_t *e = p;
        while (*e && *e != L'/' && *e != L'\\') e++;
        if (e - p == 2 && p[0] == L'.' && p[1] == L'.') return 0;
        p = *e ? e + 1 : e;
    }
    return 1;
}

static int makeDirs(const wchar_t *dir) {
    int r = SHCreateDirectoryExW(NULL, dir, NULL);
    return r == ERROR_SUCCESS || r == ERROR_ALREADY_EXISTS || r == ERROR_FILE_EXISTS;
}

static int fileExists(const wchar_t *path) {
    return GetFileAttributesW(path) != INVALID_FILE_ATTRIBUTES;
}

static void knownFolder(REFKNOWNFOLDERID id, wchar_t *out) {
    PWSTR p = NULL;
    out[0] = 0;
    if (SUCCEEDED(SHGetKnownFolderPath(id, 0, NULL, &p)) && p) wcsncpy_s(out, PATHLEN, p, _TRUNCATE);
    CoTaskMemFree(p);
}

static void message(HWND owner, const wchar_t *text, UINT flags) {
    MessageBoxW(owner, text, appName, MB_OK | flags);
}

/* Reads a whole file. The caller frees it. */
static uint8_t *readFile(const wchar_t *path, size_t *size) {
    HANDLE f = CreateFileW(path, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, 0, NULL);
    if (f == INVALID_HANDLE_VALUE) return NULL;
    LARGE_INTEGER sz;
    GetFileSizeEx(f, &sz);
    uint8_t *data = malloc((size_t) sz.QuadPart + 1);
    DWORD got = 0;
    if (!ReadFile(f, data, (DWORD) sz.QuadPart, &got, NULL)) got = 0;
    CloseHandle(f);
    data[got] = 0;
    *size = got;
    return data;
}

/* ---- inflate ------------------------------------------------------------------------------------------------------- */
/* Raw deflate, with the code tables as direct lookups so that a few hundred megabytes unpack in seconds. */

typedef struct { uint16_t t[1 << 15]; int bits; } Huff;
typedef struct { const uint8_t *p, *end; uint64_t buf; int cnt, pad; } Bits;

static Huff *huff;

static void refill(Bits *b) {
    while (b->cnt <= 56) {
        uint64_t v = 0;
        if (b->p < b->end) v = *b->p++; else b->pad++;
        b->buf |= v << b->cnt;
        b->cnt += 8;
    }
}

static uint32_t take(Bits *b, int n) {
    uint32_t v = (uint32_t) (b->buf & ((1ull << n) - 1));
    b->buf >>= n;
    b->cnt -= n;
    return v;
}

/* Reading past the end of the input hands out zeros; this says whether any of them were used. */
static int overrun(const Bits *b) { return b->pad * 8 > b->cnt; }

static int huffBuild(Huff *h, const uint8_t *lens, int n) {
    int count[16] = {0}, next[16], maxlen = 0, left = 1, code = 0;
    for (int i = 0; i < n; i++) count[lens[i]]++;
    count[0] = 0;
    for (int l = 1; l <= 15; l++) {
        if (count[l]) maxlen = l;
        left = (left << 1) - count[l];
        if (left < 0) return -1;
    }
    if (maxlen == 0) { h->bits = 1; h->t[0] = h->t[1] = 0; return 0; }
    for (int l = 1; l <= 15; l++) {
        code = (code + count[l - 1]) << 1;
        next[l] = code;
    }
    h->bits = maxlen;
    memset(h->t, 0, sizeof(uint16_t) << maxlen);
    for (int s = 0; s < n; s++) {
        int l = lens[s], c, r = 0;
        if (!l) continue;
        c = next[l]++;
        for (int i = 0; i < l; i++) { r = (r << 1) | (c & 1); c >>= 1; }
        for (int k = r; k < (1 << maxlen); k += 1 << l) h->t[k] = (uint16_t) ((s << 4) | l);
    }
    return 0;
}

static const uint16_t LBASE[29] = {3,4,5,6,7,8,9,10,11,13,15,17,19,23,27,31,35,43,51,59,67,83,99,115,131,163,195,227,258};
static const uint8_t LEXT[29] = {0,0,0,0,0,0,0,0,1,1,1,1,2,2,2,2,3,3,3,3,4,4,4,4,5,5,5,5,0};
static const uint16_t DBASE[30] = {1,2,3,4,5,7,9,13,17,25,33,49,65,97,129,193,257,385,513,769,1025,1537,2049,3073,4097,6145,8193,12289,16385,24577};
static const uint8_t DEXT[30] = {0,0,0,0,1,1,2,2,3,3,4,4,5,5,6,6,7,7,8,8,9,9,10,10,11,11,12,12,13,13};
static const uint8_t ORDER[19] = {16,17,18,0,8,7,9,6,10,5,11,4,12,3,13,2,14,1,15};

/* Inflates exactly outLen bytes. Returns 0, or -1 for damaged input or a size that does not match. */
static int inflateRaw(const uint8_t *in, size_t inLen, uint8_t *out, size_t outLen) {
    if (!huff) huff = malloc(sizeof(Huff) * 3);
    Huff *lit = &huff[0], *dist = &huff[1], *cl = &huff[2];
    Bits b = { in, in + inLen, 0, 0, 0 };
    uint8_t *o = out, *oend = out + outLen;
    int final;

    do {
        refill(&b);
        final = (int) take(&b, 1);
        int type = (int) take(&b, 2);
        if (type == 0) {
            if (overrun(&b)) return -1;
            take(&b, b.cnt & 7);
            while (b.cnt >= 8) { if (b.pad) b.pad--; else b.p--; b.cnt -= 8; }
            b.buf = 0;
            b.cnt = 0;
            if (b.end - b.p < 4) return -1;
            uint32_t len = rd16(b.p), nlen = rd16(b.p + 2);
            if ((len ^ 0xFFFF) != nlen) return -1;
            b.p += 4;
            if ((size_t) (b.end - b.p) < len || (size_t) (oend - o) < len) return -1;
            memcpy(o, b.p, len);
            o += len;
            b.p += len;
        } else if (type == 1 || type == 2) {
            uint8_t lens[320] = {0};
            if (type == 1) {
                int i = 0;
                for (; i < 144; i++) lens[i] = 8;
                for (; i < 256; i++) lens[i] = 9;
                for (; i < 280; i++) lens[i] = 7;
                for (; i < 288; i++) lens[i] = 8;
                huffBuild(lit, lens, 288);
                memset(lens, 5, 30);
                huffBuild(dist, lens, 30);
            } else {
                int nl = (int) take(&b, 5) + 257, nd = (int) take(&b, 5) + 1, nc = (int) take(&b, 4) + 4;
                uint8_t cll[19] = {0};
                if (nl > 286 || nd > 30) return -1;
                for (int i = 0; i < nc; i++) { refill(&b); cll[ORDER[i]] = (uint8_t) take(&b, 3); }
                if (huffBuild(cl, cll, 19)) return -1;
                for (int idx = 0; idx < nl + nd;) {
                    refill(&b);
                    uint16_t e = cl->t[b.buf & ((1u << cl->bits) - 1)];
                    if (!e) return -1;
                    take(&b, e & 15);
                    int sym = e >> 4, rep, val = 0;
                    if (sym < 16) { lens[idx++] = (uint8_t) sym; continue; }
                    if (sym == 16) { if (!idx) return -1; val = lens[idx - 1]; rep = 3 + (int) take(&b, 2); }
                    else if (sym == 17) rep = 3 + (int) take(&b, 3);
                    else rep = 11 + (int) take(&b, 7);
                    if (idx + rep > nl + nd) return -1;
                    while (rep--) lens[idx++] = (uint8_t) val;
                }
                if (!lens[256]) return -1;
                if (huffBuild(lit, lens, nl) || huffBuild(dist, lens + nl, nd)) return -1;
            }
            uint32_t lmask = (1u << lit->bits) - 1, dmask = (1u << dist->bits) - 1;
            for (;;) {
                refill(&b);
                uint16_t e = lit->t[b.buf & lmask];
                if (!e) return -1;
                take(&b, e & 15);
                int sym = e >> 4;
                if (sym < 256) {
                    if (o == oend) return -1;
                    *o++ = (uint8_t) sym;
                } else if (sym == 256) {
                    break;
                } else {
                    sym -= 257;
                    if (sym >= 29) return -1;
                    size_t len = LBASE[sym] + take(&b, LEXT[sym]);
                    e = dist->t[b.buf & dmask];
                    if (!e) return -1;
                    take(&b, e & 15);
                    int ds = e >> 4;
                    if (ds >= 30) return -1;
                    size_t d = DBASE[ds] + take(&b, DEXT[ds]);
                    if (d > (size_t) (o - out) || len > (size_t) (oend - o)) return -1;
                    const uint8_t *from = o - d;
                    while (len--) *o++ = *from++;
                }
            }
        } else {
            return -1;
        }
        if (overrun(&b)) return -1;
    } while (!final);
    return o == oend ? 0 : -1;
}

static uint32_t crcTable[256];

static void crcInit(void) {
    for (uint32_t i = 0; i < 256; i++) {
        uint32_t c = i;
        for (int k = 0; k < 8; k++) c = (c & 1) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
        crcTable[i] = c;
    }
}

static uint32_t crc32(const uint8_t *p, size_t n) {
    uint32_t c = 0xFFFFFFFFu;
    while (n--) c = crcTable[(c ^ *p++) & 0xFF] ^ (c >> 8);
    return ~c;
}

/* ---- the payload --------------------------------------------------------------------------------------------------- */

static int mapSelf(void) {
    GetModuleFileNameW(NULL, selfPath, PATHLEN);
    HANDLE f = CreateFileW(selfPath, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, 0, NULL);
    if (f == INVALID_HANDLE_VALUE) return 0;
    LARGE_INTEGER sz;
    GetFileSizeEx(f, &sz);
    imageSize = (size_t) sz.QuadPart;
    HANDLE m = CreateFileMappingW(f, NULL, PAGE_READONLY, 0, 0, NULL);
    if (!m) return 0;
    image = MapViewOfFile(m, FILE_MAP_READ, 0, 0, 0);
    return image != NULL;
}

/* Finds the zip at the end of this exe. False when there is none: this is the uninstaller. */
static int readDirectory(void) {
    if (imageSize < 22 + 1024) return 0;
    size_t low = imageSize > 22 + 65535 ? imageSize - 22 - 65535 : 0;
    for (size_t p = imageSize - 22 + 1; p-- > low;) {
        if (rd32(image + p) != 0x06054b50) continue;
        uint32_t count = rd16(image + p + 10), cdSize = rd32(image + p + 12), cdOffset = rd32(image + p + 16);
        if (cdSize > p || p - cdSize < cdOffset) continue;
        size_t cd = p - cdSize;
        size_t start = cd - cdOffset;  /* the payload was appended, so offsets in it are relative to start */
        Entry *list = calloc(count + 1, sizeof(Entry));
        size_t pos = cd;
        int ok = 1;
        for (uint32_t i = 0; i < count && ok; i++) {
            const uint8_t *h = image + pos;
            if (pos + 46 > p || rd32(h) != 0x02014b50) { ok = 0; break; }
            uint32_t nameLen = rd16(h + 28), extraLen = rd16(h + 30), commentLen = rd16(h + 32);
            if (pos + 46 + nameLen > p) { ok = 0; break; }
            list[i].method = rd16(h + 10);
            list[i].crc = rd32(h + 16);
            list[i].csize = rd32(h + 20);
            list[i].usize = rd32(h + 24);
            list[i].local = rd32(h + 42);
            list[i].name = widen((const char *) h + 46, (int) nameLen);
            pos += 46 + nameLen + extraLen + commentLen;
        }
        if (!ok) { free(list); continue; }
        entries = list;
        entryCount = (int) count;
        zipStart = start;
        return 1;
    }
    return 0;
}

static Entry *findEntry(const wchar_t *name) {
    for (int i = 0; i < entryCount; i++) if (wcscmp(entries[i].name, name) == 0) return &entries[i];
    return NULL;
}

/* The compressed bytes of an entry, or NULL if the file is damaged. */
static const uint8_t *entryData(const Entry *e) {
    size_t at = zipStart + e->local;
    if (at + 30 > imageSize || rd32(image + at) != 0x04034b50) return NULL;
    size_t data = at + 30 + rd16(image + at + 26) + rd16(image + at + 28);
    if (data + e->csize > imageSize) return NULL;
    return image + data;
}

static int loadSettings(void) {
    Entry *ini = findEntry(L"setup.ini");
    if (!ini || ini->method != 0) return 0;
    const uint8_t *d = entryData(ini);
    if (!d) return 0;
    iniValue((const char *) d, ini->usize, "name", appName, 256);
    iniValue((const char *) d, ini->usize, "version", appVersion, 64);
    iniValue((const char *) d, ini->usize, "vendor", appVendor, 256);
    iniValue((const char *) d, ini->usize, "description", appDesc, 512);
    iniValue((const char *) d, ini->usize, "id", appId, 256);
    iniValue((const char *) d, ini->usize, "exe", appExe, MAX_PATH);
    iniValue((const char *) d, ini->usize, "ui", appUi, MAX_PATH);
    wchar_t share[8];
    iniValue((const char *) d, ini->usize, "ui-share", share, 8);
    uiShare = wcscmp(share, L"1") == 0;
    return 1;
}

/* Files under ui/ belong to the setup program, not to the app, so they are never installed. */
static int isUiEntry(const wchar_t *name) {
    return wcsncmp(name, L"ui/", 3) == 0;
}

/* One line on stdout, when the caller asked for them with /PROGRESS and gave us a pipe to write to. */
static void report(const char *prefix, const wchar_t *value) {
    if (!reportProgress && !progressFile[0]) return;
    HANDLE out;
    if (progressFile[0]) out = CreateFileW(progressFile, FILE_APPEND_DATA, FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    else out = GetStdHandle(STD_OUTPUT_HANDLE);
    if (!out || out == INVALID_HANDLE_VALUE) return;
    Sb line = {0};
    sbAdd(&line, prefix);
    sbAddW(&line, value);
    for (size_t i = 0; i < line.n; i++) if (line.p[i] == '\r' || line.p[i] == '\n') line.p[i] = ' ';
    sbAdd(&line, "\n");
    DWORD wrote;
    WriteFile(out, line.p, (DWORD) line.n, &wrote, NULL);
    free(line.p);
    if (progressFile[0]) CloseHandle(out);
}

/* True when this process has administrator rights (it was started with "Run as administrator" or confirmed a prompt). */
static int isElevated(void) {
    HANDLE token;
    TOKEN_ELEVATION elevation;
    DWORD size;
    int elevated = 0;
    if (OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token)) {
        if (GetTokenInformation(token, TokenElevation, &elevation, sizeof elevation, &size)) elevated = elevation.TokenIsElevated != 0;
        CloseHandle(token);
    }
    return elevated;
}

static HKEY hive(int allUsers) {
    return allUsers ? HKEY_LOCAL_MACHINE : HKEY_CURRENT_USER;
}

/* ---- registry and shortcuts ---------------------------------------------------------------------------------------- */

static void regString(HKEY key, const wchar_t *name, const wchar_t *value) {
    RegSetValueExW(key, name, 0, REG_SZ, (const BYTE *) value, (DWORD) ((wcslen(value) + 1) * sizeof(wchar_t)));
}

static void regDword(HKEY key, const wchar_t *name, DWORD value) {
    RegSetValueExW(key, name, 0, REG_DWORD, (const BYTE *) &value, sizeof value);
}

static int makeShortcut(const wchar_t *lnk, const wchar_t *target, const wchar_t *workDir, const wchar_t *description) {
    IShellLinkW *link;
    IPersistFile *file;
    if (FAILED(CoCreateInstance(&CLSID_ShellLink, NULL, CLSCTX_INPROC_SERVER, &IID_IShellLinkW, (void **) &link))) return 0;
    IShellLinkW_SetPath(link, target);
    IShellLinkW_SetWorkingDirectory(link, workDir);
    IShellLinkW_SetDescription(link, description);
    IShellLinkW_SetIconLocation(link, target, 0);
    int ok = 0;
    if (SUCCEEDED(IShellLinkW_QueryInterface(link, &IID_IPersistFile, (void **) &file))) {
        ok = SUCCEEDED(IPersistFile_Save(file, lnk, TRUE));
        IPersistFile_Release(file);
    }
    IShellLinkW_Release(link);
    return ok;
}

static void shortcutPath(int desktop, int allUsers, wchar_t *out) {
    wchar_t folder[PATHLEN];
    if (allUsers) knownFolder(desktop ? &FOLDERID_PublicDesktop : &FOLDERID_CommonPrograms, folder);
    else knownFolder(desktop ? &FOLDERID_Desktop : &FOLDERID_Programs, folder);
    out[0] = 0;
    if (folder[0]) swprintf(out, PATHLEN, L"%ls\\%ls.lnk", folder, appName);
}

/* Where to offer to install: where it is installed already, else the usual place for the current user or for everyone. */
static void defaultDir(wchar_t *out, int allUsers) {
    wchar_t key[PATHLEN], old[PATHLEN];
    DWORD size = sizeof old;
    HKEY k;
    swprintf(key, PATHLEN, UNINSTALL_KEY L"%ls", appId);
    if (RegOpenKeyExW(hive(allUsers), key, 0, KEY_READ, &k) == ERROR_SUCCESS) {
        LONG r = RegQueryValueExW(k, L"InstallLocation", NULL, NULL, (BYTE *) old, &size);
        RegCloseKey(k);
        if (r == ERROR_SUCCESS && old[0]) { wcsncpy_s(out, PATHLEN, old, _TRUNCATE); return; }
    }
    wchar_t base[PATHLEN];
    if (allUsers) {
        knownFolder(&FOLDERID_ProgramFiles, base);
        swprintf(out, PATHLEN, L"%ls\\%ls", base, appName);
    } else {
        knownFolder(&FOLDERID_LocalAppData, base);
        swprintf(out, PATHLEN, L"%ls\\Programs\\%ls", base, appName);
    }
}

/* ---- installing ---------------------------------------------------------------------------------------------------- */

#define WM_PROGRESS (WM_APP + 1)
#define WM_FINISHED (WM_APP + 2)

typedef struct {
    wchar_t dir[PATHLEN];
    int desktop, startMenu;
    int allUsers;                /* for everyone: Program Files, shared shortcuts, HKEY_LOCAL_MACHINE; needs administrator rights */
    int elevatedCopy;            /* this is the copy that was started to do the work, so it must not start another */
    HWND window;                 /* gets progress messages; NULL when silent */
    wchar_t error[PATHLEN + 256];
} Job;

static int failJob(Job *j, const wchar_t *what, const wchar_t *path) {
    swprintf(j->error, PATHLEN + 256, L"%ls\n%ls", what, path ? path : L"");
    return 0;
}

/* Reads the whole of a file the elevated copy is still writing. The caller frees it; NULL if it cannot be read. */
static char *readLog(const wchar_t *path, size_t *size) {
    HANDLE f = CreateFileW(path, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_EXISTING, 0, NULL);
    if (f == INVALID_HANDLE_VALUE) return NULL;
    LARGE_INTEGER sz;
    GetFileSizeEx(f, &sz);
    char *data = malloc((size_t) sz.QuadPart + 1);
    DWORD got = 0;
    if (!ReadFile(f, data, (DWORD) sz.QuadPart, &got, NULL)) got = 0;
    CloseHandle(f);
    data[got] = 0;
    *size = got;
    return data;
}

/* Passes on the progress the elevated copy has logged (each percent once) and keeps the error it ended with, if any. */
static void relayLog(Job *j, const char *text, size_t size, int *lastPercent, wchar_t *error) {
    for (size_t i = 0; i < size;) {
        size_t e = i;
        while (e < size && text[e] != '\n') e++;
        if (e == size) break;   /* a line still being written */
        size_t le = e;
        if (le > i && text[le - 1] == '\r') le--;
        if (le - i > 9 && memcmp(text + i, "progress=", 9) == 0) {
            int percent = atoi(text + i + 9);
            if (percent != *lastPercent) {
                wchar_t number[16];
                *lastPercent = percent;
                if (j->window) PostMessageW(j->window, WM_PROGRESS, percent, 0);
                swprintf(number, 16, L"%d", percent);
                report("progress=", number);
            }
        } else if (le - i > 6 && memcmp(text + i, "error=", 6) == 0) {
            wchar_t *w = widen(text + i + 6, (int) (le - i - 6));
            wcsncpy_s(error, PATHLEN, w, _TRUNCATE);
            free(w);
        }
        i = e + 1;
    }
}

/*
 * Starts this program again with administrator rights (Windows asks the person to confirm) to do the install, and passes
 * its progress on. The two share a log file, because a process started that way cannot be given a pipe.
 */
static int installElevated(Job *j) {
    wchar_t temp[PATHLEN], log[PATHLEN], dir[PATHLEN], args[PATHLEN * 2], error[PATHLEN] = L"";
    GetTempPathW(PATHLEN, temp);
    swprintf(log, PATHLEN, L"%lsrengl-install-%lu.log", temp, GetCurrentProcessId());
    HANDLE f = CreateFileW(log, GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (f == INVALID_HANDLE_VALUE) return failJob(j, L"Could not prepare the installation for administrator rights:", log);
    CloseHandle(f);

    /* A folder ending in a backslash (a drive's root) would escape the closing quote. */
    wcscpy_s(dir, PATHLEN, j->dir);
    size_t n = wcslen(dir);
    if (n && dir[n - 1] == L'\\' && n + 1 < PATHLEN) { dir[n] = L'\\'; dir[n + 1] = 0; }
    swprintf(args, PATHLEN * 2, L"/S /ALLUSERS=1 /_ELEVATED /DIR=\"%ls\" /DESKTOP=%d /STARTMENU=%d /PROGRESSFILE=\"%ls\"",
             dir, j->desktop, j->startMenu, log);

    CoInitializeEx(NULL, COINIT_APARTMENTTHREADED);   /* ShellExecuteEx may hand the work to shell extensions */
    SHELLEXECUTEINFOW sei = { sizeof sei };
    sei.fMask = SEE_MASK_NOCLOSEPROCESS | SEE_MASK_NOASYNC;
    sei.lpVerb = noElevate ? L"open" : L"runas";
    sei.lpFile = selfPath;
    sei.lpParameters = args;
    sei.nShow = SW_HIDE;
    BOOL started = ShellExecuteExW(&sei) && sei.hProcess;
    DWORD why = GetLastError();
    CoUninitialize();
    if (!started) {
        DeleteFileW(log);
        return failJob(j, why == ERROR_CANCELLED ? L"Administrator permission was not given, so nothing was installed."
                                                  : L"Could not start the installation with administrator rights.", NULL);
    }

    int lastPercent = -1;
    for (;;) {
        DWORD r = WaitForSingleObject(sei.hProcess, 100);
        size_t size = 0;
        char *text = readLog(log, &size);
        if (text) { relayLog(j, text, size, &lastPercent, error); free(text); }
        if (r != WAIT_TIMEOUT) break;
    }
    DWORD code = 1;
    GetExitCodeProcess(sei.hProcess, &code);
    CloseHandle(sei.hProcess);
    DeleteFileW(log);
    if (code == 0) return 1;
    return failJob(j, error[0] ? error : L"The installation with administrator rights did not finish.", NULL);
}

static int install(Job *j) {
    wchar_t path[PATHLEN], exe[PATHLEN];
    Sb dat = {0};
    uint64_t total = 0, done = 0;
    size_t biggest = 0;
    int lastPercent = -1;

    if (j->allUsers && !j->elevatedCopy && !isElevated()) return installElevated(j);
    if (!makeDirs(j->dir)) return failJob(j, L"Could not create the folder:", j->dir);
    for (int i = 0; i < entryCount; i++) {
        if (wcscmp(entries[i].name, L"setup.ini") == 0 || isUiEntry(entries[i].name)) continue;
        if (!safeName(entries[i].name)) return failJob(j, L"The installer is damaged (bad file name).", entries[i].name);
        total += entries[i].usize;
        if (entries[i].usize > biggest) biggest = entries[i].usize;
    }
    uint8_t *buf = biggest ? VirtualAlloc(NULL, biggest, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE) : NULL;
    if (biggest && !buf) return failJob(j, L"Not enough memory to install.", NULL);

    sbAdd(&dat, "name="); sbAddW(&dat, appName); sbAdd(&dat, "\n");
    sbAdd(&dat, "id="); sbAddW(&dat, appId); sbAdd(&dat, "\n");
    if (j->allUsers) sbAdd(&dat, "scope=allusers\n");

    for (int i = 0; i < entryCount; i++) {
        Entry *e = &entries[i];
        if (wcscmp(e->name, L"setup.ini") == 0 || isUiEntry(e->name)) continue;
        joinPath(path, j->dir, e->name);
        size_t nl = wcslen(e->name);
        if (e->name[nl - 1] == L'/') {
            if (!makeDirs(path)) return failJob(j, L"Could not create the folder:", path);
            continue;
        }
        const uint8_t *data = entryData(e);
        if (!data) return failJob(j, L"The installer is damaged.", e->name);
        if (e->method == 8) {
            if (inflateRaw(data, e->csize, buf, e->usize)) return failJob(j, L"The installer is damaged.", e->name);
            data = buf;
        } else if (e->method != 0 || e->csize != e->usize) {
            return failJob(j, L"The installer is damaged.", e->name);
        }
        if (crc32(data, e->usize) != e->crc) return failJob(j, L"The installer is damaged (checksum).", e->name);

        wchar_t parent[PATHLEN];
        wcscpy_s(parent, PATHLEN, path);
        wchar_t *slash = wcsrchr(parent, L'\\');
        if (slash) { *slash = 0; if (!makeDirs(parent)) return failJob(j, L"Could not create the folder:", parent); }

        HANDLE f = CreateFileW(path, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
        DWORD wrote = 0;
        if (f == INVALID_HANDLE_VALUE || (e->usize && !WriteFile(f, data, e->usize, &wrote, NULL)) || wrote != e->usize) {
            wchar_t what[512];
            swprintf(what, 512, L"Could not write this file (error %lu). If %ls is running, close it and try again:", GetLastError(), appName);
            if (f != INVALID_HANDLE_VALUE) CloseHandle(f);
            return failJob(j, what, path);
        }
        CloseHandle(f);

        sbAdd(&dat, "file=");
        for (wchar_t *p = e->name; *p; p++) if (*p == L'/') *p = L'\\';
        sbAddW(&dat, e->name);
        sbAdd(&dat, "\n");

        done += e->usize;
        int percent = total ? (int) (done * 100 / total) : 100;
        if (percent != lastPercent) {
            lastPercent = percent;
            if (j->window) PostMessageW(j->window, WM_PROGRESS, percent, 0);
            wchar_t number[16];
            swprintf(number, 16, L"%d", percent);
            report("progress=", number);
        }
    }

    /* The uninstaller is this program without its payload. */
    joinPath(path, j->dir, L"uninstall.exe");
    HANDLE u = CreateFileW(path, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    DWORD wrote = 0;
    if (u == INVALID_HANDLE_VALUE || !WriteFile(u, image, (DWORD) zipStart, &wrote, NULL)) {
        if (u != INVALID_HANDLE_VALUE) CloseHandle(u);
        return failJob(j, L"Could not write the uninstaller:", path);
    }
    CloseHandle(u);

    joinPath(exe, j->dir, appExe);
    wchar_t desktopLink[PATHLEN] = L"", menuLink[PATHLEN] = L"";
    CoInitializeEx(NULL, COINIT_APARTMENTTHREADED);
    if (j->desktop) {
        shortcutPath(1, j->allUsers, desktopLink);
        if (!desktopLink[0] || !makeShortcut(desktopLink, exe, j->dir, appDesc[0] ? appDesc : appName)) desktopLink[0] = 0;
    }
    if (j->startMenu) {
        shortcutPath(0, j->allUsers, menuLink);
        if (!menuLink[0] || !makeShortcut(menuLink, exe, j->dir, appDesc[0] ? appDesc : appName)) menuLink[0] = 0;
    }
    CoUninitialize();
    sbAdd(&dat, "desktop="); sbAddW(&dat, desktopLink); sbAdd(&dat, "\n");
    sbAdd(&dat, "startmenu="); sbAddW(&dat, menuLink); sbAdd(&dat, "\n");

    joinPath(path, j->dir, L"uninstall.dat");
    HANDLE d = CreateFileW(path, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (d == INVALID_HANDLE_VALUE || !WriteFile(d, dat.p, (DWORD) dat.n, &wrote, NULL)) {
        if (d != INVALID_HANDLE_VALUE) CloseHandle(d);
        return failJob(j, L"Could not write the uninstall list:", path);
    }
    CloseHandle(d);
    free(dat.p);

    /* Apps & features */
    wchar_t key[PATHLEN], text[PATHLEN];
    HKEY k;
    swprintf(key, PATHLEN, UNINSTALL_KEY L"%ls", appId);
    if (RegCreateKeyExW(hive(j->allUsers), key, 0, NULL, 0, KEY_WRITE, NULL, &k, NULL) == ERROR_SUCCESS) {
        regString(k, L"DisplayName", appName);
        regString(k, L"DisplayVersion", appVersion);
        regString(k, L"Publisher", appVendor);
        regString(k, L"InstallLocation", j->dir);
        regString(k, L"DisplayIcon", exe);
        joinPath(path, j->dir, L"uninstall.exe");
        swprintf(text, PATHLEN, L"\"%ls\"", path);
        regString(k, L"UninstallString", text);
        swprintf(text, PATHLEN, L"\"%ls\" /S", path);
        regString(k, L"QuietUninstallString", text);
        regDword(k, L"NoModify", 1);
        regDword(k, L"NoRepair", 1);
        regDword(k, L"EstimatedSize", (DWORD) (total / 1024));
        SYSTEMTIME now;
        GetLocalTime(&now);
        swprintf(text, PATHLEN, L"%04d%02d%02d", now.wYear, now.wMonth, now.wDay);
        regString(k, L"InstallDate", text);
        RegCloseKey(k);
    }
    if (buf) VirtualFree(buf, 0, MEM_RELEASE);
    return 1;
}

/* ---- uninstalling -------------------------------------------------------------------------------------------------- */

/* Removes what uninstall.dat lists in dir. Returns 1 if everything went. */
static int removeInstall(const wchar_t *dir, int silent, wchar_t *nameOut) {
    wchar_t path[PATHLEN], id[256] = L"", desktop[PATHLEN] = L"", menu[PATHLEN] = L"", scope[16] = L"";
    size_t size = 0;
    joinPath(path, dir, L"uninstall.dat");
    char *text = (char *) readFile(path, &size);
    if (!text) { message(NULL, L"Nothing to uninstall here: uninstall.dat is missing.", MB_ICONERROR); return 0; }
    iniValue(text, size, "name", nameOut, 256);
    iniValue(text, size, "id", id, 256);
    iniValue(text, size, "desktop", desktop, PATHLEN);
    iniValue(text, size, "startmenu", menu, PATHLEN);
    iniValue(text, size, "scope", scope, 16);

    int failures = 0;
    for (size_t i = 0; i < size;) {
        size_t e = i;
        while (e < size && text[e] != '\n') e++;
        size_t le = e;
        if (le > i && text[le - 1] == '\r') le--;
        if (le - i > 5 && memcmp(text + i, "file=", 5) == 0) {
            wchar_t *rel = widen(text + i + 5, (int) (le - i - 5));
            if (safeName(rel)) {
                joinPath(path, dir, rel);
                if (!DeleteFileW(path) && GetLastError() != ERROR_FILE_NOT_FOUND && GetLastError() != ERROR_PATH_NOT_FOUND) failures++;
            }
            free(rel);
        }
        i = e + 1;
    }
    /* Folders the app made inside the install folder go, if they are empty now. */
    for (size_t i = 0; i < size;) {
        size_t e = i;
        while (e < size && text[e] != '\n') e++;
        size_t le = e;
        if (le > i && text[le - 1] == '\r') le--;
        if (le - i > 5 && memcmp(text + i, "file=", 5) == 0) {
            wchar_t *rel = widen(text + i + 5, (int) (le - i - 5));
            if (safeName(rel)) {
                joinPath(path, dir, rel);
                for (wchar_t *s; (s = wcsrchr(path, L'\\')) != NULL;) {
                    *s = 0;
                    if (wcslen(path) <= wcslen(dir)) break;
                    if (!RemoveDirectoryW(path)) break;
                }
            }
            free(rel);
        }
        i = e + 1;
    }
    free(text);

    if (failures) {
        wchar_t msg[512];
        swprintf(msg, 512, L"%d file(s) could not be removed. If %ls is running, close it and run the uninstaller again.", failures, nameOut);
        if (!silent) message(NULL, msg, MB_ICONWARNING);
        return 0;
    }

    if (desktop[0]) DeleteFileW(desktop);
    if (menu[0]) DeleteFileW(menu);
    joinPath(path, dir, L"uninstall.dat");
    DeleteFileW(path);
    joinPath(path, dir, L"uninstall.exe");
    for (int tries = 0; tries < 20 && !DeleteFileW(path) && fileExists(path); tries++) Sleep(150);  /* the first process may still be exiting */
    RemoveDirectoryW(dir);
    wchar_t key[PATHLEN];
    swprintf(key, PATHLEN, UNINSTALL_KEY L"%ls", id);
    if (id[0]) RegDeleteKeyW(hive(wcscmp(scope, L"allusers") == 0), key);
    return 1;
}

/* Starts this uninstaller again with administrator rights. Returns its exit code: 0 once started, 1 if the person said no. */
static int uninstallElevated(int silent) {
    CoInitializeEx(NULL, COINIT_APARTMENTTHREADED);
    SHELLEXECUTEINFOW sei = { sizeof sei };
    sei.fMask = SEE_MASK_NOASYNC;
    sei.lpVerb = L"runas";
    sei.lpFile = selfPath;
    sei.lpParameters = silent ? L"/S" : L"";
    sei.nShow = SW_SHOWNORMAL;
    BOOL started = ShellExecuteExW(&sei);
    DWORD why = GetLastError();
    CoUninitialize();
    if (!started && why != ERROR_CANCELLED && !silent) message(NULL, L"Could not start the uninstaller with administrator rights.", MB_ICONERROR);
    return started ? 0 : 1;
}

/* A running exe cannot delete itself, so uninstall.exe copies itself to the temp folder and that copy does the work. */
static int runUninstaller(int silent, const wchar_t *removeDir) {
    wchar_t dir[PATHLEN], name[256] = L"";
    if (removeDir) {
        wcscpy_s(dir, PATHLEN, removeDir);
        int ok = removeInstall(dir, silent, name);
        if (appName[0] == 0 || wcscmp(appName, L"App") == 0) wcscpy_s(appName, 256, name[0] ? name : L"Uninstall");
        if (ok && !silent) { wchar_t m[512]; swprintf(m, 512, L"%ls was removed.", name); message(NULL, m, MB_ICONINFORMATION); }
        /* Delete this temporary copy once the process is gone. */
        wchar_t cmd[PATHLEN + 128];
        swprintf(cmd, PATHLEN + 128, L"cmd.exe /c \"ping -n 3 127.0.0.1 >nul & del /f /q \"%ls\"\"", selfPath);
        STARTUPINFOW si = { sizeof si };
        PROCESS_INFORMATION pi;
        if (CreateProcessW(NULL, cmd, NULL, NULL, FALSE, CREATE_NO_WINDOW, NULL, NULL, &si, &pi)) {
            CloseHandle(pi.hThread);
            CloseHandle(pi.hProcess);
        }
        return ok ? 0 : 1;
    }

    wcscpy_s(dir, PATHLEN, selfPath);
    wchar_t *slash = wcsrchr(dir, L'\\');
    if (slash) *slash = 0;
    wchar_t dat[PATHLEN];
    size_t size = 0;
    joinPath(dat, dir, L"uninstall.dat");
    char *text = (char *) readFile(dat, &size);
    if (!text) { message(NULL, L"Nothing to uninstall here: uninstall.dat is missing.", MB_ICONERROR); return 1; }
    iniValue(text, size, "name", name, 256);
    wchar_t scope[16] = L"";
    iniValue(text, size, "scope", scope, 16);
    free(text);
    wcscpy_s(appName, 256, name[0] ? name : L"Uninstall");
    if (wcscmp(scope, L"allusers") == 0 && !noElevate && !isElevated()) return uninstallElevated(silent);

    if (!silent) {
        wchar_t q[PATHLEN + 256];
        swprintf(q, PATHLEN + 256, L"Remove %ls from your computer?\n\n%ls", name, dir);
        if (MessageBoxW(NULL, q, appName, MB_YESNO | MB_ICONQUESTION | MB_DEFBUTTON2) != IDYES) return 0;
    }

    wchar_t temp[PATHLEN], copy[PATHLEN];
    GetTempPathW(PATHLEN, temp);
    swprintf(copy, PATHLEN, L"%lsuninstall-%lu.exe", temp, GetCurrentProcessId());
    if (!CopyFileW(selfPath, copy, FALSE)) { message(NULL, L"Could not start the uninstaller.", MB_ICONERROR); return 1; }
    wchar_t cmd[PATHLEN * 2];
    swprintf(cmd, PATHLEN * 2, L"\"%ls\" /_remove \"%ls\"%ls", copy, dir, silent ? L" /S" : L"");
    STARTUPINFOW si = { sizeof si };
    PROCESS_INFORMATION pi;
    if (!CreateProcessW(copy, cmd, NULL, NULL, FALSE, 0, NULL, temp, &si, &pi)) {
        message(NULL, L"Could not start the uninstaller.", MB_ICONERROR);
        return 1;
    }
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return 0;
}

/* ---- the wizard ---------------------------------------------------------------------------------------------------- */

enum {
    ID_TITLE = 100, ID_SUB, ID_DESC, ID_DIRLABEL, ID_DIR, ID_BROWSE, ID_DESKTOP, ID_MENU, ID_ALLUSERS, ID_LAUNCH, ID_PROGRESS, ID_STATUS, ID_LINE
};
enum { STATE_OPTIONS, STATE_WORKING, STATE_DONE };

static HINSTANCE instance;
static HWND window;
static HFONT uiFont, titleFont;
static int dpi = 96;
static int state = STATE_OPTIONS;
static Job job;
static wchar_t installedExe[PATHLEN];
static wchar_t installedDir[PATHLEN];

#define S(x) MulDiv((x), dpi, 96)

static HWND control(const wchar_t *cls, const wchar_t *text, DWORD style, int id, int x, int y, int w, int h) {
    HWND c = CreateWindowExW(0, cls, text, WS_CHILD | WS_VISIBLE | style, S(x), S(y), S(w), S(h), window, (HMENU) (INT_PTR) id, instance, NULL);
    SendMessageW(c, WM_SETFONT, (WPARAM) (id == ID_TITLE ? titleFont : uiFont), TRUE);
    return c;
}

static HWND item(int id) { return GetDlgItem(window, id); }

static void showOptions(int on) {
    int ids[] = { ID_DIRLABEL, ID_DIR, ID_BROWSE, ID_DESKTOP, ID_MENU, ID_ALLUSERS };
    for (int i = 0; i < 6; i++) ShowWindow(item(ids[i]), on ? SW_SHOW : SW_HIDE);
}

static DWORD WINAPI installThread(LPVOID arg) {
    Job *j = arg;
    int ok = install(j);
    PostMessageW(j->window, WM_FINISHED, ok, 0);
    return 0;
}

static void startInstall(void) {
    GetWindowTextW(item(ID_DIR), job.dir, PATHLEN);
    size_t n = wcslen(job.dir);
    while (n > 3 && (job.dir[n - 1] == L'\\' || job.dir[n - 1] == L'/')) job.dir[--n] = 0;
    if (n < 4 || job.dir[1] != L':' || job.dir[2] != L'\\') {
        if (!(job.dir[0] == L'\\' && job.dir[1] == L'\\')) {
            message(window, L"Enter a full folder path, such as C:\\Programs\\App.", MB_ICONWARNING);
            return;
        }
    }
    job.desktop = IsDlgButtonChecked(window, ID_DESKTOP) == BST_CHECKED;
    job.startMenu = IsDlgButtonChecked(window, ID_MENU) == BST_CHECKED;
    job.allUsers = IsDlgButtonChecked(window, ID_ALLUSERS) == BST_CHECKED;
    job.window = window;
    state = STATE_WORKING;
    showOptions(0);
    SendMessageW(item(ID_PROGRESS), PBM_SETPOS, 0, 0);
    ShowWindow(item(ID_PROGRESS), SW_SHOW);
    SetWindowTextW(item(ID_STATUS), L"Installing...");
    ShowWindow(item(ID_STATUS), SW_SHOW);
    EnableWindow(item(IDOK), FALSE);
    EnableWindow(item(IDCANCEL), FALSE);
    HANDLE t = CreateThread(NULL, 0, installThread, &job, 0, NULL);
    if (t) CloseHandle(t);
}

static void browse(void) {
    wchar_t title[256], picked[PATHLEN];
    swprintf(title, 256, L"Choose where to install %ls", appName);
    BROWSEINFOW bi = { 0 };
    bi.hwndOwner = window;
    bi.lpszTitle = title;
    bi.ulFlags = BIF_RETURNONLYFSDIRS | BIF_USENEWUI;
    PIDLIST_ABSOLUTE pidl = SHBrowseForFolderW(&bi);
    if (pidl) {
        if (SHGetPathFromIDListW(pidl, picked)) SetWindowTextW(item(ID_DIR), picked);
        CoTaskMemFree(pidl);
    }
}

static LRESULT CALLBACK wndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
    case WM_COMMAND:
        switch (LOWORD(wp)) {
        case ID_BROWSE: browse(); return 0;
        case ID_ALLUSERS:
            if (HIWORD(wp) == BN_CLICKED) {
                /* The folder follows the choice, unless the person has typed their own. */
                wchar_t now[PATHLEN], before[PATHLEN], after[PATHLEN];
                int all = IsDlgButtonChecked(hwnd, ID_ALLUSERS) == BST_CHECKED;
                GetWindowTextW(item(ID_DIR), now, PATHLEN);
                defaultDir(before, !all);
                defaultDir(after, all);
                if (_wcsicmp(now, before) == 0) SetWindowTextW(item(ID_DIR), after);
            }
            return 0;
        case IDOK:
            if (state == STATE_OPTIONS) {
                startInstall();
            } else if (state == STATE_DONE) {
                if (IsDlgButtonChecked(hwnd, ID_LAUNCH) == BST_CHECKED) ShellExecuteW(NULL, L"open", installedExe, NULL, installedDir, SW_SHOWNORMAL);
                DestroyWindow(hwnd);
            }
            return 0;
        case IDCANCEL:
            if (state != STATE_WORKING) DestroyWindow(hwnd);
            return 0;
        }
        break;
    case WM_PROGRESS:
        SendMessageW(item(ID_PROGRESS), PBM_SETPOS, wp, 0);
        return 0;
    case WM_FINISHED:
        if (wp) {
            state = STATE_DONE;
            wcscpy_s(installedDir, PATHLEN, job.dir);
            joinPath(installedExe, job.dir, appExe);
            SendMessageW(item(ID_PROGRESS), PBM_SETPOS, 100, 0);
            wchar_t text[512];
            swprintf(text, 512, L"%ls is installed.", appName);
            SetWindowTextW(item(ID_STATUS), text);
            swprintf(text, 512, L"Launch %ls", appName);
            SetWindowTextW(item(ID_LAUNCH), text);
            ShowWindow(item(ID_LAUNCH), SW_SHOW);
            SetWindowTextW(item(IDOK), L"Finish");
            ShowWindow(item(IDCANCEL), SW_HIDE);
            EnableWindow(item(IDOK), TRUE);
        } else {
            state = STATE_OPTIONS;
            ShowWindow(item(ID_PROGRESS), SW_HIDE);
            ShowWindow(item(ID_STATUS), SW_HIDE);
            showOptions(1);
            EnableWindow(item(IDOK), TRUE);
            EnableWindow(item(IDCANCEL), TRUE);
            message(hwnd, job.error, MB_ICONERROR);
        }
        return 0;
    case WM_CLOSE:
        if (state == STATE_WORKING) return 0;
        DestroyWindow(hwnd);
        return 0;
    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

static int runWizard(int nShow) {
    INITCOMMONCONTROLSEX icc = { sizeof icc, ICC_STANDARD_CLASSES | ICC_PROGRESS_CLASS };
    InitCommonControlsEx(&icc);
    CoInitializeEx(NULL, COINIT_APARTMENTTHREADED);

    HDC screen = GetDC(NULL);
    dpi = GetDeviceCaps(screen, LOGPIXELSX);
    ReleaseDC(NULL, screen);
    NONCLIENTMETRICSW ncm = { sizeof ncm };
    SystemParametersInfoW(SPI_GETNONCLIENTMETRICS, sizeof ncm, &ncm, 0);
    uiFont = CreateFontIndirectW(&ncm.lfMessageFont);
    LOGFONTW big = ncm.lfMessageFont;
    big.lfHeight = big.lfHeight * 8 / 5;
    big.lfWeight = FW_SEMIBOLD;
    titleFont = CreateFontIndirectW(&big);

    HICON icon = ExtractIconW(instance, selfPath, 0);
    WNDCLASSEXW wc = { sizeof wc };
    wc.lpfnWndProc = wndProc;
    wc.hInstance = instance;
    wc.hCursor = LoadCursorW(NULL, IDC_ARROW);
    wc.hbrBackground = (HBRUSH) (COLOR_BTNFACE + 1);
    wc.hIcon = (icon && icon != (HICON) 1) ? icon : LoadIconW(NULL, IDI_APPLICATION);
    wc.lpszClassName = L"RenglSetup";
    RegisterClassExW(&wc);

    RECT r = { 0, 0, S(500), S(350) };
    DWORD style = WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX;
    AdjustWindowRect(&r, style, FALSE);
    wchar_t title[300];
    swprintf(title, 300, L"%ls Setup", appName);
    window = CreateWindowExW(0, L"RenglSetup", title, style, CW_USEDEFAULT, CW_USEDEFAULT, r.right - r.left, r.bottom - r.top, NULL, NULL, instance, NULL);

    wchar_t text[600];
    swprintf(text, 600, L"Install %ls", appName);
    control(L"STATIC", text, 0, ID_TITLE, 24, 20, 452, 32);
    swprintf(text, 600, L"Version %ls%ls%ls", appVersion, appVendor[0] ? L"  -  " : L"", appVendor);
    control(L"STATIC", text, 0, ID_SUB, 24, 54, 452, 18);
    control(L"STATIC", appDesc, 0, ID_DESC, 24, 80, 452, 36);

    control(L"STATIC", L"Install location:", 0, ID_DIRLABEL, 24, 128, 452, 18);
    wchar_t dir[PATHLEN];
    defaultDir(dir, 0);
    control(L"EDIT", dir, WS_BORDER | WS_TABSTOP | ES_AUTOHSCROLL, ID_DIR, 24, 150, 364, 24);
    control(L"BUTTON", L"Browse...", WS_TABSTOP | BS_PUSHBUTTON, ID_BROWSE, 396, 149, 80, 26);
    control(L"BUTTON", L"Create a desktop shortcut", WS_TABSTOP | BS_AUTOCHECKBOX, ID_DESKTOP, 24, 192, 452, 22);
    control(L"BUTTON", L"Add to the Start menu", WS_TABSTOP | BS_AUTOCHECKBOX, ID_MENU, 24, 218, 452, 22);
    control(L"BUTTON", L"Install for all users (asks for administrator permission)", WS_TABSTOP | BS_AUTOCHECKBOX, ID_ALLUSERS, 24, 244, 452, 22);
    CheckDlgButton(window, ID_DESKTOP, BST_CHECKED);
    CheckDlgButton(window, ID_MENU, BST_CHECKED);

    HWND bar = control(PROGRESS_CLASSW, L"", PBS_SMOOTH, ID_PROGRESS, 24, 192, 452, 18);
    SendMessageW(bar, PBM_SETRANGE32, 0, 100);
    ShowWindow(bar, SW_HIDE);
    ShowWindow(control(L"STATIC", L"", 0, ID_STATUS, 24, 218, 452, 18), SW_HIDE);
    ShowWindow(control(L"BUTTON", L"", WS_TABSTOP | BS_AUTOCHECKBOX, ID_LAUNCH, 24, 250, 452, 22), SW_HIDE);
    CheckDlgButton(window, ID_LAUNCH, BST_CHECKED);

    control(L"STATIC", L"", SS_ETCHEDHORZ, ID_LINE, 0, 298, 500, 2);
    control(L"BUTTON", L"Install", WS_TABSTOP | BS_DEFPUSHBUTTON, IDOK, 296, 312, 90, 28);
    control(L"BUTTON", L"Cancel", WS_TABSTOP | BS_PUSHBUTTON, IDCANCEL, 394, 312, 90, 28);

    ShowWindow(window, nShow);
    UpdateWindow(window);
    SetFocus(item(IDOK));

    MSG m;
    while (GetMessageW(&m, NULL, 0, 0) > 0) {
        if (!IsDialogMessageW(window, &m)) {
            TranslateMessage(&m);
            DispatchMessageW(&m);
        }
    }
    return 0;
}

/* ---- the RenGL setup program --------------------------------------------------------------------------------------- */

/*
 * When the payload carries a ui/ folder (setup.ini says ui=<launcher>), that folder is a small RenGL app-image whose window
 * is the installer you see. This program only unpacks it into the temp folder, runs it with
 *
 *     <launcher> --setup <this exe> --dir <default install folder> --ready <file>
 *
 * and waits. The program shows the options, then runs this exe again with /S /PROGRESS to do the installing, and reads the
 * progress lines from its stdout. It creates the --ready file once its window is up, and exits with UI_NO_WINDOW if it
 * could not open one (no usable graphics driver, say), in which case the wizard above is shown instead.
 */

#define UI_FALLBACK (-1)
#define UI_NO_WINDOW 3

static void removeTree(const wchar_t *dir) {
    wchar_t pattern[PATHLEN], path[PATHLEN];
    WIN32_FIND_DATAW f;
    swprintf(pattern, PATHLEN, L"%ls\\*", dir);
    HANDLE h = FindFirstFileW(pattern, &f);
    if (h != INVALID_HANDLE_VALUE) {
        do {
            if (wcscmp(f.cFileName, L".") == 0 || wcscmp(f.cFileName, L"..") == 0) continue;
            swprintf(path, PATHLEN, L"%ls\\%ls", dir, f.cFileName);
            int folder = (f.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
            if (folder && !(f.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT)) {
                removeTree(path);
            } else {
                SetFileAttributesW(path, FILE_ATTRIBUTE_NORMAL);   /* a link is removed, never followed out of the folder */
                if (folder) RemoveDirectoryW(path); else DeleteFileW(path);
            }
        } while (FindNextFileW(h, &f));
        FindClose(h);
    }
    RemoveDirectoryW(dir);
}

/*
 * Where an entry goes when the RenGL setup program is unpacked, relative to its folder, or NULL if it is not part of it: the
 * ui/ entries, and with ui-share also the app's runtime/ and its jars (the files directly in app/).
 */
static const wchar_t *uiPath(const wchar_t *name) {
    if (isUiEntry(name)) return name[3] ? name + 3 : NULL;
    if (!uiShare) return NULL;
    if (wcsncmp(name, L"runtime/", 8) == 0) return name;
    if (wcsncmp(name, L"app/", 4) == 0) {
        size_t n = wcslen(name);
        if (n > 8 && !wcschr(name + 4, L'/') && _wcsicmp(name + n - 4, L".jar") == 0) return name;
    }
    return NULL;
}

/* Unpacks the entries uiPath names into dir. */
static int extractUi(const wchar_t *dir) {
    size_t biggest = 0;
    int found = 0;
    for (int i = 0; i < entryCount; i++) {
        if (!uiPath(entries[i].name)) continue;
        if (isUiEntry(entries[i].name)) found = 1;
        if (entries[i].usize > biggest) biggest = entries[i].usize;
    }
    if (!found) return 0;
    uint8_t *buf = biggest ? VirtualAlloc(NULL, biggest, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE) : NULL;
    if (biggest && !buf) return 0;

    int ok = 1;
    for (int i = 0; i < entryCount && ok; i++) {
        Entry *e = &entries[i];
        const wchar_t *rel = uiPath(e->name);
        if (!rel) continue;
        wchar_t path[PATHLEN];
        if (!safeName(rel)) { ok = 0; break; }
        joinPath(path, dir, rel);
        if (rel[wcslen(rel) - 1] == L'/') {
            ok = makeDirs(path);
            continue;
        }
        const uint8_t *data = entryData(e);
        if (!data) { ok = 0; break; }
        if (e->method == 8) {
            if (inflateRaw(data, e->csize, buf, e->usize)) { ok = 0; break; }
            data = buf;
        } else if (e->method != 0 || e->csize != e->usize) {
            ok = 0;
            break;
        }
        if (crc32(data, e->usize) != e->crc) { ok = 0; break; }

        wchar_t parent[PATHLEN];
        wcscpy_s(parent, PATHLEN, path);
        wchar_t *slash = wcsrchr(parent, L'\\');
        if (slash) { *slash = 0; if (!makeDirs(parent)) { ok = 0; break; } }
        HANDLE f = CreateFileW(path, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
        DWORD wrote = 0;
        if (f == INVALID_HANDLE_VALUE) { ok = 0; break; }
        if (e->usize && (!WriteFile(f, data, e->usize, &wrote, NULL) || wrote != e->usize)) ok = 0;
        CloseHandle(f);
    }
    if (buf) VirtualFree(buf, 0, MEM_RELEASE);
    return ok;
}

/* A small "please wait" window for the seconds between double-clicking the setup and the RenGL window appearing. */
static HWND showPreparing(void) {
    HDC screen = GetDC(NULL);
    int scale = GetDeviceCaps(screen, LOGPIXELSX);
    ReleaseDC(NULL, screen);
    int w = MulDiv(320, scale, 96), h = MulDiv(64, scale, 96);
    wchar_t text[300];
    swprintf(text, 300, L"Preparing %ls Setup...", appName);
    HWND box = CreateWindowExW(WS_EX_TOPMOST | WS_EX_TOOLWINDOW, L"STATIC", text,
                               WS_POPUP | WS_BORDER | WS_VISIBLE | SS_CENTER | SS_CENTERIMAGE,
                               (GetSystemMetrics(SM_CXSCREEN) - w) / 2, (GetSystemMetrics(SM_CYSCREEN) - h) / 2, w, h,
                               NULL, NULL, instance, NULL);
    if (box) {
        NONCLIENTMETRICSW ncm = { sizeof ncm };
        SystemParametersInfoW(SPI_GETNONCLIENTMETRICS, sizeof ncm, &ncm, 0);
        SendMessageW(box, WM_SETFONT, (WPARAM) CreateFontIndirectW(&ncm.lfMessageFont), TRUE);
        UpdateWindow(box);
    }
    return box;
}

static void pumpMessages(void) {
    MSG m;
    while (PeekMessageW(&m, NULL, 0, 0, PM_REMOVE)) {
        TranslateMessage(&m);
        DispatchMessageW(&m);
    }
}

/* Runs the RenGL setup program. Returns its exit code, or UI_FALLBACK when it could not be run or had no window to show. */
static int runUi(void) {
    wchar_t temp[PATHLEN], dir[PATHLEN], exe[PATHLEN], ready[PATHLEN], start[PATHLEN], cmd[PATHLEN * 3];
    DWORD n = GetTempPathW(PATHLEN, temp);
    if (!n || n >= PATHLEN) return UI_FALLBACK;
    while (n > 3 && temp[n - 1] == L'\\') temp[--n] = 0;
    swprintf(dir, PATHLEN, L"%ls\\rengl-setup-%lu", temp, GetCurrentProcessId());
    removeTree(dir);

    HWND preparing = showPreparing();
    pumpMessages();
    int code = UI_FALLBACK;
    if (makeDirs(dir) && extractUi(dir)) {
        joinPath(exe, dir, appUi);
        joinPath(ready, dir, L"ready");
        defaultDir(start, 0);
        swprintf(cmd, PATHLEN * 3, L"\"%ls\" --setup \"%ls\" --dir \"%ls\" --ready \"%ls\"", exe, selfPath, start, ready);
        STARTUPINFOW si = { sizeof si };
        PROCESS_INFORMATION pi;
        if (fileExists(exe) && CreateProcessW(exe, cmd, NULL, NULL, FALSE, 0, NULL, dir, &si, &pi)) {
            for (;;) {
                DWORD r = MsgWaitForMultipleObjects(1, &pi.hProcess, FALSE, 100, QS_ALLINPUT);
                pumpMessages();
                if (preparing && fileExists(ready)) { DestroyWindow(preparing); preparing = NULL; }
                if (r == WAIT_OBJECT_0) break;
            }
            DWORD exitCode = 1;
            GetExitCodeProcess(pi.hProcess, &exitCode);
            CloseHandle(pi.hThread);
            CloseHandle(pi.hProcess);
            code = exitCode == UI_NO_WINDOW ? UI_FALLBACK : (int) exitCode;
        }
    }
    if (preparing) DestroyWindow(preparing);
    for (int tries = 0; tries < 20 && fileExists(dir); tries++) {   /* the runtime may still be unmapping for a moment */
        removeTree(dir);
        if (fileExists(dir)) Sleep(250);
    }
    return code;
}

/* ---- entry --------------------------------------------------------------------------------------------------------- */

int WINAPI wWinMain(HINSTANCE inst, HINSTANCE prev, PWSTR cmdLine, int nShow) {
    int argc = 0;
    wchar_t **argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    int silent = 0, launch = 0, desktop = -1, menu = -1, noUi = 0, allUsers = 0, elevatedCopy = 0;
    wchar_t dirArg[PATHLEN] = L"", removeDir[PATHLEN] = L"";
    (void) prev;
    (void) cmdLine;
    instance = inst;
    crcInit();

    for (int i = 1; i < argc; i++) {
        wchar_t *a = argv[i];
        if (_wcsicmp(a, L"/S") == 0) silent = 1;
        else if (_wcsicmp(a, L"/LAUNCH") == 0) launch = 1;
        else if (_wcsicmp(a, L"/PROGRESS") == 0) reportProgress = 1;
        else if (_wcsicmp(a, L"/NOUI") == 0) noUi = 1;
        else if (_wcsnicmp(a, L"/DIR=", 5) == 0) wcsncpy_s(dirArg, PATHLEN, a + 5, _TRUNCATE);
        else if (_wcsnicmp(a, L"/D=", 3) == 0) wcsncpy_s(dirArg, PATHLEN, a + 3, _TRUNCATE);
        else if (_wcsnicmp(a, L"/DESKTOP=", 9) == 0) desktop = _wtoi(a + 9) != 0;
        else if (_wcsnicmp(a, L"/STARTMENU=", 11) == 0) menu = _wtoi(a + 11) != 0;
        else if (_wcsnicmp(a, L"/ALLUSERS=", 10) == 0) allUsers = _wtoi(a + 10) != 0;
        else if (_wcsnicmp(a, L"/PROGRESSFILE=", 14) == 0) wcsncpy_s(progressFile, PATHLEN, a + 14, _TRUNCATE);
        else if (_wcsicmp(a, L"/NOELEVATE") == 0) noElevate = 1;
        else if (_wcsicmp(a, L"/_ELEVATED") == 0) elevatedCopy = 1;
        else if (_wcsicmp(a, L"/_remove") == 0 && i + 1 < argc) wcsncpy_s(removeDir, PATHLEN, argv[++i], _TRUNCATE);
    }

    if (!mapSelf()) {
        message(NULL, L"Could not read the setup file.", MB_ICONERROR);
        return 1;
    }
    if (!readDirectory() || !loadSettings()) {
        /* No payload: this is the uninstaller. */
        CoInitializeEx(NULL, COINIT_APARTMENTTHREADED);
        return runUninstaller(silent, removeDir[0] ? removeDir : NULL);
    }

    if (silent) {
        wcscpy_s(job.dir, PATHLEN, dirArg[0] ? dirArg : L"");
        job.allUsers = allUsers;
        job.elevatedCopy = elevatedCopy;
        if (!job.dir[0]) defaultDir(job.dir, job.allUsers);
        job.desktop = desktop < 0 ? 1 : desktop;
        job.startMenu = menu < 0 ? 1 : menu;
        job.window = NULL;
        if (!install(&job)) {
            report("error=", job.error);
            return 1;
        }
        if (launch) {
            joinPath(installedExe, job.dir, appExe);
            ShellExecuteW(NULL, L"open", installedExe, NULL, job.dir, SW_SHOWNORMAL);
        }
        return 0;
    }
    if (appUi[0] && !noUi) {
        int result = runUi();
        if (result != UI_FALLBACK) return result;
    }
    return runWizard(nShow);
}
