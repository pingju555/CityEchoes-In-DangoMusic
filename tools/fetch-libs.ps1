<#
.SYNOPSIS
    Fetch the three build-time dependency jars into libs/.

.DESCRIPTION
    The jars are not committed (see .gitignore), so a fresh clone cannot build
    until they are in place. This script downloads them from their upstream
    sources and checks their SHA-256.

.PARAMETER Force
    Re-download even if the file already exists.

.PARAMETER VerifyOnly
    No network. Just check what is already in libs\.

.PARAMETER Strict
    Treat any checksum surprise as fatal. Useful in CI.

.PARAMETER DangoVersion
    DangoMusic release to fetch. Default 1.7.3.

.PARAMETER MixinVersion
    dev.architectury:mixin-patched version. Default 0.8.5.12.

.PARAMETER LibsDir
    Output directory. Default <repo>\libs.

.EXAMPLE
    .\tools\fetch-libs.ps1

.EXAMPLE
    .\tools\fetch-libs.ps1 -VerifyOnly -Strict
#>

[CmdletBinding()]
param(
    [switch]$Force,
    [switch]$VerifyOnly,
    [switch]$Strict,
    [string]$DangoVersion = '1.7.3',
    [string]$MixinVersion = '0.8.5.12',
    [string]$LibsDir = ''
)

$ErrorActionPreference = 'Stop'

# Windows PowerShell 5.1 defaults to TLS 1.0 on some machines; GitHub needs 1.2+.
try {
    [Net.ServicePointManager]::SecurityProtocol =
        [Net.SecurityProtocolType]::Tls12 -bor [Net.SecurityProtocolType]::Tls11
} catch {
    Write-Warning "could not raise the TLS version: $($_.Exception.Message)"
}

$RootDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
if ([string]::IsNullOrWhiteSpace($LibsDir)) {
    $LibsDir = Join-Path $RootDir 'libs'
}

$ForgeJar = "DangoMusic-$DangoVersion+1.20.1-forge.jar"
$NeoJar   = "DangoMusic-$DangoVersion+1.21.1-neoforge.jar"
$MixinJar = "mixin-patched-$MixinVersion.jar"

# Known-good DangoMusic 1.7.3 builds. The upstream author re-uploaded the release
# assets after the first publication (the rebuild touched NeteaseApi, GlassBackdrop
# and the zh_cn translations), so more than one hash is legitimate for the same
# version. The six classes this addon injects into are byte-identical in both, so
# either build works -- hence an unknown hash is a warning, not a failure.
$KnownForge = @(
    '40044c8dbcdc307082fa267309f50272f98f4f6ebbf0ddbfc043bdc2e8dd81b6'
    '403c665f4f8bc02594bf891cf708bab33c0a0c69c97baf9607d70ecb2203360a'
)
$KnownNeo = @(
    '789e32b7bcfa54c5e8514106af20f703a17b5c9f8a0264a642c12dbac1f60873'
    '18d1a08582df3cf998141832e31937be39281aa951f33abcb333bc8779602dc0'
)
# mixin-patched comes from Architectury's own maven and never changes under a
# released version, so its hash is authoritative.
$KnownMixin = @('936d515ac41de42f5c0c6bb0fedaa470c9c404cae321f730a5c060d5721d41ca')

function Get-KnownHashes {
    param([string]$Kind)
    if ($Kind -eq 'forge') { if ($DangoVersion -eq '1.7.3') { return $KnownForge } }
    if ($Kind -eq 'neo')   { if ($DangoVersion -eq '1.7.3') { return $KnownNeo } }
    if ($Kind -eq 'mixin') { if ($MixinVersion -eq '0.8.5.12') { return $KnownMixin } }
    return @()
}

function Get-DangoUrl {
    param([string]$Name)
    # GitHub asset names contain '+', which must be percent-encoded.
    $encoded = $Name.Replace('+', '%2B')
    return "https://github.com/Sorihiko/DangoMusic/releases/download/v$DangoVersion/$encoded"
}

function Get-MixinUrl {
    return "https://maven.architectury.dev/dev/architectury/mixin-patched/$MixinVersion/mixin-patched-$MixinVersion.jar"
}

# Test-Jar <path> <kind> <label> <fatal-on-unknown: bool>
# returns $true when the file is present and trusted
function Test-Jar {
    param([string]$Path, [string]$Kind, [string]$Label, [bool]$Fatal)

    if (-not (Test-Path -LiteralPath $Path)) {
        Write-Host "  $Label : missing" -ForegroundColor Yellow
        return $false
    }
    if ((Get-Item -LiteralPath $Path).Length -eq 0) {
        Write-Host "  $Label : empty file" -ForegroundColor Yellow
        return $false
    }

    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    $known = Get-KnownHashes -Kind $Kind

    if ($known.Count -eq 0) {
        Write-Host "  $Label : $actual"
        Write-Host '    no reference checksum for this version, accepted as-is'
        return $true
    }
    if ($known -contains $actual) {
        Write-Host "  $Label : ok" -ForegroundColor Green
        return $true
    }

    if ($Fatal -or $Strict) {
        Write-Host "  $Label : checksum mismatch" -ForegroundColor Red
        Write-Host "    actual   $actual"
        Write-Host '    expected one of:'
        foreach ($h in $known) { Write-Host "      $h" }
        return $false
    }

    Write-Host "  $Label : checksum differs from the builds this project was tested against" -ForegroundColor Yellow
    Write-Host "    actual $actual"
    Write-Host '    This is usually a re-uploaded upstream asset. The injection targets are'
    Write-Host '    unchanged in the known rebuilds, but verify in game before shipping.'
    return $true
}

function Save-Jar {
    param([string]$Url, [string]$Destination, [string]$Kind, [string]$Label)

    $part = "$Destination.part"

    if ((Test-Path -LiteralPath $Destination) -and (-not $Force)) {
        if (Test-Jar -Path $Destination -Kind $Kind -Label $Label -Fatal $false) {
            Write-Host '    already present, skipped'
            return
        }
        Write-Host '    existing file cannot be used, re-downloading' -ForegroundColor Yellow
    }

    Write-Host "    GET $Url"
    if (Test-Path -LiteralPath $part) { Remove-Item -LiteralPath $part -Force }

    try {
        Invoke-WebRequest -Uri $Url -OutFile $part -UseBasicParsing -MaximumRedirection 10
    } catch {
        if (Test-Path -LiteralPath $part) { Remove-Item -LiteralPath $part -Force }
        throw "download failed: $Url -- $($_.Exception.Message)"
    }

    if ((Get-Item -LiteralPath $part).Length -eq 0) {
        Remove-Item -LiteralPath $part -Force
        throw "downloaded file is empty: $Url"
    }

    # A jar that does not start with the ZIP magic means an HTML error page or a
    # captive portal answered instead of the file.
    $bytes = [System.IO.File]::ReadAllBytes($part)
    if ($bytes.Length -lt 2 -or $bytes[0] -ne 0x50 -or $bytes[1] -ne 0x4B) {
        Remove-Item -LiteralPath $part -Force
        throw "not a jar (no PK header), the response was probably an error page: $Url"
    }

    if (-not (Test-Jar -Path $part -Kind $Kind -Label $Label -Fatal $false)) {
        Remove-Item -LiteralPath $part -Force
        throw "downloaded file failed verification: $Url"
    }

    Move-Item -LiteralPath $part -Destination $Destination -Force
    Write-Host "    saved to $Destination"
}

if ($DangoVersion -ne '1.7.3') {
    Write-Warning "DangoMusic $DangoVersion is not the version this project was tested against."
    Write-Warning 'Its checksums are not enforced; the injection points may not line up.'
    Write-Warning 'Check logs\latest.log for [CityEchoes] afterwards.'
}

Write-Host "libs directory: $LibsDir"

if ($VerifyOnly) {
    Write-Host 'verify only:'
    $ok = $true
    if (-not (Test-Jar -Path (Join-Path $LibsDir $ForgeJar) -Kind 'forge' -Label $ForgeJar -Fatal $false)) { $ok = $false }
    if (-not (Test-Jar -Path (Join-Path $LibsDir $NeoJar)   -Kind 'neo'   -Label $NeoJar   -Fatal $false)) { $ok = $false }
    if (-not (Test-Jar -Path (Join-Path $LibsDir $MixinJar) -Kind 'mixin' -Label $MixinJar -Fatal $true))  { $ok = $false }
    if (-not $ok) {
        throw 'libs\ is incomplete or has been modified'
    }
    Write-Host 'all three jars present' -ForegroundColor Green
    exit 0
}

if (-not (Test-Path -LiteralPath $LibsDir)) {
    New-Item -ItemType Directory -Path $LibsDir | Out-Null
}

Write-Host "[1/3] $ForgeJar"
Save-Jar -Url (Get-DangoUrl -Name $ForgeJar) -Destination (Join-Path $LibsDir $ForgeJar) -Kind 'forge' -Label $ForgeJar

Write-Host "[2/3] $NeoJar"
Save-Jar -Url (Get-DangoUrl -Name $NeoJar) -Destination (Join-Path $LibsDir $NeoJar) -Kind 'neo' -Label $NeoJar

Write-Host "[3/3] $MixinJar"
Save-Jar -Url (Get-MixinUrl) -Destination (Join-Path $LibsDir $MixinJar) -Kind 'mixin' -Label $MixinJar

Write-Host ''
Write-Host 'done. next: .\gradlew.bat build'
Write-Host '  (or .\gradlew.bat build --init-script init.gradle.kts on a restricted network)'
