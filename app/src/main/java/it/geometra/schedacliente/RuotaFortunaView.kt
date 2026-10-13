package it.geometra.schedacliente

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Disegna la "ruota della fortuna del caffè": un cerchio diviso in tanti spicchi quanti sono i
 * bar in elenco, con bordo esterno a "lampadine", spicchi sfumati, nomi lungo il raggio e un
 * mozzo centrale con la tazzina. Non gestisce da sola la rotazione: chi la usa anima la
 * proprietà standard [View.setRotation] (ad es. con un ValueAnimator).
 *
 * Il puntatore NON è disegnato qui (ruoterebbe insieme alla ruota): è una vista separata,
 * [PuntatoreRuotaView], sovrapposta e ferma in cima.
 */
class RuotaFortunaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var etichette: List<String> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    // Palette più viva e contrastata della precedente, sempre nei toni dell'app
    // (verdi, ocra, ruggine, blu petrolio), alternando chiari e scuri tra spicchi vicini.
    private val coloriSettori = listOf(
        Color.parseColor("#2E5339"),
        Color.parseColor("#E0A43A"),
        Color.parseColor("#C1440E"),
        Color.parseColor("#2B5C8A"),
        Color.parseColor("#6B8F3A"),
        Color.parseColor("#8A4B3B"),
        Color.parseColor("#2F6F62"),
        Color.parseColor("#B07A1E")
    )

    private val paintSettore = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintSeparatore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#F6E7C1")
        strokeWidth = 4f
    }
    private val paintOmbra = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintBordoEsterno = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintLuce = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintLuceAlone = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintSfumatura = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintTesto = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
        setShadowLayer(4f, 0f, 2f, Color.argb(150, 0, 0, 0))
    }
    private val paintMozzo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintMozzoBordo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#B07A1E")
        strokeWidth = 5f
    }
    private val paintTazzina = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.BLACK
    }
    private val paintVuoto = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5C6B61")
        textAlign = Paint.Align.CENTER
        textSize = 40f
    }

    init {
        // Le ombre sfumate (setShadowLayer) sul testo funzionano bene solo col rendering software.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val lato = minOf(measuredWidth, measuredHeight)
        setMeasuredDimension(lato, lato)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val cx = width / 2f
        val cy = height / 2f
        val raggioTotale = minOf(width, height) / 2f - 8f
        val spessoreBordo = raggioTotale * 0.085f
        val raggio = raggioTotale - spessoreBordo

        // Ombra morbida sotto la ruota.
        paintOmbra.shader = RadialGradient(
            cx, cy + 6f, raggioTotale + 6f,
            intArrayOf(Color.argb(90, 0, 0, 0), Color.argb(0, 0, 0, 0)),
            floatArrayOf(0.88f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy + 6f, raggioTotale + 6f, paintOmbra)

        // Bordo esterno "legno/ottone" con sfumatura.
        paintBordoEsterno.shader = LinearGradient(
            0f, cy - raggioTotale, 0f, cy + raggioTotale,
            Color.parseColor("#3A2A14"), Color.parseColor("#1E3A26"), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, raggioTotale, paintBordoEsterno)

        val n = etichette.size
        if (n == 0) {
            paintSettore.color = Color.parseColor("#E8E4DA")
            canvas.drawCircle(cx, cy, raggio, paintSettore)
            canvas.drawText("Nessun bar", cx, cy + 14f, paintVuoto)
            disegnaLuci(canvas, cx, cy, raggio + spessoreBordo / 2f, spessoreBordo, 16)
            return
        }

        val angoloPerSettore = 360f / n
        val rettangolo = RectF(cx - raggio, cy - raggio, cx + raggio, cy + raggio)

        for (i in 0 until n) {
            val angoloIniziale = i * angoloPerSettore
            // Con un numero dispari di spicchi, l'ultimo non deve avere lo stesso colore del primo.
            var indiceColore = i % coloriSettori.size
            if (i == n - 1 && n > 1 && indiceColore == 0) indiceColore = 3
            paintSettore.color = coloriSettori[indiceColore]
            canvas.drawArc(rettangolo, angoloIniziale, angoloPerSettore, true, paintSettore)
        }

        // Sfumatura radiale sopra gli spicchi: più chiaro al centro, più scuro al bordo, per dare volume.
        paintSfumatura.shader = RadialGradient(
            cx, cy, raggio,
            intArrayOf(Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(80, 0, 0, 0)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, raggio, paintSfumatura)

        // Separatori tra gli spicchi.
        if (n > 1) {
            for (i in 0 until n) {
                val a = Math.toRadians((i * angoloPerSettore).toDouble())
                canvas.drawLine(cx, cy, cx + raggio * cos(a).toFloat(), cy + raggio * sin(a).toFloat(), paintSeparatore)
            }
        }

        // Testi lungo il raggio.
        paintTesto.textSize = when {
            n <= 4 -> raggio * 0.12f
            n <= 6 -> raggio * 0.105f
            else -> raggio * 0.092f
        }.coerceIn(22f, 44f)
        val inizioTesto = raggio * 0.27f
        val larghezzaTestoDisponibile = raggio * 0.64f
        for (i in 0 until n) {
            val angoloCentrale = i * angoloPerSettore + angoloPerSettore / 2f
            canvas.save()
            canvas.rotate(angoloCentrale, cx, cy)
            // Nomi lunghi: prima si rimpicciolisce il carattere (fino al 70%), solo dopo si tronca.
            val dimensioneBase = paintTesto.textSize
            while (paintTesto.measureText(etichette[i]) > larghezzaTestoDisponibile &&
                paintTesto.textSize > dimensioneBase * 0.7f) {
                paintTesto.textSize -= 1f
            }
            val testoMostrato = tronca(etichette[i], larghezzaTestoDisponibile)
            canvas.drawText(testoMostrato, cx + inizioTesto, cy + paintTesto.textSize / 3f, paintTesto)
            paintTesto.textSize = dimensioneBase
            canvas.restore()
        }

        // Lampadine sul bordo.
        disegnaLuci(canvas, cx, cy, raggio + spessoreBordo / 2f, spessoreBordo, maxOf(16, n * 3))

        // Mozzo centrale con tazzina.
        val raggioMozzo = raggio * 0.19f
        paintMozzo.shader = RadialGradient(
            cx - raggioMozzo * 0.3f, cy - raggioMozzo * 0.3f, raggioMozzo * 1.3f,
            Color.parseColor("#FFF8E6"), Color.parseColor("#E8D3A0"), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, raggioMozzo, paintMozzo)
        canvas.drawCircle(cx, cy, raggioMozzo, paintMozzoBordo)
        paintTazzina.textSize = raggioMozzo * 1.05f
        canvas.drawText("☕", cx, cy + paintTazzina.textSize * 0.36f, paintTazzina)
    }

    /** Accorcia il testo con "…" finché non sta nella larghezza indicata (misurata col paint reale). */
    private fun tronca(testo: String, larghezzaMax: Float): String {
        if (paintTesto.measureText(testo) <= larghezzaMax) return testo
        var fine = testo.length
        while (fine > 1 && paintTesto.measureText(testo.substring(0, fine).trimEnd() + "…") > larghezzaMax) fine--
        return testo.substring(0, fine).trimEnd() + "…"
    }

    private fun disegnaLuci(canvas: Canvas, cx: Float, cy: Float, raggioLuci: Float, spessore: Float, quante: Int) {
        val r = spessore * 0.22f
        for (k in 0 until quante) {
            val a = Math.toRadians(k * 360.0 / quante)
            val x = cx + raggioLuci * cos(a).toFloat()
            val y = cy + raggioLuci * sin(a).toFloat()
            paintLuceAlone.shader = RadialGradient(
                x, y, r * 2.2f,
                intArrayOf(Color.argb(140, 255, 214, 120), Color.argb(0, 255, 214, 120)),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(x, y, r * 2.2f, paintLuceAlone)
            paintLuce.color = if (k % 2 == 0) Color.parseColor("#FFE8A3") else Color.parseColor("#FFFFFF")
            canvas.drawCircle(x, y, r, paintLuce)
        }
    }
}

/**
 * Puntatore fisso in cima alla ruota (non ruota con lei): una goccia color ruggine con bordo
 * chiaro e ombra, che punta verso il basso sullo spicchio vincente.
 */
class PuntatoreRuotaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paintCorpo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        setShadowLayer(6f, 0f, 3f, Color.argb(120, 0, 0, 0))
    }
    private val paintBordo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#FFF3D6")
    }
    private val paintPerno = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFF3D6")
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val raggioTesta = w * 0.32f
        val cyTesta = raggioTesta + 6f
        val punta = h - 4f

        val path = Path().apply {
            // Dalla punta in basso, linea fino al lato sinistro della testa, arco sopra la testa
            // (da 150° a 30° passando per la cima, in senso orario) e ritorno alla punta.
            moveTo(cx, punta)
            arcTo(
                RectF(cx - raggioTesta, cyTesta - raggioTesta, cx + raggioTesta, cyTesta + raggioTesta),
                150f, 240f, false
            )
            close()
        }
        paintCorpo.shader = LinearGradient(
            0f, 0f, 0f, h,
            Color.parseColor("#E0592A"), Color.parseColor("#9C3609"), Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, paintCorpo)
        canvas.drawPath(path, paintBordo)
        canvas.drawCircle(cx, cyTesta, raggioTesta * 0.32f, paintPerno)
    }
}
