# Build and Package Release APKs for VMusic
$ErrorActionPreference = "Stop"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Building VMusic Release APKs Locally" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# 1. Read version from build.gradle.kts
$versionLine = Get-Content "build.gradle.kts" | Where-Object { $_ -match 'version\s*=\s*"([^"]+)"' } | Select-Object -First 1
if ($versionLine -match 'version\s*=\s*"([^"]+)"') {
    $version = $matches[1]
} else {
    $version = "1.0.0"
}

Write-Host "`n[1/4] Target Version: v$version" -ForegroundColor Green

# 2. Run Gradle assembleRelease
Write-Host "`n[2/4] Running Gradle assembleRelease..." -ForegroundColor Yellow
.\gradlew :app:assembleRelease --no-configuration-cache
if ($LASTEXITCODE -ne 0) {
    Write-Error "Gradle build failed with exit code $LASTEXITCODE"
    exit $LASTEXITCODE
}

# 3. Create destination directory and copy/rename APKs
$outDir = "release_apks"
if (!(Test-Path $outDir)) {
    New-Item -ItemType Directory -Path $outDir | Out-Null
}

$arm64Src = "app\build\outputs\apk\release\app-arm64-v8a-release.apk"
$arm32Src = "app\build\outputs\apk\release\app-armeabi-v7a-release.apk"

$arm64Dst = "$outDir\vmusic-v$version-arm64-v8a.apk"
$arm32Dst = "$outDir\vmusic-v$version-armeabi-v7a.apk"

Copy-Item $arm64Src $arm64Dst -Force
Copy-Item $arm32Src $arm32Dst -Force

Write-Host "`n[3/4] Exported release APKs to: $outDir\" -ForegroundColor Green
Write-Host "  - vmusic-v$version-arm64-v8a.apk ($([math]::Round((Get-Item $arm64Dst).Length / 1MB, 2)) MB)"
Write-Host "  - vmusic-v$version-armeabi-v7a.apk ($([math]::Round((Get-Item $arm32Dst).Length / 1MB, 2)) MB)"

# 4. Generate SHA256 Checksums
Write-Host "`n[4/4] Computing SHA-256 Checksums..." -ForegroundColor Yellow
$hash64 = (Get-FileHash $arm64Dst -Algorithm SHA256).Hash.ToLower()
$hash32 = (Get-FileHash $arm32Dst -Algorithm SHA256).Hash.ToLower()

$checksumContent = @"
$hash64  vmusic-v$version-arm64-v8a.apk
$hash32  vmusic-v$version-armeabi-v7a.apk
"@

$checksumFile = "$outDir\checksums.txt"
Set-Content -Path $checksumFile -Value $checksumContent -Encoding utf8

Write-Host "`nChecksums saved to $checksumFile" -ForegroundColor Green
Get-Content $checksumFile

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "  Build Complete! Ready for Release." -ForegroundColor Cyan
Write-Host "  Upload files in '$outDir\' to GitHub Releases: https://github.com/yashrajrocxx/VMusic/releases" -ForegroundColor Cyan
Write-Host "========================================`n" -ForegroundColor Cyan
