@echo off
rem Builds setup.c into the resource the packager uses for --installer on windows-x64.
rem Needs the MSVC build tools (any Visual Studio with "Desktop development with C++"). No JDK is needed.
rem Usage: build-setup.cmd
setlocal

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

set "OUT=%~dp0..\..\src\main\resources\me\piitex\engine\packager\setup-windows-x64.exe"
set "OBJ=%TEMP%\rengl-setup"
mkdir "%OBJ%" 2>nul

rem /MT links the C runtime in, so the setup needs no Visual C++ redistributable.
cl /nologo /O2 /MT /W3 /DUNICODE /D_UNICODE /D_CRT_SECURE_NO_WARNINGS ^
   /Fo"%OBJ%\\" /Fe"%OUT%" "%~dp0setup.c" ^
   /link /SUBSYSTEM:WINDOWS /OPT:REF /OPT:ICF /MANIFEST:EMBED /MANIFESTINPUT:"%~dp0setup.manifest" ^
   user32.lib shell32.lib ole32.lib advapi32.lib gdi32.lib comctl32.lib uuid.lib
exit /b %ERRORLEVEL%
