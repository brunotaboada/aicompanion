@echo off
rem aicompanion - AI SDLC task runner (Windows launcher)
setlocal
set "DIR=%~dp0"
set "JAR=%DIR%target\aicompanion-1.0.0.jar"

if not exist "%JAR%" (
    echo aicompanion: not built yet. Run: mvn clean package -q
    exit /b 1
)

java --enable-native-access=ALL-UNNAMED -jar "%JAR%" %*
exit /b %ERRORLEVEL%
