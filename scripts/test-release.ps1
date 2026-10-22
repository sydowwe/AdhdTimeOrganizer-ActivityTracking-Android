<#
.SYNOPSIS
    Bump the app version, tag it, build it and install it on the phone connected through adb. Never pushes.

.DESCRIPTION
    A test release: for the phone on your desk, against whatever SERVER_ROOT says (the test server on main).
    A release for production is a different script (prod-release, not written yet).

      1. refuses a dirty tree (the tag must describe what gets installed) unless -AllowDirty
      2. versionCode + 1 and versionName bumped (patch by default) in app/build.gradle.kts
      3. testDebugUnitTest + assembleDebug; a failure restores the version file and commits nothing
      4. one commit that touches only app/build.gradle.kts, and an annotated tag vX.Y.Z on it
      5. adb install -r on the one connected phone, then reads the installed version back

    -NoBump builds and installs the current version with no commit and no tag (dev iteration).
    It never uninstalls: an uninstall deletes the outbox. A signature mismatch stops with a message.
    The rules are in CLAUDE.md, section "Versioning".

.EXAMPLE
    ./scripts/test-release.ps1                  # patch bump, tag, build, install
    ./scripts/test-release.ps1 -Bump minor      # 1.1.1 -> 1.2.0
    ./scripts/test-release.ps1 -Version 2.0.0   # explicit versionName (versionCode is still +1)
    ./scripts/test-release.ps1 -NoBump          # just rebuild and reinstall
#>
[CmdletBinding()]
param(
    [ValidateSet('patch', 'minor', 'major')]
    [string] $Bump = 'patch',
    [string] $Version,
    [switch] $NoBump,
    [switch] $NoInstall,
    [switch] $SkipTests,
    [switch] $AllowDirty,
    [string] $Serial,
    [string] $Trailer
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$gradleFile = Join-Path $root 'app\build.gradle.kts'
$constantsFile = Join-Path $root 'app\src\main\java\com\timeorganizer\usagetracker\util\Constants.kt'
$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
$prodRoot = 'https://backend.adhdtimeorganizer.com/'
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Fail([string] $message) {
    Write-Host "test-release: $message" -ForegroundColor Red
    exit 1
}

# Not named Git: command names are case-insensitive, so `& git` inside a function called Git calls itself forever.
function Invoke-Git {
    $output = & git -C $root @args 2>&1
    if ($LASTEXITCODE -ne 0) { Fail "git $($args -join ' ') failed:`n$output" }
    return $output
}

# ---- where the build points -------------------------------------------------------------------------------
$constants = [System.IO.File]::ReadAllText($constantsFile)
$serverMatch = [regex]::Match($constants, '(?m)^\s*const val SERVER_ROOT = "([^"]+)"')
if (-not $serverMatch.Success) { Fail 'cannot find SERVER_ROOT in Constants.kt' }
$serverRoot = $serverMatch.Groups[1].Value
if ($serverRoot -eq $prodRoot) {
    Write-Host "test-release: WARNING - this build talks to PRODUCTION ($serverRoot)." -ForegroundColor Yellow
}

# ---- the current version ----------------------------------------------------------------------------------
$gradle = [System.IO.File]::ReadAllText($gradleFile)
$codeMatch = [regex]::Match($gradle, 'versionCode\s*=\s*(\d+)')
$nameMatch = [regex]::Match($gradle, 'versionName\s*=\s*"(\d+)\.(\d+)\.(\d+)"')
$idMatch = [regex]::Match($gradle, 'applicationId\s*=\s*"([^"]+)"')
if (-not $codeMatch.Success -or -not $nameMatch.Success -or -not $idMatch.Success) {
    Fail 'app/build.gradle.kts must hold versionCode = N, versionName = "X.Y.Z" and applicationId literally'
}
$package = $idMatch.Groups[1].Value
$oldCode = [int] $codeMatch.Groups[1].Value
$parts = @([int] $nameMatch.Groups[1].Value, [int] $nameMatch.Groups[2].Value, [int] $nameMatch.Groups[3].Value)
$oldName = $parts -join '.'

if ($NoBump) {
    $newCode = $oldCode
    $newName = $oldName
} else {
    $newCode = $oldCode + 1
    if ($Version) {
        if ($Version -notmatch '^\d+\.\d+\.\d+$') { Fail "-Version must be X.Y.Z, got '$Version'" }
        if ($Version -eq $oldName) { Fail "-Version $Version is already the current version" }
        $newName = $Version
    } else {
        switch ($Bump) {
            'major' { $parts = @(($parts[0] + 1), 0, 0) }
            'minor' { $parts = @($parts[0], ($parts[1] + 1), 0) }
            'patch' { $parts = @($parts[0], $parts[1], ($parts[2] + 1)) }
        }
        $newName = $parts -join '.'
    }
    $tag = "v$newName"
    if (Invoke-Git tag -l $tag) { Fail "tag $tag already exists" }

    $dirty = Invoke-Git status --porcelain --untracked-files=no
    if ($dirty -and -not $AllowDirty) {
        Fail ("uncommitted changes would be in the APK but not in the tag:`n$($dirty -join "`n")`n" +
            'Commit them first, or pass -AllowDirty to tag anyway.')
    }
    if ($dirty) { Write-Host 'test-release: WARNING - the tag will not contain the uncommitted changes above.' -ForegroundColor Yellow }
}

# ---- the phone --------------------------------------------------------------------------------------------
function Find-Adb {
    foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if ($sdk -and (Test-Path (Join-Path $sdk 'platform-tools\adb.exe'))) { return Join-Path $sdk 'platform-tools\adb.exe' }
    }
    $props = Join-Path $root 'local.properties'
    if (Test-Path $props) {
        $line = Select-String -Path $props -Pattern '^sdk\.dir=(.+)$' | Select-Object -First 1
        if ($line) {
            $sdk = $line.Matches[0].Groups[1].Value -replace '\\\\', '\' -replace '\\:', ':'
            if (Test-Path (Join-Path $sdk 'platform-tools\adb.exe')) { return Join-Path $sdk 'platform-tools\adb.exe' }
        }
    }
    $default = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
    if (Test-Path $default) { return $default }
    return $null
}

$adb = Find-Adb
$target = $null
if (-not $NoInstall) {
    if (-not $adb) {
        Write-Host 'test-release: adb not found - the build will not be installed.' -ForegroundColor Yellow
    } else {
        $ready = @(& $adb devices | Select-String "`tdevice$" | ForEach-Object { ($_.Line -split "`t")[0] })
        if ($Serial) {
            if ($ready -notcontains $Serial) { Fail "device $Serial is not connected and authorized" }
            $target = $Serial
        } elseif ($ready.Count -eq 1) {
            $target = $ready[0]
        } elseif ($ready.Count -gt 1) {
            Fail "several devices are connected ($($ready -join ', ')); pass -Serial"
        } else {
            Write-Host 'test-release: no authorized device - the build will not be installed.' -ForegroundColor Yellow
        }
    }
}

# ---- build ------------------------------------------------------------------------------------------------
$original = $gradle
if (-not $NoBump) {
    $edited = [regex]::new('versionCode\s*=\s*\d+').Replace($gradle, "versionCode = $newCode", 1)
    $edited = [regex]::new('versionName\s*=\s*"[^"]+"').Replace($edited, "versionName = `"$newName`"", 1)
    [System.IO.File]::WriteAllText($gradleFile, $edited, $utf8)
}

Write-Host "test-release: $oldName ($oldCode) -> $newName ($newCode), server $serverRoot" -ForegroundColor Cyan
Push-Location $root
try {
    $tasks = if ($SkipTests) { @('assembleDebug') } else { @('testDebugUnitTest', 'assembleDebug') }
    & .\gradlew.bat @tasks
    $gradleExit = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($gradleExit -ne 0) {
    if (-not $NoBump) { [System.IO.File]::WriteAllText($gradleFile, $original, $utf8) }
    Fail 'the build or the tests failed; the version file is restored and nothing was committed'
}

$aapt = $null
if ($adb) {
    $aapt = Get-ChildItem (Join-Path (Split-Path (Split-Path $adb -Parent) -Parent) 'build-tools') -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name | Select-Object -Last 1
}
if ($aapt) {
    $badging = (& (Join-Path $aapt.FullName 'aapt2.exe') dump badging $apk | Select-String '^package:' | Select-Object -First 1).Line
    if ($badging -notmatch "versionCode='$newCode'" -or $badging -notmatch "versionName='$([regex]::Escape($newName))'") {
        if (-not $NoBump) { [System.IO.File]::WriteAllText($gradleFile, $original, $utf8) }
        Fail "the APK says $badging, expected $newName ($newCode); version file restored"
    }
}

# ---- commit and tag ---------------------------------------------------------------------------------------
$commit = $null
if (-not $NoBump) {
    $message = "chore: version $newName (code $newCode)"
    if ($Trailer) { $message = "$message`n`n$Trailer" }
    # Native call, not Invoke-Git: a function's $args may swallow the "--" that limits the commit to one file.
    $commitOut = & git -C $root commit -m $message -- app/build.gradle.kts 2>&1
    if ($LASTEXITCODE -ne 0) { Fail "git commit failed:`n$commitOut" }
    Invoke-Git tag -a $tag -m "$newName (code $newCode)" | Out-Null
    $commit = (Invoke-Git rev-parse --short HEAD) | Select-Object -First 1
}

# ---- install ----------------------------------------------------------------------------------------------
$installed = 'not installed'
if ($adb -and $target) {
    $out = (& $adb -s $target install -r $apk 2>&1 | ForEach-Object { "$_" }) -join "`n"
    if ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|INSTALL_FAILED_VERSION_DOWNGRADE') {
        Write-Host "test-release: the phone refused the install:`n$out" -ForegroundColor Red
        Write-Host "test-release: not uninstalling - that deletes the outbox. Do it yourself if you accept that:  adb uninstall $package" -ForegroundColor Red
        exit 1
    }
    if ($out -notmatch 'Success') { Fail "adb install failed:`n$out" }
    $line = & $adb -s $target shell dumpsys package $package 2>$null | Select-String 'versionName=' | Select-Object -First 1
    $installed = if ($line) { $line.Line.Trim() } else { 'installed (version unreadable)' }
}

Write-Host ''
Write-Host "test-release: done. $newName (code $newCode)" -ForegroundColor Green
if ($commit) { Write-Host "  commit   $commit   tag $tag   (nothing pushed)" }
Write-Host "  server   $serverRoot"
Write-Host "  phone    $installed"
