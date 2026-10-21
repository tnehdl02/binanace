package com.example.binancephonescanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 56, 48, 48)
            setBackgroundColor(android.graphics.Color.rgb(16, 19, 24))
        }

        val title = TextView(this).apply {
            text = "BINANCE PHONE SCANNER"
            textSize = 23f
            setTextColor(android.graphics.Color.rgb(0, 200, 137))
        }
        statusText = TextView(this).apply {
            text = "\\n감시 시작 버튼을 누르면 휴대폰에서 직접 감시합니다.\\n\\n검사 시각: 매시간 50분 (한국 시간)\\n\\n조건\\n• 1시간봉 상승률 +5% 이상\\n• 현재봉 누적 거래량 ≥ 직전 완료봉의 5배\\n• 완료봉 기준 EMA30 > EMA50\\n\\n서버/바이낸스 API 키 없이 공개 시세를 이용합니다.\\n주문은 실행하지 않습니다."
            textSize = 16f
            setTextColor(android.graphics.Color.WHITE)
        }
        val start = Button(this).apply {
            text = "감시 시작"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                    requestNotificationPermission()
                }
                val intent = Intent(this@MainActivity, ScannerService::class.java).apply {
                    action = ScannerService.ACTION_START
                }
                ContextCompat.startForegroundService(this@MainActivity, intent)
                statusText.text = statusText.text.toString() + "\\n\\n상태: 감시 시작 요청됨"
                Toast.makeText(this@MainActivity, "백그라운드 감시를 시작합니다.", Toast.LENGTH_SHORT).show()
            }
        }
        val stop = Button(this).apply {
            text = "감시 중지"
            setOnClickListener {
                val intent = Intent(this@MainActivity, ScannerService::class.java).apply {
                    action = ScannerService.ACTION_STOP
                }
                startService(intent)
                statusText.text = statusText.text.toString() + "\\n상태: 감시 중지 요청됨"
            }
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(start)
        root.addView(stop)
        setContentView(root)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                2001
            )
        }
    }
}
