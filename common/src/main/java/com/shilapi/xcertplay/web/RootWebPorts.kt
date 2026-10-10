package com.shilapi.xcertplay.web

/** Port-only DNAT preserves the destination IP needed by the hotspot's return route. */
internal data class RootPortPlan(val publicPort: Int, val listenerPort: Int, val address: String) {
    init {
        require(publicPort in 1..1023)
        require(listenerPort in 1024..65535)
        require(com.shilapi.xcertplay.network.TeslaHttpConfig.isHttpAddress(address))
    }

    fun commands(): List<RootHttpPlan.Command> = buildList {
        for (binary in listOf("iptables")) {
            for (chain in listOf("PREROUTING", "OUTPUT")) {
                val destination = "-d $address/32 "
                // The /32 alias is installed before these rules; an addrtype match is redundant.
                val rule = "$chain $destination-p tcp --dport $publicPort " +
                    "-m comment --comment wheelplay-port-$publicPort -j DNAT --to-destination :$listenerPort"
                add(RootHttpPlan.Command("$binary -w 2 -t nat -I $rule", "$binary -w 2 -t nat -D $rule"))
            }
        }
    }

    fun script(): String = rootScript(commands(), preflight())

    fun preflight(): String {
        val hex = "%04X".format(publicPort)
        // Do not redirect a low port already owned by another listening service.
        val preflight = """
            [ -r /proc/net/tcp ] || { echo 'Cannot check occupied ports'; exit 1; }
            port_tables=/proc/net/tcp
            [ ! -r /proc/net/tcp6 ] || port_tables="${'$'}port_tables /proc/net/tcp6"
            busy=0
            awk '${'$'}4 == "0A" && ${'$'}2 ~ /:$hex${'$'}/ { found=1 } END { exit found ? 0 : 1 }' ${'$'}port_tables || busy=${'$'}?
            case "${'$'}busy" in
              1) ;;
              0) echo 'Port $publicPort is already in use'; exit 1 ;;
              *) echo 'Cannot check occupied ports'; exit 1 ;;
            esac
            for binary in iptables; do
              wheelplay_rules=${'$'}(root_run "${'$'}binary" -w 2 -t nat -S) || exit 1
              if printf '%s\n' "${'$'}wheelplay_rules" | awk '/--comment "?wheelplay-port-$publicPort"?( |${'$'})/ { found=1 } END { exit found ? 0 : 1 }'; then
                echo 'Port $publicPort already has a WheelPlay forwarding rule'; exit 1
              fi
            done
        """.trimIndent()
        return preflight
    }
}

/** Permission probes make no routing changes. Startup checks once; the button can retry explicitly. */
internal object RootAccess {
    @Volatile var granted = false
        private set
    @Volatile var status = "尚未请求 Root 权限"
        private set
    @Volatile var checking = false
        private set
    @Volatile internal var startupChecked = false
    private val startupLock = Any()
    private val probeLock = Any()
    internal var authorize: () -> Unit = { RootHttpShell.openScript(rootScript(emptyList())).use { } }

    fun checkOnStartup() {
        synchronized(startupLock) {
            if (startupChecked) return
            startupChecked = true
            checking = true
            status = "正在检查 Root 权限…"
        }
        Thread({
            request()
            TeslaHttpCompatibility.retry()
        }, "wheelplay-root-check").apply { isDaemon = true; start() }
    }

    fun request(): String? = synchronized(probeLock) {
        synchronized(startupLock) { startupChecked = true }
        checking = true
        status = "正在请求 Root 权限…"
        granted = false
        return try {
            authorize()
            granted = true
            status = "Root 已授权（本次检查）"
            null
        } catch (error: Exception) {
            status = "Root 未授权：${error.message ?: error.javaClass.simpleName}"
            status
        } finally { checking = false }
    }
}
