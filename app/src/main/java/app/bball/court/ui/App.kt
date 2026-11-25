package app.bball.court.ui

import android.graphics.Bitmap
import android.net.Uri
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.bball.court.core.NPt
import app.bball.court.core.NRect
import app.bball.court.core.ShotHistoryRecord
import app.bball.court.core.ShotHistoryStore
import app.bball.court.core.ShotAnalyzer
import app.bball.court.core.ShotReport
import app.bball.court.CrashLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF0B0B10)
private val Card = Color(0xFF17171C)
private val Ink = Color(0xFFE9E9F0)
private val Dim = Color(0xFF9B9BAA)
private val Accent = Brush.linearGradient(listOf(Color(0xFF7C5CFF), Color(0xFFFF6BD6), Color(0xFF3EE8C8)))

sealed interface Screen {
    data object Home : Screen
    data class Failed(val title: String, val detail: String) : Screen
    data object Cameras : Screen
    data object History : Screen
    data class HistoryDetail(val record: ShotHistoryRecord) : Screen
    data class Calibrate(val uri: Uri, val frame: Bitmap?) : Screen
    data class CourtCalib(val uri: Uri, val frame: Bitmap?) : Screen
    data class Analyzing(val uri: Uri, val hoop: NRect, val progress: Float, val live: ShotReport) : Screen
    data class Report(
        val report: ShotReport,
        val uri: Uri,
        val history: ShotHistoryRecord? = null,
    ) : Screen
}

@Composable
fun ShotLabApp(initialVideoPath: String? = null, initialAtMs: Long = 0L, initialRim: String? = null) {
    val ctx = LocalContext.current
    var sens by remember { mutableStateOf(app.bball.court.core.Sensitivity.load(ctx)) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var court by remember { mutableStateOf<app.bball.court.core.CourtHomography?>(app.bball.court.core.CalibStore.load(ctx)) }
    val scope = rememberCoroutineScope()
    val analyzer = remember { ShotAnalyzer(ctx.applicationContext) }
    var crash by remember { mutableStateOf(CrashLog.read(ctx)) }

    suspend fun finishAnalysis(uri: Uri, report: ShotReport) {
        if (report.error != null && report.framesAnalysed == 0) {
            screen = Screen.Failed("分析中断", report.error!!)
            return
        }
        val savedRecord = try {
            withContext(Dispatchers.IO) {
                ShotHistoryStore.save(ctx, uri, report)
            }
        } catch (_: Throwable) {
            // 历史写入失败不能阻挡本次报告展示。
            null
        }
        screen = Screen.Report(report, uri, savedRecord)
    }

    // Test/debug entry: `adb shell am start ... -e video /sdcard/Download/x.mp4`
    // 统一的"开始分析"入口：报告页/自动套用/手动标定都走这里
    fun startAnalysis(
        uri: Uri,
        rim: NRect,
        seed: NPt? = null,
        frame: Bitmap? = null,
        autoDetected: Boolean = false,
        rememberRim: Boolean = true,
        rimInputTrusted: Boolean = true,
    ) {
        if (rememberRim) frame?.let { f ->
            try { app.bball.court.core.RimStore.save(ctx, app.bball.court.core.SceneCut.signature(f), rim) } catch (_: Throwable) {}
            // 顺带攒训练样本（A 的确认动作 → B 的数据集）
            try { app.bball.court.core.RimSampleStore.save(ctx, f, rim, tag = if (initialRim == null) "ui" else "adb") } catch (_: Throwable) {}
        }
        screen = Screen.Analyzing(uri, rim, 0f, ShotReport())
        scope.launch {
            try {
                val report = analyzer.analyze(
                    uri,
                    rim,
                    ShotAnalyzer.Options(
                        seedPoint = seed,
                        homography = court,
                        autoDetected = autoDetected,
                        rimInputTrusted = rimInputTrusted,
                    ),
                ) { p, live ->
                    screen = Screen.Analyzing(uri, rim, p, live)
                }
                finishAnalysis(uri, report)
            } catch (t: Throwable) {
                screen = Screen.Failed("分析失败", (t.message ?: t.javaClass.simpleName))
            }
        }
    }

    fun autoRim(frame: Bitmap): NRect? {
        val candidates = app.bball.court.core.RimCandidateFinder.findCandidates(frame)
        val point = app.bball.court.core.DynamicRimCalibrator().update(0L, candidates)
        return point?.takeIf { it.confidence >= 0.34f }?.rim
    }

    fun rimOverride(): NRect? {
        val values = initialRim?.split(",")?.mapNotNull { it.trim().toFloatOrNull() }
        return if (values != null && values.size == 4) {
            NRect(values[0], values[1], values[2], values[3])
        } else {
            null
        }
    }

    LaunchedEffect(initialVideoPath, initialAtMs) {
        if (!initialVideoPath.isNullOrBlank()) {
            try {
                val uri = Uri.fromFile(java.io.File(initialVideoPath))
                val frame = analyzer.firstFrame(uri, atMs = initialAtMs)
                if (frame == null) {
                    screen = Screen.Failed("这个视频解不出画面", initialVideoPath)
                } else {
                    val sig = app.bball.court.core.SceneCut.signature(frame)
                    val remembered = app.bball.court.core.RimStore.find(ctx, sig)
                    val override = rimOverride()
                    val detected = if (remembered == null && override == null) autoRim(frame) else null
                    when {
                        override != null -> startAnalysis(
                            uri, override, frame = frame, rememberRim = false,
                        )
                        remembered != null -> startAnalysis(
                            uri, remembered, frame = frame, autoDetected = true,
                        )
                        detected != null -> startAnalysis(
                            uri, detected, frame = frame, autoDetected = true,
                            rememberRim = false, rimInputTrusted = false,
                        )
                        else -> screen = Screen.Calibrate(uri, frame)
                    }
                }
            } catch (t: Throwable) {
                screen = Screen.Failed("读取视频失败", t.message ?: t.javaClass.simpleName)
            }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                ShotHistoryStore.rememberReadPermission(ctx, uri)
                val frame = analyzer.firstFrame(uri)
                if (frame == null) {
                    screen = Screen.Failed("这个视频解不出画面", "可能是 HEVC / 10bit / 特殊封装。换一段手机自己录的 mp4（H.264）再试。")
                } else {
                    // 机位指纹匹配：同一机位直接分析，不再让用户标定
                    val sig = app.bball.court.core.SceneCut.signature(frame)
                    val remembered = app.bball.court.core.RimStore.find(ctx, sig)
                    val override = rimOverride()
                    val detected = if (remembered == null && override == null) autoRim(frame) else null
                    when {
                        override != null -> startAnalysis(
                            uri, override, frame = frame, rememberRim = false,
                        )
                        remembered != null -> startAnalysis(
                            uri, remembered, frame = frame, autoDetected = true,
                        )
                        detected != null -> startAnalysis(
                            uri, detected, frame = frame, autoDetected = true,
                            rememberRim = false, rimInputTrusted = false,
                        )
                        else -> screen = Screen.Calibrate(uri, frame)
                    }
                }
            } catch (t: Throwable) {
                screen = Screen.Failed("读取视频失败", t.message ?: t.javaClass.simpleName)
            }
        }
    }

    Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
      Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        when (val s = screen) {
            is Screen.Home -> if (crash != null) {
                FailedScreen(
                    title = "上次崩溃了",
                    detail = crash!!,
                    primary = "我知道了",
                    onPrimary = { CrashLog.clear(ctx); crash = null },
                )
            } else HomeScreen(
                onPick = { pick.launch(arrayOf("video/*")) },
                onCameras = { screen = Screen.Cameras },
                onHistory = { screen = Screen.History },
                sens = sens,
                onCycleSens = {
                    val next = when (sens) {
                        app.bball.court.core.Sensitivity.LOOSE -> app.bball.court.core.Sensitivity.STANDARD
                        app.bball.court.core.Sensitivity.STANDARD -> app.bball.court.core.Sensitivity.STRICT
                        app.bball.court.core.Sensitivity.STRICT -> app.bball.court.core.Sensitivity.LOOSE
                    }
                    app.bball.court.core.Sensitivity.save(ctx, next)
                    sens = next
                },
            )
            is Screen.Cameras -> CameraListScreen(onBack = { screen = Screen.Home })
            is Screen.History -> HistoryScreen(
                onBack = { screen = Screen.Home },
                onOpen = { screen = Screen.HistoryDetail(it) },
            )
            is Screen.HistoryDetail -> HistoryDetailScreen(
                record = s.record,
                onBack = { screen = Screen.History },
            )
            is Screen.Failed -> FailedScreen(
                title = s.title, detail = s.detail,
                primary = "换一个视频",
                onPrimary = { screen = Screen.Home },
            )
            is Screen.Calibrate -> CalibrateScreen(
                frame = s.frame,
                court = court,
                initialRim = initialRim,
                onOpenCourtCalib = { screen = Screen.CourtCalib(s.uri, s.frame) },
                onBack = { screen = Screen.Home },
                onStart = { hoop, seed ->
                    s.frame?.let { f ->
                        try { app.bball.court.core.RimStore.save(ctx, app.bball.court.core.SceneCut.signature(f), hoop) } catch (_: Throwable) {}
                    }
                    screen = Screen.Analyzing(s.uri, hoop, 0f, ShotReport())
                    scope.launch {
                        try {
                            val report = analyzer.analyze(
                                s.uri,
                                hoop,
                                ShotAnalyzer.Options(
                                    seedPoint = seed,
                                    homography = court,
                                    autoDetected = false,
                                    rimInputTrusted = true,
                                ),
                            ) { p, live ->
                                screen = Screen.Analyzing(s.uri, hoop, p, live)
                            }
                            finishAnalysis(s.uri, report)
                        } catch (t: Throwable) {
                            screen = Screen.Failed("分析失败", (t.message ?: t.javaClass.simpleName))
                        }
                    }
                },
            )
            is Screen.CourtCalib -> CourtCalibScreen(
                frame = s.frame,
                onBack = { screen = Screen.Calibrate(s.uri, s.frame) },
                onDone = { h, pts -> court = h; app.bball.court.core.CalibStore.save(ctx, pts); screen = Screen.Calibrate(s.uri, s.frame) },
            )
            is Screen.Analyzing -> AnalyzingScreen(s)
            is Screen.Report -> ReportScreen(
                report = s.report,
                sourceUri = s.uri,
                history = s.history,
                onAgain = { screen = Screen.Home },
            )
        }
      }
    }
}

@Composable
private fun HomeScreen(
    onPick: () -> Unit,
    onCameras: () -> Unit,
    onHistory: () -> Unit,
    sens: app.bball.court.core.Sensitivity,
    onCycleSens: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("ShotLab", fontSize = 40.sp, fontWeight = FontWeight.Black, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text("投篮分析 · 端侧离线跑，视频不出手机", color = Dim, fontSize = 14.sp)
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tag("命中率"); Tag("出手角"); Tag("肘 / 膝角")
        }
        Spacer(Modifier.height(36.dp))
        Button(
            onClick = onPick,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C5CFF)),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("选择篮球视频", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(20.dp))
        Text("快捷入口", color = Dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeShortcut(
                title = "解析历史",
                subtitle = "查看报告与出手明细",
                onClick = onHistory,
                modifier = Modifier.weight(1f),
            )
            HomeShortcut(
                title = "机位管理",
                subtitle = "查看并删除已存机位",
                onClick = onCameras,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "拍摄建议：相机放在球场侧面或 45° 斜侧，能同时看到人和篮筐；画面里只留一个投篮的人，命中判定最准。",
            color = Dim, fontSize = 12.sp, lineHeight = 18.sp,
        )
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onCycleSens) { Text("姿态指标灵敏度：${sens.label}（点击切换）", color = Color(0xFFFFC857), fontSize = 12.sp) }
    }
}

@Composable
private fun HomeShortcut(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Card)
            .border(1.dp, Color(0xFF34343F), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("›", color = Color(0xFF3EE8C8), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(3.dp))
        Text(subtitle, color = Dim, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun Tag(text: String) {
    Box(
        Modifier.background(Color(0xFF202027), RoundedCornerShape(50))
            .border(1.dp, Color(0xFF34343F), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) { Text(text, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun CourtCalibScreen(frame: Bitmap?, onBack: () -> Unit, onDone: (app.bball.court.core.CourtHomography, List<Pair<Float, Float>>) -> Unit) {
    val pts = remember { mutableStateListOf<Pair<Float, Float>>() }
    var err by remember { mutableStateOf<String?>(null) }
    val refs = app.bball.court.core.CourtHomography.REFERENCE
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            val ratio = if (frame != null && frame.height > 0) frame.width.toFloat() / frame.height else 16f / 9f
            Box(Modifier.fillMaxSize().aspectRatio(ratio)) {
                if (frame != null) Image(
                    bitmap = frame.asImageBitmap(), contentDescription = null,
                    contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize(),
                )
                Canvas(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTapGestures { off ->
                            if (pts.size < 4) {
                                pts.add(off.x / size.width to off.y / size.height)
                                if (pts.size == 4) {
                                    val h = app.bball.court.core.CourtHomography.solve(pts.toList())
                                    if (h == null) { err = "四点几乎共线，重来"; pts.clear() } else onDone(h, pts.toList())
                                }
                            }
                        }
                    },
                ) {
                    pts.forEach { q ->
                        drawCircle(Color(0xFF3EE8C8), 9f, Offset(q.first * size.width, q.second * size.height))
                        drawCircle(Color.White, 4f, Offset(q.first * size.width, q.second * size.height))
                    }
                }
            }
        }
        Surface(color = Color(0xFF0B0A10), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("球场四点标定", color = Color(0xFFEDEDF2), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (pts.size < 4) "第 ${pts.size + 1} 点：" + refs[pts.size].first else "解算成功",
                    color = Color(0xFF3EE8C8), fontSize = 13.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "按顺序点击球场上这 4 个位置（画面里能看到几个点几个）。标定后 三分/罚球/热区 才准确。",
                    color = Color(0xFF9B9BAA), fontSize = 12.sp, lineHeight = 17.sp,
                )
                if (err != null) { Spacer(Modifier.height(6.dp)); Text(err!!, color = Color(0xFFFF6B7F), fontSize = 12.sp) }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { pts.clear(); err = null }, modifier = Modifier.weight(1f)) { Text("重来") }
                    OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("返回") }
                }
            }
        }
    }
}

@Composable
private fun CalibrateScreen(
    frame: Bitmap?,
    court: app.bball.court.core.CourtHomography?,
    initialRim: String? = null,
    onOpenCourtCalib: () -> Unit,
    onBack: () -> Unit,
    onStart: (NRect, NPt?) -> Unit,
) {
    val ctx = LocalContext.current
    var autoApplied by remember { mutableStateOf(false) }
    var rect by remember {
        // ① 命令行指定 ② 同机位历史记录 ③ 默认框
        val remembered = frame?.let { f ->
            val sig = app.bball.court.core.SceneCut.signature(f)
            app.bball.court.core.RimStore.find(ctx, sig)
        }
        if (initialRim == null && remembered != null) { autoApplied = true }
        val parsed = initialRim?.split(",")?.mapNotNull { it.trim().toFloatOrNull() }
        mutableStateOf(
            if (parsed != null && parsed.size == 4) NRect(parsed[0], parsed[1], parsed[2], parsed[3])
            else remembered ?: NRect(0.35f, 0.18f, 0.62f, 0.34f)
        )
    }
    var seed by remember { mutableStateOf<NPt?>(null) }
    var mode by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            val ratio = if (frame != null && frame.height > 0) frame.width.toFloat() / frame.height else 16f / 9f
            Box(Modifier.fillMaxSize().aspectRatio(ratio)) {
            if (frame != null) {
                Image(
                    bitmap = frame.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { off ->
                                // 双击 = 一键把筐框中心对准手指（比拖拽精确得多）
                                val cx = off.x / size.width
                                val cy = off.y / size.height
                                val hw = (rect.right - rect.left) / 2f
                                val hh = (rect.bottom - rect.top) / 2f
                                rect = NRect(cx - hw, cy - hh, cx + hw, cy + hh)
                            },
                            onTap = { off ->
                                seed = NPt(off.x / size.width, off.y / size.height)
                            },
                        )
                    }
                    .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { p ->
                            val w = size.width.toFloat(); val h = size.height.toFloat()
                            mode = if (abs(p.x - rect.right * w) < 60f && abs(p.y - rect.bottom * h) < 60f) 2 else 1
                        },
                        onDragEnd = { mode = 0 },
                    ) { change, delta ->
                        change.consume()
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        val dx = delta.x / w; val dy = delta.y / h
                        rect = if (mode == 2) {
                            rect.copy(
                                right = (rect.right + dx).coerceIn(rect.left + 0.008f, 1f),
                                bottom = (rect.bottom + dy).coerceIn(rect.top + 0.008f, 1f),
                            )
                        } else {
                            val nx = (rect.left + dx).coerceIn(0f, 1f - rect.w)
                            val ny = (rect.top + dy).coerceIn(0f, 1f - rect.h)
                            rect.copy(left = nx, top = ny, right = nx + rect.w, bottom = ny + rect.h)
                        }
                    }
                },
            ) {
                val w = size.width; val h = size.height
                drawRect(color = Color(0x99000000), topLeft = Offset(0f, 0f), size = Size(w, h))
                val l = rect.left * w; val t = rect.top * h
                val r = rect.right * w; val b = rect.bottom * h
                drawRect(color = Color(0x33000000), topLeft = Offset(l, t), size = Size(r - l, b - t))
                drawRect(brush = Accent, topLeft = Offset(l, t), size = Size(r - l, b - t), style = Stroke(width = 3f))
                drawCircle(Color.White, 12f, Offset(r, b))
                seed?.let { s ->
                    val sx = s.x * w; val sy = s.y * h
                    drawCircle(Color(0xFF3EE8C8), 13f, Offset(sx, sy), style = Stroke(width = 3f))
                    drawCircle(Color(0xFF3EE8C8), 3f, Offset(sx, sy))
                }
            }
            }
        }
        Column(Modifier.background(Bg).padding(20.dp)) {
            var showTune by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("对准篮筐", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                val rimPx = ((rect.right - rect.left) * (frame?.width ?: 0)).toInt()
                val rimOk = rimPx >= 90
                Box(Modifier.size(6.dp).background(if (rimOk) Color(0xFF2EE6B0) else Color(0xFFFFC857), RoundedCornerShape(50)))
                Spacer(Modifier.width(5.dp))
                Text("筐宽 " + rimPx + "px", color = if (rimOk) Color(0xFF2EE6B0) else Color(0xFFFFC857), fontSize = 11.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text("双击画面，把筐框中心对准篮筐", color = Dim, fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showTune = !showTune }) {
                    Text(if (showTune) "收起微调" else "微调", color = Dim, fontSize = 12.sp)
                }
                TextButton(onClick = onOpenCourtCalib) {
                    Text(if (court == null) "四点标定" else "已标定 ✓", color = if (court == null) Dim else Color(0xFF2EE6B0), fontSize = 12.sp)
                }
            }
            if (showTune) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val fw = (frame?.width ?: 720).toFloat()
                        val px = (rect.right - rect.left) * fw
                        val nw = ((px - 2f) / fw).coerceAtLeast(0.008f)
                        val nh = nw * 0.75f
                        rect = NRect(rect.cx - nw / 2, rect.cy - nh / 2, rect.cx + nw / 2, rect.cy + nh / 2)
                    }) { Text("− 2px", color = Color(0xFF3EE8C8), fontSize = 12.sp) }
                    TextButton(onClick = {
                        val fw = (frame?.width ?: 720).toFloat()
                        val px = (rect.right - rect.left) * fw
                        val nw = ((px + 2f) / fw).coerceAtMost(0.25f)
                        val nh = nw * 0.75f
                        rect = NRect(rect.cx - nw / 2, rect.cy - nh / 2, rect.cx + nw / 2, rect.cy + nh / 2)
                    }) { Text("+ 2px", color = Color(0xFF3EE8C8), fontSize = 12.sp) }
                    TextButton(onClick = {
                        frame?.let { f ->
                            val snapped = app.bball.court.core.RimSnap.snap(f, rect)
                            if (snapped != null) rect = snapped
                        }
                    }) { Text("自动贴合", color = Dim, fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("重选视频") }
                Button(
                    onClick = { onStart(rect, seed) },
                    modifier = Modifier.weight(1.6f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C5CFF)),
                ) { Text("开始分析", fontSize = 15.sp) }
            }
        }
    }
}

@Composable
private fun AnalyzingScreen(s: Screen.Analyzing) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("分析中…", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(18.dp))
        LinearProgressIndicator(
            progress = { s.progress },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = Color(0xFF7C5CFF), trackColor = Color(0xFF202027),
        )
        Spacer(Modifier.height(10.dp))
        Text("${(s.progress * 100).toInt()}%  ·  已处理 ${s.live.framesAnalysed} 帧", color = Dim, fontSize = 12.sp)
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("已处理", "${s.live.framesAnalysed} 帧")
            Stat("球检出", "${(s.live.ballHitRate * 100).toInt()}%")
        }
        Spacer(Modifier.height(14.dp))
        Text("出手和命中会在完整轨迹汇总后生成", color = Dim, fontSize = 12.sp)
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(Modifier.background(Card, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(value, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text(label, color = Dim, fontSize = 11.sp)
    }
}

@Composable
private fun ShotPlaybackOverlay(
    report: ShotReport,
    sourceUri: Uri,
    index: Int,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val shot = report.shots.getOrNull(index)
    var clipFile by remember(sourceUri, index) { mutableStateOf<java.io.File?>(null) }
    var preparing by remember(sourceUri, index) { mutableStateOf(true) }
    var failed by remember(sourceUri, index) { mutableStateOf(false) }
    var ended by remember(sourceUri, index) { mutableStateOf(false) }
    val exoPlayer = remember { ExoPlayer.Builder(ctx).build() }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                ended = playbackState == Player.STATE_ENDED
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(sourceUri, index) {
        preparing = true
        failed = false
        ended = false
        clipFile = if (shot == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                app.bball.court.core.ClipMaker.cutShotPreview(ctx, sourceUri, shot)
            }
        }
        failed = clipFile == null
        preparing = false
    }

    LaunchedEffect(clipFile) {
        val file = clipFile ?: return@LaunchedEffect
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    BackHandler(onBack = onClose)

    Box(
        Modifier.fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            preparing -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF3EE8C8))
                    Spacer(Modifier.height(12.dp))
                    Text("正在生成回放…", color = Dim, fontSize = 12.sp)
                }
            }
            failed -> Text("无法读取这次出手，源视频可能已失效", color = Color(0xFFFF6B7F), fontSize = 13.sp)
            clipFile != null -> AndroidView(
                factory = { context ->
                    // Compose 浮窗使用 TextureView，避免 SurfaceView 单独合成时出现黑屏。
                    (LayoutInflater.from(context)
                        .inflate(app.bball.court.R.layout.shot_playback_player, FrameLayout(context), false) as PlayerView).apply {
                        player = exoPlayer
                        keepScreenOn = true
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.align(Alignment.TopEnd)
                .padding(14.dp)
                .size(38.dp)
                .background(Color(0x66000000), CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Text("×", color = Color.White, fontSize = 24.sp)
        }
        if (ended) {
            Box(
                Modifier.size(72.dp)
                    .background(Color(0x77000000), CircleShape)
                    .clickable {
                        exoPlayer.seekTo(0)
                        exoPlayer.play()
                        ended = false
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("↻", color = Color.White, fontSize = 42.sp)
            }
        }
    }
}

@Composable
private fun HistoryScreen(onBack: () -> Unit, onOpen: (ShotHistoryRecord) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ShotHistoryRecord?>(null) }
    var renameError by remember { mutableStateOf<String?>(null) }
    val records = remember(version) {
        try { ShotHistoryStore.list(ctx) } catch (_: Throwable) { emptyList() }
    }

    BackHandler(onBack = onBack)

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部解析历史？") },
            text = { Text("统计记录和缩略图会从本机删除，源视频不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { ShotHistoryStore.clear(ctx) }
                        version++
                    }
                }) { Text("清空", color = Color(0xFFFF6B7F)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }

    renameTarget?.let { target ->
        RenameHistoryDialog(
            initialName = target.sourceName,
            error = renameError,
            onDismiss = {
                renameTarget = null
                renameError = null
            },
            onConfirm = { name ->
                scope.launch {
                    try {
                        val updated = withContext(Dispatchers.IO) {
                            ShotHistoryStore.rename(ctx, target.id, name)
                        }
                        if (updated == null) {
                            renameError = "名称不能为空"
                        } else {
                            renameTarget = null
                            renameError = null
                            version++
                        }
                    } catch (_: Throwable) {
                        renameError = "重命名失败，请重试"
                    }
                }
            },
        )
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("解析历史", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (records.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }) {
                    Text("清空全部", color = Color(0xFFFF8A8A), fontSize = 12.sp)
                }
            }
            TextButton(onClick = onBack) { Text("返回", color = Dim, fontSize = 12.sp) }
        }
        Spacer(Modifier.height(8.dp))
        Text("每次成功解析都会新增一条记录。", color = Dim, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        if (records.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("还没有解析记录", color = Dim, fontSize = 13.sp)
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                records.forEach { record ->
                    Column(
                        Modifier.fillMaxWidth()
                            .padding(vertical = 5.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Card)
                            .clickable { onOpen(record) }
                            .padding(14.dp),
                    ) {
                        Text(
                            record.sourceName,
                            color = Ink,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                historyTime(record.createdAtMs),
                                color = Dim,
                                fontSize = 11.sp,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                renameTarget = record
                                renameError = null
                            }) { Text("重命名", color = Color(0xFF3EE8C8), fontSize = 11.sp) }
                            TextButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { ShotHistoryStore.delete(ctx, record.id) }
                                    version++
                                }
                            }) { Text("删除", color = Color(0xFFFF8A8A), fontSize = 11.sp) }
                        }
                        Text(
                            "${record.made} 中 / ${record.attempts} 次出手 · " +
                                "两分 ${record.twoMade}/${record.twoAtt} · " +
                                "三分 ${record.threeMade}/${record.threeAtt}" +
                                if (record.unknownAtt > 0) {
                                    " · 未分类 ${record.unknownMade}/${record.unknownAtt}"
                                } else {
                                    ""
                                },
                            color = if (record.made > 0) Color(0xFF2EE6B0) else Dim,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameHistoryDialog(
    initialName: String,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName.take(60)) }
    val cleanName = name.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("给解析视频命名") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 60) name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(error.orEmpty(), color = Color(0xFFFF6B7F), fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("${name.length}/60", color = Dim, fontSize = 10.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = cleanName.isNotEmpty(),
                onClick = { onConfirm(cleanName) },
            ) { Text("保存", color = Color(0xFF3EE8C8)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Dim) }
        },
    )
}

@Composable
private fun HistoryDetailScreen(record: ShotHistoryRecord, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var report by remember(record.id) { mutableStateOf<ShotReport?>(null) }
    var failed by remember(record.id) { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    LaunchedEffect(record.id) {
        try {
            report = withContext(Dispatchers.IO) { ShotHistoryStore.loadReport(ctx, record) }
        } catch (_: Throwable) {
            failed = true
        }
    }

    val loaded = report
    if (loaded != null) {
        ReportScreen(
            report = loaded,
            sourceUri = Uri.parse(record.sourceUri),
            history = record,
            onAgain = onBack,
            againLabel = "返回历史",
        )
    } else {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("解析明细", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onBack) { Text("返回", color = Dim, fontSize = 12.sp) }
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (failed) Text("历史明细读取失败", color = Color(0xFFFF6B7F), fontSize = 13.sp)
                else CircularProgressIndicator(color = Color(0xFF3EE8C8))
            }
        }
    }
}

private fun historyTime(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

@Composable
private fun ReportScreen(
    report: ShotReport,
    sourceUri: Uri,
    history: ShotHistoryRecord? = null,
    onAgain: () -> Unit,
    againLabel: String = "再分析一个视频",
) {
    val r = report
    val reportCtx = LocalContext.current
    val renameScope = rememberCoroutineScope()
    var displayName by remember(history?.id, history?.sourceName) {
        mutableStateOf(history?.sourceName)
    }
    var renameOpen by remember(history?.id) { mutableStateOf(false) }
    var renameError by remember(history?.id) { mutableStateOf<String?>(null) }
    var playbackIndex by remember(report) { mutableStateOf<Int?>(null) }
    val scrollState = rememberScrollState()

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(24.dp)) {
        Spacer(Modifier.height(24.dp))
        if (history != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        displayName ?: history.sourceName,
                        color = Ink,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(historyTime(history.createdAtMs), color = Dim, fontSize = 11.sp)
                }
                TextButton(onClick = {
                    renameError = null
                    renameOpen = true
                }) { Text("重命名", color = Color(0xFF3EE8C8), fontSize = 12.sp) }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (r.autoRim) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(6.dp).background(
                        if (r.calibrated) Color(0xFF2EE6B0) else Color(0xFFFFC857),
                        RoundedCornerShape(50),
                    ),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    if (r.calibrated) {
                        "已自动识别机位，球场标定已生效"
                    } else {
                        "已自动识别篮筐；未做球场四点标定，类型按轨迹与筐宽比例估算"
                    },
                    color = if (r.calibrated) Color(0xFF2EE6B0) else Color(0xFFFFC857),
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        if (r.rimAdjustedPx > 0) {
            Text(
                "篮筐框中心已按真实穿筐轨迹自动校正 ${r.rimAdjustedPx} px",
                color = Color(0xFFFFC857),
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(6.dp))
        }
        Text("分析结果", color = Dim, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${(r.accuracy * 100).toInt()}", fontSize = 64.sp, fontWeight = FontWeight.Black, color = Ink)
            Text("%", fontSize = 24.sp, color = Dim, modifier = Modifier.padding(bottom = 12.dp))
        }
        Text("${r.made} 中 / ${r.attempts} 次出手", color = Dim, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("连中", "${r.bestStreak}")
            Stat("最长连铁", "${r.bestMissStreak}")
            Stat("平均间隔", if (r.avgIntervalMs > 0) String.format(java.util.Locale.US, "%.1fs", r.avgIntervalMs / 1000f) else "—")
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("上篮", "${r.layupMade}/${r.layupAtt}")
            Stat("两分", "${r.twoMade}/${r.twoAtt}")
            Stat("三分", "${r.threeMade}/${r.threeAtt}")
            Stat("罚球", "${r.ftMade}/${r.ftAtt}")
        }
        if (r.unknownAtt > 0) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("未分类", "${r.unknownMade}/${r.unknownAtt}")
            }
        }
        Spacer(Modifier.height(24.dp))
        Metric("平均出手角", r.avgReleaseAngle?.let { "${it.toInt()}°" } ?: "—")
        Metric("平均肘角", r.avgElbowAngle?.let { "${it.toInt()}°" } ?: "—")
        Metric("平均膝角", r.avgKneeAngle?.let { "${it.toInt()}°" } ?: "—")
        Metric("平均持球时长", r.avgBallInHandMs?.let { "${it} ms" } ?: "—")
        Spacer(Modifier.height(8.dp))
        if (r.framesAnalysed > 0 && r.rimPx > 0 && r.rimPx < 90) {
            Spacer(Modifier.height(8.dp))
            Text(
                "⚠ 篮筐只有 " + r.rimPx + " px（建议 ≥90）：低分辨率下按高弧下落轨迹计数，不用于区分打铁。靠近拍摄、变焦 2.5 倍，或改录 4K。",
                color = Color(0xFFFFC857),
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
        if (r.rimBallRatio > 2.1f) {
            Spacer(Modifier.height(8.dp))
            Text("⚠ 筐框偏大（筐/球 = " + String.format(java.util.Locale.US, "%.1f", r.rimBallRatio) + "，正常约 1.9）：只框篮筐圈，别把篮板框进去", color = Color(0xFFFFC857), fontSize = 11.sp, lineHeight = 15.sp)
        }
        Text(
            "球检出率 ${(r.ballHitRate * 100).toInt()}% · 处理 ${r.framesAnalysed} 帧。检出率低于 60% 时结论仅供参考，换个机位再试。",
            color = Dim, fontSize = 11.sp, lineHeight = 16.sp,
        )
        val courtShots = if (r.calibrated) r.shots.filter { it.distanceM > 0.01f } else emptyList()
        if (courtShots.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("出手热区（球场坐标 · 米）", color = Dim, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Canvas(Modifier.fillMaxWidth().height(150.dp).background(Card, RoundedCornerShape(10.dp))) {
                val pad = 12f
                val w = size.width - pad * 2
                val h = size.height - pad * 2
                val xMin = -7.5f; val xMax = 7.5f; val yMax = 8f
                fun px(x: Float) = pad + ((x - xMin) / (xMax - xMin)) * w
                fun py(y: Float) = pad + h - (y / yMax) * h
                // 罚球线
                drawLine(Color(0xFF34343F), Offset(px(-2.45f), py(4.225f)), Offset(px(2.45f), py(4.225f)), 1.5f)
                // 三分弧（6.75m）
                drawArc(
                    color = Color(0xFF3A3A48), startAngle = 180f, sweepAngle = 180f, useCenter = false,
                    topLeft = Offset(px(-6.75f), py(6.75f)),
                    size = Size((px(6.75f) - px(-6.75f)), (py(0f) - py(6.75f))),
                    style = Stroke(width = 1.5f),
                )
                // 篮筐
                drawCircle(Color(0xFFFF8A3D), 5f, Offset(px(0f), py(0f)))
                // 出手点
                courtShots.forEach { sh ->
                    drawCircle(
                        if (sh.made) Color(0xFF2EE6B0) else Color(0xFFFF6B7F),
                        5f,
                        Offset(px(sh.courtX).coerceIn(pad, pad + w), py(sh.courtY).coerceIn(pad, pad + h)),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            val offCourt = courtShots.count { it.courtY < -0.5f || it.courtY > 9f || kotlin.math.abs(it.courtX) > 8f }
            if (offCourt * 3 > courtShots.size) {
                Text("⚠ 有 " + offCourt + "/" + courtShots.size + " 个出手点落在球场外，标定可能不准（重做四点标定）", color = Color(0xFFFFC857), fontSize = 11.sp)
            }
            Text(
                "● 命中 " + courtShots.count { it.made } + "   ● 不中 " + courtShots.count { !it.made } +
                    "   平均距离 " + String.format(java.util.Locale.US, "%.1f", courtShots.map { it.distanceM }.average()) + " m",
                color = Dim, fontSize = 11.sp,
            )
        }

        if (r.shots.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("出手明细（点图核对）", color = Dim, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            r.shots.chunked(3).forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEachIndexed { columnIndex, sh ->
                        val shotIndex = rowIndex * 3 + columnIndex
                        Column(
                            Modifier.weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { playbackIndex = shotIndex },
                        ) {
                            val bmp = sh.thumb
                            Box(
                                Modifier.fillMaxWidth().height(64.dp).background(Card, RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = "播放第 ${shotIndex + 1} 次出手",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                Box(
                                    Modifier.size(28.dp).background(Color(0xAA000000), CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("▶", color = Color.White, fontSize = 13.sp)
                                }
                            }
                            Text(
                                "${sh.tMs / 1000}s · ${sh.type.label} · " +
                                    (if (sh.made) "中" else "不中") + " · 点击播放",
                                color = if (sh.made) Color(0xFF2EE6B0) else Color(0xFFFF6B7F), fontSize = 10.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        if (r.shots.any { it.made }) {
            Spacer(Modifier.height(10.dp))
            var clipMsg by remember { mutableStateOf<String?>(null) }
            val ctx = LocalContext.current
            val scope2 = rememberCoroutineScope()
            Button(
                onClick = {
                    scope2.launch(Dispatchers.IO) {
                        val segs = app.bball.court.core.ClipMaker.segmentsForShots(
                            shots = r.shots,
                            preMs = 2000,
                            postMs = 1200,
                            onlyMade = true,
                        )
                        val name = "ShotLab_" + System.currentTimeMillis() + ".mp4"
                        val where = app.bball.court.core.ClipMaker.cutToGallery(ctx, sourceUri, segs, name)
                        clipMsg = if (where != null) "已保存到相册：Movies/ShotLab/" + name + "（" + segs.size + " 段命中）"
                                  else "生成失败（源视频或片段异常）"
                    }
                },
                modifier = Modifier.fillMaxWidth().height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A36)),
            ) { Text("生成命中集锦", color = Color(0xFF3EE8C8), fontSize = 13.sp) }
            Spacer(Modifier.height(10.dp))
            var exportMsg by remember { mutableStateOf<String?>(null) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        val name = app.bball.court.core.ReportExport.saveCsv(ctx, r)
                        exportMsg = if (name != null) "已导出：Download/ShotLab/" + name else "导出失败"
                    },
                    modifier = Modifier.weight(1f).height(42.dp),
                ) { Text("导出 CSV", fontSize = 13.sp) }
                OutlinedButton(
                    onClick = { app.bball.court.core.ReportExport.shareText(ctx, r) },
                    modifier = Modifier.weight(1f).height(42.dp),
                ) { Text("分享结果", fontSize = 13.sp) }
            }
            if (exportMsg != null) {
                Spacer(Modifier.height(4.dp))
                Text(exportMsg!!, color = Dim, fontSize = 11.sp)
            }
            if (clipMsg != null) {
                Spacer(Modifier.height(6.dp))
                Text(clipMsg!!, color = Color(0xFF9B9BAA), fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onAgain, modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C5CFF)),
        ) { Text(againLabel, fontWeight = FontWeight.Bold) }
    }
    val selectedShot = playbackIndex
    if (selectedShot != null) {
        ShotPlaybackOverlay(
            report = report,
            sourceUri = sourceUri,
            index = selectedShot,
            onClose = { playbackIndex = null },
        )
    }
    if (renameOpen && history != null) {
        RenameHistoryDialog(
            initialName = displayName ?: history.sourceName,
            error = renameError,
            onDismiss = {
                renameOpen = false
                renameError = null
            },
            onConfirm = { name ->
                renameScope.launch {
                    try {
                        val updated = withContext(Dispatchers.IO) {
                            ShotHistoryStore.rename(reportCtx, history.id, name)
                        }
                        if (updated == null) {
                            renameError = "名称不能为空"
                        } else {
                            displayName = updated.sourceName
                            renameOpen = false
                            renameError = null
                        }
                    } catch (_: Throwable) {
                        renameError = "重命名失败，请重试"
                    }
                }
            },
        )
    }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Dim, fontSize = 13.sp)
        Text(value, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
    HorizontalDivider(color = Color(0xFF26262F))
}

@Composable
private fun FailedScreen(title: String, detail: String, primary: String, onPrimary: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Spacer(Modifier.height(40.dp))
        Text(title, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().weight(1f).background(Card, RoundedCornerShape(14.dp)).padding(14.dp),
        ) {
            Text(detail, color = Dim, fontSize = 11.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onPrimary, modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C5CFF)),
        ) { Text(primary, fontWeight = FontWeight.Bold) }
    }
}


@Composable
private fun CameraListScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var ver by remember { mutableStateOf(0) }
    val items = remember(ver) { try { app.bball.court.core.RimStore.list(ctx) } catch (_: Throwable) { emptyList() } }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("机位管理", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("返回", color = Dim, fontSize = 12.sp) }
        }
        Spacer(Modifier.height(8.dp))
        Text("已记住 " + items.size + " 个机位的篮筐位置，同一机位下次不用再标定。", color = Dim, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            items.forEachIndexed { i, r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("机位 " + (i + 1), color = Ink, fontSize = 13.sp)
                    Spacer(Modifier.width(10.dp))
                    Text("筐宽 " + ((r.right - r.left) * 100).toInt() + "% 画面", color = Dim, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { app.bball.court.core.RimStore.deleteAt(ctx, i); ver++ }) {
                        Text("删除", color = Color(0xFFFF6B6B), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
