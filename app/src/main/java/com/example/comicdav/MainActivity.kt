package com.example.comicdav

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.comicdav.feature.reader.installReaderImageLoader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContainer = (application as ComicDavApplication).appContainer
        installReaderImageLoader(applicationContext)
        setContent { ComicDavApp(appContainer) }
    }
}
