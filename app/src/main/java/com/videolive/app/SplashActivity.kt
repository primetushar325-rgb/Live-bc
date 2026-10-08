package com.videolive.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

/** White splash with the LIVE VIP logo, then straight into the dashboard. */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }, 1200)
    }
}
