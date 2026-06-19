[CmdletBinding()]
param(
    [string]$WorkspaceRoot = (Join-Path $PSScriptRoot "..\testing\repo-scoped-agent-workspace\master-project"),
    [string]$ServerHost = "127.0.0.1",
    [int]$Port = 29170,
    [string]$IdeServerName = "intellij-index"
)

$ErrorActionPreference = "Stop"

function Test-GitRepo {
    param([Parameter(Mandatory = $true)][string]$RepoRoot)

    $resolvedRepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
    $topLevel = (& git -C $RepoRoot rev-parse --show-toplevel 2>$null)

    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($topLevel)) {
        return $false
    }

    $resolvedTopLevel = (Resolve-Path -LiteralPath $topLevel.Trim()).Path
    return $resolvedTopLevel -ieq $resolvedRepoRoot
}

function Ensure-GitRepo {
    param([Parameter(Mandatory = $true)][string]$RepoRoot)

    if (-not (Test-GitRepo -RepoRoot $RepoRoot)) {
        & git -C $RepoRoot init -b main | Out-Null
        & git -C $RepoRoot config user.name "Repo Scope Fixture" | Out-Null
        & git -C $RepoRoot config user.email "repo-scope-fixture@example.invalid" | Out-Null
    }

    $head = (& git -C $RepoRoot rev-parse --verify HEAD 2>$null)
    if ($LASTEXITCODE -ne 0) {
        & git -C $RepoRoot add -A | Out-Null
        & git -C $RepoRoot commit -m "Initial repo-scoped fixture" | Out-Null
    }
}

function Assert-PathExists {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Required fixture path is missing: $Path"
    }
}

$workspaceRootResolved = (Resolve-Path -LiteralPath $WorkspaceRoot).Path

$repos = @(
    [pscustomobject]@{
        name = "inventory-repo"
        repo_id = "inventory-repo"
        unique_token = "inventoryrepouniquetoken"
        shared_query = "workspacescopetoken"
        file_query = "SharedScopeProbe"
        relative_keep_paths = @(
            "inventory-repo/src/inventory/SharedScopeProbe.kt",
            "inventory-repo/src/inventory/InventoryLedger.kt"
        )
    }
    [pscustomobject]@{
        name = "billing-repo"
        repo_id = "billing-repo"
        unique_token = "billingrepouniquetoken"
        shared_query = "workspacescopetoken"
        file_query = "SharedScopeProbe"
        relative_keep_paths = @(
            "billing-repo/src/billing/SharedScopeProbe.py",
            "billing-repo/src/billing/billing_ledger.py"
        )
    }
    [pscustomobject]@{
        name = "analytics-repo"
        repo_id = "analytics-repo"
        unique_token = "analyticsrepouniquetoken"
        shared_query = "workspacescopetoken"
        file_query = "SharedScopeProbe"
        relative_keep_paths = @(
            "analytics-repo/src/analytics/SharedScopeProbe.ts",
            "analytics-repo/src/analytics/analyticsLedger.ts"
        )
    }
    [pscustomobject]@{
        name = "submodules/shipping-repo"
        repo_id = "shipping-repo"
        unique_token = "shippingrepouniquetoken"
        shared_query = "workspacescopetoken"
        file_query = "SharedScopeProbe"
        relative_keep_paths = @(
            "submodules/shipping-repo/src/shipping/SharedScopeProbe.ts"
        )
    }
)

foreach ($repo in $repos) {
    $repoRoot = Join-Path $workspaceRootResolved $repo.name
    Assert-PathExists -Path $repoRoot
    foreach ($keepPath in $repo.relative_keep_paths) {
        Assert-PathExists -Path (Join-Path $workspaceRootResolved $keepPath)
    }
    Ensure-GitRepo -RepoRoot $repoRoot
}

$summary = $repos | ForEach-Object {
    [pscustomobject]@{
        repo_name = $_.name
        repo_id = $_.repo_id
        repo_root = (Join-Path $workspaceRootResolved $_.name)
        codex_server_name = "$IdeServerName-$($_.repo_id)"
        endpoint = "http://$ServerHost`:$Port/index-mcp/repos/$($_.repo_id)/streamable-http"
        file_query = $_.file_query
        shared_query = $_.shared_query
        unique_query = $_.unique_token
    }
}

[pscustomobject]@{
    ok = $true
    workspace_root = $workspaceRootResolved
    master_project = $workspaceRootResolved
    repos = $summary
} | ConvertTo-Json -Depth 6
