package com.cloudgallery.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cloudgallery.app.ui.CloudGalleryApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = androidx.lifecycle.ViewModelProvider(this)[GalleryViewModel::class.java]
        // Retain Android's launch window until local restoration is ready. Never wait for the network.
        val content = findViewById<android.view.View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (model.state.value.restoring) return false
                content.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
        setContent { CloudGalleryApp(model) }
    }
}
