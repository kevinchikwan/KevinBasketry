<#
.SYNOPSIS
    End-to-end check of inventory-service's Kafka side (DESIGN.md Section 5).

.DESCRIPTION
    Runs identity-service, merchant-service and inventory-service as real
    processes against a Compose Kafka and Redis. Every command it runs against
    them is a command of services/README.md, run as the README shows it, so the
    README is tested too:

      - the REST commands of "Trying inventory-service's REST API by hand" log
        in, onboard a merchant, create the stock row of product 42 and give it
        10 units;
      - the Kafka commands of "Sending inventory-service a request by hand"
        create the request topics, produce the requests and read the results
        and dead-letter topics;
      - the redis-cli command of "Looking at the data" reads the counter.

    Requests: the README's reserve (order 7, product 42, 3 units) and the same
    record again; a reserve over the limit; a reserve of a product with no stock
    row; a release before its reserve, then that reserve; a malformed reserve and
    a malformed release; then the release of order 7. It checks every result,
    the stock and the counter after each step, both dead-letter topics with
    their exact bytes and headers, that nothing was answered for the malformed
    records, and that no service log contains the internal API key or a
    database password of this run.

    Run it from PowerShell at the repo root, with Docker running:

        powershell -ExecutionPolicy Bypass -File scripts\e2e-inventory.ps1

    It needs ports 8080, 8082, 8083, 9092 and 6379 free (BASKETRY_REDIS_PORT
    instead of 6379 if set; with -Postgres also the Postgres port), and refuses
    to start otherwise. It stops only what it started: it records the PID of
    each service, and uses its own Compose project (basketry-e2e-inventory). It
    wipes only that project's data, before and after the run.

    With -Postgres, inventory-service runs on the Compose Postgres instead of H2
    (identity-service and merchant-service stay on H2: they are never
    restarted), and inventory-service is restarted halfway, after the counter of
    product 42 was set to a wrong value: startup reconciliation must load it
    from Postgres again, a replayed reserve must answer "reserved" and take
    nothing, and a replayed release must give nothing back. The restart is a
    forced stop, so the new listeners wait for the old ones' session to time out
    (up to 45 s); the script reports the delay.

    Service logs and the tools' output go to build\e2e\inventory, which each run
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
    How long to wait for a service to start, and for a record to arrive.

.PARAMETER Postgres
    Run inventory-service on Postgres (Compose profile "postgres") and add the
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
$inventoryUrl = 'http://127.0.0.1:8083'
$redisPort = 6379
if ($env:BASKETRY_REDIS_PORT) {
    $redisPort = [int]$env:BASKETRY_REDIS_PORT
}
$ports = @(8080, 8082, 8083, 9092, $redisPort)
$postgresPort = 5432
if ($env:BASKETRY_POSTGRES_PORT) {
    $postgresPort = [int]$env:BASKETRY_POSTGRES_PORT
}
if ($Postgres) {
    $ports += $postgresPort
}
# Its own project: only its containers and volumes are ever started or deleted.
# The ports are the same as the other scripts', so still run one at a time.
$composeProject = 'basketry-e2e-inventory'
$compose = "docker compose -p $composeProject"
$composeServices = 'kafka redis'
if ($Postgres) {
    $compose = "$compose --profile postgres"
    $composeServices = 'kafka redis postgres'
}
$repoRoot = Split-Path -Parent $PSScriptRoot
# Its own folder: the log check must never read another run's or script's logs.
$logDir = Join-Path $repoRoot 'build\e2e\inventory'
$readmePath = Join-Path $repoRoot 'services\README.md'

$reserveTopic = 'order.stock-reserve.requested'
$releaseTopic = 'order.stock-release.requested'
$reservedTopic = 'order.stock.reserved'
$failedTopic = 'order.stock.reservation-failed'
$releasedTopic = 'order.stock.released'
$reserveDlt = "$reserveTopic.DLT"
$releaseDlt = "$releaseTopic.DLT"
$counterKey = 'inventory:stock:42'

# The commands exactly as services/README.md shows them, each on a line of its
# own (Assert-ReadmeCommands). The REST lines are single-quoted, so their
# variables are expanded only when Invoke-Check runs them.
$readmeCommands = [ordered]@{
    CreateReserve = "docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --create --if-not-exists --topic $reserveTopic --partitions 3 --replication-factor 1"
    CreateRelease = "docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --create --if-not-exists --topic $releaseTopic --partitions 3 --replication-factor 1"
    Produce = "docker compose exec kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:29092 --topic $reserveTopic --property parse.key=true --property key.separator=`"|`""
    ReserveRecord = '7|{"schemaVersion":1,"orderId":7,"productId":42,"quantity":3}'
    ReadResults = "docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 --topic $reservedTopic --from-beginning --property print.key=true --timeout-ms 10000"
    ReadDeadLetters = "docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 --topic $reserveDlt --from-beginning --property print.key=true --property print.headers=true --timeout-ms 10000"
    ReadCounter = "docker compose exec redis redis-cli GET $counterKey"
    AdminLogin = '$admin = (Invoke-RestMethod -Method Post http://localhost:8080/auth/login -ContentType ''application/json'' -Body ''{"email":"admin-placeholder@basketry.example","password":"ChangeMe-Before-Deploy!"}'').accessToken'
    CreateMerchant = '$merchantId = (Invoke-RestMethod -Method Post http://localhost:8082/merchants -Headers @{ Authorization = "Bearer $admin" } -ContentType ''application/json'' -Body ''{"name":"Corner Shop","category":"Groceries","kycVerified":true,"commissionRate":5.00,"loginEmail":"shop@example.com","initialPassword":"Shop-Pass1"}'').id'
    MerchantLogin = '$merchant = (Invoke-RestMethod -Method Post http://localhost:8080/auth/login -ContentType ''application/json'' -Body ''{"email":"shop@example.com","password":"Shop-Pass1"}'').accessToken'
    InternalKey = '$key = @{ ''X-Internal-Api-Key'' = $env:BASKETRY_INTERNAL_API_KEY }'
    CreateStock = 'Invoke-RestMethod -Method Post http://localhost:8083/internal/stock -Headers $key -ContentType ''application/json'' -Body "{`"productId`":42,`"merchantId`":$merchantId}"'
    AddStock = 'Invoke-RestMethod -Method Post http://localhost:8083/stock/42/adjustments -Headers @{ Authorization = "Bearer $merchant" } -ContentType ''application/json'' -Body ''{"delta":10}'''
    ReadStock = 'Invoke-RestMethod "http://localhost:8083/internal/stock?productIds=42,43" -Headers $key'
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
# are the Compose project (-p basketry-e2e-inventory), and -T for the producer,
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

# One README command with one exact part swapped, for the topics the README only
# names ("the other result topics are ..."). The part must occur exactly once.
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

function Invoke-Kafka([string]$readmeCommand, [string]$label) {
    $out = New-LogFile "$label.log"
    $exit = Invoke-Quietly (Get-RunnableCommand $readmeCommand) $out
    if ($exit -ne 0) {
        Stop-Check "'$label' failed (exit $exit); see $out"
    }
}

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
# after the last one. Each is @{ Headers; Key; Value } (Headers only with print.headers).
function Read-Topic([string]$readmeCommand, [string]$label, [bool]$withHeaders) {
    $out = New-LogFile "$label.out.txt"
    $err = New-LogFile "$label.err.txt"
    $command = Get-RunnableCommand $readmeCommand
    & cmd.exe /c "$command > `"$out`" 2> `"$err`""
    $records = @()
    foreach ($line in [System.IO.File]::ReadAllLines($out)) {
        $parts = $line.Split("`t")
        if ($withHeaders -and $parts.Count -ge 3) {
            $records += [pscustomobject]@{ Headers = $parts[0]; Key = $parts[1]; Value = ($parts[2..($parts.Count - 1)] -join "`t") }
        } elseif (-not $withHeaders -and $parts.Count -ge 2) {
            $records += [pscustomobject]@{ Headers = ''; Key = $parts[0]; Value = ($parts[1..($parts.Count - 1)] -join "`t") }
        }
    }
    return $records
}

# Reads the topic again until at least $expected records have this key.
function Wait-ForRecords([string]$readmeCommand, [string]$label, [string]$key, [int]$expected,
                         [bool]$withHeaders = $false) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $started = Get-Date
    do {
        $all = Read-Topic $readmeCommand $label $withHeaders
        $mine = @($all | Where-Object { $_.Key -eq $key })
        if ($mine.Count -ge $expected) {
            $seconds = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
            Write-Pass "$label has $($mine.Count) record(s) for key $key (read in $seconds s)"
            return [pscustomobject]@{ All = $all; Mine = $mine; Seconds = $seconds }
        }
    } while ((Get-Date) -lt $deadline)
    Stop-Check "$label did not get $expected record(s) for key $key within $TimeoutSeconds s"
}

# The counter of product 42, by the README's redis-cli command: a number, or ''
# when the key is missing ("not loaded").
function Read-Counter {
    $out = New-LogFile 'redis-get.txt'
    $exit = Invoke-Quietly (Get-RunnableCommand $readmeCommands.ReadCounter) $out
    if ($exit -ne 0) {
        Stop-Check "redis-cli failed (exit $exit); see $out"
    }
    return ([System.IO.File]::ReadAllText($out)).Trim()
}

# The counter is written after the commit, so it can trail the result a little.
function Wait-ForCounter([string]$expected, [string]$why) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $value = ''
    do {
        $value = Read-Counter
        if ($value -eq $expected) {
            Write-Pass "the counter $counterKey is $expected ($why)"
            return
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    Stop-Check "the counter $counterKey is '$value', not $expected ($why), after $TimeoutSeconds s"
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

function Start-Inventory([string]$logName) {
    $process = Start-Service-Jar 'inventory-service' $logName
    Wait-ForHealth 'inventory-service' $inventoryUrl $process
}

# Stops one service this script started, and forgets it.
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

# Seconds from "Started InventoryServiceApplication" to the first assignment of
# the reserve topic, from the service's own log: how long the group took to let it in.
function Get-RejoinSeconds([string]$logName) {
    $log = Join-Path $logDir "$logName.out.log"
    $started = Select-String -Path $log -Pattern 'Started InventoryServiceApplication' | Select-Object -First 1
    $assigned = Select-String -Path $log -Pattern "partitions assigned: \[$reserveTopic" | Select-Object -First 1
    if ($null -eq $started -or $null -eq $assigned) {
        return $null
    }
    $from = [datetime]::Parse($started.Line.Substring(0, 23))
    $to = [datetime]::Parse($assigned.Line.Substring(0, 23))
    return [math]::Round(($to - $from).TotalSeconds, 1)
}

function Invoke-Psql([string]$sql) {
    $out = New-LogFile 'psql.txt'
    $exit = Invoke-Quietly "$compose exec -T postgres psql -U inventory -d inventory -At -c `"$sql`"" $out
    if ($exit -ne 0) {
        Stop-Check "psql failed (exit $exit); see $out"
    }
    return ([System.IO.File]::ReadAllText($out)).Trim()
}

function Request([long]$orderId, [long]$productId, [string]$quantity) {
    return "$orderId|{`"schemaVersion`":1,`"orderId`":$orderId,`"productId`":$productId,`"quantity`":$quantity}"
}

function Read-Shared([string]$path) {
    $stream = New-Object System.IO.FileStream($path, 'Open', 'Read', 'ReadWrite')
    try {
        return (New-Object System.IO.StreamReader($stream)).ReadToEnd()
    } finally {
        $stream.Dispose()
    }
}

function Get-Field([string]$json, [string]$name) {
    $property = ($json | ConvertFrom-Json).PSObject.Properties[$name]
    if ($null -eq $property) {
        return $null
    }
    return $property.Value
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

    Write-Step 'Building identity-service, merchant-service and inventory-service'
    $build = Invoke-Quietly '.\gradlew.bat --no-daemon --console=plain :identity-service:bootJar :merchant-service:bootJar :inventory-service:bootJar' (Join-Path $logDir 'build.log')
    if ($build -ne 0) {
        Stop-Check "The build failed; see $logDir\build.log" 4
    }
    Write-Pass 'boot jars built'

    $apiKey = New-Secret
    $script:serviceEnvironment = @{
        'identity-service' = @{ BASKETRY_INTERNAL_API_KEY = $apiKey }
        'merchant-service' = @{ BASKETRY_INTERNAL_API_KEY = $apiKey }
        'inventory-service' = @{ BASKETRY_INTERNAL_API_KEY = $apiKey }
    }
    $composeEnvironment = @{}
    $infrastructure = 'Kafka and Redis'
    if ($Postgres) {
        $infrastructure = 'Kafka, Redis and Postgres'
        $inventoryDbPassword = New-Secret
        $composeEnvironment = @{
            BASKETRY_POSTGRES_ADMIN_PASSWORD = New-Secret
            BASKETRY_IDENTITY_DB_PASSWORD = New-Secret
            BASKETRY_MERCHANT_DB_PASSWORD = New-Secret
            BASKETRY_PAYMENT_DB_PASSWORD = New-Secret
            BASKETRY_INVENTORY_DB_PASSWORD = $inventoryDbPassword
        }
        $script:serviceEnvironment['inventory-service'].SPRING_PROFILES_ACTIVE = 'postgres'
        $script:serviceEnvironment['inventory-service'].BASKETRY_POSTGRES_PORT = "$postgresPort"
        $script:serviceEnvironment['inventory-service'].BASKETRY_DB_PASSWORD = $inventoryDbPassword
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

    Write-Step 'Creating the request topics, as order-service will'
    Invoke-Kafka $readmeCommands.CreateReserve 'create-reserve-topic'
    Invoke-Kafka $readmeCommands.CreateRelease 'create-release-topic'
    Write-Pass "$reserveTopic and $releaseTopic exist"

    Write-Step 'Starting identity-service, merchant-service and inventory-service'
    $identity = Start-Service-Jar 'identity-service'
    $merchantProcess = Start-Service-Jar 'merchant-service'
    Wait-ForService 'identity-service' "$identityUrl/.well-known/jwks.json" $identity
    Wait-ForService 'merchant-service' "$merchantUrl/merchants" $merchantProcess
    Start-Inventory 'inventory-service'

    Write-Step 'Giving product 42 ten units, with the README REST commands'
    # The README's own lines, run in this scope so that each one sees the
    # variables the previous ones set. The key is in this session only for them.
    $env:BASKETRY_INTERNAL_API_KEY = $apiKey
    try {
        Invoke-Expression $readmeCommands.AdminLogin
        Invoke-Expression $readmeCommands.CreateMerchant
        Invoke-Expression $readmeCommands.MerchantLogin
        Invoke-Expression $readmeCommands.InternalKey
    } finally {
        Remove-Item Env:\BASKETRY_INTERNAL_API_KEY -ErrorAction SilentlyContinue
    }
    $created = Invoke-Expression $readmeCommands.CreateStock
    Assert-True ($created.outcome -eq 'CREATED' -and $created.quantity -eq 0) 'the stock row of product 42 was created at 0'
    $adjusted = Invoke-Expression $readmeCommands.AddStock
    Assert-True ($adjusted.quantity -eq 10) 'the merchant added 10 units'

    Write-Step 'Reserving: the README record, the same again, over the limit, an unknown product, a malformed one'
    $readmeReserve = $readmeCommands.ReserveRecord
    $malformedReserve = Request 11 42 '0'
    Send-Records $readmeCommands.Produce @($readmeReserve, $readmeReserve, (Request 8 42 '2000'), (Request 9 43 '1'), $malformedReserve) $reserveTopic

    # The dead letter first: the result reads below then cover the time after it,
    # so a result published for it by mistake would be seen.
    $dead = Wait-ForRecords $readmeCommands.ReadDeadLetters $reserveDlt '11' 1 $true
    $deadLetter = $dead.Mine[0]
    Assert-True ($dead.Mine.Count -eq 1) 'exactly one dead letter for the malformed reserve'
    Assert-True ($deadLetter.Value -ceq $malformedReserve.Substring(3)) 'its value is the record as sent, byte for byte'
    Assert-True ($deadLetter.Headers.Contains('kafka_dlt-exception-fqcn:com.basketry.inventory.stock.InvalidStockRequestException')) 'its exception class is InvalidStockRequestException'
    Assert-True ($deadLetter.Headers.Contains('kafka_dlt-exception-message:quantity must be positive')) 'its message names the rule'
    Assert-True ($deadLetter.Headers.Contains("kafka_dlt-original-topic:$reserveTopic")) 'it names the original topic'
    Assert-True (-not $deadLetter.Headers.Contains('kafka_dlt-exception-stacktrace')) 'it has no stack-trace header'
    Assert-True (-not $deadLetter.Headers.Contains('kafka_dlt-exception-cause-fqcn')) 'it has no cause header'

    $reserved = Wait-ForRecords $readmeCommands.ReadResults $reservedTopic '7' 2
    Assert-True ($reserved.Mine.Count -eq 2) 'the duplicate reserve was answered: two reserved results for order 7'
    Assert-True (@($reserved.Mine | Where-Object { (Get-Field $_.Value 'productId') -eq 42 -and $null -eq (Get-Field $_.Value 'quantity') }).Count -eq 2) 'both name product 42 and carry no quantity'

    $failedCommand = Get-VariantCommand $readmeCommands.ReadResults "--topic $reservedTopic " "--topic $failedTopic "
    # Keys 8 and 9 may be on different partitions: each is waited for on its own.
    $overLimit = Wait-ForRecords $failedCommand "$failedTopic-8" '8' 1
    Assert-True ((Get-Field $overLimit.Mine[0].Value 'reasonCode') -eq 'QUANTITY_OVER_LIMIT') 'order 8 (2000 units) failed with QUANTITY_OVER_LIMIT'
    $unknown = Wait-ForRecords $failedCommand "$failedTopic-9" '9' 1
    Assert-True ((Get-Field $unknown.Mine[0].Value 'reasonCode') -eq 'UNKNOWN_PRODUCT') 'order 9 (product 43, no stock row) failed with UNKNOWN_PRODUCT'

    $stock = Invoke-Expression $readmeCommands.ReadStock
    Assert-True (@($stock.content).Count -eq 1 -and $stock.content[0].productId -eq 42 -and $stock.content[0].quantity -eq 7) 'product 42 has 7 units left, and product 43 has no stock row'
    Wait-ForCounter '7' 'reserved once, although the request came twice'

    Write-Step 'Releasing before reserving, and a malformed release'
    $produceRelease = Get-VariantCommand $readmeCommands.Produce "--topic $reserveTopic " "--topic $releaseTopic "
    $malformedRelease = "12|{`"schemaVersion`":1,`"orderId`":12,`"productId`":42,`"quantity`":3,`"warehouse`":1}"
    Send-Records $produceRelease @((Request 10 42 '3'), $malformedRelease) $releaseTopic

    $releaseDltCommand = Get-VariantCommand $readmeCommands.ReadDeadLetters "--topic $reserveDlt " "--topic $releaseDlt "
    $releaseDead = Wait-ForRecords $releaseDltCommand $releaseDlt '12' 1 $true
    Assert-True ($releaseDead.Mine[0].Value -ceq $malformedRelease.Substring(3)) "the malformed release reached $releaseDlt, byte for byte"
    Assert-True ($releaseDead.Mine[0].Headers.Contains('kafka_dlt-exception-message:The value has a field that schemaVersion 1 does not have')) 'its message names the rule'

    $releasedCommand = Get-VariantCommand $readmeCommands.ReadResults "--topic $reservedTopic " "--topic $releasedTopic "
    $released = Wait-ForRecords $releasedCommand $releasedTopic '10' 1
    Assert-True ($released.Mine.Count -eq 1) 'the release before its reserve was answered "released"'

    Send-Records $readmeCommands.Produce @((Request 10 42 '3')) "$reserveTopic (the late reserve)"
    $late = Wait-ForRecords $failedCommand "$failedTopic-late" '10' 1
    Assert-True ((Get-Field $late.Mine[0].Value 'reasonCode') -eq 'RELEASED_BEFORE_RESERVE') 'the late reserve failed with RELEASED_BEFORE_RESERVE'
    $stock = Invoke-Expression $readmeCommands.ReadStock
    Assert-True ($stock.content[0].quantity -eq 7) 'and took nothing: product 42 still has 7 units'
    Wait-ForCounter '7' 'the late reserve took nothing from it'

    Write-Step 'Releasing order 7'
    Send-Records $produceRelease @((Request 7 42 '3')) "$releaseTopic (order 7)"
    Wait-ForRecords $releasedCommand "$releasedTopic-7" '7' 1 | Out-Null
    $stock = Invoke-Expression $readmeCommands.ReadStock
    Assert-True ($stock.content[0].quantity -eq 10) 'the 3 units went back: product 42 has 10 units again'
    Wait-ForCounter '10' 'the release gave its 3 units back'

    Write-Step 'Checking that nothing was answered for the malformed records'
    $allReserved = Read-Topic $readmeCommands.ReadResults "$reservedTopic-all" $false
    $allFailed = Read-Topic $failedCommand "$failedTopic-all" $false
    $allReleased = Read-Topic $releasedCommand "$releasedTopic-all" $false
    $answeredKeys = @($allReserved + $allFailed + $allReleased | ForEach-Object { $_.Key })
    Assert-True (($answeredKeys -notcontains '11') -and ($answeredKeys -notcontains '12')) 'no result topic has a record for order 11 or 12'
    Assert-True (@($allReserved | Where-Object { $_.Key -eq '10' }).Count -eq 0) 'order 10 was never reserved'

    $script:rejoinSeconds = $null
    if ($Postgres) {
        Write-Step 'Restarting inventory-service: Postgres keeps its rows, Redis gets a wrong counter'
        $set = Invoke-Quietly "$compose exec -T redis redis-cli SET $counterKey 999" (New-LogFile 'redis-set.txt')
        if ($set -ne 0) {
            Stop-Check 'redis-cli SET failed'
        }
        Assert-True ((Read-Counter) -eq '999') 'the counter was set to 999 by hand'
        Stop-StartedService 'inventory-service' 8083
        Start-Inventory 'inventory-service-restarted'
        Wait-ForCounter '10' 'startup reconciliation loaded it from Postgres again'

        Send-Records $readmeCommands.Produce @($readmeReserve) "$reserveTopic (replayed after the restart)"
        $replayed = Wait-ForRecords $readmeCommands.ReadResults "$reservedTopic-after-restart" '7' 3
        $script:rejoinSeconds = Get-RejoinSeconds 'inventory-service-restarted'
        Write-Pass "the restarted listeners got their partitions $($script:rejoinSeconds) s after startup"
        Assert-True ($replayed.Mine.Count -eq 3) 'the replayed reserve was answered "reserved" again'
        Send-Records $produceRelease @((Request 7 42 '3')) "$releaseTopic (replayed after the restart)"
        $releasedAgain = Wait-ForRecords $releasedCommand "$releasedTopic-after-restart" '7' 2
        Assert-True ($releasedAgain.Mine.Count -eq 2) 'the replayed release was answered "released" again'
        $stock = Invoke-Expression $readmeCommands.ReadStock
        Assert-True ($stock.content[0].quantity -eq 10) 'neither took nor gave back anything: product 42 still has 10 units'
        Wait-ForCounter '10' 'the replays did not change it'

        Write-Step 'Checking the stored rows'
        Assert-True ((Invoke-Psql 'select count(*) from stock_reservation') -eq '4') 'four reservation rows (orders 7, 8, 9 and 10): nothing was stored twice'
        Assert-True ((Invoke-Psql 'select status from stock_reservation where order_id = 7') -eq 'RELEASED') 'order 7 is RELEASED'
        Assert-True ((Invoke-Psql "select reason_code from stock_reservation where order_id = 10") -eq 'RELEASED_BEFORE_RESERVE') 'order 10 is the tombstone'
        Assert-True ((Invoke-Psql "select count(*) from outbox_event where status <> 'SENT'") -eq '0') 'every result was published'
    }

    Write-Step 'Checking the service logs'
    # Stopped first, so each log is complete; read shared in case a handle lingers.
    Stop-StartedService 'inventory-service' 8083
    Stop-StartedService 'merchant-service' 8082
    Stop-StartedService 'identity-service' 8080
    $logs = @(Get-ChildItem $logDir -Filter '*-service*.log')
    $text = ($logs | ForEach-Object { Read-Shared $_.FullName }) -join "`n"
    Assert-True ($text -match "Sending record .* to $([regex]::Escape($reserveDlt))") 'the logs have the dead-letter line for the malformed reserve'
    Assert-True ($text -match "Sending record .* to $([regex]::Escape($releaseDlt))") 'and for the malformed release'
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
