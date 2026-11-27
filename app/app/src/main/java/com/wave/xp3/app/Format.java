package com.wave.xp3.app;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Sizes, dates and addresses as the screens say them. Plain Java: host-tested. */
final class Format {
    private Format() {}

    static String size(long bytes) {
        if (bytes <= 0) return null;
        if (bytes < 1000 * 1000) return Math.max(1, Math.round(bytes / 1000.0)) + " KB";
        return String.format(Locale.US, "%.1f MB", bytes / 1000.0 / 1000.0);
    }

    /** "Today", "Yesterday", or "Nov 12, 2026", from YYYY-MM-DD. Anything else is returned as it is. */
    static String date(String iso) {
        return date(iso, Calendar.getInstance());
    }

    static String date(String iso, Calendar now) {
        if (iso == null) return null;
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            in.setLenient(false);
            Date day = in.parse(iso.length() >= 10 ? iso.substring(0, 10) : iso);
            Calendar then = Calendar.getInstance();
            then.setTime(day);
            return relative(then, now, "Today", "Yesterday", new SimpleDateFormat("MMM d, yyyy", Locale.US).format(day));
        } catch (Exception e) {
            return iso;
        }
    }

    /** "0 seconds ago", "40 seconds ago", "5 minutes ago", "2 hours ago", "1 day ago", or null before the first refresh. */
    static String refreshed(long millis) {
        return refreshed(millis, Calendar.getInstance());
    }

    static String refreshed(long millis, Calendar now) {
        if (millis <= 0) return null;
        long seconds = Math.max(0, now.getTimeInMillis() - millis) / 1000;
        if (seconds < 60) return ago(seconds, "second");
        long minutes = seconds / 60;
        if (minutes < 60) return ago(minutes, "minute");
        long hours = minutes / 60;
        if (hours < 24) return ago(hours, "hour");
        long days = hours / 24;
        if (days < 30) return ago(days, "day");
        if (days < 365) return ago(days / 30, "month");
        return ago(days / 365, "year");
    }

    private static String ago(long n, String unit) {
        return n + " " + unit + (n == 1 ? "" : "s") + " ago";
    }

    private static String relative(Calendar then, Calendar now, String today, String yesterday, String otherwise) {
        if (sameDay(then, now)) return today;
        Calendar before = (Calendar) now.clone();
        before.add(Calendar.DAY_OF_YEAR, -1);
        return sameDay(then, before) ? yesterday : otherwise;
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    /** An address without its scheme, as the rows show it. */
    static String shortUrl(String url) {
        if (url == null) return "";
        String s = url.replaceFirst("^[a-zA-Z]+://", "");
        if (s.endsWith(".git")) s = s.substring(0, s.length() - 4);
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** Megabytes with one decimal, for a download's progress. */
    static String megabytes(long bytes) {
        return String.format(Locale.US, "%.1f", bytes / 1000.0 / 1000.0);
    }
}
