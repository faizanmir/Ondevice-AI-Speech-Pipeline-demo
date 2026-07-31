package com.example.aiagenttestapp.ui.chat

import com.example.aiagent.engine.core.Accelerator
import com.example.aiagent.engine.core.EngineException
import com.example.aiagent.engine.core.InferenceEngine
import com.example.aiagent.engine.core.LoadRequest
import com.example.aiagent.engine.core.ToolRunner
import com.example.aiagenttestapp.data.ChatLoadPlan
import com.example.aiagenttestapp.data.ModelLoadPlan
import com.example.aiagenttestapp.data.chat.Conversation
import com.example.aiagenttestapp.data.chat.ConversationWithMessages
import com.example.aiagenttestapp.data.chat.StoredMessage
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The load path's *order*, which is the part of it that can be wrong.
 *
 * Every step here is a step that has to happen before or after another one, and every rule was a
 * comment until now: the engine is published before the load can fail, so an error screen still
 * names it; residency is attached before opening and detached only after the summary, so the model
 * cannot be released out from under it; the tool runner is unbound before anything else on the way
 * out, so a screen that no longer exists cannot be called into.
 */
class ChatSessionTest {

    // ---- fakes --------------------------------------------------------------------------------

    private class FakeStore(private val stored: ConversationWithMessages? = null) : ChatStore {
        val summaries = mutableListOf<Pair<Long, String>>()
        override suspend fun conversation(id: Long) = stored
        override suspend fun insertConversation(conversation: Conversation) = 1L
        override suspend fun insertMessage(message: StoredMessage) = Unit
        override suspend fun touch(id: Long, at: Long) = Unit
        override suspend fun updateSummary(id: Long, summary: String) {
            summaries += id to summary
        }
    }

    private class FakeResidency(private val engine: InferenceEngine) : ChatResidency {
        val events = mutableListOf<String>()
        var lastRequest: LoadRequest? = null
        var reuseAsked: Boolean? = null
        var failWith: Throwable? = null

        override fun attach() { events += "attach" }
        override fun detach() { events += "detach" }

        override suspend fun open(
            plan: ModelLoadPlan.Resolved,
            request: LoadRequest,
            reuseWhenResident: Boolean,
        ): InferenceEngine {
            events += "open"
            lastRequest = request
            reuseAsked = reuseWhenResident
            failWith?.let { throw it }
            return engine
        }

        override suspend fun runExclusive(block: suspend () -> Unit) {
            events += "runExclusive"
            block()
        }
    }

    private class RecordingListener : ChatSession.Listener {
        val events = mutableListOf<String>()
        val runner = ToolRunner { "{}" }
        override fun onUnloadable(plan: ChatLoadPlan) { events += "unloadable" }
        override fun onPlanned(plan: ChatLoadPlan.Ready) { events += "planned" }
        override fun onRestored(messages: List<StoredMessage>) { events += "restored:${messages.size}" }
        override fun createToolRunner(): ToolRunner {
            events += "runner"
            return runner
        }
        override fun onReady(accelerator: Accelerator) { events += "ready:${accelerator.label}" }
        override fun onFailed(message: String) { events += "failed:$message" }
    }

    // ---- tests --------------------------------------------------------------------------------

    @Test
    fun `an unknown model is reported and nothing is attached`() = runTest {
        val residency = FakeResidency(FakeEngine())
        val listener = RecordingListener()
        session(ChatLoadPlan.UnknownModel, residency, listener, this).open("nope", null)
        advanceUntilIdle()

        assertEquals(listOf("unloadable"), listener.events)
        // Attaching for a model that will never load would pin residency for the screen's life.
        assertTrue(residency.events.isEmpty())
    }

    @Test
    fun `the engine is published before the load is attempted`() = runTest {
        val engine = FakeEngine()
        val residency = FakeResidency(engine).apply { failWith = RuntimeException("boom") }
        val listener = RecordingListener()
        session(readyPlan(engine), residency, listener, this).open("m", null)
        advanceUntilIdle()

        // "planned" first, so a failure screen still reads "LiteRT-LM · GPU" rather than a bare
        // accelerator with no model name.
        assertEquals(listOf("planned", "failed:boom"), listener.events)
    }

    @Test
    fun `a successful load attaches, opens, binds the runner, then reports ready`() = runTest {
        val engine = FakeEngine(activeAccelerator = Accelerator.CPU)
        val residency = FakeResidency(engine)
        val listener = RecordingListener()

        session(readyPlan(engine), residency, listener, this).open("m", null)
        advanceUntilIdle()

        assertEquals(listOf("attach", "open"), residency.events)
        // The runner is bound before ready: the screen is told it can send only once the model can
        // actually run a function it asks for.
        assertEquals(listOf("planned", "runner", "ready:CPU"), listener.events)
        assertEquals(listener.runner, engine.toolRunner)
    }

    @Test
    fun `the accelerator reported is the one the engine actually used`() = runTest {
        // The plan asked for the GPU; the engine fell back. Reporting the request would leave the
        // speed the user sees with no explanation.
        val engine = FakeEngine(activeAccelerator = Accelerator.CPU)
        val residency = FakeResidency(engine)
        val listener = RecordingListener()

        session(readyPlan(engine, accelerator = Accelerator.GPU), residency, listener, this)
            .open("m", null)
        advanceUntilIdle()

        assertTrue("ready:CPU" in listener.events)
    }

    @Test
    fun `a fresh chat asks for reuse, a resumed one does not`() = runTest {
        val freshEngine = FakeEngine()
        val fresh = FakeResidency(freshEngine)
        session(readyPlan(freshEngine), fresh, RecordingListener(), this).open("m", null)
        advanceUntilIdle()
        assertEquals(true, fresh.reuseAsked)

        val resumedEngine = FakeEngine()
        val resumed = FakeResidency(resumedEngine)
        session(readyPlan(resumedEngine), resumed, RecordingListener(), this, store = FakeStore(conversation()))
            .open("m", 7L)
        advanceUntilIdle()
        // A resumed chat carries restored history, so the resident model cannot simply be reset
        // onto it.
        assertEquals(false, resumed.reuseAsked)
    }

    @Test
    fun `a resumed conversation restores its turns and folds its summary into the prompt`() = runTest {
        val engine = FakeEngine()
        val residency = FakeResidency(engine)
        val listener = RecordingListener()

        session(readyPlan(engine), residency, listener, this, store = FakeStore(conversation()))
            .open("m", 7L)
        advanceUntilIdle()

        assertTrue("restored:2" in listener.events)
        val prompt = residency.lastRequest?.systemPrompt.orEmpty()
        assertTrue("the base prompt must still be there", prompt.startsWith("base prompt"))
        assertTrue("the stored summary must be folded in", prompt.contains("earlier summary"))
    }

    @Test
    fun `close unbinds the runner before anything else`() = runTest {
        val engine = FakeEngine()
        val session = session(readyPlan(engine), FakeResidency(engine), RecordingListener(), this)
        session.open("m", null)
        advanceUntilIdle()

        session.close(this, isGenerating = false, contextTotal = 0)

        // Left bound, it would keep a dead screen reachable from a singleton engine -- and still
        // execute functions for it.
        assertEquals(null, engine.toolRunner)
    }

    @Test
    fun `close detaches after the summary, not before`() = runTest {
        val engine = FakeEngine(contextUsed = 900)
        val residency = FakeResidency(engine)
        val session = session(readyPlan(engine), residency, RecordingListener(), this)
        session.open("m", 7L)
        advanceUntilIdle()

        session.close(this, isGenerating = false, contextTotal = 1000)
        advanceUntilIdle()

        // Detaching first would let residency free the model out from under the summary that is
        // still generating on it.
        assertEquals(listOf("attach", "open", "runExclusive", "detach"), residency.events)
    }

    @Test
    fun `a chat closed mid-turn is not summarised`() = runTest {
        val engine = FakeEngine(contextUsed = 900)
        val residency = FakeResidency(engine)
        val session = session(readyPlan(engine), residency, RecordingListener(), this)
        session.open("m", 7L)
        advanceUntilIdle()

        session.close(this, isGenerating = true, contextTotal = 1000)
        advanceUntilIdle()

        // Summarising over a half-written reply would store a summary of a conversation that does
        // not exist yet.
        assertEquals(listOf("attach", "open", "detach"), residency.events)
    }

    // ---- compaction ---------------------------------------------------------------------------

    @Test
    fun `a conversation well inside the window is not compacted`() = runTest {
        val engine = FakeEngine(contextUsed = 400)
        val residency = FakeResidency(engine)
        val session = session(readyPlan(engine), residency, RecordingListener(), this,
            store = FakeStore(conversation()))
        session.open("m", 7L)
        advanceUntilIdle()
        residency.events.clear()

        assertEquals(false, session.compactIfNeeded(contextTotal = 1000))

        // Compacting costs a summary turn and a reload; at 40% there is nothing to buy with it.
        assertTrue(residency.events.isEmpty())
    }

    @Test
    fun `a conversation near the end of the window is summarised and reloaded`() = runTest {
        val engine = FakeEngine(contextUsed = 900)
        val residency = FakeResidency(engine)
        val store = FakeStore(conversation())
        val session = session(readyPlan(engine), residency, RecordingListener(), this, store = store)
        session.open("m", 7L)
        advanceUntilIdle()
        residency.events.clear()

        assertEquals(true, session.compactIfNeeded(contextTotal = 1000))
        advanceUntilIdle()

        // The summary is written first and the model reloaded onto it -- older turns survive as
        // the summary rather than being dropped.
        assertEquals(listOf("runExclusive", "open"), residency.events)
        assertEquals(listOf(7L to "a summary"), store.summaries)
    }

    @Test
    fun `compaction is skipped before a model is loaded`() = runTest {
        val engine = FakeEngine(contextUsed = 900)
        val session = session(readyPlan(engine), FakeResidency(engine), RecordingListener(), this)

        // No open() yet: nothing to summarise, and no plan to reload.
        assertEquals(false, session.compactIfNeeded(contextTotal = 1000))
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private fun session(
        plan: ChatLoadPlan,
        residency: ChatResidency,
        listener: ChatSession.Listener,
        scope: TestScope,
        store: ChatStore = FakeStore(),
    ) = ChatSession(
        planner = object : ChatModelPlanner {
            override fun plan(modelId: String) = plan
        },
        store = store,
        residency = residency,
        scope = scope,
        listener = listener,
    )

    /** A minimal engine: the load path only reads its accelerator and context use. */
    private class FakeEngine(
        override val activeAccelerator: Accelerator? = Accelerator.CPU,
        private val contextUsed: Int = 0,
    ) : InferenceEngine, com.example.aiagent.engine.core.NativeToolEngine {
        override var toolRunner: ToolRunner? = null
        override val descriptor = com.example.aiagent.engine.core.EngineDescriptor(
            id = com.example.aiagent.engine.core.EngineId.LITE_RT_LM,
            displayName = "LiteRT-LM",
            vendor = "test",
            supportedFormats = setOf(com.example.aiagent.engine.core.ModelFormat.LITERTLM),
            supportedAccelerators = setOf(Accelerator.CPU),
            supportsVision = false,
            supportsNativeTools = true,
            blurb = "",
        )
        override val loadedModelPath: String? = "/m.litertlm"
        override fun availability() = com.example.aiagent.engine.core.EngineAvailability.Available
        override suspend fun load(request: LoadRequest) = Unit
        override fun generate(prompt: String) =
            kotlinx.coroutines.flow.flowOf<com.example.aiagent.engine.core.GenerationEvent>(
                com.example.aiagent.engine.core.GenerationEvent.Token("a summary"),
            )
        override fun cancel() = Unit
        override suspend fun resetConversation() = Unit
        override fun contextTokensUsed() = contextUsed
        override suspend fun unload() = Unit
    }

    /**
     * [engine] must be the same instance residency returns -- it is in production, where
     * ModelResidency.open hands back plan.engine, and the session reads the accelerator and
     * context use off the plan's copy.
     */
    private fun readyPlan(
        engine: InferenceEngine,
        accelerator: Accelerator = Accelerator.CPU,
    ): ChatLoadPlan.Ready {
        val model = com.example.aiagent.engine.core.ModelSpec(
            id = "m", name = "Test model", vendor = "t", paramsBillions = 1.0,
            quantization = com.example.aiagent.engine.core.Quantization.Q4,
            format = com.example.aiagent.engine.core.ModelFormat.LITERTLM,
            downloadUrl = "", fileName = "m.litertlm", sizeBytes = 1, contextTokens = 4096,
            minDeviceMemoryGb = 2, accelerators = setOf(accelerator), license = "", description = "",
        )
        return ChatLoadPlan.Ready(
            resolved = ModelLoadPlan.Resolved(
                model = model,
                engine = engine,
                accelerator = accelerator,
                file = java.io.File("/m.litertlm"),
                sampling = com.example.aiagent.engine.core.SamplingParams(),
                threadCount = 0,
                cacheDir = "/cache",
                nativeLibraryDir = "/lib",
                downloaded = true,
            ),
            toolsEnabled = true,
            toolsUnavailableReason = null,
            systemPrompt = "base prompt",
        )
    }

    private fun conversation() = ConversationWithMessages(
        conversation = Conversation(
            id = 7L,
            modelId = "m",
            title = "t",
            createdAtMillis = 0,
            updatedAtMillis = 0,
            summary = "earlier summary",
        ),
        messages = listOf(
            StoredMessage(id = 1, conversationId = 7, role = "user", content = "hi", createdAtMillis = 0),
            StoredMessage(id = 2, conversationId = 7, role = "assistant", content = "hello", createdAtMillis = 0),
        ),
    )
}
