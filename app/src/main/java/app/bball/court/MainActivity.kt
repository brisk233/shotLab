package app.bball.court

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.bball.court.ui.ShotLabApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(applicationContext)
        enableEdgeToEdge()
        setContent { ShotLabApp(
                initialVideoPath = intent?.getStringExtra("video"),
                initialAtMs = intent?.getStringExtra("at")?.toLongOrNull() ?: 0L,
                initialRim = intent?.getStringExtra("rim"),
            ) }
    }
}
