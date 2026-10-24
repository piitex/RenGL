@echo off
rem Builds launcher.c into the resource the packager copies for windows-x64 apps.
rem Needs the MSVC build tools (any Visual Studio with "Desktop development with C++") and a JDK for jni.h.
rem Usage: build-launcher.cmd [path-to-JDK]   (default: JAVA_HOME)
setlocal
set "JDK=%~1"
if "%JDK%"=="" set "JDK=%JAVA_HOME%"
if not exist "%JDK%\include\jni.h" (
    echo jni.h not found. Pass a JDK folder or set JAVA_HOME.
    exit /b 1
)

set "VCVARS="
for %%E in (Community Professional Enterprise BuildTools) do (
    for %%V in (18 2022 2019) do (
        if not defined VCVARS if exist "%ProgramFiles%\Microsoft Visual Studio\%%V\%%E\VC\Auxiliary\Build\vcvars64.bat" set "VCVARS=%ProgramFiles%\Microsoft Visual Studio\%%V\%%E\VC\Auxiliary\Build\vcvars64.bat"
    )
)
if not defined VCVARS (
    echo Visual Studio build tools not found.
    exit /b 1
)
call "%VCVARS%" >nul

set "OUT=%~dp0..\..\src\main\resources\me\piitex\engine\packager\launcher-windows-x64.exe"
set "OBJ=%TEMP%\rengl-launcher"
mkdir "%OBJ%" 2>nul

rem /MT links the C runtime in, so the launcher needs no Visual C++ redistributable.
cl /nologo /O2 /MT /W3 /DUNICODE /D_UNICODE /D_CRT_SECURE_NO_WARNINGS ^
   /I"%JDK%\include" /I"%JDK%\include\win32" ^
   /Fo"%OBJ%\\" /Fe"%OUT%" "%~dp0launcher.c" ^
   /link /SUBSYSTEM:WINDOWS /OPT:REF /OPT:ICF user32.lib shell32.lib
exit /b %ERRORLEVEL%
