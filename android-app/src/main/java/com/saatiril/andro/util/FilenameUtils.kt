package com.saatiril.andro.util

/**
 * Filename building utilities for Saatiril photo captures.
 * Matches the Windows/Electron naming convention:
 *
 * Standard mode (single/dual):
 *   v1 → NIM_Nama_1_Toga.jpg + NIM_Nama_2_Ijazah.jpg
 *   v2 → NIM_Nama_1_Toga_v2.jpg + NIM_Nama_2_Ijazah_v2.jpg
 *
 * Photoshoot mode:
 *   v1 → NIM_Nama.jpg (channel 1) or NIM_Nama_Ch2.jpg (channel 2+)
 *   v2 → NIM_Nama_v2.jpg or NIM_Nama_Ch2_v2.jpg
 */
object FilenameUtils {

    /** Max length of the sanitized nama part (keeps filenames sane for long names). */
    private const val MAX_NAMA_LENGTH = 40

    /**
     * Sanitize nama (name) for use in filenames.
     * Replaces spaces with underscores, removes non-alphanumeric characters.
     * Capped at [MAX_NAMA_LENGTH] characters.
     */
    private fun sanitizeNama(nama: String?): String {
        return (nama ?: "").trim()
            .replace("\\s+".toRegex(), "_")
            .replace("[^a-zA-Z0-9_]".toRegex(), "")
            .take(MAX_NAMA_LENGTH)
    }

    /**
     * Sanitize NIM (student ID) for use in filenames.
     * Keeps alphanumeric, underscore, and hyphen characters.
     */
    private fun sanitizeNim(nim: String?): String {
        return (nim ?: "").toString().trim()
            .replace("[^a-zA-Z0-9_-]".toRegex(), "")
    }

    /**
     * Build the "NIM_Nama" identity part of a filename.
     *
     * Edge cases (empty/malformed Excel rows):
     *  - nama empty, nim present   → "NIM"     (no dangling "_")
     *  - nim empty, nama present   → "Nama"    (no leading "_")
     *  - both empty → fall back to [studentId] (first 8 chars) if provided,
     *    else a short unique id derived from the clock. Without this, every
     *    such student produced filenames like "___1_Toga.jpg" that collided.
     */
    private fun buildIdentityPart(nim: String?, nama: String?, studentId: String?): String {
        val nimPart = sanitizeNim(nim)
        val namaPart = sanitizeNama(nama)
        if (nimPart.isNotEmpty() && namaPart.isNotEmpty()) return "${nimPart}_${namaPart}"
        if (nimPart.isNotEmpty()) return nimPart
        if (namaPart.isNotEmpty()) return namaPart
        val idPart = sanitizeNim(studentId).take(8)
        return if (idPart.isNotEmpty()) idPart
        else java.lang.Long.toHexString(System.currentTimeMillis()).take(8)
    }

    /**
     * Build a versioned filename for standard mode (Toga + Ijazah).
     *
     * @param nim Student NIM
     * @param nama Student name
     * @param suffix Photo number (1 = Toga, 2 = Ijazah)
     * @param type Photo type label ("Toga" or "Ijazah")
     * @param version Capture version (1 = first, 2+ = retake after MC reset)
     * @param studentId Optional stable student id used as filename fallback
     *   when both NIM and nama sanitize to empty (first 8 chars are used)
     * @return Filename like "NIM_Nama_1_Toga.jpg" or "NIM_Nama_1_Toga_v2.jpg"
     */
    fun buildStandardFilename(
        nim: String?,
        nama: String?,
        suffix: Int,
        type: String,
        version: Int = 1,
        studentId: String? = null
    ): String {
        val base = "${buildIdentityPart(nim, nama, studentId)}_${suffix}_${type}"
        return if (version > 1) "${base}_v${version}.jpg" else "${base}.jpg"
    }

    /**
     * Build a versioned filename for photoshoot mode.
     *
     * @param nim Student NIM
     * @param nama Student name
     * @param channel Camera channel (1 = no suffix, 2+ = Ch2, Ch3, etc.)
     * @param version Capture version (1 = first, 2+ = retake after MC reset)
     * @param studentId Optional stable student id used as filename fallback
     *   when both NIM and nama sanitize to empty (first 8 chars are used)
     * @return Filename like "NIM_Nama.jpg" or "NIM_Nama_Ch2_v2.jpg"
     */
    fun buildPhotoshootFilename(
        nim: String?,
        nama: String?,
        channel: Int,
        version: Int = 1,
        studentId: String? = null
    ): String {
        val base = buildIdentityPart(nim, nama, studentId)
        val withCh = if (channel > 1) "${base}_Ch${channel}" else base
        return if (version > 1) "${withCh}_v${version}.jpg" else "${withCh}.jpg"
    }
}
