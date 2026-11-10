@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, FlowPreview::class)

package dev.metzook.teleprompter.ui.prompter

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import dev.metzook.teleprompter.BuildConfig
import dev.metzook.teleprompter.KeyCommandBus
import dev.metzook.teleprompter.asr.AsrEngine
import dev.metzook.teleprompter.asr.AsrEvent
import dev.metzook.teleprompter.asr.AsrModel
import dev.metzook.teleprompter.asr.AsrModels
import dev.metzook.teleprompter.asr.AsrState
import dev.metzook.teleprompter.asr.ModelManager
import dev.metzook.teleprompter.follow.FollowMode
import dev.metzook.teleprompter.follow.FollowTrace
import dev.metzook.teleprompter.follow.FollowVoiceCommands
import dev.metzook.teleprompter.follow.LangDetect
import dev.metzook.teleprompter.follow.MatchLang
import dev.metzook.teleprompter.follow.FollowState
import dev.metzook.teleprompter.follow.Matcher
import dev.metzook.teleprompter.follow.ScriptIndex
import dev.metzook.teleprompter.follow.ScrollDynamics
import dev.metzook.teleprompter.follow.SessionStats
import dev.metzook.teleprompter.follow.SessionStatsBuilder
import dev.metzook.teleprompter.follow.SpokenSegment
import dev.metzook.teleprompter.follow.VoiceCommand
import dev.metzook.teleprompter.follow.VoiceCommandDetector
import dev.metzook.teleprompter.session.FollowTraceWriter
import dev.metzook.teleprompter.settings.FrameMargins
import dev.metzook.teleprompter.settings.GestureSlot
import dev.metzook.teleprompter.settings.PrompterAction
import dev.metzook.teleprompter.settings.ScreenOrientation
import dev.metzook.teleprompter.settings.SettingsRepository
import dev.metzook.teleprompter.vault.MarkdownParser
import dev.metzook.teleprompter.vault.ParsedScript
import dev.metzook.teleprompter.vault.Script
import dev.metzook.teleprompter.vault.ScriptRepository
import dev.metzook.teleprompter.vault.sections
import dev.metzook.teleprompter.vault.toDisplaySegments
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reading band position: the eyeline sits in the upper third (SPEC §5.1). */
private const val EYELINE_FRACTION = 0.30f

/**
 * Follow-mode motion feel (SPEC §1: glide, never jump): a velocity servo —
 * feed-forward at the measured reading rate, a dead zone that swallows
 * word-level anchor noise, and a hard cap on backward speed. The controller
 * itself is pure Kotlin ([dev.metzook.teleprompter.follow.ScrollDynamics]);
 * these constants scale it to the layout.
 */
private const val DEADBAND_LINES = 0.35f
private const val MAX_CATCHUP_VIEWPORTS_PER_SEC = 1.5f
private const val MAX_EASE_BACK_LINES_PER_SEC = 0.6f

/** Feed-forward keeps the text coasting this long after the last anchor
 *  advance; longer silence means the reader paused, so the glide stops. */
private const val COAST_AFTER_MATCH_SEC = 0.9f

/** Decode-lag change worth a trace line; the EMA jitters by 1-2 ms at rest. */
private const val DECODE_MS_TRACE_STEP = 5

/** Slow heartbeat so a silent-but-healthy engine still stamps the trace. */
private const val ASR_TRACE_HEARTBEAT_NANOS = 2_000_000_000L

/** How long the on-screen confirmation of a spoken page-back stays up. */
private const val VOICE_JUMP_FLASH_MS = 1200L

/** countdownSec sentinel: session starts when the reading voice is heard. */
const val START_ON_VOICE = -1

private val COUNTDOWN_CYCLE = listOf(0, 1, 2, 3, 5, START_ON_VOICE)

@Composable
fun PrompterScreen(
    repo: ScriptRepository,
    settingsRepo: SettingsRepository,
    modelManager: ModelManager,
    scriptId: String,
    onExit: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val activity = LocalActivity.current
    val view = LocalView.current
    val context = LocalContext.current

    var script by remember { mutableStateOf<Script?>(null) }
    var parsed by remember { mutableStateOf<ParsedScript?>(null) }
    var ready by remember { mutableStateOf(false) }
    var wpm by remember { mutableIntStateOf(140) }
    var fontSp by remember { mutableFloatStateOf(40f) }
    var mirrorH by remember { mutableStateOf(false) }
    var mirrorV by remember { mutableStateOf(false) }
    var countdownSec by remember { mutableIntStateOf(3) }
    var pinchEnabled by remember { mutableStateOf(true) }
    var gestureMap by remember {
        mutableStateOf(GestureSlot.entries.associateWith { it.default })
    }
    // Frontmatter overrides make a value session-only: don't write it back to defaults.
    var persistWpm by remember { mutableStateOf(true) }
    var persistFont by remember { mutableStateOf(true) }
    var asrModel by remember { mutableStateOf<AsrModel?>(null) }
    var preferredModel by remember { mutableStateOf<AsrModel?>(null) }
    var asrOverlayOn by remember { mutableStateOf(true) }
    var followVoice by remember { mutableStateOf(false) }
    var voiceCommands by remember { mutableStateOf(false) }
    var scriptLang by remember { mutableStateOf<String?>(null) }
    // Reading frame: kept per orientation, because the same rig crops a portrait
    // screen and a landscape one by very different fractions.
    var portraitMargins by remember { mutableStateOf(FrameMargins()) }
    var landscapeMargins by remember { mutableStateOf(FrameMargins()) }
    var frameEditing by remember { mutableStateOf(false) }
    var orientationPref by remember { mutableStateOf(ScreenOrientation.AUTO) }

    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val margins = if (isLandscape) landscapeMargins else portraitMargins

    LaunchedEffect(scriptId) {
        val s = settingsRepo.settings.first()
        gestureMap = s.gestures
        pinchEnabled = s.pinchResize
        countdownSec = s.countdownSec
        asrOverlayOn = s.asrOverlay
        followVoice = s.followVoice
        // Mutually exclusive modes; follow wins if both were somehow stored on.
        voiceCommands = s.voiceCommands && !s.followVoice
        portraitMargins = s.marginsPortrait
        landscapeMargins = s.marginsLandscape
        orientationPref = s.orientation
        val loadedScript = withContext(Dispatchers.IO) { repo.load(scriptId) }
        if (loadedScript != null) {
            val p = withContext(Dispatchers.Default) { MarkdownParser.parse(loadedScript.body) }
            wpm = p.overrides.wpm ?: s.wpm
            persistWpm = p.overrides.wpm == null
            fontSp = (s.fontSizeSp * (p.overrides.fontScale ?: 1f)).coerceIn(24f, 72f)
            persistFont = p.overrides.fontScale == null
            mirrorH = p.overrides.mirror ?: s.mirrorH
            mirrorV = s.mirrorV
            // Model choice: frontmatter `lang:` > stopword auto-detection of the
            // script text > configured default — installed models only. The
            // wrong model turns speech into unmatchable garbage, so the script
            // itself decides, not a leftover settings pick.
            val detected = LangDetect.detect(
                p.toDisplaySegments().filter { it.spoken }.joinToString(" ") { it.text },
            )
            val langTag = p.overrides.lang?.trim() ?: detected?.name?.lowercase()
            scriptLang = langTag
            val byLang = langTag?.let { tag ->
                // First *installed* model of the language; a still-installed
                // legacy model keeps working until its upgrade is downloaded.
                (AsrModels.ALL + AsrModels.LEGACY).firstOrNull {
                    it.lang.equals(tag, ignoreCase = true) && modelManager.isInstalled(it)
                }
            }
            preferredModel = (
                listOfNotNull(byLang, AsrModels.byId(s.asrModelId)) +
                    AsrModels.ALL + AsrModels.LEGACY
                ).firstOrNull { modelManager.isInstalled(it) }
            // The mic runs only while a voice mode is on. The transcript overlay
            // is display-only — it must never keep the engine (and the mic) hot.
            asrModel = if (s.followVoice || s.voiceCommands) preferredModel else null
            parsed = p
            script = loadedScript
        }
        ready = true
    }

    // Keep the screen awake and immersive for the whole session.
    DisposableEffect(activity) {
        val window = activity?.window
        var insets: WindowInsetsControllerCompat? = null
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insets = WindowInsetsControllerCompat(window, view).apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insets?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // An app-requested orientation beats the system rotation lock, so the
    // prompter can go landscape on a phone pinned to portrait (and back).
    // Prompter-only: the library and settings keep the device's own behaviour.
    DisposableEffect(activity, orientationPref) {
        activity?.requestedOrientation = when (orientationPref) {
            ScreenOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ScreenOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            ScreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    if (!ready) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val loaded = script
    val parsedScript = parsed
    if (loaded == null || parsedScript == null) {
        LaunchedEffect(Unit) { onExit() }
        return
    }

    val annotated = remember(parsedScript) { parsedScript.toAnnotatedString() }
    val wordCount = remember(parsedScript) { parsedScript.spokenWordCount.coerceAtLeast(1) }

    // Spoken-token index over the exact rendered text; the matcher's anchor
    // maps to a char offset, then to a line via the text layout.
    val scriptIndex = remember(parsedScript, asrModel) {
        ScriptIndex.build(
            parsedScript.toDisplaySegments().map { SpokenSegment(it.text, it.spoken) },
            MatchLang.from(asrModel?.lang ?: scriptLang),
        )
    }
    val matcher = remember(scriptIndex) { Matcher(scriptIndex) }
    var voiceRetakeAnchor by remember(matcher) { mutableStateOf<Int?>(null) }
    var voiceJumpVersion by remember { mutableIntStateOf(0) }
    var voiceJumpFlash by remember { mutableStateOf(false) }
    // Confirm a spoken page-back at once: recognition already costs about a
    // second, and an unconfirmed command gets said again, paging back twice.
    LaunchedEffect(voiceJumpVersion) {
        if (voiceJumpVersion == 0) return@LaunchedEffect
        voiceJumpFlash = true
        delay(VOICE_JUMP_FLASH_MS)
        voiceJumpFlash = false
    }
    // Voice commands ride the same ASR stream; language follows the script index.
    val commandDetector = remember(scriptIndex) { VoiceCommandDetector(scriptIndex.lang) }
    var followState by remember(matcher) { mutableStateOf(matcher.state) }
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // Follow diagnostics (SPEC §3.2 testability): while follow-voice runs,
    // every matcher input and output is traced to a file so "it stopped
    // following" replays deterministically in the JVM harness (FollowTrace).
    var tracer by remember { mutableStateOf<FollowTraceWriter?>(null) }
    val density = LocalDensity.current

    // Session stats (SPEC §5.2.4): fed from matcher state changes + play/pause.
    val statsBuilder = remember(matcher) {
        SessionStatsBuilder(
            totalTokens = scriptIndex.tokens.size,
            sectionStarts = parsedScript.sections().map {
                it.title to scriptIndex.firstTokenAtOrAfter(it.charStart)
            },
        )
    }
    var summaryStats by remember(matcher) { mutableStateOf<SessionStats?>(null) }

    fun publishState(state: FollowState) {
        followState = state
        statsBuilder.onState(state, System.currentTimeMillis())
    }

    val scrollState = rememberScrollState()
    // The hand always wins (SPEC §5.4): while a finger holds the text — or the
    // fling it launched is still running — the auto-scroll drive stays off.
    val userScroll = rememberUserScrollControl(scrollState)
    var playing by remember { mutableStateOf(false) }
    // Start-on-voice: session armed, waiting for the first matched words.
    var armed by remember { mutableStateOf(false) }
    var armedAnchor by remember { mutableIntStateOf(-1) }
    var countdown by remember { mutableIntStateOf(0) }
    var countdownJob by remember { mutableStateOf<Job?>(null) }
    var contentHeightPx by remember { mutableIntStateOf(0) }
    var viewportPx by remember { mutableIntStateOf(0) }

    // WPM → px/s once the text is laid out: px-per-word is total text height / word count.
    val pxPerSec by remember(wordCount) {
        derivedStateOf {
            if (contentHeightPx == 0) 0f else (wpm / 60f) * (contentHeightPx.toFloat() / wordCount)
        }
    }

    // A reader can only speak words they can see: the matcher is told what the
    // screen currently shows so it never matches — or jumps to — off-screen text.
    fun visibleTokenRange(): IntRange? {
        val layout = textLayout ?: return null
        if (viewportPx <= 0) return null
        val topSpacerPx = viewportPx * EYELINE_FRACTION
        // Half a line of slack per edge: partially clipped lines still count.
        val slack = with(density) { (fontSp * 1.4f).sp.toPx() } / 2f
        val top = (scrollState.value - topSpacerPx - slack).coerceAtLeast(0f)
        val bottom = scrollState.value - topSpacerPx + viewportPx + slack
        if (bottom <= 0f) return null
        val charLo = layout.getLineStart(layout.getLineForVerticalPosition(top))
        val charHi = layout.getLineEnd(layout.getLineForVerticalPosition(bottom))
        return scriptIndex.tokenRangeForChars(charLo, charHi)
    }

    // --- Voice: mic → streaming ASR → matcher + debug overlay ---
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { micGranted = it }
    LaunchedEffect(asrModel) {
        if (asrModel != null && !micGranted) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val engine = remember(asrModel, micGranted) {
        val m = asrModel
        if (m != null && micGranted) AsrEngine(modelManager.dirFor(m), m) else null
    }
    val followCommands = remember(scriptIndex, engine, followVoice) {
        FollowVoiceCommands(scriptIndex)
    }
    DisposableEffect(engine) {
        engine?.start()
        onDispose { engine?.stop() }
    }
    val asrState by (engine?.state ?: remember { MutableStateFlow(AsrState()) }).collectAsState()
    val followActive = followVoice && engine != null

    // One trace file per follow session; last few kept, shareable from Settings.
    DisposableEffect(followActive, matcher) {
        if (followActive) {
            val writer = FollowTraceWriter.start(
                dir = File(context.getExternalFilesDir(null), "traces"),
                header = FollowTrace.Event.Header(
                    version = 1,
                    app = BuildConfig.VERSION_NAME,
                    lang = scriptIndex.lang.name,
                    tokenCount = scriptIndex.tokens.size,
                    scriptId = scriptId,
                ),
            )
            tracer = writer
            onDispose {
                tracer = null
                writer.close()
            }
        } else {
            onDispose { }
        }
    }

    // ASR health into the trace. Without it a trace can only show *that* the
    // words stopped, never whether the mic dropped out or the decoder fell
    // behind its 100 ms budget — two different bugs with two different fixes.
    // Logged on every listening/error edge, on a meaningful decode-lag move,
    // and otherwise as a slow heartbeat so silence is distinguishable from a
    // dead engine.
    LaunchedEffect(tracer) {
        val tr = tracer ?: return@LaunchedEffect
        var lastListening: Boolean? = null
        var lastError: String? = null
        var lastDecodeMs = Int.MIN_VALUE
        var lastLogNanos = 0L
        snapshotFlow { asrState }.collect { s ->
            val now = System.nanoTime()
            val edge = s.listening != lastListening || s.error != lastError
            val lagMoved = abs(s.decodeMs - lastDecodeMs) >= DECODE_MS_TRACE_STEP
            if (edge || lagMoved || now - lastLogNanos > ASR_TRACE_HEARTBEAT_NANOS) {
                lastListening = s.listening
                lastError = s.error
                lastDecodeMs = s.decodeMs
                lastLogNanos = now
                tr.log(FollowTrace.Event.Asr(tr.elapsed(), s.listening, s.decodeMs, s.error))
            }
        }
    }

    LaunchedEffect(playing) {
        val now = System.currentTimeMillis()
        if (playing) statsBuilder.onPlay(now) else statsBuilder.onPause(now)
        tracer?.let { it.log(FollowTrace.Event.Play(it.elapsed(), playing)) }
    }

    fun adjustWpm(delta: Int) {
        wpm = (wpm + delta).coerceIn(40, 400)
        if (persistWpm) scope.launch { settingsRepo.setWpm(wpm) }
    }

    fun adjustFont(delta: Float) {
        fontSp = (fontSp + delta).coerceIn(24f, 72f)
    }

    fun setMargins(value: FrameMargins) {
        val clamped = FrameMargins(
            h = value.h.coerceIn(0f, FrameMargins.MAX),
            v = value.v.coerceIn(0f, FrameMargins.MAX),
        )
        if (isLandscape) landscapeMargins = clamped else portraitMargins = clamped
    }

    // Drag deltas are applied to the live value, not the one captured at the
    // last composition: several drag events can land within a single frame.
    fun nudgeMargins(dh: Float, dv: Float) {
        val current = if (isLandscape) landscapeMargins else portraitMargins
        setMargins(FrameMargins(current.h + dh, current.v + dv))
    }

    /** One press flips to the other orientation — whatever the rotation lock says. */
    fun flipOrientation() {
        val next =
            if (isLandscape) ScreenOrientation.PORTRAIT else ScreenOrientation.LANDSCAPE
        orientationPref = next
        scope.launch { settingsRepo.setOrientation(next) }
    }

    // Dragging a frame edge fires continuously; persist the result, not the drag.
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        snapshotFlow { portraitMargins }.drop(1).debounce(400).collect {
            settingsRepo.setMargins(landscape = false, value = it)
        }
    }
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        snapshotFlow { landscapeMargins }.drop(1).debounce(400).collect {
            settingsRepo.setMargins(landscape = true, value = it)
        }
    }

    // Rotating relayouts the script: hold the reader's place in the text instead
    // of wherever the old pixel offset happens to land in the new layout.
    var scrollFraction by remember { mutableFloatStateOf(0f) }
    var restoringScroll by remember { mutableStateOf(false) }
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value to scrollState.maxValue }.collect { (value, max) ->
            if (!restoringScroll && max > 0) scrollFraction = value.toFloat() / max
        }
    }
    LaunchedEffect(isLandscape) {
        val target = scrollFraction
        if (target <= 0f) return@LaunchedEffect
        restoringScroll = true
        // Let the new layout settle before mapping the fraction back to pixels.
        repeat(4) { withFrameNanos { } }
        scrollState.scrollTo((target * scrollState.maxValue).roundToInt())
        restoringScroll = false
    }

    fun startCountdown() {
        if (countdownSec > 0) {
            countdownJob = scope.launch {
                try {
                    for (i in countdownSec downTo 1) {
                        countdown = i
                        delay(1000L)
                    }
                    playing = true
                } finally {
                    countdown = 0
                }
            }
        } else {
            playing = true
        }
    }

    fun restart() {
        voiceRetakeAnchor = null
        if (followVoice) {
            matcher.reanchor(-1)
            tracer?.let { it.log(FollowTrace.Event.Reanchor(it.elapsed(), -1)) }
            publishState(matcher.state)
        }
        scope.launch { scrollState.scrollTo(0) }
    }

    fun togglePlay() {
        when {
            // The on-screen "Start now" buttons are out of reach on a rig, so a
            // press during the countdown (or while armed for start-on-voice)
            // starts immediately; cancelling is just start-then-pause.
            countdown > 0 -> {
                countdownJob?.cancel()
                countdown = 0
                playing = true
            }
            armed -> {
                armed = false
                playing = true
            }
            playing -> playing = false
            scrollState.maxValue > 0 && scrollState.value >= scrollState.maxValue -> restart()
            countdownSec == START_ON_VOICE ->
                if (followActive) {
                    armedAnchor = followState.anchor
                    armed = true
                } else {
                    playing = true // no voice engine: start immediately
                }
            else -> startCountdown()
        }
    }

    fun jumpPx(): Float =
        if (pxPerSec > 0f) pxPerSec * 5f else viewportPx * 0.3f

    // In follow mode the jump keys re-anchor the matcher by paragraph — the
    // phone is on the rig, out of reach, so retakes are remote-driven (SPEC
    // principle 2: never touch mid-take). The scroll then glides to the anchor.
    fun reanchorTo(anchor: Int) {
        voiceRetakeAnchor = null
        matcher.reanchor(anchor)
        tracer?.let { it.log(FollowTrace.Event.Reanchor(it.elapsed(), anchor)) }
        publishState(matcher.state)
    }

    // Wherever the reader puts the text is the new truth: anchor the matcher to
    // the line under the eyeline, so follow resumes from the screen — never from
    // the top, never from a stale position it would pull the text back to.
    fun reanchorToEyeline() {
        val layout = textLayout ?: return
        // In text coordinates the eyeline sits exactly at the scroll offset
        // (the top spacer height equals the eyeline offset — see the layout).
        val line = layout.getLineForVerticalPosition(scrollState.value.toFloat())
        reanchorTo(scriptIndex.firstTokenAtOrAfter(layout.getLineStart(line)))
    }

    suspend fun voicePageBack(pages: Int) {
        val layout = textLayout ?: return
        if (viewportPx <= 0 || userScroll.busy) return
        val target = (scrollState.value.toLong() - viewportPx.toLong() * pages)
            .coerceAtLeast(0).toInt()
        val line = layout.getLineForVerticalPosition(target.toFloat())
        // Anchor just before the first word to read. Stop coasting until speech
        // advances this anchor; the old utterance is removed by followCommands.
        reanchorTo(scriptIndex.firstTokenAtOrAfter(layout.getLineStart(line)) - 1)
        voiceRetakeAnchor = matcher.state.anchor
        if (armed) armedAnchor = matcher.state.anchor
        voiceJumpVersion++
        scrollState.scrollTo(target)
    }

    fun perform(action: PrompterAction) {
        when (action) {
            PrompterAction.NONE -> Unit
            PrompterAction.PLAY_PAUSE -> togglePlay()
            PrompterAction.RESTART -> restart()
            PrompterAction.JUMP_BACK ->
                if (followActive) reanchorTo(scriptIndex.paragraphBackAnchor(followState.anchor))
                else scope.launch { scrollState.scrollBy(-jumpPx()) }
            PrompterAction.JUMP_FORWARD ->
                if (followActive) reanchorTo(scriptIndex.paragraphForwardAnchor(followState.anchor))
                else scope.launch { scrollState.scrollBy(jumpPx()) }
            PrompterAction.SPEED_UP -> adjustWpm(10)
            PrompterAction.SPEED_DOWN -> adjustWpm(-10)
            PrompterAction.FONT_UP -> adjustFont(2f)
            PrompterAction.FONT_DOWN -> adjustFont(-2f)
            PrompterAction.FRAME -> frameEditing = !frameEditing
            PrompterAction.ROTATE -> flipOrientation()
        }
    }

    // Feed the matcher only while delivering (or armed, waiting for the first
    // words); speech during pause is skipped, so resuming never replays a backlog.
    // The command detector listens whenever its mode is on — "go back" must work
    // paused or playing (the user is always in control).
    LaunchedEffect(engine, matcher, followCommands) {
        var tracedVis: IntRange? = null
        var tracedAnchor = Int.MIN_VALUE
        var tracedMode: FollowMode? = null
        engine?.events?.collect { event ->
            // The visible range lets the detector ignore command-like wording
            // printed in the script itself while it is on screen.
            val routed = if (followVoice) {
                followCommands.onWords(
                    event.utterance,
                    event.words,
                    event is AsrEvent.Final,
                    visibleTokenRange(),
                )
            } else null
            if (routed != null && routed.pagesBack > 0 && summaryStats == null) {
                voicePageBack(routed.pagesBack)
            }
            val trackingWords = routed?.words ?: event.words
            if (followVoice && (playing || armed)) {
                val vis = visibleTokenRange()
                matcher.visibleTokens = vis
                tracer?.let { tr ->
                    val t = tr.elapsed()
                    if (vis != tracedVis) {
                        tracedVis = vis
                        tr.log(FollowTrace.Event.Vis(t, vis?.first ?: -1, vis?.last ?: -1))
                    }
                    tr.log(
                        FollowTrace.Event.Words(
                            t, event.utterance, event is AsrEvent.Final, trackingWords,
                        ),
                    )
                }
                val st = matcher.onWords(
                    event.utterance,
                    trackingWords,
                    final = event is AsrEvent.Final,
                )
                publishState(st)
                if (voiceRetakeAnchor != null && st.anchor != voiceRetakeAnchor) {
                    voiceRetakeAnchor = null
                }
                if (st.anchor != tracedAnchor || st.mode != tracedMode) {
                    tracedAnchor = st.anchor
                    tracedMode = st.mode
                    tracer?.let {
                        it.log(
                            FollowTrace.Event.State(
                                it.elapsed(), st.anchor, st.mode, st.tokensPerSec,
                            ),
                        )
                    }
                }
                if (armed && followState.anchor != armedAnchor) {
                    armed = false
                    playing = true
                }
            } else {
                matcher.skip(event.utterance, trackingWords)
                tracer?.let {
                    it.log(
                        FollowTrace.Event.Skip(it.elapsed(), event.utterance, trackingWords.size),
                    )
                }
            }
            // The end screen swallows keys and touch; voice commands too.
            if (voiceCommands && !followVoice && summaryStats == null) {
                for (cmd in commandDetector.onWords(event.utterance, event.words)) {
                    when (cmd) {
                        VoiceCommand.GO_BACK -> perform(PrompterAction.JUMP_BACK)
                    }
                }
            }
        }
    }

    // Follow-voice picks up from wherever the reader already is — never from
    // the top. Runs when the chip turns on, and again if the matcher is rebuilt
    // under it (e.g. a model change swaps the script index).
    LaunchedEffect(matcher, followVoice) {
        if (followVoice) reanchorToEyeline()
    }

    // Manual scroll is authoritative (SPEC §5.4): when the hand and its fling
    // settle, follow re-anchors to the eyeline instead of pulling the text back
    // to the stale anchor. While playing, the drive loop consumes the move
    // itself — it must re-anchor before its first tick; this watcher only
    // covers the paused case. Deliberately NOT watching isScrollInProgress:
    // the drive's own per-frame scrolls keep that flag flapping, which starves
    // this collector while playing — the bug that kept the elastic pull alive.
    LaunchedEffect(matcher) {
        snapshotFlow { userScroll.busy }.collect { busy ->
            if (busy || !userScroll.moved) return@collect
            userScroll.moved = false
            if (followVoice) reanchorToEyeline()
        }
    }

    // Leaving after a real take shows the end screen first (SPEC §5.2.4).
    fun requestExit() {
        val now = System.currentTimeMillis()
        if (summaryStats == null && followActive && statsBuilder.meaningful(now)) {
            playing = false
            armed = false
            summaryStats = statsBuilder.build(now)
        } else {
            onExit()
        }
    }
    BackHandler { requestExit() }

    DisposableEffect(Unit) {
        KeyCommandBus.active = true
        onDispose { KeyCommandBus.active = false }
    }
    LaunchedEffect(Unit) {
        KeyCommandBus.events.collect { slot ->
            // The end screen swallows touch; keys mirror that: any press is
            // "Back to script" instead of leaking into the prompter beneath.
            if (summaryStats != null) {
                summaryStats = null
            } else {
                perform(gestureMap[slot] ?: slot.default)
            }
        }
    }
    // Pinch-resize persists debounced, and only when not overridden by frontmatter.
    LaunchedEffect(persistFont) {
        if (!persistFont) return@LaunchedEffect
        snapshotFlow { fontSp }.drop(1).debounce(600).collect { settingsRepo.setFontSizeSp(it) }
    }

    // Scroll drive. Voice-follow is a velocity servo (ScrollDynamics): the
    // text coasts at the measured reading rate and anchor matches only *trim*
    // that rate — motion with corrections, never motion made of corrections.
    // A dead zone swallows word-level anchor noise (incl. provisional
    // fallbacks), and backward speed is hard-capped: freezing must never tug
    // the text back. Classic mode keeps the fixed WPM drive.
    LaunchedEffect(playing, followActive, voiceJumpVersion) {
        if (!playing) return@LaunchedEffect
        val dynamics = ScrollDynamics()
        var last = withFrameNanos { it }
        var lastAnchor = Int.MIN_VALUE
        var lastAdvance = last
        var lastSnap = 0L
        while (playing) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
            last = now
            // The hand always wins: while the reader holds the text or its
            // fling is still running, the drive waits — it must never cancel a
            // fling or drag the text back.
            if (userScroll.busy || scrollState.isScrollInProgress) {
                dynamics.reset()
                continue
            }
            // The drive consumes the manual move itself, before its first tick
            // after release: re-anchoring must win the race against the drive,
            // or even one frame of stale-anchor pull yanks the text back.
            if (userScroll.moved) {
                userScroll.moved = false
                if (followActive) reanchorToEyeline()
                dynamics.reset()
                continue
            }
            if (followActive) {
                if (voiceRetakeAnchor != null) {
                    dynamics.reset()
                    continue
                }
                val layout = textLayout
                val st = followState
                if (layout == null || st.anchor < 0 || viewportPx <= 0) {
                    dynamics.reset()
                    continue
                }
                if (st.anchor != lastAnchor) {
                    lastAnchor = st.anchor
                    lastAdvance = now
                }
                val offset = (st.anchorCharEnd - 1).coerceIn(0, layout.layoutInput.text.length)
                val line = layout.getLineForOffset(offset)
                val lineTop = layout.getLineTop(line)
                val nextTop =
                    if (line + 1 < layout.lineCount) layout.getLineTop(line + 1)
                    else layout.getLineBottom(line)
                // Interpolate inside the line so the target moves word by
                // word instead of hopping a line-height at a time.
                val lineStart = layout.getLineStart(line)
                val lineEnd = layout.getLineEnd(line)
                val frac =
                    if (lineEnd > lineStart) {
                        (offset - lineStart).toFloat() / (lineEnd - lineStart)
                    } else 0f
                val lineHeightPx = with(density) { (fontSp * 1.4f).sp.toPx() }
                val pxPerToken =
                    if (scriptIndex.tokens.isNotEmpty() && contentHeightPx > 0) {
                        contentHeightPx.toFloat() / scriptIndex.tokens.size
                    } else 0f
                // Coast while the reader is actively advancing; stop the glide
                // on silence, improv or lost — unmatched speech never scrolls.
                val speaking = (now - lastAdvance) < COAST_AFTER_MATCH_SEC * 1_000_000_000L
                val feed =
                    if (st.mode == FollowMode.FOLLOWING && speaking) {
                        st.tokensPerSec * pxPerToken
                    } else 0f
                val target = (lineTop + frac * (nextTop - lineTop))
                    .coerceIn(0f, scrollState.maxValue.toFloat())
                val delta = dynamics.step(
                    dt = dt,
                    current = scrollState.value.toFloat(),
                    target = target,
                    feedForwardPxPerSec = feed,
                    deadbandPx = lineHeightPx * DEADBAND_LINES,
                    maxForwardPxPerSec = viewportPx * MAX_CATCHUP_VIEWPORTS_PER_SEC,
                    maxBackPxPerSec = lineHeightPx * MAX_EASE_BACK_LINES_PER_SEC,
                )
                if (delta != 0f) scrollState.scrollBy(delta)
                if (now - lastSnap > 1_000_000_000L) {
                    lastSnap = now
                    tracer?.let {
                        it.log(
                            FollowTrace.Event.Snap(
                                it.elapsed(),
                                scrollState.value,
                                scrollState.maxValue,
                                target,
                                dynamics.velocity,
                            ),
                        )
                    }
                }
            } else {
                if (pxPerSec > 0f) scrollState.scrollBy(dt * pxPerSec)
                if (scrollState.value >= scrollState.maxValue) playing = false
            }
        }
    }

    val doubleTapAction = gestureMap[GestureSlot.DOUBLE_TAP] ?: PrompterAction.NONE
    val longPressAction = gestureMap[GestureSlot.LONG_PRESS] ?: PrompterAction.NONE

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .userScrollControl(userScroll)
            .pointerInput(doubleTapAction, longPressAction, frameEditing) {
                // While framing, taps belong to the frame editor: a stray tap
                // must not start the take.
                if (frameEditing) return@pointerInput
                detectTapGestures(
                    onTap = { togglePlay() },
                    onDoubleTap =
                        if (doubleTapAction != PrompterAction.NONE) {
                            { perform(doubleTapAction) }
                        } else null,
                    onLongPress =
                        if (longPressAction != PrompterAction.NONE) {
                            { perform(longPressAction) }
                        } else null,
                )
            }
            .pointerInput(pinchEnabled) {
                if (!pinchEnabled) return@pointerInput
                // Pinch-only: never consumes single-finger events, so manual
                // scrolling keeps working (detectTransformGestures would eat it).
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                        if (event.changes.size >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                fontSp = (fontSp * zoom).coerceIn(24f, 72f)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            },
    ) {
        // The reading frame (SPEC §5.1): a phone is often bigger than the
        // prompter glass it sits behind, so everything the reader must see —
        // text, eyeline, countdown, status — is inset into the visible area.
        val insetX = maxWidth * margins.h
        val insetY = maxHeight * margins.v
        val frameHeight = maxHeight - insetY * 2
        val topSpacer = frameHeight * EYELINE_FRACTION
        val bottomSpacer = frameHeight * (1f - EYELINE_FRACTION)

        // Remaining time from measured live rate when following, else from WPM.
        val remainingSec = when {
            followActive && followState.anchor >= 0 ->
                ((scriptIndex.tokens.size - 1 - followState.anchor) /
                    followState.tokensPerSec.coerceAtLeast(0.3f)).roundToInt()
            pxPerSec > 0f -> ((scrollState.maxValue - scrollState.value) / pxPerSec).roundToInt()
            else -> 0
        }

        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = insetX, vertical = insetY)
                .onSizeChanged { viewportPx = it.height }
                // Scroll gestures live HERE, outside the mirror layer, in plain
                // screen space. A graphicsLayer transforms its children's
                // pointer input too, and a negative scaleY made flicks fight
                // the reader (device-confirmed: vertical mirror broke manual
                // scrolling; horizontal never touches y). The mirror below now
                // only draws. Normal feel is reverseDirection = true (what
                // verticalScroll uses); a vertical flip inverts what "down"
                // means on screen, so the boolean flips with it.
                .scrollable(
                    state = scrollState,
                    orientation = Orientation.Vertical,
                    reverseDirection = !mirrorV,
                ),
        ) {
            // Everything a physical rig would mirror lives inside this layer.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = if (mirrorH) -1f else 1f
                        scaleY = if (mirrorV) -1f else 1f
                    },
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        // Layout, clipping and scroll offset only — gestures are
                        // disabled here and handled outside the mirror (above).
                        .verticalScroll(scrollState, enabled = false),
                ) {
                    Spacer(Modifier.height(topSpacer))
                    Text(
                        text = annotated,
                        color = Color.White,
                        fontSize = fontSp.sp,
                        lineHeight = (fontSp * 1.4f).sp,
                        fontWeight = FontWeight.Medium,
                        onTextLayout = { textLayout = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 28.dp)
                            .onSizeChanged { contentHeightPx = it.height },
                    )
                    Spacer(Modifier.height(bottomSpacer))
                }

                // Follow-state feedback (SPEC §5.1): the eyeline marker dims while
                // frozen on improv, pulses when lost, and comes back on rejoin.
                val frozen = followActive && followState.mode == FollowMode.FROZEN
                val lost = followActive && followState.mode == FollowMode.LOST
                val markerAlpha = if (lost) {
                    rememberInfiniteTransition(label = "lost").animateFloat(
                        initialValue = 0.25f,
                        targetValue = 0.95f,
                        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
                        label = "lostPulse",
                    ).value
                } else if (frozen) 0.5f else 0.9f
                Canvas(Modifier.fillMaxSize()) {
                    val y = size.height * EYELINE_FRACTION
                    drawLine(
                        color = Color.White.copy(alpha = 0.06f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 2.dp.toPx(),
                    )
                    val h = 14.dp.toPx()
                    val w = 10.dp.toPx()
                    val marker = Path().apply {
                        moveTo(0f, y - h / 2)
                        lineTo(w, y)
                        lineTo(0f, y + h / 2)
                        close()
                    }
                    drawPath(
                        marker,
                        color = if (frozen) Color(0xFF8F8F8F) else Color(0xFFFFC24B),
                        alpha = markerAlpha,
                    )
                }
            }

            val progress = if (scrollState.maxValue > 0) {
                scrollState.value.toFloat() / scrollState.maxValue
            } else 0f
            if (progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .fillMaxHeight(progress)
                        .width(3.dp)
                        .background(Color.White.copy(alpha = 0.25f)),
                )
            }

            if (playing) {
                Text(
                    formatTime(remainingSec),
                    color = Color.White.copy(alpha = 0.35f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                )
            }

            if (engine != null) {
                // Always name the active language — a wrong-model session must be
                // visible at a glance, not a silent failure to follow.
                val lang = asrModel?.lang ?: "?"
                // Diagnostic: an anchor outside the visible window means every
                // search is clamped to a range the reader isn't in — the exact
                // "playhead stopped following" trap. Make it visible live.
                val offScreen = followActive && followState.anchor >= 0 &&
                    matcher.visibleTokens?.let { followState.anchor !in it } == true
                val offMark = if (offScreen) " · ⚠ anchor off-screen" else ""
                val statusText = when {
                    asrState.error != null -> asrState.error
                    followActive && voiceRetakeAnchor != null ->
                        if (playing || armed) "read to continue · $lang" else "paused · $lang"
                    !asrState.listening -> "starting…"
                    followActive && followState.mode == FollowMode.LOST ->
                        "lost · read any line, or use the remote$offMark"
                    followActive && followState.mode == FollowMode.FROZEN ->
                        "improv · holding · $lang$offMark"
                    followActive && followState.anchor >= 0 ->
                        "following · $lang · ${followState.anchor + 1}/" +
                            "${scriptIndex.tokens.size} · ${asrState.decodeMs} ms$offMark"
                    followActive -> "waiting for your voice · $lang"
                    voiceCommands ->
                        "say “${commandDetector.hint}” to jump back · $lang"
                    else -> "listening · $lang · ${asrState.decodeMs} ms"
                }
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, bottom = 12.dp, end = 80.dp),
                ) {
                    Text(
                        "● $statusText",
                        color = if (asrState.listening) {
                            Color(0xFF7CCB7C).copy(alpha = 0.7f)
                        } else {
                            Color.White.copy(alpha = 0.4f)
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                    val line = asrState.partial.ifBlank { asrState.lastFinal }
                    if (followActive) {
                        Text(
                            "“${followCommands.hint}” · back one screen",
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (asrOverlayOn && line.isNotBlank()) {
                        Text(
                            line.takeLast(120),
                            color = Color.White.copy(
                                alpha = if (asrState.partial.isNotBlank()) 0.6f else 0.35f,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = voiceJumpFlash,
                enter = fadeIn(tween(80)),
                exit = fadeOut(tween(400)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp),
            ) {
                Text(
                    "↑ ${followCommands.hint}",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .background(Color(0xCC2E7D32), RoundedCornerShape(50))
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            if (countdown > 0) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "$countdown",
                        color = Color.White,
                        fontSize = 96.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    TextButton(onClick = {
                        countdownJob?.cancel()
                        countdown = 0
                        playing = true
                    }) {
                        Text("Start now", color = Color.White.copy(alpha = 0.7f))
                    }
                }
            }

            if (armed) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "Speak to start",
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    TextButton(onClick = {
                        armed = false
                        playing = true
                    }) {
                        Text("Start now", color = Color.White.copy(alpha = 0.7f))
                    }
                }
            }
        }

        if (frameEditing) {
            FrameEditor(
                margins = margins,
                insetX = insetX,
                insetY = insetY,
                widthPx = with(density) { maxWidth.toPx() },
                heightPx = with(density) { maxHeight.toPx() },
                onNudge = ::nudgeMargins,
                onReset = { setMargins(FrameMargins()) },
                onDone = { frameEditing = false },
            )
        }

        // Landscape docks the controls as a side sheet: a bottom sheet plus two
        // sliders would swallow the whole short edge, leaving almost nowhere to
        // tap to resume (and mis-taps landed on the speed slider).
        AnimatedVisibility(
            visible = !playing && countdown == 0 && !armed && !frameEditing,
            modifier = Modifier.align(
                if (isLandscape) Alignment.CenterEnd else Alignment.BottomCenter,
            ),
            enter = fadeIn() +
                if (isLandscape) slideInHorizontally { it / 2 } else slideInVertically { it / 2 },
            exit = fadeOut() +
                if (isLandscape) slideOutHorizontally { it / 2 } else slideOutVertically { it / 2 },
        ) {
            ControlsPanel(
                title = loaded.title,
                subtitle = "≈ ${formatTime(remainingSec)} left · $wordCount words",
                wpm = wpm,
                onWpmChange = { wpm = it },
                onWpmChangeFinished = {
                    if (persistWpm) scope.launch { settingsRepo.setWpm(wpm) }
                },
                fontSp = fontSp,
                onFontChange = { fontSp = it },
                mirrorH = mirrorH,
                onMirrorH = {
                    mirrorH = it
                    scope.launch { settingsRepo.setMirrorH(it) }
                },
                mirrorV = mirrorV,
                onMirrorV = {
                    mirrorV = it
                    scope.launch { settingsRepo.setMirrorV(it) }
                },
                landscape = isLandscape,
                onFlipOrientation = ::flipOrientation,
                margins = margins,
                onEditFrame = { frameEditing = true },
                countdownSec = countdownSec,
                onCountdownCycle = {
                    val idx = COUNTDOWN_CYCLE.indexOf(countdownSec)
                    countdownSec = COUNTDOWN_CYCLE[(idx + 1).mod(COUNTDOWN_CYCLE.size)]
                    scope.launch { settingsRepo.setCountdownSec(countdownSec) }
                },
                followAvailable = preferredModel != null,
                followVoice = followVoice,
                onFollowVoice = { on ->
                    followVoice = on
                    if (on) {
                        // Standalone command mode stays separate; follow has its own phrases.
                        if (voiceCommands) {
                            voiceCommands = false
                            scope.launch { settingsRepo.setVoiceCommands(false) }
                        }
                        asrModel = preferredModel
                    } else if (!voiceCommands) {
                        // Untap = the mic stops. No mode, no engine, no exceptions.
                        asrModel = null
                    }
                    scope.launch { settingsRepo.setFollowVoice(on) }
                },
                voiceCommands = voiceCommands,
                voiceCommandPhrase = commandDetector.hint,
                followCommandPhrase = followCommands.hint,
                onVoiceCommands = { on ->
                    voiceCommands = on
                    if (on) {
                        if (followVoice) {
                            followVoice = false
                            scope.launch { settingsRepo.setFollowVoice(false) }
                        }
                        asrModel = preferredModel
                    } else if (!followVoice) {
                        asrModel = null
                    }
                    scope.launch { settingsRepo.setVoiceCommands(on) }
                },
                onPlay = ::togglePlay,
                onRestart = ::restart,
                onExit = ::requestExit,
            )
        }

        summaryStats?.let { stats ->
            SessionSummary(
                stats = stats,
                onDismiss = { summaryStats = null },
                onExit = onExit,
            )
        }
    }
}

@Composable
private fun ControlsPanel(
    title: String,
    subtitle: String,
    wpm: Int,
    onWpmChange: (Int) -> Unit,
    onWpmChangeFinished: () -> Unit,
    fontSp: Float,
    onFontChange: (Float) -> Unit,
    mirrorH: Boolean,
    onMirrorH: (Boolean) -> Unit,
    mirrorV: Boolean,
    onMirrorV: (Boolean) -> Unit,
    landscape: Boolean,
    onFlipOrientation: () -> Unit,
    margins: FrameMargins,
    onEditFrame: () -> Unit,
    countdownSec: Int,
    onCountdownCycle: () -> Unit,
    followAvailable: Boolean,
    followVoice: Boolean,
    onFollowVoice: (Boolean) -> Unit,
    voiceCommands: Boolean,
    voiceCommandPhrase: String,
    followCommandPhrase: String,
    onVoiceCommands: (Boolean) -> Unit,
    onPlay: () -> Unit,
    onRestart: () -> Unit,
    onExit: () -> Unit,
) {
    Surface(
        color = Color(0xF2161616),
        contentColor = Color.White,
        shape = if (landscape) {
            RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)
        } else {
            RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        },
        modifier = (if (landscape) {
            Modifier.width(340.dp).fillMaxHeight()
        } else {
            Modifier.fillMaxWidth()
        })
            // Swallow taps so touching the panel doesn't start playback.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        // The box centres the sheet's content in the landscape column; the
        // scroll keeps every control reachable on screens too short for it.
        Box(
            if (landscape) Modifier.fillMaxHeight() else Modifier,
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .navigationBarsPadding(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                    IconButton(onClick = onRestart) {
                        Icon(Icons.Default.Refresh, contentDescription = "Restart")
                    }
                    FilledIconButton(onClick = onPlay, modifier = Modifier.size(56.dp)) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    IconButton(onClick = onExit) {
                        Icon(Icons.Default.Close, contentDescription = "Exit")
                    }
                }
                // Label and value share the slider's row: half the vertical cost
                // of the old stacked label + slider blocks.
                LabeledSlider(
                    label = "Speed",
                    value = "$wpm wpm",
                    sliderValue = wpm.toFloat(),
                    range = 40f..400f,
                    onChange = { onWpmChange(it.roundToInt()) },
                    onChangeFinished = onWpmChangeFinished,
                )
                LabeledSlider(
                    label = "Size",
                    value = "${fontSp.roundToInt()} sp",
                    sliderValue = fontSp,
                    range = 24f..72f,
                    onChange = onFontChange,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (followAvailable) {
                        FilterChip(
                            selected = followVoice,
                            onClick = { onFollowVoice(!followVoice) },
                            label = { Text("Follow voice") },
                        )
                        // Standalone single-word commands for classic scrolling.
                        FilterChip(
                            selected = voiceCommands,
                            onClick = { onVoiceCommands(!voiceCommands) },
                            label = { Text("“$voiceCommandPhrase” by voice") },
                        )
                    }
                    // One press flips the screen — no rotation-lock detour.
                    FilterChip(
                        selected = false,
                        onClick = onFlipOrientation,
                        label = { Text(if (landscape) "⟳ Portrait" else "⟳ Landscape") },
                    )
                    FilterChip(
                        selected = margins.isSet,
                        onClick = onEditFrame,
                        label = {
                            Text(
                                if (margins.isSet) {
                                    "Frame ${(margins.h * 100).roundToInt()}" +
                                        " / ${(margins.v * 100).roundToInt()}%"
                                } else "Frame"
                            )
                        },
                    )
                    FilterChip(
                        selected = mirrorH,
                        onClick = { onMirrorH(!mirrorH) },
                        label = { Text("Mirror ↔") },
                    )
                    FilterChip(
                        selected = mirrorV,
                        onClick = { onMirrorV(!mirrorV) },
                        label = { Text("Mirror ↕") },
                    )
                    FilterChip(
                        selected = countdownSec != 0,
                        onClick = onCountdownCycle,
                        label = {
                            Text(
                                when (countdownSec) {
                                    0 -> "Countdown off"
                                    START_ON_VOICE -> "Start on voice"
                                    else -> "Countdown $countdownSec s"
                                },
                            )
                        },
                    )
                }
                if (followVoice) {
                    Text(
                        "Say “$followCommandPhrase” to go back one screen, then keep reading.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: String,
    sliderValue: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onChangeFinished: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(44.dp),
        )
        Slider(
            value = sliderValue,
            onValueChange = onChange,
            onValueChangeFinished = onChangeFinished,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.width(56.dp),
        )
    }
}

/**
 * Frame adjuster: drag any edge until the text sits inside the prompter glass.
 * Margins are symmetric — the phone is centred in the hood — so one edge moves
 * both sides, and the area that will be cropped away is dimmed while dragging.
 */
@Composable
private fun BoxScope.FrameEditor(
    margins: FrameMargins,
    insetX: Dp,
    insetY: Dp,
    widthPx: Float,
    heightPx: Float,
    onNudge: (dh: Float, dv: Float) -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
) {
    val accent = Color(0xFFFFC24B)
    val grab = 48.dp

    Canvas(Modifier.fillMaxSize()) {
        val l = size.width * margins.h
        val t = size.height * margins.v
        val scrim = Color.Black.copy(alpha = 0.55f)
        drawRect(scrim, size = Size(size.width, t))
        drawRect(scrim, topLeft = Offset(0f, size.height - t), size = Size(size.width, t))
        drawRect(scrim, topLeft = Offset(0f, t), size = Size(l, size.height - 2 * t))
        drawRect(
            scrim,
            topLeft = Offset(size.width - l, t),
            size = Size(l, size.height - 2 * t),
        )
        drawRect(
            color = accent.copy(alpha = 0.9f),
            topLeft = Offset(l, t),
            size = Size(size.width - 2 * l, size.height - 2 * t),
            style = Stroke(
                width = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(14.dp.toPx(), 10.dp.toPx()),
                ),
            ),
        )
    }

    // Handles hug the inside of their edge, so they stay on-screen and on the
    // line at any margin — including zero.
    Box(
        Modifier
            .align(Alignment.CenterStart)
            .offset(x = insetX)
            .size(width = grab, height = 160.dp)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { onNudge(it / widthPx, 0f) },
            ),
        contentAlignment = Alignment.CenterStart,
    ) { Grip(accent, tall = true) }
    Box(
        Modifier
            .align(Alignment.CenterEnd)
            .offset(x = -insetX)
            .size(width = grab, height = 160.dp)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { onNudge(-it / widthPx, 0f) },
            ),
        contentAlignment = Alignment.CenterEnd,
    ) { Grip(accent, tall = true) }
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .offset(y = insetY)
            .size(width = 160.dp, height = grab)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { onNudge(0f, it / heightPx) },
            ),
        contentAlignment = Alignment.TopCenter,
    ) { Grip(accent, tall = false) }
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .offset(y = -insetY)
            .size(width = 160.dp, height = grab)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { onNudge(0f, -it / heightPx) },
            ),
        contentAlignment = Alignment.BottomCenter,
    ) { Grip(accent, tall = false) }

    Surface(
        color = Color(0xF2161616),
        contentColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .align(Alignment.TopStart)
            .statusBarsPadding()
            .padding(12.dp),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp)) {
            Text("Drag an edge to fit the glass", style = MaterialTheme.typography.labelLarge)
            Text(
                "Margins ${(margins.h * 100).roundToInt()} % side · " +
                    "${(margins.v * 100).roundToInt()} % top",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
            )
            Row {
                TextButton(onClick = onReset) { Text("Reset") }
                TextButton(onClick = onDone) { Text("Done") }
            }
        }
    }
}

@Composable
private fun Grip(color: Color, tall: Boolean) {
    Box(
        Modifier
            .then(
                if (tall) Modifier.width(6.dp).height(110.dp)
                else Modifier.height(6.dp).width(110.dp),
            )
            .background(color, RoundedCornerShape(3.dp)),
    )
}

private fun formatTime(totalSec: Int): String {
    val s = totalSec.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
