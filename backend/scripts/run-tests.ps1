# TrainMate Backend Test Runner
param (
    [string]$JavaHome = "C:\Program Files\Java\jdk-27"
)

$env:JAVA_HOME = $JavaHome
$repoRoot = (Get-Item $PSScriptRoot).Parent.Parent.FullName
$backendDir = Join-Path $repoRoot "backend"

Write-Host "Compiling TrainMate backend..." -ForegroundColor Cyan
& "D:\download\apache-maven-3.10.0-bin\apache-maven-3.10.0\bin\mvn.cmd" -f (Join-Path $backendDir "pom.xml") test-compile
if ($LASTEXITCODE -ne 0) {
    Write-Error "Backend compilation failed."
    exit $LASTEXITCODE
}

Write-Host "Assembling classpath and running test suite..." -ForegroundColor Cyan
$m2Dir = "C:\Users\ronak\.m2\repository"
$jars = (Get-ChildItem -Recurse -File $m2Dir -Filter "*.jar" | Select-Object -ExpandProperty FullName) -join ";"
$classesDir = Join-Path $backendDir "target\classes"
$testClassesDir = Join-Path $backendDir "target\test-classes"
$cp = "$classesDir;$testClassesDir;$jars"

& "$env:JAVA_HOME\bin\java.exe" -cp "$cp" app.trainmate.TestRunner
exit $LASTEXITCODE
