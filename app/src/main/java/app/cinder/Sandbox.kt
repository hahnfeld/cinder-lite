package app.cinder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.system.Os
import android.util.Base64
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * The app's own little Linux sandbox.
 *
 * Everything lives under the app's private files dir, which on Android is a real filesystem the
 * app fully owns. The phone's shared storage is bound in at /sdcard once the user grants the
 * storage permission.
 *
 *   files/                    (guest path inside the sandbox in parentheses)
 *     home/            $HOME (/root) — config, caches, the claude login credential, and Claude
 *                      Code itself (its native install: .local/bin/claude -> .local/share/claude)
 *     workspace/       the agent's cwd (/workspace)
 *     bin/             proot and the helper scripts (/usr/local/bin)
 *     lib/             proot's libraries: libtalloc, libandroid-shmem (/usr/local/androidlib)
 *     libexec/         proot's loader, Claude's system-prompt brief and hooks, rish's socket (/usr/local/libexec)
 *     lx/              the staged bionic linker64 (/usr/local/androidlibexec) — see [androidLibexec]
 *     tmp/             (/tmp)
 *     rootfs/          the Alpine tree that becomes /
 *
 * Executing binaries out of a writable app directory is blocked for apps that target API 29+,
 * so this app targets 28 on purpose — the same approach Termux used. Sideload only; Play would
 * reject it.
 */
class Sandbox(private val ctx: Context) {

    val root: File get() = ctx.filesDir
    val home = File(root, "home")
    val workspace = File(root, "workspace")
    val bin = File(root, "bin")
    val lib = File(root, "lib")
    val libexec = File(root, "libexec")
    val rootfs = File(root, "rootfs")
    val tmp = File(root, "tmp")

    // Android/aarch64 (bionic) binaries — aapt2, d8, a nested proot — name /system/bin/linker64
    // as their ELF interpreter. That's a symlink into /apex, and the app's SELinux domain
    // (untrusted_app_27) won't let proot sanitize /apex, so the loader dead-ends: "cannot execute:
    // required file not found". The app itself, however, IS allowed to read those files (they're
    // labelled system_linker_exec / system_lib_file, readable by every app). So we copy the real
    // linker and bionic .so files into these dirs FROM THE ANDROID SIDE; nested proot runs through
    // that linker explicitly, and android-bootstrap rewrites Termux binaries to use it.
    val androidLib64 = File(rootfs, "usr/local/androidlib64")   // flat bionic .so set
    // The copied linker64 lives at a SHORT host path, bound in at /usr/local/androidlibexec.
    // Android 15 kernels with 16 KB page-size compat check whether a madvise() comes from the
    // linker by d_path()-ing its file into a small buffer, without checking for failure: a linker
    // path that doesn't fit (the old rootfs/usr/local/androidlibexec/linker64 was 74 chars)
    // dereferences ERR_PTR(-ENAMETOOLONG) and panics the whole device. Observed on the Android 15
    // emulator kernel 6.6.30-android15-8: `sudo` / `apk add` rebooted it.
    val androidLibexec = File(root, "lx")
    val shims = File(rootfs, "usr/local/shims")                  // agent-facing wrappers, first on PATH

    fun ensureLayout() {
        listOf(home, workspace, bin, lib, libexec, rootfs, tmp).forEach { it.mkdirs() }
    }

    /** Set once [stageAndroidRuntime] has copied every bionic library (Termux tooling needs them). */
    private val fullRuntimeMarker get() = File(androidLib64, ".full")

    /**
     * Copies the bionic dynamic linker and system .so files into the rootfs using ordinary file
     * I/O from the app's own process — the one context that can read them, since proot can't bind
     * /apex under this SELinux domain. Only copies what's missing.
     *
     * By default that's just what nested proot (sudo, apk) needs — a few MB. [full] copies the
     * device's whole bionic library set (~200 MB), which only Termux binaries need; termux-install
     * asks the app for it through a request file (see SessionService).
     */
    fun stageAndroidRuntime(full: Boolean = false) {
        listOf(androidLib64, androidLibexec, shims).forEach { it.mkdirs() }
        val linkerDst = File(androidLibexec, "linker64")
        val wanted = if (full) null else NESTED_PROOT_LIBS
        val libsStaged = wanted?.all { File(androidLib64, it).exists() } ?: fullRuntimeMarker.exists()
        if (linkerDst.canExecute() && libsStaged) return

        if (!linkerDst.canExecute()) {
            for (cand in listOf("/apex/com.android.runtime/bin/linker64", "/system/bin/linker64")) {
                if (runCatching { stage(File(cand), linkerDst, executable = true) }.isSuccess) break
            }
        }

        // cp by resolving symlinks so each name is a real file in one flat directory the linker
        // (and each lib's own rpath) can scan. Unreadable APEX partitions are simply skipped.
        val srcDirs = listOf(
            "/apex/com.android.runtime/lib64/bionic",
            "/apex/com.android.runtime/lib64",
            "/apex/com.android.art/lib64",
            "/apex/com.android.i18n/lib64",
            "/system/lib64"
        )
        for (d in srcDirs) {
            val files = runCatching {
                File(d).listFiles { f -> f.isFile && f.name.endsWith(".so") && (wanted == null || f.name in wanted) }
            }.getOrNull() ?: continue
            for (so in files) {
                val dst = File(androidLib64, so.name)
                if (!dst.exists()) runCatching { stage(so, dst) }
            }
        }
        if (full) fullRuntimeMarker.createNewFile()
    }

    /**
     * Copies [from] (symlinks resolved) to [to] through a temp file: a copy that's cut short never
     * leaves a truncated library that later runs would skip, and a running proot that has [to]
     * mapped keeps its old file.
     */
    private fun stage(from: File, to: File, executable: Boolean = false) =
        replace(to, executable) { out -> from.canonicalFile.inputStream().use { it.copyTo(out, 1 shl 16) } }

    /**
     * Writes [to] through a temp file of its own and renames it into place. A process that has the
     * old file open or mapped (a running proot, a script mid-run) keeps it, and two launches at once
     * never write the same temp file.
     */
    private fun replace(to: File, executable: Boolean, write: (java.io.OutputStream) -> Unit) {
        to.parentFile?.mkdirs()
        val part = File.createTempFile(to.name, ".part", to.parentFile)
        try {
            part.outputStream().use(write)
            part.setReadable(true, false)
            if (executable) part.setExecutable(true, false)
            check(part.renameTo(to)) { "could not install ${to.name}" }
        } finally {
            part.delete()   // gone already when the rename worked
        }
    }

    /**
     * Termux's proot is dynamically linked and shells out to a separate loader binary, so
     * copying just `proot` out of Termux gives a binary that dies before it does anything:
     * it needs libtalloc.so.2 on the library path and PROOT_LOADER pointing at the loader.
     */
    val prootLoader = File(libexec, "loader")
    val hasBash get() = File(rootfs, "bin/bash").exists()
    /**
     * What [installBaseTools] puts down, as apk itself records it. Installs from before apk could
     * write its database have the files but no record, and get them installed (recorded) again.
     */
    val hasBaseTools get() = runCatching {
        File(rootfs, "lib/apk/db/installed").useLines { lines -> lines.filter { it.startsWith("P:") }.toSet() }
    }.getOrDefault(emptySet()).containsAll(listOf("P:bash", "P:python3", "P:nodejs"))
    /** The base tools' telltale files, recorded or not. */
    private val hasBaseFiles get() = hasBash && listOf("usr/bin/python3", "usr/bin/node").all { File(rootfs, it).exists() }

    /**
     * Claude Code's native install, as `claude install` lays it out under $HOME: a symlink to the
     * current version (a guest path, so it doesn't resolve on the host side) plus the versions dir.
     * Claude Code updates itself in place there.
     */
    private val claudeLink get() = File(home, ".local/bin/claude")
    private val claudeVersions get() = File(home, ".local/share/claude")
    val hasClaude
        get() = Files.isSymbolicLink(claudeLink.toPath()) &&
            File(claudeVersions, "versions").list()?.isNotEmpty() == true

    fun env(): Map<String, String> {
        val path = listOf(bin.absolutePath, "/system/bin", "/system/xbin").joinToString(":")
        return mapOf(
            "HOME" to home.absolutePath,
            "PATH" to path,
            "TMPDIR" to tmp.absolutePath,
            "PWD" to workspace.absolutePath,
            "LANG" to "C.UTF-8",
            "TERM" to "xterm-256color",
            // proot's own requirements — without these it exits before tracing anything
            "PROOT_LOADER" to prootLoader.absolutePath,
            "PROOT_TMP_DIR" to tmp.absolutePath,
            "LD_LIBRARY_PATH" to lib.absolutePath
        )
    }

    /**
     * proot refuses to sanitize a binding whose target doesn't exist inside the rootfs, so the
     * mount points have to be created there first.
     */
    fun ensureMountPoints() {
        val fixed = listOf(
            "workspace", "root", "tmp", "usr/local/bin", "usr/local/libexec", "usr/local/androidlib",
            "usr/local/androidlibexec",
            // JVMs and anything using shm_open expect this to exist; Alpine's minirootfs has no
            // /dev/shm and Android provides none to bind.
            "dev/shm",
            "sdcard"
        )
        // Android's linker and bionic libs live in /apex, and /system/bin/linker64 is only a
        // symlink into it — a mount point is needed inside the rootfs for each path we bind.
        (fixed + androidPaths.map { it.trimStart('/') }).forEach { File(rootfs, it).mkdirs() }
    }

    /**
     * Alpine's minirootfs ships no /etc/resolv.conf, so nothing inside the sandbox can resolve a
     * hostname — every request dies as ETIMEDOUT. Write the device's current DNS servers in, and
     * refresh it on each launch since they change with the network.
     */
    fun configureNetwork() {
        val servers = runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val net = cm.activeNetwork
            cm.getLinkProperties(net)?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
        }.getOrDefault(emptyList())

        // Public resolvers as a fallback: a VPN or a restricted network can hide the real ones.
        val all = (servers + listOf("1.1.1.1", "8.8.8.8")).distinct().take(4)
        val etc = File(rootfs, "etc").apply { mkdirs() }
        File(etc, "resolv.conf").writeText(
            all.joinToString("\n") { "nameserver $it" } + "\noptions timeout:2 attempts:2\n"
        )
        val hosts = File(etc, "hosts")
        if (!hosts.exists() || hosts.readText().isBlank()) {
            hosts.writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost\n")
        }
    }

    /**
     * Snapshots the device's system properties for the sandbox. The agent's `getprop` can't run
     * /system/bin/getprop through proot (this app's SELinux domain may exec it but not open it for
     * reading), but the app PROCESS can Runtime.exec it directly — the standard way apps read ro.*
     * props. Write the full output where the getprop shim reads it. Refreshed each launch so ro.*
     * and current values stay reasonably fresh.
     */
    fun refreshSystemInfo() {
        runCatching {
            val p = ProcessBuilder("getprop").redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor()
            if (text.isNotBlank()) {
                File(home, ".cinder").apply { mkdirs() }
                File(home, ".cinder/getprop.txt").writeText(text)
            }
        }
    }

    /**
     * Alpine's minirootfs enables only the **main** repo. Add the matching stable **community**
     * repo, plus the rolling edge and testing repos as *tagged* repos: apk picks the highest
     * version across untagged repos, so untagged edge would silently replace the stable base. Tagged,
     * they're used only on request — `apk add wine@testing`, `apk add foo@edge`.
     * Refreshed each launch; the apk shim fetches indexes with --no-cache so no `apk update` needed.
     */
    fun configureRepositories() {
        val apk = File(rootfs, "etc/apk").apply { mkdirs() }
        val release = runCatching { File(rootfs, "etc/alpine-release").readText().trim() }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: "3.24.0"
        // "3.24.1" -> "v3.24"; anything unexpected falls back to the rolling edge branch.
        val branch = Regex("""^(\d+)\.(\d+)""").find(release)?.let { "v${it.groupValues[1]}.${it.groupValues[2]}" }
            ?: "edge"
        val mirror = "https://dl-cdn.alpinelinux.org/alpine"
        File(apk, "repositories").writeText(
            buildString {
                append("$mirror/$branch/main\n")
                append("$mirror/$branch/community\n")
                append("@edge $mirror/edge/main\n")
                append("@edge $mirror/edge/community\n")
                append("@testing $mirror/edge/testing\n")
            }
        )
    }

    /** Android paths bound into the sandbox. (Not /linkerconfig: apps can't read it, so proot only warns.) */
    private val androidPaths = listOf(
        "/system", "/apex", "/vendor", "/proc", "/dev", "/sys", "/system_ext", "/product"
    )

    private fun androidBinds(): Array<String> =
        androidPaths.flatMap { listOf("-b", "$it:$it") }.toTypedArray()

    /**
     * The argv that runs [inner] (a /bin/sh command line) inside the Alpine rootfs under proot: the
     * rootfs becomes /, with the sandbox dirs bound in. Touches files and runs getprop, so call it
     * off the main thread.
     *
     * [asRoot] fakes uid 0 inside the rootfs, which apk needs to install packages. Claude must NOT
     * run that way: it refuses to skip permission checks while it thinks it is root, and exits 1.
     */
    fun prootCommand(inner: String, asRoot: Boolean = false): Array<String> {
        // proot, its pieces and the helpers are writable from inside (they're bound in), so each
        // session starts from the APK's copies of them, and an app update's fixes reach existing
        // installs. (The staged linker and libs in lx/ and androidlib64/ are only copied when missing.)
        ensureLayout()
        installRuntime()
        writeHelpers()
        val proot = File(bin, "proot").takeIf { it.canExecute() }?.absolutePath ?: error("proot is not installed")
        ensureMountPoints()
        configureNetwork()
        configureRepositories()
        refreshSystemInfo()   // snapshot getprop for the sandbox's getprop shim
        // Stage the bionic linker + flat libs in from the app side (proot can't reach /apex here).
        // Idempotent and cheap once done; must happen before any Android binary is exec'd.
        stageAndroidRuntime()
        // Inside the rootfs the host's PATH and LD_LIBRARY_PATH are meaningless — they point at
        // Android's bionic world, and leaving them set makes musl binaries fail to find anything.
        // Claude Code wants a POSIX shell it can drive; busybox ash is a fallback, real bash is
        // what it asks for. SHELL has to name one that exists or the Bash tool refuses to run.
        val shell = if (hasBash) "/bin/bash" else "/bin/sh"
        val prelude = buildString {
            // /usr/local/shims is first so agent-facing wrappers (e.g. a nested-safe `proot`) win
            // over the native binaries bound in under /usr/local/bin.
            //
            // CRITICAL: Alpine's own bin dirs come BEFORE Termux's. Termux ships bionic coreutils
            // (sort, head, cut, java, …) that need libandroid-selinux.so and the linker treatment;
            // if they shadow Alpine's reliable musl builds, ordinary commands — even the ones inside
            // these very helper scripts (termux-install uses `sort -u`) — die with "library not
            // found". So Alpine wins for anything it has, and Termux only supplies what Alpine lacks
            // (aapt2, d8, apksigner — none of which exist in Alpine, so they're still found).
            append("export PATH=/usr/local/shims:/usr/local/bin:/root/.npm-global/bin:/root/.local/bin:")
            append("/usr/bin:/bin:/usr/sbin:/sbin:")
            append("/data/data/com.termux/files/usr/bin:")   // Android-native tools (aapt2, d8…), after Alpine
            append("/system/bin:/system/xbin; ")
            append("export TERMUX_PREFIX=/data/data/com.termux/files/usr; ")
            append("export ANDROID_HOME=/root/android-sdk ANDROID_SDK_ROOT=/root/android-sdk; ")
            // A JVM under proot trips over three things by default: it maps a shared class-data
            // archive at a fixed address, writes perf data into /tmp/hsperfdata, and sizes its
            // heap from the device's total RAM rather than what this process may actually get.
            // -Xshare:off      : the CDS archive maps at a fixed address proot won't honour
            // -UsePerfData     : hsperfdata needs working shared memory
            // UseSerialGC      : G1 reserves large regions up front and fails under proot
            // CompressedClassSpaceSize: the default 1 GB reservation is refused
            // -Xmx512m         : the JVM sizes the heap from total RAM, then can't reserve it
            append("export JAVA_TOOL_OPTIONS=\"-XX:-UsePerfData -Xshare:off -XX:+UseSerialGC ")
            append("-XX:CompressedClassSpaceSize=64m -Xms16m -Xmx512m -Djava.io.tmpdir=/tmp\"; ")
            // Alpine's JVM, not Termux's: a musl-native HotSpot that JITs, rather than an Android
            // binary that can't find its loader in here.
            append("export JAVA_HOME=/usr/lib/jvm/default-jvm; ")
            append("export PATH=\$PATH:/usr/lib/jvm/default-jvm/bin:/usr/local/jadx/bin; ")
            append("unset LD_LIBRARY_PATH; export HOME=/root TMPDIR=/tmp SHELL=$shell; ")
            // npm -g and pip both want to write to system dirs, which the unprivileged agent
            // can't touch. Point them at the home directory instead so installs just work.
            append("export NPM_CONFIG_PREFIX=/root/.npm-global; ")
            append("export PIP_USER=1 PIP_BREAK_SYSTEM_PACKAGES=1 PIP_DISABLE_PIP_VERSION_CHECK=1; ")
            append("export PYTHONUSERBASE=/root/.local; ")
            // Nothing in here can open a browser; xdg-open hands the URL to the app (OAuth login).
            append("export BROWSER=xdg-open; ")
        }
        // The phone's shared storage, once the user has granted the (legacy, targetSdk 28)
        // storage permission — the same access Termux's termux-setup-storage gives.
        val sdcard = if (ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) listOf("-b", "${Environment.getExternalStorageDirectory().absolutePath}:/sdcard") else emptyList()
        return listOfNotNull(
            proot,
            // SELinux denies hard links in app storage; proot emulates them with symlinks (as
            // Termux's proot-distro does). apk commits its database through one, git and npm use them.
            "--link2symlink",
            if (asRoot) "-0" else null,
            "-r", rootfs.absolutePath,
            *androidBinds(),
            "-b", "${workspace.absolutePath}:/workspace",
            "-b", "${home.absolutePath}:/root",
            "-b", "${bin.absolutePath}:/usr/local/bin",
            // proot's own pieces have to be reachable from inside too, so `sudo` can re-enter
            "-b", "${libexec.absolutePath}:/usr/local/libexec",
            "-b", "${lib.absolutePath}:/usr/local/androidlib",
            "-b", "${androidLibexec.absolutePath}:/usr/local/androidlibexec",
            "-b", "${tmp.absolutePath}:/tmp",
            *sdcard.toTypedArray(),
            "-w", "/workspace",
            "/bin/sh", "-c", prelude + inner
        ).toTypedArray()
    }

    /** Starts [inner] in the sandbox as a plain (non-terminal) process, for setup steps. */
    fun spawn(inner: String, asRoot: Boolean = false): Process {
        val pb = ProcessBuilder(*prootCommand(inner, asRoot)).directory(workspace)
        pb.environment().putAll(env())
        return pb.start()
    }

    /**
     * Installs the sandbox's helper commands from assets/helpers/ — sudo, the apk shim, xdg-open,
     * the Android-toolchain installers and friends (each script documents itself). bin/ is
     * /usr/local/bin; shims/ is first on PATH, for wrappers that must shadow a real binary
     * (proot, getprop). Plus Claude's system-prompt brief and notification hooks, and a linker config
     * for the staged bionic linker (see assets/linkerconfig/ld.config.txt).
     */
    private fun writeHelpers() {
        copyAsset("brief.md", File(libexec, "cinder-brief.md"))
        copyAsset("claude-settings.json", File(libexec, "cinder-settings.json"))
        copyAsset("linkerconfig/ld.config.txt", File(rootfs, "linkerconfig/ld.config.txt"))
        for ((dir, target) in listOf("bin" to bin, "shims" to shims)) {
            for (name in ctx.assets.list("helpers/$dir").orEmpty()) {
                copyAsset("helpers/$dir/$name", File(target, name), executable = true)
            }
        }
    }

    private fun copyAsset(name: String, to: File, executable: Boolean = false) =
        replace(to, executable) { out -> ctx.assets.open(name).use { it.copyTo(out, 1 shl 16) } }

    // ---------------- setup: run off the main thread, report through [log] ----------------

    private val bootstrapStamp get() = File(root, ".bootstrap-v1")

    /** Everything Claude Code needs before it can start: runtime, Claude Code itself, base tools. */
    val ready get() = bootstrapStamp.exists() && hasClaude && hasBaseTools

    /**
     * Unpacks the Alpine rootfs that ships inside the APK, once. (proot, its pieces and the helpers
     * are copied out at every launch, by [prootCommand].) Claude Code itself is deliberately not
     * bundled — see [installClaude]. The stamp is written last, so a rootfs unpack that was
     * interrupted starts over next time.
     */
    fun bootstrap(log: (String) -> Unit) {
        ensureLayout()
        if (bootstrapStamp.exists()) return
        log("unpacking Alpine rootfs…")
        deleteTree(rootfs)
        ctx.assets.open("rootfs.tgz").use { raw ->
            GZIPInputStream(BufferedInputStream(raw)).use { extractTar(it, into = rootfs) }
        }
        bootstrapStamp.writeText("1")
    }

    /** Copies proot, its loader and libraries out of the APK (see [replace]). */
    private fun installRuntime() {
        copyAsset("proot", File(bin, "proot"), executable = true)
        copyAsset("loader", File(libexec, "loader"), executable = true)
        copyAsset("libtalloc.so.2", File(lib, "libtalloc.so.2"))
        copyAsset("libandroid-shmem.so", File(lib, "libandroid-shmem.so"))
    }

    /**
     * Back to a fresh install: the rootfs (and everything installed into it), the staged Android
     * runtime and Claude Code's binaries. The login and settings (home/) and the user's files
     * (workspace/) stay.
     */
    fun reset() {
        bootstrapStamp.delete()
        claudeLink.delete()
        deleteTree(claudeVersions)
        deleteTree(androidLibexec)
        deleteTree(rootfs)
    }

    /**
     * Installs the latest Claude Code. The official linux-arm64-musl build of npm's current release
     * is downloaded — what `npm install` would fetch on a laptop — and checked against the sha512 npm
     * publishes for it, then its own `claude install` sets up the native install in $HOME. From
     * there Claude Code keeps itself up to date.
     */
    fun installClaude(log: (String) -> Unit) {
        val meta = org.json.JSONObject(fetch(NPM_LATEST).readBytes().decodeToString())
        val version = meta.getString("version")
        val dist = meta.getJSONObject("dist")
        val expected = dist.getString("integrity").removePrefix("sha512-")
        log("downloading Claude Code $version (about 240 MB)…")
        val installer = File(tmp, "claude-installer")
        installer.delete()
        try {
            val sha512 = MessageDigest.getInstance("SHA-512")
            DigestInputStream(BufferedInputStream(fetch(dist.getString("tarball")), 1 shl 16), sha512).use { hashed ->
                val gz = GZIPInputStream(hashed, 1 shl 16)
                extractTar(gz, onlyEntry = "package/claude") { entry ->
                    installer.outputStream().use { copyWithProgress(entry, it, log) }
                }
                // Read to the very end, so every byte is hashed and gzip checks its CRC trailer.
                gz.drain()
                hashed.drain()
            }
            val got = Base64.encodeToString(sha512.digest(), Base64.NO_WRAP)
            check(got == expected) { "download failed verification (sha512 mismatch)" }
            check(installer.length() > 0) { "archive did not contain package/claude" }
            installer.setExecutable(true, false)
            log("installing…")
            run("/tmp/claude-installer install latest", log = log)
        } finally {
            installer.delete()
        }
        check(hasClaude) { "claude install did not complete" }
    }

    private fun fetch(url: String): InputStream =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
        }.inputStream

    private fun copyWithProgress(input: InputStream, out: java.io.OutputStream, log: (String) -> Unit) {
        val buf = ByteArray(1 shl 16)
        var total = 0L
        var next = PROGRESS_STEP
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
            if (total >= next) { log("  ${total shr 20} MB"); next += PROGRESS_STEP }
        }
    }

    /**
     * Alpine's minirootfs has no bash, and Claude Code's Bash tool wants a real shell. Pull one
     * in with the tools it reaches for constantly, plus Python and Node; anything else the agent
     * can `apk add` itself.
     */
    fun installBaseTools(log: (String) -> Unit) {
        val packages = "bash coreutils findutils grep sed git ripgrep less curl python3 py3-pip nodejs npm " +
            "openssh-client make patch zip jq"
        log("apk add $packages …")
        // Real apk under proot -0, its progress ("(12/45) Installing …") logged as it happens.
        run("/sbin/apk add --no-cache $packages", asRoot = true, log = log)
        // Offline, an install whose tools are only missing their record still has them all: carry
        // on, and record them next launch.
        check(hasBaseFiles) { "bash, python3 or node is still missing" }
    }

    /** Deletes [dir] and everything under it without following symlinks out of it. */
    private fun deleteTree(dir: File) {
        val start = dir.toPath()
        if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult {
                d.toFile().setWritable(true, true)   // packages ship read-only dirs (0555)
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(f: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(f)
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(f: Path, e: IOException): FileVisitResult {
                Files.deleteIfExists(f)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(d: Path, e: IOException?): FileVisitResult {
                Files.deleteIfExists(d)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** Runs [inner] in the sandbox, logging each line of its combined output. */
    private fun run(inner: String, asRoot: Boolean = false, log: (String) -> Unit) {
        val p = spawn("$inner 2>&1", asRoot)
        p.inputStream.bufferedReader().forEachLine { if (it.isNotBlank()) log("  ${it.trim()}") }
        p.waitFor()
    }

    /**
     * Minimal tar reader: enough for npm tarballs and the Alpine minirootfs (ustar, longlink-free),
     * including the symlinks a rootfs depends on. With [onlyEntry], hands just that entry to [handler].
     */
    private fun extractTar(
        input: InputStream,
        into: File? = null,
        onlyEntry: String? = null,
        handler: ((InputStream) -> Unit)? = null
    ) {
        val header = ByteArray(512)
        while (input.readFully(header) && header.any { it != 0.toByte() }) {
            val name = String(header, 0, 100).trimEnd('\u0000', ' ')
            if (name.isEmpty()) break
            val size = String(header, 124, 12).trim { it <= ' ' }.ifEmpty { "0" }.toLong(8)
            val type = header[156].toInt().toChar()
            val linkName = String(header, 157, 100).trimEnd('\u0000', ' ')
            val padded = ((size + 511) / 512) * 512
            when {
                onlyEntry != null -> {
                    if (name == onlyEntry && handler != null) {
                        handler(LimitedStream(input, size))
                        input.skipExactly(padded - size)
                    } else input.skipExactly(padded)
                }
                else -> {
                    val rel = name.removePrefix("./")
                    val target = File(requireNotNull(into), rel)
                    when (type) {
                        '5' -> { target.mkdirs(); input.skipExactly(padded) }
                        '2' -> {
                            target.parentFile?.mkdirs()
                            runCatching { target.delete(); Os.symlink(linkName, target.absolutePath) }
                            input.skipExactly(padded)
                        }
                        '1' -> input.skipExactly(padded)
                        else -> {
                            target.parentFile?.mkdirs()
                            target.delete()   // never write through a symlink left at this path
                            target.outputStream().use { LimitedStream(input, size).copyTo(it, 1 shl 16) }
                            input.skipExactly(padded - size)
                            if (rel.startsWith("bin/") || rel.startsWith("usr/bin/") ||
                                rel.startsWith("sbin/") || rel.startsWith("lib/")
                            ) target.setExecutable(true, false)
                        }
                    }
                }
            }
        }
    }

    private fun InputStream.readFully(buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = read(buf, read, buf.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun InputStream.drain() {
        val junk = ByteArray(1 shl 16)
        while (read(junk) >= 0) { /* discard */ }
    }

    private fun InputStream.skipExactly(n: Long) {
        var left = n
        val junk = ByteArray(8192)
        while (left > 0) {
            val got = read(junk, 0, minOf(junk.size.toLong(), left).toInt())
            if (got < 0) return
            left -= got
        }
    }

    private class LimitedStream(val inner: InputStream, var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val b = inner.read()
            if (b >= 0) left--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = inner.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }

    companion object {
        private const val NPM_LATEST = "https://registry.npmjs.org/@anthropic-ai/claude-code-linux-arm64-musl/latest"
        private const val PROGRESS_STEP = 25L shl 20

        /** What nested proot (sudo, apk) needs: libc and friends, plus liblog for libandroid-shmem. */
        private val NESTED_PROOT_LIBS = setOf("libc.so", "libdl.so", "libm.so", "ld-android.so", "liblog.so", "libc++.so")
    }
}
