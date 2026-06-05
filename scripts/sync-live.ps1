[CmdletBinding()]
param(
    [switch]$StatusOnly
)

$ErrorActionPreference = "Stop"

function Test-GitIgnored {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$RelativePath
    )

    & git -C $Root check-ignore --quiet -- $RelativePath 2>$null
    $LASTEXITCODE -eq 0
}

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$requiredPaths = @(
    "docs/superpowers/PROJECT_CONTEXT.md",
    "docs/superpowers/milestones",
    "docs/superpowers/specs",
    "docs/superpowers/plans",
    "docs/superpowers/issues",
    "docs/agents/triage-labels.md",
    "docs/superpowers/issues/README.md",
    "scripts/sync-live.ps1"
)
$notIgnoredPaths = @(
    "AGENTS.md",
    "docs/agents/triage-labels.md",
    "docs/superpowers/PROJECT_CONTEXT.md",
    "docs/superpowers/issues/README.md"
)

$blocking = @()
$healthy = @()

foreach ($relative in $requiredPaths) {
    $path = Join-Path $repoRoot $relative
    if (Test-Path -LiteralPath $path) {
        $healthy += [pscustomobject]@{
            kind = "required-path"
            artifact = $relative
            message = "Required project artifact is present."
        }
    } else {
        $blocking += [pscustomobject]@{
            kind = "required-path"
            artifact = $relative
            message = "Required project artifact is missing."
        }
    }
}

foreach ($relative in $notIgnoredPaths) {
    if (Test-GitIgnored -Root $repoRoot -RelativePath $relative) {
        $blocking += [pscustomobject]@{
            kind = "git-ignore"
            artifact = $relative
            message = "Source-of-truth path is ignored by Git."
        }
    } else {
        $healthy += [pscustomobject]@{
            kind = "git-ignore"
            artifact = $relative
            message = "Source-of-truth path is not ignored by Git."
        }
    }
}

$issueReadmePath = Join-Path $repoRoot "docs/superpowers/issues/README.md"
if (Test-Path -LiteralPath $issueReadmePath -PathType Leaf) {
    $issueReadmeText = Get-Content -LiteralPath $issueReadmePath -Raw
    if ($issueReadmeText.Contains("Closed Mirror Lifecycle") -or $issueReadmeText.Contains("Mirror Retention")) {
        $healthy += [pscustomobject]@{
            kind = "issue-policy"
            artifact = "docs/superpowers/issues/README.md"
            message = "Closed mirror lifecycle policy is documented."
        }
    } else {
        $blocking += [pscustomobject]@{
            kind = "issue-policy"
            artifact = "docs/superpowers/issues/README.md"
            message = "Closed mirror lifecycle policy is missing."
        }
    }
}

$result = [pscustomobject]@{
    ok = ($blocking.Count -eq 0)
    repo_root = $repoRoot
    findings = [pscustomobject]@{
        blocking = $blocking
        healthy = $healthy
    }
}

$json = $result | ConvertTo-Json -Depth 6
$json

if (-not $StatusOnly -and $blocking.Count -gt 0) {
    throw "Live project sync found $($blocking.Count) blocking finding(s). Rerun with -StatusOnly to inspect without failing."
}
