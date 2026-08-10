package dev.stride.hud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Bring the console back to STRIDE after a power cycle.
 *
 * A treadmill is switched off at the wall, not shut down, so "after a reboot"
 * is the normal way this console starts — and what it came back to was whatever
 * launcher happens to be installed, with STRIDE one tap away. One tap is one
 * too many on a machine whose whole point is that you step on and walk.
 *
 * Two mechanisms, deliberately, because they fail in different directions:
 *
 *  - **This receiver** starts the activity once, when Android says the boot has
 *    finished. It works whatever the home app is, and it changes nothing else
 *    about the device. It is what a plain install gets.
 *  - **The home alias** (see the manifest) makes STRIDE the launcher, so it also
 *    comes back if it is ever dismissed or killed. That one is off until
 *    somebody asks for it, because turning a device's home app into a treadmill
 *    console is not a thing to do behind an installer's back.
 *
 * Starting an activity from a background receiver is allowed here: the console
 * is API 28, one below the release that closed background activity starts. If
 * this app ever targets Android 10 or later, this receiver stops working and
 * the home alias becomes the only route — which is the note to read first when
 * a future console boots to a launcher instead of a walk.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        // QUICKBOOT_POWERON is what MediaTek's quick-boot path sends instead of
        // BOOT_COMPLETED, and this console is a MediaTek board. Listening for
        // only the standard one would work on the bench and not in the hall.
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON") return

        Log.i("Stride", "boot: $action — starting the console")
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    // No task to inherit at boot, so this one has to make its own.
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // A console that cannot start itself must still leave a trace of
            // why. Throwing here would be an ANR-shaped silence instead.
            Log.e("Stride", "boot: could not start the console", e)
        }
    }
}
