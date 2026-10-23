package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Prayer times from JAKIM e-Solat and public holidays from the Malaysia Calendar API, for the Home panel
 * (config.xml solat_zone). Fork-only: it is the one place in the launcher that uses the network. It fetches
 * at most once a day on a return to Home, on a background thread, and keeps the answer in SharedPreferences.
 */
final class Solat {
    static final String[] KEYS = {"fajr", "syuruk", "dhuhr", "asr", "maghrib", "isha"};
    static final String[] NAMES = {"Subuh", "Syuruk", "Zohor", "Asar", "Maghrib", "Isyak"};
    private static final String[] MONTHS = {"Jan", "Feb", "Mac", "Apr", "Mei", "Jun", "Jul", "Ogo", "Sep", "Okt", "Nov", "Dis"};
    private static final String PREFS = "solat";

    static final class Holiday {
        final LocalDate date;
        final String ms, en;

        Holiday(LocalDate date, String ms, String en) {
            this.date = date;
            this.ms = ms;
            this.en = en;
        }
    }

    private Solat() {}

    /** Today's six times from an e-Solat takwimsolat response, or null when the day is not in it. */
    static LocalTime[] parseTimes(String json, LocalDate day) {
        try {
            JSONArray days = new JSONObject(json).getJSONArray("prayerTime");
            for (int i = 0; i < days.length(); i++) {
                JSONObject d = days.getJSONObject(i);
                String[] parts = d.getString("date").split("-");
                if (parts.length != 3 || Integer.parseInt(parts[0]) != day.getDayOfMonth()
                        || Integer.parseInt(parts[2]) != day.getYear() || !MONTHS[day.getMonthValue() - 1].equalsIgnoreCase(parts[1])) {
                    continue;
                }
                LocalTime[] out = new LocalTime[KEYS.length];
                for (int k = 0; k < KEYS.length; k++) out[k] = LocalTime.parse(d.getString(KEYS[k]).substring(0, 5));
                return out;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Index of the first prayer still to come at {@code now}, or -1 once Isyak has passed. */
    static int next(LocalTime[] times, LocalTime now) {
        for (int i = 0; i < times.length; i++) if (times[i].isAfter(now)) return i;
        return -1;
    }

    /** Holidays of a state from a Malaysia Calendar API response, as one "date, ms, en" per line. */
    static String filterHolidays(String json, String state) {
        StringBuilder out = new StringBuilder();
        try {
            JSONArray data = new JSONObject(json).getJSONArray("data");
            for (int i = 0; i < data.length(); i++) {
                JSONObject h = data.getJSONObject(i);
                if (!h.optBoolean("isPublicHoliday", true) || !contains(h.optJSONArray("states"), state)) continue;
                JSONObject name = h.getJSONObject("name");
                out.append(h.getString("date")).append('\t').append(name.optString("ms")).append('\t')
                        .append(name.optString("en")).append('\n');
            }
        } catch (Exception ignored) {
        }
        return out.toString();
    }

    static List<Holiday> parseHolidays(String lines) {
        List<Holiday> out = new ArrayList<>();
        for (String line : lines.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length < 3) continue;
            try {
                out.add(new Holiday(LocalDate.parse(f[0]), f[1], f[2]));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** The first holiday on or after {@code today}, or null. The list is in date order. */
    static Holiday upcoming(List<Holiday> all, LocalDate today) {
        for (Holiday h : all) if (!h.date.isBefore(today)) return h;
        return null;
    }

    private static boolean contains(JSONArray a, String s) {
        if (a == null) return false;
        for (int i = 0; i < a.length(); i++) if (s.equals(a.optString(i))) return true;
        return false;
    }

    // ---- storage and network ---------------------------------------------------------------------------

    static String cachedPrayers(Context c) {
        return prefs(c).getString("prayers", "");
    }

    static String cachedHolidays(Context c) {
        return prefs(c).getString("holidays", "");
    }

    /** True when the cache lacks today's times or last year's holidays, so a fetch is worth making. */
    static boolean stale(Context c, LocalDate today) {
        SharedPreferences p = prefs(c);
        return !today.toString().equals(p.getString("prayersDay", "")) || p.getInt("holidayYear", 0) != today.getYear();
    }

    /** Blocking: call from a background thread. Returns true when something new was stored. */
    static boolean refresh(Context c, String zone, String state, LocalDate today) {
        SharedPreferences.Editor e = prefs(c).edit();
        boolean changed = false;
        try {
            String prayers = get("https://www.e-solat.gov.my/index.php?r=esolatApi/takwimsolat&period=month&zone=" + zone);
            if (parseTimes(prayers, today) != null) {
                e.putString("prayers", prayers).putString("prayersDay", today.toString());
                changed = true;
            }
        } catch (Exception ex) {
            android.util.Log.w("Solat", "prayers: " + ex);
        }
        try {
            String lines = filterHolidays(get("https://mycal-api.huijun00100101.workers.dev/v1/holidays?year="
                    + today.getYear() + "&state=" + state), state);
            if (!lines.isEmpty()) {
                e.putString("holidays", lines).putInt("holidayYear", today.getYear());
                changed = true;
            }
        } catch (Exception ex) {
            android.util.Log.w("Solat", "holidays: " + ex);
        }
        e.apply();
        return changed;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) TVHome");
        conn.setRequestProperty("Accept", "application/json");
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
            return buf.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }
}
