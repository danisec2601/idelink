package io.github.danisec2601.idelink.core

import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.nio.file.Files
import java.nio.file.Path

// The socket is made with libc directly: the JDK's Unix-domain channels never expose the fd, and the
// peer checks need it (SO_PEERCRED / SO_PEERPIDFD).
internal object LibC {
    private val linker = Linker.nativeLinker()
    private val stateLayout = Linker.Option.captureStateLayout()
    private val errnoHandle = stateLayout.varHandle(MemoryLayout.PathElement.groupElement("errno"))
    private val lastErrno = ThreadLocal.withInitial { 0 }

    private fun fn(
        name: String,
        ret: MemoryLayout,
        vararg args: MemoryLayout,
        variadicFrom: Int? = null,
    ): MethodHandle {
        val options = listOfNotNull(
            Linker.Option.captureCallState("errno"),
            variadicFrom?.let { Linker.Option.firstVariadicArg(it) },
        )
        val symbol = linker.defaultLookup().find(name).orElseThrow { UnsatisfiedLinkError("libc: no $name") }
        return linker.downcallHandle(symbol, FunctionDescriptor.of(ret, *args), *options.toTypedArray())
    }

    private val socket = fn("socket", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)
    private val bind = fn("bind", JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)
    private val listen = fn("listen", JAVA_INT, JAVA_INT, JAVA_INT)
    private val accept4 = fn("accept4", JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)
    private val connect = fn("connect", JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)
    private val getsockopt = fn("getsockopt", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS)
    private val setsockopt = fn("setsockopt", JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)
    private val open = fn("open", JAVA_INT, ADDRESS, JAVA_INT)
    private val read = fn("read", JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG)
    private val write = fn("write", JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG)
    private val close = fn("close", JAVA_INT, JAVA_INT)
    private val shutdown = fn("shutdown", JAVA_INT, JAVA_INT, JAVA_INT)
    private val getuid = fn("getuid", JAVA_INT)
    private val syscall =
        fn("syscall", JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG, variadicFrom = 1)

    /** errno as the last call on this thread left it. */
    fun errno(): Int = lastErrno.get()

    private fun <T> call(h: MethodHandle, arena: Arena, vararg args: Any): T {
        val state = arena.allocate(stateLayout)
        @Suppress("UNCHECKED_CAST")
        val r = h.invokeWithArguments(state, *args) as T
        lastErrno.set(errnoHandle.get(state, 0L) as Int)
        return r
    }

    private fun <T> call(h: MethodHandle, vararg args: Any): T = Arena.ofConfined().use { call(h, it, *args) }

    fun socket(domain: Int, type: Int, protocol: Int): Int = call(socket, domain, type, protocol)
    fun bind(fd: Int, addr: ByteArray, len: Int): Int =
        Arena.ofConfined().use { a -> call(bind, a, fd, a.allocateFrom(JAVA_BYTE, *addr), len) }
    fun listen(fd: Int, backlog: Int): Int = call(listen, fd, backlog)
    fun accept4(fd: Int, flags: Int): Int = call(accept4, fd, MemorySegment.NULL, MemorySegment.NULL, flags)
    fun connect(fd: Int, addr: ByteArray, len: Int): Int =
        Arena.ofConfined().use { a -> call(connect, a, fd, a.allocateFrom(JAVA_BYTE, *addr), len) }

    /** Reads option [name] into [value]; the kernel's returned length is not reported. */
    fun getsockopt(fd: Int, level: Int, name: Int, value: ByteArray): Int = Arena.ofConfined().use { a ->
        val v = a.allocate(value.size.toLong())
        val r: Int = call(getsockopt, a, fd, level, name, v, a.allocateFrom(JAVA_INT, value.size))
        MemorySegment.copy(v, JAVA_BYTE, 0, value, 0, value.size)
        r
    }

    fun setsockopt(fd: Int, level: Int, name: Int, value: ByteArray, len: Int): Int =
        Arena.ofConfined().use { a -> call(setsockopt, a, fd, level, name, a.allocateFrom(JAVA_BYTE, *value), len) }
    fun open(path: String, flags: Int): Int = Arena.ofConfined().use { a -> call(open, a, a.allocateFrom(path), flags) }

    fun read(fd: Int, buf: ByteArray, count: Long): Long = Arena.ofConfined().use { a ->
        val b = a.allocate(count)
        val n: Long = call(read, a, fd, b, count)
        if (n > 0) MemorySegment.copy(b, JAVA_BYTE, 0, buf, 0, n.toInt())
        n
    }

    fun write(fd: Int, buf: ByteArray, count: Long): Long =
        Arena.ofConfined().use { a -> call(write, a, fd, a.allocateFrom(JAVA_BYTE, *buf), count) }
    fun close(fd: Int): Int = call(close, fd)
    fun shutdown(fd: Int, how: Int): Int = call(shutdown, fd, how)
    fun getuid(): Int = call(getuid)

    /** pidfd_send_signal([pidfd], [sig], NULL, 0). */
    fun pidfdSendSignal(pidfd: Int, sig: Int): Long =
        call(syscall, Sys.SYS_PIDFD_SEND_SIGNAL, pidfd.toLong(), sig.toLong(), MemorySegment.NULL, 0L)
}

internal object Sys {
    const val AF_UNIX = 1
    const val SOCK_STREAM = 1
    const val SOCK_CLOEXEC = 0x80000
    const val SOL_SOCKET = 1
    const val SO_PEERCRED = 17
    const val SO_RCVTIMEO = 20
    const val SO_PEERPIDFD = 77           // Linux 6.5+
    const val SYS_PIDFD_SEND_SIGNAL = 424L // x86_64
    const val SHUT_RDWR = 2

    fun errno(): Int = LibC.errno()

    /** Makes a blocking read on [fd] fail after [ms] without data; 0 waits forever. x86_64 struct timeval. */
    fun setReadTimeout(fd: Int, ms: Long) {
        val tv = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(ms / 1000).putLong(ms % 1000 * 1000).array()
        if (LibC.setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv, tv.size) != 0) {
            throw IOException("SO_RCVTIMEO: errno ${errno()}")
        }
    }

    fun sockaddr(path: String): ByteArray {
        val p = path.toByteArray()
        require(p.size < 108) { "socket path too long: $path" }
        val b = ByteArray(110)
        b[0] = AF_UNIX.toByte()             // sa_family_t, little-endian
        System.arraycopy(p, 0, b, 2, p.size)
        return b
    }

    fun listenUnix(path: String): Int {
        val fd = LibC.socket(AF_UNIX, SOCK_STREAM or SOCK_CLOEXEC, 0)
        if (fd < 0) throw IOException("socket: errno ${errno()}")
        val addr = sockaddr(path)
        if (LibC.bind(fd, addr, 2 + path.toByteArray().size + 1) != 0) {
            val e = errno(); LibC.close(fd); throw IOException("bind $path: errno $e")
        }
        if (LibC.listen(fd, 8) != 0) {
            val e = errno(); LibC.close(fd); throw IOException("listen: errno $e")
        }
        return fd
    }

    fun connectUnix(path: String): Int {
        val fd = LibC.socket(AF_UNIX, SOCK_STREAM or SOCK_CLOEXEC, 0)
        if (fd < 0) throw IOException("socket: errno ${errno()}")
        if (LibC.connect(fd, sockaddr(path), 2 + path.toByteArray().size + 1) != 0) {
            val e = errno(); LibC.close(fd); throw IOException("connect $path: errno $e")
        }
        return fd
    }

    /** (pid, uid, gid) of the peer as the kernel recorded it at connect time. */
    fun peerCred(fd: Int): Triple<Int, Int, Int> {
        val buf = ByteArray(12)
        if (LibC.getsockopt(fd, SOL_SOCKET, SO_PEERCRED, buf) != 0) throw IOException("SO_PEERCRED: errno ${errno()}")
        fun i(o: Int) = (buf[o].toInt() and 0xff) or ((buf[o + 1].toInt() and 0xff) shl 8) or
            ((buf[o + 2].toInt() and 0xff) shl 16) or ((buf[o + 3].toInt() and 0xff) shl 24)
        return Triple(i(0), i(4), i(8))
    }

    /** A pidfd for the peer: unlike a bare pid it can't be reused for a different process. */
    fun peerPidfd(fd: Int): Int {
        val buf = ByteArray(4)
        if (LibC.getsockopt(fd, SOL_SOCKET, SO_PEERPIDFD, buf) != 0) throw IOException("SO_PEERPIDFD: errno ${errno()}")
        return (buf[0].toInt() and 0xff) or ((buf[1].toInt() and 0xff) shl 8) or
            ((buf[2].toInt() and 0xff) shl 16) or ((buf[3].toInt() and 0xff) shl 24)
    }

    fun pidOfPidfd(pidfd: Int): Int =
        Files.readAllLines(Path.of("/proc/self/fdinfo/$pidfd"))
            .firstOrNull { it.startsWith("Pid:") }?.substringAfter(':')?.trim()?.toInt()
            ?: throw IOException("no Pid in fdinfo for pidfd $pidfd")

    /** True while the process the pidfd names is still alive. */
    fun pidfdAlive(pidfd: Int): Boolean =
        LibC.pidfdSendSignal(pidfd, 0) == 0L
}
