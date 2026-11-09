<#
.SYNOPSIS
    End-to-end check of payment-service's Kafka side (DESIGN.md Section 5).

.DESCRIPTION
    Runs payment-service as a real process against a Compose Kafka. Requests are
    produced and results read with the Kafka console tools, using the commands
    in services/README.md ("Sending payment-service a request by hand"), so those
    commands are tested too:

      - an approved charge, the same request again, and a declined charge;
      - a malformed charge carrying a sentinel card number: it must reach the
        charge dead-letter topic unchanged, with no header quoting the number;
      - a refund of the approved charge, and a refund of an unknown reference,
        which must reach the refund dead-letter topic;
      - the sentinel must be in no service log line.

    Run it from PowerShell at the repo root, with Docker running:

        powershell -ExecutionPolicy Bypass -File scripts\e2e-payment.ps1

    It needs ports 8084 and 9092 free (with -Postgres also the Postgres port),
    and refuses to start otherwise. It stops only what it started: it records
    the PID of the service, and uses its own Compose project (basketry-e2e). It
    wipes only that project's data, before and after the run.

    With -Postgres the service runs on the Compose Postgres instead of H2, the
    stored rows are checked for the sentinel too, and the service is restarted
    halfway: a repeated charge after the restart must give the same
    transactionRef. The restart is a forced stop, so the old consumers stay in
    the group until their session times out (up to 45 s) before the new ones get
    the partitions; the script waits for the result and reports the delay.

    Service logs and the console tools' output go to build\e2e\payment, which
    each run empties first. Database passwords are generated for this run and
    only passed through the environment.

    Exit codes:
        0  every check passed
        1  a check failed
        2  Docker is not running
        3  a port is busy
        4  Java 25 or the build is not available
        5  the service or Kafka did not start in time
        6  services/README.md no longer has the Kafka commands this script runs

.PARAMETER TimeoutSeconds
    How long to wait for the service to start, and for a record to arrive.

.PARAMETER Postgres
    Run the service on Postgres (Compose profile "postgres") and add the
    restart step. The host port is BASKETRY_POSTGRES_PORT if set, else 5432.
#>
param(
    [int]$TimeoutSeconds = 90,
    [switch]$Postgres
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$paymentUrl = 'http://127.0.0.1:8084'
$ports = @(8084, 9092)
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
# Its own folder: the log check must never read another run's or script's logs.
$logDir = Join-Path $repoRoot 'build\e2e\payment'
$readmePath = Join-Path $repoRoot 'services\README.md'

# Approved by the mock, and in no table: if it shows up anywhere it must not, it leaked.
$sentinel = '4929117300568841'
$sentinelHead = $sentinel.Substring(0, 12)
$unknownRef = 'TXN-00000000000000000000000000000000'

$chargeTopic = 'order.merchant-charge.requested'
$refundTopic = 'order.merchant-refund.requested'
$chargedTopic = 'order.merchant.charged'
$declinedTopic = 'order.merchant.declined'
$refundedTopic = 'order.merchant.refunded'
$chargeDlt = "$chargeTopic.DLT"
$refundDlt = "$refundTopic.DLT"

# The Kafka commands exactly as services/README.md shows them. Every command the
# script runs is made from one of these by Get-RunnableCommand, and each must be
# in the README verbatim (Assert-ReadmeCommands), so the README is what is tested.
$readmePrefix = 'docker compose exec kafka '
$readmeCommands = [ordered]@{
    CreateCharge = "docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --create --if-not-exists --topic $chargeTopic --partitions 3 --replication-factor 1"
    CreateRefund = "docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --create --if-not-exists --topic $refundTopic --partitions 3 --replication-factor 1"
    Produce = "docker compose exec kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:29092 --topic $chargeTopic --property parse.key=true --property key.separator=`"|`""
    ReadResults = "docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 --topic $chargedTopic --from-beginning --property print.key=true --timeout-ms 10000"
    ReadDeadLetters = "docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 --topic $chargeDlt --from-beginning --property print.key=true --property print.headers=true --timeout-ms 10000"
}

$script:startedProcesses = @()
$script:composeStarted = $false
$script:serviceEnvironment = @{}
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
    return -join ($bytes | ForEach-Object { $_.ToString('x2') })
}

function Write-Utf8NoBom([string]$path, [string]$text) {
    [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))
}

function New-LogFile([string]$label) {
    $script:fileNumber++
    return Join-Path $logDir ('{0:D2}-{1}' -f $script:fileNumber, $label)
}

# ---- The README commands -----------------------------------------------------

# A README command, turned into the one this script runs. The only changes are
# the Compose project (-p basketry-e2e), and -T for the producer, whose input
# comes from a file instead of the keyboard. Anything else must already be in
# the README command, so a changed README cannot go unnoticed.
function Get-RunnableCommand([string]$readmeCommand, [switch]$FromFile) {
    if (-not $readmeCommand.StartsWith($readmePrefix)) {
        Stop-Check "Script error: '$readmeCommand' does not start with '$readmePrefix'"
    }
    $exec = 'exec'
    if ($FromFile) {
        $exec = 'exec -T'
    }
    return "docker compose -p $composeProject $exec kafka " + $readmeCommand.Substring($readmePrefix.Length)
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
    Write-Pass "services/README.md has all $($readmeCommands.Count) Kafka commands this script runs"
}

# ---- Kafka -------------------------------------------------------------------

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

# ---- The service -------------------------------------------------------------

function Start-Payment([string]$logName) {
    $jar = Get-ChildItem (Join-Path $repoRoot 'services\payment-service\build\libs') -Filter '*.jar' |
        Where-Object { $_.Name -notlike '*-plain.jar' } |
        Select-Object -First 1
    if ($null -eq $jar) {
        Stop-Check 'No boot jar found for payment-service' 4
    }
    $environment = $script:serviceEnvironment
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
    $script:startedProcesses += [pscustomobject]@{ Name = 'payment-service'; Process = $process; Id = $process.Id }
    Write-Pass "payment-service started, PID $($process.Id)"
    Wait-ForHealth $process
}

function Wait-ForHealth($process) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ($process.HasExited) {
            Stop-Check "payment-service exited during startup (code $($process.ExitCode)); see $logDir" 5
        }
        try {
            $health = Invoke-RestMethod -Uri "$paymentUrl/actuator/health" -TimeoutSec 2
            if ($health.status -eq 'UP') {
                Write-Pass 'payment-service is UP'
                return
            }
        } catch {
            # Not listening yet.
        }
        Start-Sleep -Milliseconds 500
    }
    Stop-Check "payment-service did not start within $TimeoutSeconds s; see $logDir" 5
}

function Stop-Payment {
    foreach ($started in @($script:startedProcesses)) {
        Stop-ProcessTree $started.Id
        $started.Process.WaitForExit(15000) | Out-Null
        Write-Pass "stopped payment-service (PID $($started.Id))"
    }
    $script:startedProcesses = @()
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while (Test-PortBusy 8084) {
        if ((Get-Date) -gt $deadline) {
            Stop-Check 'Port 8084 is still busy after stopping the service' 5
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

# Seconds from "Started PaymentServiceApplication" to the first assignment of a
# request topic, from the service's own log: how long the group took to let it in.
function Get-RejoinSeconds([string]$logName) {
    $log = Join-Path $logDir "$logName.out.log"
    $started = Select-String -Path $log -Pattern 'Started PaymentServiceApplication' | Select-Object -First 1
    $assigned = Select-String -Path $log -Pattern "partitions assigned: \[$chargeTopic" | Select-Object -First 1
    if ($null -eq $started -or $null -eq $assigned) {
        return $null
    }
    $from = [datetime]::Parse($started.Line.Substring(0, 23))
    $to = [datetime]::Parse($assigned.Line.Substring(0, 23))
    return [math]::Round(($to - $from).TotalSeconds, 1)
}

function Invoke-Psql([string]$sql) {
    $out = New-LogFile 'psql.txt'
    $exit = Invoke-Quietly "$compose exec -T postgres psql -U payment -d payment -At -c `"$sql`"" $out
    if ($exit -ne 0) {
        Stop-Check "psql failed (exit $exit); see $out"
    }
    return ([System.IO.File]::ReadAllText($out)).Trim()
}

function Charge([long]$orderId, [string]$amount, [string]$cardNumber) {
    return "$orderId|{`"schemaVersion`":1,`"orderId`":$orderId,`"merchantId`":3,`"amount`":$amount,`"cardNumber`":`"$cardNumber`"}"
}

function Refund([long]$orderId, [string]$transactionRef) {
    return "$orderId|{`"schemaVersion`":1,`"orderId`":$orderId,`"merchantId`":3,`"transactionRef`":`"$transactionRef`"}"
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

    Write-Step 'Building payment-service'
    $build = Invoke-Quietly '.\gradlew.bat --no-daemon --console=plain :payment-service:bootJar' (Join-Path $logDir 'build.log')
    if ($build -ne 0) {
        Stop-Check "The build failed; see $logDir\build.log" 4
    }
    Write-Pass 'boot jar built'

    $composeEnvironment = @{}
    $infrastructure = 'Kafka'
    if ($Postgres) {
        $infrastructure = 'Kafka and Postgres'
        $paymentDbPassword = New-Secret
        $composeEnvironment = @{
            BASKETRY_POSTGRES_ADMIN_PASSWORD = New-Secret
            BASKETRY_IDENTITY_DB_PASSWORD = New-Secret
            BASKETRY_MERCHANT_DB_PASSWORD = New-Secret
            BASKETRY_PAYMENT_DB_PASSWORD = $paymentDbPassword
        }
        $script:serviceEnvironment = @{
            SPRING_PROFILES_ACTIVE = 'postgres'
            BASKETRY_POSTGRES_PORT = "$postgresPort"
            BASKETRY_DB_PASSWORD = $paymentDbPassword
        }
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
    Invoke-Kafka $readmeCommands.CreateCharge 'create-charge-topic'
    Invoke-Kafka $readmeCommands.CreateRefund 'create-refund-topic'
    Write-Pass "$chargeTopic and $refundTopic exist"

    Write-Step 'Starting payment-service'
    Start-Payment 'payment-service'

    Write-Step 'Charging: approved, the same request again, declined, and a malformed one'
    $approved = Charge 9001 '25.50' '4000000000000002'
    $malformed = "9003|{`"schemaVersion`":1,`"orderId`":9003,`"merchantId`":3,`"amount`":25.50,`"cardNumber`":$sentinel}"
    Send-Records $readmeCommands.Produce @($approved, $approved, (Charge 9002 '10.00' '4000000000000010'), $malformed) $chargeTopic

    # The dead letter first: the result reads below then cover the time after it,
    # so a result published for it by mistake would be seen.
    $dead = Wait-ForRecords $readmeCommands.ReadDeadLetters $chargeDlt '9003' 1 $true
    $deadLetter = $dead.Mine[0]
    Assert-True ($dead.Mine.Count -eq 1) 'exactly one dead letter for the malformed charge'
    Assert-True ($deadLetter.Value -ceq $malformed.Substring(5)) 'its value is the record as sent, byte for byte'
    Assert-True ($deadLetter.Headers.Contains('kafka_dlt-exception-fqcn:com.basketry.payment.payment.InvalidPaymentRequestException')) 'its exception class is InvalidPaymentRequestException'
    Assert-True ($deadLetter.Headers.Contains('kafka_dlt-exception-message:cardNumber must be a JSON string')) 'its message names the rule'
    Assert-True ($deadLetter.Headers.Contains("kafka_dlt-original-topic:$chargeTopic")) 'it names the original topic'
    Assert-True (-not $deadLetter.Headers.Contains('kafka_dlt-exception-stacktrace')) 'it has no stack-trace header'
    Assert-True (-not $deadLetter.Headers.Contains($sentinelHead)) 'no header contains the card number'
    Assert-True (-not $deadLetter.Key.Contains($sentinelHead)) 'the key does not contain it'

    $charged = Wait-ForRecords $readmeCommands.ReadResults $chargedTopic '9001' 2
    $refs = @($charged.Mine | ForEach-Object { Get-Field $_.Value 'transactionRef' } | Select-Object -Unique)
    Assert-True ($charged.Mine.Count -eq 2) 'the duplicate request was answered: two charged results'
    Assert-True ($refs.Count -eq 1 -and $refs[0] -match '^TXN-[0-9a-f]{32}$') "both carry the same transactionRef ($($refs[0]))"
    $transactionRef = $refs[0]
    Assert-True (@($charged.All | Where-Object { $_.Key -eq '9003' }).Count -eq 0) 'nothing was charged for the malformed request'

    $declinedCommand = Get-VariantCommand $readmeCommands.ReadResults "--topic $chargedTopic " "--topic $declinedTopic "
    $declined = Wait-ForRecords $declinedCommand $declinedTopic '9002' 1
    Assert-True ((Get-Field $declined.Mine[0].Value 'reasonCode') -eq '51') 'the decline carries reason code 51'
    Assert-True ($null -eq (Get-Field $declined.Mine[0].Value 'transactionRef')) 'and no transactionRef'
    Assert-True (@($declined.All | Where-Object { $_.Key -eq '9003' }).Count -eq 0) 'nothing was declined for the malformed request'

    Write-Step 'Refunding: the approved charge, and an unknown reference'
    $produceRefund = Get-VariantCommand $readmeCommands.Produce "--topic $chargeTopic " "--topic $refundTopic "
    Send-Records $produceRefund @((Refund 9001 $transactionRef), (Refund 9004 $unknownRef)) $refundTopic

    $refundDltCommand = Get-VariantCommand $readmeCommands.ReadDeadLetters "--topic $chargeDlt " "--topic $refundDlt "
    $refundDead = Wait-ForRecords $refundDltCommand $refundDlt '9004' 1 $true
    Assert-True ($refundDead.Mine[0].Headers.Contains('kafka_dlt-exception-fqcn:com.basketry.payment.payment.UnknownTransactionException')) "the unknown reference reached $refundDlt on the real broker"

    $refundedCommand = Get-VariantCommand $readmeCommands.ReadResults "--topic $chargedTopic " "--topic $refundedTopic "
    $refunded = Wait-ForRecords $refundedCommand $refundedTopic '9001' 1
    Assert-True ($refunded.Mine.Count -eq 1) 'one refunded result for the approved charge'
    Assert-True (@($refunded.All | Where-Object { $_.Key -eq '9004' }).Count -eq 0) 'nothing was refunded for the unknown reference'

    $script:rejoinSeconds = $null
    if ($Postgres) {
        Write-Step 'Restarting payment-service: Postgres keeps its transactions'
        Stop-Payment
        Start-Payment 'payment-service-restarted'
        Send-Records $readmeCommands.Produce @($approved) "$chargeTopic (after the restart)"
        $after = Wait-ForRecords $readmeCommands.ReadResults "$chargedTopic-after-restart" '9001' 3
        $script:rejoinSeconds = Get-RejoinSeconds 'payment-service-restarted'
        Write-Pass "the restarted listeners got their partitions $($script:rejoinSeconds) s after startup"
        $refsAfter = @($after.Mine | ForEach-Object { Get-Field $_.Value 'transactionRef' } | Select-Object -Unique)
        Assert-True ($refsAfter.Count -eq 1 -and $refsAfter[0] -eq $transactionRef) 'the repeat after the restart carries the same transactionRef'

        Write-Step 'Checking the stored rows'
        Assert-True ((Invoke-Psql 'select count(*) from payment_transaction') -eq '2') 'two transaction rows: nothing was charged twice'
        $leaks = Invoke-Psql "select (select count(*) from payment_transaction t where strpos(t::text, '$sentinelHead') > 0) + (select count(*) from outbox_event e where strpos(e::text, '$sentinelHead') > 0)"
        Assert-True ($leaks -eq '0') 'no stored row contains the card number'
    }

    Write-Step 'Checking the service logs'
    # Stopped first, so each log is complete; read shared in case a handle lingers.
    Stop-Payment
    $logs = @(Get-ChildItem $logDir -Filter 'payment-service*.log')
    $text = ($logs | ForEach-Object { Read-Shared $_.FullName }) -join "`n"
    # Whatever the line prints about the record, it ends by naming the dead-letter topic,
    # so a leak is reported by the check below, not hidden by this one.
    Assert-True ($text -match "Sending record .* to $([regex]::Escape($chargeDlt))") 'the logs have the dead-letter line for the malformed charge'
    Assert-True (-not $text.Contains($sentinelHead)) "no line of the $($logs.Count) service log files contains the card number"
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
