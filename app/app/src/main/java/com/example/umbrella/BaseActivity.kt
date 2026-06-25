package com.example.umbrella

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

// 모든 화면이 상속받는 베이스 액티비티.
// 상태바 영역을 침범하지 않도록 인셋만큼 자동으로 패딩을 잡아준다.
open class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // setContentView보다 먼저 호출해야 적용됨
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        applyInsets()
    }

    private fun applyInsets() {
        val root = window.decorView.rootView
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }
}
