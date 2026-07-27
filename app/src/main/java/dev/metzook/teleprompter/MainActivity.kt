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
        if (KeyCommandBus.active) {
            val slot = KeyCommandBus.slotForKeyCode(keyCode)
            if (slot != null) {
                KeyCommandBus.emit(slot)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
