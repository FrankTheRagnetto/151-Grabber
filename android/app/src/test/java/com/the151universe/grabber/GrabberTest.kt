package com.the151universe.grabber

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Lo stesso sito finto di autotest.py: le foto hanno un colore ciascuna per riconoscerle. */
class GrabberTest {
    companion object {
        private fun r(n: String) = GrabberTest::class.java.getResourceAsStream("/sito/$n")!!.use { it.readBytes() }

        val FILE = linkedMapOf(
            "/a.jpg" to r("a.jpg"), "/b-small.jpg" to r("b-small.jpg"), "/b-big.jpg" to r("b-big.jpg"),
            "/c-thumb.jpg" to r("c-thumb.jpg"), "/c-full.png" to r("c-full.png"), "/d.webp" to r("d.webp"),
            "/e.jpg" to r("e.jpg"), "/f.png" to r("f.png"),
            "/File:scheda.jpg" to "<html>pagina che parla della foto</html>".toByteArray(),
            "/i-mostrata.jpg" to r("i-mostrata.jpg"), "/pixel.gif" to r("pixel.gif"),
            "/logo.svg" to "<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray(),
            "/script.jpg" to r("script.jpg"), "/h.jpg" to r("h.jpg"),
        ).also { it["/g-copia.jpg"] = it["/a.jpg"]!! }

        const val PAGINA = """<!doctype html><html><head><title>Prova</title>
<script>var x = '<img src="/script.jpg">';</script></head><body>
<img src="a.jpg" alt="uno">
<img src="b-small.jpg" srcset="b-small.jpg 100w, b-big.jpg 800w">
<a href="/c-full.png"><img src="/c-thumb.jpg"></a>
<picture><source srcset="/d.webp" type="image/webp"><img src="/d.webp"></picture>
<img src="data:image/gif;base64,R0lGODlhAQABAAAAACw=" data-src="/e.jpg">
<div style="background-image: url('/f.png')"></div>
<a href="/File:scheda.jpg"><img src="/i-mostrata.jpg"></a>
<img src="/a.jpg#dopo">
<img src="/g-copia.jpg">
<img src="/pixel.gif"><img src="/logo.svg"><img src="/manca.jpg">
<svg><filter id="b"/></svg><div style="filter:url(#b)"></div>
<noscript><img src="/script.jpg"></noscript>
<img src="/h.jpg">
</body></html>"""
        val ATTESI = listOf("/a.jpg", "/b-big.jpg", "/c-full.png", "/d.webp", "/e.jpg", "/f.png", "/i-mostrata.jpg", "/h.jpg")

        // sito con le foto su un altro host: le pagine del capitolo su un altro host (localhost) che vuole come
        // Referer solo la radice del sito; in coda le miniature «consigliati» (nomi senza numero)
        val refererRifiutati: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val PAGINE_KK = listOf("a.jpg", "b-big.jpg", "c-full.png", "e.jpg", "h.jpg", "i-mostrata.jpg")
        val SLUG = listOf("the-red-club", "a-long-day-by-the-seas", "paper-boats-at-noon", "write-a-name-card",
            "the-sleepy-magicians", "early-raindrops", "the-runaway-baker")
        init {
            PAGINE_KK.forEachIndexed { n, f -> FILE["/kk/45/$n.${f.substringAfter('.')}"] = r(f) }
            SLUG.forEachIndexed { k, sl -> FILE["/thumb/$sl.jpg"] = r(PAGINE_KK[k % PAGINE_KK.size]) + byteArrayOf(k.toByte()) }
        }

        val uaVisti: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        // più pagine: banner = c-full.png su ogni capitolo (arredo), foto proprie per capitolo
        val HTML = mutableMapOf(
            "/indice" to """<html><body><nav><a href="/">Home</a> <a href="/about">About</a> <a href="/login?next=/indice">Login</a></nav>
<a href="#top">top</a> <a href="mailto:x@y.z">mail</a> <a href="https://altro.example/manga/x/chapter-9">fuori</a>
<a href="/manga/x/cover.jpg">cover</a>
<ul><li><a href="/manga/x/chapter-1">Ch 1</a></li><li><a href="/manga/x/chapter-2">Ch 2</a></li>
<li><a href="/manga/x/chapter-2#commenti">Ch 2 again</a></li><li><a href="/manga/x/chapter-3/">Ch 3</a></li>
<li><a href="/manga/x/chapter-4">Ch 4 (vuoto)</a></li></ul>
<a href="/indice">questa</a></body></html>""",
            "/manga/x/chapter-1" to """<img src="/c-full.png"><img src="/a.jpg"><img src="/b-big.jpg">""",
            "/manga/x/chapter-2" to """<img src="/c-full.png"><img src="/e.jpg"><img src="/f.png">""",
            "/manga/x/chapter-3/" to """<img src="/c-full.png"><img src="/h.jpg"><img src="/i-mostrata.jpg">""",
            "/manga/x/chapter-4" to "<p>niente foto</p>",
            "/about" to """<img src="/script.jpg">""",
            "/login" to "<form></form>",
            "/buchi" to """<img src="/a.jpg"><img src="/e.jpg"><img src="/h.jpg">""",
            "/morta" to """<img src="/a.jpg"><img src="/sparita.jpg"><img src="/h.jpg">""",   // sparita.jpg: 404
            "/doppia" to """<img src="/b-small.jpg"><img src="/b-big.jpg"><img src="/h.jpg">""",
            "/" to "<p>home</p>",
        )
        val INCIAMPI = HashMap<String, Int>()   // percorso -> quante volte rispondere male prima di rispondere bene
        val CHIESTE = java.util.concurrent.ConcurrentHashMap<String, Int>()   // percorso -> quante volte chiesto
        val LENTE = java.util.concurrent.ConcurrentHashMap<String, Long>()    // percorso -> ms di attesa prima di rispondere
        val TROPPO = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()   // pagine che inciampando dicono 429
        val aperte = java.util.concurrent.atomic.AtomicInteger(0)
        val maxAperte = java.util.concurrent.atomic.AtomicInteger(0)
        lateinit var srv: ServerSocket
        lateinit var sito: String

        /** Un server HTTP minimo (gli unit test vedono solo java.net, non jdk.httpserver). */
        @BeforeClass @JvmStatic fun accendi() {
            srv = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            sito = "http://127.0.0.1:${srv.localPort}"
            val altro = "http://localhost:${srv.localPort}"
            HTML["/manga/racconto/chapter-45"] = """<html><body><header><img src="/d.webp"></header><div class="container-chapter-reader">""" +
                PAGINE_KK.indices.joinToString("") { n -> "<img src='$altro/kk/45/$n.${PAGINE_KK[n].substringAfter('.')}' loading='lazy'>" } +
                """</div><div class="consigliati"><h2>Recommended</h2>""" +
                SLUG.joinToString("") { sl -> """<div class="item"><a href="/manga/$sl"><img src="$altro/thumb/$sl.jpg"></a><h3>$sl</h3></div>""" } +
                "</div></body></html>"
            thread(isDaemon = true) {
                while (!srv.isClosed) {
                    val s = try { srv.accept() } catch (e: Exception) { break }
                    thread(isDaemon = true) {
                        s.use { sock ->
                            var referer: String? = null
                            val riga = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1).let { br ->
                                val prima = br.readLine() ?: ""
                                while (true) {
                                    val l = br.readLine() ?: break; if (l.isEmpty()) break
                                    if (l.startsWith("User-Agent:", ignoreCase = true)) uaVisti.add(l.substringAfter(':').trim())
                                    if (l.startsWith("Referer:", ignoreCase = true)) referer = l.substringAfter(':').trim()
                                }
                                prima
                            }
                            val intero = java.net.URLDecoder.decode(riga.split(" ").getOrElse(1) { "/" }, "UTF-8")
                            val p = if (HTML.containsKey(intero)) intero else intero.substringBefore('?')
                            CHIESTE.merge(p, 1, Int::plus)
                            LENTE[p]?.let { ms ->
                                val ora = aperte.incrementAndGet(); maxAperte.accumulateAndGet(ora, ::maxOf)
                                Thread.sleep(ms); aperte.decrementAndGet()
                            }
                            val inciampa = synchronized(INCIAMPI) { (INCIAMPI[p] ?: 0).let { if (it > 0) { INCIAMPI[p] = it - 1; true } else false } }
                            val rifiuta = p.startsWith("/kk/") && referer != "$sito/"
                            if (rifiuta) refererRifiutati.add(referer ?: "(nessuno)")
                            val (code, corpo, tipo) = when {
                                rifiuta -> Triple(403, "no".toByteArray(), "text/plain")
                                inciampa -> Triple(if (p.endsWith(".jpg") || p in TROPPO) 429 else 503, "no".toByteArray(), "text/plain")
                                p == "/pagina" -> Triple(200, PAGINA.toByteArray(), "text/html; charset=utf-8")
                                HTML.containsKey(p) -> Triple(200, HTML[p]!!.toByteArray(), "text/html; charset=utf-8")
                                p == "/vuota" -> Triple(200, "<html><body><p>niente</p></body></html>".toByteArray(), "text/html")
                                FILE.containsKey(p) -> Triple(200, FILE[p]!!, "application/octet-stream")
                                else -> Triple(404, "no".toByteArray(), "text/plain")
                            }
                            val out = sock.getOutputStream()
                            out.write("HTTP/1.1 $code X\r\nRetry-After: 0\r\nContent-Type: $tipo\r\nContent-Length: ${corpo.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(corpo); out.flush()
                        }
                    }
                }
            }
        }

        @AfterClass @JvmStatic fun spegni() { srv.close() }

        /** Sul telefono decide BitmapFactory; qui basta riconoscere il pixel spia. */
        fun piccolaJvm(d: ByteArray) = d.contentEquals(FILE["/pixel.gif"]!!)

        fun sha(d: ByteArray) = MessageDigest.getInstance("SHA-256").digest(d).joinToString("") { "%02x".format(it) }
    }

    @Test fun ordineGrandeRiservaENienteIcone() {
        var salvate: List<Foto> = emptyList()
        val r = Grabber.scaricaTutto("$sito/pagina", ::piccolaJvm, { rel, f -> assertEquals("", rel); salvate = f })
        assertEquals("quante", 8, salvate.size)
        // manca.jpg (404) non arriva: il suo numero (008) resta libero, h.jpg è 009
        assertEquals(listOf("001.jpg", "002.jpg", "003.png", "004.webp", "005.jpg", "006.png", "007.jpg", "009.jpg"), salvate.map { it.first })
        assertEquals(listOf("008"), r.mancanti.map { it.stem })
        assertEquals("manca.jpg risponde 404: link morto", listOf(true), r.mancanti.map { it.morta })
        ATTESI.forEachIndexed { i, a -> assertEquals("posto ${i + 1} non è $a", sha(FILE[a]!!), sha(salvate[i].second)) }
        assertTrue("doppie", r.doppie >= 1)
        assertEquals("icone", 1, r.piccole)
        assertTrue("manca.jpg fra le fallite", r.fallite.any { it.first.contains("manca.jpg") })
        assertTrue("la scheda non è un fallimento", r.fallite.none { it.first.contains("scheda") })
        assertTrue("url(#b) non cercato", r.fallite.none { it.first.contains("%23b") || it.first.endsWith("#b") })
    }

    /** un sito vero: foto su un altro host = Referer solo radice; solo le pagine del lettore, niente miniature. */
    @Test fun lettoreEAltroHost() {
        assertEquals("https://a.it/m/c-1", Grabber.refererPer("https://a.it/m/c-1", "https://a.it/i/1.jpg"))
        assertEquals("https://www.a.it/", Grabber.refererPer("https://www.a.it/m/c-1?p=2", "https://cdn.b.com/1.jpg"))
        assertEquals("http://a.it:81/", Grabber.refererPer("http://a.it:81/m", "http://a.it:82/1.jpg"))
        assertEquals(null, Grabber.refererPer("https://a.it/m", "http://cdn.b.com/1.jpg"))
        refererRifiutati.clear()
        var salvate: List<Foto> = emptyList()
        val r = Grabber.scaricaTutto("$sito/manga/racconto/chapter-45", ::piccolaJvm, { _, f -> salvate = f })
        assertEquals("Referer rifiutati: $refererRifiutati", 0, refererRifiutati.size)
        assertTrue("fallite: ${r.fallite}", r.fallite.isEmpty())
        assertEquals("solo le 6 pagine, in ordine", PAGINE_KK.map { sha(r(it)) }, salvate.map { sha(it.second) })
        SLUG.forEach { assertEquals("miniatura chiesta: $it", null, CHIESTE["/thumb/$it.jpg"]) }
    }

    /** Strisce di fondo certi siti: la vera si cuce; doppia, finta, bianco su bianco e pagina intera no. */
    @Test fun strisceCucite() {
        val dettaglio = IntArray(400) { (it * 7) % 256 }
        val bianco = IntArray(400) { 255 }
        val finta = IntArray(400) { if (it % 2 == 0) 0 else 255 }
        // nome -> (larghezza, altezza, riga in cima, riga in fondo)
        val foto = linkedMapOf(
            "uno" to listOf(400, 600, finta, dettaglio), "uno-fondo" to listOf(400, 50, dettaglio, finta),
            "doppia" to listOf(800, 600, finta, finta), "due" to listOf(400, 600, finta, finta),
            "tre" to listOf(400, 600, dettaglio, dettaglio), "tre-finta" to listOf(400, 50, finta, finta),
            "bianca" to listOf(400, 600, bianco, bianco), "bianca-fondo" to listOf(400, 50, bianco, bianco),
            "quattro" to listOf(400, 600, finta, dettaglio), "cinque" to listOf(400, 600, dettaglio, finta),
        )
        val perSha = HashMap<String, List<Any>>()
        foto.keys.forEachIndexed { k, n ->
            val b = r("a.jpg") + "striscia-$n-$k".toByteArray()
            FILE["/st/$n.jpg"] = b; perSha[sha(b)] = foto[n]!!
        }
        HTML["/strisce"] = foto.keys.joinToString("") { """<img src="/st/$it.jpg">""" }
        Grabber.cucitore = object : Cucitore {
            override fun misura(d: ByteArray) = perSha[sha(d)]?.let { (it[0] as Int) to (it[1] as Int) }
            override fun riga(d: ByteArray, y: Int): IntArray? = perSha[sha(d)]?.let { if (y == 0) it[2] as IntArray else it[3] as IntArray }
            override fun unisci(sopra: ByteArray, sotto: ByteArray) = sopra + sotto
        }
        try {
            var salvate: List<Foto> = emptyList()
            val r = Grabber.scaricaTutto("$sito/strisce", ::piccolaJvm, { _, f -> salvate = f })
            assertEquals("cucite", 1, r.strisce)
            assertEquals("uno+fondo, doppia, due, tre, tre-finta, bianca, bianca-fondo, quattro, cinque", 9, salvate.size)
            assertEquals("la prima è uno con sotto la sua striscia", sha(FILE["/st/uno.jpg"]!! + FILE["/st/uno-fondo.jpg"]!!), sha(salvate[0].second))
            val resto = listOf("doppia", "due", "tre", "tre-finta", "bianca", "bianca-fondo", "quattro", "cinque")
            assertEquals(resto.map { sha(FILE["/st/$it.jpg"]!!) }, salvate.drop(1).map { sha(it.second) })
        } finally { Grabber.cucitore = null }
    }

    /** Il sito non deve sapere che telefono è: niente modello, niente versione vera. */
    @Test fun identitaGenerica() {
        Grabber.scaricaTutto("$sito/pagina", ::piccolaJvm, { _, _ -> })
        assertTrue("nessuna richiesta vista", uaVisti.isNotEmpty())
        for (ua in uaVisti) {
            assertTrue("modello nel UA: $ua", !Regex("SM-|Samsung|Build/", RegexOption.IGNORE_CASE).containsMatchIn(ua))
            assertTrue("UA non generico: $ua", ua.contains("(Linux; Android 10; K)"))
        }
    }

    @Test fun piuPagine() {
        val pag = Grabber.cercaPagine("$sito/indice")
        assertEquals(listOf("home", "about", "login?next=/indice", "chapter-1", "chapter-2", "chapter-3", "chapter-4"), pag.map { it.nome })
        assertEquals(listOf("chapter-1", "chapter-2", "chapter-3", "chapter-4"), pag.filter { it.scelta }.map { it.nome })
        pag.first { it.nome == "chapter-2" }.scelta = false
        val scritte = java.util.concurrent.ConcurrentHashMap<String, List<Foto>>()
        val r = Grabber.scaricaPagine(pag, ::piccolaJvm, { sotto, foto -> scritte[sotto] = foto })
        assertEquals(listOf("01 chapter-1", "02 chapter-3"), scritte.keys.sorted())
        assertEquals("banner fuori, ordine giusto", listOf(sha(FILE["/a.jpg"]!!), sha(FILE["/b-big.jpg"]!!)), scritte["01 chapter-1"]!!.map { sha(it.second) })
        assertEquals(listOf(sha(FILE["/h.jpg"]!!), sha(FILE["/i-mostrata.jpg"]!!)), scritte["02 chapter-3"]!!.map { sha(it.second) })
        assertEquals(1, r.arredo)
        assertEquals(listOf("chapter-4"), r.pagineVuote)
        assertEquals(2, r.pagineFatte)
    }

    /** Più capitoli nello stesso momento: il sito ne vede 3 insieme, le cartelle restano nell'ordine dell'elenco. */
    @Test fun capitoliInsieme() {
        val colori = listOf("/a.jpg", "/e.jpg", "/h.jpg", "/f.png", "/i-mostrata.jpg", "/b-big.jpg")
        for (k in 1..6) { HTML["/serie/chapter-$k"] = """<img src="${colori[k - 1]}?serie">""" }
        for (c in colori) LENTE[c] = 300   // lente le foto, non le pagine: le prime 3 pagine si leggono insieme comunque
        HTML["/serie/elenco"] = (1..6).joinToString("") { """<a href="/serie/chapter-$it">$it</a>""" }
        val pag = Grabber.cercaPagine("$sito/serie/elenco")
        maxAperte.set(0)
        val scritte = java.util.concurrent.ConcurrentHashMap<String, List<Foto>>()
        val r = Grabber.scaricaPagine(pag, ::piccolaJvm, { sotto, foto -> scritte[sotto] = foto })
        assertEquals("INSIEME capitoli insieme", minOf(6, Grabber.INSIEME), maxAperte.get())
        assertEquals((1..6).map { "0$it chapter-$it" }, scritte.keys.sorted())
        for (k in 1..6) assertEquals("capitolo $k", sha(FILE[colori[k - 1]]!!), sha(scritte["0$k chapter-$k"]!![0].second))
        assertEquals(6, r.pagineFatte); assertEquals(6, r.salvate)
        // fermato a metà: quello finito resta, il resto non parte
        val ferma = AtomicBoolean(false)
        val salvate = java.util.Collections.synchronizedList(mutableListOf<String>())
        val r2 = Grabber.scaricaPagine(pag, ::piccolaJvm, { sotto, _ -> salvate.add(sotto) },
            avanzamento = { f, _, _ -> if (f >= 1) ferma.set(true) }, stop = ferma, insieme = 3)   // con 8 partirebbero tutti e 6
        assertTrue(r2.annullato); assertTrue("salvate $salvate", salvate.size in 1..5)
        LENTE.clear()
    }

    /** Il sito dice «troppo in fretta» (429) sulle prime 3 pagine: da lì vanno 8 - 3 = 5 capitoli insieme. */
    @Test fun frenoSeIlSitoDiceRallenta() {
        Grabber.ATTESE_MS = longArrayOf(50, 50, 50)
        val foto = listOf("/a.jpg", "/e.jpg", "/h.jpg", "/f.png", "/i-mostrata.jpg", "/b-big.jpg")
        for (k in 1..12) HTML["/fretta/chapter-$k"] = """<img src="${foto[(k - 1) % 6]}?f$k">"""
        for (c in foto) LENTE[c] = 300
        HTML["/fretta/elenco"] = (1..12).joinToString("") { """<a href="/fretta/chapter-$it">$it</a>""" }
        val pag = Grabber.cercaPagine("$sito/fretta/elenco")
        for (k in 1..3) { TROPPO.add("/fretta/chapter-$k"); synchronized(INCIAMPI) { INCIAMPI["/fretta/chapter-$k"] = 1 } }
        maxAperte.set(0)
        val r = Grabber.scaricaPagine(pag, ::piccolaJvm, { _, _ -> }, insieme = 8)
        assertEquals("frenato a 5", 5, maxAperte.get()); assertTrue(r.rallentato)
        maxAperte.set(0)
        val r2 = Grabber.scaricaPagine(pag, ::piccolaJvm, { _, _ -> }, insieme = 8)
        assertEquals("senza freno 8", 8, maxAperte.get()); assertFalse(r2.rallentato)
        LENTE.clear(); TROPPO.clear()
    }

    @Test fun siRiprovaEilFattoResta() {
        Grabber.ATTESE_MS = longArrayOf(50, 50, 50)
        val pag = Grabber.cercaPagine("$sito/indice").onEach { it.scelta = it.nome == "chapter-1" || it.nome == "chapter-2" }
        synchronized(INCIAMPI) { INCIAMPI["/manga/x/chapter-1"] = 2; INCIAMPI["/e.jpg"] = 1 }
        var r = Grabber.scaricaPagine(pag, ::piccolaJvm, { _, _ -> })
        assertEquals("riprova da sola", 2, r.pagineFatte); assertTrue(r.pagineFallite.isEmpty() && r.fallite.isEmpty())
        synchronized(INCIAMPI) { INCIAMPI["/manga/x/chapter-1"] = 9 }
        r = Grabber.scaricaPagine(pag, ::piccolaJvm, { _, _ -> })
        assertEquals("si arrende e va avanti", listOf("chapter-1"), r.pagineFallite); assertEquals(1, r.pagineFatte)
        synchronized(INCIAMPI) { INCIAMPI.clear() }
        // fermata a metà: la pagina già fatta è già stata salvata
        pag.forEach { it.scelta = it.nome.startsWith("chapter") }
        val ferma = AtomicBoolean(false)
        val salvate = mutableListOf<String>()
        val totali = java.util.Collections.synchronizedSet(HashSet<Int>())
        r = Grabber.scaricaPagine(pag, ::piccolaJvm, { sotto, _ -> salvate.add(sotto) },
            avanzamento = { _, t, m -> totali.add(t); if (m.startsWith("Page 2 of")) ferma.set(true) }, stop = ferma, insieme = 1)
        assertTrue(r.annullato); assertEquals(listOf("01 chapter-1"), salvate)
        assertEquals("la barra conta le pagine", setOf(4), totali)
    }

    @Test fun buchiEBottone() {
        Grabber.ATTESE_MS = longArrayOf(50, 50, 50)
        // una foto che non arriva lascia il suo numero libero; il bottone la mette lì
        synchronized(INCIAMPI) { INCIAMPI["/e.jpg"] = 99 }
        val scritte = linkedMapOf<String, MutableList<Foto>>()
        val salva = { rel: String, f: List<Foto> -> synchronized(scritte) { scritte.getOrPut(rel) { mutableListOf() }.addAll(f) }; Unit }   // da più fili
        val r = Grabber.scaricaTutto("$sito/buchi", ::piccolaJvm, salva)
        assertEquals(listOf("001.jpg", "003.jpg"), scritte[""]!!.map { it.first })
        assertEquals(listOf("002"), r.mancanti.map { it.stem })
        assertEquals("429 è un inciampo, non un link morto", listOf(false), r.mancanti.map { it.morta })
        assertTrue("bottone visibile", Grabber.daRiprovare(r))
        assertEquals(listOf("1 picture missing (its number is left free)"), Grabber.righeMancanti(r))
        synchronized(INCIAMPI) { INCIAMPI.clear() }
        assertEquals(1, Grabber.riprovaMancanti(r, ::piccolaJvm, salva))
        assertEquals("002.jpg", scritte[""]!!.last().first)
        assertEquals(sha(FILE["/e.jpg"]!!), sha(scritte[""]!!.last().second))
        assertTrue(r.mancanti.isEmpty() && r.fallite.isEmpty())
        // una pagina intera che non si apre tiene il suo numero
        val pag = Grabber.cercaPagine("$sito/indice").onEach { it.scelta = it.nome in setOf("chapter-1", "chapter-2", "chapter-3") }
        synchronized(INCIAMPI) { INCIAMPI["/manga/x/chapter-2"] = 99 }
        scritte.clear()
        val r2 = Grabber.scaricaPagine(pag, ::piccolaJvm, salva)
        assertEquals(listOf("01 chapter-1", "03 chapter-3"), scritte.keys.sorted())   // salva arriva nell'ordine in cui finiscono
        synchronized(INCIAMPI) { INCIAMPI.clear() }
        Grabber.riprovaMancanti(r2, ::piccolaJvm, salva)
        assertEquals(listOf(sha(FILE["/e.jpg"]!!), sha(FILE["/f.png"]!!)), scritte["02 chapter-2"]!!.map { sha(it.second) })
        assertTrue(r2.pagineFallite.isEmpty() && r2.mancanti.isEmpty())
    }

    @Test fun linkMortoNienteBottone() {
        // il sito ha tolto la foto (404): numero libero, detto a parte, niente bottone, non si richiede
        val scritte = linkedMapOf<String, MutableList<Foto>>()
        val salva = { rel: String, f: List<Foto> -> synchronized(scritte) { scritte.getOrPut(rel) { mutableListOf() }.addAll(f) }; Unit }   // da più fili
        val r = Grabber.scaricaTutto("$sito/morta", ::piccolaJvm, salva)
        assertEquals(listOf("001.jpg", "003.jpg"), scritte[""]!!.map { it.first })
        assertEquals(listOf("002" to true), r.mancanti.map { it.stem to it.morta })
        assertFalse("niente bottone", Grabber.daRiprovare(r))
        assertEquals(listOf("1 dead link on the site (the site removed it, nothing to retry)"), Grabber.righeMancanti(r))
        assertEquals("2 images · 1 dead link on the site", riassunto(r))
        val prima = CHIESTE["/sparita.jpg"] ?: 0
        assertEquals(0, Grabber.riprovaMancanti(r, ::piccolaJvm, salva))
        assertEquals("il bottone non la richiede", prima, CHIESTE["/sparita.jpg"] ?: 0)
        assertEquals(listOf("002" to true), r.mancanti.map { it.stem to it.morta })
        assertTrue(r.fallite.any { it.first.contains("sparita.jpg") })
    }

    @Test fun gemelle() {
        assertEquals(Grabber.gemella("https://x/c/simulation_2x.png"), Grabber.gemella("https://x/c/simulation.png"))
        assertEquals(Grabber.gemella("https://x/foto-300x200.jpg?v=2"), Grabber.gemella("https://x/foto.jpg"))
        assertTrue(Grabber.gemella("https://x/a1.jpg") != Grabber.gemella("https://x/a2.jpg"))
        assertTrue(Grabber.gemella("https://x/123/large.jpg") != Grabber.gemella("https://x/456/large.jpg"))
        var foto: List<Foto> = emptyList()
        Grabber.scaricaTutto("$sito/doppia", ::piccolaJvm, { _, f -> foto = f })
        assertEquals("resta solo la grande", listOf(sha(FILE["/b-big.jpg"]!!), sha(FILE["/h.jpg"]!!)), foto.map { sha(it.second) })
    }

    @Test fun indovinaSenzaGruppo() {
        val p = Grabber.indovina(listOf("a", "b/c", "d/e/f").map { Pagina("$sito/$it", it) })
        assertTrue("nessun elenco chiaro: nessuna spunta", p.none { it.scelta })
        val d = Grabber.trovaPagine("""<a href="/a/cap-1">x</a><a href="/b/cap-1">y</a>""", "$sito/z")
        assertEquals(listOf("a/cap-1", "b/cap-1"), d.map { it.nome })
    }

    /**: indovina sulle STRUTTURE dei siti veri; gli stessi siti finti li prova autotest.py (resources/indovina/). */
    @Test fun indovinaStrutture() {
        val nomi = listOf("archivio-numeri", "blog", "capitoli-con-titolo", "capitoli-e-storie-extra", "capitolo-cartella-pagina",
            "capitolo-senza-elenco", "codice-e-testo", "due-capitoli-e-altre-serie", "episodi-col-titolo", "episodi-e-pagine-elenco",
            "forum", "home-tante-serie", "id-nella-cartella", "pagina-di-capitolo", "pagina-sola")
        val rossi = mutableListOf<String>()
        for (n in nomi) {
            val t = String(GrabberTest::class.java.getResourceAsStream("/indovina/$n.html")!!.use { it.readBytes() }, Charsets.UTF_8)
            val pag = Regex("<!-- pagina: (\\S+) -->").find(t)!!.groupValues[1]
            val attesi = Regex(Regex("<!-- attesi: (.*?) -->").find(t)!!.groupValues[1])
            val quanti = Regex("<!-- quanti: (\\d+) -->").find(t)!!.groupValues[1].toInt()
            val pp = Grabber.indovina(Grabber.trovaPagine(t, pag), pag)
            val sp = pp.filter { it.scelta }.map { it.url }
            val giusti = pp.filter { attesi.containsMatchIn(it.url) }.map { it.url }
            if (sp != giusti || sp.size != quanti) rossi.add("$n: ${sp.size} spuntati, attesi $quanti ${sp.filter { it !in giusti }.take(3)} mancati ${giusti.filter { it !in sp }.take(3)}")
        }
        assertEquals(nomi.size, java.io.File(GrabberTest::class.java.getResource("/indovina/blog.html")!!.toURI()).parentFile.list()!!.size)
        assertEquals("", rossi.joinToString("\n"))
    }

    @Test fun nomiFile() {
        assertEquals("001.jpg", Grabber.nomeFile(1, 8, ".jpg"))
        assertEquals("0042.png", Grabber.nomeFile(42, 1200, ".png"))
    }

    @Test fun nomeCartella() {
        assertEquals("a b c", Grabber.nomeCartellaPulito("a/b\\c\n"))
        assertEquals("", Grabber.nomeCartellaPulito(".."))
        assertEquals("", Grabber.nomeCartellaPulito("   "))
    }

    @Test fun paginaVuota() {
        var chiamata = false
        try { Grabber.scaricaTutto("$sito/vuota", ::piccolaJvm, { _, _ -> chiamata = true }); fail("nessun errore") }
        catch (e: GrabError) { }
        assertTrue("non scrive niente", !chiamata)
    }

    @Test fun annullaNonScrive() {
        var chiamata = false
        val r = Grabber.scaricaTutto("$sito/pagina", ::piccolaJvm, { _, _ -> chiamata = true }, stop = AtomicBoolean(true))
        assertTrue(r.annullato && !chiamata)
    }

    /** La freccia «avanti»: una foto per pagina, si sfoglia fino alla fine del capitolo, mai nel capitolo dopo. */
    @Test fun frecciaAvanti() {
        val l = "/lettore/x/chapter-5"
        val testa = """<img src="/c-full.png"><a href="/lettore/x/chapter-6">Ch 6</a><a href="/lettore/x/chapter-4">Ch 4</a>"""
        HTML[l] = testa + """<img src="/a.jpg"><a class="btn next-btn" href="$l/2"><i class="fa fa-chevron-right"></i></a><a href="/lettore/x/chapter-6">Next chapter</a>"""
        HTML["$l/2"] = testa + """<a href="$l/3"><img src="/b-big.jpg"></a><a href="$l">‹</a>"""
        HTML["$l/3"] = testa + """<img src="/e.jpg"><select><option value="$l/2">2</option><option selected value="$l/3">3</option><option value="$l/4">4</option></select>"""
        HTML["$l/4"] = """<html><head><link rel="next" href="$l/5"></head><body>$testa<img src="/f.png"></body></html>"""
        HTML["$l/5"] = testa + """<img src="/h.jpg"><a href="/lettore/x/chapter-6">Next ›</a><a href="/lettore/x/chapter-6/1">»</a>"""
        HTML["/lettore/x/chapter-6"] = """<img src="/script.jpg">"""
        HTML["/lettore/x/chapter-6/1"] = """<img src="/script.jpg">"""
        val scritte = mutableListOf<Foto>()
        val r = Grabber.scaricaTutto("$sito$l", ::piccolaJvm, { rel, f -> assertEquals("", rel); scritte.addAll(f) })
        assertEquals(listOf("001.jpg", "002.jpg", "003.jpg", "004.png", "005.jpg"), scritte.map { it.first })
        assertEquals("ordine", listOf("/a.jpg", "/b-big.jpg", "/e.jpg", "/f.png", "/h.jpg").map { sha(FILE[it]!!) }, scritte.map { sha(it.second) })
        assertEquals(5, r.sfogliate)
        assertEquals("", r.fermata)
        assertTrue("logo fuori", r.arredo >= 1)
        assertEquals("mai nel capitolo dopo", null, CHIESTE["/lettore/x/chapter-6"])
        assertEquals(null, CHIESTE["/lettore/x/chapter-6/1"])

        // ?page=N con «Next» scritto
        HTML["/q/read?ch=7"] = """<img src="/a.jpg"><a href="/q/read?ch=7&page=2">Next</a><a href="/q/read?ch=8">Next chapter</a>"""
        HTML["/q/read?ch=7&page=2"] = """<img src="/e.jpg"><a href="/q/read?ch=8">Next</a>"""
        HTML["/q/read?ch=8"] = """<img src="/script.jpg">"""
        val q = mutableListOf<Foto>()
        Grabber.scaricaTutto("$sito/q/read?ch=7", ::piccolaJvm, { _, f -> q.addAll(f) })
        assertEquals(listOf(sha(FILE["/a.jpg"]!!), sha(FILE["/e.jpg"]!!)), q.map { sha(it.second) })

        // capitolo intero su una pagina con «Next ›» al capitolo dopo: non si segue
        HTML["/manga/y/5"] = """<img src="/a.jpg"><img src="/e.jpg"><img src="/f.png"><img src="/h.jpg"><a class="next" href="/manga/y/6">›</a>"""
        HTML["/manga/y/6"] = """<img src="/script.jpg">"""
        val r2 = Grabber.scaricaTutto("$sito/manga/y/5", ::piccolaJvm, { _, _ -> })
        assertEquals(0, r2.sfogliate)
        assertEquals(null, CHIESTE["/manga/y/6"])

        // più pagine: ogni capitolo sfogliato nella sua cartella
        HTML["/lettore/elenco"] = """<a href="$l">5</a><a href="/lettore/x/chapter-4">4</a>"""
        HTML["/lettore/x/chapter-4"] = testa + """<img src="/i-mostrata.jpg">"""
        val cartelle = java.util.concurrent.ConcurrentHashMap<String, MutableList<Foto>>()
        Grabber.scaricaPagine(Grabber.cercaPagine("$sito/lettore/elenco"), ::piccolaJvm, { sotto, f -> synchronized(cartelle) { cartelle.getOrPut(sotto) { mutableListOf() }.addAll(f) } })
        assertEquals(listOf("01 chapter-5", "02 chapter-4"), cartelle.keys.sorted())
        assertEquals(5, cartelle["01 chapter-5"]!!.size)

        assertEquals(1L, Grabber.passo("https://s/c-5.html", "https://s/c-5-2.html"))
        assertEquals(null, Grabber.passo("https://s/c-5/9", "https://s/c-6/1"))
        assertFalse(Grabber.stessoCapitolo("https://s/series/123/chapter-5", "https://s/series/123/chapter-6", false))
        assertTrue(Grabber.stessoCapitolo("https://s/m/chapter-5/7", "https://s/m/chapter-5/8", false))
    }

    /** Link nascosti: menu a tendina, data-href, onclick, sottodomini dello stesso sito. */
    @Test fun linkNascosti() {
        val h = """<select onchange="go(this)"><option value="">Pick</option><option value="/m/chapter-1">Ch 1</option>""" +
            """<option value="https://w1.sito.com/m/chapter-2">Ch 2</option><option value="3">3</option></select>""" +
            """<div data-href="/m/chapter-3">Ch 3</div><button onclick="location.href='/m/chapter-4'">Ch 4</button>""" +
            """<a href="https://altro.example/m/chapter-9">x</a>"""
        assertEquals(listOf("chapter-1", "chapter-2", "chapter-3", "chapter-4"), Grabber.trovaPagine(h, "https://www.sito.com/m/indice").map { it.nome })
        assertEquals("sito.co.uk", Grabber.dominio("https://m.sito.co.uk/x"))
    }

    @Test fun srcset() {
        assertEquals("b.jpg", Grabber.migliorDaSrcset("a.jpg 1x, b.jpg 2x"))
        assertEquals("https://x/j.jpg", Grabber.migliorDaSrcset("https://x/i.jpg?w=1,2 100w, https://x/j.jpg 900w"))
    }
}
