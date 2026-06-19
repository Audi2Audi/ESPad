# Freenove 4WD Car Controller — Android App

Custom Android controller app for the Freenove 4WD Car ESP32.
Replaces the Freenove app with full GameSir G8 Plus Bluetooth gamepad support.

## Features
- Live camera stream from the car (port 7000)
- GameSir G8 Plus (or any Bluetooth HID gamepad) control
- All car functions accessible via gamepad + on-screen buttons
- Battery voltage display
- IP address entry dialog on launch

## Gamepad Control Map (G8 Plus in Android mode)
| Input | Function |
|---|---|
| Left Stick Y | Forward / Reverse |
| Left Stick X | Steering |
| Right Stick | Camera pan (X) and tilt (Y) |
| X button | Buzzer horn (hold) |
| Circle | Cycle WS2812 LED mode |
| Square | Cycle emotion/matrix mode |
| Triangle | Camera flip |
| L1 | Reset servos to centre |
| R1 | Request battery voltage |

## On-Screen Buttons
| Button | Function |
|---|---|
| 💡 Follow | Light-following mode |
| 🛤 Track | Line-tracking mode |
| 🌈 LED Cycle | Cycle through LED modes 0-5 |
| ⬛ LED Off | Turn off LEDs |
| 😊 Emotion Cycle | Cycle through emotion matrix 0-7 |
| ⬛ Emotion Off | Clear emotion matrix |
| 📢 Buzzer | Toggle buzzer |
| 🎯 Servo Reset | Reset pan/tilt to 90°/90° |
| 🔄 Cam Flip | Toggle camera vertical flip |

## Setup

### 1. Open in Android Studio
- Open the `FreenoveController` folder as an existing project
- Let Gradle sync complete

### 2. Connect your phone
- Enable USB debugging on your Android phone
- Connect via USB and run the app

### 3. Connect to the car
- Power on the car
- On your phone, connect to the **Sunshine** WiFi network (password: Sunshine)
- Launch the app — enter `192.168.4.1` in the IP dialog
- If using router mode, enter the car's router IP (e.g. `192.168.2.166`)

### 4. Pair the GameSir G8 Plus
- Put the G8 Plus into Android Bluetooth mode (Home + A to power on)
- Pair it to your phone via Settings → Bluetooth → GameSir-G8+_G
- Once paired, the app reads gamepad input automatically

## Architecture
- `MainActivity.kt` — main activity, gamepad input, UI
- `TcpClient.kt` — TCP socket connection to car port 4000
- `CameraStreamClient.kt` — reads JPEG frames from port 7000, draws to SurfaceView
- `ControlPanelView.kt` — on-screen button panel
- `IpDialogFragment.kt` — IP entry dialog

## TCP Command Protocol
Commands follow the Freenove protocol: `CMD_NAME#param1#param2\n`

| Command | Format | Description |
|---|---|---|
| CMD_MOTOR | `CMD_MOTOR#left#0#right#0\n` | Motor speeds -4095 to 4095 |
| CMD_CAMERA | `CMD_CAMERA#pan#tilt\n` | Servo angles 0-180 |
| CMD_SERVO | `CMD_SERVO#index#angle\n` | Individual servo |
| CMD_BUZZER | `CMD_BUZZER#1#2000\n` | Buzzer on/off + frequency |
| CMD_LED_MOD | `CMD_LED_MOD#mode\n` | WS2812 mode 0-5 |
| CMD_MATRIX_MOD | `CMD_MATRIX_MOD#mode\n` | Emotion matrix mode 0-7 |
| CMD_POWER | `CMD_POWER#0\n` | Request battery voltage |
| CMD_CAR_MODE | `CMD_CAR_MODE#mode\n` | 0=manual 1=light 2=track |
