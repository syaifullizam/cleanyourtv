package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public class SolatTest {
    private static final String TIMES = "{\"prayerTime\":[{\"date\":\"23-Okt-2026\",\"fajr\":\"05:49:00\","
            + "\"syuruk\":\"06:55:00\",\"dhuhr\":\"13:00:00\",\"asr\":\"16:19:00\",\"maghrib\":\"19:00:00\",\"isha\":\"20:10:00\"}]}";

    @Test
    public void parsesToday() {
        LocalTime[] t = Solat.parseTimes(TIMES, LocalDate.of(2026, 10, 23));
        assertEquals(LocalTime.of(5, 49), t[0]);
        assertEquals(LocalTime.of(20, 10), t[5]);
        assertNull(Solat.parseTimes(TIMES, LocalDate.of(2026, 10, 24)));
    }

    @Test
    public void findsNextPrayer() {
        LocalTime[] t = Solat.parseTimes(TIMES, LocalDate.of(2026, 10, 23));
        assertEquals(2, Solat.next(t, LocalTime.of(12, 0)));
        assertEquals(-1, Solat.next(t, LocalTime.of(21, 0)));
    }

    @Test
    public void filtersHolidaysByState() {
        String json = "{\"data\":[{\"date\":\"2026-10-31\",\"name\":{\"ms\":\"A\",\"en\":\"B\"},\"isPublicHoliday\":true,"
                + "\"states\":[\"selangor\"]},{\"date\":\"2026-11-01\",\"name\":{\"ms\":\"C\",\"en\":\"D\"},"
                + "\"isPublicHoliday\":true,\"states\":[\"johor\"]}]}";
        List<Solat.Holiday> h = Solat.parseHolidays(Solat.filterHolidays(json, "selangor"));
        assertEquals(1, h.size());
        assertEquals("A", Solat.upcoming(h, LocalDate.of(2026, 10, 23)).ms);
        assertNull(Solat.upcoming(h, LocalDate.of(2026, 11, 2)));
    }
}
