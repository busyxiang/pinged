package my.pinged.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.VisibleForTesting
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Produces the SQLCipher raw key. The stored blob is a 32-byte key plus a
 * 16-byte salt, AES-GCM wrapped by a non-exportable Keystore key, held in
 * getNoBackupFilesDir() so no backup path can carry it (spec 11.1).
 */
object DatabaseKey {
    private const val ALIAS = "pinged.db.wrap"
    private const val FILE = "db.key"

    /** [create]'s temporary, renamed over [FILE] once it is whole and on disk. */
    private const val PENDING = "db.key.new"
    private const val KEY_BYTES = 32
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** The shortest blob [unwrap] can split: an IV and a GCM tag around nothing. */
    private const val SHORTEST_BLOB = IV_BYTES + TAG_BITS / 8

    /**
     * **A missing key file is only safe to replace when there is no database to
     * open.** `if (file.exists()) unwrap(...) else create(file)` asks nothing about
     * the database, and the state it mishandles is reachable:
     * `getNoBackupFilesDir()` is excluded from backup and device transfer by design
     * (spec 11.1), so anything that clears `no_backup/` while `databases/` survives
     * arrives here with an intact, encrypted, perfectly readable database and no
     * key.
     *
     * Minting fresh key material there recovers nothing -- SQLCipher then fails to
     * decrypt page 1 -- and that failure is byte-for-byte the failure of a corrupt
     * file. Spec 11.1 requires the two to be handled differently: corruption offers
     * "export what still reads", a missing key offers "start fresh or restore", and
     * nothing must destroy the key that would have opened the file.
     */
    fun rawKeyPassphrase(
        context: Context,
        databaseName: String = DatabaseFactory.NAME,
    ): ByteArray {
        val file = File(context.noBackupFilesDir, FILE)
        val material = when {
            file.exists() -> unwrap(read(file))
            // The database this key is being asked for, not always the shipped
            // one. Hardcoding DatabaseFactory.NAME meant the guard asked "is
            // there a pinged.db?" while the caller was opening
            // migration-test.db -- so in the migration tests the safety check
            // was about an unrelated file.
            context.getDatabasePath(databaseName).exists() -> throw
                DatabaseKeyUnavailableException(
                    "key missing but database present: the wrapped key is gone from " +
                        "${file.absolutePath} while the database still exists at " +
                        "${context.getDatabasePath(databaseName).absolutePath}. " +
                        "Minting a new key would make an intact database permanently " +
                        "unopenable and indistinguishable from corruption. Offer the " +
                        "user a fresh start or a JSON restore (spec 11.1).",
                )
            else -> create(file)
        }
        return rawKeyPassphraseOf(material)
    }

    /**
     * [rawKeyPassphrase] for a database that already exists, and nothing else:
     * the key file unwrapped, or [DatabaseKeyUnavailableException].
     *
     * **Never creates a key**, which is the whole difference. The
     * check-then-create in [rawKeyPassphrase] is unsynchronised, and two opens
     * racing through it can each mint a key and leave the ledger under the one
     * that lost (`596e195`). `Databases.aside` opens beside [Databases.shared]
     * and uses this, so a second instance can only ever read the key the
     * first one made.
     */
    fun existingRawKeyPassphrase(context: Context): ByteArray {
        val file = File(context.noBackupFilesDir, FILE)
        if (!file.exists()) {
            throw DatabaseKeyUnavailableException(
                "No key file at ${file.absolutePath}, and a second instance does not " +
                    "mint one: only the first open of the database may.",
            )
        }
        return rawKeyPassphraseOf(unwrap(read(file)))
    }

    /**
     * Key material this app never wrapped, never wrote down and will not see
     * again, in the shape [rawKeyPassphrase] returns (a key plus a salt,
     * [KEY_BYTES] + [SALT_BYTES]): for a database that must never touch the
     * app's own key. `DatabaseFactory.buildAt` says why its probe must not.
     */
    fun randomRawKeyPassphrase(): ByteArray =
        rawKeyPassphraseOf(ByteArray(KEY_BYTES + SALT_BYTES).also { SecureRandom().nextBytes(it) })

    /**
     * SQLCipher treats an `x'...'` passphrase as a raw key and skips PBKDF2
     * entirely. At 256,000 default iterations that KDF would otherwise be
     * paid on every cold listener start (spec 15.2).
     *
     * Built straight into the ASCII bytes rather than through
     * `joinToString { "%02x".format(it) }`, which ran `String.format` once
     * per byte -- 48 `Formatter` allocations, 48 re-parses of the format
     * string and 48 boxed `Byte`s on the open path whose whole reason for
     * existing is that a ~2 ms open must not grow. It also left the raw key
     * material in an immutable `String` that nothing can zero.
     */
    private fun rawKeyPassphraseOf(material: ByteArray): ByteArray {
        val out = ByteArray(3 + material.size * 2)
        out[0] = 'x'.code.toByte()
        out[1] = '\''.code.toByte()
        material.forEachIndexed { i, byte ->
            val v = byte.toInt() and 0xFF
            out[2 + i * 2] = HEX[v ushr 4]
            out[3 + i * 2] = HEX[v and 0x0F]
        }
        out[out.size - 1] = '\''.code.toByte()
        return out
    }

    private val HEX = "0123456789abcdef".map { it.code.toByte() }.toByteArray()

    fun exists(context: Context): Boolean = File(context.noBackupFilesDir, FILE).exists()

    /**
     * Spec 11.3's "delete all data", key half **and** database half.
     *
     * Deleting only the key made this a generator of exactly the state
     * [rawKeyPassphrase] refuses: an encrypted database with no key, which nothing
     * can open again. There is no caller yet, so it was a trap rather than a live
     * bug -- but "delete all data" is the one path supposed to leave nothing
     * behind, and an unopenable file still counts against the storage figure spec
     * 15.6 promises to report honestly.
     *
     * The `-wal` and `-shm` companions are named explicitly per spec 11.3;
     * `Context.deleteDatabase` removes them, and they are re-checked below because
     * a WAL left beside a new database is its own corruption path.
     *
     * The order matters: database first, then key. Interrupted the other way round,
     * the process dies having deleted the key and left the database -- the
     * unrecoverable state. This way round the worst outcome is a keyed but empty
     * app with a stale Keystore alias, and the next call finishes the job.
     */
    fun destroy(context: Context) {
        context.deleteDatabase(DatabaseFactory.NAME)
        val db = context.getDatabasePath(DatabaseFactory.NAME)
        File(db.parentFile, "${DatabaseFactory.NAME}-wal").delete()
        File(db.parentFile, "${DatabaseFactory.NAME}-shm").delete()
        File(context.noBackupFilesDir, FILE).delete()
        File(context.noBackupFilesDir, PENDING).delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
    }

    /**
     * New key material, wrapped and written down.
     *
     * **A Keystore that will not mint or wrap is a
     * [DatabaseUnreadableException]**, whatever it threw: `generateKey`
     * raises `ProviderException`, a `RuntimeException`, and the rest of the
     * Keystore and `Cipher` calls `GeneralSecurityException`s, all outside
     * the family `CaptureStorage.guarded`, `SettingsViewModel.opened` and
     * `MainActivity`'s probe answer: let through, the first open after a
     * delete everything would kill the app. Unreadable rather than the key
     * gone: nothing has been written yet, so no ledger is behind it and the
     * next open is a first launch again, which tries once more. `DatabaseKeyTest` throws
     * through [beforeKeyMinted].
     */
    private fun create(file: File): ByteArray {
        val material = ByteArray(KEY_BYTES + SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val wrapped = try {
            beforeKeyMinted()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, wrappingKey())
            }
            cipher.iv + cipher.doFinal(material)
        } catch (thrown: Exception) {
            throw DatabaseUnreadableException(
                "Pinged could not make its database key: ${thrown.message ?: thrown.javaClass.simpleName}",
                thrown,
            )
        }
        writeDurably(file, wrapped)
        return material
    }

    /** Tests only: run where [create] asks the Keystore for its key. */
    @VisibleForTesting @Volatile internal var beforeKeyMinted: () -> Unit = {}

    /**
     * [bytes] at [file] whole or not at all, and on disk before this returns.
     *
     * **Not `file.writeBytes`**, which opens [file] truncated and writes in
     * place: a write that fails after the open -- a full disk -- or a process
     * killed between the two leaves a short `db.key`, and so does power lost
     * before writeback, with nothing fsynced: ext4 (this emulator's `/data`)
     * commits a new file's inode before its data, and a crash in between
     * leaves it zero bytes long. The database is written next, at
     * `synchronous = FULL`, so it survives the same crash -- an intact ledger
     * beside a key that holds nothing, which no one can open again and which
     * [rawKeyPassphrase] reports as the key gone. So the bytes go to
     * [PENDING], are fsynced, and are renamed over [file], which is atomic on
     * one filesystem; the directory is fsynced last so the rename itself
     * survives. A failure on the way leaves no `db.key` at all, which is a
     * first launch again, and is a [DatabaseUnreadableException] -- a full
     * disk is that family's own case. Paid once per install: a whole mint,
     * Keystore wrap and both fsyncs included, took 0.83-1.52 ms over fifteen
     * on emulator-5554 (`DatabaseKeyTest`).
     */
    private fun writeDurably(file: File, bytes: ByteArray) {
        val pending = File(file.parentFile, PENDING)
        try {
            FileOutputStream(pending).use { out ->
                afterKeyFileOpened()
                out.write(bytes)
                out.fd.sync()
            }
            if (!pending.renameTo(file)) throw IOException("could not rename ${pending.name} to ${file.name}")
            syncDirectory(file.parentFile!!)
        } catch (thrown: IOException) {
            pending.delete()
            throw DatabaseUnreadableException("Pinged could not write its database key: ${thrown.message}", thrown)
        }
    }

    private fun syncDirectory(directory: File) {
        try {
            val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        } catch (thrown: ErrnoException) {
            throw IOException("could not sync ${directory.path}", thrown)
        }
    }

    /**
     * Tests only: run once the key file being written is open and empty, as
     * a full disk or a killed process would leave it. `DatabaseKeyTest`
     * throws from here.
     */
    @VisibleForTesting @Volatile internal var afterKeyFileOpened: () -> Unit = {}

    /** The key file's bytes, or [DatabaseUnreadableException] for a storage error reading them. */
    private fun read(file: File): ByteArray = try {
        file.readBytes()
    } catch (thrown: IOException) {
        throw DatabaseUnreadableException("Pinged could not read its database key: ${thrown.message}", thrown)
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        // Before the split, which throws `IndexOutOfBoundsException` on a
        // blob shorter than the IV: outside the `DatabaseUnavailableException`
        // family, so it would pass `CaptureStorage.guarded` and
        // `SettingsViewModel.opened` both, and a zero-byte key would fail
        // every caller with an unhandled exception instead of naming the state.
        if (blob.size < SHORTEST_BLOB) {
            throw DatabaseKeyUnavailableException(
                "The database key file is ${blob.size} bytes, too short to hold a wrapped key " +
                    "($SHORTEST_BLOB at least): it was cut short, and the key it held is gone.",
            )
        }
        val iv = blob.copyOfRange(0, IV_BYTES)
        val body = blob.copyOfRange(IV_BYTES, blob.size)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(TAG_BITS, iv))
                doFinal(body)
            }
        } catch (e: Exception) {
            // Happens after a device-to-device transfer, and after an OTA that
            // invalidates the Keystore entry. The caller must tell the user and
            // offer a fresh start or a JSON restore. It must never crash-loop.
            throw DatabaseKeyUnavailableException(
                "The database key could not be unwrapped: ${e.javaClass.simpleName}"
            )
        }
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }
}
