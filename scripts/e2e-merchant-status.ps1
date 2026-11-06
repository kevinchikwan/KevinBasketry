<#
.SYNOPSIS
    End-to-end check of merchant.status.changed (DESIGN.md Section 5).

.DESCRIPTION
    Runs the whole chain on real processes: a Compose Kafka, identity-service
    and merchant-service. It onboards a merchant, deactivates it, checks that
    identity-service follows and the next login has no MERCHANT capability,
    then reactivates it.

    Run it from PowerShell at the repo root, with Docker running:

        powershell -ExecutionPolicy Bypass -File scripts\e2e-merchant-status.ps1

    It needs ports 8080, 8082 and 9092 free, and refuses to start otherwise, so
    stop your own services first. It stops only what it started: it records the
    PID of each service, and uses its own Compose project (basketry-e2e). The
    services use in-memory H2, so every run starts from empty.

    Service logs go to build\e2e. Secrets are never printed: the API key is
    generated for this run and only passed through the environment.

    Exit codes:
        0  every check passed
        1  a check failed
        2  Docker is not running
        3  a port is busy
        4  Java 25 or the build is not available
        5  a service or Kafka did not start in time

.PARAMETER TimeoutSeconds
    How long to wait for a service to start, and for an event to arrive.
#>
param(
    [int]$TimeoutSeconds = 90
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$identityUrl = 'http://127.0.0.1:8080'
$merchantUrl = 'http://127.0.0.1:8082'
$ports = 8080, 8082, 9092
$composeProject = 'basketry-e2e'
$repoRoot = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $repoRoot 'build\e2e'

# Seeded by V2__seed_initial_admin.sql, a placeholder until deployment.
$adminEmail = 'admin-placeholder@basketry.example'
$adminPassword = 'ChangeMe-Before-Deploy!'
if ($env:BASKETRY_E2E_ADMIN_PASSWORD) {
    $adminPassword = $env:BASKETRY_E2E_ADMIN_PASSWORD
}

$script:startedProcesses = @()
$script:composeStarted = $false

class E2eFailure : System.Exception {
    [int]$ExitCode
    E2eFailure([string]$message, [int]$exitCode) : base($message) {
        $this.ExitCode = $exitCode
    }
}

function Stop-Check([string]$message, [int]$exitCode = 1) {
    throw [E2eFailure]::new($message, $exitCode)
}

function Write-Step([string]$message) {
    Write-Host "==> $message"
}

function Write-Pass([string]$message) {
    Write-Host "    ok: $message"
}

function Assert-True([bool]$condition, [string]$message) {
    if (-not $condition) {
        Stop-Check "Check failed: $message"
    }
    Write-Pass $message
}

# Through cmd so that native stderr is a plain redirect: in Windows PowerShell
# a redirected native error stream would stop this script.
function Invoke-Quietly([string]$commandLine, [string]$logFile) {
    if ($logFile) {
        & cmd.exe /c "$commandLine > `"$logFile`" 2>&1"
    } else {
        & cmd.exe /c "$commandLine > nul 2>&1"
    }
    return $LASTEXITCODE
}

function Test-PortBusy([int]$port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $connecting = $client.BeginConnect('127.0.0.1', $port, $null, $null)
        if (-not $connecting.AsyncWaitHandle.WaitOne(500)) {
            return $false
        }
        $client.EndConnect($connecting)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function New-Secret {
    $bytes = New-Object byte[] 32
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $random.GetBytes($bytes)
    $random.Dispose()
    return -join ($bytes | ForEach-Object { $_.ToString('x2') })
}

function Start-Service-Jar([string]$name) {
    $jar = Get-ChildItem (Join-Path $repoRoot "services\$name\build\libs") -Filter '*.jar' |
        Where-Object { $_.Name -notlike '*-plain.jar' } |
        Select-Object -First 1
    if ($null -eq $jar) {
        Stop-Check "No boot jar found for $name" 4
    }
    $process = Start-Process -FilePath 'java' -ArgumentList '-jar', "`"$($jar.FullName)`"" `
        -WorkingDirectory $repoRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logDir "$name.out.log") `
        -RedirectStandardError (Join-Path $logDir "$name.err.log")
    $script:startedProcesses += [pscustomobject]@{ Name = $name; Process = $process; Id = $process.Id }
    Write-Pass "$name started, PID $($process.Id)"
    return $process
}

# Any HTTP answer counts, including 401: it means the service is listening.
function Wait-ForService([string]$name, [string]$url, $process) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ($process.HasExited) {
            Stop-Check "$name exited during startup (code $($process.ExitCode)); see $logDir" 5
        }
        try {
            Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 2 | Out-Null
            Write-Pass "$name is up"
            return
        } catch [System.Net.WebException] {
            if ($null -ne $_.Exception.Response) {
                Write-Pass "$name is up"
                return
            }
        }
        Start-Sleep -Milliseconds 500
    }
    Stop-Check "$name did not start within $TimeoutSeconds s; see $logDir" 5
}

function Invoke-Api([string]$method, [string]$url, $body, [string]$token) {
    $arguments = @{ Method = $method; Uri = $url; TimeoutSec = 15; ContentType = 'application/json' }
    if ($null -ne $body) {
        $arguments.Body = ($body | ConvertTo-Json -Compress)
    }
    if ($token) {
        $arguments.Headers = @{ Authorization = "Bearer $token" }
    }
    try {
        return Invoke-RestMethod @arguments
    } catch [System.Net.WebException] {
        $status = 'no response'
        if ($null -ne $_.Exception.Response) {
            $status = [int]$_.Exception.Response.StatusCode
        }
        Stop-Check "$method $url failed: $status"
    }
}

function Get-Token([string]$email, [string]$password) {
    return (Invoke-Api 'POST' "$identityUrl/auth/login" @{ email = $email; password = $password }).accessToken
}

function Get-Claims([string]$token) {
    $part = $token.Split('.')[1].Replace('-', '+').Replace('_', '/')
    $part = $part.PadRight($part.Length + ((4 - $part.Length % 4) % 4), '=')
    $text = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($part))
    return $text | ConvertFrom-Json
}

function Wait-ForMerchantActive([long]$accountId, [string]$adminToken, [bool]$expected) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $started = Get-Date
    while ((Get-Date) -lt $deadline) {
        $account = Invoke-Api 'GET' "$identityUrl/accounts/$accountId" $null $adminToken
        if ($account.merchantActive -eq $expected) {
            $seconds = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
            Write-Pass "identity-service has merchantActive=$expected after $seconds s"
            return
        }
        Start-Sleep -Milliseconds 250
    }
    Stop-Check "identity-service did not reach merchantActive=$expected within $TimeoutSeconds s"
}

# Children first. Win32_Process is the only source of parent ids here, and a
# parent id can be stale, so the tree starts from a process this script started.
function Stop-ProcessTree([int]$processId) {
    $children = Get-CimInstance Win32_Process -Filter "ParentProcessId = $processId"
    foreach ($child in $children) {
        Stop-ProcessTree ([int]$child.ProcessId)
    }
    Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
}

function Stop-Everything {
    foreach ($started in $script:startedProcesses) {
        if ($started.Process.HasExited) {
            Write-Host "    $($started.Name) (PID $($started.Id)) had already exited"
        } else {
            Stop-ProcessTree $started.Id
            Write-Host "    stopped $($started.Name) (PID $($started.Id))"
        }
    }
    if ($script:composeStarted) {
        Invoke-Quietly "docker compose -p $composeProject down" (Join-Path $logDir 'compose-down.log') | Out-Null
        Write-Host "    stopped Compose project $composeProject"
    }
}

function Invoke-Check {
    Write-Step 'Checking prerequisites'
    if ((Invoke-Quietly 'docker info' $null) -ne 0) {
        Stop-Check 'Docker is not running. Start Docker Desktop and run this again.' 2
    }
    Write-Pass 'Docker is running'

    $busy = @($ports | Where-Object { Test-PortBusy $_ })
    if ($busy.Count -gt 0) {
        Stop-Check "Port(s) $($busy -join ', ') are busy. Stop whatever uses them and run this again; nothing was started." 3
    }
    Write-Pass "ports $($ports -join ', ') are free"

    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    Invoke-Quietly 'java -version' (Join-Path $logDir 'java-version.log') | Out-Null
    if (-not (Select-String -Path (Join-Path $logDir 'java-version.log') -Pattern 'version "25' -Quiet)) {
        Stop-Check 'Java 25 must be the java on PATH.' 4
    }
    Write-Pass 'Java 25 is on PATH'

    Write-Step 'Building the services'
    $build = Invoke-Quietly '.\gradlew.bat --no-daemon --console=plain :identity-service:bootJar :merchant-service:bootJar' (Join-Path $logDir 'build.log')
    if ($build -ne 0) {
        Stop-Check "The build failed; see $logDir\build.log" 4
    }
    Write-Pass 'boot jars built'

    Write-Step 'Starting Kafka'
    $script:composeStarted = $true
    if ((Invoke-Quietly "docker compose -p $composeProject up -d --wait" (Join-Path $logDir 'compose-up.log')) -ne 0) {
        Stop-Check "Kafka did not start; see $logDir\compose-up.log" 5
    }
    Write-Pass 'Kafka is healthy'

    Write-Step 'Starting both services with one generated API key'
    $env:BASKETRY_INTERNAL_API_KEY = New-Secret
    try {
        $identity = Start-Service-Jar 'identity-service'
        $merchant = Start-Service-Jar 'merchant-service'
    } finally {
        Remove-Item Env:\BASKETRY_INTERNAL_API_KEY
    }
    Wait-ForService 'identity-service' "$identityUrl/.well-known/jwks.json" $identity
    Wait-ForService 'merchant-service' "$merchantUrl/merchants" $merchant

    Write-Step 'Onboarding a merchant'
    $adminToken = Get-Token $adminEmail $adminPassword
    $email = "e2e-$(New-Secret | ForEach-Object { $_.Substring(0, 12) })@example.com"
    # Meets the password policy whatever the random part is, and is at most 15 characters.
    $password = "Aa1!$(New-Secret | ForEach-Object { $_.Substring(0, 10) })"
    $onboarded = Invoke-Api 'POST' "$merchantUrl/merchants" @{
        name = 'E2E Shop'
        category = 'Testing'
        kycVerified = $true
        commissionRate = 5.00
        loginEmail = $email
        initialPassword = $password
    } $adminToken
    Assert-True ($onboarded.accountLinkStatus -eq 'LINKED') "merchant $($onboarded.merchantCode) is LINKED"

    Write-Step 'Logging in as the merchant'
    $firstToken = Get-Token $email $password
    $claims = Get-Claims $firstToken
    Assert-True ($claims.capabilities -contains 'MERCHANT') 'the login has MERCHANT'
    Assert-True ([long]$claims.merchantId -eq [long]$onboarded.id) 'the token carries the merchantId'
    $accountId = [long]$claims.sub
    $account = Invoke-Api 'GET' "$identityUrl/accounts/$accountId" $null $adminToken
    Assert-True ($account.merchantActive -eq $true) 'identity-service starts with merchantActive=true'

    Write-Step 'Deactivating the merchant'
    $patched = Invoke-Api 'PATCH' "$merchantUrl/merchants/$($onboarded.merchantCode)" @{ active = $false } $adminToken
    Assert-True ($patched.active -eq $false) 'merchant-service has active=false'
    Wait-ForMerchantActive $accountId $adminToken $false
    $claims = Get-Claims (Get-Token $email $password)
    Assert-True (-not ($claims.capabilities -contains 'MERCHANT')) 'the next login has no MERCHANT'
    Assert-True ($claims.capabilities -contains 'BUYER') 'the next login still has BUYER'
    Assert-True ($null -eq $claims.PSObject.Properties['merchantId']) 'the next login has no merchantId'
    $stillValid = Invoke-Api 'GET' "$identityUrl/accounts/$accountId" $null $firstToken
    Assert-True ($stillValid.id -eq $accountId) 'the token issued earlier stays valid until it expires'

    Write-Step 'Reactivating the merchant'
    $patched = Invoke-Api 'PATCH' "$merchantUrl/merchants/$($onboarded.merchantCode)" @{ active = $true } $adminToken
    Assert-True ($patched.active -eq $true) 'merchant-service has active=true'
    Wait-ForMerchantActive $accountId $adminToken $true
    $claims = Get-Claims (Get-Token $email $password)
    Assert-True ($claims.capabilities -contains 'MERCHANT') 'the next login has MERCHANT again'
}

$exitCode = 0
Push-Location $repoRoot
try {
    Invoke-Check
    Write-Host ''
    Write-Host 'PASSED: every check passed.'
} catch [E2eFailure] {
    $exitCode = $_.Exception.ExitCode
    Write-Host ''
    Write-Host "FAILED: $($_.Exception.Message)"
} catch {
    $exitCode = 1
    Write-Host ''
    Write-Host "FAILED: unexpected error: $($_.Exception.Message)"
} finally {
    if (($script:startedProcesses.Count -gt 0) -or $script:composeStarted) {
        Write-Step 'Stopping what this script started'
        Stop-Everything
    }
    Pop-Location
}
exit $exitCode
