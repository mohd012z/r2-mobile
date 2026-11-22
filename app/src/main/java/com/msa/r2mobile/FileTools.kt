package com.msa.r2mobile

import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password encryption with AES-256-GCM (standard Java crypto, no extra library).
 * File layout: MAGIC (6 bytes) | salt (16) | iv (12) | ciphertext with a 16-byte tag.
 * A wrong password or a changed file fails the tag check and is reported as an error.
 */
object FileCrypto {
    private val MAGIC = "R2ENC1".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 100_000
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private val rng = SecureRandom()

    fun encrypt(input: File, output: File, password: CharArray) {
        val salt = ByteArray(SALT_LEN).also { rng.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
        output.outputStream().use { out ->
            out.write(MAGIC)
            out.write(salt)
            out.write(iv)
            CipherOutputStream(out, cipher).use { cos -> input.inputStream().use { it.copyTo(cos) } }
        }
    }

    fun decrypt(input: File, output: File, password: CharArray) {
        input.inputStream().use { raw ->
            val ins = DataInputStream(raw)
            val header = ByteArray(MAGIC.size + SALT_LEN + IV_LEN)
            ins.readFully(header)
            require(header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "not an R2 encrypted file" }
            val salt = header.copyOfRange(MAGIC.size, MAGIC.size + SALT_LEN)
            val iv = header.copyOfRange(MAGIC.size + SALT_LEN, header.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
            output.outputStream().use { out -> CipherInputStream(ins, cipher).use { it.copyTo(out) } }
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }
}

/** Writes bytes into a COPY of a file. The input file is never changed. */
object BytePatch {
    /** Returns the bytes that were at [offset] before the change. */
    fun patch(input: File, output: File, offset: Long, newBytes: ByteArray): ByteArray {
        require(offset >= 0 && offset + newBytes.size <= input.length()) {
            "offset ${"0x%x".format(offset)} with ${newBytes.size} bytes is outside the file (${input.length()} bytes)"
        }
        input.copyTo(output, overwrite = true)
        val before = ByteArray(newBytes.size)
        RandomAccessFile(output, "rw").use { raf ->
            raf.seek(offset)
            raf.readFully(before)
            raf.seek(offset)
            raf.write(newBytes)
        }
        return before
    }

    /** Parses "90 90", "9090" or "0x9090" into bytes. Throws if the text is not hex pairs. */
    fun parseHex(text: String): ByteArray {
        val clean = text.trim().removePrefix("0x").removePrefix("0X").replace(" ", "")
        require(clean.isNotEmpty() && clean.length % 2 == 0 && clean.all { it.lowercaseChar() in "0123456789abcdef" }) {
            "bytes must be hex pairs, e.g. 1f 20 03 d5"
        }
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun parseOffset(text: String): Long {
        val clean = text.trim().removePrefix("0x").removePrefix("0X")
        return clean.toLongOrNull(16) ?: throw IllegalArgumentException("offset must be hex, e.g. 0x1a40")
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
}

/** The step-by-step guide shown by function 26. */
object Guide {
    val PATCHING = """
STEP 1  Know what you want to change.
  Use find WORD to search, e.g.  find version  or  find http
  The output shows the address of each match.

STEP 2  Look at the bytes at that address.
  Type  s ADDRESS;px 32  (use the address from step 1).
  Tap any address in the output to see the code there.

STEP 3  Patch a copy.
  Tap FUNCTIONS, pick 25, then Run. Type the offset (hex) and the new bytes (hex).
  The app writes NAME-patched.EXT in your app folder. Your original is not changed.
  The console shows the bytes before and after.

STEP 4  Check the result.
  Run 1 or 2 on the patched copy, or search for the new bytes with  /x 1f2003d5.
  The copy becomes the target, so you can patch again. Each patch stacks on the last.

EXAMPLES (ARM64)
  1f 20 03 d5   NOP: does nothing (useful for skipping one instruction)
  c0 03 5f d6   RET: returns from the function immediately

APK FILES
  An APK is a ZIP. Compressed entries (classes.dex, resources) cannot be patched in place.
  1. Run 21 to extract classes.dex.
  2. Patch classes.dex with 25. Its offsets are inside the extracted file.
  3. Put it back in the APK, then SIGN the APK. Android refuses unsigned or changed APKs.
     This app does not repack or sign yet. Use a signing tool on your computer.

ENCRYPTING
  23 locks the loaded file with a password. The output is NAME.r2enc.
  24 unlocks it with the same password. If the password is lost, the file cannot be opened.
  The password is never stored by the app.

SAVING
  Outputs are saved in your app folder, in Android > data > com.msa.r2mobile > files.
  Use Save in the app to keep the console text.
""".trimIndent()
}
