// SPDX-License-Identifier: Apache-2.0
// Adapted from Mygod/VPNHotspot v2.17.1 Routing.kt and util/RootSession.kt.
// Changes: local HTTP destination only, scoped return routes, and an EOF-owned root shell.
package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.network.TeslaHttpConfig
import com.shilapi.xcertplay.network.TeslaHttpStatus
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal data class HttpDownstream(val name: String, val index: Int, val subnet: String) {
    init {
        require(Regex("[a-zA-Z0-9_.-]{1,15}").matches(name) && name != "lo")
        require(index in 1..Int.MAX_VALUE - 1000)
        val parts = subnet.split('/')
        require(parts.size == 2 && parts[1].toIntOrNull() in 1..30)
        val octets = parts[0].split('.')
        require(octets.size == 4 && octets.all { Regex("0|[1-9][0-9]{0,2}").matches(it) && it.toInt() in 0..255 })
    }
}

internal data class RootHttpPlan(val address: String, val port: Int, val downstreams: List<HttpDownstream>, val httpsPort: Int? = null,
                                val httpMappingPort: Int? = null, val httpsMappingPort: Int? = null) {
    init {
        require(TeslaHttpConfig.isHttpAddress(address) && port in 1024..65535)
        require(downstreams.isNotEmpty() && downstreams.distinctBy { it.name }.size == downstreams.size)
        require(httpsPort == null || httpsPort in 1024..65535 && httpsPort != port)
        require(httpMappingPort == null || httpMappingPort in 1..1023)
        require(httpsMappingPort == null || httpsMappingPort in 1..1023 && httpsPort != null)
        require(httpMappingPort == null || httpMappingPort != httpsMappingPort)
    }

    data class Command(val apply: String, val revert: String)

    // Android tethering routes live in local_network (97), rather than 1000 + ifindex.
    // See AOSP RouteController::addInterfaceToLocalNetwork and VPN_OUTPUT_TO_LOCAL.
    // Only packets addressed to our local HTTP alias and their replies use these rules.
    fun commands(): List<Command> = buildList {
        val ip = "/system/bin/ip -4"
        fun ipRule(rule: String) = add(Command("$ip rule add $rule priority 9000", "$ip rule del $rule priority 9000"))
        fun filter(rule: String) = add(Command("iptables -w 2 -I $rule", "iptables -w 2 -D $rule"))
        add(Command("$ip address add $address/32 dev lo", "$ip address del $address/32 dev lo"))
        ipRule("to $address/32 lookup local")
        for (downstream in downstreams) {
            ipRule("from $address/32 to ${downstream.subnet} iif lo lookup 97")
            for (listenerPort in listOfNotNull(port, httpsPort)) {
                filter("INPUT -i ${downstream.name} -d $address/32 -p tcp --dport $listenerPort -j ACCEPT")
                filter("OUTPUT -o ${downstream.name} -s $address/32 -d ${downstream.subnet} -p tcp --sport $listenerPort -m conntrack --ctstate ESTABLISHED -j ACCEPT")
            }
        }
        mappings().forEach { addAll(it.commands()) }
    }

    private fun mappings() = listOfNotNull(httpMappingPort?.let { RootPortPlan(it, port, address) },
        httpsMappingPort?.let { RootPortPlan(it, httpsPort!!, address) })

    /** The shell owns the transaction. Closing stdin (including app death) reverses successful steps. */
    fun script(): String = rootScript(commands(), mappings().joinToString("\n") { it.preflight() })

    companion object {
        const val READY = "WHEELPLAY_HTTP_ROUTING_READY"
        const val CLEAN = "WHEELPLAY_HTTP_ROUTING_CLEAN"
    }
}

internal fun rootScript(steps: List<RootHttpPlan.Command>, preflight: String = ""): String = buildString {
    appendLine("root_run() {")
    appendLine("  printf 'WHEELPLAY_ROOT_STEP %s\\n' \"\$*\" >&2")
    appendLine("  \"\$@\" || { printf 'WHEELPLAY_ROOT_FAILED %s\\n' \"\$*\" >&2; return 1; }")
    appendLine("}")
    appendLine("applied=0")
    appendLine("cleanup() {")
    appendLine("  failed=0")
    for (i in steps.indices.reversed()) {
        appendLine("  if [ \"\$applied\" -ge ${i + 1} ]; then ${steps[i].revert} || failed=1; fi")
    }
    appendLine("  [ \"\$failed\" = 0 ] && echo ${RootHttpPlan.CLEAN}")
    appendLine("  return \"\$failed\"")
    appendLine("}")
    appendLine("trap 'code=\$?; trap - EXIT; cleanup || code=1; exit \"\$code\"' EXIT")
    appendLine("trap 'exit 1' HUP INT TERM")
    appendLine("[ \"\$(id -u)\" = 0 ] || { echo 'Root authorization required'; exit 1; }")
    appendLine(preflight)
    appendLine("echo WHEELPLAY_ROOT_APPLYING")
    for ((i, step) in steps.withIndex()) {
        appendLine("root_run ${step.apply} || exit 1")
        appendLine("applied=${i + 1}")
    }
    appendLine("echo ${RootHttpPlan.READY}")
    appendLine("while IFS= read -r command; do [ \"\$command\" = stop ] && break; done")
}

internal interface HttpRoutingSession : AutoCloseable { val alive: Boolean }
internal class RootCleanupException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Runs off the UI thread. No global firewall flushes, sysctl edits, or persistent system settings. */
internal class RootHttpShell private constructor(private val process: Process) : HttpRoutingSession {
    private val ready = CountDownLatch(1)
    private val outputFinished = CountDownLatch(1)
    @Volatile private var started = false
    @Volatile private var cleaned = false
    @Volatile private var attempted = false
    @Volatile private var lastOutput = ""
    @Volatile private var failureDetails: String? = null
    private var currentCommand = ""
    private var commandOutput = ""
    override val alive get() = process.isAlive

    init {
        Thread({
            try {
                process.inputStream.bufferedReader().useLines { lines -> lines.forEach {
                    if (it.startsWith("WHEELPLAY_ROOT_STEP ")) {
                        currentCommand = it.removePrefix("WHEELPLAY_ROOT_STEP ").take(600)
                        commandOutput = ""
                    }
                    else if (it.startsWith("WHEELPLAY_ROOT_FAILED ")) {
                        if (failureDetails == null) failureDetails = "命令：$currentCommand\n$commandOutput".trimEnd()
                    }
                    else if (it == "WHEELPLAY_ROOT_APPLYING") attempted = true
                    else if (it == RootHttpPlan.READY) { started = true; ready.countDown() }
                    else if (it == RootHttpPlan.CLEAN) cleaned = true
                    else {
                        lastOutput = it.take(400)
                        commandOutput = (commandOutput + lastOutput + "\n").takeLast(1200)
                    }
                } }
            } finally { ready.countDown(); outputFinished.countDown() }
        }, "http-root-output").apply { isDaemon = true; start() }
    }

    private fun awaitReady(): RootHttpShell {
        if (!ready.await(30, TimeUnit.SECONDS) || !started || !alive) {
            val cleanupError = runCatching { close() }.exceptionOrNull()
            if (cleanupError != null) throw RootCleanupException("Root 路由启动失败且清理未确认，请检查规则或重启设备", cleanupError)
            throw IOException("Root 路由未启动，请检查 Root 授权及 ip/iptables 支持：${failureDetails ?: lastOutput}")
        }
        return this
    }

    override fun close() {
        if (alive) {
            runCatching { process.outputStream.write("stop\n".toByteArray()); process.outputStream.flush() }
            runCatching { process.outputStream.close() }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        }
        outputFinished.await(1, TimeUnit.SECONDS)
        if (alive || attempted && !cleaned) throw RootCleanupException("Root 路由退出异常，请检查规则清理或重启设备")
    }

    companion object {
        fun open(plan: RootHttpPlan, launch: (String) -> Process = ::launchRoot): HttpRoutingSession =
            openScript(plan.script(), launch)

        fun openScript(script: String, launch: (String) -> Process = ::launchRoot): HttpRoutingSession =
            RootHttpShell(launch(script)).awaitReady()

        private fun launchRoot(script: String): Process =
            ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
    }
}

/** Serial worker-owned state. A denied/failed plan is retried only after a change or an explicit retry. */
internal class RootHttpRouting(private val open: (RootHttpPlan) -> HttpRoutingSession = { RootHttpShell.open(it) }) {
    private var session: HttpRoutingSession? = null
    private var activePlan: RootHttpPlan? = null
    private var failedPlan: RootHttpPlan? = null
    private var failure: String? = null
    // A failed startup rollback returns no session whose close could confirm cleanup.
    private var startupCleanupFailure: String? = null

    fun retry() {
        if (startupCleanupFailure != null) return
        failedPlan = null; failure = null
    }

    fun reconcile(plan: RootHttpPlan?): TeslaHttpStatus {
        startupCleanupFailure?.let { return TeslaHttpStatus(error = it) }
        if (session != null && (plan != activePlan || session?.alive != true)) {
            val oldPlan = activePlan
            try { session!!.close() }
            catch (error: Exception) {
                failedPlan = oldPlan
                failure = error.message ?: "Root 路由清理失败"
                return TeslaHttpStatus(error = failure)
            }
            session = null; activePlan = null
            if (plan == oldPlan) { failedPlan = plan; failure = "Root 路由已断开，请重新启动" }
        }
        if (plan == null) { retry(); return TeslaHttpStatus() }
        if (session != null) return readyStatus(plan)
        if (failedPlan == plan) return TeslaHttpStatus(error = failure)
        return try {
            session = open(plan); activePlan = plan; retry()
            readyStatus(plan)
        } catch (error: Exception) {
            failedPlan = plan; failure = error.message ?: "无法启动 Root 路由，请检查 Root 授权"
            if (error is RootCleanupException) startupCleanupFailure = failure
            TeslaHttpStatus(error = failure)
        }
    }

    private fun readyStatus(plan: RootHttpPlan) = TeslaHttpStatus(plan.address, downstreams = plan.downstreams.map { it.name },
        httpMappingPort = plan.httpMappingPort, httpsMappingPort = plan.httpsMappingPort)
}
