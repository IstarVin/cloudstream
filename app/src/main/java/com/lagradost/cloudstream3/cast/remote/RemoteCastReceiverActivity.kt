package com.lagradost.cloudstream3.cast.remote

import android.os.Bundle
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.UIHelper.enableEdgeToEdgeCompat

/**
 * Hosts [RemoteCastReceiverFragment] while a phone is casting to this TV.
 *
 * It is a separate activity so none of the existing navigation graph or [com.lagradost.cloudstream3.MainActivity]
 * has to know about casting. Back closes it and the phone is told.
 */
class RemoteCastReceiverActivity : AppCompatActivity() {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        CommonActivity.dispatchKeyEvent(this, event) ?: super.dispatchKeyEvent(event)

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        CommonActivity.onKeyDown(this, keyCode, event) ?: super.onKeyDown(keyCode, event)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CommonActivity.loadThemes(this)
        CommonActivity.setActivityInstance(this)
        enableEdgeToEdgeCompat()

        val container = FrameLayout(this).apply { id = R.id.remote_cast_receiver_container }
        setContentView(container)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(container.id, RemoteCastReceiverFragment())
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        CommonActivity.setActivityInstance(this)
    }
}
