"""Invariant 2, pinned: the day boundary is the user's timezone, computed here.

These are the cases that decide whether a count lands on the right date. They are worth more than
they look — a count filed a day late is not a crash, it is a streak that breaks for no reason the
user can see, and `docs/DECISIONS.md` D81 already flags today's streaks as PROVISIONAL precisely
because the client's interim boundary (D14) will be replaced by this.
"""

from __future__ import annotations

from datetime import UTC, date, datetime, timedelta, timezone

import pytest

from app.timezones import (
    FALLBACK_TIMEZONE,
    assert_tzdata_available,
    is_valid_timezone,
    local_date_for,
    normalise_timezone,
)


class TestValidation:
    @pytest.mark.parametrize(
        "name",
        ["UTC", "Asia/Kolkata", "America/New_York", "Europe/London", "Australia/Sydney"],
    )
    def test_real_zones_are_valid(self, name: str):
        assert is_valid_timezone(name)

    @pytest.mark.parametrize(
        "name",
        [
            "",
            "Not/AZone",
            "IST",  # a common abbreviation, and NOT an IANA key
            "GMT+5:30",  # an offset, not a zone — it has no DST rules
            "asia/kolkata",  # IANA keys are case-sensitive
        ],
    )
    def test_junk_is_rejected(self, name: str):
        assert not is_valid_timezone(name)

    @pytest.mark.parametrize("name", ["/etc/passwd", "../../etc/passwd", "UTC\x00"])
    def test_path_shaped_input_is_rejected(self, name: str):
        """`zoneinfo` treats its key as a relative path into the tz database.

        That makes rejecting these a security property, not just tidiness — this value arrives from
        a client and is stored on `users.timezone`, so it is attacker-influenced input reaching a
        filesystem lookup. Pinned separately from the junk cases so nobody "simplifies" the
        validator into a regex over `[A-Za-z/_]+` and quietly re-admits traversal.
        """
        assert not is_valid_timezone(name)


class TestNormalise:
    def test_a_valid_zone_is_kept_exactly(self):
        assert normalise_timezone("Asia/Kolkata") == "Asia/Kolkata"

    @pytest.mark.parametrize("name", [None, "", "Not/AZone"])
    def test_anything_unusable_falls_back(self, name: str | None):
        assert normalise_timezone(name) == FALLBACK_TIMEZONE

    def test_near_misses_are_NOT_silently_corrected(self):
        """`Asia/Calcutta` is a real IANA alias and resolves; `IST` is not and must not be guessed.

        The temptation is to map abbreviations onto zones so more clients "just work". Resist it:
        silently reinterpreting a timezone moves somebody's midnight, and a day boundary that
        shifts without being asked is the exact wrongness invariant 2 exists to prevent. An
        unrecognised zone is a client bug worth surfacing.
        """
        assert normalise_timezone("Asia/Calcutta") == "Asia/Calcutta"
        assert normalise_timezone("IST") == FALLBACK_TIMEZONE


class TestDayBoundary:
    def test_the_same_instant_is_two_different_dates_in_two_zones(self):
        """The whole reason the boundary is per-user and not global."""
        instant = datetime(2026, 8, 4, 20, 30, tzinfo=UTC)
        assert local_date_for("UTC", instant) == date(2026, 8, 4)
        # 02:00 on the 5th in Kolkata (UTC+5:30) — a different day, same moment.
        assert local_date_for("Asia/Kolkata", instant) == date(2026, 8, 5)
        # 16:30 on the 4th in New York.
        assert local_date_for("America/New_York", instant) == date(2026, 8, 4)

    def test_just_before_and_just_after_a_users_midnight(self):
        """The boundary itself, from the user's side rather than UTC's.

        18:29 UTC is 23:59 in Kolkata and 18:30 UTC is 00:00 — so one minute of server time moves a
        count a whole day for that user. This is the case a naive `utcnow().date()` gets wrong for
        every Indian user for five and a half hours out of every day, which given where this app's
        users are is most of the evening scrolling it exists to measure.
        """
        before = datetime(2026, 8, 4, 18, 29, tzinfo=UTC)
        after = datetime(2026, 8, 4, 18, 30, tzinfo=UTC)
        assert local_date_for("Asia/Kolkata", before) == date(2026, 8, 4)
        assert local_date_for("Asia/Kolkata", after) == date(2026, 8, 5)

    def test_dst_forward_and_back(self):
        """A zone that actually changes offset, on both transitions.

        Kolkata has no DST, so testing only against it would leave the offset arithmetic
        permanently unexercised. New York's 2026 transitions are 8 March and 1 November.
        """
        # Spring forward: 06:30 UTC on 8 March is 01:30 EST, still the 8th.
        assert local_date_for("America/New_York", datetime(2026, 3, 8, 6, 30, tzinfo=UTC)) == date(
            2026, 3, 8
        )
        # Fall back: 05:30 UTC on 1 November is 01:30 EDT, still the 1st.
        assert local_date_for("America/New_York", datetime(2026, 11, 1, 5, 30, tzinfo=UTC)) == date(
            2026, 11, 1
        )
        # And the boundary either side of it: 04:00 UTC on 1 Nov is 00:00 EDT.
        assert local_date_for("America/New_York", datetime(2026, 11, 1, 3, 59, tzinfo=UTC)) == date(
            2026, 10, 31
        )

    def test_a_naive_datetime_is_refused(self):
        """Because assuming it is UTC is wrong on any host that is not UTC.

        The mistake is `datetime.now()` where `datetime.now(UTC)` was meant, and it is invisible in
        a UTC container — it surfaces the day somebody provisions one that is not.
        """
        with pytest.raises(ValueError, match="aware datetime"):
            local_date_for("UTC", datetime(2026, 8, 4, 20, 30))

    def test_an_aware_non_utc_instant_is_converted_not_assumed(self):
        """The input's own offset must be honoured, not stripped."""
        instant = datetime(2026, 8, 5, 2, 0, tzinfo=timezone(timedelta(hours=5, minutes=30)))
        assert local_date_for("UTC", instant) == date(2026, 8, 4)

    def test_an_unusable_zone_dates_the_count_rather_than_dropping_it(self):
        """Falls back instead of raising, on purpose.

        This runs on the sync path. A count dated in UTC is metadata that is slightly wrong; a
        rejected batch is a count that never arrives — and because the client is idempotent and
        retries forever, it is a batch that can never succeed. The product is the count.
        """
        instant = datetime(2026, 8, 4, 20, 30, tzinfo=UTC)
        assert local_date_for("Not/AZone", instant) == date(2026, 8, 4)

    def test_it_defaults_to_now(self):
        """No `instant` means server-now, which is what the sync path passes."""
        today = local_date_for("UTC")
        assert abs((today - datetime.now(UTC).date()).days) <= 1


def test_the_timezone_database_is_available_here():
    """The startup assertion, exercised.

    If this fails in CI, CI's image lost its tz database and every day-boundary computation on it
    is meaningless — which is worth knowing immediately rather than via a subtly wrong date in a
    different test.
    """
    assert_tzdata_available()
