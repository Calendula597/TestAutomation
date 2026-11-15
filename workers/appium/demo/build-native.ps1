$ErrorActionPreference = "Stop"
$compiler = Join-Path $env:WINDIR "Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path $compiler)) { throw ".NET Framework C# compiler was not found" }
$output = Join-Path $PSScriptRoot "bin"
$source = Join-Path $PSScriptRoot "NativeDemo.cs"
$executable = Join-Path $output "NativeDemo.exe"
New-Item -ItemType Directory -Force -Path $output | Out-Null
& $compiler -nologo -target:winexe -platform:x64 -reference:System.Windows.Forms.dll -reference:System.Drawing.dll `
  -out:$executable $source
if ($LASTEXITCODE) { throw "WinForms demo build failed" }
