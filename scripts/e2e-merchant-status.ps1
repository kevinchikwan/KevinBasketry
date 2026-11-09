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

    It needs ports 8080, 8082 and 9092 free (with -Postgres also the Postgres
    port), and refuses to start otherwise, so stop your own services first. It
    stops only what it started: it records the PID of each service, and uses its
    own Compose project (basketry-e2e). It wipes only that project's data, before
    and after the run, so every run starts from empty.

    With -Postgres the services run on the Compose Postgres instead of H2, and
    the check also restarts both services halfway: the merchant, the link and
    the status must survive, and the next status change must still arrive.

    Service logs go to build\e2e. Secrets are never printed: the API key and the
    database passwords are generated for this run and only passed through the
    environment.

    Exit codes:
        0  every check passed
        1  a check failed
        2  Docker is not running
        3  a port is busy
        4  Java 25 or the build is not available
        5  a service or Kafka did not start in time

.PARAMETER TimeoutSeconds
    How long to wait for a service to start, and for an event to arrive.

.PARAMETER Postgres
    Run the services on Postgres (Compose profile "postgres") and add the
    restart step. The host port is BASKETRY_POSTGRES_PORT if set, else 5432.
#>
param(
    [int]$TimeoutSeconds = 90,
    [switch]$Postgres
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$identityUrl = 'http://127.0.0.1:8080'
$merchantUrl = 'http://127.0.0.1:8082'
$ports = @(8080, 8082, 9092)
$postgresPort = 5432
if ($env:BASKETRY_POSTGRES_PORT) {
    $postgresPort = [int]$env:BASKETRY_POSTGRES_PORT
}
if ($Postgres) {
    $ports += $postgresPort
}
# Only this project's containers and volumes are ever started or deleted.
$composeProject = 'basketry-e2e'
$compose = "docker compose -p $composeProject"
# Only what this script needs: not Redis (inventory-service only), so port 6379 is never needed.
$composeServices = 'kafka'
if ($Postgres) {
    $compose = "$compose --profile postgres"
    $composeServices = 'kafka postgres'
}
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
# Per run, passed to the services through the environment only.
$script:serviceEnvironment = @{}

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

# The variables in $script:serviceEnvironment[$name] are set only while the
# process starts, so they reach that one service and stay out of this session.
function Start-Service-Jar([string]$name, [string]$logName = $name) {
    $jar = Get-ChildItem (Join-Path $repoRoot "services\$name\build\libs") -Filter '*.jar' |
        Where-Object { $_.Name -notlike '*-plain.jar' } |
        Select-Object -First 1
    if ($null -eq $jar) {
        Stop-Check "No boot jar found for $name" 4
    }
    $environment = $script:serviceEnvironment[$name]
    try {
        foreach ($variable in $environment.Keys) {
            Set-Item "Env:\$variable" $environment[$variable]
        }
        $process = Start-Process -FilePath 'java' -ArgumentList '-jar', "`"$($jar.FullName)`"" `
            -WorkingDirectory $repoRoot -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $logDir "$logName.out.log") `
            -RedirectStandardError (Join-Path $logDir "$logName.err.log")
    } finally {
        foreach ($variable in $environment.Keys) {
            Remove-Item "Env:\$variable" -ErrorAction SilentlyContinue
        }
    }
    $script:startedProcesses += [pscustomobject]@{ Name = $name; Process = $process; Id = $process.Id }
    Write-Pass "$name started, PID $($process.Id)"
    return $process
}

# Stops one service this script started, and forgets it.
function Stop-StartedService([string]$name) {
    foreach ($started in @($script:startedProcesses | Where-Object { $_.Name -eq $name })) {
        Stop-ProcessTree $started.Id
        $started.Process.WaitForExit(15000) | Out-Null
        Write-Pass "stopped $name (PID $($started.Id))"
    }
    $script:startedProcesses = @($script:startedProcesses | Where-Object { $_.Name -ne $name })
}

function Wait-ForPortFree([int]$port) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while (Test-PortBusy $port) {
        if ((Get-Date) -gt $deadline) {
            Stop-Check "Port $port is still busy after stopping the service" 5
        }
        Start-Sleep -Milliseconds 250
    }
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
        # -v: Kafka and Postgres keep data in volumes, and the next run must start empty.
        Invoke-Quietly "$compose down -v" (Join-Path $logDir 'compose-down.log') | Out-Null
        Write-Host "    stopped Compose project $composeProject and deleted its data"
    }
}

function Start-BothServices([string]$logSuffix = '') {
    $identity = Start-Service-Jar 'identity-service' "identity-service$logSuffix"
    $merchant = Start-Service-Jar 'merchant-service' "merchant-service$logSuffix"
    Wait-ForService 'identity-service' "$identityUrl/.well-known/jwks.json" $identity
    Wait-ForService 'merchant-service' "$merchantUrl/merchants" $merchant
}

function Invoke-Check {
    Write-Step 'Checking prerequisites'
    if ((Invoke-Quietly 'docker info' $null) -ne 0) {
        Stop-Check 'Docker is not running. Start Docker Desktop and run this again.' 2
    }
    Write-Pass 'Docker is running'

    # A run that was killed may have left its own project behind; nothing else is touched.
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    Invoke-Quietly "docker compose -p $composeProject --profile postgres down -v" (Join-Path $logDir 'compose-cleanup.log') | Out-Null

    $busy = @($ports | Where-Object { Test-PortBusy $_ })
    if ($busy.Count -gt 0) {
        Stop-Check "Port(s) $($busy -join ', ') are busy. Stop whatever uses them and run this again; nothing was started." 3
    }
    Write-Pass "ports $($ports -join ', ') are free"

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

    $apiKey = New-Secret
    $script:serviceEnvironment = @{
        'identity-service' = @{ BASKETRY_INTERNAL_API_KEY = $apiKey }
        'merchant-service' = @{ BASKETRY_INTERNAL_API_KEY = $apiKey }
    }
    $composeEnvironment = @{}
    $infrastructure = 'Kafka'
    if ($Postgres) {
        $infrastructure = 'Kafka and Postgres'
        $identityDbPassword = New-Secret
        $merchantDbPassword = New-Secret
        $composeEnvironment = @{
            BASKETRY_POSTGRES_ADMIN_PASSWORD = New-Secret
            BASKETRY_IDENTITY_DB_PASSWORD = $identityDbPassword
            BASKETRY_MERCHANT_DB_PASSWORD = $merchantDbPassword
        }
        foreach ($service in 'identity-service', 'merchant-service') {
            $script:serviceEnvironment[$service].SPRING_PROFILES_ACTIVE = 'postgres'
            $script:serviceEnvironment[$service].BASKETRY_POSTGRES_PORT = "$postgresPort"
        }
        $script:serviceEnvironment['identity-service'].BASKETRY_DB_PASSWORD = $identityDbPassword
        $script:serviceEnvironment['merchant-service'].BASKETRY_DB_PASSWORD = $merchantDbPassword
    }

    Write-Step "Starting $infrastructure"
    $script:composeStarted = $true
    try {
        foreach ($variable in $composeEnvironment.Keys) {
            Set-Item "Env:\$variable" $composeEnvironment[$variable]
        }
        $up = Invoke-Quietly "$compose up -d --wait $composeServices" (Join-Path $logDir 'compose-up.log')
    } finally {
        foreach ($variable in $composeEnvironment.Keys) {
            Remove-Item "Env:\$variable" -ErrorAction SilentlyContinue
        }
    }
    if ($up -ne 0) {
        Stop-Check "$infrastructure did not start; see $logDir\compose-up.log" 5
    }
    Write-Pass "$infrastructure ready (healthchecks passed)"

    Write-Step 'Starting both services with one generated API key'
    Start-BothServices

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

    if ($Postgres) {
        Write-Step 'Restarting both services: Postgres keeps their data'
        Stop-StartedService 'merchant-service'
        Stop-StartedService 'identity-service'
        Wait-ForPortFree 8080
        Wait-ForPortFree 8082
        Start-BothServices '-restarted'
        # identity-service's signing key lives in memory, so tokens from before do not survive.
        $adminToken = Get-Token $adminEmail $adminPassword
        $merchantAfter = Invoke-Api 'GET' "$merchantUrl/merchants/$($onboarded.merchantCode)" $null $adminToken
        Assert-True ([long]$merchantAfter.id -eq [long]$onboarded.id) 'merchant-service still has the merchant'
        Assert-True ($merchantAfter.accountLinkStatus -eq 'LINKED') 'it is still LINKED'
        Assert-True ($merchantAfter.active -eq $false) 'it is still active=false'
        Assert-True ($merchantAfter.createdAt -eq $onboarded.createdAt) 'its createdAt is unchanged'
        $account = Invoke-Api 'GET' "$identityUrl/accounts/$accountId" $null $adminToken
        Assert-True ($account.merchantActive -eq $false) 'identity-service still has merchantActive=false'
        $claims = Get-Claims (Get-Token $email $password)
        Assert-True ([long]$claims.sub -eq $accountId) 'the merchant logs in to the same account'
        Assert-True (-not ($claims.capabilities -contains 'MERCHANT')) 'still without MERCHANT'
    }

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
