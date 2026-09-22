package com.saatiril.andro.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.saatiril.andro.data.Student
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Manual XLSX / CSV importer for student lists — **no Apache POI**.
 *
 * An `.xlsx` file is just a ZIP archive containing XML files. We read it
 * directly with [ZipInputStream] and parse the relevant XML parts with the
 * Android built-in [XmlPullParser]:
 *
 *  - `xl/sharedStrings.xml` → the shared-strings table (`<si><t>…</t></si>`).
 *  - `xl/worksheets/sheet1.xml` → the cell matrix (`<c r="A1" t="s"><v>0</v></c>`).
 *
 * CSV files are read as text and split by the auto-detected delimiter
 * (`,` or `;`). Quoted fields with embedded delimiters are supported.
 *
 * Header detection looks for `"nim"/"nis"/"npm"/"nip"` and
 * `"nama"/"name"` (case-insensitive). If no header is found, the file is
 * assumed to have column A = NIM and column B = Nama.
 *
 * The result is a list of [Student]s with `status = "pending"`,
 * `assignedChannel = 1` and a fresh UUID `id`.
 */
object ExcelImporter {

    private const val TAG = "ExcelImporter"

    /** Keywords (lower-cased, trimmed) that mark the NIM column in a header row. */
    private val NIM_KEYWORDS = setOf("nim", "nis", "npm", "nip", "nobp", "no", "no. mhs", "id")

    /** Keywords (lower-cased, trimmed) that mark the Nama column in a header row. */
    private val NAMA_KEYWORDS = setOf("nama", "name", "nama lengkap", "full name", "student name", "nama mahasiswa")

    /**
     * Import students from a `.xlsx` (or `.csv`) file at the given content [uri].
     *
     * Detection:
     *  - File extension from [OpenableColumns.DISPLAY_NAME] takes priority.
     *  - Falls back to MIME-type sniffing ("spreadsheet"/"excel" → XLSX).
     *  - Default: CSV.
     *
     * @return list of [Student]s (status = "pending", assignedChannel = 1, fresh UUID).
     * @throws IOException on any parse / read error.
     */
    fun import(context: Context, uri: Uri): List<Student> {
        val filename = queryDisplayName(context, uri).orEmpty().lowercase()
        return try {
            when {
                filename.endsWith(".csv") -> importCsv(context, uri)
                filename.endsWith(".xlsx") || filename.endsWith(".xlsm") ->
                    importXlsx(context, uri)

                else -> {
                    val mime = context.contentResolver.getType(uri).orEmpty()
                    if (mime.contains("spreadsheet") || mime.contains("excel") ||
                        mime.contains("officedocument.spreadsheetml")
                    ) {
                        importXlsx(context, uri)
                    } else {
                        // Default: assume CSV for unknown extensions / no extension.
                        importCsv(context, uri)
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Import failed for uri=$uri — ${e.message}")
            throw e
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  CSV
    // ──────────────────────────────────────────────────────────────────

    private fun importCsv(context: Context, uri: Uri): List<Student> {
        val text = context.contentResolver.openInputStream(uri)?.use { readText(it) }
            ?: throw IOException("Cannot open input stream for URI: $uri")

        // Strip UTF-8 BOM if present.
        val clean = if (text.startsWith("\uFEFF")) text.substring(1) else text
        val lines = clean.split("\r\n", "\n", "\r").filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        val delimiter = detectCsvDelimiter(lines.first())
        val rows = lines.map { splitCsvLine(it, delimiter) }
        return parseRows(rows)
    }

    /** Pick `,` or `;` — whichever occurs more often in the first line. */
    private fun detectCsvDelimiter(sample: String): Char {
        val semis = sample.count { it == ';' }
        val commas = sample.count { it == ',' }
        return if (semis > commas) ';' else ','
    }

    /** Split a CSV line honoring `"quoted,values"` with embedded delimiters and `""` escapes. */
    private fun splitCsvLine(line: String, delimiter: Char): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"'); i++ // escaped quote
                    } else inQuotes = !inQuotes
                }
                c == delimiter && !inQuotes -> {
                    out.add(sb.toString().trim()); sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString().trim())
        return out
    }

    // ──────────────────────────────────────────────────────────────────
    //  XLSX
    // ──────────────────────────────────────────────────────────────────

    private fun importXlsx(context: Context, uri: Uri): List<Student> {
        val sharedStrings = mutableListOf<String>()
        var sheetXml: String? = null

        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        name == "xl/sharedStrings.xml" -> {
                            sharedStrings.addAll(parseSharedStrings(zip))
                        }
                        // Prefer sheet1.xml; fall back to the first worksheet we see.
                        name == "xl/worksheets/sheet1.xml" -> {
                            sheetXml = readText(zip)
                        }
                        sheetXml == null && name.startsWith("xl/worksheets/sheet") &&
                                name.endsWith(".xml") -> {
                            sheetXml = readText(zip)
                        }
                    }
                    try { zip.closeEntry() } catch (_: IOException) {}
                    entry = zip.nextEntry
                }
            }
        } ?: throw IOException("Cannot open input stream for URI: $uri")

        if (sheetXml == null) {
            Log.w(TAG, "XLSX contains no worksheet — returning empty list")
            return emptyList()
        }

        val rowMaps = parseSheet(sheetXml!!, sharedStrings)
        // Convert each row's (colIndex → value) map into an ordered list of strings.
        val tableRows = rowMaps.map { rowMap ->
            if (rowMap.isEmpty()) {
                emptyList()
            } else {
                val maxCol = rowMap.keys.maxOrNull() ?: 0
                (0..maxCol).map { rowMap[it].orEmpty() }
            }
        }
        return parseRows(tableRows)
    }

    /** Parse `<si>` entries from `xl/sharedStrings.xml` into a flat list of strings. */
    @Throws(IOException::class)
    private fun parseSharedStrings(input: InputStream): List<String> {
        val out = mutableListOf<String>()
        try {
            val parser = newPullParser()
            parser.setInput(input, "UTF-8")
            var event = parser.eventType
            var insideSi = false
            val sb = StringBuilder()
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "si" -> { insideSi = true; sb.setLength(0) }
                            "t" -> if (insideSi) sb.append(parser.nextText())
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "si") {
                            out.add(sb.toString())
                            insideSi = false
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: XmlPullParserException) {
            throw IOException("Failed to parse sharedStrings.xml", e)
        }
        return out
    }

    /**
     * Parse `xl/worksheets/sheetN.xml` into a list of rows (indexed by row number 0-based),
     * each row a map of (colIndex → cell value).
     */
    @Throws(IOException::class)
    private fun parseSheet(xml: String, sharedStrings: List<String>): List<MutableMap<Int, String>> {
        val rows = mutableListOf<MutableMap<Int, String>>()
        try {
            val parser = newPullParser()
            parser.setInput(xml.reader())
            var event = parser.eventType
            var currentRow = -1
            var currentCol = -1
            var currentType: String? = null   // "s" | "n" | "str" | "inlineStr" | null
            var currentVal: String? = null
            var insideIs = false
            var inlineStrText: String? = null

            fun push() {
                if (currentRow < 0 || currentCol < 0) return
                while (rows.size <= currentRow) rows.add(mutableMapOf())
                val value: String = when (currentType) {
                    "s" -> {
                        val idx = currentVal?.trim()?.toIntOrNull() ?: -1
                        if (idx in sharedStrings.indices) sharedStrings[idx] else ""
                    }
                    "inlineStr" -> inlineStrText.orEmpty()
                    else -> currentVal.orEmpty()   // number or formula-string
                }
                rows[currentRow][currentCol] = value
                currentVal = null
                inlineStrText = null
            }

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "c" -> {
                                val r = parser.getAttributeValue(null, "r").orEmpty()
                                val (col, row) = parseCellRef(r)
                                currentCol = col
                                currentRow = row
                                currentType = parser.getAttributeValue(null, "t")
                                currentVal = null
                                inlineStrText = null
                            }
                            "v" -> {
                                currentVal = parser.nextText()
                            }
                            "is" -> { insideIs = true; inlineStrText = null }
                            "t" -> {
                                if (insideIs) {
                                    inlineStrText = (inlineStrText ?: "") + parser.nextText()
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "is" -> insideIs = false
                            "c" -> push()
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: XmlPullParserException) {
            throw IOException("Failed to parse sheet XML", e)
        }
        return rows
    }

    /** "A1" → (col=0, row=0); "AA12" → (col=26, row=11). Blank → (-1, -1). */
    private fun parseCellRef(ref: String): Pair<Int, Int> {
        if (ref.isBlank()) return -1 to -1
        var i = 0
        var col = 0
        while (i < ref.length && ref[i].isLetter()) {
            col = col * 26 + (ref[i].uppercaseChar().code - 'A'.code + 1)
            i++
        }
        val colIdx = col - 1
        val rowIdx = (ref.substring(i).toIntOrNull() ?: 1) - 1
        return colIdx to rowIdx
    }

    // ──────────────────────────────────────────────────────────────────
    //  Shared row → Student conversion
    // ──────────────────────────────────────────────────────────────────

    /**
     * Convert a 2-D table of strings (already trimmed per cell) into a list of [Student]s.
     *
     * Performs header detection on the first non-empty row. If a header is detected,
     * the NIM and Nama columns are taken from it; otherwise column A = NIM, column B = Nama
     * and the first row is treated as data.
     */
    private fun parseRows(rows: List<List<String>>): List<Student> {
        if (rows.isEmpty()) return emptyList()

        val firstNonEmpty = rows.indexOfFirst { it.any { c -> c.isNotBlank() } }
        if (firstNonEmpty < 0) return emptyList()

        val header = rows[firstNonEmpty].map { it.trim().lowercase() }
        val detectedNim = header.indexOfFirst { NIM_KEYWORDS.contains(it) }
        val detectedNama = header.indexOfFirst { NAMA_KEYWORDS.contains(it) }

        val (nimCol, namaCol, startIdx) = when {
            detectedNim >= 0 && detectedNama >= 0 ->
                Triple(detectedNim, detectedNama, firstNonEmpty + 1)
            detectedNim >= 0 ->
                Triple(detectedNim, if (detectedNim == 0) 1 else 0, firstNonEmpty + 1)
            detectedNama >= 0 ->
                Triple(if (detectedNama == 0) 1 else 0, detectedNama, firstNonEmpty + 1)
            else ->
                // No header detected: assume col A=NIM, col B=Nama, first row is data.
                Triple(0, 1, firstNonEmpty)
        }

        val students = mutableListOf<Student>()
        for (r in startIdx until rows.size) {
            val row = rows[r]
            val nim = row.getOrNull(nimCol)?.trim().orEmpty()
            val nama = row.getOrNull(namaCol)?.trim().orEmpty()
            if (nim.isBlank() && nama.isBlank()) continue
            students.add(
                Student(
                    id = UUID.randomUUID().toString(),
                    nim = nim,
                    nama = nama,
                    status = "pending",
                    assignedChannel = 1
                )
            )
        }
        return students
    }

    // ──────────────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────────────

    private fun newPullParser(): XmlPullParser =
        XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()

    private fun readText(input: InputStream): String {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            baos.write(buf, 0, n)
        }
        return baos.toString("UTF-8")
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) {
            null
        }
    }
}
