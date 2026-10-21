# Binance Phone Scanner (Android, no server)

A native Android app that directly scans Binance USDT-M perpetual futures using public market data. It needs no Binance API key, cloud server, paid server, Firebase, or Telegram.

## Conditions
- At each hour at HH:50 Korea time.
- Current in-progress 1h candle open-to-current-price >= +5%.
- Current candle's accumulated base-asset volume >= 5x previous completed 1h candle's base-asset volume.
- EMA30 > EMA50, calculated from completed 1h candles only.
- Shows a local Android notification with sound for matches.
- Does not place orders.

## Build APK without installing Android Studio on your PC
This project includes a GitHub Actions workflow that builds a debug APK in GitHub's hosted runner. You need a GitHub account and internet access. This does use GitHub's temporary build machine, but **the finished app does not need a server**; it runs on your phone and calls Binance directly.

1. Create a new repository on GitHub. A private repository is fine.
2. On the repository page, choose **Add file → Upload files**.
3. Unzip this project on your PC. Upload the *contents* of the `binance_phone_scanner` folder (including the hidden `.github` folder) to the repository's root. If the browser doesn't show `.github`, enable showing hidden files or upload the workflow separately by creating `.github/workflows/build-apk.yml` with the included file's contents.
4. Commit the files to the `main` branch.
5. Open the repository's **Actions** tab and select **Build Android APK**. If prompted, enable Actions. Run **Run workflow** if the workflow has not started automatically.
6. When the run finishes with a green check, open that run, scroll to **Artifacts**, and download `binance-phone-scanner-debug-apk`.
7. Unzip the artifact; it contains `app-debug.apk`. Send it to your Android phone and open it to install. Android may ask you to allow installation from that source.
8. Open the app, allow notifications, and tap **Start monitoring**.

The APK built this way is a debug/sideload APK, not a Play Store release. Keep the GitHub repository private if you prefer not to publish the source code. Do not put passwords, API keys, or other secrets in the repository; this app does not need a Binance API key.

## Alternative: build locally
Open this folder in Android Studio, sync Gradle, then use **Build → Build Bundle(s) / APK(s) → Build APK(s)**. The output is normally `app/build/outputs/apk/debug/app-debug.apk`.

## Use
- Start monitoring from inside the app. Android requires a user-visible foreground-service notification.
- Stop monitoring from the app or the persistent notification's Stop action.
- The scanner aligns its scan to HH:50 Korea time. It scans all symbols with bounded concurrency.
- If there are matches, each matching symbol gets a local notification. If no symbols match, no alert is shown.
- On reboot, reopen the app and tap Start monitoring. This starter project intentionally does not auto-start a long-running service from boot.

## Important limitations
- No app can guarantee perfect 24/7 execution on every Android device. Force-stop, reboot, disabled notifications, loss of internet, battery policies, or manufacturer-specific task killers can interrupt monitoring.
- For best reliability, allow notifications, set battery usage to Unrestricted / Don't optimize for this app (settings wording varies by device), and keep mobile data enabled.
- The scanner uses Binance's public REST API. Network delays and Binance API rate limits can cause partial scan failures.
- It uses Binance kline base-asset volume, not quote-asset turnover.
- EMA values use completed candles only. Price and volume conditions use the current unfinished candle at HH:50, so conditions may change before the candle closes.
- It uses `specialUse` foreground-service type. Sideload builds may work, but Play Store distribution has additional policy review requirements.
