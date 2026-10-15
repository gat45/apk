package com.op15.philoharness.hardware

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Porte depuis F:/oneplus/opencode-root/app/src/main/java/com/op15/toolkit/RootHelper.kt
 * (meme auteur, meme device, deja durci par un audit de securite anterieur -- 5 bypass RCE
 * corriges le 18/08/2026, cf. commentaires inline conserves). Revue de securite dediee faite
 * cette session (sous-agent token-scout) avant ce portage : verdict "port recommande sous
 * conditions" -- n'exposer QUE suCmdReadOnly/isReadOnly a un agent pilote par LLM, jamais
 * suCmd() brut ni suCmdUnrestricted() (porte volontairement non gatee, desactivee par defaut,
 * conservee telle quelle pour parite avec la source mais JAMAIS appelee depuis
 * PhiloReasoningWorker/les outils agent -- usage reserve au debug manuel).
 */
object RootHelper {

    // Serialise tous les forks su : evite deux flux concurrents de l'app (ex. deux Workers)
    // qui declenchent des popups Magisk/KernelSU concurrentes.
    private val suLock = java.util.concurrent.locks.ReentrantLock()

    private const val TIMEOUT_MS = 5000L
    private const val TIMEOUT_LONG_MS = 180_000L

    private const val SU_PATH = "/product/bin/su"
    private val SU_BIN_PATHS = arrayOf(
        "/product/bin/su", "/system/bin/su", "/system/xbin/su", "/sbin/su", "/vendor/bin/su",
    )
    private val SU_PATHS = arrayOf(
        "/product/bin/su", "/system/bin/su", "/system/xbin/su", "/sbin/su", "/vendor/bin/su",
        "/data/adb/magisk/busybox", "/data/adb/ksu/bin/ksud",
    )

    @Volatile private var resolvedSuPath: String? = null
    private fun resolveSuPath(): String {
        resolvedSuPath?.let { return it }
        val found = SU_BIN_PATHS.firstOrNull { File(it).exists() } ?: SU_PATH
        resolvedSuPath = found
        return found
    }

    // 15s : la toute premiere requete su sur un Magisk fraichement (re)installe declenche une
    // popup systeme que l'utilisateur doit valider a la main -- un timeout trop court tue le
    // process su avant la reponse humaine.
    private const val ROOT_CHECK_TIMEOUT_MS = 15000L

    fun isRooted(): Boolean {
        if (SU_PATHS.any { File(it).exists() }) return true
        return suExec(resolveSuPath(), "id", ROOT_CHECK_TIMEOUT_MS).contains("uid=0")
    }

    /** Exécution root brute -- JAMAIS exposée à un outil pilote par le LLM, usage interne/debug seulement. */
    private fun suCmd(cmd: String): String = suExec(resolveSuPath(), cmd, TIMEOUT_MS)
    private fun suCmdLong(cmd: String): String = suExec(resolveSuPath(), cmd, TIMEOUT_LONG_MS)

    private val READ_BINS = listOf(
        "cat ", "ls ", "getprop ", "dmesg ", "find ", "grep ", "strings ", "uname ",
        "ps ", "pgrep ", "df ", "wc ", "head ", "tail ", "sort ", "uniq ", "awk ", "sed ",
        "readelf ", "sha256sum ", "md5sum ", "stat ", "file ", "xxd ", "od ", "hexdump ",
        "cut ", "tr ", "basename ", "dirname ", "which ", "id ", "uptime ", "free ",
        "vmstat ", "top -n1 ", "lsof ", "ss ", "netstat ", "env ", "printenv ", "date ",
        "whoami ", "groups ", "getenforce ", "lsmod ", "modinfo ", "sysctl -a ",
        "sysctl ", "sqlite3 ", "mount ", "echo ", "printf ",
    )
    private val READ_ONLY_PREFIXES = listOf(
        "iptables -L ", "iptables -S ", "iptables --list ", "nft list ",
        "seinfo ", "sesearch ", "dumpsys ", "content query ", "cmd package ", "cmd activity ",
        "logcat -d ", "zcat ", "xzcat ", "gzip -dc ", "bzip2 -dc ",
        "openssl x509 ", "openssl pkey ", "service list ", "settings get ", "wm size ",
        "wm density ", "pm list ", "am stack ",
        "ip addr ", "ip link ", "ip route ", "route ", "arp ",
        "tar -tf ", "tar -tf", "unzip -l ", "keytool -list ",
    )
    private val BARE_READ_COMMANDS = setOf(
        "mount", "iptables -L", "iptables -S", "iptables --list", "nft list",
        "ip addr", "ip link", "ip route", "route", "arp",
    )
    private val PIPE_READ_BINS = listOf(
        "grep", "head", "tail", "sort", "uniq", "wc", "awk", "sed", "cut", "tr",
        "cat", "less", "more", "xargs", "strings", "find", "ls", "od", "xxd", "hexdump",
        "readelf", "sha256sum", "md5sum", "stat", "file", "basename", "dirname", "env",
        "printenv", "date", "whoami", "id", "uptime", "free", "vmstat", "lsof", "ss",
        "netstat", "getprop", "dmesg", "uname", "ps", "pgrep", "df", "groups", "getenforce",
        "lsmod", "modinfo", "sysctl", "echo", "printf",
        "seinfo", "sesearch", "dumpsys", "route", "arp", "zcat", "xzcat", "gzip",
        "bzip2", "logcat",
    )
    private val WRITE_TOKENS = listOf(
        ">", ">>", "tee ", "dd of=", "dd if=", "chmod ", "chown ", "chattr ",
        "rm ", "rmdir ", "mv ", "cp ", "ln ", "mkfs", "wipe", "format", "flash", "fastboot",
        "setprop ", "insmod", "rmmod", "reboot", "unlock", "efuse", "qfprom", "scm write",
        "iptables", "ip6tables", "svc ", "pm uninstall", "pm clear", "am force-stop",
        "applypatch", "uncrypt", "update_engine", "flash_image", "kill ", "pkill ", "killall ",
        "touch ", "mkdir ", "sed -i", "wget ", "curl ", "nc ", "ncat ", "socat ",
        "sysctl -w", "sh -c", "bash -c",
        "sh -c'", "bash -c'", "-e /system/bin/sh", "-e sh", "chroot ", "switch_root ",
        "dd ", "mkfs.", "mkswap", "swapon", "swapoff", "losetup", "dmsetup", "pvcreate",
        "vgcreate", "lvcreate", "fdisk", "parted", "blkdiscard", "blockdev", "hdparm",
        "echo >", "printf >", "cat >", "tee >", "> /", "> /data", "> /system", "> /vendor",
        "mount -o ", "mount --bind ", "mount -t ", "tar -x", "tar -c", "tar -xf", "tar -cf",
        "gzip ", "gunzip ", "unzip ", "zip ", "settings put ",
        ".write", ".backup", ".restore", "insert ", "update ", "delete ", "drop ", "create table ",
    )
    private val SHELL_METACHARS = listOf(";", "&&", "||", "\n", "`", "$(", "\${", ">>", ">")

    /** Vérifie si une commande est en lecture seule (true = sûre). */
    fun isReadOnly(cmd: String): Boolean {
        var c = cmd.trim()
        if (c.isEmpty()) return false
        c = c.replace("2>/dev/null", "").replace(">/dev/null", "").replace("&>/dev/null", "").trim()
        val isReadPrefix = READ_ONLY_PREFIXES.any { c.startsWith(it, ignoreCase = true) } ||
            BARE_READ_COMMANDS.contains(c)
        if (!isReadPrefix) {
            if (WRITE_TOKENS.any { c.contains(it, ignoreCase = true) }) return false
            if (!READ_BINS.any { c.startsWith(it, ignoreCase = true) }) return false
        }
        if (SHELL_METACHARS.any { c.contains(it) }) return false
        val segments = splitPipesIgnoringQuotes(c)
        for (i in 1 until segments.size) {
            val after = segments[i].trim()
            val bin = after.substringBefore(' ').substringBefore('\t')
            if (!PIPE_READ_BINS.any { bin.startsWith(it, ignoreCase = true) }) return false
        }
        return true
    }

    private fun splitPipesIgnoringQuotes(cmd: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < cmd.length) {
            val ch = cmd[i]
            when {
                ch == '\'' && !inDouble -> inSingle = !inSingle
                ch == '"' && !inSingle -> inDouble = !inDouble
                ch == '|' && !inSingle && !inDouble -> {
                    parts.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
            i++
        }
        parts.add(current.toString())
        return parts
    }

    /** Exécute une commande root en LECTURE SEULE. Refuse (et log) toute commande d'écriture. */
    fun suCmdReadOnly(cmd: String): String {
        if (!isReadOnly(cmd)) {
            android.util.Log.w("RootHelper", "Commande refusee (lecture seule) : $cmd")
            return "REFUSED: commande non lecture seule"
        }
        return suCmd(cmd)
    }

    fun suCmdReadOnlyLong(cmd: String): String {
        if (!isReadOnly(cmd)) {
            android.util.Log.w("RootHelper", "Commande refusee (lecture seule) : $cmd")
            return "REFUSED: commande non lecture seule"
        }
        return suCmdLong(cmd)
    }

    private fun suExec(su: String, cmd: String, timeoutMs: Long): String {
        val escaped = cmd.replace("'", "'\\''")
        suLock.lock()
        try {
            return exec("/system/bin/sh", "$su -c '$escaped'", timeoutMs)
        } finally {
            suLock.unlock()
        }
    }

    private fun exec(bin: String, command: String, timeoutMs: Long): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf(bin, "-c", command))
            try { process.outputStream.close() } catch (e: Exception) { /* deja ferme */ }
            val out = StringBuilder()
            val err = StringBuilder()
            val outThread = Thread { out.append(drain(process.inputStream)) }
            val errThread = Thread { err.append(drain(process.errorStream)) }
            outThread.start()
            errThread.start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                val exitedCleanly = process.waitFor(1000, TimeUnit.MILLISECONDS)
                if (!exitedCleanly) {
                    process.destroyForcibly()
                    process.waitFor(1000, TimeUnit.MILLISECONDS)
                }
            }
            outThread.join(1000)
            errThread.join(1000)
            (out.toString().trim() + "\n" + err.toString().trim()).trim()
        } catch (e: Exception) {
            ""
        }
    }

    private fun drain(stream: java.io.InputStream): String {
        return try {
            stream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
    }
}
