package com.particlesdevs.photoncamera.circularbarlib.ui;

import android.content.Context;

import com.particlesdevs.photoncamera.circularbarlib.R;

import java.util.Locale;

/**
 * Value texts of the manual controls (chips, ruler pill, top-bar summary). Plain functions: the units and the decimal
 * separator come in a {@link Units} (Russian: «с», «м», «см» and a decimal comma; English: s, m, cm and a point).
 * <ul>
 * <li>ISO: «2560»;</li>
 * <li>shutter: below 0.5 s «1/8000», else seconds with at most one decimal: «2 с», «1,3 с»;</li>
 * <li>EV: «0», else a sign («+» or «−», U+2212), the whole part and a fraction in thirds: «+1 1/3», «−2/3» (halves,
 * quarters and sixths when the value is one);</li>
 * <li>focus (diopters, as Camera2 reports them): «∞», from 1 m «1,2 м», below «30 см»;</li>
 * <li>white balance: «5200K».</li>
 * </ul>
 */
public final class ManualFormat {
    private ManualFormat() {}

    /** Units and decimal separator of a UI language. */
    public static final class Units {
        public final String seconds, metres, centimetres;
        public final char decimal;

        public Units(String seconds, String metres, String centimetres, char decimal) {
            this.seconds = seconds;
            this.metres = metres;
            this.centimetres = centimetres;
            this.decimal = decimal;
        }

        /** The units of the library's resources (values / values-ru). */
        public static Units of(Context c) {
            String sep = c.getString(R.string.manual_decimal_separator);
            return new Units(c.getString(R.string.manual_unit_seconds), c.getString(R.string.manual_unit_metres),
                    c.getString(R.string.manual_unit_centimetres), sep.isEmpty() ? '.' : sep.charAt(0));
        }
    }

    /** The English units; the Russian ones come from values-ru through {@link Units#of}. */
    public static final Units EN = new Units("s", "m", "cm", '.');
    /** The minus sign of negative values. */
    public static final char MINUS = '−';

    public static String iso(double iso) {
        return String.valueOf(Math.round(iso));
    }

    public static String shutter(long ns, Units u) {
        double s = ns / 1e9;
        if (s <= 0) return "0";
        if (s < 0.5) return "1/" + Math.round(1 / s);
        return oneDecimal(s, u) + " " + u.seconds;
    }

    /** The shutter without its unit, for the ruler's labels. */
    public static String shutterShort(long ns, Units u) {
        double s = ns / 1e9;
        if (s <= 0) return "0";
        if (s < 0.5) return "1/" + Math.round(1 / s);
        return oneDecimal(s, u);
    }

    /** Exposure compensation in EV, in thirds when it is one (see the class comment). */
    public static String ev(double ev) {
        if (Math.abs(ev) < 1e-3) return "0";
        char sign = ev > 0 ? '+' : MINUS;
        double a = Math.abs(ev);
        for (int den : new int[]{1, 3, 2, 4, 6}) {
            long n = Math.round(a * den);
            if (Math.abs(a * den - n) < 0.02) {
                long whole = n / den, rest = n % den;
                if (rest == 0) return sign + String.valueOf(whole);
                long g = gcd(rest, den);
                String frac = (rest / g) + "/" + (den / g);
                return whole == 0 ? sign + frac : sign + String.valueOf(whole) + " " + frac;
            }
        }
        return sign + String.format(Locale.ROOT, "%.1f", a).replace('.', ',');
    }

    /** A focus distance given in diopters (1/m); 0 or less is infinity. */
    public static String focus(double diopters, Units u) {
        if (diopters <= 1e-6) return "∞";
        double metres = 1 / diopters;
        long cm = Math.round(metres * 100);
        if (cm < 100) return cm + " " + u.centimetres;
        if (metres >= 10) return Math.round(metres) + " " + u.metres;
        return oneDecimal(metres, u) + " " + u.metres;
    }

    public static String wb(int kelvin) {
        return kelvin + "K";
    }

    /** One decimal, «,0» / «.0» dropped: 2 -> «2», 1.33 -> «1,3». */
    static String oneDecimal(double v, Units u) {
        long tenths = Math.round(v * 10);
        if (tenths % 10 == 0) return String.valueOf(tenths / 10);
        return (tenths / 10) + String.valueOf(u.decimal) + (tenths % 10);
    }

    private static long gcd(long a, long b) {
        return b == 0 ? a : gcd(b, a % b);
    }
}
