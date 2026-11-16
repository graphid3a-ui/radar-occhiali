package it.paolo.radarocchiali;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Radar circolare: la distanza dal centro è la distanza stimata, l'angolo è solo grafico. */
public class RadarView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Dispositivo> punti = new ArrayList<>();
    private Dispositivo bersaglio;
    private boolean agganciato;
    private final float dp;

    public RadarView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
    }

    public void imposta(List<Dispositivo> lista, Dispositivo bers, boolean aggancio) {
        punti.clear();
        punti.addAll(lista);
        bersaglio = bers;
        agganciato = aggancio;
        invalidate();
    }

    @Override protected void onMeasure(int w, int h) {
        int lato = MeasureSpec.getSize(w);
        setMeasuredDimension(lato, (int) (lato * 0.92f));
    }

    /** scala logaritmica 0.3 m .. 15 m -> 0..1 */
    private static float raggioRel(double metri) {
        double m = Math.max(0.3, Math.min(15, metri));
        return (float) (Math.log(m / 0.3) / Math.log(15 / 0.3));
    }

    /** colore da freddo (blu) a caldo (rosso) */
    static int colore(double vic) {
        float[] hsv = { (float) (210 - 210 * vic), 0.85f, 1f };
        return Color.HSVToColor(hsv);
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2, cy = h / 2, R = Math.min(w, h) / 2 - 14 * dp;
        long t = SystemClock.uptimeMillis();
        c.drawColor(Color.rgb(8, 14, 12));

        // anelli con distanze
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.2f * dp);
        p.setTextSize(11 * dp);
        double[] anelli = { 1, 3, 10 };
        for (double m : anelli) {
            float r = raggioRel(m) * R;
            p.setColor(Color.rgb(30, 90, 60));
            p.setStyle(Paint.Style.STROKE);
            c.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(70, 150, 110));
            c.drawText((m < 10 ? " " : "") + (int) m + " m", cx + 4 * dp, cy - r - 3 * dp, p);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setColor(Color.rgb(30, 90, 60));
        c.drawCircle(cx, cy, R, p);
        c.drawLine(cx - R, cy, cx + R, cy, p);
        c.drawLine(cx, cy - R, cx, cy + R, p);

        // fascio rotante
        float ang = (t % 3000) / 3000f * 360f;
        p.setStyle(Paint.Style.FILL);
        RectF box = new RectF(cx - R, cy - R, cx + R, cy + R);
        for (int i = 0; i < 12; i++) {
            p.setColor(Color.argb(60 - i * 5, 60, 220, 140));
            c.drawArc(box, ang - i * 3, 3, true, p);
        }

        // dispositivi
        for (Dispositivo d : punti) {
            double vic = Rilevatore.vicinanza(d.rssi);
            float r = raggioRel(Rilevatore.metri(d.rssi)) * R;
            double a = Math.toRadians(d.angolo);
            float x = cx + (float) (r * Math.cos(a)), y = cy + (float) (r * Math.sin(a));
            boolean b = d == bersaglio;
            if (!d.occhiale()) {
                p.setColor(Color.argb(b ? 255 : 110, 160, 160, 160));
                c.drawCircle(x, y, (b ? 7 : 3.5f) * dp, p);
            } else {
                p.setColor(colore(vic));
                c.drawCircle(x, y, (b ? 10 : 7) * dp, p);
            }
            if (b) {
                float puls = (t % 1000) / 1000f;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2 * dp);
                p.setColor(Color.argb((int) (255 * (1 - puls)), 255, 255, 255));
                c.drawCircle(x, y, (12 + 18 * puls) * dp, p);
                if (agganciato) {
                    p.setColor(Color.WHITE);
                    c.drawRect(x - 15 * dp, y - 15 * dp, x + 15 * dp, y + 15 * dp, p);
                }
                p.setStyle(Paint.Style.FILL);
            }
        }

        // centro = tu
        p.setColor(Color.WHITE);
        c.drawCircle(cx, cy, 5 * dp, p);
        postInvalidateOnAnimation();
    }
}
