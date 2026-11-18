package com.the151universe.grabber

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lo schermo. Due modi: «This page only» (una pagina → una cartella in Download) e
 * «Pages it links to» (la pagina dà i link, chi lo usa vede l'elenco con l'ultima parte del link,
 * toglie quelli che non vuole; cartella madre col nome scelto, dentro una cartella per pagina).
 * Ogni «Download» diventa una scheda sotto il modulo, e il modulo si svuota per il link dopo.
 */
class MainActivity : Activity() {
    private lateinit var link: EditText
    private lateinit var solo: RadioButton
    private lateinit var molte: RadioButton
    private lateinit var nome: EditText
    private lateinit var boxElenco: LinearLayout
    private lateinit var conta: TextView
    private lateinit var elenco: ListView
    private lateinit var vai: Button
    private lateinit var barra: ProgressBar
    private lateinit var esito: TextView
    private lateinit var titoloLista: TextView
    private lateinit var lista: LinearLayout
    private val schede = HashMap<Int, Scheda>()          // id del lavoro -> la sua scheda
    private var stop: AtomicBoolean? = null              // solo la ricerca delle pagine
    private var pagine: List<Pagina>? = null

    /** Le viste di uno scarico; si aggiornano sul posto, mai ridisegnate da capo. */
    private inner class Scheda(var lavoro: Corsa.Lavoro) {
        val radice = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat(); setStroke(dp(1), getColor(R.color.testo) and 0x33FFFFFF)
            }
        }
        val nome = TextView(this@MainActivity).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTypeface(typeface, android.graphics.Typeface.BOLD) }
        val barra = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal)
        val testo = TextView(this@MainActivity).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(0, dp(4), 0, dp(2)); setTextIsSelectable(true) }
        private fun bottone(t: String, f: () -> Unit) = Button(this@MainActivity, null, android.R.attr.borderlessButtonStyle).apply {
            text = t; minHeight = dp(48); setOnClickListener { f() }
        }
        val annulla = bottone("Cancel") { premiAnnulla(this) }
        val riprova = bottone("Try again") { premiRiprova(this) }
        val apri = bottone("Open") { apriGalleria(this) }
        val togli = bottone("Remove") { Corsa.togli(lavoro) }
        init {
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            radice.addView(nome, lp); radice.addView(barra, LinearLayout.LayoutParams(lp).apply { topMargin = dp(6) }); radice.addView(testo, lp)
            val riga = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.END }
            riga.addView(riprova); riga.addView(apri); riga.addView(annulla); riga.addView(togli)
            radice.addView(riga, lp)
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(22))
        }
        fun etichetta(t: String) = TextView(this).apply {
            text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(0, dp(14), 0, dp(4))
        }
        val lp = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }

        col.addView(TextView(this).apply {
            text = getString(R.string.app_name); setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "Paste a link: every image on that page goes into one folder in Downloads, numbered in the order the page shows them."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); alpha = 0.7f; setPadding(0, dp(6), 0, dp(6))
        })
        col.addView(etichetta("Link"))
        link = EditText(this).apply {
            hint = "https://…"; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_NEXT
            id = View.generateViewId()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { azzeraElenco() }
            })
        }
        col.addView(link, lp())

        solo = RadioButton(this).apply { text = "This page only"; id = View.generateViewId(); minHeight = dp(48) }
        molte = RadioButton(this).apply { text = "Pages it links to"; id = View.generateViewId(); minHeight = dp(48) }
        val modi = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(solo); addView(molte)
            check(solo.id)
            setOnCheckedChangeListener { _, _ -> azzeraElenco() }
        }
        col.addView(modi, lp().apply { topMargin = dp(6) })

        col.addView(etichetta("Folder name"))
        nome = EditText(this).apply {
            hint = "e.g. Rome trip"; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_GO) { premiVai(); true } else false }
            id = View.generateViewId()
        }
        col.addView(nome, lp())
        col.addView(TextView(this).apply {
            text = "Saved in Downloads → the folder name you type"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); alpha = 0.6f
            setPadding(0, dp(6), 0, 0)
        })

        // elenco delle pagine: solo nel modo «più pagine», dopo «Find pages»
        boxElenco = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val rigaConta = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        conta = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); alpha = 0.7f }
        rigaConta.addView(conta, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = android.view.Gravity.CENTER_VERTICAL })
        rigaConta.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply { text = "All"; setOnClickListener { spuntaTutte(true) } })
        rigaConta.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply { text = "None"; setOnClickListener { spuntaTutte(false) } })
        boxElenco.addView(rigaConta, lp())
        elenco = ListView(this).apply {
            choiceMode = AbsListView.CHOICE_MODE_MULTIPLE
            isNestedScrollingEnabled = true          // scorre dentro la pagina che scorre
            setOnItemClickListener { _, _, pos, _ ->
                pagine?.getOrNull(pos)?.scelta = isItemChecked(pos)
                aggiornaConta()
            }
        }
        boxElenco.addView(elenco, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(340)))
        col.addView(boxElenco, lp().apply { topMargin = dp(16) })

        vai = Button(this).apply {
            text = "Download images"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); minHeight = dp(56)
            setOnClickListener { premiVai() }
        }
        col.addView(vai, lp().apply { topMargin = dp(22) })
        barra = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { visibility = View.GONE }
        col.addView(barra, lp().apply { topMargin = dp(10) })
        esito = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setPadding(0, dp(10), 0, dp(4)); setTextIsSelectable(true) }
        col.addView(esito, lp())
        // gli scarichi: una scheda per ognuno, il più nuovo in cima
        titoloLista = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); alpha = 0.7f; setPadding(0, dp(14), 0, dp(6)); visibility = View.GONE }
        col.addView(titoloLista, lp())
        lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(lista, lp())

        // Android 15 disegna l'app anche sotto l'orologio e la barra in basso: il contenuto scorreva
        // sotto l'orologio (screenshot di chi lo usa, 08/11). Si lascia lo spazio delle barre di sistema.
        val radice = ScrollView(this).apply { addView(col); isFillViewport = true; clipToPadding = true }
        radice.setOnApplyWindowInsetsListener { v, ins ->
            val (sopra, sotto) = if (Build.VERSION.SDK_INT >= 30) {
                val b = ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                b.top to b.bottom
            } else @Suppress("DEPRECATION") (ins.systemWindowInsetTop to ins.systemWindowInsetBottom)
            v.setPadding(0, sopra, 0, sotto)
            ins
        }
        setContentView(radice)
    }

    // -- elenco
    private fun azzeraElenco() {
        if (stop != null) return
        pagine = null
        elenco.adapter = null
        boxElenco.visibility = View.GONE
        testoBottone()
    }

    private fun testoBottone() {
        vai.text = when {
            stop != null -> "Cancel"
            molte.isChecked && pagine == null -> "Find pages"
            molte.isChecked -> { val n = pagine!!.count { it.scelta }; "Download from $n page${if (n != 1) "s" else ""}" }
            else -> "Download images"
        }
    }

    private fun mostraElenco(p: List<Pagina>) {
        pagine = p
        elenco.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, p.map { it.nome })
        p.forEachIndexed { i, x -> elenco.setItemChecked(i, x.scelta) }
        boxElenco.visibility = View.VISIBLE
        aggiornaConta()
    }

    private fun spuntaTutte(si: Boolean) {
        val p = pagine ?: return
        p.forEachIndexed { i, x -> x.scelta = si; elenco.setItemChecked(i, si) }
        aggiornaConta()
    }

    private fun aggiornaConta() {
        val p = pagine ?: return
        conta.text = "${p.size} pages found · ${p.count { it.scelta }} selected"
        testoBottone()
    }

    // -- stato a schermo
    private fun mostra(t: String, errore: Boolean = false, ok: Boolean = false) {
        esito.text = t
        esito.setTextColor(getColor(when { errore -> R.color.errore; ok -> R.color.ok; else -> R.color.testo }))
    }

    /** Solo mentre cerca le pagine: il modulo è fermo. Gli scarichi non lo bloccano mai. */
    private fun occupato(si: Boolean) {
        link.isEnabled = !si; nome.isEnabled = !si; solo.isEnabled = !si; molte.isEnabled = !si; elenco.isEnabled = !si
        testoBottone()
        barra.visibility = if (si) View.VISIBLE else View.GONE
    }

    // -- il giro
    private fun premiVai() {
        stop?.let { it.set(true); vai.isEnabled = false; mostra("Cancelling…"); return }
        if (link.text.isBlank()) { mostra("Paste a link.", errore = true); return }
        val l = link.text.toString()

        if (molte.isChecked && pagine == null) {          // primo tocco: cercare le pagine
            val s = AtomicBoolean(false); stop = s
            barra.isIndeterminate = true
            mostra("Looking for pages…")
            occupato(true)
            Thread {
                try {
                    val p = Grabber.cercaPagine(l)
                    runOnUiThread {
                        fine()
                        if (!s.get()) { mostraElenco(p); mostra("Tap a page to tick or untick it, then press Download.") }
                        else mostra("Cancelled.")
                    }
                } catch (e: GrabError) {
                    runOnUiThread { fine(); mostra(e.message ?: "Error", errore = true) }
                } catch (e: Exception) {
                    runOnUiThread { fine(); mostra("Something went wrong: ${e.javaClass.simpleName}: ${e.message}", errore = true) }
                }
            }.start()
            return
        }

        val n = Grabber.nomeCartellaPulito(nome.text.toString())
        if (n.isEmpty()) { mostra("Type a name for the folder.", errore = true); return }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            mostra("Allow saving pictures, then press Download again.")
            return
        }
        if (Corsa.nomeInUso(n)) { mostra("Already downloading into “$n”: pick another name.", errore = true); return }
        if (Salva.giaPiena(this, n)) { mostra("A folder called “$n” already exists in Downloads: pick another name.", errore = true); return }
        val scelte = if (molte.isChecked) pagine else null
        if (scelte != null && scelte.none { it.scelta }) { mostra("Tick at least one page.", errore = true); return }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)   // la barra nelle notifiche; senza, scarica lo stesso
        Scarico.parti(this, Corsa.Richiesta(l, n, scelte?.map { Pagina(it.url, it.nome, it.scelta) }))
        // il modulo si svuota: si può incollare subito il link dopo
        link.setText(""); nome.setText("")
        azzeraElenco()
        mostra("“$n” started. You can paste another link now.", ok = true)
        link.requestFocus()
    }

    /** I lavori (che girano nel servizio) disegnati come schede, aggiornate sul posto. */
    private val ascolta: () -> Unit = { disegnaLavori() }

    private fun disegnaLavori() {
        val lavori = Corsa.lavori.toList()
        val ids = lavori.map { it.id }.toSet()
        for (id in schede.keys.filter { it !in ids }) schede.remove(id)?.let { lista.removeView(it.radice) }
        // il più nuovo in cima; un «riprova» prende il posto del suo vecchio lavoro
        lavori.reversed().forEachIndexed { i, l ->
            val sc = schede.getOrPut(l.id) { Scheda(l) }
            if (sc.radice.parent == null) lista.addView(sc.radice, i, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
            else if (lista.indexOfChild(sc.radice) != i) { lista.removeView(sc.radice); lista.addView(sc.radice, i) }
            aggiornaScheda(sc)
        }
        val vanno = lavori.count { it.stato is Corsa.Stato.Va }
        val attesa = lavori.count { it.stato is Corsa.Stato.Attesa }
        titoloLista.visibility = if (lavori.isEmpty()) View.GONE else View.VISIBLE
        titoloLista.text = "Downloads" + (if (vanno > 0) " · $vanno running" else "") + (if (attesa > 0) " · $attesa waiting" else "")
        // niente FLAG_KEEP_SCREEN_ON: lo scarico vive nel servizio col suo wakelock, lo schermo può spegnersi
    }

    private fun aggiornaScheda(sc: Scheda) {
        val l = sc.lavoro
        sc.nome.text = l.nome
        fun colore(errore: Boolean = false, ok: Boolean = false) =
            sc.testo.setTextColor(getColor(when { errore -> R.color.errore; ok -> R.color.ok; else -> R.color.testo }))
        when (val st = l.stato) {
            is Corsa.Stato.Attesa -> {
                sc.barra.visibility = View.VISIBLE; sc.barra.isIndeterminate = true
                sc.testo.text = "Waiting: another download will finish first…"; colore(); sc.testo.alpha = 0.7f
            }
            is Corsa.Stato.Va -> {
                sc.barra.visibility = View.VISIBLE
                sc.barra.isIndeterminate = st.totale == 0
                if (st.totale > 0) { sc.barra.max = st.totale; sc.barra.progress = st.fatte }
                sc.testo.text = if (l.stop.get()) "Cancelling…" else st.msg; colore(); sc.testo.alpha = 1f
            }
            is Corsa.Stato.Finito -> { sc.barra.visibility = View.GONE; sc.testo.alpha = 1f; sc.testo.text = testoFinito(st.ris, l.nome); colore(ok = !(st.ris.annullato && st.ris.salvate == 0)) }
            is Corsa.Stato.Errore -> { sc.barra.visibility = View.GONE; sc.testo.alpha = 1f; sc.testo.text = st.msg; colore(errore = true) }
        }
        val vivo = l.vivo
        sc.annulla.visibility = if (vivo) View.VISIBLE else View.GONE
        sc.annulla.isEnabled = !l.stop.get()
        sc.togli.visibility = if (vivo) View.GONE else View.VISIBLE
        sc.apri.visibility = if (!vivo && l.prima != null) View.VISIBLE else View.GONE
        sc.riprova.visibility = if ((l.stato as? Corsa.Stato.Finito)?.ris?.let { Grabber.daRiprovare(it) } == true) View.VISIBLE else View.GONE
    }

    override fun onResume() { super.onResume(); Corsa.ascolta(ascolta) }
    override fun onPause() { Corsa.smetti(ascolta); super.onPause() }

    private fun fine() { stop = null; vai.isEnabled = true; occupato(false) }

    private fun testoFinito(r: Risultato, n: String): String {
        if (r.annullato && r.salvate == 0) return "Cancelled: nothing was saved."
        val extraPagine = if (r.pagineFallite.isNotEmpty()) "${r.pagineFallite.size} pages didn't open: " + r.pagineFallite.take(5).joinToString(", ") + (if (r.pagineFallite.size > 5) "…" else "") else null
        val parola = if (r.salvate == 1) "image" else "images"
        var riga = if (r.pagineFatte > 0)
            "${r.salvate} $parola saved in Downloads → “$n”, ${r.pagineFatte} folder${if (r.pagineFatte != 1) "s" else ""}."
        else "${r.salvate} $parola saved in Downloads → “$n”."
        if (r.annullato) riga = "Cancelled. $riga"
        val extra = mutableListOf<String>()
        if (r.sfogliate > 0) extra.add("followed the site's “next” arrow through ${r.sfogliate} pages")
        if (r.fermata.isNotEmpty()) extra.add(r.fermata)
        extraPagine?.let { extra.add(it) }
        if (r.pagineVuote.isNotEmpty()) extra.add("no pictures on: " + r.pagineVuote.take(5).joinToString(", ") + if (r.pagineVuote.size > 5) "…" else "")
        if (r.rallentato) extra.add("the site asked to slow down, so fewer chapters ran at once")
        if (r.arredo > 0) extra.add("${r.arredo} site pictures (logo, menus) left out")
        if (r.piccole > 0) extra.add("${r.piccole} tiny icons skipped")
        if (r.doppie > 0) extra.add("${r.doppie} duplicates skipped")
        if (r.strisce > 0) extra.add("${r.strisce} strips joined to their page")
        extra.addAll(Grabber.righeMancanti(r))
        return riga + if (extra.isEmpty()) "" else "\n" + extra.joinToString(" · ")
    }

    private fun premiAnnulla(sc: Scheda) {
        val l = sc.lavoro
        if (l.stato is Corsa.Stato.Attesa) Corsa.metti(l, Corsa.Stato.Finito(Risultato().also { it.annullato = true }))
        else { l.stop.set(true); Corsa.avvisa() }
    }

    private fun premiRiprova(sc: Scheda) {
        val l = sc.lavoro
        val r = (l.stato as? Corsa.Stato.Finito)?.ris ?: return
        if (Corsa.nomeInUso(l.nome)) return
        val nuovo = Scarico.parti(this, Corsa.Richiesta(l.richiesta.link, l.nome, null, riprova = r), alPostoDi = l)
        nuovo.prima = l.prima
        schede.remove(l.id)?.let { vecchia -> vecchia.lavoro = nuovo; schede[nuovo.id] = vecchia }
        disegnaLavori()
    }

    private fun apriGalleria(sc: Scheda) {
        val u = sc.lavoro.prima ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            mostra("No gallery app found to open the pictures.", errore = true)
        }
    }

    override fun onDestroy() {
        stop?.set(true)          // solo la ricerca delle pagine; lo scarico vive nel servizio e continua
        super.onDestroy()
    }
}
