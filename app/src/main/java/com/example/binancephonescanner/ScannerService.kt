package com.example.binancephonescanner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class ScannerService : Service() {
    companion object {
        const val ACTION_START = "com.example.binancephonescanner.START"
        const val ACTION_STOP = "com.example.binancephonescanner.STOP"
        private const val CHANNEL_SERVICE = "scanner_status"
        private const val CHANNEL_ALERTS = "scanner_alerts"
        private const val SERVICE_NOTIFICATION_ID = 1001
        private const val PRICE_CHANGE_THRESHOLD = 5.0
        private const val VOLUME_MULTIPLE_THRESHOLD = 5.0
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }

    private val running = AtomicBoolean(false)
    private val scheduler = Executors.newSingleThreadExecutor()
    private val scannerPool = Executors.newFixedThreadPool(8)
    private val httpExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(SERVICE_NOTIFICATION_ID, serviceNotification("매시간 50분에 감시합니다."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> startMonitoring()
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        if (!running.compareAndSet(false, true)) return
        updateServiceNotification("감시 실행 중 · 다음 정각 50분 검사")
        scheduler.execute {
            while (running.get()) {
                try {
                    val waitMs = millisUntilNextCheck()
                    updateServiceNotification("감시 실행 중 · 다음 검사까지 ${waitMs / 60000}분")
                    Thread.sleep(waitMs)
                    if (!running.get()) break
                    performScan()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    updateServiceNotification("오류 발생 · 인터넷 연결을 확인하세요")
                    Thread.sleep(30_000)
                }
            }
        }
    }

    private fun stopMonitoring() {
        running.set(false)
        scheduler.shutdownNow()
    }

    private fun millisUntilNextCheck(): Long {
        val now = ZonedDateTime.now(KST)
        var target = now.withMinute(50).withSecond(5).withNano(0)
        if (!now.isBefore(target)) target = target.plusHours(1)
        return max(1_000L, Duration.between(now, target).toMillis())
    }

    private fun performScan() {
        updateServiceNotification("바이낸스 종목 목록 조회 중…")
        val symbols = getSymbols()
        val futures = symbols.map { symbol ->
            scannerPool.submit<Match?> { inspectSymbol(symbol) }
        }
        val matches = mutableListOf<Match>()
        futures.forEachIndexed { index, future ->
            try {
                future.get(25, TimeUnit.SECONDS)?.let { matches.add(it) }
            } catch (_: Exception) {
                // One failed symbol does not prevent the rest of the scan.
                futures[index].cancel(true)
            }
        }

        matches.sortByDescending { it.changePct }
        if (matches.isNotEmpty()) {
            matches.forEach { sendMatchNotification(it) }
        }
        val nowText = ZonedDateTime.now(KST).toString()
        updateServiceNotification("마지막 검사: $nowText · ${symbols.size}종목 · ${matches.size}개 감지")
    }

    private fun getSymbols(): List<String> {
        val json = JSONArray(httpGet("https://fapi.binance.com/fapi/v1/exchangeInfo"))
        val result = ArrayList<String>()
        for (i in 0 until json.length()) {
            val item = json.getJSONObject(i)
            if (item.optString("status") == "TRADING" &&
                item.optString("contractType") == "PERPETUAL" &&
                item.optString("quoteAsset") == "USDT") {
                result.add(item.getString("symbol"))
            }
        }
        return result
    }

    private data class Match(
        val symbol: String,
        val price: Double,
        val changePct: Double,
        val volumeMultiple: Double,
        val ema30: Double,
        val ema50: Double
    )

    private fun inspectSymbol(symbol: String): Match? {
        return try {
            val url = "https://fapi.binance.com/fapi/v1/klines?symbol=$symbol&interval=1h&limit=100"
            val rows = JSONArray(httpGet(url))
            if (rows.length() < 52) return null

            val current = rows.getJSONArray(rows.length() - 1)
            val previousClosed = rows.getJSONArray(rows.length() - 2)
            val closes = ArrayList<Double>()
            for (i in 0 until rows.length() - 1) {
                closes.add(rows.getJSONArray(i).getString(4).toDouble())
            }

            val openPrice = current.getString(1).toDouble()
            val livePrice = current.getString(4).toDouble()
            val currentVolume = current.getString(5).toDouble()
            val previousVolume = previousClosed.getString(5).toDouble()
            if (openPrice <= 0 || previousVolume <= 0) return null

            val changePct = (livePrice - openPrice) / openPrice * 100.0
            val volumeMultiple = currentVolume / previousVolume
            val ema30 = calculateEma(closes, 30)
            val ema50 = calculateEma(closes, 50)

            if (changePct >= PRICE_CHANGE_THRESHOLD &&
                volumeMultiple >= VOLUME_MULTIPLE_THRESHOLD &&
                ema30 > ema50) {
                Match(symbol, livePrice, changePct, volumeMultiple, ema30, ema50)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun calculateEma(values: List<Double>, period: Int): Double {
        val alpha = 2.0 / (period + 1.0)
        var result = values.first()
        for (i in 1 until values.size) {
            result = alpha * values[i] + (1.0 - alpha) * result
        }
        return result
    }

    private fun httpGet(urlString: String): String {
        val connection = (URL(urlString).openConnection() as HttpURLConnection)
        connection.requestMethod = "GET"
        connection.connectTimeout = 12_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", "BinancePhoneScanner/1.0")
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("HTTP $code: $text")
            text
        } finally {
            connection.disconnect()
        }
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_SERVICE, "감시 상태", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "바이낸스 감시 서비스 실행 상태" }
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERTS, "급등 감지 알림", NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        description = "급등 조건을 만족한 바이낸스 선물 종목"
                        setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, null)
                        enableVibration(true)
                    }
            )
        }
    }

    private fun serviceNotification(message: String): Notification {
        val stopIntent = Intent(this, ScannerService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 101, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 102, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Binance Phone Scanner")
            .setContentText(message)
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(0, "감시 중지", stopPending)
            .build()
    }

    private fun updateServiceNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(SERVICE_NOTIFICATION_ID, serviceNotification(message))
    }

    private fun sendMatchNotification(match: Match) {
        val manager = getSystemService(NotificationManager::class.java)
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, match.symbol.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = "상승률 +%.2f%% · 거래량 %.2fx · EMA30 > EMA50\n현재가 %s".format(
            match.changePct, match.volumeMultiple, match.price.toString()
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("급등 감지: ${match.symbol}")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running.set(false)
        scheduler.shutdownNow()
        scannerPool.shutdownNow()
        httpExecutor.shutdownNow()
        super.onDestroy()
    }
}
