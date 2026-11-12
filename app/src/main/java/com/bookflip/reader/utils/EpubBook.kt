package com.bookflip.reader.utils

import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * নিজস্ব হালকা EPUB পার্সার (epublib ছাড়া)।
 * container.xml → OPF → manifest/spine/metadata/cover/TOC।
 */
class EpubBook private constructor(
    private val zip: ZipFile,
    val title: String,
    val author: String,
    private val spinePaths: List<String>,
    private val mimeByPath: Map<String, String>,
    private val coverPath: String?,
    val toc: List<TocEntry>
) : Closeable {

    data class TocEntry(val title: String, val spineIndex: Int, val fragment: String?)

    private val entryNames: Map<String, String> by lazy {
        // lower-case → আসল নাম (কিছু EPUB-এ href-এর কেস মেলে না)
        val m = HashMap<String, String>()
        val en = zip.entries()
        while (en.hasMoreElements()) {
            val e = en.nextElement()
            m[e.name.lowercase()] = e.name
        }
        m
    }

    val chapterCount: Int get() = spinePaths.size

    fun chapterPath(index: Int): String? = spinePaths.getOrNull(index)

    fun spineIndexOf(path: String): Int = spinePaths.indexOf(path)

    fun mimeOf(path: String): String {
        mimeByPath[path]?.let { return normalizeMime(it) }
        return when (path.substringAfterLast('.', "").lowercase()) {
            "xhtml", "html", "htm" -> "text/html"
            "css" -> "text/css"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "js" -> "application/javascript"
            "xml", "ncx" -> "text/xml"
            else -> "application/octet-stream"
        }
    }

    private fun normalizeMime(m: String) = if (m == "application/xhtml+xml") "text/html" else m

    fun open(path: String): InputStream? {
        val name = if (zip.getEntry(path) != null) path else entryNames[path.lowercase()] ?: return null
        val entry = zip.getEntry(name) ?: return null
        return zip.getInputStream(entry)
    }

    fun readBytes(path: String): ByteArray? = try { open(path)?.use { it.readBytes() } } catch (e: Exception) { null }

    fun coverBytes(): ByteArray? = coverPath?.let { readBytes(it) }

    override fun close() { try { zip.close() } catch (_: Exception) {} }

    companion object {

        /** পার্স করে EpubBook ফেরত দেয়; ব্যর্থ হলে null */
        fun open(file: File): EpubBook? {
            var zip: ZipFile? = null
            return try {
                zip = ZipFile(file)
                parse(zip)
            } catch (e: Exception) {
                try { zip?.close() } catch (_: Exception) {}
                null
            }
        }

        private fun dirOf(path: String) = if (path.contains('/')) path.substringBeforeLast('/') + "/" else ""

        private fun resolve(base: String, href: String): String {
            val clean = Uri.decode(href.substringBefore('#').substringBefore('?'))
            val parts = ArrayList<String>()
            for (seg in (base + clean).split('/')) {
                when (seg) {
                    "", "." -> {}
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts.add(seg)
                }
            }
            return parts.joinToString("/")
        }

        private fun parse(zip: ZipFile): EpubBook? {
            // 1) container.xml
            val containerEntry = zip.getEntry("META-INF/container.xml") ?: return null
            var opfPath: String? = null
            zip.getInputStream(containerEntry).use { ins ->
                val p = Xml.newPullParser().apply { setInput(ins, null) }
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "rootfile") {
                        opfPath = p.getAttributeValue(null, "full-path"); break
                    }
                }
            }
            val opf = opfPath ?: return null
            val opfEntry = zip.getEntry(opf) ?: return null
            val base = dirOf(opf)

            // 2) OPF
            var title = ""
            var author = ""
            val manifestHref = LinkedHashMap<String, String>()      // id -> path
            val manifestMime = HashMap<String, String>()            // path -> mime
            val manifestProps = HashMap<String, String>()           // id -> properties
            val spineIds = ArrayList<String>()
            var coverId: String? = null
            var ncxId: String? = null

            zip.getInputStream(opfEntry).use { ins ->
                val p = Xml.newPullParser().apply { setInput(ins, null) }
                var inTitle = false
                var inCreator = false
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    when (p.eventType) {
                        XmlPullParser.START_TAG -> when (p.name) {
                            "title" -> if (title.isEmpty()) inTitle = true
                            "creator" -> if (author.isEmpty()) inCreator = true
                            "meta" -> if (p.getAttributeValue(null, "name") == "cover")
                                coverId = p.getAttributeValue(null, "content")
                            "item" -> {
                                val id = p.getAttributeValue(null, "id") ?: continue
                                val href = p.getAttributeValue(null, "href") ?: continue
                                val path = resolve(base, href)
                                manifestHref[id] = path
                                p.getAttributeValue(null, "media-type")?.let { manifestMime[path] = it }
                                p.getAttributeValue(null, "properties")?.let { manifestProps[id] = it }
                            }
                            "spine" -> ncxId = p.getAttributeValue(null, "toc")
                            "itemref" -> {
                                val linear = p.getAttributeValue(null, "linear")
                                if (linear != "no") p.getAttributeValue(null, "idref")?.let { spineIds.add(it) }
                            }
                        }
                        XmlPullParser.TEXT -> {
                            if (inTitle) title += p.text
                            if (inCreator) author += p.text
                        }
                        XmlPullParser.END_TAG -> when (p.name) {
                            "title" -> inTitle = false
                            "creator" -> inCreator = false
                        }
                    }
                }
            }

            val spinePaths = spineIds.mapNotNull { manifestHref[it] }
            if (spinePaths.isEmpty()) return null

            // 3) cover
            val coverPath = manifestProps.entries.firstOrNull { it.value.contains("cover-image") }?.key?.let { manifestHref[it] }
                ?: coverId?.let { manifestHref[it] }
                ?: manifestHref.entries.firstOrNull { (id, path) ->
                    (id.contains("cover", true) || path.contains("cover", true)) &&
                        (manifestMime[path]?.startsWith("image/") == true)
                }?.value

            // 4) TOC (EPUB3 nav → না থাকলে NCX)
            val navId = manifestProps.entries.firstOrNull { it.value.split(' ').contains("nav") }?.key
            val toc = ArrayList<TocEntry>()
            try {
                if (navId != null) parseNav(zip, manifestHref[navId]!!, spinePaths, toc)
                if (toc.isEmpty()) {
                    val ncxPath = (ncxId?.let { manifestHref[it] })
                        ?: manifestMime.entries.firstOrNull { it.value == "application/x-dtbncx+xml" }?.key
                    if (ncxPath != null) parseNcx(zip, ncxPath, spinePaths, toc)
                }
            } catch (_: Exception) { /* TOC ঐচ্ছিক */ }

            return EpubBook(
                zip, title.trim(), author.trim(), spinePaths, manifestMime, coverPath,
                toc
            )
        }

        private fun addToc(list: MutableList<TocEntry>, title: String, href: String, navDir: String, spine: List<String>) {
            val path = resolve(navDir, href)
            val idx = spine.indexOf(path)
            if (idx >= 0 && title.isNotBlank()) {
                list.add(TocEntry(title.trim().replace(Regex("\\s+"), " "), idx, href.substringAfter('#', "").ifEmpty { null }))
            }
        }

        private fun parseNav(zip: ZipFile, navPath: String, spine: List<String>, out: MutableList<TocEntry>) {
            val entry = zip.getEntry(navPath) ?: return
            val dir = dirOf(navPath)
            zip.getInputStream(entry).use { ins ->
                val p = Xml.newPullParser().apply {
                    setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                    setInput(ins, null)
                }
                var inTocNav = false
                var href: String? = null
                val text = StringBuilder()
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    when (p.eventType) {
                        XmlPullParser.START_TAG -> when (p.name) {
                            "nav" -> {
                                val type = p.getAttributeValue(null, "epub:type") ?: p.getAttributeValue(null, "type") ?: ""
                                if (type.contains("toc")) inTocNav = true
                            }
                            "a" -> if (inTocNav) { href = p.getAttributeValue(null, "href"); text.setLength(0) }
                        }
                        XmlPullParser.TEXT -> if (inTocNav && href != null) text.append(p.text)
                        XmlPullParser.END_TAG -> when (p.name) {
                            "a" -> if (inTocNav && href != null) { addToc(out, text.toString(), href!!, dir, spine); href = null }
                            "nav" -> if (inTocNav) return
                        }
                    }
                }
            }
        }

        private fun parseNcx(zip: ZipFile, ncxPath: String, spine: List<String>, out: MutableList<TocEntry>) {
            val entry = zip.getEntry(ncxPath) ?: return
            val dir = dirOf(ncxPath)
            zip.getInputStream(entry).use { ins ->
                val p = Xml.newPullParser().apply { setInput(ins, null) }
                var label = ""
                var inText = false
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    when (p.eventType) {
                        XmlPullParser.START_TAG -> when (p.name) {
                            "navPoint" -> label = ""
                            "text" -> { inText = true; if (label.isEmpty()) label = "" }
                            "content" -> {
                                val src = p.getAttributeValue(null, "src")
                                if (src != null) addToc(out, label, src, dir, spine)
                            }
                        }
                        XmlPullParser.TEXT -> if (inText && label.isEmpty()) label = p.text
                        XmlPullParser.END_TAG -> if (p.name == "text") inText = false
                    }
                }
            }
        }
    }
}
