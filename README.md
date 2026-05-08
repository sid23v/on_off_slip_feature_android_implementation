# UVC Viewer (USB Endoscopy Camera Preview + Tracking)

This Android app shows a live preview from a USB endoscopy camera (UVC / USB Video Class) and includes an OpenCV-based port of `trial_32.py` for motion/threshold tracking.

It uses the `org.uvccamera:lib` library to talk to UVC devices over USB host mode.

## What you need

- An Android device that supports **USB OTG / USB Host**
- A USB endoscopy camera that is **UVC compliant**
- (Usually) a **USB-C OTG adapter** or **USB-C hub**
- Android Studio installed

## Build & run (recommended: Wi-Fi debugging)

If your Android device has only **one USB-C port**, you usually can't keep it connected to the laptop for debugging *and* connect the USB camera at the same time. The easiest workflow is **Wireless debugging** (Android 11+):

1. Open Android Studio.
2. Open this project folder.
3. Wait for Gradle sync to finish (it will download dependencies).
4. On the Android device: enable **Developer options** -> enable **Wireless debugging**.
5. In Android Studio: Run dropdown -> **Pair Devices Using Wi-Fi** -> pair using QR / pairing code.
6. Click **Run** to install the app.
7. Connect the USB endoscopy camera to the Android device (OTG).
8. Grant:
   - the app's **Camera** runtime permission
   - the **USB device permission** dialog

## Using the app

- The app auto-requests USB permission when it sees a device and starts preview.
- Tap **Set reference** to set the reference point and enable tracking (equivalent to `s` in `trial_32.py`).
- Tap **Reset** to reset tracking (equivalent to `r`).
- Tap **Quit** to close the app (equivalent to `q`).
- The **Radial** and **Axial (Z)** bars are shown below the preview for readability.

## Troubleshooting

- If you never get a USB permission dialog, confirm Camera permission was granted.
- If the preview stays black:
  - try reconnecting the camera
  - try a different OTG adapter / hub
  - confirm the camera works with a known USB camera app
- Some Android versions/devices have UVC quirks (device/firmware dependent).

