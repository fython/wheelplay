package com.shilapi.xcertplay.web

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class RootHttpRoutingTest {
    private val plan = RootHttpPlan("100.96.0.1", 8080, listOf(HttpDownstream("ap0", 7, "192.168.43.1/24")))
    private class Session : HttpRoutingSession {
        override var alive = true
        var closes = 0
        override fun close() { alive = false; closes++ }
    }

    @Test fun publicAliasAndBothPortsStayScopedToTheHotspotAndLocalNetworkReturnTable() {
        val commands = plan.copy(address = "3.3.3.3", httpsPort = 9443).commands().map { it.apply }
        assertTrue(commands.any { it.contains("from 3.3.3.3/32 to 192.168.43.1/24 iif lo lookup 97") })
        for (port in listOf(8080, 9443)) {
            assertTrue(commands.any { it.contains("INPUT -i ap0 -d 3.3.3.3/32 -p tcp --dport $port") })
        }
        assertFalse(commands.any { it.contains("FORWARD") || it.contains("MASQUERADE") || it.contains("0.0.0.0/0") })
    }

    @Test fun failedAuthorizationIsNotAdvertisedOrRepeatedUntilAnExplicitRetry() {
        var attempts = 0
        val router = RootHttpRouting { attempts++; throw IOException("Root denied") }
        assertNull(router.reconcile(plan).address)
        assertEquals("Root denied", router.reconcile(plan).error)
        assertEquals(1, attempts)
        router.retry()
        router.reconcile(plan)
        assertEquals(2, attempts)
    }

    @Test fun unchangedPlanKeepsSessionWhilePortAndHotspotChangesCloseOldRules() {
        val sessions = mutableListOf<Session>()
        val router = RootHttpRouting { Session().also(sessions::add) }
        assertEquals(plan.address, router.reconcile(plan).address)
        assertEquals(listOf("ap0"), router.reconcile(plan).downstreams)
        assertEquals(1, sessions.size)
        val next = plan.copy(port = 9090)
        assertEquals(plan.address, router.reconcile(next).address)
        assertEquals(1, sessions.first().closes)
        router.reconcile(null)
        assertEquals(1, sessions.last().closes)
        router.reconcile(plan)
        assertEquals(3, sessions.size)
        router.reconcile(null)
    }

    @Test fun unexpectedRootExitWithdrawsAddressAndRequiresRetry() {
        val session = Session()
        val router = RootHttpRouting { session }
        router.reconcile(plan)
        session.alive = false
        assertNull(router.reconcile(plan).address)
        assertNotNull(router.reconcile(plan).error)
        assertEquals(1, session.closes)
    }

    @Test fun cleanupFailureNeverAdvertisesOrStartsAnotherSession() {
        var attempts = 0
        val router = RootHttpRouting {
            attempts++
            object : HttpRoutingSession {
                override val alive = true
                override fun close() { throw IOException("cleanup failed") }
            }
        }
        router.reconcile(plan)
        val result = router.reconcile(plan.copy(port = 9090))
        assertNull(result.address)
        assertEquals("cleanup failed", result.error)
        assertEquals(1, attempts)
    }

    @Test fun commandInputsCannotInjectShellSyntax() {
        for (name in listOf("ap0;id", "ap0\nreboot", "$(id)", "lo")) {
            try { HttpDownstream(name, 7, "192.168.43.1/24"); fail(name) }
            catch (_: IllegalArgumentException) { }
        }
        for (subnet in listOf("192.168.1.1/0", "192.168.1.999/24", "192.168.1.1/24;id", "1.2.3.4/32")) {
            try { HttpDownstream("ap0", 7, subnet); fail(subnet) }
            catch (_: IllegalArgumentException) { }
        }
    }

    /** Execute the actual generated shell, replacing only kernel commands with recording binaries. */
    private fun withShell(failAt: Int = 0, failRollbackAt: Int = 0, occupiedPort: Boolean = false, ownedPort: Boolean = false,
                          unsupportedAddrtype: Boolean = false, unavailableNat: Boolean = false,
                          block: (File, (String) -> Process) -> Unit) {
        val directory = Files.createTempDirectory("wheelplay-root-test-").toFile()
        try {
            val log = File(directory, "commands")
            val counter = File(directory, "counter").apply { writeText("0") }
            val tcp = File(directory, "tcp").apply { writeText(if (occupiedPort) "0: 00000000:0050 00000000:0000 0A\n" else "") }
            val tcp6 = File(directory, "tcp6").apply { writeText("") }
            val executable = File(directory, "kernel").apply {
                writeText("""#!/bin/sh
                    case "${'$'}*" in *'-t nat -S') ${if (unavailableNat) "echo 'Cannot initialize NAT table' >&2; exit 1" else if (ownedPort) "echo '--comment wheelplay-port-80'; exit 0" else "exit 0"};; esac
                    echo "${'$'}*" >> '${log.path}'
                    ${if (unsupportedAddrtype) "case \"\$*\" in *'-m addrtype'*) echo 'iptables: No chain/target/match by that name.' >&2; exit 1;; esac" else ":"}
                    n=${'$'}(cat '${counter.path}')
                    n=${'$'}((n + 1))
                    echo "${'$'}n" > '${counter.path}'
                    case "${'$'}n" in $failAt|$failRollbackAt) echo 'iptables: No chain/target/match by that name.' >&2; exit 1;; esac
                    exit 0
                """.trimIndent())
                setExecutable(true)
            }
            block(log) { script ->
                val adapted = script.replace("/system/bin/ip", executable.path)
                    .replace("ip6tables", executable.path).replace("iptables", executable.path).replace("id -u", "printf 0")
                    .replace("/proc/net/tcp6", tcp6.path).replace("/proc/net/tcp", tcp.path)
                ProcessBuilder("sh", "-c", adapted).redirectErrorStream(true).start()
            }
        } finally { directory.deleteRecursively() }
    }

    private fun recorded(command: String) = command.removePrefix("/system/bin/ip ").removePrefix("iptables ")

    @Test fun rootShellRevertsEverySuccessfulCommandInReverseOrderOnStop() = withShell { log, launch ->
        val shell = RootHttpShell.open(plan, launch)
        assertTrue(shell.alive)
        assertEquals(plan.commands().map { recorded(it.apply) }, log.readLines())
        shell.close()
        assertFalse(shell.alive)
        assertEquals(plan.commands().map { recorded(it.apply) } +
            plan.commands().asReversed().map { recorded(it.revert) }, log.readLines())
    }

    @Test fun partialStartupFailureRollsBackWithoutDeletingAnUnownedRule() = withShell(failAt = 4) { log, launch ->
        try { RootHttpShell.open(plan, launch); fail("startup must fail") }
        catch (_: IOException) { }
        assertEquals(plan.commands().take(4).map { recorded(it.apply) } +
            plan.commands().take(3).asReversed().map { recorded(it.revert) }, log.readLines())
    }

    @Test fun unconfirmedStartupRollbackBlocksRetriesPlanChangesAndStopStart() = withShell(failAt = 4, failRollbackAt = 5) { log, launch ->
        var attempts = 0
        val routing = RootHttpRouting { attempts++; RootHttpShell.open(it, launch) }
        try {
            val failed = routing.reconcile(plan)
            assertNull(failed.address)
            assertTrue(failed.error.orEmpty().contains("清理未确认"))
            assertEquals(plan.commands().take(4).map { recorded(it.apply) } +
                plan.commands().take(3).asReversed().map { recorded(it.revert) }, log.readLines())
            val installed = log.readText()
            for (changed in listOf(plan, plan.copy(address = "3.3.3.3"), plan.copy(port = 9090),
                plan.copy(httpsPort = 9443), plan.copy(httpMappingPort = 80),
                plan.copy(downstreams = listOf(HttpDownstream("ap1", 8, "192.168.44.1/24"))))) {
                routing.retry()
                val result = routing.reconcile(changed)
                assertNull(result.address)
                assertNull(result.httpMappingPort)
                assertEquals(failed.error, result.error)
            }
            assertEquals(failed.error, routing.reconcile(null).error)
            routing.retry()
            assertEquals(failed.error, routing.reconcile(plan).error)
            assertEquals(1, attempts)
            assertEquals(installed, log.readText())
        } finally { routing.reconcile(null) }
    }

    @Test fun closingStdinCleansRulesWhenTheAppOwnerDisappears() = withShell { log, launch ->
        var process: Process? = null
        val shell = RootHttpShell.open(plan) { launch(it).also { child -> process = child } }
        process!!.outputStream.close()
        assertTrue(process!!.waitFor(5, TimeUnit.SECONDS))
        shell.close()
        assertEquals(plan.commands().map { recorded(it.apply) } +
            plan.commands().asReversed().map { recorded(it.revert) }, log.readLines())
    }

    @Test fun mappedPortsAreScopedToCustomIpWithoutRequiringTheAddrtypeModule() = withShell(unsupportedAddrtype = true) { log, launch ->
        val mapped = plan.copy(httpsPort = 8443, httpMappingPort = 80, httpsMappingPort = 443)
        val rules = mapped.commands().filter { it.apply.contains("-t nat") }
        assertEquals(4, rules.size)
        assertTrue(rules.all { it.apply.contains("-d 100.96.0.1/32") && !it.apply.contains("addrtype") })
        assertTrue(rules.any { it.apply.contains("--dport 80") && it.apply.contains("--to-destination :8080") })
        assertTrue(rules.any { it.apply.contains("--dport 443") && it.apply.contains("--to-destination :8443") })
        val shell = RootHttpShell.open(mapped, launch)
        shell.close()
        assertEquals(mapped.commands().map { recorded(it.apply) } +
            mapped.commands().asReversed().map { recorded(it.revert) }, log.readLines())
    }

    @Test fun startupErrorKeepsTheFailedCommandAndOriginalErrorAfterRollback() = withShell(failAt = 4) { _, launch ->
        val error = runCatching { RootHttpShell.open(plan, launch) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("No chain/target/match by that name"))
        assertTrue(error?.message.orEmpty().contains(recorded(plan.commands()[3].apply)))
        assertFalse(error is RootCleanupException)
    }

    @Test fun failedNatPreflightReportsItsCommandWithoutMutatingRules() = withShell(unavailableNat = true) { log, launch ->
        val error = runCatching { RootHttpShell.open(plan.copy(httpMappingPort = 80), launch) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("-t nat -S"))
        assertTrue(error?.message.orEmpty().contains("Cannot initialize NAT table"))
        assertFalse(log.exists())
        assertFalse(error is RootCleanupException)
    }

    @Test fun mappingFailureRollsBackTheAliasAndEveryInstalledMapping() = withShell(failAt = 10) { log, launch ->
        val mapped = plan.copy(httpsPort = 8443, httpMappingPort = 80, httpsMappingPort = 443)
        assertTrue(runCatching { RootHttpShell.open(mapped, launch) }.isFailure)
        assertEquals(mapped.commands().take(10).map { recorded(it.apply) } +
            mapped.commands().take(9).asReversed().map { recorded(it.revert) }, log.readLines())
    }

    @Test fun occupiedLowPortIsRejectedBeforeAnyFirewallMutation() = withShell(occupiedPort = true) { log, launch ->
        val error = runCatching { RootHttpShell.openScript(RootPortPlan(80, 42000, plan.address).script(), launch) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("already in use"))
        assertFalse(log.exists())
    }

    @Test fun lowPortAlreadyForwardedByAnotherInstanceIsNotReplaced() = withShell(ownedPort = true) { log, launch ->
        val error = runCatching { RootHttpShell.openScript(RootPortPlan(80, 42000, plan.address).script(), launch) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("already has a WheelPlay"))
        assertFalse(log.exists())
    }

    @Test fun explicitPermissionProbeInstallsNoRulesAndDenialDoesNotClaimLeftoverRules() = withShell { log, launch ->
        RootHttpShell.openScript(rootScript(emptyList()), launch).use { }
        assertFalse(log.exists())
        val error = runCatching { RootHttpShell.openScript(rootScript(emptyList())) {
            ProcessBuilder("sh", "-c", "echo 'Permission denied'; exit 1").redirectErrorStream(true).start()
        } }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("Permission denied"))
        assertFalse(error is RootCleanupException)
    }

    @Test fun failedMappingCleanupBlocksFurtherRedirects() {
        var attempts = 0
        val routing = RootHttpRouting {
            attempts++
            object : HttpRoutingSession {
                override val alive = true
                override fun close() { throw IOException("cannot remove rule") }
            }
        }
        val mapped = plan.copy(httpMappingPort = 80)
        assertEquals(80, routing.reconcile(mapped).httpMappingPort)
        assertNotNull(routing.reconcile(null).error)
        assertNull(routing.reconcile(mapped.copy(httpMappingPort = 81)).address)
        assertEquals(1, attempts)
    }
}
