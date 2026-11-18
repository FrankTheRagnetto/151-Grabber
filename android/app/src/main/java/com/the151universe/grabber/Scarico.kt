package com.the151universe.grabber

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Il lavoro gira qui, non nello schermo: «it should also work smoothly in the background …
 * a progress bar in my notifications». Servizio in primo piano (dataSync) con
 * la barra nelle notifiche e «Cancel»; lo schermo si iscrive a `Corsa` e mostra gli stessi lavori.
 * Più lavori insieme Both»): al massimo MAX nello stesso momento, gli altri in fila.
 */
object Corsa {
    /** Cosa fare: una pagina (pagine == null), le pagine scelte, o riprovare ciò che manca di un giro finito. */
    class Richiesta(val link: String, val nome: String, val pagine: List<Pagina>?, val riprova: Risultato? = null)

    sealed class Stato {
        object Attesa : Stato()
        class Va(val fatte: Int, val totale: Int, val msg: String) : Stato()
        class Finito(val ris: Risultato) : Stato()
        class Errore(val msg: String) : Stato()
    }

    /** Un lavoro: una scheda sullo schermo. */
    class Lavoro(val id: Int, val richiesta: Richiesta) {
        val stop = AtomicBoolean(false)
        @Volatile var stato: Stato = Stato.Attesa
        @Volatile var prima: Uri? = null
        val vivo get() = stato is Stato.Attesa || stato is Stato.Va
        val nome get() = richiesta.nome
    }

    const val MAX = 3
    private var prossimo = 1
    val lavori = java.util.concurrent.CopyOnWriteArrayList<Lavoro>()   // in ordine di partenza
    private val ascoltatori = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    val occupato get() = lavori.any { it.vivo }
    fun nomeInUso(n: String) = lavori.any { it.vivo && it.nome == n }

    fun ascolta(f: () -> Unit) { synchronized(ascoltatori) { ascoltatori.add(f) }; main.post { f() } }
    fun smetti(f: () -> Unit) { synchronized(ascoltatori) { ascoltatori.remove(f) } }
    fun avvisa() { main.post { chiama() } }

    /** L'avanzamento ridisegna lo schermo al più ogni RIDISEGNO_MS: a ogni foto era troppo (batteria).
     *  Partenze, fine ed errori restano subito (`avvisa`). */
    const val RIDISEGNO_MS = 400L
    private val inArrivo = AtomicBoolean(false)
    @Volatile private var ultimoDisegno = 0L
    private fun chiama() { ultimoDisegno = SystemClock.elapsedRealtime(); synchronized(ascoltatori) { ascoltatori.toList() }.forEach { it() } }
    private fun avvisaPiano() {
        if (!inArrivo.compareAndSet(false, true)) return   // un disegno è già in arrivo: vedrà anche questo stato
        val attesa = maxOf(0L, ultimoDisegno + RIDISEGNO_MS - SystemClock.elapsedRealtime())
        main.postDelayed({ inArrivo.set(false); chiama() }, attesa)
    }

    fun metti(l: Lavoro, s: Stato) { l.stato = s; if (s is Stato.Va) avvisaPiano() else avvisa() }

    @Synchronized fun nuovo(r: Richiesta, alPostoDi: Lavoro? = null): Lavoro {
        val l = Lavoro(prossimo++, r)
        val i = if (alPostoDi != null) lavori.indexOf(alPostoDi) else -1
        if (i >= 0) lavori[i] = l else lavori.add(l)
        avvisa()
        return l
    }

    fun togli(l: Lavoro) { if (!l.vivo) { lavori.remove(l); avvisa() } }

    /** I lavori in fila che possono partire adesso (dal più vecchio). */
    @Synchronized fun daAvviare(): List<Lavoro> {
        val liberi = MAX - lavori.count { it.stato is Stato.Va }
        if (liberi <= 0) return emptyList()
        return lavori.filter { it.stato is Stato.Attesa }.take(liberi).onEach { it.stato = Stato.Va(0, 0, "Starting…") }
    }
}

class Scarico : Service() {
    companion object {
        const val CANALE = "scarichi"
        const val ID_VA = 1
        const val ID_FINE = 1000   // + id del lavoro: una notifica di fine per ognuno
        const val ANNULLA = "com.the151universe.grabber.ANNULLA"

        fun parti(ctx: Context, r: Corsa.Richiesta, alPostoDi: Corsa.Lavoro? = null): Corsa.Lavoro {
            val l = Corsa.nuovo(r, alPostoDi)
            val i = Intent(ctx, Scarico::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            return l
        }

        fun canale(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CANALE) == null)
                nm.createNotificationChannel(NotificationChannel(CANALE, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of 151 Grabber downloads"; setShowBadge(false)
                })
        }
    }

    private var lucchetto: PowerManager.WakeLock? = null
    private var ultimaNotifica = 0L
    private var inPrimoPiano = false
    private val principale = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    private fun apriApp(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notifica(titolo: String, testo: String, fatte: Int, totale: Int, inCorso: Boolean, annulla: String = "Cancel"): Notification {
        val b = Notification.Builder(this, CANALE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(titolo)
            .setContentText(testo)
            .setContentIntent(apriApp())
            .setOnlyAlertOnce(true)
            .setShowWhen(!inCorso)
        if (inCorso) {
            b.setOngoing(true)
            b.setProgress(totale, fatte, totale == 0)
            val pi = PendingIntent.getService(this, 1, Intent(this, Scarico::class.java).setAction(ANNULLA), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(null, annulla, pi).build())
            if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        } else b.setAutoCancel(true)
        return b.build()
    }

    /** La notifica in corso dice tutti i lavori vivi: uno → il suo nome e la sua barra; più → quanti e la barra insieme. */
    private fun riepilogo(): Notification {
        val vanno = Corsa.lavori.filter { it.stato is Corsa.Stato.Va }
        val attesa = Corsa.lavori.count { it.stato is Corsa.Stato.Attesa }
        if (vanno.size <= 1) {
            val l = vanno.firstOrNull()
            val st = l?.stato as? Corsa.Stato.Va
            val testo = (st?.msg ?: "Starting…") + if (attesa > 0) " · $attesa waiting" else ""
            return notifica(l?.nome ?: "151 Grabber", testo, st?.fatte ?: 0, st?.totale ?: 0, true)
        }
        val stati = vanno.map { it.stato as Corsa.Stato.Va }
        val conBarra = stati.all { it.totale > 0 }
        val titolo = "${vanno.size} downloads running" + if (attesa > 0) " · $attesa waiting" else ""
        return notifica(titolo, vanno.joinToString(" · ") { it.nome },
            if (conBarra) stati.sumOf { it.fatte * 100 / it.totale } else 0, if (conBarra) stati.size * 100 else 0, true, "Cancel all")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Grabber.cucitore = CuciBitmap
        if (intent?.action == ANNULLA) { Corsa.lavori.filter { it.vivo }.forEach { it.stop.set(true) }; return START_NOT_STICKY }
        canale(this)
        val n = riepilogo()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_VA, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID_VA, n)
        inPrimoPiano = true
        if (lucchetto == null) lucchetto = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "151grabber:scarico").apply { acquire(6 * 60 * 60 * 1000L) }
        avvia()
        return START_NOT_STICKY
    }

    private fun avvia() {
        for (l in Corsa.daAvviare()) Thread { lavora(l) }.start()
        if (!Corsa.occupato) fermati() else aggiornaNotifica(true)
    }

    private fun aggiornaNotifica(subito: Boolean = false) {
        synchronized(this) {
            val ora = SystemClock.elapsedRealtime()
            if (!subito && ora - ultimaNotifica <= 1500) return   // il sistema scarta le notifiche troppo fitte; più rade = meno batteria
            ultimaNotifica = ora
        }
        if (Corsa.occupato) getSystemService(NotificationManager::class.java).notify(ID_VA, riepilogo())
    }

    private fun lavora(l: Corsa.Lavoro) {
        val r = l.richiesta
        val s = l.stop
        val nm = getSystemService(NotificationManager::class.java)
        val av = { f: Int, t: Int, m: String ->
            Corsa.metti(l, Corsa.Stato.Va(f, t, m))
            aggiornaNotifica()
        }
        val salva = { rel: String, foto: List<Foto> ->
            val u = Salva.scrivi(this, if (rel.isEmpty()) r.nome else "${r.nome}/$rel", foto)
            synchronized(l) { if (l.prima == null) l.prima = u }
        }
        val fine: Corsa.Stato = try {
            val ris = when {
                r.riprova != null -> { Grabber.riprovaMancanti(r.riprova, ::piccola, salva, av, s); r.riprova.annullato = s.get(); r.riprova }
                r.pagine != null -> Grabber.scaricaPagine(r.pagine, ::piccola, salva, av, s)
                else -> Grabber.scaricaTutto(r.link, ::piccola, salva, av, s)
            }
            Corsa.Stato.Finito(ris)
        } catch (e: GrabError) {
            Corsa.Stato.Errore(e.message ?: "Error")
        } catch (e: Exception) {
            Corsa.Stato.Errore("Something went wrong: ${e.javaClass.simpleName}: ${e.message}")
        }
        Corsa.metti(l, fine)
        val (titolo, testo) = when (fine) {
            is Corsa.Stato.Finito -> (if (fine.ris.annullato) "Cancelled · ${r.nome}" else "Saved · ${r.nome}") to riassunto(fine.ris)
            is Corsa.Stato.Errore -> "Couldn't download · ${r.nome}" to fine.msg
            else -> r.nome to ""
        }
        nm.notify(ID_FINE + l.id, notifica(titolo, testo, 0, 0, false))
        principale.post { avvia() }   // sul filo principale, come onStartCommand: niente stopSelf a metà di un avvio
    }

    private fun fermati() {
        try { lucchetto?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        lucchetto = null
        if (inPrimoPiano) {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
            inPrimoPiano = false
        }
        stopSelf()
    }

    override fun onDestroy() {
        try { lucchetto?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun piccola(d: ByteArray): Boolean {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(d, 0, d.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return false   // non la legge (es. tif): la teniamo
        return o.outWidth < Grabber.MIN_LATO && o.outHeight < Grabber.MIN_LATO
    }
}

/** Una riga per la notifica e lo schermo. */
fun riassunto(r: Risultato): String {
    val parola = if (r.salvate == 1) "image" else "images"
    val base = if (r.pagineFatte > 0) "${r.salvate} $parola in ${r.pagineFatte} folder${if (r.pagineFatte != 1) "s" else ""}"
               else "${r.salvate} $parola"
    val extra = mutableListOf<String>()
    if (r.pagineFallite.isNotEmpty()) extra.add("${r.pagineFallite.size} pages didn't open")
    if (r.fermata.isNotEmpty()) extra.add(r.fermata)
    extra.addAll(Grabber.righeMancanti(r, corte = true))
    return base + if (extra.isEmpty()) "" else " · " + extra.joinToString(" · ")
}
