package com.espad32.controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MatrixCanvasActivity : AppCompatActivity() {

    // ── Drawing mode ──────────────────────────────────────────────────
    enum class DrawMode { BOTH, LEFT_ONLY, RIGHT_ONLY, MIRROR }

    private var drawMode = DrawMode.BOTH
    private var activeEye = 0  // 0=left, 1=right (for LEFT_ONLY/RIGHT_ONLY)

    // ── Matrix data — two 8x8 grids ───────────────────────────────────
    // leftGrid[row][col] and rightGrid[row][col] — true = LED on
    private val leftGrid  = Array(8) { BooleanArray(8) }
    private val rightGrid = Array(8) { BooleanArray(8) }

    // ── Views ─────────────────────────────────────────────────────────
    private lateinit var leftCanvas:  MatrixGridView
    private lateinit var rightCanvas: MatrixGridView
    private lateinit var spinnerMode: Spinner
    private lateinit var tvEyeLabel:  TextView
    private lateinit var btnSend:     Button
    private lateinit var btnClear:    Button
    private lateinit var btnFill:     Button
    private lateinit var btnInvert:   Button
    private lateinit var quickPatterns: LinearLayout

    // ── Quick patterns ────────────────────────────────────────────────
    data class Pattern(val name: String, val left: Array<Int>, val right: Array<Int>)

    private val patterns = listOf(
        Pattern("😊 Happy",
            arrayOf(0x00, 0x3C, 0x42, 0x95, 0xA1, 0xA1, 0x3E, 0x00),
            arrayOf(0x00, 0x3C, 0x42, 0xA9, 0x85, 0x85, 0x7C, 0x00)),
        Pattern("😢 Sad",
            arrayOf(0x00, 0x3C, 0x42, 0xA1, 0x95, 0x95, 0x3E, 0x00),
            arrayOf(0x00, 0x3C, 0x42, 0x85, 0xA9, 0xA9, 0x7C, 0x00)),
        Pattern("😡 Angry",
            arrayOf(0x00, 0x7E, 0x7E, 0x3C, 0x18, 0x18, 0x3C, 0x00),
            arrayOf(0x00, 0x7E, 0x7E, 0x3C, 0x18, 0x18, 0x3C, 0x00)),
        Pattern("😲 Surprised",
            arrayOf(0x00, 0x3C, 0x42, 0x81, 0x81, 0x42, 0x3C, 0x00),
            arrayOf(0x00, 0x3C, 0x42, 0x81, 0x81, 0x42, 0x3C, 0x00)),
        Pattern("❤ Heart",
            arrayOf(0x00, 0x36, 0x7F, 0x7F, 0x3E, 0x1C, 0x08, 0x00),
            arrayOf(0x00, 0x36, 0x7F, 0x7F, 0x3E, 0x1C, 0x08, 0x00)),
        Pattern("✖ X",
            arrayOf(0x42, 0x24, 0x18, 0x0C, 0x18, 0x24, 0x42, 0x00),
            arrayOf(0x42, 0x24, 0x18, 0x0C, 0x18, 0x24, 0x42, 0x00)),
        Pattern("🔲 Clear",
            arrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
            arrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
        Pattern("🔳 Fill",
            arrayOf(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF),
            arrayOf(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF))
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_matrix_canvas)

        leftCanvas    = findViewById(R.id.leftMatrixView)
        rightCanvas   = findViewById(R.id.rightMatrixView)
        spinnerMode   = findViewById(R.id.spinnerDrawMode)
        tvEyeLabel    = findViewById(R.id.tvEyeLabel)
        btnSend       = findViewById(R.id.btnSendPattern)
        btnClear      = findViewById(R.id.btnClearAll)
        btnFill       = findViewById(R.id.btnFill)
        btnInvert     = findViewById(R.id.btnInvert)
        quickPatterns = findViewById(R.id.quickPatterns)

        setupGridViews()
        setupModeSpinner()
        setupQuickPatterns()
        setupButtons()
    }

    private fun setupGridViews() {
        leftCanvas.grid  = leftGrid
        rightCanvas.grid = rightGrid

        leftCanvas.onCellChanged  = { _, _ -> if (drawMode == DrawMode.MIRROR) mirrorLeftToRight() }
        rightCanvas.onCellChanged = { _, _ -> }

        leftCanvas.setOnTouchListener  { v, e -> leftCanvas.handleTouch(e); v.performClick(); true }
        rightCanvas.setOnTouchListener { v, e ->
            if (drawMode == DrawMode.BOTH || drawMode == DrawMode.RIGHT_ONLY) {
                rightCanvas.handleTouch(e)
            }
            v.performClick()
            true
        }
    }

    private fun setupModeSpinner() {
        val modes = listOf("Both eyes", "Left eye only", "Right eye only", "Mirror (L→R)")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerMode.adapter = adapter
        spinnerMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                drawMode = DrawMode.values()[pos]
                updateModeUi()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun updateModeUi() {
        when (drawMode) {
            DrawMode.BOTH -> {
                leftCanvas.alpha  = 1f; leftCanvas.isEnabled  = true
                rightCanvas.alpha = 1f; rightCanvas.isEnabled = true
                tvEyeLabel.text = "Draw on both eyes independently"
            }
            DrawMode.LEFT_ONLY -> {
                leftCanvas.alpha  = 1f;  leftCanvas.isEnabled  = true
                rightCanvas.alpha = 0.4f; rightCanvas.isEnabled = false
                tvEyeLabel.text = "Drawing on LEFT eye only"
            }
            DrawMode.RIGHT_ONLY -> {
                leftCanvas.alpha  = 0.4f; leftCanvas.isEnabled  = false
                rightCanvas.alpha = 1f;   rightCanvas.isEnabled = true
                tvEyeLabel.text = "Drawing on RIGHT eye only"
            }
            DrawMode.MIRROR -> {
                leftCanvas.alpha  = 1f;  leftCanvas.isEnabled  = true
                rightCanvas.alpha = 0.6f; rightCanvas.isEnabled = false
                tvEyeLabel.text = "Draw left — mirrors to right"
            }
        }
    }

    private fun mirrorLeftToRight() {
        for (row in 0..7) {
            for (col in 0..7) {
                rightGrid[row][col] = leftGrid[row][7 - col]  // horizontal mirror
            }
        }
        rightCanvas.invalidate()
    }

    private fun setupQuickPatterns() {
        quickPatterns.removeAllViews()
        for (pattern in patterns) {
            val btn = Button(this).apply {
                text = pattern.name
                textSize = 11f
                setPadding(16, 8, 16, 8)
                setBackgroundResource(R.drawable.btn_car_bg)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(6, 0, 6, 0) }
            }
            btn.setOnClickListener {
                applyPattern(pattern)
            }
            quickPatterns.addView(btn)
        }
    }

    private fun applyPattern(pattern: Pattern) {
        fun byteToRow(byte: Int): BooleanArray {
            val row = BooleanArray(8)
            for (col in 0..7) row[col] = (byte shr (7 - col)) and 1 == 1
            return row
        }
        for (row in 0..7) {
            leftGrid[row]  = byteToRow(pattern.left[row])
            rightGrid[row] = byteToRow(pattern.right[row])
        }
        leftCanvas.invalidate()
        rightCanvas.invalidate()
    }

    private fun setupButtons() {
        btnSend.setOnClickListener { sendPattern() }
        btnClear.setOnClickListener {
            for (row in 0..7) { leftGrid[row].fill(false); rightGrid[row].fill(false) }
            leftCanvas.invalidate(); rightCanvas.invalidate()
        }
        btnFill.setOnClickListener {
            for (row in 0..7) { leftGrid[row].fill(true); rightGrid[row].fill(true) }
            leftCanvas.invalidate(); rightCanvas.invalidate()
        }
        btnInvert.setOnClickListener {
            for (row in 0..7) {
                for (col in 0..7) {
                    leftGrid[row][col]  = !leftGrid[row][col]
                    rightGrid[row][col] = !rightGrid[row][col]
                }
            }
            leftCanvas.invalidate(); rightCanvas.invalidate()
        }
        findViewById<Button>(R.id.btnClose).setOnClickListener { finish() }
    }

    private fun gridToBytes(grid: Array<BooleanArray>): IntArray {
        return IntArray(8) { row ->
            var byte = 0
            for (col in 0..7) if (grid[row][col]) byte = byte or (1 shl (7 - col))
            byte
        }
    }

    private fun sendPattern() {
        val left  = gridToBytes(leftGrid)
        val right = gridToBytes(rightGrid)

        val cmdLeft = buildString {
            append("CMD_MATRIX_LEFT")
            left.forEach { append("#$it") }
            append("\n")
        }
        val cmdRight = buildString {
            append("CMD_MATRIX_RIGHT")
            right.forEach { append("#$it") }
            append("\n")
        }

        CarLogger.log("Matrix", "Sending left:  $cmdLeft".trim())
        CarLogger.log("Matrix", "Sending right: $cmdRight".trim())

        val client = MainTcpHolder.client
        if (client == null) {
            Toast.makeText(this, "Not connected to car", Toast.LENGTH_SHORT).show()
            return
        }

        // Send sequentially on IO thread with gap between commands
        lifecycleScope.launch(Dispatchers.IO) {
            client.send(cmdLeft)
            delay(500)
            client.send(cmdRight)
        }

        Toast.makeText(this, "Pattern sent ✓", Toast.LENGTH_SHORT).show()
    }
}

// ── Grid view widget ──────────────────────────────────────────────────
class MatrixGridView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var grid: Array<BooleanArray> = Array(8) { BooleanArray(8) }
    var onCellChanged: ((row: Int, col: Int) -> Unit)? = null

    private val paintOn  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 200, 0) }
    private val paintOff = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 40, 40) }
    private val paintBg  = Paint().apply { color = Color.rgb(15, 15, 15) }
    private var drawing = false
    private var drawValue = true

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintBg)
        val cellW = width / 8f
        val cellH = height / 8f
        // Use the smaller dimension for radius so circles are always round
        val radius = (minOf(cellW, cellH) * 0.4f)
        for (row in 0..7) {
            for (col in 0..7) {
                val cx = col * cellW + cellW / 2f
                val cy = row * cellH + cellH / 2f
                canvas.drawCircle(cx, cy, radius, if (grid[row][col]) paintOn else paintOff)
            }
        }
    }

    fun handleTouch(event: MotionEvent) {
        if (!isEnabled) return
        val col = (event.x / (width / 8f)).toInt().coerceIn(0, 7)
        val row = (event.y / (height / 8f)).toInt().coerceIn(0, 7)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                drawing = true
                drawValue = !grid[row][col]
                grid[row][col] = drawValue
                onCellChanged?.invoke(row, col)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (drawing && grid[row][col] != drawValue) {
                    grid[row][col] = drawValue
                    onCellChanged?.invoke(row, col)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> drawing = false
        }
    }
}

