package org.mubox.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.mubox.reader.feature.reader.installReaderImageLoader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContainer = (application as MuBoxApplication).appContainer
        installReaderImageLoader(applicationContext)
        setContent { MuBoxApp(appContainer) }
    }
}
