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

/** The radare2 reference shown by function 28. */
object R2Reference {
    val TEXT = """
HOW THE OUTPUT IS ARRANGED
  Address      where the code or data is, e.g. 0x00401000
  Bytes        the raw machine bytes, e.g. 55 48 89 e5
  Instruction  what the processor does, e.g. push rbp
  HINT: lines   warnings only. Most are harmless.
  done (exit 0) means the command finished.

WHAT YOU TYPE IN THE BOX
  28             this reference
  27             block diagram of the code flow
  0x00401000     analyse that address: function, bytes, code, callers
  find WORD      search the file for WORD
  only WORD      show only the last output's lines that contain WORD
  range S E      show only the last output's lines between addresses S and E
  any r2 command sent to radare2, e.g.  iI   or   afl~main
  ;              joins commands:  s 0x401000;pd 10
  ~              filters inside radare2:  afl~main  keeps functions with main in the name
  @              runs a command at an address:  px 16 @ 0x401000
  s ADDRESS      moves the cursor to ADDRESS; the next commands start there

FILE INFO (use these first)
  i      summary: CPU, bits, operating system, stripped or not
         example:  i   ->  arch arm   bits 64   stripped false
  ie     the entry point, where the program starts
         example:  ie  ->  vaddr 0x00401000
  iS     sections: .text is code, .data is variables, .rodata is constants
  il     libraries it needs at runtime
  ii     imports: functions it calls from outside (open, connect, crypto).
         These show what the program does.
  iE     exports: functions it offers to other programs
  is     symbols: names of functions. Few names = stripped (run aaa).
  iz     strings in the data sections; izz searches the whole file (slower)

ANALYSIS
  aaa    analyse everything: find functions and their calls. Run it first on
         any stripped file. Big files take a while.
  afl    list all functions: address, size, name
         example line:  0x00401000  1  42  entry0
  afi    details of the function at the cursor: size, blocks, calls
  axt    who points to the address at the cursor (cross-references)
         example:  s 0x00401010;axt
  agf    text flow chart of the function at the cursor
  agfj   the same flow as JSON. Function 27 reads this.

READ CODE
  pdf    disassemble the whole function at the cursor
  pd N   disassemble N instructions from the cursor
         example:  s 0x00401000;pd 12
  px N   hex dump of N bytes, with text on the side
         example:  px 32 @ 0x00401000

HOW TO READ ONE CODE LINE
  0x00401004   3   48 89 e5   mov rbp, rsp
  address      size  bytes      instruction
  jmp, je, jne, jz, jnz, jl, jg ...   a jump. The last address on the line is where it goes.
  call          runs another function
  ret           returns to the caller
  colours:  pink = loop back   blue = forward jump   green = short jump in the same block
            orange = call      teal = return
  tap any address to see the 24 instructions starting there

OFFSET AND ADDRESS
  Both are positions shown in hex. In ELF and PE files used here they are usually the same
  numbers. Use the address exactly as the output shows it.

SEARCH
  / text         search for text, e.g.  / license
  /x 4d5a        search for hex bytes; 4d5a is the start of a Windows program (PE)
  /x 7f454c46    the start of a Linux/Android program (ELF)

SAVE AND CONTROL
  save     writes the console to a file    clear   empties the screen
  stop     cancels the running command     help    shows the short shortcut list

EXAMPLE WORKFLOWS
  1. Unknown file:      1 (info), 2 (summary), 9 (strings)
  2. Find a feature:    find http -> note an address -> paste it in the box -> RUN
  3. Understand code:   11 (analyse), 12 (list functions), paste an address, read it
  4. Follow the flow:   27 (block diagram), then tap an address to read that block
  5. Check a patch:     25 (patch a copy), then  s ADDRESS;px 16  to see the new bytes

NOT AVAILABLE YET
  Live tracing while the program runs (needs root or a debugger on the phone).
  Decoding resources (strings in resources.arsc) and turning classes.dex into smali.
""".trimIndent()
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
