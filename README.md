# UVC Viewer (USB Endoscopy Camera Preview)

This project is a minimal Android app that shows the live preview from a USB endoscopy camera (UVC / USB Video Class) on the screen.

It uses the `org.uvccamera:lib` library to talk to UVC devices over USB host mode.

## What you need

- An Android device that supports **USB OTG / USB Host**
- A USB endoscopy camera that is **UVC compliant**
- (Usually) a **USB‑C OTG adapter** or **USB‑C hub** to connect the camera
- Android Studio installed

## Build & run (recommended: Wi‑Fi debugging)

If your Android device has only **one USB‑C port**, you usually can't keep it connected to the laptop for debugging *and* connect the USB camera at the same time. The easiest workflow is **Wireless debugging** (Android 11+):

1. Open Android Studio.
2. Open this project folder.
3. Wait for Gradle sync to finish (it will download dependencies).
4. On the Android device: enable **Developer options** → enable **Wireless debugging**.
5. In Android Studio: **Run** dropdown → **Pair Devices Using Wi‑Fi** → pair using QR / pairing code.
6. Click **Run** to install the app.
7. Now connect the USB endoscopy camera to the Android device (OTG).
8. Grant:
   - the app's **Camera** runtime permission
   - the **USB device permission** dialog

## Using the app

- The app auto-requests USB permission when it sees a device.
- If nothing happens, tap **Retry**.
- Tap **Disconnect** to close the camera.

## Troubleshooting

- If you never get a USB permission dialog, confirm Camera permission was granted.
- If the preview stays black:
  - try reconnecting the camera
  - try a different OTG adapter / hub
  - confirm the camera works with a known USB camera app
- Some Android 10 devices have known issues with UVC permission/streaming (device/firmware dependent).

