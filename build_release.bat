@echo off
REM Cree l'APK publie : dist\IPTVPlayerTel-<version>.apk (version = ligne "version=" de version.properties).
REM Avant : augmenter la version dans version.properties. L'APK est signe avec la cle de keystore.properties
REM (hors depot) : sans elle, Android refuse la mise a jour des telephones deja installes.
cd /d "%~dp0"
if not defined JAVA_HOME set "JAVA_HOME=%LOCALAPPDATA%\Android\jdk-17"
if not exist keystore.properties (
  echo ERREUR : keystore.properties introuvable ^(cle de signature, voir README^).
  pause
  exit /b 1
)
set VERSION=
for /f "tokens=2 delims==" %%v in ('findstr /b /c:"version=" version.properties') do set VERSION=%%v
set VERSION=%VERSION: =%
if not defined VERSION goto erreur
echo Version %VERSION%
call "%~dp0gradlew.bat" assembleRelease || goto erreur
if not exist dist mkdir dist
copy /y app\build\outputs\apk\release\app-release.apk "dist\IPTVPlayerTel-%VERSION%.apk" >nul || goto erreur
echo.
echo Termine. APK : dist\IPTVPlayerTel-%VERSION%.apk
echo Sur GitHub : release avec le tag v%VERSION% et ce fichier joint.
pause
exit /b 0

:erreur
echo.
echo ECHEC de la creation de l'APK.
pause
exit /b 1
