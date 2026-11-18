package com.the151universe.grabber

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import java.io.ByteArrayOutputStream

/** I pixel veri sul telefono, per cucire le strisce di fondo alla loro pagina. */
object CuciBitmap : Cucitore {
    override fun misura(d: ByteArray): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(d, 0, d.size, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    // la foto intera, non BitmapRegionDecoder: su webp la regione esce con colori un po' diversi
    // e due strisce vere di un capitolo vero non passavano (telefono 18 file, PC 16)
    private fun apri(d: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(d, 0, d.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })

    private fun grigi(bmp: Bitmap, y: Int): IntArray? {
        if (y !in 0 until bmp.height) return null
        val px = IntArray(bmp.width)
        bmp.getPixels(px, 0, bmp.width, 0, y, bmp.width, 1)
        // come Pillow «L»: L = R*299/1000 + G*587/1000 + B*114/1000
        return IntArray(px.size) { i -> val c = px[i]; (((c shr 16) and 0xff) * 299 + ((c shr 8) and 0xff) * 587 + (c and 0xff) * 114) / 1000 }
    }

    private fun incolla(a: Bitmap, b: Bitmap): ByteArray {
        val p = Bitmap.createBitmap(a.width, a.height + b.height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(p).apply { drawBitmap(a, 0f, 0f, null); drawBitmap(b, 0f, a.height.toFloat(), null) }
            val out = ByteArrayOutputStream()
            // senza perdite: webp lossless da Android 11, prima png. Nel lossless la «qualità» è solo lo
            // sforzo di compressione, i pixel restano identici: 50 invece di 100 costa meno batteria
            if (Build.VERSION.SDK_INT >= 30) p.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 50, out)
            else p.compress(Bitmap.CompressFormat.PNG, 100, out)
            return out.toByteArray()
        } finally { p.recycle() }
    }

    override fun riga(d: ByteArray, y: Int): IntArray? {
        val bmp = apri(d) ?: return null
        try { return grigi(bmp, y) } finally { bmp.recycle() }
    }

    override fun unisci(sopra: ByteArray, sotto: ByteArray): ByteArray? {
        val a = apri(sopra) ?: return null
        try {
            val b = apri(sotto) ?: return null
            try { return incolla(a, b) } finally { b.recycle() }
        } finally { a.recycle() }
    }

    /** Ogni foto si apre una volta sola: le righe del taglio e la pagina intera dalle stesse Bitmap
     *  (prima 4 decodifiche intere per coppia, ora 2: batteria). */
    override fun cuciSe(sopra: ByteArray, sotto: ByteArray, vaBene: (fondo: IntArray, cima: IntArray) -> Boolean): ByteArray? {
        val a = apri(sopra) ?: return null
        try {
            val fondo = grigi(a, a.height - 1) ?: return null
            val b = apri(sotto) ?: return null
            try {
                val cima = grigi(b, 0) ?: return null
                return if (vaBene(fondo, cima)) incolla(a, b) else null
            } finally { b.recycle() }
        } finally { a.recycle() }
    }
}
