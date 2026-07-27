package dev.metzook.teleprompter

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TeleprompterApp()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (VolumeKeyBus.active) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    VolumeKeyBus.emit(1)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    VolumeKeyBus.emit(-1)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
