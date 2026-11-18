@echo off
rem Runs gradlew through a temporary ASCII drive letter: Gradle test workers fail
rem (ClassNotFoundException) when the project path contains non-ASCII characters.
rem Usage: build-debug.cmd testDebugUnitTest assembleDebug
setlocal
for %%I in ("%~dp0..") do set "PARENT=%%~fI"
for %%I in ("%~dp0.") do set "NAME=%%~nxI"
set "DRIVE="
for %%D in (Q R S T U V W X Y Z) do if not defined DRIVE if not exist %%D:\ set "DRIVE=%%D:"
if not defined DRIVE (echo No free drive letter for subst & exit /b 1)
subst %DRIVE% "%PARENT%"
pushd %DRIVE%\%NAME%
call gradlew.bat %*
set "CODE=%ERRORLEVEL%"
popd
subst %DRIVE% /d
exit /b %CODE%
