package io.github.olbartek.agentctl.cli

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

/** Files only their owner may read (mode 0600): the launch state, which holds the launch's token, and the CLI's logs. */
internal object PrivateFiles {
    private val OWNER_ONLY = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    private val random = SecureRandom()

    /**
     * Writes [bytes] to [file] whole or not at all: to a new temporary file beside it, with a random name, created with
     * mode 0600 and written through the channel that created it, then moved over [file].
     */
    @Throws(IOException::class)
    fun writeAtomically(file: Path, bytes: ByteArray) {
        val (temporary, channel) = createUnique(file)
        try {
            channel.use { out ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) out.write(buffer)
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** A new file beside [file] that did not exist before (`CREATE_NEW`), open for writing. */
    private fun createUnique(file: Path): Pair<Path, SeekableByteChannel> {
        repeat(100) {
            val suffix = ByteArray(8).also(random::nextBytes).joinToString("") { byte -> HEX[(byte.toInt() shr 4) and 0xf].toString() + HEX[byte.toInt() and 0xf] }
            val temporary = file.resolveSibling("${file.fileName}.$suffix.tmp")
            try {
                return temporary to create(temporary)
            } catch (_: FileAlreadyExistsException) {
                // Another's: try another name.
            }
        }
        throw IOException("cannot create a temporary file beside $file")
    }

    /** Creates [path] (it must not exist) with mode 0600, open for writing. */
    @Throws(IOException::class)
    fun create(path: Path): SeekableByteChannel {
        val options = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        return try {
            Files.newByteChannel(path, options, OWNER_ONLY)
        } catch (_: UnsupportedOperationException) {
            // A file system without POSIX permissions (Windows): its own access rules apply.
            Files.newByteChannel(path, options)
        }
    }

    /**
     * Makes [log] a file only its owner may read before a command's output goes to it: created with mode 0600 if it is
     * not there, its mode set to 0600 if it is.
     */
    fun prepareLog(log: File) {
        try {
            log.parentFile?.mkdirs()
            val path = log.toPath()
            try {
                create(path).close()
            } catch (_: FileAlreadyExistsException) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
            }
        } catch (_: UnsupportedOperationException) {
            // No POSIX permissions here.
        } catch (_: IOException) {
            // The command's own redirect reports a log it cannot write.
        }
    }

    private const val HEX = "0123456789abcdef"
}
