package com.example.aiagent.engine.litertlm

import android.util.Log
import com.example.aiagent.engine.core.NativeToolEngine
import com.example.aiagent.engine.core.ToolCall
import com.example.aiagent.engine.core.ToolDefinition
import com.google.ai.edge.litertlm.OpenApiTool

/**
 * One app function, described to LiteRT-LM in the schema its runtime speaks.
 *
 * The declarative [OpenApiTool] rather than the annotation-driven `ToolSet`: `@Tool`-annotated
 * Kotlin functions are read by reflection at construction, so the tool list would be whatever was
 * compiled in. This app's is not -- web search appears only when a Tavily key is set, and the set
 * of functions is data the app assembles per load. A schema built at run time is the only shape
 * that fits, and it is what `OpenApiTool` exists for.
 *
 * The engine is held rather than its runner, so the runner is read per call: the tools are baked
 * into the conversation when the model loads, while the thing that runs them belongs to whichever
 * screen is driving and may not exist yet -- see [NativeToolEngine.toolRunner].
 */
internal class AppFunctionTool(
    private val definition: ToolDefinition,
    private val engine: NativeToolEngine,
) : OpenApiTool {

    override fun getToolDescriptionJsonString(): String =
        ToolSchema.declaration(definition).toString()

    /**
     * Runs the call and returns what the model should be told.
     *
     * Never throws. This is invoked from inside the runtime's decode loop, where an exception
     * crosses a JNI boundary and takes generation down with it -- and a failed tool is an ordinary
     * event (no network, a bad argument, a function that no longer exists), not a reason to lose
     * the turn. Anything that goes wrong comes back as a result the model can read and recover
     * from.
     */
    override fun execute(paramsJsonString: String): String = try {
        engine.toolRunner
            ?.run(ToolCall(definition.name, ToolSchema.arguments(paramsJsonString)))
            ?: ToolSchema.errorJson("This function is not available right now.")
    } catch (t: Throwable) {
        Log.w(TAG, "${definition.name} failed", t)
        ToolSchema.errorJson(t.message ?: "The function failed.")
    }

    private companion object {
        const val TAG = "AppFunctionTool"
    }
}
