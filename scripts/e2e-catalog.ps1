<#
.SYNOPSIS
    End-to-end check of catalog-service's Kafka side (DESIGN.md Section 5, the
    catalog-service consumer) and its listing cache.

.DESCRIPTION
    Runs identity-service, merchant-service, inventory-service and
    catalog-service as real processes against a Compose Kafka and Redis. Every
    command it runs against them is a command of services/README.md, run as the
    README shows it, so the README is tested too:

      - the REST commands of "Trying catalog-service's REST API by hand" log in,
        onboard a merchant, create a product, read the listings, deactivate and
        reactivate the merchant;
      - the Kafka commands of that section produce a malformed status record and
        read catalog-service's dead-letter topic;
      - the redis-cli commands of "Looking at the data" read the cache keys,
        their TTLs and the eviction policy.

    Steps: the product is listed once its stock row is CREATED. The merchant is
    deactivated: its product leaves the cached listings, the detail answers 404,
    /internal/products says merchantActive false, and both cache versions went
    up by one. It is reactivated: the product is back. A malformed status record
    goes to merchant.status.changed.catalog-service.DLT with its exact bytes and
    our two headers only, and the next real event behind it is still applied.
    Redis: every catalog key has the catalog:v2: prefix, versions and an
    inventory counter have TTL -1, the policy is volatile-lru. With Redis
    stopped, the listing still answers from the database and a deactivation
    still hides the product. Last, that the dead-letter topic holds only the one
    malformed record, and that no service log contains the internal API key or
    a database password of this run.

    Run it from PowerShell at the repo root, with Docker running:

        powershell -ExecutionPolicy Bypass -File scripts\e2e-catalog.ps1

    It needs ports 8080 to 8083, 9092 and 6379 free (BASKETRY_REDIS_PORT
    instead of 6379 if set; with -Postgres also the Postgres port), and refuses
    to start otherwise. It stops only what it started: it records the PID of
    each service, and uses its own Compose project (basketry-e2e-catalog). It
    wipes only that project's data, before and after the run.

    With -Postgres, catalog-service runs on the Compose Postgres instead of H2
    (the other services stay on H2: they are never restarted), and
    catalog-service is force-stopped halfway. The merchant is deactivated while
    it is down; once restarted, catalog-service must pick its consumer group
    back up from the committed offset and apply that event. The new listener
    waits for the old one's session to time out (up to 45 s); the script
    reports the delay.

    inventory-service runs with a counter reconciliation every 5 s (instead of
    5 minutes), so a stock counter exists in Redis for the TTL check.

    Service logs and the tools' output go to build\e2e\catalog, which each run
    empties first. The internal API key and the database passwords are generated
    for this run and only passed through the environment.

    Exit codes:
        0  every check passed
        1  a check failed
        2  Docker is not running
        3  a port is busy
        4  Java 25 or the build is not available
        5  a service, Kafka, Redis or Postgres did not start in time
        6  services/README.md no longer has the commands this script runs

.PARAMETER TimeoutSeconds
    How long to wait for a service to start, a record to arrive, a listing to
    change, or the consumer group to be rejoined.

.PARAMETER Postgres
    Run catalog-service on Postgres (Compose profile "postgres") and add the
    restart step. The host port is BASKETRY_POSTGRES_PORT if set, else 5432.
#>
param(
    [int]$TimeoutSeconds = 90,
    [switch]$Postgres
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$identityUrl = 'http://127.0.0.1:8080'
$catalogUrl = 'http://127.0.0.1:8081'
$merchantUrl = 'http://127.0.0.1:8082'
$inventoryUrl = 'http://127.0.0.1:8083'
$redisPort = 6379
if ($env:BASKETRY_REDIS_PORT) {
    $redisPort = [int]$env:BASKETRY_REDIS_PORT
}
$ports = @(8080, 8081, 8082, 8083, 9092, $redisPort)
$postgresPort = 5432
if ($env:BASKETRY_POSTGRES_PORT) {
    $postgresPort = [int]$env:BASKETRY_POSTGRES_PORT
}
if ($Postgres) {
    $ports += $postgresPort
}
# Its own project: only its containers and volumes are ever started or deleted.
# The ports are the same as the other scripts', so still run one at a time.
$composeProject = 'basketry-e2e-catalog'
$compose = "docker compose -p $composeProject"
$composeServices = 'kafka redis'
if ($Postgres) {
    $compose = "$compose --profile postgres"
    $composeServices = 'kafka redis postgres'
}
$repoRoot = Split-Path -Parent $PSScriptRoot
# Its own folder: the log check must never read another run's or script's logs.
$logDir = Join-Path $repoRoot 'build\e2e\catalog'
$readmePath = Join-Path $repoRoot 'services\README.md'

$statusTopic = 'merchant.status.changed'
$catalogDlt = 'merchant.status.changed.catalog-service.DLT'

# The commands exactly as services/README.md shows them, each on a line of its
# own (Assert-ReadmeCommands). The REST lines are single-quoted, so their
# variables are expanded only when Invoke-Check runs them.
$readmeCommands = [ordered]@{
    AdminLogin = '$admin = (Invoke-RestMethod -Method Post http://localhost:8080/auth/login -ContentType ''application/json'' -Body ''{"email":"admin-placeholder@basketry.example","password":"ChangeMe-Before-Deploy!"}'').accessToken'
    Onboard = '$onboarded = Invoke-RestMethod -Method Post http://localhost:8082/merchants -Headers @{ Authorization = "Bearer $admin" } -ContentType ''application/json'' -Body ''{"name":"Tea House","category":"Groceries","kycVerified":true,"commissionRate":5.00,"loginEmail":"tea@example.com","initialPassword":"Tea-Pass1"}'''
    ShopId = '$shopId = $onboarded.id'
    ShopLogin = '$shop = (Invoke-RestMethod -Method Post http://localhost:8080/auth/login -ContentType ''application/json'' -Body ''{"email":"tea@example.com","password":"Tea-Pass1"}'').accessToken'
    CreateProduct = '$productId = (Invoke-RestMethod -Method Post http://localhost:8081/products -Headers @{ Authorization = "Bearer $shop" } -ContentType ''application/json'' -Body ''{"name":"Green Tea","description":"Loose leaf, 100 g","category":"Drinks","price":4.50}'').id'
    ListAll = 'Invoke-RestMethod http://localhost:8081/products | ConvertTo-Json -Depth 5'
    ManageRead = 'Invoke-RestMethod "http://localhost:8081/manage/products/$productId" -Headers @{ Authorization = "Bearer $shop" }'
    Detail = 'Invoke-RestMethod "http://localhost:8081/products/$productId"'
    InternalRead = 'Invoke-RestMethod "http://localhost:8081/internal/products?ids=$productId" -Headers @{ ''X-Internal-Api-Key'' = $env:BASKETRY_INTERNAL_API_KEY } | ConvertTo-Json -Depth 5'
    Deactivate = 'Invoke-RestMethod -Method Patch "http://localhost:8082/merchants/$($onboarded.merchantCode)" -Headers @{ Authorization = "Bearer $admin" } -ContentType ''application/json'' -Body ''{"active":false}'''
    ListMerchant = 'Invoke-RestMethod "http://localhost:8081/products?merchantId=$shopId" | ConvertTo-Json -Depth 5'
    ProduceStatus = "docker compose exec kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:29092 --topic $statusTopic --property parse.key=true --property key.separator=`"|`""
    BadStatusRecord = '1|{"schemaVersion":1,"merchantId":1,"active":"false","version":99}'
    ReadCatalogDlt = "docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 --topic $catalogDlt --from-beginning --property print.key=true --property print.headers=true --timeout-ms 10000"
    ScanCache = 'docker compose exec redis redis-cli --scan --pattern "catalog:v2:*"'
    TtlVersion = 'docker compose exec redis redis-cli TTL catalog:v2:ver:all'
    EvictionPolicy = 'docker compose exec redis redis-cli CONFIG GET maxmemory-policy'
    ReadCounter = 'docker compose exec redis redis-cli GET inventory:stock:42'
}
# The shell commands above, by the container they run in.
$composeExecPrefix = 'docker compose exec '

$script:startedProcesses = @()
$script:composeStarted = $false
$script:serviceEnvironment = @{}
$script:secrets = @()
$script:fileNumber = 0
$script:rejoinSeconds = $null

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
    $secret = -join ($bytes | ForEach-Object { $_.ToString('x2') })
    $script:secrets += $secret
    return $secret
}

function Write-Utf8NoBom([string]$path, [string]$text) {
    [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))
}

function New-LogFile([string]$label) {
    $script:fileNumber++
    return Join-Path $logDir ('{0:D2}-{1}' -f $script:fileNumber, $label)
}

# ---- The README commands -----------------------------------------------------

# A README shell command, turned into the one this script runs. The only changes
# are the Compose project (-p basketry-e2e-catalog), and -T for the producer,
# whose input comes from a file instead of the keyboard. Anything else must
# already be in the README command, so a changed README cannot go unnoticed.
function Get-RunnableCommand([string]$readmeCommand, [switch]$FromFile) {
    if (-not $readmeCommand.StartsWith($composeExecPrefix)) {
        Stop-Check "Script error: '$readmeCommand' does not start with '$composeExecPrefix'"
    }
    $exec = 'exec'
    if ($FromFile) {
        $exec = 'exec -T'
    }
    return "docker compose -p $composeProject $exec " + $readmeCommand.Substring($composeExecPrefix.Length)
}

# One README command with one exact part swapped, for the keys the README only
# names. The part must occur exactly once.
function Get-VariantCommand([string]$readmeCommand, [string]$old, [string]$new) {
    $count = ([regex]::Matches($readmeCommand, [regex]::Escape($old))).Count
    if ($count -ne 1) {
        Stop-Check "Script error: '$old' occurs $count times in '$readmeCommand'"
    }
    return $readmeCommand.Replace($old, $new)
}

function Assert-ReadmeCommands {
    $lines = Get-Content -Path $readmePath -Encoding UTF8 | ForEach-Object { $_.TrimEnd() }
    $missing = @($readmeCommands.Keys | Where-Object { $lines -notcontains $readmeCommands[$_] })
    if ($missing.Count -gt 0) {
        $shown = ($missing | ForEach-Object { "    $($readmeCommands[$_])" }) -join "`n"
        Stop-Check "services/README.md no longer has these commands, each on a line of its own:`n$shown" 6
    }
    Write-Pass "services/README.md has all $($readmeCommands.Count) commands this script runs"
}

# ---- Kafka and Redis ---------------------------------------------------------

# Records are "key|value" lines in a UTF-8 file without a BOM: a BOM would become
# part of the first key. No JSON is ever put on a command line.
function Send-Records([string]$readmeCommand, [string[]]$records, [string]$label) {
    $recordFile = New-LogFile "$label.records.txt"
    Write-Utf8NoBom $recordFile (($records -join "`n") + "`n")
    $out = New-LogFile "$label.log"
    $command = Get-RunnableCommand $readmeCommand -FromFile
    & cmd.exe /c "$command < `"$recordFile`" > `"$out`" 2>&1"
    if ($LASTEXITCODE -ne 0) {
        Stop-Check "Producing '$label' failed (exit $LASTEXITCODE); see $out"
    }
    Write-Pass "produced $($records.Count) record(s) to $label"
}

# Every record of a topic, from its beginning: the console consumer stops 10 s
# after the last one. Each is @{ Headers; Key; Value } (the README command prints headers).
function Read-Topic([string]$readmeCommand, [string]$label) {
    $out = New-LogFile "$label.out.txt"
    $err = New-LogFile "$label.err.txt"
    $command = Get-RunnableCommand $readmeCommand
    & cmd.exe /c "$command > `"$out`" 2> `"$err`""
    $records = @()
    foreach ($line in [System.IO.File]::ReadAllLines($out)) {
        $parts = $line.Split("`t")
        if ($parts.Count -ge 3) {
            $records += [pscustomobject]@{ Headers = $parts[0]; Key = $parts[1]; Value = ($parts[2..($parts.Count - 1)] -join "`t") }
        }
    }
    return $records
}

# Reads the topic again until at least $expected records have this key.
function Wait-ForRecords([string]$readmeCommand, [string]$label, [string]$key, [int]$expected) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $started = Get-Date
    do {
        $all = Read-Topic $readmeCommand $label
        $mine = @($all | Where-Object { $_.Key -eq $key })
        if ($mine.Count -ge $expected) {
            $seconds = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
            Write-Pass "$label has $($mine.Count) record(s) for key $key (read in $seconds s)"
            return [pscustomobject]@{ All = $all; Mine = $mine; Seconds = $seconds }
        }
    } while ((Get-Date) -lt $deadline)
    Stop-Check "$label did not get $expected record(s) for key $key within $TimeoutSeconds s"
}

# One redis-cli command (a README line, or a variant of one), its output lines.
function Invoke-Redis([string]$readmeCommand, [string]$label) {
    $out = New-LogFile "redis-$label.txt"
    $exit = Invoke-Quietly (Get-RunnableCommand $readmeCommand) $out
    if ($exit -ne 0) {
        Stop-Check "redis-cli failed (exit $exit); see $out"
    }
    return @([System.IO.File]::ReadAllLines($out) | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' })
}

# GET or TTL of one key, through the README's TTL command with only the command and key swapped.
function Read-RedisKey([string]$verb, [string]$key) {
    $command = Get-VariantCommand $readmeCommands.TtlVersion 'TTL catalog:v2:ver:all' "$verb $key"
    $lines = @(Invoke-Redis $command "$verb-$($key -replace '[^A-Za-z0-9]', '_')")
    if ($lines.Count -eq 0) {
        return ''
    }
    return $lines[0]
}

function Read-Version([string]$scope) {
    $value = Read-RedisKey 'GET' "catalog:v2:ver:$scope"
    if ($value -eq '') {
        return 0
    }
    return [long]$value
}

# ---- The services ------------------------------------------------------------

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

function Wait-ForHealth([string]$name, [string]$url, $process) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ($process.HasExited) {
            Stop-Check "$name exited during startup (code $($process.ExitCode)); see $logDir" 5
        }
        try {
            $health = Invoke-RestMethod -Uri "$url/actuator/health" -TimeoutSec 2
            if ($health.status -eq 'UP') {
                Write-Pass "$name is UP"
                return
            }
        } catch {
            # Not listening yet.
        }
        Start-Sleep -Milliseconds 500
    }
    Stop-Check "$name did not start within $TimeoutSeconds s; see $logDir" 5
}

function Start-Catalog([string]$logName) {
    $process = Start-Service-Jar 'catalog-service' $logName
    Wait-ForHealth 'catalog-service' $catalogUrl $process
}

# Stops one service this script started, and forgets it. A forced stop: the
# consumer never leaves its group, as after a crash.
function Stop-StartedService([string]$name, [int]$port) {
    foreach ($started in @($script:startedProcesses | Where-Object { $_.Name -eq $name })) {
        Stop-ProcessTree $started.Id
        $started.Process.WaitForExit(15000) | Out-Null
        Write-Pass "stopped $name (PID $($started.Id))"
    }
    $script:startedProcesses = @($script:startedProcesses | Where-Object { $_.Name -ne $name })
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while (Test-PortBusy $port) {
        if ((Get-Date) -gt $deadline) {
            Stop-Check "Port $port is still busy after stopping $name" 5
        }
        Start-Sleep -Milliseconds 250
    }
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

# Seconds from "Started CatalogServiceApplication" to the first assignment of
# the status topic, from the service's own log: how long the group took to let it in.
function Get-RejoinSeconds([string]$logName) {
    $log = Join-Path $logDir "$logName.out.log"
    $started = Select-String -Path $log -Pattern 'Started CatalogServiceApplication' | Select-Object -First 1
    $assigned = Select-String -Path $log -Pattern "partitions assigned: \[$([regex]::Escape($statusTopic))" | Select-Object -First 1
    if ($null -eq $started -or $null -eq $assigned) {
        return $null
    }
    $from = [datetime]::Parse($started.Line.Substring(0, 23))
    $to = [datetime]::Parse($assigned.Line.Substring(0, 23))
    return [math]::Round(($to - $from).TotalSeconds, 1)
}

function Invoke-Psql([string]$sql) {
    $out = New-LogFile 'psql.txt'
    $exit = Invoke-Quietly "$compose exec -T postgres psql -U catalog -d catalog -At -c `"$sql`"" $out
    if ($exit -ne 0) {
        Stop-Check "psql failed (exit $exit); see $out"
    }
    return ([System.IO.File]::ReadAllText($out)).Trim()
}

function Read-Shared([string]$path) {
    $stream = New-Object System.IO.FileStream($path, 'Open', 'Read', 'ReadWrite')
    try {
        return (New-Object System.IO.StreamReader($stream)).ReadToEnd()
    } finally {
        $stream.Dispose()
    }
}

# The README's listing lines answer JSON text; these turn it into product ids.
function Get-ListedIds([string]$readmeCommand) {
    $page = (Invoke-Expression $readmeCommand) | ConvertFrom-Json
    return @($page.content | ForEach-Object { [long]$_.id })
}

# The HTTP status of the README's detail line: 200, or the refusal's status.
function Get-DetailStatus {
    try {
        Invoke-Expression $readmeCommands.Detail | Out-Null
        return 200
    } catch [System.Net.WebException] {
        if ($null -eq $_.Exception.Response) {
            throw
        }
        return [int]$_.Exception.Response.StatusCode
    }
}

# Polls both public listings until the product is (or is no longer) in them;
# returns the seconds it took.
function Wait-ForListing([bool]$listed, [string]$why) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $started = Get-Date
    do {
        $inAll = (Get-ListedIds $readmeCommands.ListAll) -contains $productId
        $inMerchant = (Get-ListedIds $readmeCommands.ListMerchant) -contains $productId
        if ($inAll -eq $listed -and $inMerchant -eq $listed) {
            $seconds = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
            $state = if ($listed) { 'is listed' } else { 'left both listings' }
            Write-Pass "the product $state ($why), after $seconds s"
            return $seconds
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    Stop-Check "the product was not $(if ($listed) { 'listed' } else { 'hidden' }) within $TimeoutSeconds s ($why)"
}

function Get-MerchantActive {
    $answer = (Invoke-Expression $readmeCommands.InternalRead) | ConvertFrom-Json
    return [bool]$answer.content[0].merchantActive
}

# The README's deactivation line, or the same line with true, as Admin.
function Set-MerchantActive([bool]$active) {
    $command = $readmeCommands.Deactivate
    if ($active) {
        $command = Get-VariantCommand $command '{"active":false}' '{"active":true}'
    }
    $patched = Invoke-Expression $command
    Assert-True ($patched.active -eq $active) "merchant-service has active=$($active.ToString().ToLower())"
}

# ---- The check ---------------------------------------------------------------

function Invoke-Check {
    Write-Step 'Checking prerequisites'
    Assert-ReadmeCommands
    if ((Invoke-Quietly 'docker info' $null) -ne 0) {
        Stop-Check 'Docker is not running. Start Docker Desktop and run this again.' 2
    }
    Write-Pass 'Docker is running'

    $busy = @($ports | Where-Object { Test-PortBusy $_ })
    if ($busy.Count -gt 0) {
        Stop-Check "Port(s) $($busy -join ', ') are busy. Stop whatever uses them and run this again; nothing was started." 3
    }
    Write-Pass "ports $($ports -join ', ') are free"

    # A run that was killed may have left its own project behind; nothing else is touched.
    if (Test-Path $logDir) {
        Remove-Item -Recurse -Force $logDir
    }
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    Invoke-Quietly "docker compose -p $composeProject --profile postgres down -v" (Join-Path $logDir 'compose-cleanup.log') | Out-Null

    Invoke-Quietly 'java -version' (Join-Path $logDir 'java-version.log') | Out-Null
    if (-not (Select-String -Path (Join-Path $logDir 'java-version.log') -Pattern 'version "25' -Quiet)) {
        Stop-Check 'Java 25 must be the java on PATH.' 4
    }
    Write-Pass 'Java 25 is on PATH'

    Write-Step 'Building identity-service, merchant-service, inventory-service and catalog-service'
    $build = Invoke-Quietly '.\gradlew.bat --no-daemon --console=plain :identity-service:bootJar :merchant-service:bootJar :inventory-service:bootJar :catalog-service:bootJar' (Join-Path $logDir 'build.log')
    if ($build -ne 0) {
        Stop-Check "The build failed; see $logDir\build.log" 4
    }
    Write-Pass 'boot jars built'

    $apiKey = New-Secret
    $script:serviceEnvironment = @{
        'identity-service' = @{ BASKETRY_INTERNAL_API_KEY = $apiKey }
        'merchant-service' = @{ BASKETRY_INTERNAL_API_KEY = $apiKey }
        # Every 5 s instead of 5 minutes: a counter exists in Redis soon after the stock row.
        'inventory-service' = @{ BASKETRY_INTERNAL_API_KEY = $apiKey; BASKETRY_STOCKCOUNTER_RECONCILIATION_INTERVAL = '5s' }
        'catalog-service' = @{ BASKETRY_INTERNAL_API_KEY = $apiKey }
    }
    $composeEnvironment = @{}
    $infrastructure = 'Kafka and Redis'
    if ($Postgres) {
        $infrastructure = 'Kafka, Redis and Postgres'
        $catalogDbPassword = New-Secret
        $composeEnvironment = @{
            BASKETRY_POSTGRES_ADMIN_PASSWORD = New-Secret
            BASKETRY_IDENTITY_DB_PASSWORD = New-Secret
            BASKETRY_MERCHANT_DB_PASSWORD = New-Secret
            BASKETRY_PAYMENT_DB_PASSWORD = New-Secret
            BASKETRY_INVENTORY_DB_PASSWORD = New-Secret
            BASKETRY_CATALOG_DB_PASSWORD = $catalogDbPassword
        }
        $script:serviceEnvironment['catalog-service'].SPRING_PROFILES_ACTIVE = 'postgres'
        $script:serviceEnvironment['catalog-service'].BASKETRY_POSTGRES_PORT = "$postgresPort"
        $script:serviceEnvironment['catalog-service'].BASKETRY_DB_PASSWORD = $catalogDbPassword
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

    # The status topic does not exist yet: merchant-service creates it on the
    # first status change, and catalog-service must start without it.
    Write-Step 'Starting the four services (the status topic does not exist yet)'
    $identity = Start-Service-Jar 'identity-service'
    $merchantProcess = Start-Service-Jar 'merchant-service'
    $inventory = Start-Service-Jar 'inventory-service'
    Wait-ForService 'identity-service' "$identityUrl/.well-known/jwks.json" $identity
    Wait-ForService 'merchant-service' "$merchantUrl/merchants" $merchantProcess
    Wait-ForHealth 'inventory-service' $inventoryUrl $inventory
    Start-Catalog 'catalog-service'

    Write-Step 'Onboarding a merchant and creating a product, with the README REST commands'
    # The README's own lines, run in this scope so that each one sees the
    # variables the previous ones set. The key is in this session only while
    # an internal call runs.
    Invoke-Expression $readmeCommands.AdminLogin
    Invoke-Expression $readmeCommands.Onboard
    Invoke-Expression $readmeCommands.ShopId
    Invoke-Expression $readmeCommands.ShopLogin
    Assert-True ($shopId -eq 1) 'the merchant is merchant 1, the one the README''s malformed record names'
    Invoke-Expression $readmeCommands.CreateProduct
    Assert-True ($productId -gt 0) "product $productId created"

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $managed = Invoke-Expression $readmeCommands.ManageRead
        if ($managed.stockRow -eq 'CREATED') {
            break
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    Assert-True ($managed.stockRow -eq 'CREATED') 'the relay created its stock row in inventory-service (stockRow CREATED)'
    Wait-ForListing $true 'a new product of an active merchant' | Out-Null
    # Read again, so the pages are served from the cache from here on.
    Wait-ForListing $true 'read again, from the cache' | Out-Null
    $pages = @(Invoke-Redis $readmeCommands.ScanCache 'scan-before' | Where-Object { $_ -like 'catalog:v2:list:*' })
    Assert-True ($pages.Count -ge 2) "both listings are cached ($($pages.Count) page keys)"
    $env:BASKETRY_INTERNAL_API_KEY = $apiKey
    try {
        Assert-True (Get-MerchantActive) '/internal/products says merchantActive true (no status event yet)'
    } finally {
        Remove-Item Env:\BASKETRY_INTERNAL_API_KEY -ErrorAction SilentlyContinue
    }

    Write-Step 'Deactivating the merchant'
    $allBefore = Read-Version 'all'
    $ownBefore = Read-Version "m:$shopId"
    Set-MerchantActive $false
    # The first change creates the topic; the consumer notices it within 10 s.
    Wait-ForListing $false 'the first status change, which also creates the topic' | Out-Null
    Assert-True ((Get-DetailStatus) -eq 404) 'the public detail answers 404'
    $env:BASKETRY_INTERNAL_API_KEY = $apiKey
    try {
        Assert-True (-not (Get-MerchantActive)) '/internal/products says merchantActive false'
    } finally {
        Remove-Item Env:\BASKETRY_INTERNAL_API_KEY -ErrorAction SilentlyContinue
    }
    Assert-True ((Read-Version 'all') -eq $allBefore + 1) "catalog:v2:ver:all went up by one ($allBefore to $($allBefore + 1))"
    Assert-True ((Read-Version "m:$shopId") -eq $ownBefore + 1) "catalog:v2:ver:m:$shopId went up by one"

    Write-Step 'Reactivating the merchant'
    Set-MerchantActive $true
    Wait-ForListing $true 'reactivated' | Out-Null
    Assert-True ((Get-DetailStatus) -eq 200) 'the public detail answers 200 again'

    Write-Step 'A malformed status record, then a real event behind it'
    Send-Records $readmeCommands.ProduceStatus @($readmeCommands.BadStatusRecord) $statusTopic
    # Same key, so same partition: the deactivation is applied only if the bad record did not block it.
    Set-MerchantActive $false
    Wait-ForListing $false 'the event behind the malformed record' | Out-Null
    $dead = Wait-ForRecords $readmeCommands.ReadCatalogDlt $catalogDlt '1' 1
    $deadLetter = $dead.Mine[0]
    $badValue = $readmeCommands.BadStatusRecord.Substring(2)
    Assert-True ($deadLetter.Value -ceq $badValue) 'its value is the record as sent, byte for byte'
    Assert-True ($deadLetter.Headers.Contains('kafka_dlt-exception-fqcn:com.basketry.catalog.merchantstatus.InvalidMerchantStatusEventException')) 'its exception class is InvalidMerchantStatusEventException'
    Assert-True ($deadLetter.Headers.Contains('kafka_dlt-exception-message:active must be a JSON boolean')) 'its message names the rule'
    Assert-True ($deadLetter.Headers.Contains("kafka_dlt-original-topic:$statusTopic")) 'it names the original topic'
    Assert-True (-not $deadLetter.Headers.Contains('kafka_dlt-exception-stacktrace')) 'it has no stack-trace header'
    Assert-True (-not $deadLetter.Headers.Contains('kafka_dlt-exception-cause-fqcn')) 'it has no cause header'
    Set-MerchantActive $true
    Wait-ForListing $true 'reactivated again' | Out-Null

    Write-Step 'Checking the Redis keys and policy'
    $allKeys = @(Invoke-Redis (Get-VariantCommand $readmeCommands.ScanCache '"catalog:v2:*"' '"catalog:*"') 'scan-all')
    $foreign = @($allKeys | Where-Object { -not $_.StartsWith('catalog:v2:') })
    Assert-True ($allKeys.Count -gt 0 -and $foreign.Count -eq 0) "all $($allKeys.Count) catalog keys have the catalog:v2: prefix"
    Assert-True (@(Invoke-Redis $readmeCommands.TtlVersion 'ttl-all')[0] -eq '-1') 'catalog:v2:ver:all has TTL -1 (never evicted)'
    Assert-True ((Read-RedisKey 'TTL' "catalog:v2:ver:m:$shopId") -eq '-1') "catalog:v2:ver:m:$shopId has TTL -1"
    $page = @($allKeys | Where-Object { $_ -like 'catalog:v2:list:*' } | Select-Object -First 1)
    if ($page.Count -eq 0) {
        Wait-ForListing $true 'read once more, to store a page' | Out-Null
        $page = @(Invoke-Redis $readmeCommands.ScanCache 'scan-pages' | Where-Object { $_ -like 'catalog:v2:list:*' } | Select-Object -First 1)
    }
    $pageTtl = [int](Read-RedisKey 'TTL' $page[0])
    Assert-True ($pageTtl -gt 0 -and $pageTtl -le 60) "a cached page has a TTL ($pageTtl s, at most 60)"
    $policy = @(Invoke-Redis $readmeCommands.EvictionPolicy 'policy')
    Assert-True ($policy.Count -ge 2 -and $policy[1] -eq 'volatile-lru') 'maxmemory-policy is volatile-lru'
    $counterKey = "inventory:stock:$productId"
    $counterTtl = Get-VariantCommand $readmeCommands.ReadCounter 'GET inventory:stock:42' "TTL $counterKey"
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $ttl = @(Invoke-Redis $counterTtl 'counter-ttl')[0]
        if ($ttl -ne '-2') {
            break
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    Assert-True ($ttl -eq '-1') "the inventory counter $counterKey has TTL -1 (never evicted)"

    Write-Step 'Stopping Redis: the listing answers from the database'
    if ((Invoke-Quietly "$compose stop redis" (New-LogFile 'redis-stop.log')) -ne 0) {
        Stop-Check 'Could not stop Redis'
    }
    Write-Pass 'Redis is stopped'
    Wait-ForListing $true 'Redis down, read from the database' | Out-Null
    Set-MerchantActive $false
    Wait-ForListing $false 'deactivated while Redis is down' | Out-Null
    if ((Invoke-Quietly "$compose up -d --wait redis" (New-LogFile 'redis-start.log')) -ne 0) {
        Stop-Check 'Could not start Redis again' 5
    }
    Write-Pass 'Redis is back (empty: it keeps nothing)'
    Set-MerchantActive $true
    Wait-ForListing $true 'reactivated with Redis back' | Out-Null

    $script:rejoinSeconds = $null
    if ($Postgres) {
        Write-Step 'Restarting catalog-service: the merchant is deactivated while it is down'
        Stop-StartedService 'catalog-service' 8081
        Set-MerchantActive $false
        Start-Catalog 'catalog-service-restarted'
        Wait-ForListing $false 'the event published while catalog-service was down' | Out-Null
        $script:rejoinSeconds = Get-RejoinSeconds 'catalog-service-restarted'
        Write-Pass "the restarted listener got its partitions $($script:rejoinSeconds) s after startup"
        $managed = Invoke-Expression $readmeCommands.ManageRead
        Assert-True ($managed.id -eq $productId -and $managed.stockRow -eq 'CREATED') 'the product and its stock row survived the restart (Postgres)'

        Write-Step 'Checking the stored status'
        Assert-True ((Invoke-Psql "select active || ',' || version from merchant_status where merchant_id = $shopId") -eq 'false,7') 'merchant_status holds active false at version 7 (seven changes, the last one while down)'
    }

    Write-Step 'Checking that only the malformed record was dead-lettered'
    $allDead = @(Read-Topic $readmeCommands.ReadCatalogDlt "$catalogDlt-all")
    Assert-True ($allDead.Count -eq 1) "$catalogDlt holds exactly one record"

    Write-Step 'Checking the service logs'
    # Stopped first, so each log is complete; read shared in case a handle lingers.
    foreach ($service in @(@('catalog-service', 8081), @('inventory-service', 8083), @('merchant-service', 8082), @('identity-service', 8080))) {
        Stop-StartedService $service[0] $service[1]
    }
    $logs = @(Get-ChildItem $logDir -Filter '*-service*.log')
    $text = ($logs | ForEach-Object { Read-Shared $_.FullName }) -join "`n"
    Assert-True ($text -match "Sending record .* to $([regex]::Escape($catalogDlt))") 'the logs have the dead-letter line for the malformed record'
    Assert-True ($text.Contains("Could not invalidate the cached listings of merchant $shopId")) 'catalog-service warned that the listings could not be invalidated while Redis was down'
    $leaked = @($script:secrets | Where-Object { $text.Contains($_) })
    Assert-True ($leaked.Count -eq 0) "no line of the $($logs.Count) service log files contains the internal API key or a database password of this run"
}

$exitCode = 0
$runStarted = Get-Date
Push-Location $repoRoot
try {
    Invoke-Check
    Write-Host ''
    $total = [math]::Round(((Get-Date) - $runStarted).TotalSeconds)
    Write-Host "PASSED: every check passed in $total s."
    if ($null -ne $script:rejoinSeconds) {
        Write-Host "Consumer-group rejoin after the restart: $($script:rejoinSeconds) s."
    }
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
