package com.example.aiagenttestapp

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.hilt.navigation.compose.hiltViewModel
import javax.inject.Inject
import com.example.aiagenttestapp.data.SettingsStore
import com.example.aiagent.engine.core.EngineRegistry
import com.example.aiagenttestapp.data.HuggingFaceAuth
import com.example.aiagenttestapp.data.ModelDirectory
import com.example.aiagenttestapp.data.ModelRepository
import com.example.aiagenttestapp.data.audiomodels.AudioModelRepository
import com.example.aiagenttestapp.stt.SpeechModelRepository
import dagger.hilt.android.AndroidEntryPoint
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.aiagenttestapp.data.audit.AuditMode
import com.example.aiagenttestapp.functions.AppNavigation
import com.example.aiagenttestapp.ui.audit.AuditIntent
import com.example.aiagenttestapp.ui.audit.AuditReportIntent
import com.example.aiagenttestapp.ui.audit.AuditReportScreen
import com.example.aiagenttestapp.ui.audit.AuditReportViewModel
import com.example.aiagenttestapp.ui.audit.AuditScreen
import com.example.aiagenttestapp.ui.audit.AuditViewModel
import com.example.aiagenttestapp.ui.catalog.CatalogScreen
import com.example.aiagenttestapp.ui.catalog.CatalogViewModel
import com.example.aiagenttestapp.ui.catalog.MnnMarketViewModel
import com.example.aiagenttestapp.ui.chat.ChatEffect
import com.example.aiagenttestapp.ui.chat.ChatIntent
import com.example.aiagenttestapp.ui.chat.ChatScreen
import com.example.aiagenttestapp.ui.chat.ChatViewModel
import com.example.aiagenttestapp.ui.history.HistoryScreen
import com.example.aiagenttestapp.ui.history.HistoryViewModel
import com.example.aiagenttestapp.ui.hub.HubIntent
import com.example.aiagenttestapp.ui.hub.HubScreen
import com.example.aiagenttestapp.ui.hub.HubViewModel
import com.example.aiagenttestapp.ui.notes.NotesScreen
import com.example.aiagenttestapp.ui.notes.NotesViewModel
import com.example.aiagenttestapp.ui.notes.RecordEffect
import com.example.aiagenttestapp.ui.notes.RecordIntent
import com.example.aiagenttestapp.ui.notes.RecordScreen
import com.example.aiagenttestapp.ui.notes.RecordViewModel
import com.example.aiagenttestapp.ui.settings.SettingsScreen
import com.example.aiagenttestapp.ui.speakers.SpeakersScreen
import com.example.aiagenttestapp.ui.speakers.SpeakersViewModel
import com.example.aiagenttestapp.ui.splash.SplashScreen
import com.example.aiagenttestapp.ui.splash.SplashViewModel
import com.example.aiagenttestapp.ui.theme.AIAgentTestAppTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // The nav host is a composable, so it cannot be injected; the Activity owns these and passes
    // them down. ViewModels get their own dependencies through hiltViewModel().
    @Inject lateinit var settingsStore: SettingsStore
    @Inject lateinit var engines: EngineRegistry
    @Inject lateinit var huggingFaceAuth: HuggingFaceAuth
    @Inject lateinit var models: ModelDirectory
    @Inject lateinit var modelRepository: ModelRepository
    @Inject lateinit var speechModels: SpeechModelRepository
    @Inject lateinit var audioModels: AudioModelRepository

    /** Doc id from a tapped "Audit complete" notification; consumed by the nav host once past splash. */
    private val deepLinkAuditDocId = androidx.compose.runtime.mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readAuditDeepLink(intent)


        setContent {
            AIAgentTestAppTheme {
                AppNavHost(
                    settingsStore = settingsStore,
                    engines = engines,
                    huggingFaceAuth = huggingFaceAuth,
                    models = models,
                    modelRepository = modelRepository,
                    speechModels = speechModels,
                    audioModels = audioModels,
                    deepLinkAuditDocId = deepLinkAuditDocId.value,
                    onDeepLinkConsumed = { deepLinkAuditDocId.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readAuditDeepLink(intent)
    }

    private fun readAuditDeepLink(intent: android.content.Intent?) {
        val id = intent?.getLongExtra(EXTRA_AUDIT_DOC_ID, -1L) ?: -1L
        if (id >= 0) deepLinkAuditDocId.value = id
    }

    companion object {
        const val EXTRA_AUDIT_DOC_ID = "audit_doc_id"
    }
}

private object Routes {
    const val SPLASH = "splash"
    const val CATALOG = "catalog"
    const val SETTINGS = "settings"
    const val HUB = "hub"
    const val NOTES = "notes"
    const val SPEAKERS = "speakers"

    /**
     * The recorder, optionally resuming a note the background worker already transcribed.
     *
     * A query parameter carrying a String rather than [NavType.LongType], for the same reason the chat
     * route does it: "absent" has to be expressible, and LongType has no null.
     */
    const val RECORD = "record?noteId={noteId}"

    fun record(noteId: Long? = null): String =
        if (noteId == null) "record" else "record?noteId=$noteId"

    fun hub(query: String?) =
        if (query.isNullOrBlank()) HUB else "hub?q=${Uri.encode(query)}"

    /**
     * A query parameter, not a path segment.
     *
     * Model ids from HuggingFace look like `hf:litert-community/gemma-4-E2B-it:gemma-4-E2B.litertlm`
     * -- they contain slashes. In a path route (`chat/{modelId}`) those slashes split the id across
     * several path segments and no destination matches, which crashes the moment a user opens a
     * model they added themselves. Built-in ids have no slashes, so this only ever showed up with
     * real data. A query parameter has no segment structure to break.
     */
    const val CHAT = "chat?modelId={modelId}&conversationId={conversationId}"

    fun chat(modelId: String, conversationId: Long? = null): String {
        val base = "chat?modelId=${Uri.encode(modelId)}"
        return if (conversationId != null) "$base&conversationId=$conversationId" else base
    }

    /** Audit queue. Same modelId-as-query-parameter reasoning as [CHAT]. */
    const val AUDIT = "audit?modelId={modelId}&mode={mode}"

    /**
     * [mode] only seeds the screen's picker -- the read is pinned per document at enqueue, so a
     * route is never the authority on how a queued document was analysed.
     */
    fun audit(modelId: String, mode: AuditMode = AuditMode.DETAILED) =
        "audit?modelId=${Uri.encode(modelId)}&mode=${mode.name}"

    /** A saved per-document audit report, opened from the queue or from a chat. */
    const val AUDIT_REPORT = "auditReport?docId={docId}"

    fun auditReport(docId: Long) = "auditReport?docId=$docId"

    const val HISTORY = "history"
}

@Composable
private fun AppNavHost(
    settingsStore: SettingsStore,
    engines: EngineRegistry,
    huggingFaceAuth: HuggingFaceAuth,
    models: ModelDirectory,
    modelRepository: ModelRepository,
    speechModels: SpeechModelRepository,
    audioModels: AudioModelRepository,
    navController: NavHostController = rememberNavController(),
    deepLinkAuditDocId: Long? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    // A notification tap while the app is already past the splash: open the report on top of wherever
    // the user is. A cold launch is instead handled in the splash's onReady below, so the report
    // stacks on the Chats home rather than on the (popped) splash.
    LaunchedEffect(deepLinkAuditDocId) {
        val docId = deepLinkAuditDocId ?: return@LaunchedEffect
        val route = navController.currentBackStackEntry?.destination?.route
        if (route != null && route != Routes.SPLASH) {
            navController.navigate(Routes.auditReport(docId))
            onDeepLinkConsumed()
        }
    }

    NavHost(navController = navController, startDestination = Routes.SPLASH) {

        composable(Routes.SPLASH) {
            val splashViewModel: SplashViewModel = hiltViewModel()
            SplashScreen(
                viewModel = splashViewModel,
                onReady = {
                    // The splash is a one-way gate: popped so back from the home screen exits the
                    // app rather than replaying the permission flow.
                    navController.navigate(Routes.HISTORY) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                    // Launched by a completion notification: land on that report, above the home.
                    deepLinkAuditDocId?.let { docId ->
                        navController.navigate(Routes.auditReport(docId))
                        onDeepLinkConsumed()
                    }
                },
            )
        }

        composable(Routes.CATALOG) {
            val catalogViewModel: CatalogViewModel = hiltViewModel()
            val hubViewModel: HubViewModel = hiltViewModel()
            val mnnMarketViewModel: MnnMarketViewModel = hiltViewModel()
            CatalogScreen(
                viewModel = catalogViewModel,
                hubViewModel = hubViewModel,
                mnnMarketViewModel = mnnMarketViewModel,
                // Chatting from Manage models also makes that model the active one for new chats.
                onOpenChat = {
                    settingsStore.update { s -> s.copy(activeModelId = it.id) }
                    navController.navigate(Routes.chat(it.id))
                },
                // The card body opens the chat as well. A separate detail screen would add a tap
                // between a downloaded model and using it, to show information the card already has.
                onOpenDetail = {
                    settingsStore.update { s -> s.copy(activeModelId = it.id) }
                    navController.navigate(Routes.chat(it.id))
                },
                onSignIn = { navController.navigate(Routes.SETTINGS) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = "hub?q={q}",
            arguments = listOf(
                navArgument("q") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val hubViewModel: HubViewModel = hiltViewModel()

            // An app function can arrive here with a search already in mind ("find me a qwen
            // model"), so seed the query rather than dumping the user on an empty search box.
            val query = backStackEntry.arguments?.getString("q")
            LaunchedEffect(query) {
                if (!query.isNullOrBlank()) hubViewModel.onIntent(HubIntent.QueryChanged(query))
            }

            HubScreen(
                viewModel = hubViewModel,
                onSignIn = { navController.navigate(Routes.SETTINGS) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument("modelId") { type = NavType.StringType },
                navArgument("conversationId") {
                    // A String, parsed to Long, so "absent" is expressible -- LongType has no null.
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val modelId = backStackEntry.arguments?.getString("modelId").orEmpty()
            val conversationId = backStackEntry.arguments?.getString("conversationId")?.toLongOrNull()

            // Scoped to this back-stack entry, so leaving the chat clears the ViewModel and its
            // onCleared() unloads the model. Without that, gigabytes of native memory would stay
            // resident behind the catalogue screen.
            val chatViewModel: ChatViewModel = hiltViewModel()

            LaunchedEffect(modelId, conversationId) {
                chatViewModel.onIntent(ChatIntent.OpenChat(modelId, conversationId))
            }

            // The model asked to go somewhere. This is the one place an app function can move the
            // user, and it is deliberately narrow: the ViewModel emits an effect, and only routes
            // named here can satisfy it. A function cannot navigate anywhere the app does not
            // already let the user go.
            LaunchedEffect(chatViewModel) {
                chatViewModel.effects.collect { effect ->
                    when (effect) {
                        is ChatEffect.Navigate -> navController.handleAppNavigation(effect.destination)
                    }
                }
            }

            ChatScreen(
                viewModel = chatViewModel,
                onBack = { navController.popBackStack() },
                onOpenAudit = { mode -> navController.navigate(Routes.audit(modelId, mode)) },
            )
        }

        composable(
            route = Routes.AUDIT,
            arguments = listOf(
                navArgument("modelId") { type = NavType.StringType },
                navArgument("mode") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val modelId = backStackEntry.arguments?.getString("modelId").orEmpty()
            // An unknown or absent mode reads as DETAILED, which is what every route meant before
            // quick mode existed.
            val mode = AuditMode.from(backStackEntry.arguments?.getString("mode"))

            // Scoped to this back-stack entry, like the chat route, so leaving the audit clears the
            // ViewModel and detaches from the residency.
            val auditViewModel: AuditViewModel = hiltViewModel()

            LaunchedEffect(modelId, mode) { auditViewModel.onIntent(AuditIntent.Open(modelId, mode)) }

            AuditScreen(
                viewModel = auditViewModel,
                onBack = { navController.popBackStack() },
                onOpenReport = { docId -> navController.navigate(Routes.auditReport(docId)) },
            )
        }

        composable(
            route = Routes.AUDIT_REPORT,
            arguments = listOf(navArgument("docId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val docId = backStackEntry.arguments?.getLong("docId") ?: -1L
            val reportViewModel: AuditReportViewModel = hiltViewModel()
            LaunchedEffect(docId) { reportViewModel.onIntent(AuditReportIntent.Load(docId)) }
            AuditReportScreen(
                viewModel = reportViewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.NOTES) {
            val notesViewModel: NotesViewModel = hiltViewModel()
            NotesScreen(
                viewModel = notesViewModel,
                onRecord = { navController.navigate(Routes.record()) },
                // A note whose transcript is ready but unreviewed reopens the recorder at the review
                // step, rather than duplicating that whole flow somewhere else.
                onOpenDraft = { noteId -> navController.navigate(Routes.record(noteId)) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.RECORD,
            arguments = listOf(
                navArgument("noteId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val noteId = backStackEntry.arguments?.getString("noteId")?.toLongOrNull()
            val recordViewModel: RecordViewModel = hiltViewModel()

            LaunchedEffect(noteId) {
                if (noteId != null) recordViewModel.onIntent(RecordIntent.ResumeNote(noteId))
            }

            // The recorder's one-shot effects, collected in exactly one place. A command spoken into
            // the recording ("open settings") reaches the app through the very same AppNavigation
            // path the in-chat model uses -- one place decides where a command may send the user,
            // whether it was typed or spoken -- and a saved note leaves the same way.
            LaunchedEffect(recordViewModel) {
                recordViewModel.effects.collect { effect ->
                    when (effect) {
                        is RecordEffect.Navigate ->
                            navController.handleAppNavigation(effect.destination)
                        // Straight back to the list, where the new note is already at the top.
                        is RecordEffect.Saved ->
                            navController.popBackStack(Routes.NOTES, inclusive = false)
                    }
                }
            }

            RecordScreen(
                viewModel = recordViewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SPEAKERS) {
            val speakersViewModel: SpeakersViewModel = hiltViewModel()
            SpeakersScreen(
                viewModel = speakersViewModel,
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.HISTORY) {
            val historyViewModel: HistoryViewModel = hiltViewModel()
            HistoryScreen(
                viewModel = historyViewModel,
                onOpenChat = { modelId, conversationId ->
                    navController.navigate(Routes.chat(modelId, conversationId))
                },
                onNewChat = { model ->
                    // The model was chosen right there in the New-chat fan-out. Starting a chat
                    // also makes that model the active one, matching what the catalogue does.
                    settingsStore.update { s -> s.copy(activeModelId = model.id) }
                    navController.navigate(Routes.chat(model.id))
                },
                // The fan's "Download a model" option, shown when nothing is on disk yet.
                onGetModels = { navController.navigate(Routes.CATALOG) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenNotes = { navController.navigate(Routes.NOTES) },
                // Audit runs on the active model (the one new chats use). With none chosen yet, the
                // catalogue is where you pick and download one.
                onOpenAudit = { mode ->
                    val modelId = settingsStore.settings.value.activeModelId
                    if (modelId != null) navController.navigate(Routes.audit(modelId, mode))
                    else navController.navigate(Routes.CATALOG)
                },
                onOpenAuditReport = { docId -> navController.navigate(Routes.auditReport(docId)) },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                settingsStore = settingsStore,
                engines = engines,
                auth = huggingFaceAuth,
                downloadedModels = models.snapshot()
                    .filter { modelRepository.isDownloaded(it) },
                speechModels = speechModels.available,
                audioModels = audioModels,
                onOpenModels = { navController.navigate(Routes.CATALOG) },
                onOpenSpeakers = { navController.navigate(Routes.SPEAKERS) },
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/**
 * The one place an app command -- typed to the model or spoken into a recording -- may move the
 * user. Deliberately narrow: it can only reach destinations the app already exposes, so a command
 * can never navigate somewhere the UI itself would not.
 */
private fun NavHostController.handleAppNavigation(destination: AppNavigation) {
    when (destination) {
        is AppNavigation.Settings -> navigate(Routes.SETTINGS)
        // Catalog is no longer the home, so it is not always on the back stack -- navigate to it
        // rather than popping back to an entry that may not be there.
        is AppNavigation.Catalog -> navigate(Routes.CATALOG)
        is AppNavigation.HuggingFace -> navigate(Routes.hub(destination.query))
    }
}

