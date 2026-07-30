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
import androidx.compose.animation.slideInVertically
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import dev.metzook.teleprompter.KeyCommandBus
import dev.metzook.teleprompter.asr.AsrEngine
import dev.metzook.teleprompter.asr.AsrEvent
import dev.metzook.teleprompter.asr.AsrModel
import dev.metzook.teleprompter.asr.AsrModels
import dev.metzook.teleprompter.asr.AsrState
import dev.metzook.teleprompter.asr.ModelManager
import dev.metzook.teleprompter.follow.FollowMode
import dev.metzook.teleprompter.follow.LangDetect
import dev.metzook.teleprompter.follow.MatchLang
import dev.metzook.teleprompter.follow.FollowState
import dev.metzook.teleprompter.follow.Matcher
import dev.metzook.teleprompter.follow.ScriptIndex
import dev.metzook.teleprompter.follow.SessionStats
import dev.metzook.teleprompter.follow.SessionStatsBuilder
import dev.metzook.teleprompter.follow.SpokenSegment
import dev.metzook.teleprompter.follow.VoiceCommand
import dev.metzook.teleprompter.follow.VoiceCommandDetector
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
import kotlin.math.exp
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
 * Follow-mode scroll feel: the target leads the anchor by [FOLLOW_LEAD_SEC]
 * of measured rate to compensate recognizer confirmation delay, and the
 * spring catches up briskly but eases back gently (SPEC §1: glide, never jump).
 */
private const val FOLLOW_LEAD_SEC = 0.45f
private const val CATCH_UP_TAU = 0.20f
private const val EASE_BACK_TAU = 0.50f

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
                AsrModels.ALL.firstOrNull { it.lang.equals(tag, ignoreCase = true) }
            }
            preferredModel = (listOfNotNull(byLang, AsrModels.byId(s.asrModelId)) + AsrModels.ALL)
                .firstOrNull { modelManager.isInstalled(it) }
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
    // Voice commands ride the same ASR stream; language follows the script index.
    val commandDetector = remember(scriptIndex) { VoiceCommandDetector(scriptIndex.lang) }
    var followState by remember(matcher) { mutableStateOf(matcher.state) }
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
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
    DisposableEffect(engine) {
        engine?.start()
        onDispose { engine?.stop() }
    }
    val asrState by (engine?.state ?: remember { MutableStateFlow(AsrState()) }).collectAsState()
    val followActive = followVoice && engine != null

    LaunchedEffect(playing) {
        val now = System.currentTimeMillis()
        if (playing) statsBuilder.onPlay(now) else statsBuilder.onPause(now)
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
        if (followVoice) {
            matcher.reanchor(-1)
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
        matcher.reanchor(anchor)
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
    LaunchedEffect(engine, matcher) {
        engine?.events?.collect { event ->
            if (followVoice && (playing || armed)) {
                matcher.visibleTokens = visibleTokenRange()
                publishState(
                    matcher.onWords(
                        event.utterance,
                        event.words,
                        final = event is AsrEvent.Final,
                    ),
                )
                if (armed && followState.anchor != armedAnchor) {
                    armed = false
                    playing = true
                }
            } else {
                matcher.skip(event.utterance, event.words)
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

    // Scroll drive: voice-follow moves toward the anchor line (critically
    // damped, plus rate feed-forward so motion glides between matches);
    // classic mode keeps the fixed WPM drive. SPEC §3.1/§5.1: glide, never jump.
    LaunchedEffect(playing, followActive) {
        if (!playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (playing) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
            last = now
            // The hand always wins: while the reader holds the text or its
            // fling is still running, the drive waits — it must never cancel a
            // fling or drag the text back.
            if (userScroll.busy || scrollState.isScrollInProgress) continue
            // The drive consumes the manual move itself, before its first tick
            // after release: re-anchoring must win the race against the spring,
            // or even one frame of stale-anchor pull yanks the text back.
            if (userScroll.moved) {
                userScroll.moved = false
                if (followActive) reanchorToEyeline()
                continue
            }
            if (followActive) {
                val layout = textLayout
                val st = followState
                if (layout != null && st.anchor >= 0) {
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
                    val following = st.mode == FollowMode.FOLLOWING
                    val lineHeightPx = with(density) { (fontSp * 1.4f).sp.toPx() }
                    val pxPerToken =
                        if (scriptIndex.tokens.isNotEmpty() && contentHeightPx > 0) {
                            contentHeightPx.toFloat() / scriptIndex.tokens.size
                        } else 0f
                    val lead =
                        if (following) {
                            (st.tokensPerSec * pxPerToken * FOLLOW_LEAD_SEC)
                                .coerceAtMost(lineHeightPx)
                        } else 0f
                    val target = (lineTop + frac * (nextTop - lineTop) + lead)
                        .coerceIn(0f, scrollState.maxValue.toFloat())
                    val value = scrollState.value.toFloat()
                    val err = target - value
                    val tau = if (err >= 0f) CATCH_UP_TAU else EASE_BACK_TAU
                    scrollState.scrollBy(err * (1f - exp(-dt / tau)))
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
                val statusText = when {
                    asrState.error != null -> asrState.error
                    !asrState.listening -> "starting…"
                    followActive && followState.mode == FollowMode.LOST ->
                        "lost · read any line, or use the remote"
                    followActive && followState.mode == FollowMode.FROZEN ->
                        "improv · holding · $lang"
                    followActive && followState.anchor >= 0 ->
                        "following · $lang · ${followState.anchor + 1}/" +
                            "${scriptIndex.tokens.size} · ${asrState.decodeMs} ms"
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

        AnimatedVisibility(
            visible = !playing && countdown == 0 && !armed && !frameEditing,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            ControlsPanel(
                title = loaded.title,
                subtitle = "≈ ${formatTime(remainingSec)} left · $wordCount words · $wpm wpm",
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
                        // Exclusive with command mode: one interpretation of speech.
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
    onVoiceCommands: (Boolean) -> Unit,
    onPlay: () -> Unit,
    onRestart: () -> Unit,
    onExit: () -> Unit,
) {
    Surface(
        color = Color(0xF2161616),
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = Modifier
            .fillMaxWidth()
            // Swallow taps so touching the panel doesn't start playback.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp, vertical = 12.dp)
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
            Text("Speed — $wpm wpm", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = wpm.toFloat(),
                onValueChange = { onWpmChange(it.roundToInt()) },
                onValueChangeFinished = onWpmChangeFinished,
                valueRange = 40f..400f,
            )
            Text(
                "Text size — ${fontSp.roundToInt()} sp (pinch the text to adjust)",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = fontSp,
                onValueChange = onFontChange,
                valueRange = 24f..72f,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (followAvailable) {
                    FilterChip(
                        selected = followVoice,
                        onClick = { onFollowVoice(!followVoice) },
                        label = { Text("Follow voice") },
                    )
                    // "Go back" by voice: same pipeline, exclusive with follow.
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
        }
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
