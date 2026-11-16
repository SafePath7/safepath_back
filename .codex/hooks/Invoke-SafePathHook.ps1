[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('SessionStart', 'UserPromptSubmit', 'PreToolUse')]
    [string]$Event
)

$ErrorActionPreference = 'Stop'

function Get-RepositoryRoot {
    $root = (& git rev-parse --show-toplevel 2>$null)
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($root)) {
        throw 'SafePath hook must run inside a Git worktree.'
    }
    return $root.Trim()
}

function Read-HookInput {
    $rawInput = [Console]::In.ReadToEnd()
    if ([string]::IsNullOrWhiteSpace($rawInput)) {
        throw 'Codex hook input was empty.'
    }
    return $rawInput | ConvertFrom-Json
}

function Write-HookLog {
    param([string]$Root, [string]$Status, [string]$Detail)

    $logDirectory = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), 'SafePath-codex-hooks')
    New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
    $entry = '{0:o} [{1}] {2}' -f (Get-Date).ToUniversalTime(), $Status, $Detail.Replace("`r", ' ').Replace("`n", ' ')
    Add-Content -LiteralPath (Join-Path $logDirectory 'safepath-hooks.log') -Value $entry -Encoding utf8
}

function Require-RuleFile {
    param([string]$Root, [string]$RelativePath)

    $path = Join-Path $Root $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required SafePath rule file is missing: $RelativePath"
    }
    return Get-Content -LiteralPath $path -Raw -Encoding utf8
}

function Write-Context {
    param([string]$HookEventName, [string]$Context)

    @{
        hookSpecificOutput = @{
            hookEventName = $HookEventName
            additionalContext = $Context
        }
    } | ConvertTo-Json -Depth 6 -Compress
}

function Deny-ToolUse {
    param([string]$Reason)

    @{
        hookSpecificOutput = @{
            hookEventName = 'PreToolUse'
            permissionDecision = 'deny'
            permissionDecisionReason = $Reason
        }
    } | ConvertTo-Json -Depth 6 -Compress
}

function Get-ToolCommand {
    param($ToolInput)

    if ($null -eq $ToolInput) {
        return ''
    }
    foreach ($propertyName in @('cmd', 'command', 'script')) {
        $property = $ToolInput.PSObject.Properties[$propertyName]
        if ($null -ne $property -and $null -ne $property.Value) {
            return [string]$property.Value
        }
    }
    return ''
}

function Test-KnownReadCommand {
    param([string]$Command)

    if ([string]::IsNullOrWhiteSpace($Command)) {
        return $false
    }
    if ($Command -match '(?m)(?<!\d)>(?!>)|\b(Out-File|Set-Content|Add-Content|Clear-Content|New-Item|Copy-Item|Move-Item|Rename-Item|Remove-Item|del|erase|rd|rmdir)\b|\bgit\s+(add|commit|mv|rm|restore|checkout|reset|clean)\b') {
        return $false
    }
    $segments = $Command -split '(?:;|\r?\n|&&|\|\|)'
    foreach ($segment in $segments) {
        $trimmed = $segment.Trim()
        if ($trimmed -and $trimmed -notmatch '^(?i:(Get-Content|gc|rg|Select-String|sls|Get-ChildItem|gci|dir|type|Test-Path|git\s+(status|diff|log|show|ls-files|grep|rev-parse)))\b') {
            return $false
        }
    }
    return $true
}

function Get-ToolOperation {
    param([string]$ToolName, [string]$Command)

    if ($ToolName -match '^(?i:apply_patch|edit|write)$') {
        return 'write'
    }
    if (Test-KnownReadCommand $Command) {
        return 'read'
    }
    if ($Command -match '(?i)\bgit\s+add\b') {
        return 'git-add'
    }
    if ($Command -match '(?i)(?<!\d)>(?!>)|\b(Set-Content|Add-Content|Clear-Content|New-Item|Copy-Item|Move-Item|Rename-Item|Remove-Item|del|erase|rd|rmdir|git\s+(commit|mv|rm|restore|checkout|reset|clean))\b') {
        return 'write'
    }
    return 'unknown'
}

try {
    $root = Get-RepositoryRoot
    $inputObject = Read-HookInput

    switch ($Event) {
        'SessionStart' {
            # IntelliJ Codex already loads AGENTS.md. Validate its presence without duplicating it in context.
            Require-RuleFile $root 'AGENTS.md' | Out-Null
            $common = Require-RuleFile $root 'docs/ai-rules/common.md'
            $context = "SafePath session policy loaded. AGENTS.md is repository guidance already supplied by Codex; do not duplicate it. Apply its task-rule selection and post-change verification requirements. Common rules follow:`n`n$common"
            Write-Context 'SessionStart' $context
            Write-HookLog $root 'PASS' 'SessionStart loaded AGENTS.md and common.md.'
        }

        'UserPromptSubmit' {
            $prompt = [string]$inputObject.prompt
            if ([string]::IsNullOrWhiteSpace($prompt)) {
                throw 'UserPromptSubmit input did not contain prompt.'
            }

            $selected = [System.Collections.Generic.List[string]]::new()
            $selected.Add('docs/ai-rules/common.md')
            if ($prompt -match '(?i)\b(entity|repository|flyway|postgres|postgresql|migration|schema|table|column|jpa)\b|DB|\uB370\uC774\uD130\uBCA0\uC774\uC2A4|\uC2A4\uD0A4\uB9C8|\uD14C\uC774\uBE14|\uCEEC\uB7FC|\uB9C8\uC774\uADF8\uB808\uC774\uC158|\uC5D4\uD2F0\uD2F0|\uB9AC\uD3EC\uC9C0\uD1A0\uB9AC') {
                $selected.Add('docs/ai-rules/database.md')
                $selected.Add('docs/ai-rules/backend.md')
            }
            if ($prompt -match '(?i)\b(firebase|websocket|stomp|fcm|notification|authentication|authorization|token)\b|\uC778\uC99D|\uAD8C\uD55C|\uC54C\uB9BC|\uC6F9\uC18C\uCF13|\uCC44\uB110|\uAD6C\uB3C5') {
                $selected.Add('docs/ai-rules/integration.md')
            }
            if ($prompt -match '(?i)\b(aws|s3|upload|download|multipart|file storage|spring security|security config|environment|env|deployment|deploy|secret|credential)\b|AWS|S3|\uD30C\uC77C\s*\uC5C5\uB85C\uB4DC|\uD30C\uC77C\s*\uB2E4\uC6B4\uB85C\uB4DC|\uBCF4\uC548\s*\uC124\uC815|\uD658\uACBD\s*\uBCC0\uC218|\uBC30\uD3EC') {
                $selected.Add('docs/ai-rules/integration.md')
                $selected.Add('docs/ai-rules/backend.md')
            }
            if ($prompt -match '(?i)\b(test|junit|mockito|refactor|build|gradle)\b|\uD14C\uC2A4\uD2B8|\uB9AC\uD329\uD130\uB9C1|\uBE4C\uB4DC') {
                $selected.Add('docs/ai-rules/testing.md')
            }
            if ($prompt -match '(?i)\b(controller|service|rest|api|request|response|endpoint|spring)\b|\uCEE8\uD2B8\uB864\uB7EC|\uC11C\uBE44\uC2A4|API|\uC5D4\uB4DC\uD3EC\uC778\uD2B8|\uC694\uCCAD|\uC751\uB2F5') {
                $selected.Add('docs/ai-rules/backend.md')
            }
            if ($selected.Count -eq 1) {
                $selected.Add('docs/ai-rules/backend.md')
                $selected.Add('docs/ai-rules/testing.md')
            }

            $uniqueSelected = @($selected | Select-Object -Unique)
            $ruleSections = foreach ($rule in $uniqueSelected) {
                "# $rule`n" + (Require-RuleFile $root $rule)
            }
            $context = "SafePath rules selected for this request: $($uniqueSelected -join ', ')`n`n" + ($ruleSections -join "`n`n")
            Write-Context 'UserPromptSubmit' $context
            Write-HookLog $root 'PASS' "UserPromptSubmit selected: $($uniqueSelected -join ', ')."
        }

        'PreToolUse' {
            $toolName = [string]$inputObject.tool_name
            $toolInput = $inputObject.tool_input | ConvertTo-Json -Depth 20 -Compress
            $command = Get-ToolCommand $inputObject.tool_input
            $operation = Get-ToolOperation $toolName $command
            $protectedPathPattern = '(?i)(^|[\s\\/])AGENTS\.md\b|(^|[\s\\/])docs[\\/]ai-rules([\\/]|$)|(^|[\s\\/])\.agents[\\/]skills([\\/]|$)|(^|[\s\\/])\.codex([\\/]|$)|(^|[\s\\/])build\.gradle\b|(^|[\s\\/])settings\.gradle\b|(^|[\s\\/])\.github([\\/]|$)|(^|[\s\\/])src[\\/]main[\\/]resources[\\/]db[\\/]migration([\\/]|$)'
            $sensitivePathPattern = '(?i)(^|[\s\\/])\.env(?:\.[^\s\\/]*)?(?=$|[\s\\/"''])|(^|[\s\\/])(firebase[-_]?|google[-_]?)?(service[-_]?account|adminsdk)[^\s\\/"'']*\.json\b|(^|[\s\\/])[^\s\\/"'']+\.(pem|key|p12|pfx|jks|keystore)\b|(^|[\s\\/])(application|bootstrap|config)[^\s\\/"'']*\.(properties|ya?ml)\b'
            $destructivePattern = '(?i)\bgit\s+(reset\s+--hard|checkout\s+--|clean\s+-[a-z]*f|restore\b(?!\s+--staged))|\b(remove-item|rm|rmdir|del|erase|rd)\b.*(?:-recurse|-rf|/s|/q)'

            if ($toolInput -match $destructivePattern) {
                $reason = 'Blocked a destructive repository command. Request explicit user direction and use a narrowly scoped recovery step.'
                Deny-ToolUse $reason
                Write-HookLog $root 'BLOCK' "${toolName}: destructive command."
                break
            }
            if ($operation -eq 'read') {
                if ($toolInput -match $sensitivePathPattern) {
                    $context = 'Sensitive-file read detected. Do not print, log, or copy credentials, tokens, passwords, or private-key material; inspect only the minimum metadata or redacted values needed.'
                    Write-Context 'PreToolUse' $context
                    Write-HookLog $root 'WARN' "${toolName}: sensitive file read."
                } else {
                    Write-HookLog $root 'PASS' "${toolName}: read-only operation."
                }
                break
            }
            if ($operation -eq 'git-add' -and $toolInput -match $sensitivePathPattern) {
                $reason = 'Blocked Git add of a potentially sensitive file. Do not stage credentials, tokens, passwords, private keys, certificates, or environment secrets.'
                Deny-ToolUse $reason
                Write-HookLog $root 'BLOCK' "${toolName}: sensitive file Git add."
                break
            }
            if ($operation -eq 'write' -and $toolInput -match $sensitivePathPattern) {
                $context = 'Potentially sensitive file modification detected. Confirm the exact target and necessity; never add secrets to code or Git, and avoid emitting sensitive values in tool output or logs.'
                Write-Context 'PreToolUse' $context
                Write-HookLog $root 'WARN' "${toolName}: sensitive file modification."
                break
            }
            if ($operation -eq 'write' -and $toolInput -match $protectedPathPattern) {
                $context = 'Protected policy, build, CI, or migration target detected. Do not treat this warning as approval: explain impact and confirm explicit user direction before proceeding. The hook permits the tool call because it cannot verify approval from the conversation.'
                Write-Context 'PreToolUse' $context
                Write-HookLog $root 'WARN' "${toolName}: protected target requires explicit user approval."
                break
            }

            if ($operation -eq 'unknown') {
                $context = 'Tool operation could not be classified as read-only or a known write. Do not treat it as safe; inspect its exact effect, target paths, and user authorization before proceeding.'
                Write-Context 'PreToolUse' $context
                Write-HookLog $root 'WARN' "${toolName}: unclassified operation."
                break
            }

            $context = 'Scope check passed. Confirm the pending change is necessary for the request; the hook cannot determine code ownership or user approval and does not treat either as granted.'
            Write-Context 'PreToolUse' $context
            Write-HookLog $root 'PASS' "${toolName}: known non-sensitive operation."
        }
    }
}
catch {
    $message = $_.Exception.Message
    try {
        if ($root) { Write-HookLog $root 'ERROR' "${Event}: $message" }
    } catch { }
    [Console]::Error.WriteLine("SafePath $Event hook failed: $message")
    exit 1
}
