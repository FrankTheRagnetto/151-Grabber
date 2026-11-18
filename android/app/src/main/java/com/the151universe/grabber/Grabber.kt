package com.the151universe.grabber

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Il cuore senza Android (gira anche nella prova sul PC). Stessa logica di
 * grabber.py: immagini nell'ordine della pagina, la versione grande se c'è,
 * con la foto mostrata come riserva; via icone, svg, doppioni.
 */
/** riprova=true: il sito ha inciampato (rete, 429 «rallenta», 5xx); attesaMs = Retry-After del sito.
 *  morta=true: il sito dice che non esiste più (404/410): riprovare non serve. */
class GrabError(msg: String, val riprova: Boolean = false, val attesaMs: Long? = null, val morta: Boolean = false) : Exception(msg)

class Risultato(val impronte: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())) {
    var trovate = 0
    var salvate = 0
    var piccole = 0
    var doppie = 0
    var strisce = 0                        // strisce di fondo cucite alla loro pagina
    val fallite = mutableListOf<Pair<String, String>>()
    var annullato = false
    var pagineFatte = 0                    // solo «più pagine»
    val pagineVuote = mutableListOf<String>()
    var arredo = 0                         // foto ripetute su più pagine, lasciate fuori
    val pagineFallite = mutableListOf<String>()   // pagine che il sito non ha dato
    val mancanti = mutableListOf<Mancante>()       // cosa riprovare col bottone
    var arredoSet: Set<String> = emptySet()        // contesto per riprovare
    var sfogliate = 0                              // pagine passate con la freccia «avanti» (prima compresa)
    var fermata = ""                               // perché lo sfoglio si è fermato prima della fine
    var rallentato = false                         // il sito ha detto «troppo in fretta»: meno capitoli insieme

    /** Il risultato di un capitolo dentro quello del giro intero. */
    fun aggiungi(r: Risultato) {
        trovate += r.trovate; salvate += r.salvate; piccole += r.piccole; doppie += r.doppie; strisce += r.strisce
        fallite.addAll(r.fallite); pagineFatte += r.pagineFatte; pagineVuote.addAll(r.pagineVuote)
        pagineFallite.addAll(r.pagineFallite); mancanti.addAll(r.mancanti); sfogliate += r.sfogliate
        if (r.fermata.isNotEmpty()) fermata = r.fermata
    }
}

/** Legge e scrive i pixel per cucire le strisce: sul telefono Bitmap (`CuciBitmap`), nelle prove uno finto. */
interface Cucitore {
    fun misura(d: ByteArray): Pair<Int, Int>?
    /** I grigi (0-255, come Pillow «L») della riga y. */
    fun riga(d: ByteArray, y: Int): IntArray?
    /** `sotto` attaccata sotto `sopra`, senza perdite. */
    fun unisci(sopra: ByteArray, sotto: ByteArray): ByteArray?
    /** Le due righe del taglio (fondo di `sopra`, cima di `sotto`) e, se `vaBene` le accetta, la pagina
     *  intera. Il telefono la riscrive per aprire ogni foto una volta sola (batteria). */
    fun cuciSe(sopra: ByteArray, sotto: ByteArray, vaBene: (fondo: IntArray, cima: IntArray) -> Boolean): ByteArray? {
        val (_, ha) = misura(sopra) ?: return null
        val fondo = riga(sopra, ha - 1) ?: return null
        val cima = riga(sotto, 0) ?: return null
        return if (vaBene(fondo, cima)) unisci(sopra, sotto) else null
    }
}

/** Un file da scrivere: nome («004.jpg»), byte, estensione. */
typealias Foto = Triple<String, ByteArray, String>

/**
 * Una foto (o una pagina intera) che non è arrivata: il suo numero resta libero.
 * `cartella` è relativa alla cartella madre ("" = la madre stessa). Foto: `stem` = «004».
 */
class Mancante(val cartella: String, val urls: List<String>, val referer: String = "", val stem: String? = null, val pagina: Pagina? = null,
               var morta: Boolean = false)   // link morto sul sito: niente bottone, solo detto

/** Il posto di una foto che non è arrivata, dentro la lista dei posti di una pagina. */
class Buco(val urls: List<String>, val morta: Boolean = false)

/** Un link verso un'altra pagina: `nome` è l'ultima parte, quella che vede chi lo usa. */
/** testo = il testo del link sulla pagina («Chapter 12»): aiuta indovina. */
class Pagina(val url: String, var nome: String, var scelta: Boolean = true, val testo: String = "")

object Grabber {
    // Come Chrome sul telefono: modello e versione vera di Android nascosti ("Android 10; K"),
    // così i siti non sanno che telefono è — «going privately».
    const val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"
    const val MIN_LATO = 48
    const val STRISCIA_MAX = 0.15   // una striscia è alta al massimo il 15% della pagina sopra (stessa larghezza)
    const val DETTAGLIO_MIN = 20.0  // sul taglio ci dev'essere disegno (vere: 94-118): bianco contro bianco non prova niente
    const val SALDATURA_MAX = 75.0  // differenza media di grigio sul taglio: vere 11-60, false 95-133 (una serie 1190)
    @Volatile var cucitore: Cucitore? = null
    const val LETTORE_MIN = 5   // foto numerate nella stessa cartella perché sia il lettore di un capitolo
    const val MAX_BYTES = 80 * 1024 * 1024
    const val TIMEOUT_MS = 30_000
    private val IMG_EXT = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".avif", ".bmp", ".tif", ".tiff", ".jfif")
    private val LAZY = listOf("data-src", "data-lazy-src", "data-original", "data-lazy", "data-url", "data-full", "data-hi-res-src")
    private val SRCSET = listOf("srcset", "data-srcset", "data-lazy-srcset")
    private val BG = Regex("""url\(\s*['"]?([^'")]+)['"]?\s*\)""", RegexOption.IGNORE_CASE)

    fun nomeCartellaPulito(nome: String?): String {
        var n = (nome ?: "").replace(Regex("[\\x00-\\x1f/\\\\]"), " ")
        n = n.replace(Regex("\\s+"), " ").trim().trim('.')
        return n.take(120).trim()
    }

    fun migliorDaSrcset(valore: String): String? {
        var migliore: String? = null
        var punti = -1.0
        for (pezzo in valore.trim().split(Regex(",\\s+(?=\\S)"))) {
            val parti = pezzo.trim().split(Regex("\\s+"))
            if (parti.isEmpty() || parti[0].isEmpty()) continue
            var peso = 1.0
            if (parti.size > 1) {
                val d = parti[1].lowercase()
                peso = when {
                    d.endsWith("w") -> d.dropLast(1).toDoubleOrNull() ?: 1.0
                    d.endsWith("x") -> (d.dropLast(1).toDoubleOrNull() ?: 1.0) * 1000
                    else -> 1.0
                }
            }
            if (peso > punti) { migliore = parti[0]; punti = peso }
        }
        return migliore
    }

    private fun sembraImmagine(url: String): Boolean {
        val p = try { URLDecoder.decode(url.substringBefore('#').substringBefore('?'), "UTF-8") } catch (e: Exception) { url }
        val l = p.lowercase()
        return IMG_EXT.any { l.endsWith(it) }
    }

    private fun daImg(t: Element): String? {
        for (a in SRCSET) if (t.hasAttr(a) && t.attr(a).isNotBlank()) migliorDaSrcset(t.attr(a))?.let { return it }
        for (a in LAZY) if (t.hasAttr(a) && t.attr(a).isNotBlank() && !t.attr(a).startsWith("data:")) return t.attr(a)
        return if (t.hasAttr("src")) t.attr("src") else null
    }

    private fun assoluto(base: String, u: String): String? {
        val s = u.trim()
        if (s.startsWith("data:")) return s
        return try {
            val r = URI(base).resolve(s.replace(" ", "%20")).toString().substringBefore('#')
            if (r.startsWith("http://") || r.startsWith("https://")) r else null
        } catch (e: Exception) { null }
    }

    /** Ogni voce: candidati in ordine (la grande, poi la riserva mostrata). */
    fun trovaImmagini(html: String, baseIn: String): List<List<String>> {
        val doc = Jsoup.parse(html, baseIn)
        var base = baseIn
        doc.selectFirst("base[href]")?.let { assoluto(baseIn, it.attr("href"))?.let { b -> base = b } }
        val visti = HashSet<String>()
        val ordine = mutableListOf<List<String>>()
        val tags = mutableListOf<Element>()   // il tag di ogni voce, per riconoscere il lettore
        val usati = java.util.IdentityHashMap<Element, Boolean>()

        fun metti(u: String?, tag: Element, riserva: String? = null) {
            if (u.isNullOrBlank()) return
            val a = assoluto(base, u) ?: return
            if (!visti.add(a)) return
            val voce = mutableListOf(a)
            val r = riserva?.let { assoluto(base, it) }
            if (r != null && r != a) { visti.add(r); voce.add(r) }
            ordine.add(voce); tags.add(tag)
        }

        for (tag in doc.allElements) {
            if (usati.containsKey(tag)) continue
            when (tag.normalName()) {
                "script", "style", "noscript", "template" -> { tag.allElements.forEach { usati[it] = true }; continue }
                "a" -> if (tag.hasAttr("href") && sembraImmagine(tag.attr("href"))) {
                    val dentro = tag.selectFirst("img")
                    var mostrata: String? = null
                    if (dentro != null) {
                        mostrata = daImg(dentro)
                        usati[dentro] = true
                        dentro.parents().filter { it.normalName() == "picture" }.forEach { usati[it] = true }
                        tag.select("source").forEach { usati[it] = true }
                    }
                    metti(tag.attr("href"), tag, mostrata)
                }
                "picture" -> {
                    var scelta: String? = null
                    for (s in tag.select("source")) {
                        usati[s] = true
                        if (scelta == null && s.attr("srcset").isNotBlank()) scelta = migliorDaSrcset(s.attr("srcset"))
                    }
                    tag.selectFirst("img")?.let { usati[it] = true; scelta = daImg(it) ?: scelta }
                    metti(scelta, tag)
                }
                "img" -> metti(daImg(tag), tag)
            }
            val st = tag.attr("style")
            if (st.contains("url(")) BG.findAll(st).forEach { m ->
                val u = m.groupValues[1]
                if (!u.trim().startsWith("#")) metti(u, tag)
            }
        }
        return soloLettore(ordine, tags)
    }

    /** Pagina di un capitolo: le sue pagine sono foto numerate nella stessa cartella
     *  (monster/45/0.webp, 1.webp, …) dentro un contenitore, il lettore. Se c'è, resta solo
     *  ciò che sta nel lettore: fuori il logo e le miniature dei manga «consigliati».
     *  Nessun lettore (o il lettore è la pagina intera) → tutto com'era. */
    private fun soloLettore(voci: List<List<String>>, tags: List<Element>): List<List<String>> {
        val gruppi = LinkedHashMap<String, MutableList<Int>>()
        voci.forEachIndexed { i, v ->
            if (v[0].startsWith("data:")) return@forEachIndexed
            val u = try { URL(v[0]) } catch (e: Exception) { return@forEachIndexed }
            val cartella = u.path.substringBeforeLast('/', "")
            val nome = u.path.substringAfterLast('/')
            if (Regex("""\d+$""").containsMatchIn(nome.substringBeforeLast('.'))) gruppi.getOrPut("${u.authority} $cartella") { mutableListOf() }.add(i)
        }
        val scelto = gruppi.values.maxByOrNull { it.size } ?: return voci
        if (scelto.size < LETTORE_MIN) return voci
        fun catena(t: Element): List<Element> = listOf(t) + t.parents()
        val altri = scelto.drop(1).map { i -> java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Element, Boolean>()).also { it.addAll(catena(tags[i])) } }
        val lettore = catena(tags[scelto[0]]).firstOrNull { a -> altri.all { it.contains(a) } } ?: return voci
        if (lettore.normalName() in setOf("body", "html", "#root")) return voci
        return voci.filterIndexed { i, _ -> catena(tags[i]).any { it === lettore } }
    }

    /** Certi siti certi siti danno ogni pagina come foto + una striscia di fondo. `sotto` si
     *  attacca a `sopra` solo se è davvero il suo pezzo: stessa larghezza, bassa (≤ STRISCIA_MAX),
     *  sul taglio c'è disegno e i pixel continuano. Una doppia pagina non si taglia mai. */
    fun cuci(sopra: ByteArray, sotto: ByteArray): ByteArray? {
        val c = cucitore ?: return null
        return try {
            val (wa, ha) = c.misura(sopra) ?: return null
            val (wb, hb) = c.misura(sotto) ?: return null
            if (wa != wb || hb >= ha || hb > ha * STRISCIA_MAX) return null
            c.cuciSe(sopra, sotto) { fondo, cima ->
                if (fondo.size != wa || cima.size != wa) return@cuciSe false
                val media = fondo.average()
                if (Math.sqrt(fondo.sumOf { (it - media) * (it - media) } / wa) < DETTAGLIO_MIN) return@cuciSe false
                fondo.indices.sumOf { Math.abs(fondo[it] - cima[it]) }.toDouble() / wa < SALDATURA_MAX
            }
        } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
    }

    /** Ogni foto arrivata che è la striscia di quella prima finisce dentro di lei. */
    private fun unisciStrisce(dati: Array<ByteArray?>, perse: Map<Int, Boolean>, ris: Risultato) {
        var ultima: Int? = null
        for (j in dati.indices) {
            if (j in perse) { ultima = null; continue }   // un buco in mezzo: niente da cucire attraverso
            val d = dati[j] ?: continue
            val u = ultima
            if (u != null) {
                val intera = cuci(dati[u]!!, d)
                if (intera != null) { dati[u] = intera; dati[j] = null; ris.strisce++; continue }
            }
            ultima = j
        }
    }

    /** Il Referer come lo manda Chrome (strict-origin-when-cross-origin): la pagina intera allo
     *  stesso sito, solo la radice del sito a un altro host, niente da https verso http.
     *  Certi server di foto rifiutano l'indirizzo intero (un sito vero: 403). */
    fun refererPer(pagina: String, foto: String): String? {
        val p = try { URL(pagina) } catch (e: Exception) { return null }
        val f = try { URL(foto) } catch (e: Exception) { return null }
        if (p.protocol == "https" && f.protocol == "http") return null
        if (p.protocol == f.protocol && p.authority == f.authority) return pagina.substringBefore('#')
        return "${p.protocol}://${p.authority}/"
    }

    fun estensione(d: ByteArray): String? {
        fun at(i: Int) = if (i < d.size) d[i].toInt() and 0xff else -1
        fun s(a: Int, b: Int) = if (d.size >= b) String(d, a, b - a, Charsets.ISO_8859_1) else ""
        return when {
            at(0) == 0xff && at(1) == 0xd8 && at(2) == 0xff -> ".jpg"
            at(0) == 0x89 && s(1, 4) == "PNG" -> ".png"
            s(0, 6) == "GIF87a" || s(0, 6) == "GIF89a" -> ".gif"
            s(0, 4) == "RIFF" && s(8, 12) == "WEBP" -> ".webp"
            s(4, 12) == "ftypavif" || s(4, 12) == "ftypavis" -> ".avif"
            s(0, 2) == "BM" -> ".bmp"
            s(0, 4) == "II*\u0000" || s(0, 4) == "MM\u0000*" -> ".tif"
            else -> null
        }
    }

    private val TAGLIA = Regex("([@_-](\\d+x\\d*|\\d*x\\d+|scaled|large|medium|small|thumb|thumbnail|orig|original|full|big|hd))+$", RegexOption.IGNORE_CASE)

    /** La stessa foto in un'altra misura: stesso nome (e stessa cartella) senza il pezzo della misura. */
    fun gemella(url: String): String? {
        if (url.startsWith("data:")) return null
        val pulito = try { URLDecoder.decode(url.substringBefore('#').substringBefore('?').trimEnd('/'), "UTF-8") } catch (e: Exception) { url }.lowercase()
        val cartella = pulito.substringBeforeLast('/', "")
        val nome = pulito.substringAfterLast('/')
        val radice = TAGLIA.replace(if (nome.contains('.')) nome.substringBeforeLast('.') else nome, "")
        return if (radice.isEmpty()) null else "$cartella/$radice"
    }

    fun mime(ext: String) = when (ext) {
        ".jpg" -> "image/jpeg"; ".png" -> "image/png"; ".gif" -> "image/gif"; ".webp" -> "image/webp"
        ".avif" -> "image/avif"; ".bmp" -> "image/bmp"; ".tif" -> "image/tiff"; else -> "application/octet-stream"
    }

    var ATTESE_MS = longArrayOf(2000, 5000, 12000)   // fra un tentativo e l'altro (la prova le accorcia)

    private fun attesaSito(c: HttpURLConnection): Long? =
        c.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.let { minOf(it, 30) * 1000 }

    private fun inciampo(c: HttpURLConnection): Boolean {
        if (c.responseCode == 429) RALLENTA.incrementAndGet()   // «troppo in fretta»: il freno dei capitoli lo legge
        return c.responseCode == 429 || c.responseCode >= 500
    }

    /** Quante volte un sito ha risposto 429 «troppo in fretta». */
    val RALLENTA = AtomicInteger(0)

    /** Rifà `fare()` quando il sito inciampa; un no vero (404, non è una pagina) passa subito. */
    fun <T> conRiprova(stop: AtomicBoolean, tentativi: Int = 3, fare: () -> T): T {
        var n = 0
        while (true) {
            try { return fare() } catch (e: GrabError) {
                if (!e.riprova || n == tentativi - 1 || stop.get()) throw e
                val fine = System.currentTimeMillis() + (e.attesaMs ?: ATTESE_MS[minOf(n, ATTESE_MS.size - 1)])
                while (System.currentTimeMillis() < fine) { if (stop.get()) throw e; Thread.sleep(minOf(250L, maxOf(1L, fine - System.currentTimeMillis()))) }
                n++
            }
        }
    }

    private fun apri(url: String, referer: String?): HttpURLConnection = try { apriCrudo(url, referer) }
        catch (e: GrabError) { throw e }
        catch (e: Exception) { throw GrabError("network: ${e.javaClass.simpleName}", riprova = true) }

    private fun apriCrudo(url: String, referer: String?): HttpURLConnection {
        var u = URL(url)
        repeat(6) {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = TIMEOUT_MS; c.readTimeout = TIMEOUT_MS
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Language", "it,en;q=0.8")
            if (referer != null) c.setRequestProperty("Referer", referer)
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: return c
                c.disconnect(); u = URL(u, loc); return@repeat
            }
            return c
        }
        throw GrabError("too many redirects")
    }

    fun scarica(url: String, referer: String, stop: AtomicBoolean): ByteArray {
        if (url.startsWith("data:")) {
            val testa = url.substringBefore(','); val corpo = url.substringAfter(',', "")
            return if (testa.contains(";base64")) Base64.getMimeDecoder().decode(corpo)
            else URLDecoder.decode(corpo, "UTF-8").toByteArray(Charsets.ISO_8859_1)
        }
        val c = apri(url, if (referer.isEmpty()) null else refererPer(referer, url))
        try {
            if (c.responseCode != 200) throw GrabError("the site answered ${c.responseCode}", inciampo(c), attesaSito(c),
                morta = c.responseCode == 404 || c.responseCode == 410)
            val attesi = c.contentLengthLong
            val out = ByteArrayOutputStream()
            c.inputStream.use { ins ->
                val buf = ByteArray(65536)
                while (true) {
                    if (stop.get()) throw GrabError("cancelled")
                    val n = ins.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) throw GrabError("bigger than 80 MB")
                }
            }
            val enc = c.contentEncoding
            if (attesi >= 0 && (enc == null || enc == "identity") && attesi != out.size().toLong()) throw GrabError("arrived only halfway")
            return out.toByteArray()
        } finally { c.disconnect() }
    }

    fun leggiPagina(linkIn: String): Pair<String, String> {
        var link = linkIn.trim()
        if (link.isEmpty()) throw GrabError("Paste a link.")
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(link)) link = "https://$link"
        val c = try { apriCrudo(link, null) } catch (e: GrabError) { throw e } catch (e: Exception) {
            throw GrabError("Can't open the page (${e.javaClass.simpleName}: ${e.message?.take(100) ?: "no details"}).", riprova = true)
        }
        try {
            if (c.responseCode != 200) throw GrabError("The page answered ${c.responseCode}.", inciampo(c), attesaSito(c))
            val tipo = c.contentType ?: ""
            if (tipo.startsWith("image/")) throw GrabError("This link is a single image, not a page.")
            val cs = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(tipo)?.groupValues?.get(1)
            val bytes = c.inputStream.use { it.readBytes() }
            val html = try { String(bytes, charset(cs ?: "UTF-8")) } catch (e: Exception) { String(bytes, Charsets.UTF_8) }
            return html to c.url.toString()
        } finally { c.disconnect() }
    }

    /** Scarica le foto di una pagina già letta: in ordine, senza icone, doppioni (anche verso `impronte`) né `escludi`. */
    private fun raccogli(
        html: String, base: String, ris: Risultato, impronte: MutableSet<String>, piccola: (ByteArray) -> Boolean,
        avanzamento: (Int, Int, String) -> Unit, stop: AtomicBoolean, paralleli: Int, prefisso: String = "",
        escludi: Set<String> = emptySet(),
    ): List<Any> {   // posti in ordine: Pair(dati, ext) arrivata, Buco (candidati) di una mancata
        val urls = trovaImmagini(html, base).filter { it[0] !in escludi }
        ris.trovate += urls.size
        if (urls.isEmpty()) return emptyList()
        val dati = arrayOfNulls<ByteArray>(urls.size)
        val perse = java.util.Collections.synchronizedMap(HashMap<Int, Boolean>())   // indice -> link morto sul sito
        val fatte = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(paralleli)
        for (i in urls.indices) pool.execute {
            var motivo: String? = null
            var morte = 0
            for (u in urls[i]) {
                if (stop.get()) break
                val d = try { conRiprova(stop, 2) { scarica(u, base, stop) } } catch (e: Exception) {
                    motivo = e.message ?: e.javaClass.simpleName; if ((e as? GrabError)?.morta == true) morte++; null } ?: continue
                if (estensione(d) != null) { dati[i] = d; break }
                if (motivo == null) motivo = "not an image"
            }
            if (dati[i] == null && !stop.get() && motivo != "not an image")
                synchronized(ris.fallite) { ris.fallite.add(urls[i][0].take(200) to (motivo ?: "?")); perse[i] = morte == urls[i].size }
            val f = fatte.incrementAndGet()
            avanzamento(f, urls.size, "${prefisso}Downloading $f of ${urls.size}…")
        }
        pool.shutdown(); pool.awaitTermination(1, TimeUnit.HOURS)
        if (stop.get()) return emptyList()
        // la stessa foto in due misure nella stessa pagina: resta la più grande, al posto della prima
        val posto = HashMap<String, Int>()
        for (i in dati.indices) {
            val d = dati[i] ?: continue
            val g = gemella(urls[i][0]) ?: continue
            val j = posto[g]
            if (j == null) { posto[g] = i; continue }
            if (d.size > dati[j]!!.size) dati[j] = d
            dati[i] = null
            ris.doppie++
        }
        unisciStrisce(dati, perse, ris)
        val tenute = mutableListOf<Any>()
        val md = MessageDigest.getInstance("SHA-256")
        for ((i, d) in dati.withIndex()) {
            if (i in perse) { tenute.add(Buco(urls[i], perse[i] == true)); continue }   // posto libero
            if (d == null) continue
            val ext = estensione(d) ?: continue
            val imp = md.digest(d).joinToString("") { "%02x".format(it) }
            // più capitoli insieme: «già salvata?» e «ora è salvata» in un gesto solo
            val tenuta = synchronized(impronte) {
                when {
                    imp in impronte -> { ris.doppie++; false }
                    piccola(d) -> { ris.piccole++; false }
                    else -> { impronte.add(imp); true }
                }
            }
            if (tenuta) tenute.add(d to ext)
        }
        return tenute
    }

    /** Numera i posti nell'ordine della pagina: le arrivate si scrivono, le mancate lasciano il numero libero. */
    private fun scriviCartella(rel: String, posti: List<Any>, ris: Risultato, referer: String, salva: (String, List<Foto>) -> Unit,
                               inizio: Int = 0, cifreIn: Int? = null): Int {
        val cifre = cifreIn ?: maxOf(3, posti.size.toString().length)
        val file = mutableListOf<Foto>()
        posti.forEachIndexed { k, x ->
            val stem = (inizio + k + 1).toString().padStart(cifre, '0')
            if (x is Pair<*, *>) { val d = x.first as ByteArray; val ext = x.second as String; file.add(Foto(stem + ext, d, ext)) }
            else if (x is Buco) ris.mancanti.add(Mancante(rel, x.urls, referer, stem = stem, morta = x.morta))
        }
        if (file.isNotEmpty()) salva(rel, file)
        return file.size
    }

    /**
     * Una pagina → una cartella. piccola(dati): true se è un'icona. salva(lista di (dati, ext)):
     * scrive la cartella (chiamata solo se c'è almeno una foto). avanzamento(fatte, totale, msg).
     */
    fun scaricaTutto(
        link: String,
        piccola: (ByteArray) -> Boolean,
        salva: (String, List<Foto>) -> Unit,
        avanzamento: (Int, Int, String) -> Unit = { _, _, _ -> },
        stop: AtomicBoolean = AtomicBoolean(false),
        paralleli: Int = 6,
    ): Risultato {
        avanzamento(0, 0, "Reading the page…")
        val (html, base) = leggiPagina(link)
        val ris = Risultato()
        val (scritte, numeri) = giroPagine(html, base, "", ris, piccola, salva, avanzamento, stop, paralleli, cercaArredo = true)
        ris.salvate = scritte
        if (stop.get()) { ris.annullato = true; return ris }
        if (ris.trovate == 0) throw GrabError("No images found on this page. It may load them only while you scroll.")
        if (numeri == 0) throw GrabError("Only icons or non-photo files found: nothing to save.")
        avanzamento(1, 1, "Saved")
        return ris
    }

    // ------------------------------------------------------------ la freccia «avanti»
    // Siti che mostrano una foto per pagina: si segue la freccia finché il capitolo finisce.
    // «Stesso capitolo» = i numeri del link di partenza restano uguali (tranne il numero di
    // pagina), e un link che dice «chapter/episode/post» non si segue mai. Come grabber.py.

    const val SFOGLIA_MAX = 500
    private val I = RegexOption.IGNORE_CASE
    private val FRECCIA_TESTO = Regex("(next|next page|next image|avanti|successiva|pagina successiva|seguente|suivant|" +
        "siguiente|weiter|nächste|próxima|proxima|[›»>→⟩❯▶►⇒⟶⇨]+|-->)", I)
    private val FRECCIA_ATTR = Regex("(?:^|[^a-z])(next|nxt|avanti|forward|succ\\w*|arrow-right|chevron-right|angle-right|" +
        "caret-right|right-arrow)(?:[^a-z]|$)", I)
    private val CAPITOLO_TESTO = Regex("chap|capit|episod|volum|\\bvol\\b|\\bpost\\b|article|articolo|stor[yi]", I)
    private val CAPITOLO_ATTR = Regex("chap|capit|episod", I)
    private val PAROLA_PAGINA = Regex("(?:^|[^a-z])(page|pagina|pag|pg|img|image|foto|photo|slide|p)[\\s/=_\\-]*$", I)
    private val PAROLA_CAPITOLO = Regex("(?:^|[^a-z])(chapter|chap|ch|capitolo|cap|episode|ep|vol|volume|issue|c)[\\s/=_.\\-]*$", I)
    private val EXT_PAGINA = Regex("\\.(html?|php|aspx?)$", I)
    private val CODA = Regex("[/\\-_?&.]?(?:[a-z]+[/=_\\-]?)?(\\d+)(?:\\.(?:html?|php|aspx?))?/?", I)

    /** Il sito senza sottodomini: m.sito.com, w1.sito.com e sito.com sono lo stesso sito. */
    fun dominio(u: String): String {
        val h = host(u)
        if (Regex("[\\d.]+").matches(h) || ':' in h || h.count { it == '.' } < 2) return h
        val p = h.split('.')
        if (p.last().length == 2 && p[p.size - 2] in setOf("co", "com", "org", "net", "ac", "gov", "edu", "ne", "or")) return p.takeLast(3).joinToString(".")
        return p.takeLast(2).joinToString(".")
    }

    private fun chiave(u: String): String {
        val uri = try { URI(u) } catch (e: Exception) { return u }
        val k = (uri.rawPath ?: "").trimEnd('/').ifEmpty { "/" }
        return if (uri.rawQuery.isNullOrEmpty()) k else "$k?${uri.rawQuery}"
    }

    private fun pezzi(u: String): Pair<List<String>, List<java.math.BigInteger>> {
        val k = chiave(u)
        val testi = k.split(Regex("\\d+"))
        val numeri = Regex("\\d+").findAll(k).map { java.math.BigInteger(it.value) }.toList()
        return testi to numeri
    }

    /** Di quanto nxt va avanti rispetto a cur, se ne è la pagina dopo (stessa forma con un solo numero
     *  cresciuto, oppure cur + un numero in coda: /chapter-5 → /2, ?page=2, -2.html); null se no. */
    fun passo(cur: String, nxt: String): Long? {
        val (tc, nc) = pezzi(cur)
        val (tn, nn) = pezzi(nxt)
        if (tc == tn && nc.size == nn.size) {
            val diversi = nc.indices.filter { nc[it] != nn[it] }
            if (diversi.size == 1 && nn[diversi[0]] > nc[diversi[0]]) return (nn[diversi[0]] - nc[diversi[0]]).toLong()
            return null
        }
        val kc = chiave(cur); val kn = chiave(nxt)
        val radice = EXT_PAGINA.find(kc)?.let { kc.substring(0, it.range.first) } ?: kc
        if (kn.startsWith(radice)) {
            val m = CODA.matchEntire(kn.substring(radice.length))
            if (m != null && m.groupValues[1].toBigInteger() in listOf(1.toBigInteger(), 2.toBigInteger())) return 1
        }
        return null
    }

    /** I numeri del link di partenza restano tutti uguali in u, tranne l'ultimo quando è il numero di pagina.
     *  /chapter-5 → /chapter-5/2 sì; /chapter-5 → /chapter-6 no. */
    fun stessoCapitolo(partenza: String, u: String, lasco: Boolean): Boolean {
        val (ts, ns) = pezzi(partenza)
        val nn = pezzi(u).second
        if (nn.size < ns.size) return false
        var libero = -1
        if (ns.isNotEmpty()) {
            val prima = ts[ns.size - 1]
            libero = when {
                PAROLA_PAGINA.containsMatchIn(prima) -> ns.size - 1
                PAROLA_CAPITOLO.containsMatchIn(prima) -> -1
                ns.size >= 2 || lasco -> ns.size - 1
                else -> -1
            }
        }
        return ns.indices.all { it == libero || nn[it] == ns[it] }
    }

    private fun pulito(u: String) = u.substringBefore('#').trimEnd('/')

    /** Il link della pagina dopo, o null se il capitolo è finito. Prima la freccia che va a un numero
     *  dopo; poi un link qualunque al numero +1; la freccia senza numero solo sulle pagine da una foto. */
    fun paginaDopo(html: String, baseIn: String, partenza: String, visti: Set<String>, lasco: Boolean, evita: Set<String> = emptySet()): String? {
        val doc = Jsoup.parse(html, baseIn)
        var base = baseIn
        doc.selectFirst("base[href]")?.let { assoluto(baseIn, it.attr("href"))?.let { b -> base = b } }
        val casa = dominio(base)
        val io = base.substringBefore('#')
        fun buono(hIn: String?): String? {
            val h = (hIn ?: "").trim()
            if (h.isEmpty() || listOf("#", "javascript:", "mailto:", "tel:", "data:").any { h.startsWith(it) }) return null
            val u = assoluto(base, h) ?: return null
            if (u.startsWith("data:") || dominio(u) != casa || chiave(u) == chiave(io) || pulito(u) in visti || pulito(u) in evita) return null
            val percorso = try { URLDecoder.decode(u.substringBefore('?'), "UTF-8") } catch (e: Exception) { u }.lowercase()
            if (NON_PAGINE.any { percorso.endsWith(it) } || !stessoCapitolo(partenza, u, lasco)) return null
            return u
        }
        data class F(val senzaNumero: Boolean, val passo: Long, val u: String)
        val frecce = mutableListOf<F>()
        val numeri = mutableListOf<String>()
        for (t in doc.select("a[href], link[href]")) {
            val u = buono(t.attr("href")) ?: continue
            val rel = t.attr("rel").lowercase().split(Regex("\\s+"))
            if (t.tagName() == "link" && "next" !in rel) continue
            val testo = t.text().trim().replace(Regex("\\s+"), " ")
            val attr = (listOf(t) + t.select("*").drop(1)).joinToString(" ") { x ->
                listOf("class", "id", "title", "aria-label", "alt").joinToString(" ") { x.attr(it) } }
            if (CAPITOLO_TESTO.containsMatchIn(testo) || CAPITOLO_ATTR.containsMatchIn(attr)) continue   // «Next chapter»
            val freccia = "next" in rel || (testo.isNotEmpty() && testo.length <= 25 && FRECCIA_TESTO.matches(testo)) ||
                FRECCIA_ATTR.containsMatchIn(attr)
            val p = passo(io, u)
            if (freccia) frecce.add(F(p == null, p ?: 0, u)) else if (p == 1L) numeri.add(u)
        }
        // il menu a tendina delle pagine: l'opzione dopo quella scelta
        for (sel in doc.select("select")) {
            val opz = sel.select("option")
            for (i in 0 until opz.size - 1) {
                if (!opz[i].hasAttr("selected")) continue
                val v = opz[i + 1].attr("value")
                val u = if ('/' in v || v.startsWith("http") || EXT_PAGINA.containsMatchIn(v)) buono(v) else null
                if (u != null) { val p = passo(io, u); frecce.add(F(p == null, p ?: 0, u)) }
            }
        }
        val ordinate = frecce.sortedWith(compareBy<F>({ it.senzaNumero }, { it.passo }))
        if (ordinate.isNotEmpty() && !ordinate[0].senzaNumero) return ordinate[0].u
        if (numeri.isNotEmpty()) return numeri[0]
        if (ordinate.isNotEmpty() && lasco) return ordinate[0].u
        return null
    }

    /** Le pagine dopo quella data, una alla volta, finché c'è la freccia (al massimo SFOGLIA_MAX). */
    private fun pagineSeguenti(html0: String, base0: String, ris: Risultato, stop: AtomicBoolean, evita: Set<String>): Iterator<Pair<String, String>> = iterator {
        val partenza = base0
        val lasco = trovaImmagini(html0, base0).size <= 3
        val visti = HashSet<String>().apply { add(pulito(base0)) }
        var html = html0; var base = base0
        for (n in 2..SFOGLIA_MAX) {
            if (stop.get()) return@iterator
            val u = paginaDopo(html, base, partenza, visti, lasco, evita) ?: return@iterator
            visti.add(pulito(u))
            val letta = try { conRiprova(stop) { leggiPagina(u) } } catch (e: GrabError) {
                if (!stop.get()) ris.fermata = "page $n didn't open (${e.message}), stopped there"
                return@iterator
            }
            html = letta.first; base = letta.second
            if (pulito(base) in visti && pulito(base) != pulito(u)) return@iterator   // rimandati a una pagina già vista
            visti.add(pulito(base))
            yield(letta)
        }
        ris.fermata = "stopped after $SFOGLIA_MAX pages"
    }

    /** Una pagina e quelle dopo (la freccia «avanti») in una cartella sola, numerate di seguito e salvate
     *  pagina per pagina. Torna (foto scritte, numeri usati). */
    private fun giroPagine(
        html: String, base: String, rel: String, ris: Risultato, piccola: (ByteArray) -> Boolean,
        salva: (String, List<Foto>) -> Unit, avanzamento: (Int, Int, String) -> Unit, stop: AtomicBoolean, paralleli: Int,
        prefisso: String = "", escludiIn: Set<String> = emptySet(), evita: Set<String> = emptySet(), cercaArredo: Boolean = false,
    ): Pair<Int, Int> {
        val seguenti = pagineSeguenti(html, base, ris, stop, evita)
        var dopo = if (seguenti.hasNext()) seguenti.next() else null
        var escludi = escludiIn
        if (dopo != null && cercaArredo) {
            // foto uguali su pagina 1 e 2 (logo, banner) = arredo del sito
            val comuni = trovaImmagini(html, base).map { it[0] }.toSet() intersect trovaImmagini(dopo.first, dopo.second).map { it[0] }.toSet()
            if (comuni.isNotEmpty()) { escludi = escludi + comuni; ris.arredo += comuni.size; ris.arredoSet = ris.arredoSet + comuni }
        }
        var numero = 0; var scritte = 0; var vuote = 0; var cifre = 3; var pagine = 1; var conFoto = 0
        var corrente: Pair<String, String>? = html to base
        while (corrente != null) {
            val (h, b) = corrente
            val pref = if (dopo != null || pagine > 1) "${prefisso}Page $pagine · " else prefisso
            val posti = raccogli(h, b, ris, ris.impronte, piccola, avanzamento, stop, paralleli, pref, escludi)
            if (stop.get()) break
            if (posti.isNotEmpty()) {
                vuote = 0
                conFoto++
                if (numero == 0) cifre = maxOf(3, posti.size.toString().length)
                if (numero + posti.size >= Math.pow(10.0, cifre.toDouble()).toInt()) { ris.fermata = "stopped at $numero pictures (folder full)"; break }
                scritte += scriviCartella(rel, posti, ris, b, salva, numero, cifre)
                numero += posti.size
            } else {
                vuote++
                if (vuote >= 2) break
            }
            if (dopo == null) break
            corrente = dopo
            dopo = if (seguenti.hasNext()) seguenti.next() else null
            pagine++
        }
        if (pagine > 1) ris.sfogliate += conFoto   // pagine che hanno dato foto
        return scritte to numero
    }

    // ------------------------------------------------------------ più pagine

    private val NON_PAGINE = IMG_EXT + listOf(".svg", ".pdf", ".zip", ".rar", ".7z", ".mp4", ".mp3", ".webm", ".mov",
        ".css", ".js", ".json", ".xml", ".rss", ".apk", ".exe", ".dmg")

    private fun host(u: String): String {
        val h = (try { URI(u).host } catch (e: Exception) { null } ?: "").lowercase()
        return if (h.startsWith("www.")) h.drop(4) else h
    }

    private fun segmenti(u: String): List<String> {
        val p = try { URI(u).rawPath ?: "" } catch (e: Exception) { "" }
        return p.split("/").filter { it.isNotEmpty() }.map { try { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { it } }
    }

    fun nomeBreve(u: String, profondita: Int = 1): String {
        val seg = segmenti(u)
        val q = try { URI(u).rawQuery } catch (e: Exception) { null }
        var n = if (seg.isEmpty()) "home" else seg.takeLast(profondita).joinToString("/")
        if (!q.isNullOrEmpty()) n += "?" + (try { URLDecoder.decode(q, "UTF-8") } catch (e: Exception) { q })
        return n.take(80)
    }

    private fun senzaBarra(u: String) = u.trimEnd('/')

    private val ONCLICK = Regex("""(?:location(?:\.href)?|window\.open)\s*(?:=|\()\s*['"]([^'"]+)['"]""", I)

    /** Il testo di un elemento come lo legge il PC (get_text(" ")): ogni pezzo di testo separato da uno spazio. */
    private fun testoDi(t: Element): String {
        val pezzi = mutableListOf<String>()
        fun giro(n: org.jsoup.nodes.Node) {
            if (n is org.jsoup.nodes.TextNode) pezzi.add(n.wholeText) else n.childNodes().forEach { giro(it) }
        }
        giro(t)
        return pezzi.joinToString(" ").split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(120)
    }

    /** Ogni link della pagina in ordine, col suo testo: <a href>, le voci dei menu a tendina (<option value>),
     *  data-href/data-url/data-link e onclick="location.href='…'". */
    private fun linkNascosti(doc: org.jsoup.nodes.Document): List<Pair<String, String>> {
        val fuori = doc.select("script, style, template").flatMap { it.select("*") }.toCollection(java.util.Collections.newSetFromMap(java.util.IdentityHashMap()))
        val out = mutableListOf<Pair<String, String>>()
        for (t in doc.getAllElements()) {
            if (t in fuori) continue
            val testo by lazy { testoDi(t) }
            if (t.tagName() == "a" && t.attr("href").isNotBlank()) out.add(t.attr("href").trim() to testo)
            else if (t.tagName() == "option") {
                val v = t.attr("value").trim()
                if (listOf("http://", "https://", "/", "./", "../").any { v.startsWith(it) } || ('/' in v && ' ' !in v) || EXT_PAGINA.containsMatchIn(v)) out.add(v to testo)
            }
            for (a in listOf("data-href", "data-url", "data-link")) if (t.attr(a).isNotBlank()) out.add(t.attr(a).trim() to testo)
            val oc = t.attr("onclick")
            if (oc.isNotBlank()) ONCLICK.findAll(oc).forEach { out.add(it.groupValues[1].trim() to testo) }
        }
        return out
    }

    /** Link verso altre pagine dello STESSO sito, in ordine, senza doppioni, senza sé stessa né file. */
    fun trovaPagine(html: String, baseIn: String): List<Pagina> {
        val doc = Jsoup.parse(html, baseIn)
        var base = baseIn
        doc.selectFirst("base[href]")?.let { assoluto(baseIn, it.attr("href"))?.let { b -> base = b } }
        val ioStessa = senzaBarra(base.substringBefore('#'))
        val casa = dominio(base)
        val visti = HashSet<String>()
        val pagine = mutableListOf<Pagina>()
        for ((h, testo) in linkNascosti(doc)) {
            if (h.isEmpty() || h.startsWith("#") || h.startsWith("mailto:") || h.startsWith("tel:") ||
                h.startsWith("javascript:") || h.startsWith("data:")) continue
            val u = assoluto(base, h) ?: continue
            if (u.startsWith("data:") || dominio(u) != casa) continue
            val k = senzaBarra(u)
            if (k == ioStessa || k in visti) continue
            val percorso = try { URLDecoder.decode(u.substringBefore('?'), "UTF-8") } catch (e: Exception) { u }.lowercase()
            if (NON_PAGINE.any { percorso.endsWith(it) }) continue
            visti.add(k)
            pagine.add(Pagina(u, nomeBreve(u), testo = testo))
        }
        for (prof in 2..3) {
            val conta = pagine.groupingBy { it.nome }.eachCount()
            val doppi = conta.filterValues { it > 1 }.keys
            if (doppi.isEmpty()) break
            for (p in pagine) if (p.nome in doppi) p.nome = nomeBreve(p.url, prof)
        }
        return pagine
    }

    // «chapter-12», «ch12», «c012», «ep-3», «season-2-ep-40», «capitolo_7»: una parola da capitolo attaccata
    // a un numero, all'inizio del pezzo o dopo un separatore (non «f212c582», non «step-1»). Uguale a grabber.py.
    private val CAP_PEZZO = Regex("(?:^|[-_.~ ])(?:chapter|chap|ch|c|episode|ep|capitolo|cap|capitulo)[-_. ]?\\d", RegexOption.IGNORE_CASE)
    private val CAP_PAROLA = Regex("(?:chapter|chap|ch|c|episode|ep|capitolo|cap|capitulo)", RegexOption.IGNORE_CASE)   // «/chapter/6»
    // il testo del link COMINCIA con «Chapter 12» (non «Solo Leveling - Chapter 200», che è la serie)
    private val CAP_TESTO = Regex("^\\W*(?:chapter|chap|ch|episode|ep|capitolo|cap|capitulo)\\b\\.?\\s*#?\\d", RegexOption.IGNORE_CASE)
    private val CODICE = Regex("[0-9a-f-]{8,}|[0-9A-Za-z]{16,}", RegexOption.IGNORE_CASE)
    private val NUMERO = Regex("\\d+(?:\\.\\d+)?")
    private val CIFRA = Regex("\\d")
    private val LETTERA = Regex("[a-z]", RegexOption.IGNORE_CASE)

    /** Un pezzo di link senza ciò che cambia: numeri (anche 34.5) → #, codice lungo (01M6RHBP…, uuid) → *. */
    fun norma(pezzo: String): String =
        if (CIFRA.containsMatchIn(pezzo) && CODICE.matches(pezzo)) "*" else NUMERO.replace(pezzo, "#")

    /** Un pezzo di cartella: numeri e codici cambiano (/chapters/2-11198000/), un nome resta com'è (/manga/level-99-hero/). */
    private fun normaCartella(pezzo: String): String {
        val n = norma(pezzo)
        return if (n == "*" || !LETTERA.containsMatchIn(pezzo)) n else pezzo
    }

    private fun cartella(seg: List<String>) = seg.joinToString("/") { normaCartella(it) }

    /** La parte dopo «?» senza ciò che cambia: «?episode_no=#&title_no=#» (come parse_qsl del PC). */
    private fun domanda(u: String): String {
        val q = try { URI(u).rawQuery } catch (e: Exception) { null } ?: ""
        fun dec(x: String) = try { URLDecoder.decode(x, "UTF-8") } catch (e: Exception) { x }
        val coppie = q.split('&').filter { it.isNotEmpty() }.map {
            val i = it.indexOf('=')
            if (i < 0) dec(it) + "=" else dec(it.substring(0, i)) + "=" + norma(dec(it.substring(i + 1)))
        }
        return "?" + coppie.sorted().joinToString("&")
    }

    private class Forma(val host: String, val dove: Int?, val cartella: String, val coda: String)

    /** Dove sta il pezzo-capitolo (l'ultimo con «chapter/ch/c/ep…+numero»; se il link non lo dice ma il testo
     *  sì, «Chapter 12» su /chapters/01M6…, l'ultimo pezzo), la cartella prima e la coda dopo, normalizzate. */
    private fun forma(p: Pagina): Forma {
        val seg = segmenti(p.url)
        var dove: Int? = null
        for (i in seg.indices.reversed()) {
            if (CAP_PEZZO.containsMatchIn(seg[i]) || (i > 0 && CAP_PAROLA.matches(seg[i - 1]) && NUMERO.matches(seg[i]))) { dove = i; break }
        }
        if (dove == null && seg.isNotEmpty() && CAP_TESTO.containsMatchIn(p.testo)) dove = seg.size - 1
        val h = host(p.url)
        if (dove == null) {   // l'ultimo pezzo è quello che cambia: lì i numeri contano anche dentro un nome
            val c = (if (seg.size > 1) listOf(cartella(seg.dropLast(1))) else emptyList()) + seg.takeLast(1).map { norma(it) }
            return Forma(h, null, c.joinToString("/"), domanda(p.url))
        }
        return Forma(h, dove, cartella(seg.take(dove)), seg.drop(dove + 1).joinToString("/") { norma(it) } + domanda(p.url))
    }

    private data class Chiave(val host: String, val cartella: String, val coda: String)

    /**
     * Spunta solo i link che sembrano «i capitoli» della pagina `pagina`; gli altri visibili, senza spunta.
     * Nessun elenco chiaro → nessuna spunta: non inventa capitoli. Stessa regola di `indovina` in grabber.py:
     * 1) link con «chapter/ch/c/ep…+numero» (nel link o all'inizio del testo) per cartella e coda, ≥2: vince il gruppo
     *    della serie della pagina, poi il più grande; entrano i fratelli con un numero (side-story-3).
     * 2) dentro la cartella della pagina, cambia un pezzo (il titolo) e un numero (webtoon canvas), ≥3.
     * 3) uguali tranne un numero o un codice (xkcd /123/, viewtopic.php?id=), ≥3.
     * 4) una cartella con almeno 10 link e più di metà della pagina (gli articoli di un blog).
     */
    fun indovina(pagine: List<Pagina>, pagina: String = ""): List<Pagina> {
        if (pagine.isEmpty()) return pagine
        val pagSeg = if (pagina.isNotEmpty()) segmenti(pagina) else emptyList()
        val pagNorm = pagSeg.map { normaCartella(it) }
        val segs = pagine.map { segmenti(it.url) }
        val forme = pagine.map { forma(it) }
        fun spunta(scelti: Set<Int>): List<Pagina> { pagine.forEachIndexed { i, p -> p.scelta = i in scelti }; return pagine }

        fun dellaPagina(c0: String): Boolean {
            val c = if (c0.isEmpty()) emptyList() else c0.split("/")
            return c.isNotEmpty() && pagNorm.take(c.size) == c && (c.size >= 2 || pagNorm.size == 1)
        }
        fun simili(a: Chiave, b: Chiave): Boolean {
            val ca = a.cartella.split("/"); val cb = b.cartella.split("/")
            return a.host == b.host && a.coda == b.coda && ca.size == cb.size && ca.zip(cb).count { it.first != it.second } == 1
        }
        fun scegli(gruppi: Map<Chiave, List<Int>>, minimo: Int, contaPagina: Boolean): Chiave? {
            if (gruppi.isEmpty()) return null
            val ordine = gruppi.keys.sortedWith(compareByDescending<Chiave> { contaPagina && dellaPagina(it.cartella) }
                .thenByDescending { gruppi[it]!!.size }.thenBy { gruppi[it]!![0] })
            val k = ordine[0]
            val mia = contaPagina && dellaPagina(k.cartella)
            if (gruppi[k]!!.size < (if (mia) 2 else minimo)) return null
            if (!mia && ordine.drop(1).any { gruppi[it]!!.size == gruppi[k]!!.size && simili(it, k) }) return null   // tante serie uguali
            return k
        }

        // 1) capitoli dichiarati
        val capitoli = LinkedHashMap<Chiave, MutableList<Int>>()
        forme.forEachIndexed { i, f -> if (f.dove != null) capitoli.getOrPut(Chiave(f.host, f.cartella, f.coda)) { mutableListOf() }.add(i) }
        scegli(capitoli, 2, true)?.let { k ->
            val scelti = capitoli[k]!!.toMutableSet()
            val prof = if (k.cartella.isEmpty()) 0 else k.cartella.split("/").size
            segs.forEachIndexed { i, seg ->   // i fratelli: side-story-3, extra-1
                if (forme[i].host == k.host && seg.size > prof && cartella(seg.take(prof)) == k.cartella &&
                    seg.drop(prof + 1).joinToString("/") { norma(it) } + domanda(pagine[i].url) == k.coda &&
                    CIFRA.containsMatchIn(seg[prof])) scelti.add(i)
            }
            return spunta(scelti)
        }
        // 2) dentro la cartella della pagina, cambia un pezzo (il titolo) e c'è un numero che cambia
        val lp = pagSeg.size - 1
        if (lp >= 2) {
            val gruppi = LinkedHashMap<Pair<Int, String>, MutableList<Int>>()
            segs.forEachIndexed { i, seg ->
                if (seg.size > lp && seg.take(lp) == pagSeg.take(lp) && forme[i].host == host(pagina)) {
                    val resto = seg.drop(lp + 1).joinToString("/") { norma(it) } + domanda(pagine[i].url)
                    if ('#' in resto || '*' in resto || CIFRA.containsMatchIn(seg[lp])) gruppi.getOrPut(seg.size to resto) { mutableListOf() }.add(i)
                }
            }
            val g = gruppi.values.maxByOrNull { it.size }
            if (g != null && g.size >= 3) return spunta(g.toSet())
        }
        // 3) uguali tranne un numero o un codice
        val altri = LinkedHashMap<Chiave, MutableList<Int>>()
        forme.forEachIndexed { i, f ->
            if ((f.cartella + f.coda).any { it == '#' || it == '*' }) altri.getOrPut(Chiave(f.host, f.cartella, f.coda)) { mutableListOf() }.add(i)
        }
        scegli(altri, 3, false)?.let { return spunta(altri[it]!!.toSet()) }
        // 4) una cartella che è più di metà della pagina
        val cartelle = LinkedHashMap<Triple<String, String, Int>, MutableList<Int>>()
        segs.forEachIndexed { i, seg -> cartelle.getOrPut(Triple(forme[i].host, cartella(seg.dropLast(1)), seg.size)) { mutableListOf() }.add(i) }
        val g = cartelle.values.maxByOrNull { it.size }!!
        if (g.size >= 10 && g.size * 2 > pagine.size) return spunta(g.toSet())
        return spunta(emptySet())
    }

    fun cercaPagine(link: String): List<Pagina> {
        val (html, base) = leggiPagina(link)
        val p = indovina(trovaPagine(html, base), base)
        if (p.isEmpty()) throw GrabError("This page has no links to other pages of the same site.")
        return p
    }

    /**
     * Le pagine scelte → salva(sottocartella, foto) per ognuna: «01 chapter-1», «02 …». Le foto che
     * compaiono in più pagine (logo, banner) sono l'arredo del sito e restano fuori.
     */
    /** Capitoli nello stesso momento (modo «più pagine»); il sito che dice «rallenta» ne toglie. */
    const val INSIEME = 8   // 8 anche dopo: la velocità la vuole chi lo usa (10/11), la batteria la mangiava lo schermo acceso

    fun scaricaPagine(
        pagine: List<Pagina>,
        piccola: (ByteArray) -> Boolean,
        salva: (String, List<Foto>) -> Unit,
        avanzamento: (Int, Int, String) -> Unit = { _, _, _ -> },
        stop: AtomicBoolean = AtomicBoolean(false),
        paralleli: Int = 6,
        insieme: Int = INSIEME,
    ): Risultato {
        val scelte = pagine.filter { it.scelta }
        if (scelte.isEmpty()) throw GrabError("No pages selected.")
        val ris = Risultato()
        val n = scelte.size
        val cifre = maxOf(2, n.toString().length)
        // 1) le prime pagine (al massimo 3) si leggono subito, insieme: una foto che compare in più
        //    di una è l'arredo del sito e resta fuori.
        fun leggi(p: Pagina): Any = try { conRiprova(stop) { leggiPagina(p.url) } } catch (e: GrabError) { e }
        val baseRallenta = RALLENTA.get()   // il freno conta i «troppo in fretta» da qui in poi
        val lette = java.util.Collections.synchronizedMap(java.util.IdentityHashMap<Pagina, Any>())
        val prime = Executors.newFixedThreadPool(minOf(maxOf(1, insieme), 3))
        for (p in scelte.take(3)) prime.execute { if (!stop.get()) lette[p] = leggi(p) }
        prime.shutdown(); prime.awaitTermination(1, TimeUnit.HOURS)
        val campione = scelte.take(3).mapNotNull { lette[it] as? Pair<*, *> }.map { it.first as String to it.second as String }
        val conta = HashMap<String, Int>()
        for ((h, b) in campione) for (u in trovaImmagini(h, b).map { it[0] }.toSet()) conta[u] = (conta[u] ?: 0) + 1
        val arredo = if (campione.size >= 2) conta.filterValues { it >= 2 }.keys else emptySet()
        ris.arredo = arredo.size
        ris.arredoSet = arredo
        val altre = pagine.map { pulito(it.url) }.toSet()   // la freccia non salta in un altro capitolo dell'elenco
        // il numero della cartella è il posto della pagina nell'elenco, qualunque finisca prima
        fun sottocartella(p: Pagina, i: Int): String { val s = i.toString().padStart(cifre, '0'); return nomeCartellaPulito("$s ${p.nome.replace('/', ' ')}").ifEmpty { s } }

        // 2) `insieme` pagine alla volta: leggi, scarica, salva. Ogni pagina si salva appena è pronta.
        val inCorso = java.util.TreeMap<Int, String>()
        var finite = 0
        val lock = Object()
        fun di(i: Int, m: String?) {
            val (f, testo) = synchronized(lock) {
                if (m != null) inCorso[i] = m
                finite to when {
                    inCorso.size == 1 -> "Page ${inCorso.firstKey()} of $n · ${inCorso.firstEntry()!!.value}"
                    inCorso.isNotEmpty() -> "Pages ${inCorso.keys.joinToString(", ")} of $n · $finite done"
                    else -> "$finite of $n done"
                }
            }
            avanzamento(f, n, testo)
        }
        // il freno: ogni 429 da quando siamo partiti toglie un capitolo a quelli insieme, mai sotto uno
        val porta = Object()
        var attivi = 0
        fun limite() = maxOf(1, insieme - (RALLENTA.get() - baseRallenta))
        fun entra() = synchronized(porta) { while (attivi >= limite() && !stop.get()) porta.wait(1000); attivi++ }   // esci() sveglia; il tempo serve solo a sentire «stop»
        fun esci() = synchronized(porta) { attivi--; porta.notifyAll() }
        fun una(i: Int, p: Pagina): Risultato {
            val r = Risultato(ris.impronte).also { it.arredoSet = arredo }
            entra()
            if (stop.get()) { esci(); return r }
            val rel = sottocartella(p, i)
            di(i, "reading…")
            try {
                val letta = lette.remove(p) ?: leggi(p)
                if (letta is GrabError) {
                    if (!stop.get()) { r.pagineFallite.add(p.nome); r.mancanti.add(Mancante(rel, listOf(p.url), pagina = p)) }
                    return r
                }
                @Suppress("UNCHECKED_CAST") val hb = letta as Pair<String, String>
                val (h, b) = hb
                val (scritte, numeri) = giroPagine(h, b, rel, r, piccola, salva, { _, _, m -> di(i, m) }, stop, paralleli, "", arredo, evita = altre)
                r.salvate = scritte
                if (numeri > 0) r.pagineFatte = 1 else if (!stop.get()) r.pagineVuote.add(p.nome)
                return r
            } finally {
                esci()
                synchronized(lock) { inCorso.remove(i); finite++ }
                di(i, null)
            }
        }
        avanzamento(0, n, "Page 1 of $n · reading…")
        val pool = Executors.newFixedThreadPool(maxOf(1, insieme))
        val futuri = scelte.mapIndexed { k, p -> pool.submit(java.util.concurrent.Callable { una(k + 1, p) }) }
        pool.shutdown(); pool.awaitTermination(24, TimeUnit.HOURS)
        var errore: Throwable? = null
        for (f in futuri) {                     // nell'ordine dell'elenco, non in quello d'arrivo
            try { ris.aggiungi(f.get()) } catch (e: java.util.concurrent.ExecutionException) { if (errore == null) errore = e.cause ?: e }
        }
        errore?.let { throw it }
        ris.annullato = stop.get()
        ris.rallentato = limite() < insieme
        if (!ris.annullato && ris.salvate == 0) throw GrabError("None of the selected pages had pictures to save.")
        avanzamento(n, n, "Saved")
        return ris
    }

    /**
     * Il bottone «Try the missing ones again»: riprende solo ciò che manca e mette ogni foto al suo
     * posto (il numero lasciato libero). Torna quante foto sono arrivate; ris.mancanti tiene il resto.
     */
    fun riprovaMancanti(
        ris: Risultato,
        piccola: (ByteArray) -> Boolean,
        salva: (String, List<Foto>) -> Unit,
        avanzamento: (Int, Int, String) -> Unit = { _, _, _ -> },
        stop: AtomicBoolean = AtomicBoolean(false),
    ): Int {
        val vecchi = ris.mancanti.toList(); ris.mancanti.clear()
        val ancora = mutableListOf<Mancante>()
        var arrivate = 0
        val md = MessageDigest.getInstance("SHA-256")
        for ((k, m) in vecchi.withIndex()) {
            if (stop.get()) { ancora.addAll(vecchi.drop(k)); break }
            if (m.morta) { ancora.add(m); continue }            // link morto sul sito: riprovare non serve
            avanzamento(k, vecchi.size, "Trying again ${k + 1} of ${vecchi.size}…")
            val pg = m.pagina
            if (pg != null) {                                   // una pagina intera
                val hb = try { conRiprova(stop) { leggiPagina(m.urls[0]) } } catch (e: GrabError) { ancora.add(m); continue }
                val posti = raccogli(hb.first, hb.second, ris, ris.impronte, piccola, { _, _, _ -> }, stop, 6, "", ris.arredoSet)
                if (posti.isNotEmpty()) {
                    val prima = ris.mancanti.size
                    arrivate += scriviCartella(m.cartella, posti, ris, hb.second, salva)
                    ancora.addAll(ris.mancanti.drop(prima)); while (ris.mancanti.size > prima) ris.mancanti.removeAt(ris.mancanti.size - 1)
                    ris.pagineFatte++
                }
                ris.pagineFallite.remove(pg.nome)
                continue
            }
            var d: ByteArray? = null                            // una foto sola
            var morte = 0
            for (u in m.urls) {
                val x = try { conRiprova(stop, 2) { scarica(u, m.referer, stop) } } catch (e: Exception) {
                    if ((e as? GrabError)?.morta == true) morte++; null } ?: continue
                if (estensione(x) != null) { d = x; break }
            }
            if (d == null) {
                m.morta = m.urls.isNotEmpty() && morte == m.urls.size && !stop.get()   // ora il sito dice 404/410
                ancora.add(m); continue
            }
            val imp = md.digest(d).joinToString("") { "%02x".format(it) }
            val nuova = synchronized(ris.impronte) {
                if (imp in ris.impronte || piccola(d)) false else { ris.impronte.add(imp); true }
            }
            if (!nuova) continue                               // doppione o icona: il posto resta vuoto
            val ext = estensione(d)!!
            salva(m.cartella, listOf(Foto(m.stem + ext, d, ext)))
            arrivate++
        }
        ris.mancanti.clear(); ris.mancanti.addAll(ancora)
        ris.salvate += arrivate
        val ancoraUrl = ancora.filter { it.pagina == null }.map { it.urls[0].take(200) }.toSet()
        ris.fallite.retainAll { it.first in ancoraUrl }
        avanzamento(vecchi.size, vecchi.size, "Saved")
        return arrivate
    }

    /** Il bottone «Try the missing ones again» serve solo se manca qualcosa che non è un link morto. */
    fun daRiprovare(r: Risultato): Boolean = r.mancanti.any { !it.morta }

    /** Le righe sui buchi, a parte: foto mancanti (si riprova) e link morti sul sito (niente da riprovare). */
    fun righeMancanti(r: Risultato, corte: Boolean = false): List<String> {
        val out = mutableListOf<String>()
        val foto = r.mancanti.count { it.pagina == null && !it.morta }
        val morte = r.mancanti.count { it.morta }
        if (foto > 0) out.add(when {
            corte -> "$foto missing"
            foto == 1 -> "1 picture missing (its number is left free)"
            else -> "$foto pictures missing (their numbers are left free)"
        })
        if (morte > 0) out.add(when {
            corte -> if (morte == 1) "1 dead link on the site" else "$morte dead links on the site"
            morte == 1 -> "1 dead link on the site (the site removed it, nothing to retry)"
            else -> "$morte dead links on the site (the site removed them, nothing to retry)"
        })
        return out
    }

    fun nomeFile(n: Int, totale: Int, ext: String): String {
        val cifre = maxOf(3, totale.toString().length)
        return n.toString().padStart(cifre, '0') + ext
    }
}
