package com.espad32.controller

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.espad32.controller.controls.ActiveProfile
import com.espad32.controller.controls.ControlButtonDef
import com.espad32.controller.controls.ControlButtonStorage
import com.espad32.controller.controls.ControlType
import com.espad32.controller.pinmapper.Profiles
import kotlinx.coroutines.*
import java.util.concurrent.LinkedBlockingQueue

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var tvStatus: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvIp: TextView
    private lateinit var tvRecording: TextView
    private lateinit var controlPanelView: ControlPanelView
    private lateinit var joystickLeft: JoystickView
    private lateinit var joystickRight: JoystickView
    private lateinit var controlButtonStorage: ControlButtonStorage
    private lateinit var pinConfigStorageForSensor: com.espad32.controller.pinmapper.PinConfigStorage
    private lateinit var customRoleStorageForSensor: com.espad32.controller.pinmapper.CustomRoleStorage

    private var carIp = "192.168.4.1"
    private var tcpClient: TcpClient? = null
    private var cameraStream: CameraStreamClient? = null
    private lateinit var mediaSaver: MediaSaver

    // Use lifecycleScope so coroutines survive minor lifecycle events
    // For connection persistence across recreation use a companion object scope
    private val connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val scope get() = connectionScope
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Queues ────────────────────────────────────────────────────────
    private val cmdQueue   = LinkedBlockingQueue<String>(16)
    private val motorQueue = LinkedBlockingQueue<String>(2)
    private var senderJob: Job? = null

    // ── Gamepad / motion state ────────────────────────────────────────
    private var lastLeftX = 0f
    private var lastLeftY = 0f
    private val DEADZONE = 0.08f
    private val MOTOR_MAX = 4095
    private val MOTOR_INTERVAL_MS = 50L

    // ── Servo state ───────────────────────────────────────────────────
    private var servo1Angle = 90.0f
    private var servo2Angle = 90.0f
    private var lastServoSendTime = 0L
    private val SERVO_SEND_INTERVAL_MS = 80L
    private var lastCustomPwmSendTime = 0L
    private var lastCustomPwmSentValue = -1

    // ── Sensitivity (loaded from prefs) ───────────────────────────────
    private var g8ServoStep  = 8.0f
    private var g8MotorScale = 1.0f
    private var osServoStep  = 4.0f
    private var osMotorScale = 0.6f

    // ── Mode state ────────────────────────────────────────────────────
    private var currentLedMode     = 0
    private var currentEmotionMode = 0
    private var joysticksEnabled   = false
    private var virtualButtonsEnabled = false
    private var speedCurveExpo    = false
    private var autoStopMs        = 500
    private val PREFS_NAME = "ESPad32Prefs"

    // ── Jobs ──────────────────────────────────────────────────────────
    private var batteryJob: Job? = null
    private var motorJob:   Job? = null

    // ── Motor auto-stop ───────────────────────────────────────────────
    private var lastMotorCmdTime = 0L
    private var lastMotorWasZero = true
    private var highResActive = false
    private val highResHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val switchToHighResRunnable = Runnable {
        if (lastMotorWasZero && !highResActive) {
            highResActive = true
            enqueue("CMD_CAM_RES#UXGA\n")
            CarLogger.log("Camera", "Switched to UXGA (idle)")
        }
    }

    // ── Video dimensions ──────────────────────────────────────────────
    private var frameWidth  = 320
    private var frameHeight = 240

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        carIp          = prefs.getString("ip", "192.168.4.1") ?: "192.168.4.1"
        joysticksEnabled = prefs.getBoolean("joysticks", false)
        virtualButtonsEnabled = prefs.getBoolean("virtual_buttons", false)
        g8ServoStep    = prefs.getFloat("g8ServoStep",  8.0f)
        g8MotorScale   = prefs.getFloat("g8MotorScale", 1.0f)
        osServoStep    = prefs.getFloat("osServoStep",  4.0f)
        osMotorScale   = prefs.getFloat("osMotorScale", 0.6f)
        speedCurveExpo = prefs.getString("speedCurve","linear") == "exponential"
        autoStopMs     = prefs.getInt("autoStopMs", 500)

        CarLogger.init(this)
        CarLogger.log("Main", "App started")

        surfaceView      = findViewById(R.id.surfaceView)
        tvStatus         = findViewById(R.id.tvStatus)
        tvBattery        = findViewById(R.id.tvBattery)
        tvBattery.visibility = android.view.View.GONE // shown only if the active profile has an ANALOG_INPUT role assigned
        tvIp             = findViewById(R.id.tvIp)
        findViewById<android.widget.Button>(R.id.btnSearchDevicesTop).setOnClickListener {
            searchForDevicesFromMain()
        }
        tvRecording      = findViewById(R.id.tvRecording)
        controlPanelView = findViewById(R.id.controlPanel)
        joystickLeft     = findViewById(R.id.joystickLeft)
        joystickRight    = findViewById(R.id.joystickRight)
        controlButtonStorage = ControlButtonStorage(this)
        pinConfigStorageForSensor = com.espad32.controller.pinmapper.PinConfigStorage(this)
        customRoleStorageForSensor = com.espad32.controller.pinmapper.CustomRoleStorage(this)

        mediaSaver = MediaSaver(this)
        ThemeManager.load(this)
        ThemeManager.apply(controlPanelView)
        ControllerMapping.init(this)
        MainTcpHolder.enqueue = { cmd -> enqueue(cmd) }

        requestPermissions()
        surfaceView.holder.addCallback(this)
        setupJoysticks()
        applyJoystickVisibility()
        applyVirtualButtonsVisibility()
        renderLiveButtons()
        updateCameraUiVisibility()
        val cameraControls = findViewById<android.view.ViewGroup>(R.id.cameraControls)
        cameraControls.isClickable = true
        cameraControls.setOnClickListener { }
        findViewById<android.widget.Button>(R.id.btnPhotoOverlay).setOnClickListener { takePhoto() }
        findViewById<android.widget.Button>(R.id.btnRecordOverlay).setOnClickListener { toggleRecording() }
        findViewById<android.widget.Button>(R.id.btnSettingsOverlay).setOnClickListener { showSettings() }
        findViewById<android.widget.Button>(R.id.btnPinMapperOverlay).setOnClickListener {
            startActivity(Intent(this@MainActivity, com.espad32.controller.pinmapper.PinMapperActivity::class.java))
        }
        findViewById<android.widget.Button>(R.id.btnControlsOverlay).setOnClickListener {
            startActivity(Intent(this@MainActivity, com.espad32.controller.controls.ControlsActivity::class.java))
        }

        setupAutoHide()

        tvIp.text = carIp
        CarLogger.log("Main", "Connecting to $carIp")
        // Show placeholder until camera connects
        findViewById<android.view.View>(R.id.cameraPlaceholder)?.visibility = android.view.View.VISIBLE
        applyLastActiveProfileThenConnect()

        controlPanelView.setButtonListener(object : ControlPanelView.ButtonListener {
            override fun onCameraFlip()                 { showUiTemporarily(); cameraStream?.let { it.flipped = !it.flipped } }
            override fun onTakePhoto()                  { showUiTemporarily(); takePhoto() }
            override fun onToggleRecording()            { showUiTemporarily(); toggleRecording() }
            override fun onViewLog()                    { showUiTemporarily(); startActivity(Intent(this@MainActivity, LogViewerActivity::class.java)) }
            override fun onSettings()                   { showUiTemporarily(); showSettings() }
        })

        startMotorLoop()
        startSenderLoop()
    }

    // ── Queue ─────────────────────────────────────────────────────────
    private fun startSenderLoop() {
        // Only restart if not already running
        if (senderJob?.isActive == true) return
        senderJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val cmd = cmdQueue.poll() ?: motorQueue.poll()
                    if (cmd != null) {
                        tcpClient?.send(cmd)
                        CarLogger.log("TX", cmd.trim())
                        delay(8)
                    } else {
                        delay(5)
                    }
                } catch (e: Exception) {
                    // Send failed — TCP likely dropped, wait for reconnect
                    CarLogger.log("TX", "Send error: ${e.message} — waiting for reconnect")
                    delay(500)
                }
            }
        }
    }

    private fun enqueue(cmd: String) {
        when {
            cmd.startsWith("CMD_MOTOR") -> {
                motorQueue.clear(); motorQueue.offer(cmd)
            }
            cmd.startsWith("CMD_CAMERA") -> {
                // Keep only latest servo position
                cmdQueue.removeIf { it.startsWith("CMD_CAMERA") }
                cmdQueue.offer(cmd)
            }
            cmd.startsWith("CMD_MATRIX_MOD") -> {
                // Drop any queued face commands — only latest matters
                // This prevents rapid-press pile-up; the mode counter is already
                // set correctly in executeButtonFunction before enqueue is called
                cmdQueue.removeIf { it.startsWith("CMD_MATRIX_MOD") }
                cmdQueue.offer(cmd)
            }
            cmd.startsWith("CMD_LED_MOD") -> {
                // Drop any queued LED commands — only latest matters
                cmdQueue.removeIf { it.startsWith("CMD_LED_MOD") }
                cmdQueue.offer(cmd)
            }
            else -> {
                if (cmdQueue.size >= 14) cmdQueue.poll()
                cmdQueue.offer(cmd)
            }
        }
    }

    // ── Auto-hide ─────────────────────────────────────────────────────
    private val hideRunnable = Runnable { hideUi() }
    private fun setupAutoHide() {
        surfaceView.setOnClickListener {
            if (controlPanelView.visibility == android.view.View.VISIBLE) {
                mainHandler.removeCallbacks(hideRunnable)
                hideUi()
            } else {
                showUiTemporarily()
            }
        }
        showUiTemporarily()
    }
    private fun showUiTemporarily() {
        mainHandler.removeCallbacks(hideRunnable)
        showUi()
        mainHandler.postDelayed(hideRunnable, 10000)
    }
    private fun showUi() {
        val overlay = findViewById<android.view.View>(R.id.cameraControls)
        overlay.animate().alpha(0f).setDuration(300).withEndAction {
            overlay.visibility = android.view.View.INVISIBLE
        }.start()
        findViewById<android.view.View>(R.id.statusBar).animate().alpha(1f).setDuration(300).start()
        controlPanelView.animate().alpha(1f).setDuration(300).start()
        controlPanelView.visibility = android.view.View.VISIBLE
        tvRecording.visibility = android.view.View.VISIBLE
    }
    private fun hideUi() {
        val overlay = findViewById<android.view.View>(R.id.cameraControls)
        overlay.visibility = android.view.View.VISIBLE
        overlay.animate().alpha(1f).setDuration(500).start()
        findViewById<android.view.View>(R.id.statusBar).animate().alpha(0f).setDuration(500).start()
        controlPanelView.animate().alpha(0f).setDuration(500).withEndAction {
            controlPanelView.visibility = android.view.View.INVISIBLE
        }.start()
        if (!mediaSaver.isRecording()) tvRecording.visibility = android.view.View.INVISIBLE
    }

    // ── Connection ────────────────────────────────────────────────────
    // Solves the case where a device's STA IP wasn't received via the
    // normal TCP response (AP->STA channel switch can drop the
    // connection right as the response is sent). Unlike Settings'
    // version of this (which just fills the IP field for the user to
    // review and Save), this applies directly and reconnects — there's
    // no separate "Save" step on the main screen.
    private fun searchForDevicesFromMain() {
        CarLogger.log("Main", "Searching for devices...")
        com.espad32.controller.controls.DeviceDiscovery.discover { found ->
            when {
                found.isEmpty() -> {
                    android.widget.Toast.makeText(this, "No devices found.", android.widget.Toast.LENGTH_SHORT).show()
                }
                found.size == 1 -> applyDiscoveredIp(found[0].ip, found[0].name)
                else -> {
                    val labels = found.map { "${it.name} (${it.ip})" }.toTypedArray()
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Select a device")
                        .setItems(labels) { _, index -> applyDiscoveredIp(found[index].ip, found[index].name) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
    }

    private fun applyDiscoveredIp(ip: String, name: String) {
        if (ip == carIp) {
            android.widget.Toast.makeText(this, "Already connected to $ip", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        carIp = ip
        tvIp.text = carIp
        getSharedPreferences("ESPad32Prefs", MODE_PRIVATE).edit().putString("ip", carIp).apply()
        CarLogger.log("Main", "Found \"$name\" at $ip — reconnecting")
        findViewById<android.view.View>(R.id.cameraPlaceholder)?.visibility = android.view.View.VISIBLE
        connectToCar()
    }

    // A deliberate "choose your device" moment before connecting, per
    // session — not swapped mid-session, just picked once up front,
    // matching the confirmed usage model (today's train, tomorrow's RC
    // car, never juggling live connections at once). Skips the dialog
    // entirely when there's nothing to choose (0 or 1 profiles exist),
    // since forcing a picker with a single option would just be
    // annoying rather than useful.
    // Silently uses whichever device was active last time — no prompt.
    // Switching devices happens via Pin Mapper's profile tabs (tapping
    // one already sets ActiveProfile); this just picks up whatever that
    // left behind, rather than asking again on every launch. Previously
    // showed a "Which device are you using?" dialog here every time —
    // removed per direct feedback that the extra confirmation step was
    // more friction than help once switching was already easy elsewhere.
    private fun applyLastActiveProfileThenConnect() {
        val profiles = com.espad32.controller.pinmapper.ProfileResolver.allProfiles(this)
        if (profiles.isEmpty()) {
            connectToCar()
            return
        }

        val activeKey = com.espad32.controller.controls.ActiveProfile.get(this, Profiles.TRAIN.key)
        val active = profiles.find { it.key == activeKey } ?: profiles.first()

        // If this device remembers its own connection IP, use it —
        // otherwise leave whatever IP was already set.
        if (!active.connectionIp.isNullOrBlank()) {
            carIp = active.connectionIp
            tvIp.text = carIp
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString("ip", carIp).apply()
        }
        CarLogger.log("Main", "Using \"${active.displayName}\".")
        connectToCar()
    }

    private fun connectToCar() {
        updateStatus("Connecting…")
        tcpClient?.disconnect()
        tcpClient = TcpClient(carIp, 4000,
            onConnected = {
                CarLogger.log("TCP", "Connected to $carIp:4000")
                updateStatus("Connected ✓")
                startSenderLoop()  // restart sender in case it died during disconnect
                highResActive = false  // reset so correct res is sent on connect
                // Always query WiFi status on connect — auto-populates IP if STA changed
                enqueue("CMD_WIFI_STATUS#\n")
                val res = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString("cameraRes","QVGA") ?: "QVGA"
                enqueue("CMD_CAM_RES#$res\n")
                enqueue("CMD_VIDEO#1\n")
                // Turn LEDs on at connect — keeps RMT clocked, prevents servo ticking,
                // without forcing it from ESP32 boot (which caused side effects)
                enqueue("CMD_LED_MOD#1\n")
                startBatteryPolling()
            },
            onDisconnected = {
                CarLogger.log("TCP", "Disconnected — reconnecting")
                updateStatus("Reconnecting…")
                batteryJob?.cancel()
                cmdQueue.clear()
                motorQueue.clear()
                lastMotorWasZero = true  // reset motor state on disconnect
            },
            onData = { data -> handleIncomingData(data) }
        )
        tcpClient?.connect(scope)
        MainTcpHolder.client = tcpClient
        MainTcpHolder.enqueue = { cmd -> enqueue(cmd) }

        cameraStream?.stop()
        cameraStream = CameraStreamClient(carIp, 7000, surfaceView.holder).also { cs ->
            cs.onFrameAvailable = { bitmap ->
                frameWidth  = bitmap.width
                frameHeight = bitmap.height
                if (mediaSaver.isRecording()) mediaSaver.encodeFrame(bitmap)
                // Hide placeholder on first frame
                mainHandler.post {
                    val ph = findViewById<android.view.View>(R.id.cameraPlaceholder)
                    if (ph?.visibility != android.view.View.GONE) {
                        CarLogger.log("Camera", "First frame decoded — hiding placeholder")
                    }
                    ph?.visibility = android.view.View.GONE
                }
            }
            cs.onDisconnected = {
                // Show placeholder when camera disconnects
                mainHandler.post {
                    findViewById<android.view.View>(R.id.cameraPlaceholder)?.visibility =
                        android.view.View.VISIBLE
                }
            }
        }
        cameraStream?.start(scope)
    }

    private fun startBatteryPolling() {
        batteryJob?.cancel()
        batteryJob = scope.launch {
            while (isActive) {
                pollAnalogSensorIfConfigured()
                delay(15000)
            }
        }
    }

    // Generic replacement for the old Freenove-specific CMD_POWER poll.
    // CMD_POWER assumed a fixed onboard voltage-divider circuit that
    // only the real car board has — nothing a custom device (train,
    // lamp, whatever) can rely on. Instead: if the ACTIVE profile has
    // an ANALOG_INPUT role assigned to a real pin, poll THAT via the
    // generic GET command. If not, hide the display entirely rather
    // than show a permanent, meaningless "--V".
    private fun pollAnalogSensorIfConfigured() {
        val profileKey = com.espad32.controller.controls.ActiveProfile.get(this, Profiles.TRAIN.key)
        val profile = com.espad32.controller.pinmapper.ProfileResolver.allProfiles(this)
            .find { it.key == profileKey } ?: run {
                mainHandler.post { tvBattery.visibility = android.view.View.GONE }
                return
            }

        val roles = com.espad32.controller.pinmapper.RoleResolver.effectiveRoles(profile, customRoleStorageForSensor)
        val analogRole = roles.find { it.type == com.espad32.controller.pinmapper.RoleType.ANALOG_INPUT }
        if (analogRole == null) {
            mainHandler.post { tvBattery.visibility = android.view.View.GONE }
            return
        }

        val boardKey = pinConfigStorageForSensor.loadSelectedBoard(profile.key, profile.boardKey)
        val assignments = pinConfigStorageForSensor.load(profile.key, boardKey, profile.defaults)
        val gpio = assignments[analogRole.key]
        if (gpio == null) {
            mainHandler.post { tvBattery.visibility = android.view.View.GONE }
            return
        }

        com.espad32.controller.controls.DeviceCommand.sendGet(analogRole.key) { response ->
            val millivolts = response?.substringAfterLast("-> ")?.removeSuffix("mV")?.trim()?.toIntOrNull()
            mainHandler.post {
                if (millivolts != null) {
                    tvBattery.visibility = android.view.View.VISIBLE
                    tvBattery.text = "${analogRole.label}: ${"%.2f".format(millivolts / 1000f)}V"
                } else {
                    tvBattery.visibility = android.view.View.GONE
                }
            }
        }
    }

    private fun handleIncomingData(data: String) {
        CarLogger.log("RX", data)
        if (data.startsWith("CMD_WIFI")) {
            // Route to settings dialog if waiting for response
            MainTcpHolder.onNextData?.let { cb ->
                cb(data)
                if (data.startsWith("CMD_WIFI_OK") || data.startsWith("CMD_WIFI_FAIL") ||
                    data.startsWith("CMD_WIFI_STATUS") || data.startsWith("CMD_WIFI_FORGOTTEN")) {
                    MainTcpHolder.onNextData = null
                }
                return
            }
            // Auto-handle status response even when no dialog is open
            // Updates IP field if STA IP differs from current connection IP
            if (data.startsWith("CMD_WIFI_STATUS")) {
                val parts = data.split("#")
                val connected = parts.getOrNull(1) == "1"
                val staIp = parts.getOrNull(2)?.trim() ?: ""
                if (connected && staIp.isNotEmpty() && staIp != carIp) {
                    CarLogger.log("WiFi", "STA IP detected: $staIp — switching")
                    mainHandler.post {
                        carIp = staIp
                        tvIp.text = carIp
                        getSharedPreferences("ESPad32Prefs", MODE_PRIVATE)
                            .edit().putString("ip", carIp).apply()
                        // Show placeholder until camera connects
        findViewById<android.view.View>(R.id.cameraPlaceholder)?.visibility = android.view.View.VISIBLE
        connectToCar()
                    }
                }
                return
            }
            return
        }
        // Generic passthrough for anyone waiting on the next line via
        // MainTcpHolder.onNextData (Pin Mapper/Controls SET/SETV/GET
        // command responses, OtaActivity's version query, etc). CMD_WIFI
        // is already fully handled above and returns before reaching
        // here, so this only fires for everything else.
        MainTcpHolder.onNextData?.let { cb ->
            cb(data)
            MainTcpHolder.onNextData = null
        }
    }

    // Rolling average of last 4 readings to smooth out load spikes
    private val voltageHistory = ArrayDeque<Float>(4)

    private fun updateEsp32Battery(volts: Float) {
        val tvEspBatt  = findViewById<TextView>(R.id.tvEspBattery)  ?: return
        val tvEspLabel = findViewById<TextView>(R.id.tvEspBattLabel) ?: return
        // Add to history, keep last 4
        if (voltageHistory.size >= 4) voltageHistory.removeFirst()
        voltageHistory.addLast(volts)
        val avgVolts = voltageHistory.average().toFloat()
        val pct = ((avgVolts - 7.0f) / 1.4f * 100f).toInt().coerceIn(0, 100)
        val color = when { pct > 50 -> 0xFF69FF47.toInt(); pct > 20 -> 0xFFFFCC00.toInt(); else -> 0xFFFF4444.toInt() }
        val bars = when { pct > 75 -> "█████"; pct > 50 -> "████░"; pct > 25 -> "███░░"; pct > 10 -> "██░░░"; else -> "█░░░░" }
        tvEspBatt.text = "$bars $pct%"; tvEspBatt.setTextColor(color)
        tvEspLabel.setTextColor(color)
    }

    // ── Motor loop ────────────────────────────────────────────────────
    private fun startMotorLoop() {
        motorJob = scope.launch {
            while (isActive) { sendMotorFromStick(); delay(MOTOR_INTERVAL_MS) }
        }
    }

    private fun sendMotorFromStick() {
        val y = applyDeadzone(lastLeftY)
        val x = applyDeadzone(lastLeftX)
        val curvedY = if (speedCurveExpo) Math.signum(y)*y*y else y
        val curvedX = if (speedCurveExpo) Math.signum(x)*x*x else x
        val baseSpeed  = (-curvedY * MOTOR_MAX * g8MotorScale).toInt()
        val turnOffset = (curvedX * 1600 * g8MotorScale).toInt()
        val left  = (baseSpeed + turnOffset).coerceIn(-MOTOR_MAX, MOTOR_MAX)
        val right = (baseSpeed - turnOffset).coerceIn(-MOTOR_MAX, MOTOR_MAX)
        if (left != 0 || right != 0) {
            lastMotorCmdTime = System.currentTimeMillis()
            if (lastMotorWasZero) {
                lastMotorWasZero = false
                // Cancel pending high-res switch
                highResHandler.removeCallbacks(switchToHighResRunnable)
                // Switch back to SVGA immediately if we were in high-res
                if (highResActive) {
                    highResActive = false
                    enqueue("CMD_CAM_RES#SVGA\n")
                    CarLogger.log("Camera", "Switched to SVGA (driving)")
                }
            }
            enqueue("CMD_MOTOR#${left}#${left}#${right}#${right}\n")
        } else if (!lastMotorWasZero) {
            // Send stop immediately on first zero reading
            lastMotorWasZero = true
            lastMotorCmdTime = 0
            enqueue("CMD_MOTOR#0#0#0#0\n")
            // Schedule high-res after 2 seconds of idle
            highResHandler.removeCallbacks(switchToHighResRunnable)
            highResHandler.postDelayed(switchToHighResRunnable, 2000)
        }
    }

    // ── Gamepad ───────────────────────────────────────────────────────
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            && event.action == MotionEvent.ACTION_MOVE) {
            ControllerMapping.axes.forEach { mapping ->
                val rawX = event.getAxisValue(mapping.axisX)
                val rawY = event.getAxisValue(mapping.axisY)
                val x = applyDeadzone(if (mapping.invertX) -rawX else rawX)
                val y = applyDeadzone(if (mapping.invertY) -rawY else rawY)
                when (mapping.function) {
                    AxisFunction.DRIVE        -> { lastLeftX = x; lastLeftY = y }
                    AxisFunction.TRIGGER_DRIVE -> { lastLeftY = -(rawX - rawY) }
                    AxisFunction.STEER_ONLY   -> { lastLeftX = x }
                    AxisFunction.PAN_TILT     -> {
                        if (x != 0f || y != 0f) {
                            val now = System.currentTimeMillis()
                            val newS1 = (servo1Angle - x * g8ServoStep).coerceIn(0f, 180f)
                            val newS2 = (servo2Angle + y * g8ServoStep).coerceIn(80f, 180f)
                            if ((Math.abs(newS1-servo1Angle) >= 1f || Math.abs(newS2-servo2Angle) >= 1f)
                                && (now - lastServoSendTime) >= SERVO_SEND_INTERVAL_MS) {
                                servo1Angle = newS1; servo2Angle = newS2
                                lastServoSendTime = now
                                enqueue("CMD_CAMERA#${servo1Angle.toInt()}#${servo2Angle.toInt()}\n")
                            }
                        }
                    }
                    AxisFunction.NONE -> {}
                    AxisFunction.CUSTOM_PWM -> {
                        // Single-axis only (axisY ignored). Raw axis
                        // values are typically -1..1 for sticks or 0..1
                        // for triggers — this maps the -1..1 case
                        // correctly to 0-255; a trigger-only axis will
                        // only span the upper half of that range, which
                        // is a known rough edge worth revisiting once a
                        // real trigger axis is tested (see
                        // PIN_MAPPER_ROADMAP.md).
                        val normalized = ((rawX + 1f) / 2f * 255f).toInt().coerceIn(0, 255)
                        val now = System.currentTimeMillis()
                        if (normalized != lastCustomPwmSentValue &&
                            (now - lastCustomPwmSendTime) >= SERVO_SEND_INTERVAL_MS) {
                            lastCustomPwmSendTime = now
                            lastCustomPwmSentValue = normalized
                            executeCustomPwmAxis(mapping.customButtonId, normalized)
                        }
                    }
                }
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount > 0) return super.onKeyDown(keyCode, event)
        if (!isGamepad(event)) return super.onKeyDown(keyCode, event)
        handleGamepadButtonEvent(keyCode, true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!isGamepad(event)) return super.onKeyUp(keyCode, event)
        handleGamepadButtonEvent(keyCode, false)
        return true
    }

    // Single shared dispatch for "this physical-gamepad-button keyCode
    // was pressed or released" — used by real onKeyDown/onKeyUp AND by
    // the on-screen virtual gamepad buttons, so whatever's mapped via
    // Controller Mapping behaves identically either way. On press, runs
    // the full ButtonFunction dispatch. On release, only the cases that
    // actually need a release event act (horn, CUSTOM_CONTROL) — every
    // other legacy function only ever reacted to press and shouldn't
    // start firing twice just because release events now flow through
    // here too.
    private fun handleGamepadButtonEvent(keyCode: Int, pressed: Boolean) {
        if (pressed) {
            executeButtonFunction(keyCode, true)
        } else {
            val mapping = ControllerMapping.buttons.find { it.keyCode == keyCode }
            when (mapping?.function) {
                ButtonFunction.HORN_ON -> enqueue("CMD_BUZZER#0#2000\n")
                ButtonFunction.CUSTOM_CONTROL -> executeCustomControlButton(mapping.customButtonId, pressed = false)
                else -> {}
            }
        }
    }

    private fun executeButtonFunction(keyCode: Int, isPress: Boolean) {
        val mapping = ControllerMapping.buttons.find { it.keyCode == keyCode }
        val fn = mapping?.function ?: ButtonFunction.NONE
        when (fn) {
            ButtonFunction.HORN_ON      -> enqueue("CMD_BUZZER#1#2000\n")
            ButtonFunction.PHOTO        -> takePhoto()
            ButtonFunction.RECORD       -> toggleRecording()
            ButtonFunction.LED_CYCLE    -> { currentLedMode = (currentLedMode+1)%6; enqueue("CMD_LED_MOD#$currentLedMode\n") }
            ButtonFunction.LED_OFF      -> { currentLedMode = 0; enqueue("CMD_LED_MOD#0\n") }
            ButtonFunction.FACE_CYCLE   -> { currentEmotionMode = (currentEmotionMode+1)%8; enqueue("CMD_MATRIX_MOD#$currentEmotionMode\n") }
            ButtonFunction.FACE_OFF     -> { currentEmotionMode = 0; enqueue("CMD_MATRIX_MOD#0\n") }
            ButtonFunction.CAMERA_FLIP  -> cameraStream?.let { it.flipped = !it.flipped }
            ButtonFunction.SERVO_RESET  -> { servo1Angle = 90f; servo2Angle = 90f; enqueue("CMD_CAMERA#90#90\n") }
            ButtonFunction.PAN_LEFT     -> { servo1Angle = (servo1Angle + g8ServoStep).coerceIn(0f,180f); enqueue("CMD_CAMERA#${servo1Angle.toInt()}#${servo2Angle.toInt()}\n") }
            ButtonFunction.PAN_RIGHT    -> { servo1Angle = (servo1Angle - g8ServoStep).coerceIn(0f,180f); enqueue("CMD_CAMERA#${servo1Angle.toInt()}#${servo2Angle.toInt()}\n") }
            ButtonFunction.TILT_UP      -> { servo2Angle = (servo2Angle - g8ServoStep).coerceIn(80f,180f); enqueue("CMD_CAMERA#${servo1Angle.toInt()}#${servo2Angle.toInt()}\n") }
            ButtonFunction.TILT_DOWN    -> { servo2Angle = (servo2Angle + g8ServoStep).coerceIn(80f,180f); enqueue("CMD_CAMERA#${servo1Angle.toInt()}#${servo2Angle.toInt()}\n") }
            ButtonFunction.PAN_CENTER   -> { servo1Angle = 90f; enqueue("CMD_CAMERA#90#${servo2Angle.toInt()}\n") }
            ButtonFunction.TILT_CENTER  -> { servo2Angle = 90f; enqueue("CMD_CAMERA#${servo1Angle.toInt()}#90\n") }
            ButtonFunction.LIGHT_FOLLOW -> enqueue("CMD_CAR_MODE#1\n")
            ButtonFunction.LINE_TRACK   -> enqueue("CMD_CAR_MODE#2\n")
            ButtonFunction.STOP         -> enqueue("CMD_MOTOR#0#0#0#0\n")
            ButtonFunction.CUSTOM_CONTROL -> executeCustomControlButton(mapping?.customButtonId)
            ButtonFunction.NONE         -> {}
        }
    }

    // Triggers a user-defined Controls button (e.g. "LED") from a
    // mapped gamepad press — same DeviceCommand.sendSet path the
    // on-screen Controls button itself uses, so behavior (and the
    // device log) is identical whether it's tapped on screen or fired
    // from the gamepad. `pressed` distinguishes button-down from
    // button-up — MOMENTARY genuinely needs both (matches the same
    // touch-based fix in ControlsActivity/buildLiveButton); TOGGLE
    // ignores the release call entirely, since it only makes sense to
    // flip on press.
    private fun executeCustomControlButton(buttonId: String?, pressed: Boolean = true) {
        if (buttonId == null) {
            if (pressed) CarLogger.log("Controls", "Gamepad button has no Control button assigned yet.")
            return
        }
        val profileKey = com.espad32.controller.controls.ActiveProfile.get(
            this, com.espad32.controller.pinmapper.Profiles.TRAIN.key
        )
        val btn = controlButtonStorage.loadButtons(profileKey).find { it.id == buttonId }
        if (btn == null) {
            if (pressed) CarLogger.log("Controls", "Assigned Control button no longer exists (was it deleted?).")
            return
        }

        when (btn.controlType) {
            com.espad32.controller.controls.ControlType.TOGGLE -> {
                if (!pressed) return // only flip on press, release is a no-op
                val newState = !controlButtonStorage.getState(profileKey, btn.id)
                controlButtonStorage.setState(profileKey, btn.id, newState)
                CarLogger.log("Controls", "[Gamepad] \"${btn.label}\" -> ${if (newState) "ON" else "OFF"} — sending...")
                com.espad32.controller.controls.DeviceCommand.sendSet(btn.roleKey, newState) { response ->
                    CarLogger.log("Controls", response ?: "\"${btn.label}\": no response (check connection)")
                    renderLiveButtons()
                }
            }
            com.espad32.controller.controls.ControlType.MOMENTARY -> {
                CarLogger.log("Controls", "[Gamepad] \"${btn.label}\" ${if (pressed) "pressed" else "released"} — sending...")
                com.espad32.controller.controls.DeviceCommand.sendSet(btn.roleKey, pressed) { response ->
                    CarLogger.log("Controls", response ?: "\"${btn.label}\": no response (check connection)")
                }
            }
            com.espad32.controller.controls.ControlType.SLIDER -> {
                // Gamepad buttons can't drive a continuous slider value —
                // that needs axis mapping, which is separate, unbuilt
                // work (see PIN_MAPPER_ROADMAP.md). A gamepad button
                // mapped to a PWM/slider function has nothing sensible
                // to do here yet.
                CarLogger.log("Controls", "\"${btn.label}\" is a slider — map it to a gamepad AXIS instead of a button (Controller Mapping settings).")
            }
        }
    }

    // Drives a user-defined Controls slider (a PWM_OUTPUT role) from a
    // mapped gamepad axis — same DeviceCommand.sendSetValue path the
    // on-screen slider itself uses (including the centralized PWM
    // inversion compensation), so behavior is identical either way.
    // `value` arrives pre-normalized 0-255 (see the CUSTOM_PWM case in
    // onGenericMotionEvent) regardless of what it's ultimately headed
    // for — rescaled down to 0-180 here if the target turns out to be
    // a servo, rather than changing the caller's normalization (which
    // also drives the "did this actually change" dedup check in 0-255
    // space).
    private fun executeCustomPwmAxis(buttonId: String?, value: Int) {
        if (buttonId == null) {
            CarLogger.log("Controls", "Gamepad axis has no PWM/servo slider assigned yet.")
            return
        }
        val profileKey = com.espad32.controller.controls.ActiveProfile.get(
            this, com.espad32.controller.pinmapper.Profiles.TRAIN.key
        )
        val btn = controlButtonStorage.loadButtons(profileKey).find { it.id == buttonId }
        if (btn == null) {
            CarLogger.log("Controls", "Assigned PWM/servo slider no longer exists (was it deleted?).")
            return
        }
        val role = com.espad32.controller.pinmapper.RoleResolver.effectiveRoles(
            com.espad32.controller.pinmapper.ProfileResolver.allProfiles(this).find { it.key == profileKey }
                ?: return, customRoleStorageForSensor
        ).find { it.key == btn.roleKey }

        if (role?.type == com.espad32.controller.pinmapper.RoleType.SERVO) {
            val angle = (value * 180 / 255).coerceIn(0, 180)
            controlButtonStorage.setValue(profileKey, btn.id, angle)
            com.espad32.controller.controls.DeviceCommand.sendSetAngle(btn.roleKey, angle) { response ->
                CarLogger.log("Controls", response ?: "\"${btn.label}\": no response (check connection)")
            }
        } else {
            controlButtonStorage.setValue(profileKey, btn.id, value)
            com.espad32.controller.controls.DeviceCommand.sendSetValue(btn.roleKey, value) { response ->
                CarLogger.log("Controls", response ?: "\"${btn.label}\": no response (check connection)")
            }
        }
    }

    // ── Joysticks ─────────────────────────────────────────────────────
    private fun setupJoysticks() {
        joystickLeft.onMoved = { x, y ->
            lastLeftX = x * osMotorScale.coerceIn(0.1f, 1.0f)
            lastLeftY = y * osMotorScale.coerceIn(0.1f, 1.0f)
        }
        joystickLeft.onReleased = { lastLeftX = 0f; lastLeftY = 0f }
        joystickRight.onMoved = { x, y ->
            val now = System.currentTimeMillis()
            val newS1 = (servo1Angle - x * osServoStep).coerceIn(0f, 180f)
            val newS2 = (servo2Angle + y * osServoStep).coerceIn(80f, 180f)
            if ((Math.abs(newS1-servo1Angle) >= 1f || Math.abs(newS2-servo2Angle) >= 1f)
                && (now - lastServoSendTime) >= SERVO_SEND_INTERVAL_MS) {
                servo1Angle = newS1; servo2Angle = newS2
                lastServoSendTime = now
                enqueue("CMD_CAMERA#${servo1Angle.toInt()}#${servo2Angle.toInt()}\n")
            }
        }
        joystickRight.onReleased = { }

        setupWidgetRelocation(joystickLeft, "layout_joystick_left")
        setupWidgetRelocation(joystickRight, "layout_joystick_right")
    }

    // ── Custom layout — long-press-and-drag repositioning ──────────────
    // Shared by both joysticks (via JoystickView's own relocate
    // callbacks) and the virtual gamepad cluster (via its own touch
    // listeners, since it's built from several separate Button views
    // rather than one custom View that already owns its full touch
    // handling). Persisted as a plain translation offset (pixels) from
    // each widget's normal XML/constraint-based position — the default
    // layout is completely untouched; this only layers a "the user
    // nudged it by this much" adjustment on top.
    private val LAYOUT_PREFS_NAME = "espad_layout_prefs"

    private fun loadLayoutOffset(key: String): Pair<Float, Float> {
        val prefs = getSharedPreferences(LAYOUT_PREFS_NAME, MODE_PRIVATE)
        return Pair(prefs.getFloat("${key}_x", 0f), prefs.getFloat("${key}_y", 0f))
    }

    private fun saveLayoutOffset(key: String, x: Float, y: Float) {
        getSharedPreferences(LAYOUT_PREFS_NAME, MODE_PRIVATE).edit()
            .putFloat("${key}_x", x).putFloat("${key}_y", y).apply()
    }

    // Keeps a widget's translated position from ever going fully
    // off-screen — view.left/top/right/bottom are its LAID-OUT bounds
    // (unaffected by translationX/Y, which is a purely visual offset
    // applied after layout), so this computes the valid offset range
    // that keeps those bounds within the parent's bounds.
    // NOTE: an earlier version had a disableClipChildrenUpToRoot()
    // helper here, worked around ViewGroups clipping a translated
    // button's drawing to their own small bounds. Removed, not just
    // left unused — the underlying problem it solved no longer exists:
    // renderVirtualButtons() now positions everything directly within
    // an already full-screen container instead of small intermediate
    // boxes, so there's no small parent bounds for anything to be
    // clipped against in the first place.

    private fun clampOffset(view: android.view.View, dx: Float, dy: Float): Pair<Float, Float> {
        // Walks all the way up to the true root, accumulating each
        // intermediate ancestor's own layout position — needed because
        // a gamepad button's IMMEDIATE parent (clusterFrame,
        // diamondGroup, utilityRow) is a small box sized just to fit
        // its own children, not the actual screen. Clamping against
        // that confined dragging to a small region instead of the
        // whole screen — confirmed directly from a screenshot showing
        // buttons stuck inside that box. Joysticks were never affected
        // by this, since they're direct children of the root layout
        // already (their "immediate parent" already IS the screen-
        // sized root, so the old logic happened to be correct for
        // them by coincidence).
        var cumulativeLeft = view.left
        var cumulativeTop = view.top
        var current = view.parent as? android.view.View ?: return Pair(dx, dy)
        while (true) {
            val next = current.parent as? android.view.View ?: break
            cumulativeLeft += current.left
            cumulativeTop += current.top
            current = next
        }
        val root = current // the last view whose parent wasn't a View — the true root

        val minX = -cumulativeLeft.toFloat()
        val maxX = (root.width - cumulativeLeft - view.width).toFloat()
        val minY = -cumulativeTop.toFloat()
        val maxY = (root.height - cumulativeTop - view.height).toFloat()
        return Pair(
            if (maxX >= minX) dx.coerceIn(minX, maxX) else dx,
            if (maxY >= minY) dy.coerceIn(minY, maxY) else dy
        )
    }

    private fun applySavedLayoutOffset(view: android.view.View, key: String) {
        val (x, y) = loadLayoutOffset(key)
        view.translationX = x
        view.translationY = y
    }

    // Wires a JoystickView's relocate callbacks (see JoystickView.kt) to
    // actually move the view via translationX/Y, with clamping and
    // persistence — the joystick handles its own long-press-without-
    // movement detection internally, this just applies the result.
    private fun setupWidgetRelocation(view: android.view.View, prefsKey: String) {
        applySavedLayoutOffset(view, prefsKey)
        if (view is com.espad32.controller.JoystickView) {
            var baseX = 0f; var baseY = 0f
            view.onRelocateModeEntered = {
                baseX = view.translationX
                baseY = view.translationY
                CarLogger.log("Main", "Hold and drag to reposition — release to save.")
            }
            view.onRelocateDragged = { dx, dy ->
                val (clampedX, clampedY) = clampOffset(view, baseX + dx, baseY + dy)
                view.translationX = clampedX
                view.translationY = clampedY
            }
            view.onRelocateFinished = {
                saveLayoutOffset(prefsKey, view.translationX, view.translationY)
                CarLogger.log("Main", "Position saved.")
            }
        }
    }

    // Resets every custom-positioned widget back to its default layout
    // position — the safety net in case something ends up somewhere
    // awkward (e.g. a saved offset that doesn't make sense on a
    // different screen size). Called from Settings.
    fun resetCustomLayout() {
        getSharedPreferences(LAYOUT_PREFS_NAME, MODE_PRIVATE).edit().clear().apply()
        joystickLeft.translationX = 0f; joystickLeft.translationY = 0f
        joystickRight.translationX = 0f; joystickRight.translationY = 0f
        // The gamepad cluster now has many independently-positioned
        // widgets (the diamond group + 4 shoulders + 4 utility buttons)
        // rather than one — re-rendering it from scratch after clearing
        // prefs is simpler and more robust than individually tracking
        // and zeroing every one of those view references here.
        if (virtualButtonsEnabled) renderVirtualButtons()
        CarLogger.log("Main", "Custom layout reset to defaults.")
    }

    private fun applyJoystickVisibility() {
        val vis = if (joysticksEnabled) android.view.View.VISIBLE else android.view.View.GONE
        joystickLeft.visibility  = vis
        joystickRight.visibility = vis
    }

    private fun applyVirtualButtonsVisibility() {
        val container = findViewById<android.view.View>(R.id.virtualButtonsContainer) ?: return
        if (virtualButtonsEnabled) {
            container.visibility = android.view.View.VISIBLE
            renderVirtualButtons()
        } else {
            container.visibility = android.view.View.GONE
        }
    }

    // On-screen equivalents of the 12 physical gamepad buttons
    // ControllerMapping already knows about (see ALL_BUTTONS) — tapping
    // one calls the exact same handleGamepadButtonEvent() a real
    // gamepad's KeyEvent would, so whatever's mapped via Controller
    // Mapping works identically either way, and the app can be fully
    // driven without ever owning a physical gamepad.
    //
    // Precisely positioned (FrameLayout + explicit margins, not
    // LinearLayout flow) to match a reference layout exactly: face
    // buttons in a tight diamond, shoulder buttons bracketing the
    // middle row height with a SMALL gap (not off to the far sides —
    // that's what the first version got wrong). LinearLayout's gravity-
    // based centering didn't reliably land shoulder buttons at the
    // intended height, which is why this uses explicit coordinates
    // instead.
    //
    // Per-circle "what's this mapped to" captions from the first
    // version are dropped here — the tight center-to-center spacing
    // needed to match the reference leaves no room for a caption line
    // between rows without it overlapping the row below. Trade-off
    // made deliberately in favor of matching the requested layout.
    private fun renderVirtualButtons() {
        val container = findViewById<android.widget.FrameLayout>(R.id.virtualButtonsContainer) ?: return
        container.removeAllViews()
        // Deliberately no clipChildren handling needed here anymore —
        // container now genuinely covers the whole screen (see the XML),
        // so there's no small box for anything to be clipped against in
        // the first place. Replaces the earlier clipChildren-based fix,
        // which solved VISIBILITY but not the deeper problem: Android's
        // touch dispatch checks whether a touch point falls within a
        // ViewGroup's OWN bounds before even considering translated
        // children, so a button dragged outside a small parent's
        // rectangle was visible (once clipping was disabled) but
        // genuinely unreachable by touch — confirmed directly from
        // screenshots showing this exact symptom. The only real fix is
        // for every ancestor between a button and the root to already
        // be big enough to cover wherever it might get dragged to —
        // hence eliminating the small intermediate clusterFrame/
        // utilityRow containers entirely, in favor of positioning
        // everything directly within this one already-full-screen
        // container.

        val faceSize = 52
        val faceRadius = faceSize / 2
        val shoulderSize = 42
        val shoulderRadius = shoulderSize / 2
        val vStep = faceSize + 8       // center-to-center, Y-to-X and X-to-A
        val hStep = vStep              // center-to-center, X-to-center and center-to-B
        val shoulderHOffset = faceRadius + 6 + shoulderRadius  // X's edge -> shoulder's center
        val shoulderVOffset = shoulderRadius + 3               // half the L1/L2 pair gap

        // Fixed default anchor point (DP units, matching every other
        // measurement here) — replaces the OLD small clusterFrame's own
        // (cx,cy) center, since there's no more small box to center
        // things within. An approximation of where the cluster used to
        // visually sit (roughly centered horizontally, in the lower
        // portion of the screen) rather than a pixel-precise match —
        // reasonable given this is a customizable-layout feature to
        // begin with: anyone who cares about the exact default spot can
        // just drag it once, and it stays there.
        val density = resources.displayMetrics.density
        val screenWidthDp = (resources.displayMetrics.widthPixels / density).toInt()
        val screenHeightDp = (resources.displayMetrics.heightPixels / density).toInt()
        val anchorX = screenWidthDp / 2
        val anchorY = (screenHeightDp * 0.68f).toInt()

        // Diamond sub-container — Y/X/B/A live inside THIS, positioned
        // directly in `container` (no intermediate clusterFrame needed
        // anymore), sized just to fit the 4 face buttons — it doesn't
        // need to be screen-sized itself, since ITS OWN parent
        // (container) is already screen-sized, and diamondGroup's own
        // children (Y/X/B/A) never move relative to it — only
        // diamondGroup AS A WHOLE gets translated.
        val diamondWidth = 2 * hStep + faceSize
        val diamondHeight = 2 * vStep + faceSize
        val dcx = diamondWidth / 2
        val dcy = diamondHeight / 2
        val diamondGroup = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(diamondWidth), dp(diamondHeight)).apply {
                leftMargin = dp(anchorX - diamondWidth / 2)
                topMargin = dp(anchorY - diamondHeight / 2)
            }
            clipChildren = false
        }
        applySavedLayoutOffset(diamondGroup, "layout_gamepad_diamond")
        container.addView(diamondGroup)

        // A single, reusable button-builder — dragTarget decides what
        // actually moves during THIS button's long-press-drag: the
        // shared diamondGroup for Y/X/B/A (so they move as one unit),
        // or the button itself for everything else (independent).
        // Same long-press-without-movement pattern as before: normal
        // press fires immediately on ACTION_DOWN (unchanged, no added
        // lag), and if a hold-without-movement later triggers relocate
        // mode, that press gets explicitly released right away rather
        // than left stuck "on" for the whole drag.
        fun place(
            keyCode: Int, sizeDp: Int, textSizeSp: Float, centerX: Int, centerY: Int,
            parent: android.view.ViewGroup, dragTarget: android.view.View, prefsKey: String
        ) {
            val circle = Button(this).apply {
                text = shortLabelForKeyCode(keyCode)
                textSize = textSizeSp
                isAllCaps = false
                minWidth = 0; minimumWidth = 0
                minHeight = 0; minimumHeight = 0
                setPadding(0, 0, 0, 0)
                setBackgroundResource(R.drawable.virtual_button_circle)
                setTextColor(Color.parseColor("#E7EBEE"))
                alpha = 0.9f
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply {
                    leftMargin = dp(centerX - sizeDp / 2)
                    topMargin = dp(centerY - sizeDp / 2)
                }
            }

            var relocateMode = false
            var downRawX = 0f; var downRawY = 0f
            var baseX = 0f; var baseY = 0f
            val stationaryThresholdPx = 12f * resources.displayMetrics.density
            val longPressRunnable = Runnable {
                if (!relocateMode) {
                    relocateMode = true
                    handleGamepadButtonEvent(keyCode, false) // release now, not left stuck "on"
                    circle.alpha = 0.9f
                    circle.setBackgroundResource(R.drawable.virtual_button_circle)
                    circle.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    baseX = dragTarget.translationX
                    baseY = dragTarget.translationY
                    CarLogger.log("Main", "Hold and drag to reposition — release to save.")
                }
            }

            circle.setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX; downRawY = event.rawY
                        relocateMode = false
                        v.postDelayed(longPressRunnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                        handleGamepadButtonEvent(keyCode, true)
                        v.alpha = 1f
                        v.setBackgroundResource(R.drawable.virtual_button_circle_pressed)
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (relocateMode) {
                            val (clampedX, clampedY) = clampOffset(dragTarget, baseX + (event.rawX - downRawX), baseY + (event.rawY - downRawY))
                            dragTarget.translationX = clampedX
                            dragTarget.translationY = clampedY
                        } else {
                            val dx = event.rawX - downRawX; val dy = event.rawY - downRawY
                            if (kotlin.math.sqrt(dx * dx + dy * dy) > stationaryThresholdPx) {
                                v.removeCallbacks(longPressRunnable) // real movement — not a hold, cancel relocate detection
                            }
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        v.removeCallbacks(longPressRunnable)
                        if (relocateMode) {
                            saveLayoutOffset(prefsKey, dragTarget.translationX, dragTarget.translationY)
                            CarLogger.log("Main", "Position saved.")
                        } else {
                            handleGamepadButtonEvent(keyCode, false)
                            v.alpha = 0.9f
                            v.setBackgroundResource(R.drawable.virtual_button_circle)
                        }
                        true
                    }
                    else -> false
                }
            }
            parent.addView(circle)
        }

        // Diamond — Y top, X left, B right, A bottom, positioned within
        // diamondGroup (its own local origin), all four sharing
        // diamondGroup as their drag target so they move as one unit.
        place(KeyEvent.KEYCODE_BUTTON_Y, faceSize, 13f, dcx, dcy - vStep, diamondGroup, diamondGroup, "layout_gamepad_diamond")
        place(KeyEvent.KEYCODE_BUTTON_X, faceSize, 13f, dcx - hStep, dcy, diamondGroup, diamondGroup, "layout_gamepad_diamond")
        place(KeyEvent.KEYCODE_BUTTON_B, faceSize, 13f, dcx + hStep, dcy, diamondGroup, diamondGroup, "layout_gamepad_diamond")
        place(KeyEvent.KEYCODE_BUTTON_A, faceSize, 13f, dcx, dcy + vStep, diamondGroup, diamondGroup, "layout_gamepad_diamond")

        // Shoulders — tight against the diamond by default, bracketing
        // the X/B row height — each independently draggable (dragTarget
        // = the button itself), positioned directly in `container`
        // (using the screen-wide anchor point, not a small clusterFrame
        // that no longer exists).
        fun placeIndependent(keyCode: Int, sizeDp: Int, textSizeSp: Float, centerX: Int, centerY: Int, parent: android.view.ViewGroup, prefsKey: String) {
            lateinit var selfRef: android.widget.Button
            val circle = Button(this).apply {
                text = shortLabelForKeyCode(keyCode)
                textSize = textSizeSp
                isAllCaps = false
                minWidth = 0; minimumWidth = 0
                minHeight = 0; minimumHeight = 0
                setPadding(0, 0, 0, 0)
                setBackgroundResource(R.drawable.virtual_button_circle)
                setTextColor(Color.parseColor("#E7EBEE"))
                alpha = 0.9f
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply {
                    leftMargin = dp(centerX - sizeDp / 2)
                    topMargin = dp(centerY - sizeDp / 2)
                }
            }
            selfRef = circle
            applySavedLayoutOffset(circle, prefsKey)

            var relocateMode = false
            var downRawX = 0f; var downRawY = 0f
            var baseX = 0f; var baseY = 0f
            val stationaryThresholdPx = 12f * resources.displayMetrics.density
            val longPressRunnable = Runnable {
                if (!relocateMode) {
                    relocateMode = true
                    handleGamepadButtonEvent(keyCode, false)
                    circle.alpha = 0.9f
                    circle.setBackgroundResource(R.drawable.virtual_button_circle)
                    circle.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    baseX = selfRef.translationX
                    baseY = selfRef.translationY
                    CarLogger.log("Main", "Hold and drag to reposition — release to save.")
                }
            }
            circle.setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX; downRawY = event.rawY
                        relocateMode = false
                        v.postDelayed(longPressRunnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                        handleGamepadButtonEvent(keyCode, true)
                        v.alpha = 1f
                        v.setBackgroundResource(R.drawable.virtual_button_circle_pressed)
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (relocateMode) {
                            val (clampedX, clampedY) = clampOffset(selfRef, baseX + (event.rawX - downRawX), baseY + (event.rawY - downRawY))
                            selfRef.translationX = clampedX
                            selfRef.translationY = clampedY
                        } else {
                            val dx = event.rawX - downRawX; val dy = event.rawY - downRawY
                            if (kotlin.math.sqrt(dx * dx + dy * dy) > stationaryThresholdPx) {
                                v.removeCallbacks(longPressRunnable)
                            }
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        v.removeCallbacks(longPressRunnable)
                        if (relocateMode) {
                            saveLayoutOffset(prefsKey, selfRef.translationX, selfRef.translationY)
                            CarLogger.log("Main", "Position saved.")
                        } else {
                            handleGamepadButtonEvent(keyCode, false)
                            v.alpha = 0.9f
                            v.setBackgroundResource(R.drawable.virtual_button_circle)
                        }
                        true
                    }
                    else -> false
                }
            }
            parent.addView(circle)
        }

        placeIndependent(KeyEvent.KEYCODE_BUTTON_L1, shoulderSize, 11f, anchorX - hStep - shoulderHOffset, anchorY - shoulderVOffset, container, "layout_gamepad_l1")
        placeIndependent(KeyEvent.KEYCODE_BUTTON_L2, shoulderSize, 11f, anchorX - hStep - shoulderHOffset, anchorY + shoulderVOffset, container, "layout_gamepad_l2")
        placeIndependent(KeyEvent.KEYCODE_BUTTON_R1, shoulderSize, 11f, anchorX + hStep + shoulderHOffset, anchorY - shoulderVOffset, container, "layout_gamepad_r1")
        placeIndependent(KeyEvent.KEYCODE_BUTTON_R2, shoulderSize, 11f, anchorX + hStep + shoulderHOffset, anchorY + shoulderVOffset, container, "layout_gamepad_r2")

        // Select/L3/R3/Start — same independent-dragging treatment,
        // positioned directly in `container` as a row below the
        // diamond by default (no intermediate utilityRow LinearLayout
        // anymore — explicit per-button coordinates instead of relying
        // on LinearLayout's automatic horizontal flow).
        val utilityButtonSize = 28
        val utilityGap = 6
        val utilityStep = utilityButtonSize + utilityGap
        val utilityTotalWidth = 4 * utilityButtonSize + 3 * utilityGap
        val utilityStartX = anchorX - utilityTotalWidth / 2 + utilityButtonSize / 2
        val utilityY = anchorY + vStep + faceRadius + 10 + utilityButtonSize / 2
        val utilityKeys = listOf(
            KeyEvent.KEYCODE_BUTTON_SELECT to "layout_gamepad_select",
            KeyEvent.KEYCODE_BUTTON_THUMBL to "layout_gamepad_l3",
            KeyEvent.KEYCODE_BUTTON_THUMBR to "layout_gamepad_r3",
            KeyEvent.KEYCODE_BUTTON_START to "layout_gamepad_start"
        )
        utilityKeys.forEachIndexed { index, (kc, prefsKey) ->
            lateinit var selfRef: android.widget.Button
            val centerX = utilityStartX + index * utilityStep
            val b = Button(this).apply {
                text = shortLabelForKeyCode(kc)
                textSize = 8f
                isAllCaps = false
                minWidth = 0; minimumWidth = 0
                minHeight = 0; minimumHeight = 0
                setPadding(0, 0, 0, 0)
                setBackgroundResource(R.drawable.virtual_button_circle)
                setTextColor(Color.parseColor("#8A939C"))
                alpha = 0.8f
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(utilityButtonSize), dp(utilityButtonSize)).apply {
                    leftMargin = dp(centerX - utilityButtonSize / 2)
                    topMargin = dp(utilityY - utilityButtonSize / 2)
                }
            }
            selfRef = b
            applySavedLayoutOffset(b, prefsKey)

            var relocateMode = false
            var downRawX = 0f; var downRawY = 0f
            var baseX = 0f; var baseY = 0f
            val stationaryThresholdPx = 12f * resources.displayMetrics.density
            val longPressRunnable = Runnable {
                if (!relocateMode) {
                    relocateMode = true
                    handleGamepadButtonEvent(kc, false)
                    b.alpha = 0.8f
                    b.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    baseX = selfRef.translationX
                    baseY = selfRef.translationY
                    CarLogger.log("Main", "Hold and drag to reposition — release to save.")
                }
            }
            b.setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX; downRawY = event.rawY
                        relocateMode = false
                        v.postDelayed(longPressRunnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                        handleGamepadButtonEvent(kc, true)
                        v.alpha = 1f
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (relocateMode) {
                            val (clampedX, clampedY) = clampOffset(selfRef, baseX + (event.rawX - downRawX), baseY + (event.rawY - downRawY))
                            selfRef.translationX = clampedX
                            selfRef.translationY = clampedY
                        } else {
                            val dx = event.rawX - downRawX; val dy = event.rawY - downRawY
                            if (kotlin.math.sqrt(dx * dx + dy * dy) > stationaryThresholdPx) {
                                v.removeCallbacks(longPressRunnable)
                            }
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        v.removeCallbacks(longPressRunnable)
                        if (relocateMode) {
                            saveLayoutOffset(prefsKey, selfRef.translationX, selfRef.translationY)
                            CarLogger.log("Main", "Position saved.")
                        } else {
                            handleGamepadButtonEvent(kc, false)
                            v.alpha = 0.8f
                        }
                        true
                    }
                    else -> false
                }
            }
            container.addView(b)
        }
    }

    private fun shortLabelForKeyCode(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> "A"
        KeyEvent.KEYCODE_BUTTON_B -> "B"
        KeyEvent.KEYCODE_BUTTON_X -> "X"
        KeyEvent.KEYCODE_BUTTON_Y -> "Y"
        KeyEvent.KEYCODE_BUTTON_L1 -> "L1"
        KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
        KeyEvent.KEYCODE_BUTTON_L2 -> "L2"
        KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
        KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
        KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
        KeyEvent.KEYCODE_BUTTON_START -> "Start"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "Select"
        else -> "?"
    }

    // Shows what a button is actually mapped to right now (resolving
    // CUSTOM_CONTROL to the real Controls button's label), not just the
    // raw physical button name — otherwise someone using this without a
    // gamepad at all would see "A"/"B"/"X" with no idea what they do.
    // Currently unused — the tight-geometry redesign above dropped
    // per-circle captions (no room for a label line between rows at
    // this spacing). Kept rather than deleted, since it's the exact
    // logic needed if captions come back in some other form later
    // (e.g. a toast on long-press instead of a permanent label).
    private fun resolveVirtualButtonTarget(mapping: ButtonMapping): String? {
        return when (mapping.function) {
            ButtonFunction.CUSTOM_CONTROL -> {
                val profileKey = com.espad32.controller.controls.ActiveProfile.get(this, Profiles.TRAIN.key)
                controlButtonStorage.loadButtons(profileKey).find { it.id == mapping.customButtonId }?.label
            }
            ButtonFunction.NONE -> null
            else -> mapping.function.label
        }
    }

    // ── Photo / Video ─────────────────────────────────────────────────
    private fun takePhoto() {
        val bitmap = cameraStream?.currentBitmap
        if (bitmap == null) { toast("No camera frame yet"); return }
        scope.launch(Dispatchers.IO) {
            val matrix = android.graphics.Matrix().apply { setScale(1f, -1f); postTranslate(0f, bitmap.height.toFloat()) }
            val flipped = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            val name = mediaSaver.savePhoto(flipped)
            flipped.recycle()
            mainHandler.post { if (name != null) toast("📸 Saved: $name") else toast("Photo save failed") }
        }
    }

    private fun toggleRecording() {
        if (mediaSaver.isRecording()) {
            scope.launch(Dispatchers.IO) {
                val name = mediaSaver.stopRecording()
                mainHandler.post {
                    tvRecording.text = ""
                    findViewById<android.widget.Button>(R.id.btnRecordOverlay).text = "⏺"
                    if (name != null) toast("🎬 Saved: $name") else toast("Video save failed")
                }
            }
        } else {
            if (mediaSaver.startRecording(frameWidth, frameHeight)) {
                mainHandler.post {
                    tvRecording.text = "⏺ REC"
                    findViewById<android.widget.Button>(R.id.btnRecordOverlay).text = "⏹"
                }
                toast("Recording started")
            } else toast("Could not start recording")
        }
    }

    // ── Settings ──────────────────────────────────────────────────────
    private fun showSettings() {
        SettingsDialogFragment.newInstance(
            carIp, joysticksEnabled, virtualButtonsEnabled,
            g8ServoStep, g8MotorScale,
            osServoStep, osMotorScale
        ) { newIp, newJoysticks, newVirtualButtons, newG8Servo, newG8Motor, newOsServo, newOsMotor ->
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit()
                .putString("ip", newIp)
                .putBoolean("joysticks", newJoysticks)
                .putBoolean("virtual_buttons", newVirtualButtons)
                .putFloat("g8ServoStep",  newG8Servo)
                .putFloat("g8MotorScale", newG8Motor)
                .putFloat("osServoStep",  newOsServo)
                .putFloat("osMotorScale", newOsMotor)
                .apply()
            g8ServoStep = newG8Servo; g8MotorScale = newG8Motor
            osServoStep = newOsServo; osMotorScale = newOsMotor
            speedCurveExpo = prefs.getString("speedCurve","linear") == "exponential"
            autoStopMs     = prefs.getInt("autoStopMs", 500)
            joysticksEnabled = newJoysticks
            virtualButtonsEnabled = newVirtualButtons
            applyJoystickVisibility()
            applyVirtualButtonsVisibility()
            setupJoysticks()
            ThemeManager.apply(controlPanelView)
            if (newIp != carIp) {
                carIp = newIp; tvIp.text = carIp
                CarLogger.log("Main", "IP changed to $carIp — reconnecting")
                // Show placeholder until camera connects
        findViewById<android.view.View>(R.id.cameraPlaceholder)?.visibility = android.view.View.VISIBLE
        connectToCar()
            }
        }.show(supportFragmentManager, "settings")
    }

    // ── Live Control buttons (from Controls screen) ────────────────────
    // Renders whatever buttons were configured for the currently "active"
    // profile (see ActiveProfile.kt for what that means and its
    // limitations). Local-only, same as the Controls screen itself —
    // tapping flips stored state and does not talk to the ESP32 yet.
    // Photo/Record/Flip only make sense on a board that actually has a
    // camera — previously always shown, a leftover from when this app
    // only ever talked to the Freenove car. A camera isn't something a
    // pin role can express (fixed wiring, not a user-assignable
    // function), so this checks the active profile's BOARD directly
    // instead — see BoardDef.supportsCamera.
    private fun updateCameraUiVisibility() {
        val profileKey = com.espad32.controller.controls.ActiveProfile.get(this, Profiles.TRAIN.key)
        val profile = com.espad32.controller.pinmapper.ProfileResolver.allProfiles(this)
            .find { it.key == profileKey } ?: Profiles.TRAIN
        val boardKey = pinConfigStorageForSensor.loadSelectedBoard(profile.key, profile.boardKey)
        val hasCamera = com.espad32.controller.pinmapper.Boards.byKey(boardKey).supportsCamera

        val visibility = if (hasCamera) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.btnPhoto)?.visibility = visibility
        findViewById<android.view.View>(R.id.btnRecord)?.visibility = visibility
        findViewById<android.view.View>(R.id.btnCameraFlip)?.visibility = visibility
        findViewById<android.view.View>(R.id.btnPhotoOverlay)?.visibility = visibility
        findViewById<android.view.View>(R.id.btnRecordOverlay)?.visibility = visibility
    }

    private fun renderLiveButtons() {
        val container = controlPanelView.getDynamicButtonsContainer()
        container.removeAllViews()
        val profileKey = ActiveProfile.get(this, Profiles.TRAIN.key)
        val profile = com.espad32.controller.pinmapper.ProfileResolver.allProfiles(this)
            .find { it.key == profileKey } ?: Profiles.TRAIN
        // SLIDER controls (PWM roles) aren't rendered here — the compact
        // pill-button panel has no slider widget yet. They're fully
        // usable from the Controls screen; this is a scoped decision,
        // not a bug. See PIN_MAPPER_ROADMAP.md.
        val buttons = controlButtonStorage.loadButtons(profile.key)
            .filter { it.controlType != com.espad32.controller.controls.ControlType.SLIDER }

        // Chunk into rows of up to 4, matching the density of the fixed
        // rows above (Photo/Record/Log/Matrix/Settings is 5, LED row is 4).
        buttons.chunked(4).forEach { rowButtons ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = if (rowButtons.size == 1) android.view.Gravity.CENTER_HORIZONTAL else android.view.Gravity.START
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.topMargin = dp(6)
                layoutParams = params
            }
            rowButtons.forEachIndexed { i, btn ->
                row.addView(buildLiveButton(profile.key, btn, isLastInRow = i == rowButtons.size - 1, soloInRow = rowButtons.size == 1))
            }
            container.addView(row)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun handleLiveMomentaryPress(btn: ControlButtonDef, pressed: Boolean) {
        CarLogger.log("Controls", "\"${btn.label}\" ${if (pressed) "pressed" else "released"} — sending...")
        com.espad32.controller.controls.DeviceCommand.sendSet(btn.roleKey, pressed) { response ->
            CarLogger.log("Controls", response ?: "\"${btn.label}\": no response (check connection)")
        }
    }

    private fun buildLiveButton(profileKey: String, btn: ControlButtonDef, isLastInRow: Boolean, soloInRow: Boolean): Button {
        val isOn = controlButtonStorage.getState(profileKey, btn.id)
        val view = layoutInflater.inflate(R.layout.item_dynamic_car_button, null) as Button
        view.text = btn.label
        if (isOn) view.setTextColor(Color.parseColor("#E3A458"))

        val params = if (soloInRow) {
            // A single button in its own row shouldn't stretch edge-to-edge
            // like it's part of a 4-across grid — size to content instead,
            // with a sensible minimum so it's still a comfortable tap target.
            view.minWidth = dp(160)
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36))
        } else {
            LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                if (!isLastInRow) marginEnd = dp(4)
            }
        }
        view.layoutParams = params

        if (btn.controlType == ControlType.MOMENTARY) {
            // Real press/release, not a tap — matches the same fix in
            // ControlsActivity. A horn/buzzer should stop the moment
            // you lift your finger, not stay on until tapped again.
            view.setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        handleLiveMomentaryPress(btn, true)
                        v.performClick()
                        true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        handleLiveMomentaryPress(btn, false)
                        true
                    }
                    else -> false
                }
            }
        } else {
            view.setOnClickListener {
                when (btn.controlType) {
                    ControlType.TOGGLE -> {
                        val newState = !controlButtonStorage.getState(profileKey, btn.id)
                        controlButtonStorage.setState(profileKey, btn.id, newState)
                        CarLogger.log("Controls", "\"${btn.label}\" -> ${if (newState) "ON" else "OFF"} — sending...")
                        com.espad32.controller.controls.DeviceCommand.sendSet(btn.roleKey, newState) { response ->
                            CarLogger.log("Controls", response ?: "\"${btn.label}\": no response (check connection)")
                            renderLiveButtons()
                        }
                    }
                    com.espad32.controller.controls.ControlType.SLIDER -> {
                        // Never reached — SLIDER buttons are filtered out
                        // before reaching this panel (renderLiveButtons).
                    }
                    ControlType.MOMENTARY -> {} // handled by the touch listener above, never reached here
                }
                renderLiveButtons()
            }
        }
        view.setOnLongClickListener {
            showLiveEditDialog(profileKey, btn)
            true
        }
        return view
    }

    private fun showLiveEditDialog(profileKey: String, btn: ControlButtonDef) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 0)
        }
        val labelInput = EditText(this).apply { setText(btn.label) }
        container.addView(labelInput)

        AlertDialog.Builder(this)
            .setTitle("Edit Button")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newLabel = labelInput.text.toString().ifBlank { btn.label }
                val buttons = controlButtonStorage.loadButtons(profileKey)
                val index = buttons.indexOfFirst { it.id == btn.id }
                if (index >= 0) {
                    buttons[index] = btn.copy(label = newLabel)
                    controlButtonStorage.saveButtons(profileKey, buttons)
                    renderLiveButtons()
                }
            }
            .setNeutralButton("Remove") { _, _ ->
                val buttons = controlButtonStorage.loadButtons(profileKey)
                buttons.removeAll { it.id == btn.id }
                controlButtonStorage.saveButtons(profileKey, buttons)
                renderLiveButtons()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Permissions ───────────────────────────────────────────────────
    private fun requestPermissions() {
        val perms = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (perms.isNotEmpty()) ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
    }

    // ── Helpers ───────────────────────────────────────────────────────
    private fun applyDeadzone(v: Float) = if (Math.abs(v) < DEADZONE) 0f else v
    private fun isGamepad(e: KeyEvent)  = e.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
    private fun updateStatus(msg: String) { mainHandler.post { tvStatus.text = msg } }
    private fun toast(msg: String) { mainHandler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Forward file pick result to SettingsDialogFragment if open
        val fragment = supportFragmentManager.findFragmentByTag("settings")
        fragment?.onActivityResult(requestCode, resultCode, data)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        cameraStream?.updateHolder(holder)
        if (cameraStream?.isRunning() == false) cameraStream?.start(scope)
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) { cameraStream?.stop() }

    override fun onResume() {
        super.onResume()
        // Refresh in case buttons were added/edited/removed in the
        // Pin Mapper or Controls screen while we were away.
        renderLiveButtons()
        updateCameraUiVisibility()
        if (virtualButtonsEnabled) renderVirtualButtons()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(hideRunnable)
        if (mediaSaver.isRecording()) mediaSaver.stopRecording()
        // Only fully cancel if actually finishing (not config change)
        highResHandler.removeCallbacks(switchToHighResRunnable)
        if (isFinishing) {
            senderJob?.cancel(); motorJob?.cancel(); batteryJob?.cancel()
            connectionScope.cancel()
            tcpClient?.disconnect(); cameraStream?.stop()
        }
        CarLogger.log("Main", "App destroyed"); CarLogger.close()
    }
}
