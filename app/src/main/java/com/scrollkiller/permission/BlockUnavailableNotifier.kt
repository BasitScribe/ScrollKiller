package com.scrollkiller.permission

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.scrollkiller.R

/**
 * Tells the user, OUTSIDE the app, that ScrollKiller cannot block — because that is where they are
 * when it matters (D51).
 *
 * ## Where this sits in the layering, and what it is NOT
 * This is the NUDGE, not the safety net. It can itself fail — POST_NOTIFICATIONS may be denied, and
 * if the accessibility service is off then nothing is running to post anything at all. The
 * guaranteed signal is the Home banner, which needs no permission and covers every case including
 * "we could not even notify you". Treating this class as the backstop would rebuild the exact bug
 * D51 fixes one level up.
 *
 * ## One notification, updated — not two
 * The early warning (you entered Reels and we noticed the permission is gone) and the at-the-limit
 * warning (you actually hit the limit and we were refused) share [NOTIFICATION_ID], so the second
 * REPLACES the first rather than stacking. They are the same problem at two moments, and two
 * notifications about one broken permission is how a warning becomes noise.
 *
 * ## Why there is no rate limiter for the at-limit case
 * [warnBlockPrevented] is called from the overlay's render path, which runs on every count
 * emission — so while the user is over the limit it fires repeatedly. `setOnlyAlertOnce(true)`
 * makes every post after the first update the notification SILENTLY: the text stays current, the
 * phone buzzes once. That is a property of the notification rather than state we have to keep
 * correct, which is why no counter or timestamp is needed here. The EARLY warning does need state
 * — see [warnEarly].
 */
class BlockUnavailableNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    /** Created lazily on first post; creating a channel that already exists is a no-op. */
    private var channelReady = false

    /**
     * Whether a warning is currently up. Exists so [cancel] is FREE in the normal case: it is
     * called from the overlay's render path on every count emission when the permission is fine,
     * and an unguarded `NotificationManager.cancel` there would be a binder call per reel to
     * dismiss a notification that was never posted.
     */
    private var posted = false

    /**
     * The app is running with the block dead, and the user has just walked into a reel surface.
     *
     * Fires at most once per [dayKey]. The caller owns that state (see
     * [com.scrollkiller.data.SettingsPrefs.blockWarnedOn]) because "have we warned today" must
     * survive the service being restarted — which happens far more often than a day rolls over,
     * and an in-memory flag would re-warn on every restart.
     *
     * This is the half that would have caught the D51 incident at reel 1 rather than reel 108.
     */
    fun warnEarly() {
        post(
            title = context.getString(R.string.notif_block_unavailable_early_title),
            body = context.getString(R.string.notif_block_unavailable_body),
        )
    }

    /**
     * A block SHOULD have fired and could not. The moment the promise was actually broken, so the
     * copy says so plainly rather than talking about permissions in the abstract.
     */
    fun warnBlockPrevented() {
        post(
            title = context.getString(R.string.notif_block_unavailable_title),
            body = context.getString(R.string.notif_block_unavailable_body),
        )
    }

    /**
     * The permission came back — clear the warning.
     *
     * Called from the same render path that posts it, so recovery is noticed at the next count
     * emission without anything having to poll. A "ScrollKiller can't block" notification left
     * sitting in the shade after the user has already fixed it is its own small dishonesty, and it
     * teaches people that our warnings are stale.
     */
    fun cancel() {
        if (!posted) return
        posted = false
        try {
            manager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "cancel failed", e)
        }
    }

    private fun post(title: String, body: String) {
        // Checked rather than assumed: on API 33+ this is a runtime permission the user may have
        // denied, and notify() would throw or silently drop. Failing here is fine — the Home
        // banner is the layer that cannot fail.
        if (!PermissionHealthReader.canNotify(context)) {
            Log.w(TAG, "cannot warn: notifications are off. The Home banner is the only signal.")
            return
        }
        ensureChannel()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            // Re-posted on many count emissions while over the limit; without this the phone
            // buzzes on every reel. See the class doc.
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(overlaySettingsIntent())
            .build()

        try {
            manager.notify(NOTIFICATION_ID, notification)
            posted = true
        } catch (e: SecurityException) {
            // Permission revoked between the check and the post. Fail soft; the banner holds.
            Log.w(TAG, "notify refused", e)
        }
    }

    /**
     * Straight to the "Display over other apps" screen for THIS package — one tap from the warning
     * to the fix. A notification that says something is broken and then drops you in a settings
     * root screen is a notification people close.
     *
     * FLAG_IMMUTABLE is required from API 31 and correct regardless: nothing should be able to
     * rewrite where this intent points.
     */
    private fun overlaySettingsIntent(): PendingIntent {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** HIGH importance: this is the app reporting it cannot do the thing the user installed it for. */
    private fun ensureChannel() {
        if (channelReady || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            channelReady = true
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_health),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notif_channel_health_description)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        val system = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        system?.createNotificationChannel(channel)
        channelReady = true
    }

    private companion object {
        const val TAG = "ScrollKiller"
        const val CHANNEL_ID = "scrollkiller_health"

        /** ONE id, shared by both warnings, so the second updates the first. See the class doc. */
        const val NOTIFICATION_ID = 1001
    }
}
