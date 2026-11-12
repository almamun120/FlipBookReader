// SplashActivity.kt
package com.bookflip.reader

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.animation.AnimationUtils
import androidx.appcompat.app.AppCompatActivity
import com.bookflip.reader.databinding.ActivitySplashBinding

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen
        @Suppress("DEPRECATION")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startAnimations()

        Handler(Looper.getMainLooper()).postDelayed({
            navigateToHome()
        }, 1300)
    }

    private fun startAnimations() {
        val fadeIn   = AnimationUtils.loadAnimation(this, R.anim.fade_in)
        val slideUp  = AnimationUtils.loadAnimation(this, R.anim.slide_up)
        val zoomIn   = AnimationUtils.loadAnimation(this, R.anim.zoom_in)

        binding.ivLogo.startAnimation(zoomIn)
        binding.tvAppName.startAnimation(slideUp)
        binding.tvTagline.startAnimation(fadeIn)
        // binding.lottieLoading.playAnimation()
    }

    private fun navigateToHome() {
        val intent = Intent(this, HomeActivity::class.java)
        startActivity(intent)
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        finish()
    }
}