@echo off
setlocal EnableDelayedExpansion

set "TESTLOG=%~dp0bat_test.log"
echo [TEST] Starting at %DATE% %TIME% > "%TESTLOG%"

set "JDK=%~dp0jdk\bin\java.exe"
set "JAVAC=%~dp0jdk\bin\javac.exe"
set "SOURCE=%~dp0LinkdroidLauncher.java"
set "LIBDIR=%~dp0lib"
set "BUILDDIR=%~dp0build"

echo [TEST] JDK=%JDK% >> "%TESTLOG%"
echo [TEST] SOURCE=%SOURCE% >> "%TESTLOG%"

if not exist "%JDK%" (
    echo [ERROR] JDK not found >> "%TESTLOG%"
    echo ERROR: JDK not found at %JDK%
    pause
    exit /b 1
)

if not exist "%SOURCE%" (
    echo [ERROR] Source not found >> "%TESTLOG%"
    echo ERROR: Source not found
    pause
    exit /b 1
)

if not exist "%LIBDIR%\jmdns.jar" (
    echo [ERROR] jmdns.jar not found >> "%TESTLOG%"
    echo ERROR: jmdns.jar not found
    pause
    exit /b 1
)

if not exist "%BUILDDIR%" mkdir "%BUILDDIR%"

echo [TEST] Compiling... >> "%TESTLOG%"
echo Compiling...
"%JAVAC%" -d "%BUILDDIR%" -cp "%LIBDIR%\*" "%SOURCE%"
if errorlevel 1 (
    echo [ERROR] Compilation failed >> "%TESTLOG%"
    echo ERROR: Compilation failed
    pause
    exit /b 1
)

echo [TEST] Compilation OK >> "%TESTLOG%"
echo Starting launcher...
echo [TEST] Launching Java >> "%TESTLOG%"

rem Pass command-line arguments to Java
cmd /k ""%JDK%" -cp "%LIBDIR%\*;%BUILDDIR%" LinkdroidLauncher %*"
echo [TEST] Java exited with %ERRORLEVEL% >> "%TESTLOG%"
pause
endlocal
