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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.aiagenttestapp.functions.AppNavigation
import com.example.aiagenttestapp.ui.catalog.CatalogScreen
import com.example.aiagenttestapp.ui.catalog.CatalogViewModel
import com.example.aiagenttestapp.ui.catalog.MnnMarketViewModel
import com.example.aiagenttestapp.ui.chat.ChatScreen
import com.example.aiagenttestapp.ui.chat.ChatViewModel
import com.example.aiagenttestapp.ui.history.HistoryScreen
import com.example.aiagenttestapp.ui.history.HistoryViewModel
import com.example.aiagenttestapp.ui.hub.HubScreen
import com.example.aiagenttestapp.ui.hub.HubViewModel
import com.example.aiagenttestapp.ui.notes.NotesScreen
import com.example.aiagenttestapp.ui.notes.NotesViewModel
import com.example.aiagenttestapp.ui.notes.RecordScreen
import com.example.aiagenttestapp.ui.notes.RecordViewModel
import com.example.aiagenttestapp.ui.settings.SettingsScreen
import com.example.aiagenttestapp.ui.theme.AIAgentTestAppTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as AIAgentApplication).container

        setContent {
            AIAgentTestAppTheme {
                AppNavHost(container = container)
            }
        }
    }
}

private object Routes {
    const val CATALOG = "catalog"
    const val SETTINGS = "settings"
    const val HUB = "hub"
    const val NOTES = "notes"
    const val RECORD = "record"

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

    const val HISTORY = "history"
}

@Composable
private fun AppNavHost(
    container: AppContainer,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = Routes.HISTORY) {

        composable(Routes.CATALOG) {
            val catalogViewModel: CatalogViewModel = viewModel(factory = container.factory())
            val hubViewModel: HubViewModel = viewModel(factory = container.factory())
            val mnnMarketViewModel: MnnMarketViewModel = viewModel(factory = container.factory())
            CatalogScreen(
                viewModel = catalogViewModel,
                hubViewModel = hubViewModel,
                mnnMarketViewModel = mnnMarketViewModel,
                // Chatting from Manage models also makes that model the active one for new chats.
                onOpenChat = {
                    container.settingsStore.update { s -> s.copy(activeModelId = it.id) }
                    navController.navigate(Routes.chat(it.id))
                },
                // The card body opens the chat as well. A separate detail screen would add a tap
                // between a downloaded model and using it, to show information the card already has.
                onOpenDetail = {
                    container.settingsStore.update { s -> s.copy(activeModelId = it.id) }
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
            val hubViewModel: HubViewModel = viewModel(factory = container.factory())

            // An app function can arrive here with a search already in mind ("find me a qwen
            // model"), so seed the query rather than dumping the user on an empty search box.
            val query = backStackEntry.arguments?.getString("q")
            LaunchedEffect(query) {
                if (!query.isNullOrBlank()) hubViewModel.onQueryChange(query)
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
            val chatViewModel: ChatViewModel = viewModel(factory = container.factory())

            LaunchedEffect(modelId, conversationId) { chatViewModel.openChat(modelId, conversationId) }

            // The model asked to go somewhere. This is the one place an app function can move the
            // user, and it is deliberately narrow: the ViewModel emits an intent, and only routes
            // named here can satisfy it. A function cannot navigate anywhere the app does not
            // already let the user go.
            LaunchedEffect(chatViewModel) {
                chatViewModel.navigation.collect { navController.handleAppNavigation(it) }
            }

            ChatScreen(
                viewModel = chatViewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.NOTES) {
            val notesViewModel: NotesViewModel = viewModel(factory = container.factory())
            NotesScreen(
                viewModel = notesViewModel,
                onRecord = { navController.navigate(Routes.RECORD) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.RECORD) {
            val recordViewModel: RecordViewModel = viewModel(factory = container.factory())

            // A command spoken into the recording ("open settings") reaches the app through the
            // very same AppNavigation path the in-chat model uses -- one place decides where a
            // command may send the user, whether it was typed or spoken.
            LaunchedEffect(recordViewModel) {
                recordViewModel.navigation.collect { navController.handleAppNavigation(it) }
            }

            RecordScreen(
                viewModel = recordViewModel,
                // Straight back to the list, where the new note is already at the top.
                onSaved = { navController.popBackStack(Routes.NOTES, inclusive = false) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.HISTORY) {
            val historyViewModel: HistoryViewModel = viewModel(factory = container.factory())
            HistoryScreen(
                viewModel = historyViewModel,
                onOpenChat = { modelId, conversationId ->
                    navController.navigate(Routes.chat(modelId, conversationId))
                },
                onNewChat = { model ->
                    // The model was chosen right there in the New-chat fan-out. Starting a chat
                    // also makes that model the active one, matching what the catalogue does.
                    container.settingsStore.update { s -> s.copy(activeModelId = model.id) }
                    navController.navigate(Routes.chat(model.id))
                },
                // The fan's "Download a model" option, shown when nothing is on disk yet.
                onGetModels = { navController.navigate(Routes.CATALOG) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenNotes = { navController.navigate(Routes.NOTES) },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                settingsStore = container.settingsStore,
                engines = container.engines,
                auth = container.huggingFaceAuth,
                downloadedModels = container.allModelsSnapshot()
                    .filter { container.modelRepository.isDownloaded(it) },
                speechModels = container.speechModels.available,
                onOpenModels = { navController.navigate(Routes.CATALOG) },
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

/** Bridges the hand-rolled container to ViewModelProvider without pulling in a DI framework. */
private fun AppContainer.factory(): ViewModelProvider.Factory {
    val container = this
    return object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
            modelClass.isAssignableFrom(CatalogViewModel::class.java) ->
                CatalogViewModel(container) as T

            modelClass.isAssignableFrom(ChatViewModel::class.java) ->
                ChatViewModel(container) as T

            modelClass.isAssignableFrom(HubViewModel::class.java) ->
                HubViewModel(container) as T

            modelClass.isAssignableFrom(MnnMarketViewModel::class.java) ->
                MnnMarketViewModel(container) as T

            modelClass.isAssignableFrom(NotesViewModel::class.java) ->
                NotesViewModel(container) as T

            modelClass.isAssignableFrom(RecordViewModel::class.java) ->
                RecordViewModel(container) as T

            modelClass.isAssignableFrom(HistoryViewModel::class.java) ->
                HistoryViewModel(container) as T

            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
