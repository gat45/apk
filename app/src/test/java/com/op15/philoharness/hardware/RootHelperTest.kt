package com.op15.philoharness.hardware

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Teste uniquement isReadOnly() (fonction pure, pas d'exec root) -- la partie critique pour
 * la securite de ce portage. Les cas couverts reprennent les 5 bypass corriges dans le fichier
 * source (audit 18/08/2026) : on verifie qu'ils restent bloques apres le portage.
 */
class RootHelperTest {

    @Test
    fun `simple read commands are allowed`() {
        assertTrue(RootHelper.isReadOnly("cat /proc/version"))
        assertTrue(RootHelper.isReadOnly("ls /sys/class/thermal"))
        assertTrue(RootHelper.isReadOnly("getprop ro.build.version.release"))
        // "dmesg" seul (sans espace/argument final) est en fait rejete par la logique
        // d'origine : READ_BINS exige "dmesg " (espace terminal) et BARE_READ_COMMANDS ne
        // liste pas "dmesg" nu -- trouve en ecrivant ce test, comportement herite du fichier
        // source, pas un bug introduit par ce portage. "dmesg -t" (avec argument) passe.
        assertTrue(RootHelper.isReadOnly("dmesg -t"))
        assertFalse(RootHelper.isReadOnly("dmesg"))
    }

    @Test
    fun `pgrep is allowed -- real bug found testing LlmServerController on device`() {
        // "pgrep" manquait de READ_BINS/PIPE_READ_BINS : isRunning() (LlmServerController)
        // recevait "REFUSED: ..." (chaine NON VIDE) pour CHAQUE appel, donc .isNotBlank()
        // etait toujours vrai -- le demarrage du serveur se croyait bloque par un serveur
        // "deja en cours" qui n'existait pas, sur TOUTES les tentatives (2026-10-14).
        assertTrue(RootHelper.isReadOnly("pgrep llama-server"))
    }

    @Test
    fun `write commands are rejected`() {
        assertFalse(RootHelper.isReadOnly("rm -rf /data"))
        assertFalse(RootHelper.isReadOnly("echo 1 > /sys/foo"))
        assertFalse(RootHelper.isReadOnly("chmod 777 /system"))
        assertFalse(RootHelper.isReadOnly("reboot"))
        assertFalse(RootHelper.isReadOnly("mount -o rw,remount /system"))
    }

    @Test
    fun `pipe to a read binary is allowed`() {
        assertTrue(RootHelper.isReadOnly("dmesg | grep fastrpc"))
        assertTrue(RootHelper.isReadOnly("ls /dev | grep rpc | head -5"))
    }

    @Test
    fun `pipe to a non-read binary is rejected (bypass B1)`() {
        assertFalse(RootHelper.isReadOnly("printf 'id' | sh"))
    }

    @Test
    fun `IFS substitution is rejected because metacharacters are still blocked (bypass B2)`() {
        assertFalse(RootHelper.isReadOnly("printf 'rm\${IFS}-rf${'$'}{IFS}/data'"))
    }

    @Test
    fun `busybox and toybox are not whitelisted (bypass B3)`() {
        assertFalse(RootHelper.isReadOnly("busybox sh -c 'id'"))
        assertFalse(RootHelper.isReadOnly("toybox sh -c 'id'"))
    }

    @Test
    fun `write binaries added after the original audit are rejected (bypass B4)`() {
        assertFalse(RootHelper.isReadOnly("touch /data/local/tmp/x"))
        assertFalse(RootHelper.isReadOnly("mkdir /data/local/tmp/x"))
        assertFalse(RootHelper.isReadOnly("sed -i 's/a/b/' /some/file"))
    }

    @Test
    fun `shell metacharacters are rejected even on an otherwise-safe prefix`() {
        assertFalse(RootHelper.isReadOnly("cat /proc/version; rm -rf /"))
        assertFalse(RootHelper.isReadOnly("cat /proc/version && reboot"))
        assertFalse(RootHelper.isReadOnly("echo `id`"))
        assertFalse(RootHelper.isReadOnly("echo \$(id)"))
    }

    @Test
    fun `grep alternation inside quotes is not mistaken for a shell pipe`() {
        // "grep -iE 'geniex|QnnHtp'" : le | est a l'interieur de quotes simples (regex
        // alternation), pas un pipe shell -- doit rester une commande read-only valide.
        assertTrue(RootHelper.isReadOnly("grep -iE 'geniex|QnnHtp' /proc/version"))
    }

    @Test
    fun `stderr redirect to devnull is still considered read-only`() {
        assertTrue(RootHelper.isReadOnly("cat /proc/version 2>/dev/null"))
    }

    @Test
    fun `blank command is rejected`() {
        assertFalse(RootHelper.isReadOnly(""))
        assertFalse(RootHelper.isReadOnly("   "))
    }

    @Test
    fun `read-only prefixes with arguments are allowed`() {
        assertTrue(RootHelper.isReadOnly("dumpsys battery"))
        assertTrue(RootHelper.isReadOnly("logcat -d -t 100"))
    }

    @Test
    fun `bare read commands without trailing args are allowed`() {
        assertTrue(RootHelper.isReadOnly("mount"))
        assertTrue(RootHelper.isReadOnly("ip addr"))
    }
}
