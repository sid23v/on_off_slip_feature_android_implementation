# ADB Logcat Guide for Tracking Logs

This guide explains how to extract and view the consolidated tracking logs from your Android app using adb logcat.

## Log Format Overview

The app now generates **one consolidated log line per second** containing all tracking information in a single line. This makes it easy to analyze the complete system state at any timestamp.

## Log Format

```
CONSOLIDATED - camera:[Connected/Disconnected];tracker:[Active/Inactive];camera_fps:[0.0-30.0];queue_size:[0-1];frames_dropped:[0-N];processing_fps:[0-N];motion_params:[...];color:[green/yellow/red/None];reset_status:[None/Reason]
```

## Log Fields Explained

### 1. Camera Connection State
- **camera**: `Connected` or `Disconnected`
- Shows whether the USB camera is currently connected to the device

### 2. Tracker State
- **tracker**: `Active` or `Inactive`
- Shows whether tracking is currently running (reference has been set)
- If camera is disconnected, this will always be `Inactive`

### 3. Camera Frame Rate
- **camera_fps**: Frames per second received from camera (e.g., `30.0`)
- If camera is disconnected, this will be `0.0`

### 4. Queue Size
- **queue_size**: Number of frames in the processing queue (0 or 1)
- If camera is disconnected or tracker is inactive, this will be `0`

### 5. Frames Dropped
- **frames_dropped**: Number of frames dropped from the queue in the last second
- If camera is disconnected or tracker is inactive, this will be `0`

### 6. Processing Rate
- **processing_fps**: Frames per second processed by the tracker
- If camera is disconnected or tracker is inactive, this will be `0`

### 7. Motion Parameters
- **motion_params**: Contains all motion parameters with current values and thresholds
- Format: `[distance_current:X;distance_threshold:Y;scale_current:A;scale_min:B;scale_max:C;lost_counter:D;lost_frames_max:E;feature_loss_counter:F;feature_loss_limit:G;low_entropy_counter:H;low_entropy_limit:I;distance_exceed_counter:J;distance_confirm_frames:K;scale_outlier_counter:L;scale_outlier_limit:M]`
- If camera is disconnected or tracker is inactive, current values will be `0` or `1.000` (default)

### 8. Color Indicator
- **color**: `green`, `yellow`, `red`, or `None`
- Shows the color of the circular indicator
- If camera is disconnected or tracker is inactive, this will be `None`

### 9. Reset Status
- **reset_status**: `None` or the reason for reset (e.g., `Auto-reset (distance threshold)`, `Manual reset`)
- Only populated when tracking transitions from active to inactive
- If camera is disconnected or tracking is still active, this will be `None`

## Example Log Lines

### Camera Connected, Tracking Active
```
CONSOLIDATED - camera:Connected;tracker:Active;camera_fps:30.0;queue_size:1;frames_dropped:0;processing_fps:30;motion_params:[distance_current:45;distance_threshold:260;scale_current:1.050;scale_min:0.85;scale_max:1.75;lost_counter:0;lost_frames_max:3;feature_loss_counter:0;feature_loss_limit:7;low_entropy_counter:0;low_entropy_limit:7;distance_exceed_counter:0;distance_confirm_frames:2;scale_outlier_counter:0;scale_outlier_limit:7];color:green;reset_status:None
```

### Camera Disconnected
```
CONSOLIDATED - camera:Disconnected;tracker:Inactive;camera_fps:0.0;queue_size:0;frames_dropped:0;processing_fps:0;motion_params:[distance_current:0;distance_threshold:260;scale_current:1.000;scale_min:0.85;scale_max:1.75;lost_counter:0;lost_frames_max:3;feature_loss_counter:0;feature_loss_limit:7;low_entropy_counter:0;low_entropy_limit:7;distance_exceed_counter:0;distance_confirm_frames:2;scale_outlier_counter:0;scale_outlier_limit:7];color:None;reset_status:None
```

### After Auto-Reset
```
CONSOLIDATED - camera:Connected;tracker:Inactive;camera_fps:30.0;queue_size:0;frames_dropped:0;processing_fps:0;motion_params:[distance_current:0;distance_threshold:260;scale_current:1.000;scale_min:0.85;scale_max:1.75;lost_counter:0;lost_frames_max:3;feature_loss_counter:0;feature_loss_limit:7;low_entropy_counter:0;low_entropy_limit:7;distance_exceed_counter:0;distance_confirm_frames:2;scale_outlier_counter:0;scale_outlier_limit:7];color:None;reset_status:Auto-reset (distance threshold)
```

## Prerequisites

1. **Enable USB Debugging on your Android device:**
   - Go to Settings > About Phone
   - Tap "Build Number" 7 times to enable Developer Options
   - Go to Settings > Developer Options
   - Enable "USB Debugging"

2. **Install ADB (Android Debug Bridge):**
   - Download Android Studio from https://developer.android.com/studio
   - ADB is included with Android Studio
   - Or download the standalone Android SDK Platform Tools from https://developer.android.com/studio/releases/platform-tools
   - Add the platform-tools directory to your system PATH

3. **Connect your device:**
   - Connect your Android device to your computer via USB
   - Accept the debugging prompt on your device

## Verify ADB Connection

Open a command prompt/terminal and run:
```bash
adb devices
```

You should see your device listed. If not, check USB debugging is enabled and the device is connected.

## Building and Installing the App

1. **Build the app:**
   ```bash
   cd C:\Users\Admin\Desktop\on_off_slip_android_v3
   gradlew assembleDebug
   ```

2. **Install the app on your device:**
   ```bash
   adb install app\build\outputs\apk\debug\app-debug.apk
   ```

## Viewing Logs in Real-Time

### View all consolidated logs:
```bash
adb logcat -s TrackerLog:D
```

### View logs with timestamps:
```bash
adb logcat -v time -s TrackerLog:D
```

### View logs and save to file simultaneously:
```bash
adb logcat -s TrackerLog:D > tracking_logs.txt
```

## Saving Logs to a File

### Save all consolidated logs to a file:
```bash
adb logcat -s TrackerLog:D > C:\Users\Admin\Desktop\tracking_logs.txt
```

### Save logs with timestamps:
```bash
adb logcat -v time -s TrackerLog:D > C:\Users\Admin\Desktop\tracking_logs_timestamped.txt
```

### Save logs for a specific duration (e.g., 60 seconds):
```bash
timeout 60 adb logcat -s TrackerLog:D > C:\Users\Admin\Desktop\tracking_logs_60s.txt
```

## Clearing Log Buffer

Before starting a new logging session, clear the log buffer:
```bash
adb logcat -c
```

## Filtering Logs

### Filter for specific camera state:
```bash
adb logcat -s TrackerLog:D | findstr "camera:Connected"
```

### Filter for specific tracker state:
```bash
adb logcat -s TrackerLog:D | findstr "tracker:Active"
```

### Filter for reset events:
```bash
adb logcat -s TrackerLog:D | findstr /V "reset_status:None"
```

## Common Issues

### "adb: device not found"
- Check USB debugging is enabled
- Try reconnecting the USB cable
- Try a different USB port
- Restart adb server: `adb kill-server` then `adb start-server`

### No logs appearing
- Make sure the app is running
- Check that the app has been built with the logging code
- Try clearing the log buffer first: `adb logcat -c`

### Logs appear only once per second
- This is expected behavior - the app logs consolidated metrics once per second
- The log contains all information for that timestamp in a single line

## Quick Reference

**Start logging (real-time):**
```bash
adb logcat -s TrackerLog:D
```

**Save logs to file:**
```bash
adb logcat -s TrackerLog:D > C:\Users\Admin\Desktop\tracking_logs.txt
```

**Clear log buffer:**
```bash
adb logcat -c
```

**Check device connection:**
```bash
adb devices
```

**Install app:**
```bash
adb install app\build\outputs\apk\debug\app-debug.apk
```
