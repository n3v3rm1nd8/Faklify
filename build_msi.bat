@echo off
setlocal enabledelayedexpansion

echo 🧹 1. Limpiando y preparando archivos...
if exist "empaquetado_temp" rd /s /q "empaquetado_temp"
mkdir "empaquetado_temp\lib"

echo 🏗️ 2. Compilando y limpiando dependencias...
:: Borramos la carpeta de dependencias de Gradle para que la regenere limpia
if exist "app\build\dependencies" rd /s /q "app\build\dependencies"

call gradlew.bat :app:copyDependencies :app:assemble

:: Borramos cualquier posible duplicado manual en la carpeta de destino
del /q "empaquetado_temp\lib\jna-platform-jpms-*.jar" 2>nul

:: Ahora copiamos los archivos limpios
copy "app\build\libs\*.jar" "empaquetado_temp\lib\"
xcopy /E /I /Y "app\build\dependencies" "empaquetado_temp\lib"

echo 📦 3. Integrando vlc_runtime (Motor de Audio)...
xcopy /E /I /Y "app\vlc_runtime" "empaquetado_temp\vlc_runtime"

echo 🛠️ 4. Generando MSI Fusionado...
if not exist "dist" mkdir "dist"

:: Buscamos tu JAR (probablemente app.jar o pruebasYTDLP.jar)
for %%f in ("empaquetado_temp\lib\*.jar") do set "JAR_NAME=%%~nxf"

"C:\Program Files\Java\jdk-21\bin\jpackage.exe" ^
  --type msi ^
  --name "Faklify" ^
  --dest "dist" ^
  --input "empaquetado_temp" ^
  --main-jar "lib\%JAR_NAME%" ^
  --main-class com.faklify.Faklify ^
  --module-path "empaquetado_temp\lib" ^
  --add-modules javafx.controls,javafx.media,java.logging,jdk.unsupported,java.desktop,java.sql,java.net.http,jdk.charsets ^
  --icon "Faklify.ico" ^
  --win-dir-chooser ^
  --win-menu ^
  --win-shortcut ^
  --java-options "-Dfile.encoding=UTF-8" ^
  --java-options "--add-opens=java.base/java.lang=ALL-UNNAMED" ^
  --java-options "-Djna.nosys=true" ^
  --java-options "-Djna.nounpack=true" ^
  --java-options "-Djna.library.path=$APPDIR\vlc_runtime" ^
  --java-options "-Djna.boot.library.path=$APPDIR\vlc_runtime"

echo ✅ ¡MSI Completo generado en la carpeta dist!
rd /s /q "empaquetado_temp"
pause