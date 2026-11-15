<#
.SYNOPSIS
    End-to-end check of order-service's checkout saga (DESIGN.md Sections 2, 4
    and 5, order-service) across six real processes.

.DESCRIPTION
    Runs identity-, catalog-, merchant-, inventory-, payment- and order-service
    as real processes against a Compose Kafka and Redis. Every command it runs
    against them is a command of services/README.md, run as the README shows it,
    so the README is tested too:

      - the REST commands of "Trying catalog-service's REST API by hand" log in,
        onboard two merchants (the second as a variant: another name, email and
        rate), create the products, add stock and change a price;
      - inventory-service's GET /stock line reads the stock;
      - the commands of "Trying order-service by hand" register and log in a
        buyer, check out, read an order as its buyer and as Admin, list orders,
        read order.succeeded, order.failed and order-service's dead-letter
        topic, produce a malformed charge result and describe the consumer
        groups;
      - the Kafka consumer lines of payment-service's and inventory-service's
        sections (with only the topic swapped) read the results and the refund
        and release requests; the psql lines of "Looking at the data" (with
        -Postgres) read the stored rows.

    Scenarios, and what proves each:
      1. Happy path, two merchants: the order is SUCCEEDED (buyer and Admin
         views); the stock went down by the quantities; two charged and two
         reserved records; one order.succeeded whose merchants carry the rate
         snapshot and the exact commission, HALF_UP (0.225 -> 0.23, where
         HALF_EVEN would give 0.22). -Postgres: the legs, the outbox all SENT,
         payment_transaction APPROVED with only the last 4 digits.
      2. Replay: the same key and body answers 200 with the same Location; the
         order count and the charged records do not change.
      3. Price change: after the merchant's PATCH, the old expectedTotal gets
         409 PRICE_CHANGED with the new price and total, and nothing is stored.
      4. A decline (card 4000000000000010 declines every merchant): FAILED, both
         legs DECLINED 51, both lines RELEASED, the stock back, order.failed
         with the two 51 reasons, no refund requested.
      5. One merchant short of stock: both merchants charged, the reservation
         fails INSUFFICIENT_STOCK, both charges refunded, the other line
         released. FAILING is proven from the evidence (decidedAt before
         completedAt, the refund and release requests), not caught by polling.
      6. A malformed charged result (bad transactionRef), produced with the
         README's console producer ahead of a real result for the same order:
         its dead letter has the exact bytes, the key, our two headers only,
         and the real result behind it is applied (SUCCEEDED).
      7. payment-service stopped past the deadline: both charge legs TIMED_OUT
         (NO_RESULT), the lines released, FAILED with order.failed #1. Then
         payment-service starts, the late charges arrive and the order is
         reopened. The reopen is proven from evidence: a second order.failed
         with another eventId, the refund requests and their refunded results
         between the two (by occurredAt), completedAt set again later. The
         buyer view is polled every 200 ms meanwhile; whether it showed
         FAILING is printed, never a failure.
      8. inventory-service stopped past the deadline: payment charges; at the
         deadline the line is TIMED_OUT and the order FAILING, and it stays
         FAILING while inventory-service is down (read 10 times over 5 s);
         the charge is refunded and the line's release requested while it is
         down (both by occurredAt, before the restart). inventory-service
         starts: the line is RELEASED, the stock is back where it started, and
         the order ends FAILED with the line's NO_RESULT. inventory-service
         answers the late reserve once: RELEASED_BEFORE_RESERVE, from the
         tombstone its release left (or reserved, then released, if the reserve
         happens to be read first; which one is printed).
      9. (-Postgres only) order-service force-killed with a PENDING order whose
         requests are SENT: payment and inventory answer while it is down (both
         are stopped before the checkout and started once order-service is
         down: their results otherwise arrive before any kill could); it is
         restarted after the deadline and, within its 60 s startup grace, the
         listeners apply the queued results: SUCCEEDED, decided after its
         deadline. The rejoin time of the consumer group is printed; the run
         fails above 50 s. Here the old members' session (45 s) has expired
         before the restart, so the rejoin is short.
     9b. (-Postgres only) The same, but order-service is started again within
         about 5 s of the kill, with payment and inventory: the new members wait
         for the old ones' session to time out. The rejoin time is printed; the
         run fails above 50 s. The order ends SUCCEEDED, decided after its
         deadline, which passes during the wait.
    Last: the topics exist only because the services created them; the
    dead-letter topic holds only the one record; no service log contains the
    card number used, the internal key, the fingerprint key, a database
    password or a bearer token of this run; with -Postgres, the card number is
    in no stored value of orders or payment and no outbox row is PENDING.

    Not covered live:
      - one merchant declining while another is charged: the mock declines by
        card number and a buyer pays with one card, so there is no per-merchant
        override (scenario 5 covers the refund path; SagaDecisionTests and the
        Kafka listener tests cover the mixed decline);
      - a leg going STUCK after 3 resends: SagaTimerTests only;
      - a late reserved on a TIMED_OUT line: inventory-service answered the
        late reserve RELEASED_BEFORE_RESERVE in every run so far;
        SagaResultTableTests only.

    Run settings, as order-service command-line arguments (never a code
    change): --basketry.order.saga-deadline=30s, --basketry.order.timer-interval=1s,
    --basketry.order.compensation.resend-interval=10s. The startup grace stays
    the real 60 s. Before the first scenario, and again before each scenario
    that waits for the deadline, the script waits until order-service's
    consumer group (one group, order-service, its six listeners the members)
    is Stable (kafka-consumer-groups.sh --describe).

    Memory: every service it starts gets JAVA_TOOL_OPTIONS=-Xmx384m, and it
    runs "gradlew --stop" before building, so no Gradle daemon holds memory.
    With Docker Desktop already running, it needs about 4 GB free: the six
    services peaked at 2.4 GB together; Kafka, Redis, Postgres and the console
    consumers run in Docker Desktop's VM, which peaked at 3.1 GB. A run takes
    about 6 minutes, 8 with -Postgres (the jars already built).

    Run it from PowerShell at the repo root, with Docker running:

        powershell -ExecutionPolicy Bypass -File scripts\e2e-order.ps1

    It needs ports 8080 to 8085, 9092 and 6379 free (BASKETRY_REDIS_PORT
    instead of 6379 if set; with -Postgres also the Postgres port), and refuses
    to start otherwise. It stops only what it started: it records the PID of
    each service, and uses its own Compose project (basketry-e2e-order). It
    wipes only that project's data, before and after the run.

    Databases: by default every service runs on in-memory H2, except
    inventory-service, which runs on an H2 file under build\e2e\order (it is
    restarted in scenario 8, and in-memory H2 would lose its stock). With
    -Postgres, order-, payment- and inventory-service, the three restarted,
    run on the Compose Postgres; the others stay on in-memory H2 and are never
    restarted. payment-service on in-memory H2 is restarted in scenario 7 and
    loses its earlier transactions; no later scenario needs them.

    Service logs and the tools' output go to build\e2e\order, which each run
    empties first. The internal key, the fingerprint key and the database
    passwords are generated for this run and only passed through the
    environment.

    Exit codes:
        0  every check passed
        1  a check failed
        2  Docker is not running
        3  a port is busy
        4  Java 25 or the build is not available
        5  a service, Kafka, Redis or Postgres did not start in time
        6  services/README.md no longer has the commands this script runs

.PARAMETER TimeoutSeconds
    How long to wait for a service to start, a record to arrive, an order to
    reach a state, or the consumer groups to be stable.

.PARAMETER Postgres
    Run order-, payment- and inventory-service on Postgres (Compose profile
    "postgres") and add scenarios 9 and 9b. The host port is BASKETRY_POSTGRES_PORT if
    set, else 5432.
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
$paymentUrl = 'http://127.0.0.1:8084'
$orderUrl = 'http://127.0.0.1:8085'
$redisPort = 6379
if ($env:BASKETRY_REDIS_PORT) {
    $redisPort = [int]$env:BASKETRY_REDIS_PORT
}
$ports = @(8080, 8081, 8082, 8083, 8084, 8085, 9092, $redisPort)
$postgresPort = 5432
if ($env:BASKETRY_POSTGRES_PORT) {
    $postgresPort = [int]$env:BASKETRY_POSTGRES_PORT
}
if ($Postgres) {
    $ports += $postgresPort
}
# Its own project: only its containers and volumes are ever started or deleted.
# The ports are the same as the other scripts', so still run one at a time.
$composeProject = 'basketry-e2e-order'
$compose = "docker compose -p $composeProject"
$composeServices = 'kafka redis'
if ($Postgres) {
    $compose = "$compose --profile postgres"
    $composeServices = 'kafka redis postgres'
}
$repoRoot = Split-Path -Parent $PSScriptRoot
# Its own folder: the log check must never read another run's or script's logs.
$logDir = Join-Path $repoRoot 'build\e2e\order'
$readmePath = Join-Path $repoRoot 'services\README.md'

# The run settings of the table (row 12): command-line arguments, never a code change.
$orderArguments = @(
    '--basketry.order.saga-deadline=30s',
    '--basketry.order.timer-interval=1s',
    '--basketry.order.compensation.resend-interval=10s'
)
$sagaDeadlineSeconds = 30
$startupGraceSeconds = 60
$maxRejoinSeconds = 50
# The heap of each service this script starts (JAVA_TOOL_OPTIONS).
$serviceHeap = '-Xmx384m'

# The mock approves any number not in its table; this one is also the sentinel
# that must never reach a log line or (with -Postgres) a stored value.
$sentinelCard = '4929117300568841'
$declineCard = '4000000000000010'

$topics = @{
    ChargeRequested = 'order.merchant-charge.requested'
    RefundRequested = 'order.merchant-refund.requested'
    ReserveRequested = 'order.stock-reserve.requested'
    ReleaseRequested = 'order.stock-release.requested'
    Charged = 'order.merchant.charged'
    Declined = 'order.merchant.declined'
    Refunded = 'order.merchant.refunded'
    Reserved = 'order.stock.reserved'
    ReservationFailed = 'order.stock.reservation-failed'
    Released = 'order.stock.released'
    Succeeded = 'order.succeeded'
    Failed = 'order.failed'
}
$resultTopics = @($topics.Charged, $topics.Declined, $topics.Refunded, $topics.Reserved, $topics.ReservationFailed, $topics.Released)
$chargedDlt = 'order.merchant.charged.order-service.DLT'

# The commands exactly as services/README.md shows them, each on a line of its
# own (Assert-ReadmeCommands). The REST lines are single-quoted, so their
# variables are expanded only when they run.
$readmeCommands = [ordered]@{
    # "Trying catalog-service's REST API by hand"
    AdminLogin = '$admin = (Invoke-RestMethod -Method Post http://localhost:8080/auth/login -ContentType ''application/json'' -Body ''{"email":"admin-placeholder@basketry.example","password":"ChangeMe-Before-Deploy!"}'').accessToken'
    Onboard = '$onboarded = Invoke-RestMethod -Method Post http://localhost:8082/merchants -Headers @{ Authorization = "Bearer $admin" } -ContentType ''application/json'' -Body ''{"name":"Tea House","category":"Groceries","kycVerified":true,"commissionRate":5.00,"loginEmail":"tea@example.com","initialPassword":"Tea-Pass1"}'''
    ShopId = '$shopId = $onboarded.id'
    ShopLogin = '$shop = (Invoke-RestMethod -Method Post http://localhost:8080/auth/login -ContentType ''application/json'' -Body ''{"email":"tea@example.com","password":"Tea-Pass1"}'').accessToken'
    CreateProduct = '$productId = (Invoke-RestMethod -Method Post http://localhost:8081/products -Headers @{ Authorization = "Bearer $shop" } -ContentType ''application/json'' -Body ''{"name":"Green Tea","description":"Loose leaf, 100 g","category":"Drinks","price":4.50}'').id'
    ManageRead = 'Invoke-RestMethod "http://localhost:8081/manage/products/$productId" -Headers @{ Authorization = "Bearer $shop" }'
    AddStock = 'Invoke-RestMethod -Method Post "http://localhost:8083/stock/$productId/adjustments" -Headers @{ Authorization = "Bearer $shop" } -ContentType ''application/json'' -Body ''{"delta":10}'''
    ChangePrice = 'Invoke-RestMethod -Method Patch "http://localhost:8081/products/$productId" -Headers @{ Authorization = "Bearer $shop" } -ContentType ''application/json'' -Body ''{"price":4.95}'''
    # "Trying inventory-service's REST API by hand"
    ReadStock = 'Invoke-RestMethod http://localhost:8083/stock -Headers @{ Authorization = "Bearer $merchant" }'
    # "Trying order-service by hand"
    Register = 'Invoke-RestMethod -Method Post http://localhost:8080/accounts/register -ContentType ''application/json'' -Body ''{"email":"buyer@example.com","password":"Buyer-Pass1"}'''
    BuyerLogin = '$buyer = (Invoke-RestMethod -Method Post http://localhost:8080/auth/login -ContentType ''application/json'' -Body ''{"email":"buyer@example.com","password":"Buyer-Pass1"}'').accessToken'
    Checkout = '$order = Invoke-WebRequest -UseBasicParsing -Method Post http://localhost:8085/orders/checkout -Headers @{ Authorization = "Bearer $buyer"; ''Idempotency-Key'' = $key } -ContentType ''application/json'' -Body $cart'
    ReadOrderBuyer = 'Invoke-RestMethod "http://localhost:8085$($order.Headers.Location)" -Headers @{ Authorization = "Bearer $buyer" } | ConvertTo-Json -Depth 5'
    ReadOrderAdmin = 'Invoke-RestMethod "http://localhost:8085$($order.Headers.Location)" -Headers @{ Authorization = "Bearer $admin" } | ConvertTo-Json -Depth 5'
    ListOrders = 'Invoke-RestMethod http://localhost:8085/orders -Headers @{ Authorization = "Bearer $buyer" } | ConvertTo-Json -Depth 5'
    ListAllOrders = 'Invoke-RestMethod http://localhost:8085/orders -Headers @{ Authorization = "Bearer $admin" } | ConvertTo-Json -Depth 5'
    RepairQueue = 'Invoke-RestMethod "http://localhost:8085/orders?needsAttention=true" -Headers @{ Authorization = "Bearer $admin" } | ConvertTo-Json -Depth 5'
    ReadSucceeded = "docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 --topic $($topics.Succeeded) --from-beginning --property print.key=true --timeout-ms 10000"
    ReadFailed = "docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 --topic $($topics.Failed) --from-beginning --property print.key=true --timeout-ms 10000"
    ProduceCharged = "docker compose exec kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:29092 --topic $($topics.Charged) --property parse.key=true --property key.separator=`"|`""
    BadChargedRecord = '7|{"schemaVersion":1,"orderId":7,"merchantId":1,"transactionRef":"TXN-not-a-reference"}'
    ReadChargedDlt = "docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 --topic $chargedDlt --from-beginning --property print.key=true --property print.headers=true --timeout-ms 10000"
    GroupStates = 'docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:29092 --describe --all-groups --state'
    # "Sending payment-service a request by hand" and "Sending inventory-service a request by hand"
    ReadCharged = "docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 --topic $($topics.Charged) --from-beginning --property print.key=true --timeout-ms 10000"
    ReadReserved = "docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 --topic $($topics.Reserved) --from-beginning --property print.key=true --timeout-ms 10000"
    CreateTopic = "docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --create --if-not-exists --topic $($topics.ChargeRequested) --partitions 3 --replication-factor 1"
    # "Looking at the data"
    PsqlOrders = 'docker compose exec postgres psql -U orders orders'
    PsqlPayment = 'docker compose exec postgres psql -U payment payment'
}
# The shell commands above, by the container they run in.
$composeExecPrefix = 'docker compose exec '

$script:startedProcesses = @()
$script:composeStarted = $false
$script:serviceEnvironment = @{}
$script:serviceArguments = @{}
$script:secrets = @()
$script:fileNumber = 0
$script:rejoinSeconds = $null
$script:rejoinSecondsQuick = $null
$script:steps = @()
$script:currentStep = $null
$script:nextOrderId = 1
$script:orderServiceStartedAt = $null
$script:notes = @()

class E2eFailure : System.Exception {
    [int]$ExitCode
    E2eFailure([string]$message, [int]$exitCode) : base($message) {
        $this.ExitCode = $exitCode
    }
}

function Stop-Check([string]$message, [int]$exitCode = 1) {
    throw [E2eFailure]::new($message, $exitCode)
}

# Each step's time is printed when the next one starts, and again in the summary.
function Close-Step {
    if ($null -ne $script:currentStep) {
        $seconds = [math]::Round(((Get-Date) - $script:currentStep.Started).TotalSeconds, 1)
        $script:steps += [pscustomobject]@{ Name = $script:currentStep.Name; Seconds = $seconds }
        Write-Host "    ($seconds s)"
        $script:currentStep = $null
    }
}

function Write-Step([string]$message) {
    Close-Step
    $script:currentStep = [pscustomobject]@{ Name = $message; Started = Get-Date }
    Write-Host "==> $message"
}

function Write-Pass([string]$message) {
    Write-Host "    ok: $message"
}

function Write-Info([string]$message) {
    Write-Host "    info: $message"
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
    return Join-Path $logDir ('{0:D3}-{1}' -f $script:fileNumber, ($label -replace '[^A-Za-z0-9._-]', '_'))
}

# An ISO instant as a DateTimeOffset. Java writes up to 9 fractional digits;
# .NET reads at most 7.
function ConvertTo-Instant([string]$text) {
    if ([string]::IsNullOrEmpty($text)) {
        return $null
    }
    $trimmed = $text -replace '^(.*\.\d{1,7})\d*(Z|[+-]\d\d:\d\d)$', '$1$2'
    return [System.DateTimeOffset]::Parse($trimmed, [System.Globalization.CultureInfo]::InvariantCulture)
}

# ---- The README commands -----------------------------------------------------

# A README shell command, turned into the one this script runs. The only changes
# are the Compose project (-p basketry-e2e-order), and -T for a command whose
# input comes from a file instead of the keyboard. Anything else must already be
# in the README command, so a changed README cannot go unnoticed.
function Get-RunnableCommand([string]$readmeCommand, [switch]$FromFile) {
    if (-not $readmeCommand.StartsWith($composeExecPrefix)) {
        Stop-Check "Script error: '$readmeCommand' does not start with '$composeExecPrefix'"
    }
    $exec = 'exec'
    if ($FromFile) {
        $exec = 'exec -T'
    }
    return "$compose $exec " + $readmeCommand.Substring($composeExecPrefix.Length)
}

# One README command with exact parts swapped (old, new, old, new ...). Each
# part must occur exactly once.
function Get-VariantCommand([string]$readmeCommand) {
    $command = $readmeCommand
    for ($i = 0; $i -lt $args.Count; $i += 2) {
        $old = [string]$args[$i]
        $new = [string]$args[$i + 1]
        $count = ([regex]::Matches($command, [regex]::Escape($old))).Count
        if ($count -ne 1) {
            Stop-Check "Script error: '$old' occurs $count times in '$command'"
        }
        $command = $command.Replace($old, $new)
    }
    return $command
}

# The README's console consumer line for one topic, built from a README line by
# swapping only the topic.
function Get-ReadCommand([string]$topic) {
    switch ($topic) {
        { $_ -eq $topics.Succeeded } { return $readmeCommands.ReadSucceeded }
        { $_ -eq $topics.Failed } { return $readmeCommands.ReadFailed }
        { $_ -eq $topics.Charged } { return $readmeCommands.ReadCharged }
        { $_ -eq $topics.Reserved } { return $readmeCommands.ReadReserved }
        { $_ -eq $chargedDlt } { return $readmeCommands.ReadChargedDlt }
        { $_ -like 'order.stock*' } { return Get-VariantCommand $readmeCommands.ReadReserved "--topic $($topics.Reserved) " "--topic $topic " }
        default { return Get-VariantCommand $readmeCommands.ReadCharged "--topic $($topics.Charged) " "--topic $topic " }
    }
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

# ---- Kafka -------------------------------------------------------------------

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

# Parses a console consumer's output: "headers TAB key TAB value" when the
# README command prints headers, else "key TAB value".
function ConvertFrom-ConsumerOutput([string]$path, [bool]$withHeaders) {
    $records = @()
    foreach ($line in [System.IO.File]::ReadAllLines($path)) {
        $parts = $line.Split("`t")
        if ($withHeaders -and $parts.Count -ge 3) {
            $records += [pscustomobject]@{ Headers = $parts[0]; Key = $parts[1]; Value = ($parts[2..($parts.Count - 1)] -join "`t") }
        } elseif (-not $withHeaders -and $parts.Count -ge 2) {
            $records += [pscustomobject]@{ Headers = ''; Key = $parts[0]; Value = ($parts[1..($parts.Count - 1)] -join "`t") }
        }
    }
    return $records
}

# Every record of several topics, from their beginning, read in parallel: each
# console consumer stops 10 s after its last record. Returns topic -> records.
function Read-Topics([string[]]$topicNames) {
    $running = @()
    foreach ($topic in $topicNames) {
        $readme = Get-ReadCommand $topic
        $out = New-LogFile "$topic.out.txt"
        $err = New-LogFile "$topic.err.txt"
        $command = Get-RunnableCommand $readme
        $process = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', "`"$command > `"$out`" 2> `"$err`"`"" `
            -WindowStyle Hidden -PassThru
        $running += [pscustomobject]@{ Topic = $topic; Process = $process; Out = $out; Headers = $readme.Contains('print.headers=true') }
    }
    $result = @{}
    foreach ($reader in $running) {
        if (-not $reader.Process.WaitForExit(120000)) {
            Stop-ProcessTree $reader.Process.Id
            Stop-Check "Reading $($reader.Topic) did not finish within 120 s; see $($reader.Out)"
        }
        $result[$reader.Topic] = @(ConvertFrom-ConsumerOutput $reader.Out $reader.Headers)
    }
    return $result
}

# Reads the topics again until each has at least the wanted number of records
# for the key: $wanted is topic -> count. Returns topic -> that key's records.
function Wait-ForRecords([hashtable]$wanted, [string]$key) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $started = Get-Date
    do {
        $all = Read-Topics @($wanted.Keys)
        $mine = @{}
        $short = @()
        foreach ($topic in $wanted.Keys) {
            $mine[$topic] = @($all[$topic] | Where-Object { $_.Key -eq $key })
            if ($mine[$topic].Count -lt $wanted[$topic]) {
                $short += $topic
            }
        }
        if ($short.Count -eq 0) {
            $seconds = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
            $counts = ($wanted.Keys | Sort-Object | ForEach-Object { "$_ $($mine[$_].Count)" }) -join ', '
            Write-Pass "records for order $key read in $seconds s: $counts"
            return $mine
        }
    } while ((Get-Date) -lt $deadline)
    Stop-Check "Within $TimeoutSeconds s, order $key did not get the records wanted on: $($short -join ', ')"
}

function Get-Values($records) {
    return @($records | ForEach-Object { $_.Value | ConvertFrom-Json })
}

# order-service's consumer group (all six result listeners are its members)
# Stable, with its six members, and no rebalance in progress; the README's
# describe line. A group named after a listener id would mean a listener left
# the group, so any such group fails the check at once.
function Wait-ForStableGroups([string]$why) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $started = Get-Date
    $command = Get-RunnableCommand $readmeCommands.GroupStates
    $state = $null
    do {
        $out = New-LogFile 'consumer-groups.txt'
        Invoke-Quietly $command $out | Out-Null
        $state = $null
        foreach ($line in [System.IO.File]::ReadAllLines($out)) {
            $tokens = @($line.Trim() -split '\s+')
            if ($tokens.Count -ge 3 -and $tokens[0] -like 'order-results-*') {
                Stop-Check "Check failed: a consumer group $($tokens[0]) exists: a result listener is not in the order-service group"
            }
            if ($tokens.Count -ge 3 -and $tokens[0] -eq 'order-service') {
                $state = [pscustomobject]@{ State = $tokens[$tokens.Count - 2]; Members = $tokens[$tokens.Count - 1] }
            }
        }
        if ($null -ne $state -and $state.State -eq 'Stable' -and $state.Members -eq "$($resultTopics.Count)") {
            $seconds = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
            Write-Pass "the consumer group order-service is Stable with its $($resultTopics.Count) members ($why; checked in $seconds s)"
            return
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    $shown = if ($null -eq $state) { 'not listed' } else { "$($state.State), $($state.Members) member(s)" }
    Stop-Check "the consumer group order-service was not Stable with $($resultTopics.Count) members within $TimeoutSeconds s ($why): $shown"
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
    $arguments = @('-jar', "`"$($jar.FullName)`"")
    if ($script:serviceArguments.ContainsKey($name)) {
        $arguments += $script:serviceArguments[$name]
    }
    try {
        foreach ($variable in $environment.Keys) {
            Set-Item "Env:\$variable" $environment[$variable]
        }
        $process = Start-Process -FilePath 'java' -ArgumentList $arguments `
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

function Start-WithHealth([string]$name, [string]$url, [string]$logName = $name) {
    $process = Start-Service-Jar $name $logName
    Wait-ForHealth $name $url $process
    return $process
}

# Stops one service this script started, and forgets it. A forced stop: its
# consumers never leave their groups, as after a crash.
function Stop-StartedService([string]$name, [int]$port) {
    foreach ($started in @($script:startedProcesses | Where-Object { $_.Name -eq $name })) {
        Stop-ProcessTree $started.Id
        $started.Process.WaitForExit(15000) | Out-Null
        Write-Pass "force-stopped $name (PID $($started.Id))"
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
    # A JVM forked by anything this script started and still holding a port.
    foreach ($port in @(8080, 8081, 8082, 8083, 8084, 8085)) {
        $owners = @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique)
        foreach ($owner in $owners) {
            $process = Get-CimInstance Win32_Process -Filter "ProcessId = $owner"
            if ($null -ne $process -and $process.CommandLine -like "*$repoRoot*") {
                Stop-Process -Id $owner -Force -ErrorAction SilentlyContinue
                Write-Host "    stopped a leftover JVM on port $port (PID $owner)"
            }
        }
    }
}

# Seconds from "Started OrderServiceApplication" to the moment the last of the
# six result listeners got its partitions, from the service's own log.
function Get-RejoinSeconds([string]$logName) {
    $log = Join-Path $logDir "$logName.out.log"
    $started = Select-String -Path $log -Pattern 'Started OrderServiceApplication' | Select-Object -First 1
    if ($null -eq $started) {
        return $null
    }
    $from = [datetime]::Parse($started.Line.Substring(0, 23))
    $last = $from
    foreach ($topic in $resultTopics) {
        $assigned = Select-String -Path $log -Pattern "partitions assigned: \[$([regex]::Escape($topic))-" | Select-Object -First 1
        if ($null -eq $assigned) {
            return $null
        }
        $at = [datetime]::Parse($assigned.Line.Substring(0, 23))
        if ($at -gt $last) {
            $last = $at
        }
    }
    return [math]::Round(($last - $from).TotalSeconds, 1)
}

# The README's psql line (-T, the statement from a file), unaligned and tuples only.
function Invoke-Psql([string]$readmeCommand, [string]$sql) {
    $sqlFile = New-LogFile 'psql.sql'
    Write-Utf8NoBom $sqlFile ($sql + "`n")
    $out = New-LogFile 'psql.txt'
    $command = (Get-RunnableCommand $readmeCommand -FromFile) + ' -At -v ON_ERROR_STOP=1'
    & cmd.exe /c "$command < `"$sqlFile`" > `"$out`" 2>&1"
    if ($LASTEXITCODE -ne 0) {
        Stop-Check "psql failed (exit $LASTEXITCODE); see $out"
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

# ---- Merchants, products, stock ------------------------------------------------

# The README's product line with another name and price, as the given merchant.
function New-Product([string]$token, [string]$name, [string]$price) {
    $shop = $token
    $command = Get-VariantCommand $readmeCommands.CreateProduct '"name":"Green Tea"' "`"name`":`"$name`"" '"price":4.50' "`"price`":$price"
    Invoke-Expression $command
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $managed = Invoke-Expression $readmeCommands.ManageRead
        if ($managed.stockRow -eq 'CREATED') {
            Write-Pass "product $productId ($name, $price) created; its stock row is CREATED"
            return [long]$productId
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    Stop-Check "the stock row of product $productId was not CREATED within $TimeoutSeconds s"
}

# The README's adjustment line ({"delta":10}), or the same with another delta.
function Add-Stock([string]$token, [long]$product, [int]$delta) {
    $shop = $token
    $productId = $product
    $command = $readmeCommands.AddStock
    if ($delta -ne 10) {
        $command = Get-VariantCommand $command '{"delta":10}' "{`"delta`":$delta}"
    }
    $after = Invoke-Expression $command
    Write-Pass "product $product has $($after.quantity) unit(s)"
}

# The quantity of one product, from the README's GET /stock line, as its merchant.
function Get-Stock([string]$token, [long]$product) {
    $merchant = $token
    $page = Invoke-Expression $readmeCommands.ReadStock
    $row = @($page.content | Where-Object { [long]$_.productId -eq $product })
    if ($row.Count -ne 1) {
        Stop-Check "GET /stock has no row for product $product"
    }
    return [long]$row[0].quantity
}

# ---- Orders --------------------------------------------------------------------

# The README's checkout line with this script's cart and key. Returns the status,
# the Location, the body (parsed) and, for a new order, the orderId it was given
# (the sequence counts up by one, checked against the events later).
function Invoke-Checkout($lines, [string]$card, [decimal]$expectedTotal, [string]$key) {
    $cart = @{ lines = @($lines); cardNumber = $card; expectedTotal = $expectedTotal } | ConvertTo-Json -Depth 3 -Compress
    try {
        Invoke-Expression $readmeCommands.Checkout
        $answer = [pscustomobject]@{
            Status = [int]$order.StatusCode
            Location = [string]$order.Headers.Location
            Body = ($order.Content | ConvertFrom-Json)
            OrderId = $null
        }
    } catch [System.Net.WebException] {
        if ($null -eq $_.Exception.Response) {
            throw
        }
        $text = (New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())).ReadToEnd()
        return [pscustomobject]@{ Status = [int]$_.Exception.Response.StatusCode; Location = $null; Body = ($text | ConvertFrom-Json); OrderId = $null }
    }
    if ($answer.Status -eq 202) {
        $answer.OrderId = $script:nextOrderId
        $script:nextOrderId++
    }
    return $answer
}

# The README's order line, as its buyer or as Admin, parsed.
function Get-Order([string]$location, [string]$view) {
    $order = [pscustomobject]@{ Headers = @{ Location = $location } }
    $command = $readmeCommands.ReadOrderBuyer
    if ($view -eq 'admin') {
        $command = $readmeCommands.ReadOrderAdmin
    }
    return (Invoke-Expression $command) | ConvertFrom-Json
}

function Get-OrderCount {
    return [long]((Invoke-Expression $readmeCommands.ListAllOrders) | ConvertFrom-Json).totalElements
}

# Polls the Admin view until $done is true for it; returns that view.
function Wait-ForOrder([string]$location, [scriptblock]$done, [string]$what, [int]$seconds = $TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    $started = Get-Date
    do {
        $view = Get-Order $location 'admin'
        if (& $done $view) {
            $elapsed = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
            Write-Pass "the order $what, after $elapsed s"
            return $view
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    Stop-Check "the order did not reach '$what' within $seconds s (last status $($view.status))"
}

# Polls the buyer view every 200 ms, and the Admin view about every second,
# until $done is true for the Admin view. Returns the final Admin view and the
# buyer statuses seen, in order. What the buyer saw is printed, never checked.
function Watch-Order([string]$location, [scriptblock]$done, [string]$what, [int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    $started = Get-Date
    $seen = New-Object System.Collections.Generic.List[string]
    $polls = 0
    $nextAdmin = Get-Date
    do {
        $status = [string](Get-Order $location 'buyer').status
        $polls++
        if ($seen.Count -eq 0 -or $seen[$seen.Count - 1] -ne $status) {
            $seen.Add($status)
        }
        if ((Get-Date) -ge $nextAdmin) {
            $view = Get-Order $location 'admin'
            if (& $done $view) {
                $elapsed = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
                Write-Pass "the order $what, after $elapsed s"
                return [pscustomobject]@{ View = $view; Seen = @($seen); Polls = $polls }
            }
            $nextAdmin = (Get-Date).AddSeconds(1)
        }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $deadline)
    Stop-Check "the order did not reach '$what' within $seconds s (buyer statuses seen: $($seen -join ' -> '))"
}

function Get-Leg($view, [string]$kind, [long]$id) {
    if ($kind -eq 'charge') {
        return @($view.merchants | Where-Object { [long]$_.merchantId -eq $id })[0]
    }
    return @($view.lines | Where-Object { [long]$_.productId -eq $id })[0]
}

function Format-Money([decimal]$value) {
    return $value.ToString('0.00', [System.Globalization.CultureInfo]::InvariantCulture)
}

# The order.succeeded / order.failed records of one order must name it.
function Assert-EventNamesOrder($values, [string]$reference, [long]$orderId) {
    foreach ($value in $values) {
        if ($value.orderReference -ne $reference -or [long]$value.orderId -ne $orderId) {
            Stop-Check "Script error: order $orderId was expected to be $reference, but its event names $($value.orderReference) (order ids no longer count up by one?)"
        }
    }
}

# ---- The check -----------------------------------------------------------------

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

    Write-Step 'Building the six services'
    # Gradle daemons left by IDEs or earlier builds hold memory the six services need.
    Invoke-Quietly '.\gradlew.bat --stop' (Join-Path $logDir 'gradle-stop.log') | Out-Null
    Write-Pass 'Gradle daemons stopped'
    $build = Invoke-Quietly '.\gradlew.bat --no-daemon --console=plain :identity-service:bootJar :catalog-service:bootJar :merchant-service:bootJar :inventory-service:bootJar :payment-service:bootJar :order-service:bootJar' (Join-Path $logDir 'build.log')
    if ($build -ne 0) {
        Stop-Check "The build failed; see $logDir\build.log" 4
    }
    Write-Pass 'boot jars built'

    $apiKey = New-Secret
    $fingerprintKey = New-Secret
    $script:serviceEnvironment = @{
        'identity-service' = @{ BASKETRY_INTERNAL_API_KEY = $apiKey }
        'catalog-service' = @{ BASKETRY_INTERNAL_API_KEY = $apiKey }
        'merchant-service' = @{ BASKETRY_INTERNAL_API_KEY = $apiKey }
        'inventory-service' = @{ BASKETRY_INTERNAL_API_KEY = $apiKey }
        'payment-service' = @{}
        'order-service' = @{ BASKETRY_INTERNAL_API_KEY = $apiKey; BASKETRY_ORDER_FINGERPRINT_KEY = $fingerprintKey }
    }
    # Six JVMs at once: a fixed heap each keeps the run's memory predictable.
    foreach ($environment in $script:serviceEnvironment.Values) {
        $environment.JAVA_TOOL_OPTIONS = $serviceHeap
    }
    $script:serviceArguments = @{ 'order-service' = $orderArguments }
    $composeEnvironment = @{}
    $infrastructure = 'Kafka and Redis'
    if ($Postgres) {
        $infrastructure = 'Kafka, Redis and Postgres'
        $passwords = @{ payment = New-Secret; inventory = New-Secret; orders = New-Secret }
        $composeEnvironment = @{
            BASKETRY_POSTGRES_ADMIN_PASSWORD = New-Secret
            BASKETRY_IDENTITY_DB_PASSWORD = New-Secret
            BASKETRY_MERCHANT_DB_PASSWORD = New-Secret
            BASKETRY_CATALOG_DB_PASSWORD = New-Secret
            BASKETRY_PAYMENT_DB_PASSWORD = $passwords.payment
            BASKETRY_INVENTORY_DB_PASSWORD = $passwords.inventory
            BASKETRY_ORDER_DB_PASSWORD = $passwords.orders
        }
        foreach ($pair in @(@('payment-service', 'payment'), @('inventory-service', 'inventory'), @('order-service', 'orders'))) {
            $environment = $script:serviceEnvironment[$pair[0]]
            $environment.SPRING_PROFILES_ACTIVE = 'postgres'
            $environment.BASKETRY_POSTGRES_PORT = "$postgresPort"
            $environment.BASKETRY_DB_PASSWORD = $passwords[$pair[1]]
        }
    } else {
        # inventory-service is restarted in scenario 8: an H2 file keeps its stock.
        $h2File = (Join-Path $logDir 'inventory-h2\inventory') -replace '\\', '/'
        $script:serviceEnvironment['inventory-service'].SPRING_DATASOURCE_URL = "jdbc:h2:file:$h2File;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
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

    Write-Step 'Starting the six services (no topic exists yet)'
    $identity = Start-Service-Jar 'identity-service'
    $merchantProcess = Start-Service-Jar 'merchant-service'
    $inventory = Start-Service-Jar 'inventory-service'
    $payment = Start-Service-Jar 'payment-service'
    Wait-ForService 'identity-service' "$identityUrl/.well-known/jwks.json" $identity
    Wait-ForService 'merchant-service' "$merchantUrl/merchants" $merchantProcess
    Wait-ForHealth 'inventory-service' $inventoryUrl $inventory
    Wait-ForHealth 'payment-service' $paymentUrl $payment
    Start-WithHealth 'catalog-service' $catalogUrl | Out-Null
    $script:orderServiceStartedAt = Get-Date
    Start-WithHealth 'order-service' $orderUrl | Out-Null
    Write-Info "order-service runs with $($orderArguments -join ' ')"

    Write-Step 'Two merchants, three products and their stock, with the README REST commands'
    # The README's own lines, run in this scope so that each one sees the
    # variables the previous ones set.
    Invoke-Expression $readmeCommands.AdminLogin
    Invoke-Expression $readmeCommands.Onboard
    Invoke-Expression $readmeCommands.ShopId
    Invoke-Expression $readmeCommands.ShopLogin
    $merchantA = [long]$shopId
    $tokenA = $shop
    # The second merchant: the README's onboarding and login lines with another name, email, password and rate.
    Invoke-Expression (Get-VariantCommand $readmeCommands.Onboard '$onboarded =' '$onboardedB =' '"name":"Tea House"' '"name":"Spice Market"' '"commissionRate":5.00' '"commissionRate":7.50' '"loginEmail":"tea@example.com","initialPassword":"Tea-Pass1"' '"loginEmail":"spice@example.com","initialPassword":"Spice-Pass1"')
    Invoke-Expression (Get-VariantCommand $readmeCommands.ShopLogin '$shop =' '$shopB =' '"email":"tea@example.com","password":"Tea-Pass1"' '"email":"spice@example.com","password":"Spice-Pass1"')
    $merchantB = [long]$onboardedB.id
    $tokenB = $shopB
    Assert-True ($merchantA -ne $merchantB -and $onboarded.commissionRate -eq 5.00 -and $onboardedB.commissionRate -eq 7.50) "merchants $merchantA (Tea House, 5.00 %) and $merchantB (Spice Market, 7.50 %)"
    Invoke-Expression $readmeCommands.CreateProduct
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $managed = Invoke-Expression $readmeCommands.ManageRead
        if ($managed.stockRow -eq 'CREATED') { break }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    Assert-True ($managed.stockRow -eq 'CREATED') "product $productId (Green Tea, 4.50, merchant $merchantA): its stock row is CREATED"
    $teaId = [long]$productId
    $saffronId = New-Product $tokenB 'Saffron' '8.50'
    $vanillaId = New-Product $tokenB 'Vanilla' '3.20'
    Invoke-Expression $readmeCommands.AddStock | Out-Null
    Write-Pass "product $teaId has 10 units (the README's adjustment line)"
    Add-Stock $tokenB $saffronId 10
    Add-Stock $tokenB $vanillaId 1
    Invoke-Expression $readmeCommands.Register | Out-Null
    Invoke-Expression $readmeCommands.BuyerLogin
    Assert-True ($buyer.Length -gt 0) 'buyer@example.com registered and logged in'
    # Bearer tokens must never reach a log line either.
    $script:secrets += @($admin, $tokenA, $tokenB, $buyer)

    Write-Step 'Waiting for order-service''s consumer groups'
    Wait-ForStableGroups 'before the first scenario'

    # ---- 1. Happy path ----
    Write-Step 'Scenario 1: two merchants, an approved card: SUCCEEDED'
    $teaBefore = Get-Stock $tokenA $teaId
    $saffronBefore = Get-Stock $tokenB $saffronId
    $happyLines = @(@{ productId = $teaId; quantity = 1 }, @{ productId = $saffronId; quantity = 1 })
    $happyKey = 'e2e-happy-0001'
    $happy = Invoke-Checkout $happyLines $sentinelCard ([decimal]'13.00') $happyKey
    Assert-True ($happy.Status -eq 202 -and $happy.Location -match '^/orders/ORD-[A-Z2-7]{20}$' -and $happy.Body.status -eq 'PENDING') "checkout answered 202, PENDING, Location $($happy.Location)"
    $happyRef = $happy.Body.reference
    $happyId = $happy.OrderId
    $view = Wait-ForOrder $happy.Location { param($v) $v.status -ne 'PENDING' } 'was decided'
    Assert-True ($view.status -eq 'SUCCEEDED') 'the Admin view says SUCCEEDED'
    Assert-True ((Get-Order $happy.Location 'buyer').status -eq 'SUCCEEDED') 'the buyer view says SUCCEEDED'
    Assert-True (@($view.merchants | Where-Object { $_.status -eq 'CHARGED' -and $_.transactionRef -match '^TXN-[0-9a-f]{32}$' }).Count -eq 2) 'both charge legs are CHARGED with a transactionRef'
    Assert-True (@($view.lines | Where-Object { $_.status -eq 'RESERVED' }).Count -eq 2) 'both lines are RESERVED'
    Assert-True ((Get-Stock $tokenA $teaId) -eq $teaBefore - 1 -and (Get-Stock $tokenB $saffronId) -eq $saffronBefore - 1) "GET /stock: Green Tea $teaBefore -> $($teaBefore - 1), Saffron $saffronBefore -> $($saffronBefore - 1)"
    $records = Wait-ForRecords @{ $topics.Charged = 2; $topics.Reserved = 2; $topics.Succeeded = 1 } "$happyId"
    $succeeded = @(Get-Values $records[$topics.Succeeded])
    Assert-EventNamesOrder $succeeded $happyRef $happyId
    Assert-True ($succeeded.Count -eq 1) "one order.succeeded for order $happyId"
    $succeededEvent = $succeeded[0]
    $charged = @(Get-Values $records[$topics.Charged])
    $legA = @($succeededEvent.merchants | Where-Object { [long]$_.merchantId -eq $merchantA })[0]
    $legB = @($succeededEvent.merchants | Where-Object { [long]$_.merchantId -eq $merchantB })[0]
    Assert-True ($succeededEvent.totalAmount -eq 13.00 -and @($succeededEvent.items).Count -eq 2 -and @($succeededEvent.merchants).Count -eq 2) 'order.succeeded: totalAmount 13.00, two items, two merchants'
    Assert-True ($legA.commissionRate -eq 5.00 -and $legA.amount -eq 4.50 -and $legA.commission -eq 0.23) "merchant $merchantA`: amount 4.50, rate 5.00, commission 0.23 (0.225 HALF_UP; HALF_EVEN would be 0.22)"
    Assert-True ($legB.commissionRate -eq 7.50 -and $legB.amount -eq 8.50 -and $legB.commission -eq 0.64) "merchant $merchantB`: amount 8.50, rate 7.50, commission 0.64 (0.6375 HALF_UP)"
    $refs = @($charged | ForEach-Object { $_.transactionRef } | Sort-Object)
    Assert-True ((@($legA.transactionRef, $legB.transactionRef) | Sort-Object) -join ',' -eq ($refs -join ',')) 'its transactionRefs are those of the two charged records'
    if ($Postgres) {
        Assert-True ((Invoke-Psql $readmeCommands.PsqlOrders "select string_agg(status, ',' order by merchant_id) from merchant_charge where order_id = $happyId") -eq 'CHARGED,CHARGED') 'merchant_charge: CHARGED, CHARGED'
        Assert-True ((Invoke-Psql $readmeCommands.PsqlOrders "select string_agg(status, ',' order by product_id) from order_line where order_id = $happyId") -eq 'RESERVED,RESERVED') 'order_line: RESERVED, RESERVED'
        Assert-True ((Invoke-Psql $readmeCommands.PsqlOrders "select count(*) || '/' || count(*) filter (where status = 'SENT') from outbox_event where message_key = '$happyId'") -eq '5/5') 'outbox_event: 5 rows for the order (2 charges, 2 reserves, order.succeeded), all SENT'
        Assert-True ((Invoke-Psql $readmeCommands.PsqlPayment "select string_agg(status || ':' || card_last4, ',' order by merchant_id) from payment_transaction where order_id = $happyId") -eq 'APPROVED:8841,APPROVED:8841') 'payment_transaction: APPROVED twice, only the last 4 digits (8841)'
    }

    # ---- 2. Replay, 3. Price change ----
    Write-Step 'Scenarios 2 and 3: a replay, then a price change'
    $countBefore = Get-OrderCount
    $replay = Invoke-Checkout $happyLines $sentinelCard ([decimal]'13.00') $happyKey
    Assert-True ($replay.Status -eq 200 -and $replay.Location -eq $happy.Location -and $replay.Body.status -eq 'SUCCEEDED') 'the same key and body answers 200, the same Location, the order as it stands (SUCCEEDED)'
    Assert-True ((Get-OrderCount) -eq $countBefore) "GET /orders (Admin) still counts $countBefore order(s)"
    # The README's price line, as merchant A for Green Tea: 4.50 -> 4.95.
    $shop = $tokenA
    $productId = $teaId
    $patched = Invoke-Expression $readmeCommands.ChangePrice
    Assert-True ($patched.price -eq 4.95) 'Green Tea now costs 4.95'
    if ($Postgres) {
        $rowsBefore = Invoke-Psql $readmeCommands.PsqlOrders 'select (select count(*) from customer_order) || ''/'' || (select count(*) from outbox_event)'
    }
    $stale = Invoke-Checkout $happyLines $sentinelCard ([decimal]'13.00') 'e2e-price-0001'
    $teaPrice = @($stale.Body.lines | Where-Object { [long]$_.productId -eq $teaId })[0].unitPrice
    Assert-True ($stale.Status -eq 409 -and $stale.Body.code -eq 'PRICE_CHANGED' -and $teaPrice -eq 4.95 -and $stale.Body.total -eq 13.45) 'the old expectedTotal 13.00 gets 409 PRICE_CHANGED, Green Tea at 4.95, total 13.45'
    Assert-True ((Get-OrderCount) -eq $countBefore) "nothing stored: GET /orders still counts $countBefore"
    if ($Postgres) {
        Assert-True ((Invoke-Psql $readmeCommands.PsqlOrders 'select (select count(*) from customer_order) || ''/'' || (select count(*) from outbox_event)') -eq $rowsBefore) "nothing stored: customer_order / outbox_event rows still $rowsBefore"
    }
    $again = Wait-ForRecords @{ $topics.Charged = 2 } "$happyId"
    Assert-True ($again[$topics.Charged].Count -eq 2) "still one charged record per merchant for order $happyId after the replay"

    # ---- 4. Decline ----
    Write-Step 'Scenario 4: the decline card: both merchants declined, FAILED'
    $teaBefore = Get-Stock $tokenA $teaId
    $saffronBefore = Get-Stock $tokenB $saffronId
    $declined = Invoke-Checkout $happyLines $declineCard ([decimal]'13.45') 'e2e-decline-0001'
    Assert-True ($declined.Status -eq 202) 'checkout answered 202'
    $declinedId = $declined.OrderId
    $view = Wait-ForOrder $declined.Location { param($v) $v.status -eq 'FAILED' } 'is FAILED'
    Assert-True (@($view.merchants | Where-Object { $_.status -eq 'DECLINED' -and $_.reasonCode -eq '51' }).Count -eq 2) 'both charge legs DECLINED with 51'
    Assert-True (@($view.lines | Where-Object { $_.status -eq 'RELEASED' }).Count -eq 2) 'both lines RELEASED'
    Assert-True ((Get-Stock $tokenA $teaId) -eq $teaBefore -and (Get-Stock $tokenB $saffronId) -eq $saffronBefore) "the stock is back where it was (Green Tea $teaBefore, Saffron $saffronBefore)"
    Assert-True ($null -ne $view.decidedAt -and (ConvertTo-Instant $view.decidedAt) -le (ConvertTo-Instant $view.completedAt)) 'decidedAt is set, not after completedAt (FAILING came first)'
    $records = Wait-ForRecords @{ $topics.Failed = 1; $topics.ReleaseRequested = 2; $topics.RefundRequested = 0 } "$declinedId"
    $failed = @(Get-Values $records[$topics.Failed])
    Assert-EventNamesOrder $failed $declined.Body.reference $declinedId
    $reasons = ($failed[0].reasons | ForEach-Object { "$($_.leg):$($_.merchantId):$($_.reasonCode)" }) -join ','
    Assert-True ($failed.Count -eq 1 -and $reasons -eq "CHARGE:$($merchantA):51,CHARGE:$($merchantB):51") "one order.failed with the two 51 reasons ($reasons)"
    Assert-True ($records[$topics.RefundRequested].Count -eq 0) 'no refund was requested'
    Assert-True ($records[$topics.ReleaseRequested].Count -eq 2) 'two releases were requested'

    # ---- 5. One merchant short of stock ----
    Write-Step 'Scenario 5: Vanilla has 1 unit, the cart asks 2: both charged, both refunded'
    $teaBefore = Get-Stock $tokenA $teaId
    $short = Invoke-Checkout @(@{ productId = $teaId; quantity = 1 }, @{ productId = $vanillaId; quantity = 2 }) $sentinelCard ([decimal]'11.35') 'e2e-short-0001'
    Assert-True ($short.Status -eq 202) 'checkout answered 202'
    $shortId = $short.OrderId
    $view = Wait-ForOrder $short.Location { param($v) $v.status -eq 'FAILED' } 'is FAILED'
    Assert-True (@($view.merchants | Where-Object { $_.status -eq 'REFUNDED' -and $_.transactionRef -match '^TXN-' }).Count -eq 2) 'both charge legs REFUNDED (both were charged)'
    $vanillaLine = Get-Leg $view 'line' $vanillaId
    $teaLine = Get-Leg $view 'line' $teaId
    Assert-True ($vanillaLine.status -eq 'FAILED' -and $vanillaLine.reasonCode -eq 'INSUFFICIENT_STOCK') 'the Vanilla line FAILED with INSUFFICIENT_STOCK'
    Assert-True ($teaLine.status -eq 'RELEASED') 'the Green Tea line RELEASED'
    Assert-True ((Get-Stock $tokenA $teaId) -eq $teaBefore -and (Get-Stock $tokenB $vanillaId) -eq 1) "the stock is unchanged (Green Tea $teaBefore, Vanilla 1)"
    Assert-True ((ConvertTo-Instant $view.decidedAt) -lt (ConvertTo-Instant $view.completedAt)) 'decidedAt is before completedAt: FAILING while the reversal ran'
    $records = Wait-ForRecords @{ $topics.Charged = 2; $topics.RefundRequested = 2; $topics.Refunded = 2; $topics.ReleaseRequested = 1; $topics.Failed = 1 } "$shortId"
    $failed = @(Get-Values $records[$topics.Failed])
    Assert-EventNamesOrder $failed $short.Body.reference $shortId
    $reasons = ($failed[0].reasons | ForEach-Object { "$($_.leg):$($_.productId):$($_.reasonCode)" }) -join ','
    Assert-True ($reasons -eq "RESERVE:$($vanillaId):INSUFFICIENT_STOCK") "order.failed names the one reason ($reasons)"
    $refundRefs = (@(Get-Values $records[$topics.RefundRequested]) | ForEach-Object { $_.transactionRef } | Sort-Object) -join ','
    $chargedRefs = (@(Get-Values $records[$topics.Charged]) | ForEach-Object { $_.transactionRef } | Sort-Object) -join ','
    Assert-True ($refundRefs -eq $chargedRefs) 'the two refund requests carry the two charged transactionRefs'
    if ($Postgres) {
        Assert-True ((Invoke-Psql $readmeCommands.PsqlPayment "select string_agg(status, ',' order by merchant_id) from payment_transaction where order_id = $shortId") -eq 'REFUNDED,REFUNDED') 'payment_transaction: REFUNDED twice'
    }

    # ---- 6. A malformed result ----
    Write-Step 'Scenario 6: a malformed charged result ahead of a real one for the same order'
    $nextId = $script:nextOrderId
    # The README's record, for the order about to be placed: the same key, so the same partition.
    $bad = Get-VariantCommand $readmeCommands.BadChargedRecord '7|' "$nextId|" '"orderId":7' "`"orderId`":$nextId" '"merchantId":1' "`"merchantId`":$merchantA"
    Send-Records $readmeCommands.ProduceCharged @($bad) $topics.Charged
    $behind = Invoke-Checkout @(@{ productId = $teaId; quantity = 1 }) $sentinelCard ([decimal]'4.95') 'e2e-behind-0001'
    Assert-True ($behind.Status -eq 202 -and $behind.OrderId -eq $nextId) "checkout answered 202 (order $nextId)"
    $view = Wait-ForOrder $behind.Location { param($v) $v.status -ne 'PENDING' } 'was decided'
    Assert-True ($view.status -eq 'SUCCEEDED') 'the real charged result behind the malformed one was applied: SUCCEEDED'
    $records = Wait-ForRecords @{ $chargedDlt = 1; $topics.Succeeded = 1 } "$nextId"
    Assert-EventNamesOrder @(Get-Values $records[$topics.Succeeded]) $behind.Body.reference $nextId
    $deadLetter = $records[$chargedDlt][0]
    $badValue = $bad.Substring($bad.IndexOf('|') + 1)
    Assert-True ($deadLetter.Value -ceq $badValue) 'the dead letter''s value is the record as sent, byte for byte, keyed like it'
    Assert-True ($deadLetter.Headers.Contains('kafka_dlt-exception-fqcn:com.basketry.order.results.InvalidResultException')) 'its exception class is InvalidResultException'
    Assert-True ($deadLetter.Headers.Contains('kafka_dlt-exception-message:transactionRef must be TXN- and 32 lowercase hex digits')) 'its message names the rule and quotes nothing'
    Assert-True ($deadLetter.Headers.Contains("kafka_dlt-original-topic:$($topics.Charged)")) 'it names the original topic'
    Assert-True (-not $deadLetter.Headers.Contains('kafka_dlt-exception-stacktrace') -and -not $deadLetter.Headers.Contains('kafka_dlt-exception-cause-fqcn')) 'it has no stack-trace or cause header (our two only)'

    # ---- 7. payment-service stopped past the deadline, then the reopen ----
    Write-Step 'Scenario 7: payment-service stopped past the deadline; the late charges reopen the order'
    # The deadline must fall after the startup grace, or the timer would not act on it.
    $graceEnds = $script:orderServiceStartedAt.AddSeconds($startupGraceSeconds + 5)
    $wait = ($graceEnds - (Get-Date).AddSeconds($sagaDeadlineSeconds)).TotalSeconds
    if ($wait -gt 0) {
        Write-Info "waiting $([math]::Ceiling($wait)) s, so the deadline falls after order-service's startup grace"
        Start-Sleep -Seconds ([math]::Ceiling($wait))
    }
    Wait-ForStableGroups 'before a deadline scenario'
    $teaBefore = Get-Stock $tokenA $teaId
    $saffronBefore = Get-Stock $tokenB $saffronId
    Stop-StartedService 'payment-service' 8084
    $late = Invoke-Checkout $happyLines $sentinelCard ([decimal]'13.45') 'e2e-late-0001'
    Assert-True ($late.Status -eq 202) 'checkout answered 202 with payment-service down'
    $lateId = $late.OrderId
    $first = Watch-Order $late.Location { param($v) $v.status -eq 'FAILED' } 'is FAILED at its deadline' ($sagaDeadlineSeconds + $TimeoutSeconds)
    $view = $first.View
    Write-Info "buyer view up to FAILED #1 ($($first.Polls) polls, every 200 ms): $($first.Seen -join ' -> ')"
    Assert-True (@($view.merchants | Where-Object { $_.status -eq 'TIMED_OUT' -and $_.reasonCode -eq 'NO_RESULT' -and $null -eq $_.transactionRef }).Count -eq 2) 'both charge legs TIMED_OUT, NO_RESULT, no transactionRef'
    Assert-True (@($view.lines | Where-Object { $_.status -eq 'RELEASED' }).Count -eq 2) 'both lines RELEASED'
    Assert-True ((ConvertTo-Instant $view.decidedAt) -ge (ConvertTo-Instant $view.deadlineAt)) 'decided at or after its deadline'
    $completedFirst = ConvertTo-Instant $view.completedAt
    Assert-True ((Get-Stock $tokenA $teaId) -eq $teaBefore -and (Get-Stock $tokenB $saffronId) -eq $saffronBefore) 'the stock is back where it was'
    $records = Wait-ForRecords @{ $topics.Failed = 1 } "$lateId"
    $failedFirst = @(Get-Values $records[$topics.Failed])
    Assert-EventNamesOrder $failedFirst $late.Body.reference $lateId
    Assert-True ($failedFirst.Count -eq 1) 'order.failed #1 published'

    Start-WithHealth 'payment-service' $paymentUrl 'payment-service-restarted' | Out-Null
    $reopen = Watch-Order $late.Location { param($v) $v.status -eq 'FAILED' -and (ConvertTo-Instant $v.completedAt) -gt $completedFirst } 'is FAILED again, completedAt later' $TimeoutSeconds
    $view = $reopen.View
    $sawFailing = $reopen.Seen -contains 'FAILING'
    Write-Info "buyer view during the reopen ($($reopen.Polls) polls, every 200 ms): $($reopen.Seen -join ' -> '); FAILING seen: $(if ($sawFailing) { 'yes' } else { 'no' }) (printed only, never a failure)"
    Assert-True (@($view.merchants | Where-Object { $_.status -eq 'REFUNDED' -and $_.reasonCode -eq 'NO_RESULT' -and $_.transactionRef -match '^TXN-' }).Count -eq 2) 'both charge legs REFUNDED, still NO_RESULT (what failed the order)'
    $records = Wait-ForRecords @{ $topics.Failed = 2; $topics.Charged = 2; $topics.RefundRequested = 2; $topics.Refunded = 2 } "$lateId"
    $failedBoth = @(Get-Values $records[$topics.Failed] | Sort-Object { ConvertTo-Instant $_.occurredAt })
    Assert-EventNamesOrder $failedBoth $late.Body.reference $lateId
    $f1 = ConvertTo-Instant $failedBoth[0].occurredAt
    $f2 = ConvertTo-Instant $failedBoth[1].occurredAt
    Assert-True ($failedBoth.Count -eq 2 -and $failedBoth[0].eventId -ne $failedBoth[1].eventId) "two order.failed, eventIds $($failedBoth[0].eventId) and $($failedBoth[1].eventId)"
    $refundTimes = @(Get-Values $records[$topics.RefundRequested]) + @(Get-Values $records[$topics.Refunded]) | ForEach-Object { ConvertTo-Instant $_.occurredAt }
    Assert-True (@($refundTimes | Where-Object { $_ -le $f1 -or $_ -ge $f2 }).Count -eq 0) 'the 2 refund requests and 2 refunded results all lie between the two order.failed (by occurredAt)'
    $chargedRefs = (@(Get-Values $records[$topics.Charged]) | ForEach-Object { $_.transactionRef } | Sort-Object) -join ','
    $refundRefs = (@(Get-Values $records[$topics.RefundRequested]) | ForEach-Object { $_.transactionRef } | Sort-Object) -join ','
    Assert-True ($chargedRefs -eq $refundRefs) 'the refunds are of the two late charges'
    Assert-True ((ConvertTo-Instant $view.completedAt) -gt $f1) "completedAt was set again after order.failed #1 ($($view.completedAt))"
    $reasons = ($failedBoth[1].reasons | ForEach-Object { "$($_.leg):$($_.merchantId):$($_.reasonCode)" }) -join ','
    Assert-True ($reasons -eq "CHARGE:$($merchantA):NO_RESULT,CHARGE:$($merchantB):NO_RESULT") "order.failed #2 names the reasons as they stand ($reasons)"
    if ($Postgres) {
        Assert-True ((Invoke-Psql $readmeCommands.PsqlPayment "select string_agg(status, ',' order by merchant_id) from payment_transaction where order_id = $lateId") -eq 'REFUNDED,REFUNDED') 'payment_transaction: REFUNDED twice'
    }

    # ---- 8. inventory-service stopped past the deadline ----
    Write-Step 'Scenario 8: inventory-service stopped past the deadline; the late reserve is released'
    Wait-ForStableGroups 'before a deadline scenario'
    $teaStart = Get-Stock $tokenA $teaId
    Stop-StartedService 'inventory-service' 8083
    $stockless = Invoke-Checkout @(@{ productId = $teaId; quantity = 1 }) $sentinelCard ([decimal]'4.95') 'e2e-stockless-0001'
    Assert-True ($stockless.Status -eq 202) 'checkout answered 202 with inventory-service down'
    $stocklessId = $stockless.OrderId
    $view = Wait-ForOrder $stockless.Location { param($v) $v.lines[0].status -eq 'TIMED_OUT' -and $v.merchants[0].status -eq 'REFUNDED' } 'has its line TIMED_OUT and its charge REFUNDED' ($sagaDeadlineSeconds + $TimeoutSeconds)
    $line = $view.lines[0]
    Assert-True ($line.reasonCode -eq 'NO_RESULT' -and $null -ne $line.compensationDueAt) 'the line is TIMED_OUT, NO_RESULT, with its release requested (compensationDueAt set)'
    $checks = 0
    $stableUntil = (Get-Date).AddSeconds(5)
    do {
        $view = Get-Order $stockless.Location 'admin'
        if ($view.status -ne 'FAILING' -or $view.lines[0].status -ne 'TIMED_OUT') {
            Stop-Check "Check failed: the order left FAILING while inventory-service was down (status $($view.status), line $($view.lines[0].status))"
        }
        $checks++
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $stableUntil)
    Assert-True ($view.status -eq 'FAILING') "the order stays FAILING while inventory-service is down ($checks reads over 5 s)"

    # Started at once: the line goes STUCK 4 resend intervals after the deadline.
    # The topics are read afterwards; occurredAt shows the release was requested while it was down.
    $inventoryBack = [System.DateTimeOffset]::Now
    Start-WithHealth 'inventory-service' $inventoryUrl 'inventory-service-restarted' | Out-Null
    $view = Wait-ForOrder $stockless.Location { param($v) $v.status -eq 'FAILED' } 'is FAILED'
    $line = $view.lines[0]
    Assert-True ($line.status -eq 'RELEASED' -and -not $view.needsAttention) "the line is RELEASED ($($line.resends) resend(s) of its release), the order needs no attention"
    Assert-True ((Get-Stock $tokenA $teaId) -eq $teaStart) "the stock is back where it started ($teaStart)"
    $records = Wait-ForRecords @{ $topics.ReleaseRequested = 1; $topics.RefundRequested = 1; $topics.Refunded = 1; $topics.Released = 1; $topics.Failed = 1 } "$stocklessId"
    $releases = @(Get-Values $records[$topics.ReleaseRequested] | Where-Object { [long]$_.productId -eq $teaId -and [int]$_.quantity -eq 1 })
    Assert-True (@($releases | Where-Object { (ConvertTo-Instant $_.occurredAt) -lt $inventoryBack }).Count -ge 1) "the line's release (quantity 1) was requested while inventory-service was down ($($releases.Count) request(s) in all)"
    Assert-True ((ConvertTo-Instant (Get-Values $records[$topics.Refunded])[0].occurredAt) -lt $inventoryBack) 'the charge was refunded while inventory-service was down'
    $failed = @(Get-Values $records[$topics.Failed])
    Assert-EventNamesOrder $failed $stockless.Body.reference $stocklessId
    $reasons = ($failed[0].reasons | ForEach-Object { "$($_.leg):$($_.productId):$($_.reasonCode)" }) -join ','
    Assert-True ($reasons -eq "RESERVE:$($teaId):NO_RESULT") "order.failed names the timed-out line ($reasons)"
    $lateAnswers = Read-Topics @($topics.Reserved, $topics.ReservationFailed)
    $lateReserved = @($lateAnswers[$topics.Reserved] | Where-Object { $_.Key -eq "$stocklessId" })
    $lateFailed = @($lateAnswers[$topics.ReservationFailed] | Where-Object { $_.Key -eq "$stocklessId" })
    Assert-True (($lateReserved.Count + $lateFailed.Count) -eq 1) 'inventory-service answered the late reserve once'
    if ($lateReserved.Count -eq 1) {
        Write-Pass 'the late reserve was reserved, and its release gave the unit back'
    } else {
        $code = ($lateFailed[0].Value | ConvertFrom-Json).reasonCode
        Write-Info "the release overtook the late reserve: reservation-failed $code (nothing was taken)"
        $script:notes += "Scenario 8: the release overtook the late reserve (reservation-failed $code), so no reserved record came."
    }

    # ---- 9. order-service force-killed (-Postgres) ----
    $script:rejoinSeconds = $null
    if ($Postgres) {
        Write-Step 'Scenario 9: order-service force-killed with a PENDING order, restarted after the deadline'
        Wait-ForStableGroups 'before a deadline scenario'
        $teaBefore = Get-Stock $tokenA $teaId
        $saffronBefore = Get-Stock $tokenB $saffronId
        # Results come within a second of the requests, faster than any kill: payment-
        # and inventory-service (on Postgres here) are stopped first and started again
        # once order-service is down, so they answer exactly while it is down.
        Stop-StartedService 'payment-service' 8084
        Stop-StartedService 'inventory-service' 8083
        $crash = Invoke-Checkout $happyLines $sentinelCard ([decimal]'13.45') 'e2e-crash-0001'
        Assert-True ($crash.Status -eq 202) 'checkout answered 202'
        $crashId = $crash.OrderId
        $deadlineAt = ConvertTo-Instant (Get-Order $crash.Location 'admin').deadlineAt
        $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
        do {
            $sent = Invoke-Psql $readmeCommands.PsqlOrders "select count(*) filter (where status = 'SENT') || '/' || count(*) from outbox_event where message_key = '$crashId'"
            if ($sent -eq '4/4') { break }
            Start-Sleep -Milliseconds 250
        } while ((Get-Date) -lt $deadline)
        Assert-True ($sent -eq '4/4') 'its 4 requests are SENT (psql on outbox_event)'
        Assert-True ((Get-Order $crash.Location 'admin').status -eq 'PENDING') 'the order is PENDING'
        Stop-StartedService 'order-service' 8085
        $killedAt = Get-Date
        Start-WithHealth 'payment-service' $paymentUrl 'payment-service-restarted-2' | Out-Null
        Start-WithHealth 'inventory-service' $inventoryUrl 'inventory-service-restarted-2' | Out-Null
        Wait-ForRecords @{ $topics.Charged = 2; $topics.Reserved = 2 } "$crashId" | Out-Null
        Write-Pass 'payment-service and inventory-service answered while order-service was down'
        $wait = ($deadlineAt.LocalDateTime.AddSeconds(2) - (Get-Date)).TotalSeconds
        if ($wait -gt 0) {
            Write-Info "waiting $([math]::Ceiling($wait)) s for the order's deadline to pass"
            Start-Sleep -Seconds ([math]::Ceiling($wait))
        }
        Assert-True ((Get-Date).ToUniversalTime() -gt $deadlineAt.UtcDateTime) "the deadline ($($deadlineAt.ToString('o'))) has passed with order-service down"
        $restartedAt = Get-Date
        Start-WithHealth 'order-service' $orderUrl 'order-service-restarted' | Out-Null
        $view = Wait-ForOrder $crash.Location { param($v) $v.status -ne 'PENDING' } 'was decided'
        # The order can be decided before the last listener's line reaches the log.
        $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
        do {
            $script:rejoinSeconds = Get-RejoinSeconds 'order-service-restarted'
            if ($null -ne $script:rejoinSeconds) { break }
            Start-Sleep -Milliseconds 500
        } while ((Get-Date) -lt $deadline)
        $sinceKill = [math]::Round(($restartedAt - $killedAt).TotalSeconds, 1)
        Write-Info "consumer-group rejoin: the six listeners had their partitions $($script:rejoinSeconds) s after startup (the service was down $sinceKill s)"
        if ($null -eq $script:rejoinSeconds) {
            Stop-Check "Check failed: the restarted order-service's log does not show all six listeners getting their partitions within $TimeoutSeconds s"
        }
        if ($script:rejoinSeconds -gt $maxRejoinSeconds) {
            Stop-Check "Check failed: the consumer groups' rejoin after the force-kill took $($script:rejoinSeconds) s, more than $maxRejoinSeconds s"
        }
        Write-Pass "the rejoin took $($script:rejoinSeconds) s, within $maxRejoinSeconds s"
        Assert-True ($view.status -eq 'SUCCEEDED') 'the queued results were applied: SUCCEEDED, not failed by the timer'
        Assert-True ((ConvertTo-Instant $view.decidedAt) -gt $deadlineAt) "decided after its deadline ($($view.decidedAt) > $($deadlineAt.ToString('o'))): the startup grace held the timer back"
        Assert-True ((Get-Stock $tokenA $teaId) -eq $teaBefore - 1 -and (Get-Stock $tokenB $saffronId) -eq $saffronBefore - 1) 'the stock went down by the quantities'
        $records = Wait-ForRecords @{ $topics.Succeeded = 1 } "$crashId"
        Assert-EventNamesOrder @(Get-Values $records[$topics.Succeeded]) $crash.Body.reference $crashId
        Assert-True ($records[$topics.Succeeded].Count -eq 1) 'one order.succeeded'

        # ---- 9b. The same, restarted within about 5 s of the kill ----
        Write-Step 'Scenario 9b: order-service force-killed with a PENDING order, restarted within 5 s'
        Wait-ForStableGroups 'before a deadline scenario'
        $teaBefore = Get-Stock $tokenA $teaId
        $saffronBefore = Get-Stock $tokenB $saffronId
        Stop-StartedService 'payment-service' 8084
        Stop-StartedService 'inventory-service' 8083
        $quick = Invoke-Checkout $happyLines $sentinelCard ([decimal]'13.45') 'e2e-quick-0001'
        Assert-True ($quick.Status -eq 202) 'checkout answered 202'
        $quickId = $quick.OrderId
        $deadlineAt = ConvertTo-Instant (Get-Order $quick.Location 'admin').deadlineAt
        $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
        do {
            $sent = Invoke-Psql $readmeCommands.PsqlOrders "select count(*) filter (where status = 'SENT') || '/' || count(*) from outbox_event where message_key = '$quickId'"
            if ($sent -eq '4/4') { break }
            Start-Sleep -Milliseconds 250
        } while ((Get-Date) -lt $deadline)
        Assert-True ($sent -eq '4/4') 'its 4 requests are SENT (psql on outbox_event)'
        Assert-True ((Get-Order $quick.Location 'admin').status -eq 'PENDING') 'the order is PENDING'
        Stop-StartedService 'order-service' 8085
        $killedAt = Get-Date
        # payment- and inventory-service start with it; their results wait in Kafka
        # while the old members of order-service's group are still registered.
        $paymentProcess = Start-Service-Jar 'payment-service' 'payment-service-restarted-3'
        $inventoryProcess = Start-Service-Jar 'inventory-service' 'inventory-service-restarted-3'
        $orderProcess = Start-Service-Jar 'order-service' 'order-service-restarted-2'
        $restartAfter = [math]::Round(((Get-Date) - $killedAt).TotalSeconds, 1)
        Assert-True ($restartAfter -le 6) "order-service started again $restartAfter s after the kill"
        Wait-ForHealth 'order-service' $orderUrl $orderProcess
        Wait-ForHealth 'payment-service' $paymentUrl $paymentProcess
        Wait-ForHealth 'inventory-service' $inventoryUrl $inventoryProcess
        $view = Wait-ForOrder $quick.Location { param($v) $v.status -ne 'PENDING' } 'was decided' ($sagaDeadlineSeconds + $TimeoutSeconds)
        $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
        do {
            $script:rejoinSecondsQuick = Get-RejoinSeconds 'order-service-restarted-2'
            if ($null -ne $script:rejoinSecondsQuick) { break }
            Start-Sleep -Milliseconds 500
        } while ((Get-Date) -lt $deadline)
        if ($null -eq $script:rejoinSecondsQuick) {
            Stop-Check "Check failed: the restarted order-service's log does not show all six listeners getting their partitions within $TimeoutSeconds s"
        }
        Write-Info "consumer-group rejoin: the six listeners had their partitions $($script:rejoinSecondsQuick) s after startup (restarted $restartAfter s after the kill)"
        if ($script:rejoinSecondsQuick -gt $maxRejoinSeconds) {
            Stop-Check "Check failed: the consumer group's rejoin after the force-kill took $($script:rejoinSecondsQuick) s, more than $maxRejoinSeconds s"
        }
        Write-Pass "the rejoin took $($script:rejoinSecondsQuick) s, within $maxRejoinSeconds s"
        Assert-True ($view.status -eq 'SUCCEEDED') 'the results were applied: SUCCEEDED, not failed by the timer'
        Assert-True ((ConvertTo-Instant $view.decidedAt) -gt $deadlineAt) "decided after its deadline ($($view.decidedAt) > $($deadlineAt.ToString('o'))): the startup grace held the timer back"
        Assert-True ((Get-Stock $tokenA $teaId) -eq $teaBefore - 1 -and (Get-Stock $tokenB $saffronId) -eq $saffronBefore - 1) 'the stock went down by the quantities'
        $records = Wait-ForRecords @{ $topics.Charged = 2; $topics.Reserved = 2; $topics.Succeeded = 1 } "$quickId"
        Assert-EventNamesOrder @(Get-Values $records[$topics.Succeeded]) $quick.Body.reference $quickId
        Assert-True ($records[$topics.Succeeded].Count -eq 1) 'one order.succeeded'
    } else {
        Write-Step 'Scenarios 9 and 9b skipped: restarting order-service on H2 would lose its orders (run with -Postgres)'
    }

    Write-Step 'Checking the repair queue, the topics and the dead-letter topic'
    $queue = (Invoke-Expression $readmeCommands.RepairQueue) | ConvertFrom-Json
    Assert-True ([long]$queue.totalElements -eq 0) 'GET /orders?needsAttention=true is empty: no leg is STUCK'
    $mine = (Invoke-Expression $readmeCommands.ListOrders) | ConvertFrom-Json
    Assert-True ([long]$mine.totalElements -eq ($script:nextOrderId - 1)) "the buyer's GET /orders lists its $($script:nextOrderId - 1) orders"
    $listOut = New-LogFile 'topics.txt'
    Invoke-Quietly (Get-RunnableCommand (Get-VariantCommand $readmeCommands.CreateTopic "--create --if-not-exists --topic $($topics.ChargeRequested) --partitions 3 --replication-factor 1" '--list')) $listOut | Out-Null
    $existing = @([System.IO.File]::ReadAllLines($listOut) | ForEach-Object { $_.Trim() })
    $absent = @(@($topics.Values) + @($chargedDlt) | Where-Object { $existing -notcontains $_ })
    Assert-True ($absent.Count -eq 0) "all $($topics.Count + 1) saga topics exist, created by the services themselves (the broker creates none and this script none)"
    $allDead = Read-Topics @($chargedDlt)
    Assert-True ($allDead[$chargedDlt].Count -eq 1) "$chargedDlt holds exactly one record"

    Write-Step 'Checking the service logs and the stored values'
    if ($Postgres) {
        Assert-True ((Invoke-Psql $readmeCommands.PsqlOrders "select count(*) from outbox_event where status = 'PENDING'") -eq '0') 'no outbox row of orders is PENDING (all SENT or CANCELLED)'
        foreach ($database in @('orders', 'payment')) {
            $dump = New-LogFile "pg_dump-$database.sql"
            if ((Invoke-Quietly "$compose exec -T postgres pg_dump -U $database $database" $dump) -ne 0) {
                Stop-Check "pg_dump of $database failed; see $dump"
            }
            $hit = Select-String -Path $dump -Pattern $sentinelCard -SimpleMatch -Quiet
            Remove-Item $dump
            Assert-True (-not $hit) "no stored value of $database contains the card number used (pg_dump searched)"
        }
    }
    # Stopped first, so each log is complete; read shared in case a handle lingers.
    foreach ($service in @(@('order-service', 8085), @('payment-service', 8084), @('inventory-service', 8083), @('catalog-service', 8081), @('merchant-service', 8082), @('identity-service', 8080))) {
        Stop-StartedService $service[0] $service[1]
    }
    $logs = @(Get-ChildItem $logDir -Filter '*-service*.log')
    $text = ($logs | ForEach-Object { Read-Shared $_.FullName }) -join "`n"
    Assert-True ($text -match "Sending record $([regex]::Escape($topics.Charged))-\d+@\d+ to $([regex]::Escape($chargedDlt))") 'the logs have the dead-letter line for the malformed result'
    Assert-True (-not $text.Contains($sentinelCard)) "no line of the $($logs.Count) service log files contains the card number used"
    $leaked = @($script:secrets | Where-Object { $text.Contains($_) })
    Assert-True ($leaked.Count -eq 0) 'none contains the internal key, the fingerprint key, a database password or a bearer token of this run'
}

$exitCode = 0
$runStarted = Get-Date
Push-Location $repoRoot
try {
    Invoke-Check
    Close-Step
    Write-Host ''
    Write-Host 'Step times:'
    foreach ($step in $script:steps) {
        Write-Host ('    {0,6} s  {1}' -f $step.Seconds, $step.Name)
    }
    foreach ($note in $script:notes) {
        Write-Host "Note: $note"
    }
    $total = [math]::Round(((Get-Date) - $runStarted).TotalSeconds)
    Write-Host "PASSED: every check passed in $total s."
    if ($null -ne $script:rejoinSeconds) {
        Write-Host "Consumer-group rejoin after the force-kill (scenario 9): $($script:rejoinSeconds) s (limit $maxRejoinSeconds s)."
    }
    if ($null -ne $script:rejoinSecondsQuick) {
        Write-Host "Consumer-group rejoin after the force-kill and a restart within 5 s (scenario 9b): $($script:rejoinSecondsQuick) s (limit $maxRejoinSeconds s)."
    }
} catch [E2eFailure] {
    $exitCode = $_.Exception.ExitCode
    Close-Step
    Write-Host ''
    Write-Host "FAILED: $($_.Exception.Message)"
} catch {
    $exitCode = 1
    Close-Step
    Write-Host ''
    Write-Host "FAILED: unexpected error: $($_.Exception.Message) (line $($_.InvocationInfo.ScriptLineNumber))"
} finally {
    if (($script:startedProcesses.Count -gt 0) -or $script:composeStarted) {
        Write-Step 'Stopping what this script started'
        Stop-Everything
        Close-Step
    }
    Pop-Location
}
exit $exitCode
