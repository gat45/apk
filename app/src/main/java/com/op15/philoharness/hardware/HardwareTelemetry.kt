package com.op15.philoharness.hardware

import java.io.File

/**
 * Porte (reduit) depuis F:/oneplus/opencode-root/app/src/main/java/com/op15/toolkit/
 * Diagnostics.kt (thermique/GPU/FastRPC/LMh uniquement -- la bande passante DDR/LLCC de la
 * source depend de SystemMonitor.kt, non revu dans ce portage, donc volontairement omise
 * plutot que portee a l'aveugle).
 *
 * Objectif direct : combler l'angle mort identifie plus tot cette session (audit Pithagoras,
 * point A2) -- "jamais declarer un backend actif sans preuve". Ce module donne une preuve
 * independante (temperature GPU/NPU, charge GPU, throttling thermique) qu'on peut comparer a
 * ce que `llama-server` pretend utiliser (`-dev HTP0`), au lieu de faire confiance au seul nom
 * de l'option de lancement.
 */
object HardwareTelemetry {

    data class ThermalReading(val family: String, val maxCelsius: Float, val zoneCount: Int)

    /** Temperatures par famille de capteur (nsphvx/nsphmx = NPU Hexagon, gpuss = GPU, cpu, ddr). */
    fun thermalZones(): List<ThermalReading> {
        return try {
            val dir = File("/sys/class/thermal")
            val zones = dir.listFiles { f -> f.isDirectory && f.name.startsWith("thermal_zone") }
                ?: return emptyList()
            val families = linkedMapOf<String, MutableList<Float>>()
            for (zone in zones) {
                val type = try { File(zone, "type").readText().trim() } catch (e: Exception) { continue }
                val fam = when {
                    type.startsWith("nsphvx") -> "nsphvx" // NPU Hexagon vecteur
                    type.startsWith("nsphmx") -> "nsphmx" // NPU Hexagon matrice (HTP)
                    type.startsWith("gpuss") -> "gpuss"
                    Regex("^(cpu-\\d|cpullc-\\d)").containsMatchIn(type) -> "cpu"
                    type.startsWith("ddr") -> "ddr"
                    else -> continue
                }
                val temp = try { File(zone, "temp").readText().trim().toFloatOrNull() } catch (e: Exception) { null }
                    ?: continue
                families.getOrPut(fam) { mutableListOf() }.add(temp / 1000f)
            }
            families.map { (fam, temps) -> ThermalReading(fam, temps.maxOrNull() ?: 0f, temps.size) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    data class GpuReading(val busyPercent: Float?, val clockMhz: Long?, val maxClockMhz: Long?, val memMb: Long?)

    /**
     * Charge/frequence/RAM GPU Adreno. Bug reel trouve en comparant au retour utilisateur
     * ("manque le GPU") : cette fonction lisait les noeuds sous /sys/class/kgsl/kgsl-3d0/
     * en acces DIRECT (File.readText(), contexte app, sans root) -- verifie sur device : SELinux refuse ces
     * noeuds au contexte untrusted_app ("Permission denied" confirme via adb shell sans su,
     * alors que `su -c cat ...` les lit sans probleme). La fonction echouait donc TOUJOURS
     * silencieusement (catch generique -> null), et summary() omettait la ligne GPU sans
     * jamais signaler d'erreur -- angle mort invisible jusqu'a ce retour. Passe desormais
     * par RootHelper (meme discipline que ModelInventory/LlmServerController pour tout ce
     * qui touche /sys/class/kgsl). clock_mhz est deja en MHz (pas besoin de diviser comme
     * l'ancien gpuclk en Hz). GPUTotalUsed (/proc/meminfo) EST lisible sans root (verifie),
     * contrairement au reste -- pas besoin de RootHelper pour cette seule valeur.
     */
    fun gpuUsage(): GpuReading {
        val clk = RootHelper.suCmdReadOnly("cat /sys/class/kgsl/kgsl-3d0/clock_mhz")
            .trim().toLongOrNull()
        val maxClk = RootHelper.suCmdReadOnly("cat /sys/class/kgsl/kgsl-3d0/max_clock_mhz")
            .trim().toLongOrNull()
        val busyRaw = RootHelper.suCmdReadOnly("cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage").trim()
        val busy = Regex("(\\d+)\\s*%").find(busyRaw)?.groupValues?.get(1)?.toFloatOrNull()
        val memMb = try {
            File("/proc/meminfo").readLines()
                .firstOrNull { it.startsWith("GPUTotalUsed:") }
                ?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull()?.div(1024) }
        } catch (e: Exception) { null }
        return GpuReading(busy, clk, maxClk, memMb)
    }

    data class NpuMemReading(val sharedBuffersMb: Long?)

    /**
     * NPU/Hexagon : AUCUN noeud de frequence lisible trouve sur ce device apres recherche
     * exhaustive (2026-10-14, retour utilisateur "idem pour le NPU") : pas de devfreq, pas
     * de clock_mhz equivalent, debugfs (/sys/kernel/debug/clk, /sys/kernel/debug/kgsl)
     * entierement inaccessible meme en root sur ce build. Plutot que d'inventer une valeur,
     * cette fonction ne renvoie QUE ce qui est reellement mesurable : IonTotalUsed
     * (/proc/meminfo) -- memoire ION, le pool de buffers partage GPU/NPU/camera sur
     * Qualcomm, PAS exclusif au NPU (etiquete comme tel dans l'UI, jamais presente comme
     * "RAM du NPU" seul -- ce serait fabriquer une precision qu'on n'a pas).
     */
    fun npuMemory(): NpuMemReading {
        val ionMb = try {
            File("/proc/meminfo").readLines()
                .firstOrNull { it.startsWith("IonTotalUsed:") }
                ?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull()?.div(1024) }
        } catch (e: Exception) { null }
        return NpuMemReading(ionMb)
    }

    /** Peripheriques FastRPC presents (/dev/fastrpc*) -- indique si le chemin HTP/NPU est actif au niveau kernel. */
    fun fastRpcDevices(): List<String> {
        return try {
            (File("/dev").listFiles { f -> f.name.startsWith("fastrpc") } ?: emptyArray())
                .map { it.name }.sorted()
        } catch (e: Exception) {
            emptyList()
        }
    }

    data class RamInfo(val totalMb: Long, val availableMb: Long, val usedMb: Long, val pctUsed: Int)

    /**
     * RAM detaillee (/proc/meminfo) -- comble le manque signale par l'utilisateur en comparant
     * a ses anciens projets (op15-unified/HardwareGovernor.kt memJson()) : avant, seule la
     * temperature/GPU/throttling etaient visibles, jamais la RAM.
     */
    fun ramInfo(): RamInfo? {
        return try {
            val map = HashMap<String, Long>()
            File("/proc/meminfo").readLines().forEach { line ->
                val parts = line.split(Regex("[:\\s]+"))
                if (parts.size >= 2) map[parts[0]] = parts[1].toLongOrNull() ?: 0L
            }
            val totalKb = map["MemTotal"] ?: return null
            val availKb = map["MemAvailable"] ?: return null
            val usedKb = totalKb - availKb
            val pct = if (totalKb > 0) (usedKb * 100 / totalKb).toInt() else 0
            RamInfo(totalKb / 1024, availKb / 1024, usedKb / 1024, pct)
        } catch (e: Exception) {
            null
        }
    }

    data class CpuCore(val cpu: Int, val mhz: Long, val maxMhz: Long)

    /** Frequence actuelle/max par coeur CPU (sysfs cpufreq, lecture seule, pas de root). */
    fun cpuFrequencies(): List<CpuCore> {
        val cores = mutableListOf<CpuCore>()
        var i = 0
        while (true) {
            val curFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
            if (!curFile.exists()) break
            val cur = try { curFile.readText().trim().toLongOrNull() ?: 0L } catch (e: Exception) { 0L }
            val max = try {
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLongOrNull() ?: 0L
            } catch (e: Exception) { 0L }
            cores.add(CpuCore(i, cur / 1000, max / 1000))
            i++
        }
        return cores
    }

    data class CpuThermalLimit(val cpu: Int, val limitMhz: Int)

    /** Limites de frequence imposees par le throttling thermique LMh (Limits Management Hardware), par coeur CPU. */
    fun cpuThermalLimits(): List<CpuThermalLimit> {
        val cpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val limits = mutableListOf<CpuThermalLimit>()
        for (cpu in 0 until cpuCount) {
            try {
                val f = File("/sys/kernel/lmh_stats_$cpu/dcvsh_freq_limit")
                if (f.exists()) {
                    val khz = f.readText().trim().toIntOrNull() ?: 0
                    if (khz > 0) limits.add(CpuThermalLimit(cpu, khz / 1000))
                }
            } catch (e: Exception) {
                // Non bloquant : un coeur illisible ne doit pas faire echouer les autres.
            }
        }
        return limits
    }

    /** Resume textuel compact pour injection dans un prompt ou affichage diagnostic. */
    fun summary(): String {
        val sb = StringBuilder()
        val thermal = thermalZones()
        if (thermal.isNotEmpty()) {
            sb.appendLine("Temperatures : " + thermal.joinToString(", ") { "${it.family}=${"%.1f".format(it.maxCelsius)}C" })
        }
        val gpu = gpuUsage()
        if (gpu.busyPercent != null || gpu.clockMhz != null || gpu.memMb != null) {
            sb.appendLine(
                "GPU : ${gpu.busyPercent?.let { "%.0f%%".format(it) } ?: "N/A"} @ ${gpu.clockMhz ?: "N/A"}" +
                    "/${gpu.maxClockMhz ?: "N/A"}MHz, ${gpu.memMb ?: "N/A"}Mo",
            )
        }
        val npuMem = npuMemory()
        if (npuMem.sharedBuffersMb != null) {
            sb.appendLine("NPU : frequence non exposee par ce device, ION (partage GPU/NPU/camera) ${npuMem.sharedBuffersMb}Mo")
        }
        val fastrpc = fastRpcDevices()
        sb.appendLine("FastRPC : ${if (fastrpc.isEmpty()) "aucun peripherique" else fastrpc.joinToString(", ")}")
        val throttle = cpuThermalLimits()
        if (throttle.isNotEmpty()) {
            sb.appendLine("Throttling thermique actif : " + throttle.joinToString(", ") { "cpu${it.cpu}=${it.limitMhz}MHz" })
        }
        return sb.toString().trimEnd()
    }
}
