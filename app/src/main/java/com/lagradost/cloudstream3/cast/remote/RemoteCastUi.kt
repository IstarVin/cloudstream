package com.lagradost.cloudstream3.cast.remote

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.MotionEvent
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.mediarouter.app.MediaRouteButton
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialog
import com.lagradost.cloudstream3.utils.UIHelper.getResourceColor
import com.lagradost.cloudstream3.utils.txt

/** All dialogs for connecting the phone to a CloudStream TV, driven by the cast button. */
object RemoteCastUi {
    /**
     * Turns the existing Chromecast button on the result page into the entry point for both Chromecast
     * and CloudStream TV. Tapping it shows a small chooser, "Chromecast" opens the same picker as before.
     *
     * @param chromecastSupported if the current provider supports Chromecast
     * @param chromecastAvailable if Chromecast can be used at all on this device (Google Play services)
     */
    @SuppressLint("ClickableViewAccessibility")
    fun setUpCastButton(
        activity: Activity,
        button: MediaRouteButton,
        chromecastSupported: Boolean,
        chromecastAvailable: Boolean,
    ) {
        // Always usable as the TV cast works for every provider, the dimming was only for Chromecast
        button.alpha = 1f
        button.visibility = android.view.View.VISIBLE

        fun refresh() = updateButtonState(activity, button)
        refresh()

        val showChromecast: (() -> Unit)? = if (chromecastAvailable) {
            {
                if (chromecastSupported) {
                    button.showDialog()
                } else {
                    showToast(R.string.no_chromecast_support_toast, Toast.LENGTH_LONG)
                }
            }
        } else null

        // The button handles its own clicks and always opens the Chromecast dialog,
        // so touches are taken over here instead of changing the layout
        button.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP &&
                event.x in 0f..view.width.toFloat() && event.y in 0f..view.height.toFloat()
            ) {
                onCastButtonClicked(activity, showChromecast, ::refresh)
            }
            true
        }
    }

    /** Highlights the button while connected to a TV */
    private fun updateButtonState(activity: Activity, button: MediaRouteButton) {
        button.background = if (RemoteCast.isActive) {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(activity.getResourceColor(R.attr.colorPrimary, 0.35f))
            }
        } else null
    }

    /**
     * Called when the cast button is pressed.
     *
     * @param showChromecast opens the normal Chromecast picker, null if Chromecast is not available
     * @param onChanged called after connecting or disconnecting, to refresh the button
     */
    fun onCastButtonClicked(
        activity: Activity?,
        showChromecast: (() -> Unit)?,
        onChanged: () -> Unit,
    ) {
        if (activity == null) return

        val connectedName = RemoteCast.takeIf { it.isActive }?.let {
            it.deviceName ?: it.device?.name
        }

        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (connectedName != null) {
            options += activity.getString(R.string.remote_cast_option_disconnect, connectedName) to {
                RemoteCast.disconnect()
                onChanged()
            }
        } else {
            options += activity.getString(R.string.remote_cast_option_cloudstream) to {
                showDevicePicker(activity, onChanged)
            }
        }
        showChromecast?.let {
            options += activity.getString(R.string.remote_cast_option_chromecast) to it
        }

        // No need to ask when there is only one thing that can be done
        if (options.size == 1) {
            options.first().second()
            return
        }

        activity.showBottomDialog(
            options.map { it.first },
            -1,
            activity.getString(R.string.remote_cast_chooser_title),
            false,
            {},
        ) { index ->
            options.getOrNull(index)?.second?.invoke()
        }
    }

    private fun showDevicePicker(activity: Activity, onChanged: () -> Unit) {
        val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1)
        val list = ListView(activity).apply { this.adapter = adapter }
        var devices = emptyList<RemoteCastDevice>()

        val dialog = AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setTitle(R.string.remote_cast_select_device)
            .setMessage(R.string.remote_cast_searching)
            .setView(list)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        fun refresh(found: List<RemoteCastDevice>) {
            devices = found
            adapter.clear()
            adapter.addAll(found.map { "${it.name}  (${it.host})" })
            adapter.add(activity.getString(R.string.remote_cast_enter_ip))
        }
        refresh(emptyList())

        list.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            val device = devices.getOrNull(position)
            if (device != null) {
                connect(activity, device, onChanged)
            } else {
                showEnterIp(activity, onChanged)
            }
        }

        dialog.setOnDismissListener { RemoteCastDiscovery.stopDiscovery() }
        dialog.show()
        RemoteCastDiscovery.startDiscovery(activity) { found ->
            if (dialog.isShowing) refresh(found)
        }
    }

    private fun showEnterIp(activity: Activity, onChanged: () -> Unit) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            hint = "192.168.1.20"
        }
        AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setTitle(R.string.remote_cast_enter_ip_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                // Accepts "host" and "host:port"
                val host = text.substringBefore(":")
                val port = text.substringAfter(":", "").toIntOrNull() ?: REMOTE_CAST_DEFAULT_PORT
                connect(activity, RemoteCastDevice(name = host, host = host, port = port), onChanged)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun connect(activity: Activity, device: RemoteCastDevice, onChanged: () -> Unit) {
        showToast(txt(R.string.remote_cast_connecting, device.name), Toast.LENGTH_SHORT)
        RemoteCast.connect(
            activity,
            device,
            pinProvider = { name, wasWrong, submit -> showPinPrompt(activity, name, wasWrong, submit) },
        ) { result ->
            when (result) {
                is RemoteCast.ConnectResult.Success -> {
                    showToast(txt(R.string.remote_cast_connected, result.deviceName), Toast.LENGTH_SHORT)
                    onChanged()
                }

                is RemoteCast.ConnectResult.Failure -> {
                    // No message means the user cancelled the PIN, nothing to complain about
                    if (result.message != null) {
                        showToast(txt(R.string.remote_cast_connection_failed, device.name), Toast.LENGTH_LONG)
                    }
                    onChanged()
                }
            }
        }
    }

    private fun showPinPrompt(
        activity: Activity,
        deviceName: String,
        wasWrong: Boolean,
        submit: (String?) -> Unit,
    ) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            hint = "0000"
        }
        var answered = false
        fun answer(value: String?) {
            if (answered) return
            answered = true
            submit(value)
        }

        val message = activity.getString(R.string.remote_cast_enter_pin_message, deviceName)
        AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setTitle(R.string.remote_cast_enter_pin_title)
            .setMessage(
                if (wasWrong) "${activity.getString(R.string.remote_cast_wrong_pin)}\n\n$message" else message
            )
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> answer(input.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> answer(null) }
            .setOnCancelListener { answer(null) }
            .show()
    }
}
