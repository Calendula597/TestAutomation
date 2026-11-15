param([string]$Sdk = (Resolve-Path "$PSScriptRoot/../../../../tools/android-sdk").Path)
$ErrorActionPreference = "Stop"
$javaHome = $env:JAVA_HOME
if (-not $javaHome) { throw "Set JAVA_HOME to a JDK before building the demo APK" }
$build = Join-Path $PSScriptRoot "build"
if (Test-Path $build) { Remove-Item -Recurse -Force $build }
$tools = Join-Path $Sdk "build-tools/34.0.0"
$androidJar = Join-Path $Sdk "platforms/android-34/android.jar"
New-Item -ItemType Directory -Force -Path "$build/classes", "$build/dex" | Out-Null
$sources = (Get-ChildItem -Recurse "$PSScriptRoot/src" -Filter *.java).FullName
& javac --release 11 -classpath $androidJar -d "$build/classes" $sources
if ($LASTEXITCODE) { throw "javac failed" }
$classes = (Get-ChildItem -Recurse "$build/classes" -Filter *.class).FullName
& "$tools/d8.bat" --lib $androidJar --output "$build/dex" $classes
if ($LASTEXITCODE) { throw "d8 failed" }
& "$tools/aapt.exe" package -f -M "$PSScriptRoot/AndroidManifest.xml" -I $androidJar -F "$build/unsigned.apk"
if ($LASTEXITCODE) { throw "aapt failed" }
Push-Location "$build/dex"
& "$tools/aapt.exe" add "$build/unsigned.apk" classes.dex
Pop-Location
if ($LASTEXITCODE) { throw "dex packaging failed" }
& (Join-Path $javaHome.Trim() "bin/keytool.exe") -genkeypair -keystore "$build/demo.keystore" -storepass android -keypass android -alias demo -dname "CN=Local Android Demo" -keyalg RSA -keysize 2048 -validity 10000
if ($LASTEXITCODE) { throw "demo signing key failed" }
& "$tools/apksigner.bat" sign --ks "$build/demo.keystore" --ks-pass pass:android --out "$build/demo.apk" "$build/unsigned.apk"
if ($LASTEXITCODE) { throw "APK signing failed" }
& "$tools/apksigner.bat" verify --verbose "$build/demo.apk"
if ($LASTEXITCODE) { throw "APK verification failed" }
