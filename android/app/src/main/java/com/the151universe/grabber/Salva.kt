package com.the151universe.grabber

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Scrive le foto in Download/<nome>/001.jpg … — «go to downloads as a folder called like chapter one». */
object Salva {
    fun percorso(nome: String) = "${Environment.DIRECTORY_DOWNLOADS}/$nome/"

    private fun legacyDir(nome: String) =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), nome)

    /**
     * La cartella c'è già? Dal 10 in su l'app vede solo i file suoi, quindi una cartella
     * che esiste (anche fatta da altri) basta per dire di no: mai mescolare dentro.
     */
    fun giaPiena(ctx: Context, nome: String): Boolean {
        val dir = legacyDir(nome)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return dir.list()?.isNotEmpty() == true
        if (dir.exists()) return true
        ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(percorso(nome)), null
        )?.use { return it.count > 0 }
        return false
    }

    /** Scrive tutto; un file nasce «in attesa» e diventa visibile solo a dimensione controllata. Torna l'uri della prima. */
    fun scrivi(ctx: Context, nome: String, foto: List<Foto>): Uri? {
        val scritti = mutableListOf<Uri>()
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val dir = legacyDir(nome); dir.mkdirs()
                val percorsi = mutableListOf<String>()
                foto.forEach { (nomeFile, d, _) ->
                    val f = File(dir, nomeFile)
                    if (f.exists()) throw GrabError("${f.name} already exists")
                    val part = File(dir, ".${f.name}.part")
                    part.outputStream().use { it.write(d); it.fd.sync() }
                    if (part.length() != d.size.toLong() || !part.renameTo(f)) { part.delete(); throw GrabError("incomplete write") }
                    percorsi.add(f.absolutePath)
                }
                MediaScannerConnection.scanFile(ctx, percorsi.toTypedArray(), null, null)
                return Uri.fromFile(File(percorsi.first()))
            }
            val cr = ctx.contentResolver
            foto.forEach { (nomeFile, d, ext) ->
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, nomeFile)
                    put(MediaStore.MediaColumns.MIME_TYPE, Grabber.mime(ext))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, percorso(nome))
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw GrabError("The phone refused to save the file.")
                scritti.add(uri)
                cr.openOutputStream(uri, "w")?.use { it.write(d) } ?: throw GrabError("The phone refused to save the file.")
                val misura = cr.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
                if (misura != d.size.toLong()) throw GrabError("incomplete write")
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            return scritti.firstOrNull()
        } catch (e: Exception) {
            // a metà non resta niente: una cartella con dei buchi sarebbe peggio di nessuna
            for (u in scritti) try { ctx.contentResolver.delete(u, null, null) } catch (_: Exception) {}
            try { legacyDir(nome).delete() } catch (_: Exception) {}  // solo se vuota: così lo stesso nome si può riprovare
            throw e
        }
    }
}
