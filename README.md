# WaterH Sync for Android

Unofficial Android app that syncs your [WaterH](https://www.waterh.com) smart bottle in the background. Drink, and a minute later the drink is where you want it, without opening the official app. Two modes:

- **Local only**: drinks go straight from the bottle to **[Home Assistant](https://github.com/raymanvh/ha-waterh)**. No WaterH account, nothing sent to WaterH.
- **WaterH account**: drinks are uploaded to your WaterH account, as the official app does, so they appear in the WaterH app and in the Home Assistant integration's cloud mode.

> **Not affiliated with WaterH.** This app talks to the bottle and to WaterH's private API the same way the official app does, worked out by examining that app. A WaterH firmware or API change could break it at any time.

## Why

The bottle stores each drink on board and only hands it over when the official WaterH app is opened. Until then, WaterH's servers (and anything that reads from them, like Home Assistant) don't see it. The official app doesn't sync in the background.

WaterH Sync does. It watches for your bottle and syncs it whenever the bottle wakes up nearby, typically right after you drink. Drinks taken away from your phone stay on the bottle and sync the next time it's in range.

## Features

- **Background sync**: syncs automatically when the bottle wakes up nearby. Survives reboots and app updates.
- **Safe by design**: drinks are cleared from the bottle only after WaterH has accepted them. If the upload fails, they stay on the bottle and go up on the next sync.
- **Keeps the bottle's clock right**: sets the time on every sync, as the official app does, without touching your goal or reminder settings.
- **Bottle status**: battery level, charging state, and the bottle's own total for today, optionally sent to Home Assistant.
- **Material You design**: follows your wallpaper colors and dark mode.
- **Diagnostics**: a read-only "Check bottle" and a copyable log for troubleshooting.

## Requirements

- Android 12 or newer, with Bluetooth LE.
- **Local only:** Home Assistant with the WaterH integration v0.3.0+. No WaterH account needed.
- **WaterH account:** an email and password login. If you signed up with Google or Facebook, set a password in the WaterH app first.
- Tested with the **WaterH Boost 946 mL** (firmware 1.1.5). Other Boost bottles very likely work. **Vita** bottles use a different status format and aren't supported yet.

## Install

1. Download the latest `.apk` from [**Releases**](https://github.com/raymanvh/waterh-sync-android/releases) on your phone.
2. Open it. Android asks you to allow installing apps from your browser or file manager the first time; allow it, then tap **Install**.

Or build it yourself (see [Building](#building)).

## Setup

### Local only (Home Assistant, no WaterH account)

1. In Home Assistant, install the [WaterH integration](https://github.com/raymanvh/ha-waterh) (v0.3.0 or newer), then **Add integration → WaterH → Local only**, and copy the webhook URL it shows.
2. **Close the official WaterH app** and keep it closed. If it connects to the bottle, it takes the drinks, and Home Assistant never sees them.
3. Open **WaterH Sync**, choose **Local only**, paste the URL, and tap **Connect**.
4. Tap **Search for bottles**, tilt the bottle to wake it, and pick it.
5. Tap **Sync now** once, then turn on **Automatic sync** in Settings.

Your daily goal is set in Home Assistant (the **Daily goal** control), and the app writes it to the bottle on every sync. Drinks are cleared from the bottle only after Home Assistant has saved them. If Home Assistant can't be reached (for example, you're away and used a local address), they wait on the bottle until it can.

### WaterH account

1. **Close the official WaterH app** (Settings → Apps → WaterH → **Force stop**). The bottle accepts only one connection at a time, so the two apps would compete for it. You can keep the official app installed and open it occasionally; WaterH Sync just has to be the one connecting most of the time.
2. Open **WaterH Sync**, choose **WaterH account**, and **sign in** with your WaterH email and password.
3. Tap **Search for bottles**, tilt the bottle to wake it, and pick it from the list.
4. Tap **Sync now** once to check everything works.
5. In **Settings**, turn on **Automatic sync**, and allow notifications when asked.

While automatic sync is on, a small "Watching for your bottle" notification stays visible. That's Android's requirement for apps that keep running in the background. You can minimize it in the system notification settings.

### Battery and charging in Home Assistant (optional)

The [WaterH Home Assistant integration](https://github.com/raymanvh/ha-waterh) can show the bottle's battery level and charging state, which only this app can read:

1. In Home Assistant, open **Settings → Devices & services → WaterH → Configure** and copy the webhook URL.
2. In WaterH Sync, open **Settings → Home Assistant → Set up**, paste it, and tap **Save**.

The app reports after every sync; **Send test** reports right away. If you use an `http://` local address, it's only reachable at home, and reports while you're away will fail quietly. Use your external or Nabu Casa remote URL to report from anywhere.

**If syncs seem delayed**, set the app's battery usage to **Unrestricted**: long-press the app icon → **App info** → **App battery usage** → **Unrestricted**.

## How it works

Each sync does what the official app does when you open it:

1. Reads your goal (from WaterH, or from Home Assistant in local mode).
2. Connects to the bottle over Bluetooth LE and reads its status (battery, clock, goal, reminder).
3. Sets the bottle's clock and goal, keeping its reminder settings.
4. Reads the drinks stored on the bottle.
5. Sends them to WaterH (in the official app's format) or, in local mode, to Home Assistant.
6. Once they've been accepted and saved, tells the bottle to clear them.

In the background, the app runs a low-power Bluetooth scan filtered to your bottle. When the bottle advertises (it does when it wakes up), a sync starts, with a 3-minute cooldown between attempts.

The Bluetooth protocol is documented in [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Privacy

- Your **password is never stored**. It's sent once to WaterH to get a sign-in token, which is kept in the app's private storage.
- The app talks only to **your bottle**, **api.waterh.com** (WaterH account mode only), and **your own Home Assistant** (if set up). In local mode it never contacts WaterH. No analytics, no other servers.
- The WaterH client ID and secret in the source are the official app's public app credentials (the same ones every copy of the WaterH app ships with), not personal keys.

## Building

1. Install [Android Studio](https://developer.android.com/studio).
2. **File → Open** this folder and let Gradle sync. If asked which JDK to use, pick 21.
3. Connect your phone with USB debugging on, select it, and press **Run ▶**.

Unit tests for the Bluetooth protocol, including packets captured from a real bottle, are in `app/src/test`.

## Troubleshooting

- **"Couldn't reach the bottle"**: tilt it to wake it, keep it within a few meters, and make sure the official WaterH app isn't running.
- **Drinks not appearing in Home Assistant**: the Home Assistant integration checks WaterH every 5 minutes by default; its **Last update** sensor shows when it last checked.
- **Something else**: open **Diagnostics**, tap **Copy log**, and include it in an [issue](https://github.com/raymanvh/waterh-sync-android/issues). The log contains Bluetooth packets and no account details.

## License

MIT. See [LICENSE](LICENSE). WaterH is a trademark of its owner; this project is not endorsed by WaterH.
