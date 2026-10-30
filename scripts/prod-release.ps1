<#
.SYNOPSIS
    Promote a tested build to a production release: a signed release APK of a test-release tag, checked,
    copied to releases\, and marked with a prod/vX.Y.Z tag. Never makes a new version, never pushes, never installs.

.DESCRIPTION
    Refuses at the first failure and says why:

      1. HEAD is on main and the tree is clean (CHANGELOG.md excepted: it is not in the APK)
      2. the release is a vX.Y.Z tag made by test-release: the latest on HEAD, or -Version X.Y.Z. HEAD is that
         tag, or past it only by commits that touch nothing but CHANGELOG.md, so the APK is exactly the tag's tree
      3. -ServerDeployed: your statement that production runs the server this build needs (the script cannot know)
      4. the release key is configured (keystore.properties or the RELEASE_* env vars) and its file exists
      5. CHANGELOG.md has a section for this version that no longer says DRAFT. A missing one is drafted from
         git log since the previous prod/ tag and the script stops for you to edit it
      6. testDebugUnitTest + assembleRelease, the release build's servers are production's, apksigner verify
         accepts the APK, and aapt2 reads the tag's versionName and versionCode back out of it
      7. the APK is copied to releases\adhd-tracker-X.Y.Z-<code>.apk, its SHA-256 printed, the annotated tag
         prod/vX.Y.Z put on the release commit with the changelog section as its message, and an uncommitted
         CHANGELOG.md committed on its own

    -DryRun does every check and the build and writes nothing: no draft, no copy, no tag, no commit.
    The rules are in CLAUDE.md, section "Versioning".

.EXAMPLE
    ./scripts/prod-release.ps1 -ServerDeployed -DryRun      # everything but the writes
    ./scripts/prod-release.ps1 -ServerDeployed              # release the latest test-release tag on HEAD
    ./scripts/prod-release.ps1 -ServerDeployed -Version 1.5.0
#>
[CmdletBinding()]
param(
    [string] $Version,
    [switch] $ServerDeployed,
    [switch] $DryRun
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$gradleFile = Join-Path $root 'app\build.gradle.kts'
$changelogFile = Join-Path $root 'CHANGELOG.md'
$keystoreFile = Join-Path $root 'keystore.properties'
$apk = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'
$unsignedApk = Join-Path $root 'app\build\outputs\apk\release\app-release-unsigned.apk'
$buildConfigFile = Join-Path $root 'app\build\generated\source\buildConfig\release\com\timeorganizer\usagetracker\BuildConfig.java'
$resValuesFile = Join-Path $root 'app\build\generated\res\resValues\release\values\gradleResValues.xml'
$releasesDir = Join-Path $root 'releases'
$prodServer = 'https://backend.adhdtimeorganizer.com/'
$prodWebApp = 'https://adhdtimeorganizer.com/'
# The release key's certificate (apksigner's "certificate SHA-256 digest"). A different key forces every installed
# copy to be uninstalled to update, so a build signed with any other is refused. Public, not a secret.
$expectedCertSha256 = ''
$utf8 = New-Object System.Text.UTF8Encoding($false)
$mode = if ($DryRun) { 'prod-release (dry run)' } else { 'prod-release' }

function Fail([string] $message) {
    Write-Host "${mode}: $message" -ForegroundColor Red
    exit 1
}

function Say([string] $message) {
    Write-Host "${mode}: $message" -ForegroundColor Cyan
}

# Native tools write progress to stderr; under Windows PowerShell 5.1 with 'Stop' that would throw mid-run.
# No named parameter: a git flag like -n would bind to it by prefix.
function Invoke-Native {
    $ErrorActionPreference = 'Continue'
    $exe = $args[0]
    $rest = @($args | Select-Object -Skip 1)
    $output = & $exe @rest 2>&1 | ForEach-Object { "$_" }
    return [pscustomobject] @{ Exit = $LASTEXITCODE; Output = @($output) }
}

# Not named Git: command names are case-insensitive, so `& git` inside a function called Git calls itself forever.
function Invoke-Git {
    $result = Invoke-Native 'git' -C $root @args
    if ($result.Exit -ne 0) { Fail "git $($args -join ' ') failed:`n$($result.Output -join "`n")" }
    return $result.Output
}

function Find-BuildTools {
    $sdks = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
    $props = Join-Path $root 'local.properties'
    if (Test-Path $props) {
        $line = Select-String -Path $props -Pattern '^sdk\.dir=(.+)$' | Select-Object -First 1
        if ($line) { $sdks += $line.Matches[0].Groups[1].Value -replace '\\\\', '\' -replace '\\:', ':' }
    }
    $sdks += Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    foreach ($sdk in $sdks) {
        if (-not $sdk) { continue }
        $tools = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue |
            Sort-Object { [version] ($_.Name -replace '[^0-9.].*$', '') } | Select-Object -Last 1
        if ($tools -and (Test-Path (Join-Path $tools.FullName 'apksigner.bat'))) { return $tools.FullName }
    }
    return $null
}

function Get-VersionKey([string] $tag) {
    $m = [regex]::Match($tag, '^(?:prod/)?v(\d+)\.(\d+)\.(\d+)$')
    return [version] "$($m.Groups[1].Value).$($m.Groups[2].Value).$($m.Groups[3].Value)"
}

# The section "## X.Y.Z ..." up to the next "## ", or $null.
function Get-ChangelogSection([string] $text, [string] $name) {
    $m = [regex]::Match($text, "(?ms)^## $([regex]::Escape($name))\b.*?(?=^## |\z)")
    if ($m.Success) { return $m.Value.TrimEnd() }
    return $null
}

# git log between the previous production tag and this one, grouped by the conventional-commit prefix.
function New-ChangelogDraft([string] $name, [int] $code, [string] $tag, [string] $previous) {
    $range = if ($previous) { "$previous..$tag" } else { $tag }
    $subjects = Invoke-Git log --no-merges --format=%s $range
    $groups = [ordered] @{ 'Features' = @(); 'Fixes' = @(); 'Other' = @() }
    foreach ($subject in $subjects) {
        if ($subject -match '^chore: version ') { continue }
        $m = [regex]::Match($subject, '^(\w+)(?:\(([^)]*)\))?!?:\s*(.+)$')
        if ($m.Success) {
            $type = $m.Groups[1].Value
            if ($type -eq 'docs') { continue }
            $text = if ($m.Groups[2].Success -and $m.Groups[2].Value) { "$($m.Groups[2].Value): $($m.Groups[3].Value)" } else { $m.Groups[3].Value }
        } else {
            $type = ''
            $text = $subject
        }
        $group = switch ($type) { 'feat' { 'Features' } 'fix' { 'Fixes' } default { 'Other' } }
        $groups[$group] += "- $text"
    }
    $since = if ($previous) { "since $previous" } else { 'the first production release' }
    $lines = @("## $name (code $code), $(Get-Date -Format 'yyyy-MM-dd')", '',
        "DRAFT - drafted by prod-release from git log ($since). Edit it, then delete this line.")
    foreach ($group in $groups.Keys) {
        if ($groups[$group].Count -eq 0) { continue }
        $lines += ''
        $lines += "### $group"
        $lines += $groups[$group]
    }
    return ($lines -join "`n")
}

# ---- 1. main, clean --------------------------------------------------------------------------------------
$branch = (Invoke-Git rev-parse --abbrev-ref HEAD) | Select-Object -First 1
if ($branch -ne 'main') { Fail "HEAD is on '$branch'; a production release is made from main" }

$dirty = @(Invoke-Git status --porcelain | Where-Object { $_ -and $_.Substring(3) -ne 'CHANGELOG.md' })
if ($dirty.Count -gt 0) {
    Fail ("the tree is not clean, and the release must be exactly a tested tag:`n$($dirty -join "`n")`n" +
        'Commit, stash or remove them first (only CHANGELOG.md may be uncommitted).')
}

# ---- 2. the tag ------------------------------------------------------------------------------------------
$head = (Invoke-Git rev-parse HEAD) | Select-Object -First 1
if ($Version) {
    if ($Version -notmatch '^\d+\.\d+\.\d+$') { Fail "-Version must be X.Y.Z, got '$Version'" }
    $tag = "v$Version"
    if (-not (Invoke-Git tag -l $tag)) { Fail "there is no tag $tag; test-release makes the vX.Y.Z tags" }
} else {
    $onHead = @(Invoke-Git tag --points-at HEAD | Where-Object { $_ -match '^v\d+\.\d+\.\d+$' })
    if ($onHead.Count -eq 0) {
        Fail ('HEAD is not a test-release tag (vX.Y.Z), so it is not what you tested. ' +
            'Run scripts/test-release.ps1 to make one, or name an earlier one with -Version.')
    }
    $tag = $onHead | Sort-Object { Get-VersionKey $_ } | Select-Object -Last 1
}
$name = $tag.Substring(1)
if ((Invoke-Git cat-file -t $tag | Select-Object -First 1) -ne 'tag') { Fail "$tag is a lightweight tag; test-release makes annotated ones" }
$tagCommit = (Invoke-Git rev-list -n 1 $tag) | Select-Object -First 1

if ($tagCommit -ne $head) {
    $isAncestor = Invoke-Native 'git' -C $root merge-base --is-ancestor $tagCommit $head
    if ($isAncestor.Exit -ne 0) { Fail "$tag is not on main's history at HEAD" }
    $changed = @(Invoke-Git diff --name-only $tagCommit $head | Where-Object { $_ -and $_ -ne 'CHANGELOG.md' })
    if ($changed.Count -gt 0) {
        Fail ("HEAD has changed more than CHANGELOG.md since $tag, so the build would not be what you tested:`n" +
            "$($changed -join "`n")`nCheck out $tag (or run test-release for a new version) first.")
    }
}

$prodTag = "prod/$tag"
if (Invoke-Git tag -l $prodTag) { Fail "$prodTag already exists: $name was released already" }

$gradle = [System.IO.File]::ReadAllText($gradleFile)
$codeMatch = [regex]::Match($gradle, 'versionCode\s*=\s*(\d+)')
$nameMatch = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"')
if (-not $codeMatch.Success -or -not $nameMatch.Success) { Fail 'app/build.gradle.kts must hold versionCode = N and versionName = "X.Y.Z" literally' }
$code = [int] $codeMatch.Groups[1].Value
if ($nameMatch.Groups[1].Value -ne $name) { Fail "app/build.gradle.kts says $($nameMatch.Groups[1].Value), the tag $tag" }
Say "releasing $name (code $code) from $tag ($($tagCommit.Substring(0, 8)))"

# ---- 3. the server ---------------------------------------------------------------------------------------
if (-not $ServerDeployed) {
    Fail ("-ServerDeployed is required: your statement that production runs the server this build needs - the " +
        "auth lost-response recovery (refresh replay after the 5 s grace), the evening-review routes, and every " +
        "tracker/android route the build calls (the server's android-tracker work). Against a server without the " +
        "recovery, a replayed refresh revokes every session of the account. Deploy the server first, then pass " +
        "-ServerDeployed.")
}

# ---- 4. the key ------------------------------------------------------------------------------------------
# Only the path and whether the passwords are set are read here; the passwords are Gradle's alone.
$storeFile = $env:RELEASE_STORE_FILE
$passwordsSet = [bool] ($env:RELEASE_STORE_PASSWORD -and $env:RELEASE_KEY_PASSWORD)
if (Test-Path $keystoreFile) {
    $props = [System.IO.File]::ReadAllText($keystoreFile)
    $storeMatch = [regex]::Match($props, '(?m)^\s*storeFile\s*=\s*(\S.*?)\s*$')
    if ($storeMatch.Success) { $storeFile = $storeMatch.Groups[1].Value }
    $passwordsSet = ($props -match '(?m)^\s*storePassword\s*=\s*\S') -and ($props -match '(?m)^\s*keyPassword\s*=\s*\S')
}
if (-not $storeFile) { Fail 'no release key: keystore.properties (storeFile, keyAlias, storePassword, keyPassword) or the RELEASE_* env vars' }
if (-not (Test-Path $storeFile)) { Fail "the release key $storeFile does not exist" }
if (-not $passwordsSet) { Fail 'the release key has no storePassword or keyPassword set' }

# ---- 5. the changelog ------------------------------------------------------------------------------------
$changelog = if (Test-Path $changelogFile) { [System.IO.File]::ReadAllText($changelogFile) } else { '' }
$section = Get-ChangelogSection $changelog $name
$changelogProblem = $null
if (-not $section) {
    $previous = @(Invoke-Git tag -l 'prod/v*' --merged $tagCommit | Where-Object { $_ -match '^prod/v\d+\.\d+\.\d+$' } |
        Sort-Object { Get-VersionKey $_ } | Select-Object -Last 1)
    $draft = New-ChangelogDraft $name $code $tag $(if ($previous.Count -gt 0) { $previous[0] } else { $null })
    if ($DryRun) {
        $changelogProblem = "CHANGELOG.md has no section for $name; a real run writes this draft and stops:`n`n$draft"
    } else {
        # Newest first: the draft goes between the file's intro and the latest section.
        $intro = [regex]::Match($changelog, '(?s)\A.*?(?=^## |\z)', 'Multiline').Value.TrimEnd()
        if (-not $intro) { $intro = "# Changelog" }
        $rest = $changelog.Substring([regex]::Match($changelog, '(?s)\A.*?(?=^## |\z)', 'Multiline').Length).Trim()
        $text = "$intro`n`n$draft`n" + $(if ($rest) { "`n$rest`n" } else { '' })
        [System.IO.File]::WriteAllText($changelogFile, $text, $utf8)
        Fail "CHANGELOG.md had no section for $name; a draft is written there. Edit it, delete its DRAFT line, and run again."
    }
} elseif ($section -match '(?m)^DRAFT\b') {
    $changelogProblem = "CHANGELOG.md's section for $name still says DRAFT; edit it and delete that line"
    if (-not $DryRun) { Fail $changelogProblem }
}

# ---- 6. build and check ----------------------------------------------------------------------------------
$tools = Find-BuildTools
if (-not $tools) { Fail 'Android build-tools (apksigner, aapt2) not found; set ANDROID_HOME' }

# No APK of an earlier build may pass for this one: removed outputs make Gradle package again.
Remove-Item $apk, $unsignedApk -ErrorAction SilentlyContinue
Push-Location $root
try {
    & .\gradlew.bat testDebugUnitTest assembleRelease
    $gradleExit = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($gradleExit -ne 0) { Fail 'the tests or the release build failed' }
if (-not (Test-Path $apk)) {
    if (Test-Path $unsignedApk) { Fail 'the release came out unsigned: Gradle found no usable key (see its warning above)' }
    Fail "no release APK at $apk"
}

# The servers this release talks to, as Gradle generated them (R8 inlines BuildConfig, so the APK can't be read).
$buildConfig = [System.IO.File]::ReadAllText($buildConfigFile)
$server = [regex]::Match($buildConfig, 'String SERVER_ROOT = "([^"]*)"').Groups[1].Value
$webApp = [regex]::Match($buildConfig, 'String WEB_APP_ROOT = "([^"]*)"').Groups[1].Value
$googleClient = [regex]::Match($buildConfig, 'String GOOGLE_SERVER_CLIENT_ID = "([^"]*)"').Groups[1].Value
if ($server -ne $prodServer) { Fail "the release build talks to '$server', not production ($prodServer): fix release's environment(...) in app/build.gradle.kts" }
if ($webApp -ne $prodWebApp) { Fail "the release build opens the web app '$webApp', not production's ($prodWebApp)" }
$assetStatements = [System.IO.File]::ReadAllText($resValuesFile)
if ($assetStatements -notmatch [regex]::Escape("${prodWebApp}.well-known/assetlinks.json")) {
    Fail "the release build's asset_statements don't name ${prodWebApp}.well-known/assetlinks.json"
}

$verify = Invoke-Native (Join-Path $tools 'apksigner.bat') verify --print-certs $apk
if ($verify.Exit -ne 0) { Fail "apksigner refused the APK:`n$($verify.Output -join "`n")" }
$certLine = $verify.Output | Where-Object { $_ -match 'certificate SHA-256 digest:' } | Select-Object -First 1
$cert = if ($certLine) { ($certLine -split 'digest:\s*')[1].Trim() } else { '' }
if (-not $cert) { Fail "apksigner printed no certificate:`n$($verify.Output -join "`n")" }
if ($expectedCertSha256 -and $cert -ne $expectedCertSha256) {
    Fail "the APK is signed by $cert, not the release key ($expectedCertSha256): every install would have to be uninstalled to take it"
}

$badging = Invoke-Native (Join-Path $tools 'aapt2.exe') dump badging $apk
$package = $badging.Output | Where-Object { $_ -match '^package:' } | Select-Object -First 1
if ($package -notmatch "versionCode='$code'" -or $package -notmatch "versionName='$([regex]::Escape($name))'") {
    Fail "the APK says $package, the tag $name (code $code)"
}
$hash = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLowerInvariant()

if ($changelogProblem) { Fail $changelogProblem }

# ---- 7. copy and tag -------------------------------------------------------------------------------------
$fileName = "adhd-tracker-$name-$code.apk"
if ($DryRun) {
    Write-Host ''
    Write-Host "${mode}: every check passed; nothing written. $name (code $code)" -ForegroundColor Green
    Write-Host "  would copy  releases\$fileName"
    Write-Host "  sha-256     $hash"
    Write-Host "  signed by   $cert"
    Write-Host "  server      $server"
    Write-Host "  would tag   $prodTag on $($tagCommit.Substring(0, 8))"
    if (-not $expectedCertSha256) { Write-Host "  note        no expected certificate is set in this script yet" -ForegroundColor Yellow }
    if (-not $googleClient) { Write-Host '  note        the release has no Google client id: its sign-in button is hidden' -ForegroundColor Yellow }
    exit 0
}

New-Item -ItemType Directory -Force $releasesDir | Out-Null
$target = Join-Path $releasesDir $fileName
if (Test-Path $target) { Fail "$target already exists" }
Copy-Item $apk $target

$messageFile = [System.IO.Path]::GetTempFileName()
try {
    [System.IO.File]::WriteAllText($messageFile, "Production release $name (code $code)`n`n$section`n", $utf8)
    Invoke-Git tag -a $prodTag $tagCommit -F $messageFile | Out-Null
} finally {
    Remove-Item $messageFile -ErrorAction SilentlyContinue
}

$changelogCommit = $null
if (Invoke-Git status --porcelain CHANGELOG.md) {
    # Native call, not Invoke-Git: a function's $args may swallow the "--" that limits the commit to one file.
    $commitOut = & git -C $root commit -m "docs: changelog $name" -- CHANGELOG.md 2>&1
    if ($LASTEXITCODE -ne 0) { Fail "the tag is made, but committing CHANGELOG.md failed:`n$commitOut" }
    $changelogCommit = (Invoke-Git rev-parse --short HEAD) | Select-Object -First 1
}

Write-Host ''
Write-Host "${mode}: done. $name (code $code)" -ForegroundColor Green
Write-Host "  apk         releases\$fileName"
Write-Host "  sha-256     $hash"
Write-Host "  signed by   $cert"
Write-Host "  server      $server"
Write-Host "  tag         $prodTag on $($tagCommit.Substring(0, 8))   (nothing pushed)"
if ($changelogCommit) { Write-Host "  changelog   committed as $changelogCommit" }
if (-not $googleClient) { Write-Host '  note        the release has no Google client id: its sign-in button is hidden' -ForegroundColor Yellow }
Write-Host ''
Write-Host 'To publish the tags:'
Write-Host "  git push origin $tag"
Write-Host "  git push origin $prodTag"
