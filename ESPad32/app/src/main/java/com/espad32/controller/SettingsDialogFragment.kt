package com.espad32.controller

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import com.espad32.controller.controls.DeviceDiscovery
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.DialogFragment

class SettingsDialogFragment : DialogFragment() {

    var onSave: ((ip: String, joysticks: Boolean,
                  g8Servo: Float, g8Motor: Float,
                  osServo: Float, osMotor: Float) -> Unit)? = null

    companion object {
        private const val G8_SERVO_DEFAULT  = 5
        private const val G8_MOTOR_DEFAULT  = 5
        private const val OS_SERVO_DEFAULT  = 3
        private const val OS_MOTOR_DEFAULT  = 3

        fun newInstance(
            ip: String, joysticks: Boolean,
            g8Servo: Float, g8Motor: Float,
            osServo: Float, osMotor: Float,
            onSave: (String, Boolean, Float, Float, Float, Float) -> Unit
        ) = SettingsDialogFragment().apply {
            arguments = Bundle().apply {
                putString("ip", ip)
                putBoolean("joysticks", joysticks)
                putInt("g8ServoSlider",  servoStepToSlider(g8Servo))
                putInt("g8MotorSlider",  motorScaleToSlider(g8Motor))
                putInt("osServoSlider",  servoStepToSlider(osServo))
                putInt("osMotorSlider",  motorScaleToSlider(osMotor))
            }
            this.onSave = onSave
        }

        fun sliderToServoStep(v: Int)    = v * 1.6f
        fun servoStepToSlider(s: Float)  = (s / 1.6f).toInt().coerceIn(1, 10)
        fun sliderToMotorScale(v: Int)   = v * 0.2f
        fun motorScaleToSlider(s: Float) = (s / 0.2f).toInt().coerceIn(1, 10)
    }

    // Persisted view refs for auto-save on dismiss
    private var saved = false
    private var etIp: EditText? = null
    private var switchJoysticks: Switch? = null
    private var sbG8Servo: SeekBar? = null
    private var sbG8Motor: SeekBar? = null
    private var sbOsServo: SeekBar? = null
    private var sbOsMotor: SeekBar? = null
    private var rgTheme: RadioGroup? = null
    private var contentArea: LinearLayout? = null

    // Sidebar tab views
    private var tabWifi: TextView? = null
    private var tabTheme: TextView? = null
    private var tabController: TextView? = null
    private var tabOta: TextView? = null
    private var currentTab = 0
    private var pickBinLauncher: ((ByteArray) -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                               savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.dialog_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        saved = false
        val args = arguments!!

        etIp            = view.findViewById(R.id.etIpAddress)
        switchJoysticks = view.findViewById(R.id.switchJoysticks)
        contentArea     = view.findViewById(R.id.settingsContent)
        tabWifi         = view.findViewById(R.id.tabWifi)
        tabTheme        = view.findViewById(R.id.tabTheme)
        tabController   = view.findViewById(R.id.tabController)
        tabOta          = view.findViewById(R.id.tabAdvanced)

        etIp?.setText(args.getString("ip"))
        switchJoysticks?.isChecked = args.getBoolean("joysticks")

        tabWifi?.setOnClickListener       { showTab(0) }
        tabTheme?.setOnClickListener      { showTab(1) }
        tabController?.setOnClickListener { showTab(2) }
        tabOta?.setOnClickListener        { showTab(3) }

        val btnSave   = view.findViewById<Button>(R.id.btnSave)
        val btnCancel = view.findViewById<Button>(R.id.btnCancel)

        // Follow / Track buttons in top bar
        view.findViewById<Button>(R.id.btnFollowTop)?.setOnClickListener {
            MainTcpHolder.enqueue?.invoke("CMD_CAR_MODE#1\n")
        }
        view.findViewById<Button>(R.id.btnTrackTop)?.setOnClickListener {
            MainTcpHolder.enqueue?.invoke("CMD_CAR_MODE#2\n")
        }

        view.findViewById<Button>(R.id.btnSearchDevices)?.setOnClickListener {
            searchForDevices()
        }

        fun doSave() {
            val theme = when (rgTheme?.checkedRadioButtonId) {
                R.id.rbMinimal -> PanelTheme.MINIMAL_FLAT
                R.id.rbHud     -> PanelTheme.TACTICAL_HUD
                else           -> PanelTheme.DARK_GLASS
            }
            ThemeManager.save(requireContext(), theme)
            onSave?.invoke(
                etIp?.text.toString().trim(),
                switchJoysticks?.isChecked ?: false,
                sliderToServoStep((sbG8Servo?.progress ?: 4) + 1),
                sliderToMotorScale((sbG8Motor?.progress ?: 4) + 1),
                sliderToServoStep((sbOsServo?.progress ?: 2) + 1),
                sliderToMotorScale((sbOsMotor?.progress ?: 2) + 1)
            )
            saved = true
        }

        btnSave.setOnClickListener { doSave(); dismiss() }
        btnCancel.setOnClickListener { dismiss() }

        showTab(0)
    }

    private fun showTab(tab: Int) {
        currentTab = tab
        val cyan = 0xFF00E5FF.toInt()
        val grey = 0xFF888888.toInt()
        listOf(tabWifi, tabTheme, tabController, tabOta).forEachIndexed { i, tv ->
            tv?.setTextColor(if (i == tab) cyan else grey)
            tv?.setBackgroundColor(if (i == tab) 0xFF1A1A1A.toInt() else 0xFF111111.toInt())
        }
        contentArea?.removeAllViews()
        when (tab) {
            0 -> buildWifiTab()
            1 -> buildThemeTab()
            2 -> buildControllerTab()
            3 -> buildAdvancedTab()
        }
    }

    // ── Device search (UDP broadcast discovery) ────────────────────────
    // Solves the case where a device's STA IP wasn't received via the
    // normal TCP response after connecting to a router — instead of
    // needing to log into the router to find it, ask the device
    // directly over the network. See DeviceDiscovery.kt for details.
    private fun searchForDevices() {
        toast("Searching for devices...")
        DeviceDiscovery.discover { found ->
            if (!isAdded) return@discover // fragment may have closed mid-search

            when {
                found.isEmpty() -> {
                    toast("No devices found. Make sure it's powered on and on the same network.")
                }
                found.size == 1 -> {
                    etIp?.setText(found[0].ip)
                    toast("Found \"${found[0].name}\" at ${found[0].ip} — tap Save to connect.")
                }
                else -> {
                    val labels = found.map { "${it.name} (${it.ip})" }.toTypedArray()
                    AlertDialog.Builder(requireContext())
                        .setTitle("Select a device")
                        .setItems(labels) { _, index ->
                            etIp?.setText(found[index].ip)
                            toast("Selected ${found[index].ip} — tap Save to connect.")
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
    }

    // ── WiFi Tab ──────────────────────────────────────────────────────
    // ── WiFi password storage helpers ─────────────────────────────────
    private fun saveWifiPassword(ssid: String, pass: String) {
        requireContext().getSharedPreferences("ESPad32WiFiPasswords", android.content.Context.MODE_PRIVATE)
            .edit().putString("pwd_$ssid", pass).apply()
    }
    private fun getSavedPassword(ssid: String): String? {
        return requireContext().getSharedPreferences("ESPad32WiFiPasswords", android.content.Context.MODE_PRIVATE)
            .getString("pwd_$ssid", null)
    }
    private fun isNetworkSavedOnPhone(ssid: String): Boolean {
        return try {
            val wm = requireContext().applicationContext
                .getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            wm.configuredNetworks?.any { it.SSID == "\"$ssid\"" } == true
        } catch (e: Exception) { false }
    }

    private fun buildWifiTab() {
        // Status display
        val tvStatus = TextView(requireContext()).apply {
            text = "Status: querying…"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 14.dp }
        }
        contentArea?.addView(tvStatus)

        // Query current WiFi status
        MainTcpHolder.onNextData = { data ->
            if (data.startsWith("CMD_WIFI_STATUS")) {
                val parts = data.split("#")
                val isConn = parts.getOrNull(1) == "1"
                val ip     = parts.getOrNull(2)?.trim() ?: ""
                val ssid   = parts.getOrNull(3)?.trim() ?: ""
                activity?.runOnUiThread {
                    when {
                        isConn -> {
                            tvStatus.text = "✓ Connected to \"$ssid\"  IP: $ip"
                            tvStatus.setTextColor(0xFF69FF47.toInt())
                            etIp?.setText(ip)
                        }
                        ssid.isNotEmpty() -> {
                            tvStatus.text = "✗ Not connected (saved: \"$ssid\")"
                            tvStatus.setTextColor(0xFFFF6644.toInt())
                        }
                        else -> {
                            tvStatus.text = "No home WiFi configured — AP only"
                            tvStatus.setTextColor(0xFF888888.toInt())
                        }
                    }
                }
                MainTcpHolder.onNextData = null
            }
        }
        MainTcpHolder.enqueue?.invoke("CMD_WIFI_STATUS#\n")

        // SSID row with scan dropdown and Connect button
        val ssidRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 0 }
        }
        val etWifiSsid = EditText(requireContext()).apply {
            hint = "Tap to scan  •  Long-press to type"
            inputType = android.text.InputType.TYPE_NULL  // prevents keyboard on tap; long-press re-enables
            setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF555555.toInt())
            setBackgroundColor(0xFF2A2A2A.toInt()); setPadding(12, 12, 12, 12)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnConnect = Button(requireContext()).apply {
            text = "Connect"; textSize = 12f; isAllCaps = false
            setBackgroundResource(R.drawable.btn_car_bg); setTextColor(0xFF00E5FF.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = 8.dp
            }
        }
        ssidRow.addView(etWifiSsid); ssidRow.addView(btnConnect)
        contentArea?.addView(ssidRow)

        // Network scan dropdown — appears below SSID field when tapped
        val scanContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF222222.toInt())
            visibility = android.view.View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 8.dp
                // Match width of SSID field (exclude Connect button)
            }
        }
        val tvScanHeader = TextView(requireContext()).apply {
            text = "📶  Scanning for networks…"
            textSize = 11f; setTextColor(0xFF888888.toInt())
            setPadding(12, 10, 12, 6)
        }
        scanContainer.addView(tvScanHeader)
        contentArea?.addView(scanContainer)

        fun showNetworkList(networks: List<String>) {
            scanContainer.removeAllViews()
            if (networks.isEmpty()) {
                scanContainer.addView(TextView(requireContext()).apply {
                    text = "No networks found — check location permission"
                    textSize = 11f; setTextColor(0xFF666666.toInt()); setPadding(12, 10, 12, 10)
                })
            } else {

                networks.forEach { ssid ->
                    val row = TextView(requireContext()).apply {
                        text = "  $ssid"
                        textSize = 13f; setTextColor(0xFFCCCCCC.toInt())
                        setPadding(12, 12, 12, 12)
                        setOnClickListener {
                            etWifiSsid.setText(ssid)
                            scanContainer.visibility = android.view.View.GONE
                        }
                    }
                    // Divider
                    scanContainer.addView(android.view.View(requireContext()).apply {
                        setBackgroundColor(0xFF2A2A2A.toInt())
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    })
                    scanContainer.addView(row)
                }
            }
        }

        fun scanNetworks() {
            val ctx = requireContext()
            // Check permission
            val hasPermission = ContextCompat.checkSelfPermission(ctx,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                scanContainer.visibility = android.view.View.VISIBLE
                scanContainer.removeAllViews()
                scanContainer.addView(TextView(ctx).apply {
                    text = "Location permission required. Grant it in Android Settings → Apps → ESPad32 → Permissions → Location"
                    textSize = 11f; setTextColor(0xFFFF6644.toInt()); setPadding(12, 10, 12, 10)
                })
                return
            }
            scanContainer.visibility = android.view.View.VISIBLE
            scanContainer.removeAllViews()
            scanContainer.addView(TextView(ctx).apply {
                text = "📶  Scanning…"; textSize = 11f
                setTextColor(0xFF888888.toInt()); setPadding(12, 10, 12, 10)
            })
            val wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val results = wifiManager.scanResults
                .mapNotNull { it.SSID }
                .filter { it.isNotEmpty() && it != "ESPad_32" }
                .distinctBy { it }
                .sortedBy { it.lowercase() }
            showNetworkList(results)
        }

        // Suppress keyboard on tap — show network list instead
        // User can still type by tapping the field again after selecting a network
        etWifiSsid.setOnClickListener {
            // Hide keyboard
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(etWifiSsid.windowToken, 0)
            etWifiSsid.clearFocus()
            scanNetworks()
        }
        // Long press = manual keyboard entry
        etWifiSsid.setOnLongClickListener {
            etWifiSsid.inputType = android.text.InputType.TYPE_CLASS_TEXT
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
            etWifiSsid.requestFocus()
            imm.showSoftInput(etWifiSsid, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            scanContainer.visibility = android.view.View.GONE
            true
        }

        // Password row with eye toggle and Forget button inline
        val passRow2 = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF2A2A2A.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16.dp }
        }
        val etWifiPass = EditText(requireContext()).apply {
            hint = "Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF555555.toInt())
            setBackgroundColor(0x00000000.toInt()); setPadding(12, 12, 12, 12)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnToggle = ImageButton(requireContext()).apply {
            setImageResource(android.R.drawable.ic_menu_view)
            setBackgroundColor(0x00000000.toInt())
            setColorFilter(0xFF888888.toInt())
            layoutParams = LinearLayout.LayoutParams(48.dp, 48.dp).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
        }
        val btnForget = Button(requireContext()).apply {
            text = "Forget"; textSize = 11f; isAllCaps = false
            setBackgroundResource(R.drawable.btn_car_bg); setTextColor(0xFFFF6644.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = 8.dp; gravity = android.view.Gravity.CENTER_VERTICAL
            }
        }
        passRow2.addView(etWifiPass); passRow2.addView(btnToggle); passRow2.addView(btnForget)
        contentArea?.addView(passRow2)

        var passwordVisible = false
        btnToggle.setOnClickListener {
            passwordVisible = !passwordVisible
            etWifiPass.inputType = if (passwordVisible)
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            else
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            btnToggle.setColorFilter(if (passwordVisible) 0xFF00E5FF.toInt() else 0xFF888888.toInt())
            etWifiPass.setSelection(etWifiPass.text?.length ?: 0)
        }

        btnForget.setOnClickListener {
            MainTcpHolder.enqueue?.invoke("CMD_WIFI_FORGET#\n")
            etWifiSsid.setText(""); etWifiPass.setText("")
            tvStatus.text = "WiFi credentials cleared"
            tvStatus.setTextColor(0xFF888888.toInt())
        }

        btnConnect.setOnClickListener {
            val ssid = etWifiSsid.text.toString().trim()
            val pass = etWifiPass.text.toString()
            if (ssid.isEmpty()) { toast("Please enter a network name"); return@setOnClickListener }
            tvStatus.text = "Connecting to \"$ssid\"…"
            tvStatus.setTextColor(0xFFFFCC00.toInt())
            MainTcpHolder.onNextData = { data ->
                activity?.runOnUiThread {
                    when {
                        data.startsWith("CMD_WIFI_TRYING") -> {
                            tvStatus.text = "⏳ ESP32 connecting…"
                            tvStatus.setTextColor(0xFFFFCC00.toInt())
                        }
                        data.startsWith("CMD_WIFI_OK") -> {
                            val newIp = data.split("#").getOrNull(1)?.trim() ?: ""
                            tvStatus.text = "✓ Connected! IP: $newIp — tap Save"
                            tvStatus.setTextColor(0xFF69FF47.toInt())
                            etIp?.setText(newIp)
                            // Save password for future auto-fill
                            saveWifiPassword(
                                etWifiSsid.text.toString().trim(),
                                etWifiPass.text.toString()
                            )
                            MainTcpHolder.onNextData = null
                        }
                        data.startsWith("CMD_WIFI_FAIL") -> {
                            tvStatus.text = "✗ Failed — check SSID and password"
                            tvStatus.setTextColor(0xFFFF6644.toInt())
                            MainTcpHolder.onNextData = null
                        }
                    }
                }
            }
            MainTcpHolder.enqueue?.invoke("CMD_WIFI_STA#$ssid#$pass\n")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (MainTcpHolder.onNextData != null) {
                    MainTcpHolder.onNextData = null
                    tvStatus.text = "⏱ No response — try again"
                    tvStatus.setTextColor(0xFFFF6644.toInt())
                }
            }, 25000)
        }


    }

    // ── Theme Tab ─────────────────────────────────────────────────────
    private fun buildThemeTab() {
        addSectionHeader("PANEL THEME")

        val rg = RadioGroup(requireContext()).apply {
            orientation = RadioGroup.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        rgTheme = rg

        val themes = listOf(
            Pair(R.id.rbGlass,   "Dark Glass"),
            Pair(R.id.rbMinimal, "Minimal Flat"),
            Pair(R.id.rbHud,     "Tactical HUD")
        )
        themes.forEach { (id, label) ->
            RadioButton(requireContext()).apply {
                this.id = id; text = label
                setTextColor(0xFFCCCCCC.toInt()); textSize = 13f
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF00E5FF.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 12.dp
                }
                rg.addView(this)
            }
        }
        when (ThemeManager.current) {
            PanelTheme.DARK_GLASS   -> rg.check(R.id.rbGlass)
            PanelTheme.MINIMAL_FLAT -> rg.check(R.id.rbMinimal)
            PanelTheme.TACTICAL_HUD -> rg.check(R.id.rbHud)
        }
        contentArea?.addView(rg)
    }

    // ── Controller Tab — embedded controller mapping ──────────────────
    private fun buildControllerTab() {
        // Presets
        addSectionHeader("PRESETS")
        ControllerMapping.PRESETS.forEach { profile ->
            val isActive = profile.name == ControllerMapping.activeProfileName
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(0xFF1A1A1A.toInt()); setPadding(16, 14, 16, 14)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 4.dp }
            }
            val col = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(requireContext()).apply {
                text = profile.name; textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setTypeface(null, android.graphics.Typeface.BOLD)
            })
            val drive = profile.axes.find { it.function == AxisFunction.DRIVE || it.function == AxisFunction.TRIGGER_DRIVE }?.label ?: "—"
            val pan   = profile.axes.find { it.function == AxisFunction.PAN_TILT }?.label ?: "—"
            col.addView(TextView(requireContext()).apply {
                text = "Drive: $drive  |  Pan/Tilt: $pan"; textSize = 11f; setTextColor(0xFF888888.toInt())
            })
            val btn = Button(requireContext()).apply {
                text = if (isActive) "✓ Active" else "Apply"
                textSize = 12f; isAllCaps = false
                setBackgroundResource(R.drawable.btn_car_bg)
                setTextColor(if (isActive) 0xFF00E5FF.toInt() else 0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
            }
            btn.setOnClickListener {
                ControllerMapping.applyPreset(profile)
                buildControllerTab()
            }
            row.addView(col); row.addView(btn)
            contentArea?.addView(row)
        }

        addDivider()

        // Open full mapping screen button
        val btnFullMapping = Button(requireContext()).apply {
            text = "🎮  Open Full Controller Mapping"
            textSize = 12f; isAllCaps = false
            setBackgroundResource(R.drawable.btn_car_bg); setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 44.dp).apply {
                bottomMargin = 8.dp
            }
        }
        btnFullMapping.setOnClickListener {
            val ctx = requireContext()
            ctx.startActivity(android.content.Intent(ctx, ControllerMappingActivity::class.java))
        }
        contentArea?.addView(btnFullMapping)
    }

    // ── Advanced Tab ──────────────────────────────────────────────────
    private fun buildAdvancedTab() {
        val ip = requireContext().getSharedPreferences("ESPad32Prefs", android.content.Context.MODE_PRIVATE)
            .getString("ip", "192.168.4.1") ?: "192.168.4.1"

        addSectionHeader("OTA FIRMWARE UPDATE")
        addHint("Compile in Arduino IDE → Sketch → Export Compiled Binary (.bin)")

        // ESP32 status
        val tvOtaStatus = TextView(requireContext()).apply {
            text = "Checking ESP32…"; textSize = 13f
            setTextColor(0xFF888888.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16.dp }
        }
        contentArea?.addView(tvOtaStatus)

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val conn = java.net.URL("http://$ip:8080/ota/status")
                        .openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 3000; conn.readTimeout = 3000
                    conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    // Query firmware version through the EXISTING connection —
                    // the ESP32 command server only accepts one client at a time
                    val version = kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
                        val prevHandler = MainTcpHolder.onNextData
                        var resumed = false
                        MainTcpHolder.onNextData = { data ->
                            if (data.startsWith("CMD_VERSION") && !resumed) {
                                resumed = true
                                val ver = data.split("#").getOrNull(1)?.trim()
                                MainTcpHolder.onNextData = prevHandler
                                if (cont.isActive) cont.resume(ver) {}
                            }
                        }
                        MainTcpHolder.enqueue?.invoke("CMD_VERSION#\n")
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (!resumed) {
                                resumed = true
                                MainTcpHolder.onNextData = prevHandler
                                if (cont.isActive) cont.resume(null) {}
                            }
                        }, 3000)
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        tvOtaStatus.text = if (version != null)
                            "✓ ESP32 ready  •  Firmware v$version"
                        else
                            "✓ ESP32 ready for OTA  •  Firmware unknown"
                        tvOtaStatus.setTextColor(0xFF69FF47.toInt())
                    }
                } catch (e: Exception) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        tvOtaStatus.text = "✗ ESP32 not reachable on $ip"
                        tvOtaStatus.setTextColor(0xFFFF4444.toInt())
                    }
                }
            }
        }

        addDivider()
        addSectionHeader("STEP 1 — SELECT FIRMWARE")
        val btnPickBin = addButton("📂  Browse for custom .bin file")

        addDivider()
        addSectionHeader("STEP 2 — UPLOAD")
        val btnFlash = addButton("⚡  Flash Firmware")
        btnFlash.isEnabled = false

        // Shared progress bar
        val progressBar = android.widget.ProgressBar(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 20.dp).apply {
                bottomMargin = 8.dp }
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF00E5FF.toInt())
            visibility = android.view.View.INVISIBLE
        }
        val tvProgress = TextView(requireContext()).apply {
            textSize = 11f; setTextColor(0xFF00E5FF.toInt())
            typeface = android.graphics.Typeface.MONOSPACE; gravity = android.view.Gravity.CENTER
            visibility = android.view.View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16.dp }
        }
        contentArea?.addView(progressBar)
        contentArea?.addView(tvProgress)

        // Flash Default — below progress, clearly separate
        addDivider()
        addSectionHeader("RESTORE DEFAULT FIRMWARE")
        addHint("Flashes the bundled default sketch. Use this to restore factory firmware.")
        val btnDefault = addButton("⭐  Flash Default Firmware")

        // Shared flash logic
        fun doFlash(bytes: ByteArray) {
            val ip2 = requireContext().getSharedPreferences("ESPad32Prefs", android.content.Context.MODE_PRIVATE)
                .getString("ip", "192.168.4.1") ?: "192.168.4.1"
            btnFlash.isEnabled = false; btnPickBin.isEnabled = false; btnDefault.isEnabled = false
            progressBar.visibility = android.view.View.VISIBLE
            tvProgress.visibility  = android.view.View.VISIBLE
            progressBar.progress = 0
            tvOtaStatus.text = "Flashing…"; tvOtaStatus.setTextColor(0xFFFFCC00.toInt())
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val total = bytes.size
                    val conn = java.net.URL("http://$ip2:8080/ota/upload")
                        .openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"; conn.doOutput = true
                    conn.connectTimeout = 10000; conn.readTimeout = 60000
                    conn.setChunkedStreamingMode(4096)
                    conn.setRequestProperty("Content-Type", "application/octet-stream")
                    val out = java.io.DataOutputStream(conn.outputStream)
                    var offset = 0
                    while (offset < total) {
                        val end = minOf(offset + 4096, total)
                        out.write(bytes, offset, end - offset)
                        offset = end
                        val pct = offset * 100 / total
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            progressBar.progress = pct
                            tvProgress.text = "${offset/1024}KB / ${total/1024}KB  ($pct%)"
                        }
                    }
                    out.flush(); out.close()
                    val code = conn.responseCode; conn.disconnect()
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (code == 200 || progressBar.progress >= 95) {
                            progressBar.progress = 100
                            tvOtaStatus.text = "✓ Flash Complete!"
                            tvOtaStatus.setTextColor(0xFF69FF47.toInt())
                            tvProgress.text = "Reconnect the app to resume control"
                        } else {
                            tvOtaStatus.text = "✗ Flash failed (HTTP $code)"
                            tvOtaStatus.setTextColor(0xFFFF4444.toInt())
                            btnFlash.isEnabled = true; btnPickBin.isEnabled = true; btnDefault.isEnabled = true
                        }
                    }
                } catch (e: Exception) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (progressBar.progress >= 95) {
                            progressBar.progress = 100
                            tvOtaStatus.text = "✓ Flash Complete!"
                            tvOtaStatus.setTextColor(0xFF69FF47.toInt())
                            tvProgress.text = "Reconnect the app to resume control"
                        } else {
                            tvOtaStatus.text = "✗ Error: ${e.message}"
                            tvOtaStatus.setTextColor(0xFFFF4444.toInt())
                            btnFlash.isEnabled = true; btnPickBin.isEnabled = true; btnDefault.isEnabled = true
                        }
                    }
                }
            }
        }

        var cachedBytes: ByteArray? = null

        btnPickBin.setOnClickListener {
            // Copy from Google Drive to cache then enable Flash
            val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"; addCategory(android.content.Intent.CATEGORY_OPENABLE)
            }
            pickBinLauncher = { bytes ->
                cachedBytes = bytes
                btnFlash.isEnabled = true
                tvOtaStatus.text = "File ready — tap Flash Firmware"
                tvOtaStatus.setTextColor(0xFFFFCC00.toInt())
            }
            @Suppress("DEPRECATION")
            requireActivity().startActivityForResult(
                android.content.Intent.createChooser(intent, "Select .bin file"), 9001)
        }

        btnFlash.setOnClickListener {
            cachedBytes?.let { doFlash(it) }
        }

        btnDefault.setOnClickListener {
            try {
                val bytes = requireContext().assets.open("06_3_Multi_Functional_Car.ino.bin").readBytes()
                cachedBytes = bytes; btnFlash.isEnabled = true
                doFlash(bytes)
            } catch (e: Exception) {
                tvOtaStatus.text = "✗ No bundled firmware found in assets"
                tvOtaStatus.setTextColor(0xFFFF4444.toInt())
            }
        }
    }

    // ── Shared helpers ────────────────────────────────────────────────
    private fun addSectionHeader(text: String) {
        contentArea?.addView(TextView(requireContext()).apply {
            this.text = text; textSize = 10f; setTextColor(0xFF666666.toInt())
            letterSpacing = 0.1f; setPadding(0, 0, 0, 8)
        })
    }
    private fun addHint(text: String) {
        contentArea?.addView(TextView(requireContext()).apply {
            this.text = text; textSize = 11f; setTextColor(0xFF777777.toInt())
            setPadding(0, 0, 0, 14)
        })
    }
    private fun addDivider() {
        contentArea?.addView(android.view.View(requireContext()).apply {
            setBackgroundColor(0xFF333333.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = 4; bottomMargin = 16
            }
        })
    }
    private fun addEditField(hint: String, password: Boolean): EditText {
        val et = EditText(requireContext()).apply {
            this.hint = hint; inputType = if (password)
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            else android.text.InputType.TYPE_CLASS_TEXT
            setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF555555.toInt())
            setBackgroundColor(0xFF2A2A2A.toInt()); setPadding(12, 12, 12, 12)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10.dp }
        }
        contentArea?.addView(et)
        return et
    }
    private fun addPasswordField(): Pair<EditText, ImageButton> {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF2A2A2A.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10.dp }
        }
        val et = EditText(requireContext()).apply {
            hint = "WiFi password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF555555.toInt())
            setBackgroundColor(0x00000000.toInt()); setPadding(12, 12, 12, 12)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btn = ImageButton(requireContext()).apply {
            setImageResource(android.R.drawable.ic_menu_view)
            setBackgroundColor(0x00000000.toInt())
            setColorFilter(0xFF888888.toInt())
            layoutParams = LinearLayout.LayoutParams(48.dp, 48.dp).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        }
        row.addView(et); row.addView(btn)
        contentArea?.addView(row)
        return Pair(et, btn)
    }
    private fun addSlider(label: String, initial: Int): SeekBar {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 4.dp }
        }
        val tvLabel = TextView(requireContext()).apply {
            text = label; textSize = 12f; setTextColor(0xFFCCCCCC.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvVal = TextView(requireContext()).apply {
            text = "$initial"; textSize = 12f; setTextColor(0xFF00E5FF.toInt())
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(32.dp, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row.addView(tvLabel); row.addView(tvVal)
        contentArea?.addView(row)
        val sb = SeekBar(requireContext()).apply {
            max = 9; progress = initial - 1
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF00E5FF.toInt())
            thumbTintList    = android.content.res.ColorStateList.valueOf(0xFF00E5FF.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 14.dp }
        }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, v: Int, u: Boolean) { tvVal.text = "${v+1}" }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        contentArea?.addView(sb)
        return sb
    }
    private fun addButton(label: String): Button {
        return Button(requireContext()).apply {
            text = label; textSize = 12f; isAllCaps = false
            setBackgroundResource(R.drawable.btn_car_bg); setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 44.dp).apply {
                bottomMargin = 10.dp
            }
            contentArea?.addView(this)
        }
    }
    private fun addLabelValue(label: String, value: String): TextView {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 6.dp }
        }
        row.addView(TextView(requireContext()).apply {
            text = label; textSize = 12f; setTextColor(0xFFCCCCCC.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val tvVal = TextView(requireContext()).apply {
            text = value; textSize = 12f; setTextColor(0xFF00E5FF.toInt())
            gravity = android.view.Gravity.END
        }
        row.addView(tvVal); contentArea?.addView(row); return tvVal
    }
    private fun formatTimeout(ms: Int) = if (ms == 0) "Off" else if (ms < 1000) "${ms}ms" else "${ms/1000}s"
    private fun toast(msg: String) {
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
    }
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 9001 && resultCode == android.app.Activity.RESULT_OK) {
            val uri = data?.data ?: return
            android.widget.Toast.makeText(requireContext(), "Loading file…", android.widget.Toast.LENGTH_SHORT).show()
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    // Copy to local cache first — handles Google Drive and other cloud URIs
                    val inStream = requireContext().contentResolver.openInputStream(uri)
                        ?: throw Exception("Cannot open file")
                    val cacheFile = java.io.File(requireContext().cacheDir, "firmware_ota.bin")
                    cacheFile.outputStream().use { out -> inStream.copyTo(out) }
                    inStream.close()
                    val bytes = cacheFile.readBytes()
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        pickBinLauncher?.invoke(bytes)
                        pickBinLauncher = null
                    }
                } catch (e: Exception) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(requireContext(),
                            "Could not read file: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        if (!saved) {
            try {
                val theme = when (rgTheme?.checkedRadioButtonId) {
                    R.id.rbMinimal -> PanelTheme.MINIMAL_FLAT
                    R.id.rbHud     -> PanelTheme.TACTICAL_HUD
                    else           -> PanelTheme.DARK_GLASS
                }
                ThemeManager.save(requireContext(), theme)
                onSave?.invoke(
                    etIp?.text.toString().trim(),
                    switchJoysticks?.isChecked ?: false,
                    sliderToServoStep((sbG8Servo?.progress ?: 4) + 1),
                    sliderToMotorScale((sbG8Motor?.progress ?: 4) + 1),
                    sliderToServoStep((sbOsServo?.progress ?: 2) + 1),
                    sliderToMotorScale((sbOsMotor?.progress ?: 2) + 1)
                )
            } catch (e: Exception) { }
        }
        MainTcpHolder.onNextData = null
        super.onDismiss(dialog)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.95).toInt(),
            (resources.displayMetrics.heightPixels * 0.80).toInt()
        )
    }
}
