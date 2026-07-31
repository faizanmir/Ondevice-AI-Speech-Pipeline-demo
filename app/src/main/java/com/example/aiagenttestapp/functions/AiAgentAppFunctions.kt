package com.example.aiagenttestapp.functions

import androidx.appfunctions.AppFunctionContext
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.service.AppFunction
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.example.aiagent.engine.core.ToolCall

/**
 * This app's capabilities, exported to the Android system so agents like Gemini can call them.
 *
 * ## Why this exists separately from the in-app tool calling
 *
 * These two things point in opposite directions and neither can do the other's job.
 *
 * - [AppFunctionRegistry] + `ToolCallingProtocol` is how *our own* on-device model drives the app. It has
 *   to be a prompt protocol, because the model runs inside this process and there is no platform
 *   API for an app to call its own app functions.
 * - This class is how the *system's* agent drives the app. It cannot be reused for the in-app model,
 *   because `EXECUTE_APP_FUNCTIONS` is `protectionLevel="internal|role"` -- the platform grants it
 *   only to preinstalled apps holding the `ASSISTANT` role. A normal app simply cannot be a caller,
 *   no matter what it declares in its manifest.
 *
 * What they *do* share is the logic. Every method here is a thin adapter onto the same
 * [AppFunctionRegistry], so a capability is defined once and exposed twice. Adding a function to
 * that registry and forgetting to export it here costs you the export, not correctness.
 *
 * ## Notes on the API
 *
 * - The KDoc is not decoration. With `isDescribedByKDoc = true` the compiler encodes these comments
 *   into the function's metadata, and they become the *only* thing an agent reads to decide whether
 *   to call it. They are written for the agent, not for you.
 * - AppFunctions land on the main thread, so anything that could block must be a `suspend` fun that
 *   moves off it. Ours delegate to suspend functions that already do.
 * - This requires Android 16 (API 36). Below that the platform has no AppFunctions service, the
 *   generated code is simply never invoked, and the app is unaffected -- which is why minSdk can
 *   stay at 31.
 */
class AiAgentAppFunctions {

    /**
     * The platform instantiates this class itself, so there is no constructor to inject through --
     * an entry point is the supported way into the graph from a system-created object.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AppFunctionsEntryPoint {
        fun deps(): AppFunctionDeps
    }

    private fun deps(context: android.content.Context): AppFunctionDeps =
        EntryPointAccessors.fromApplication(context, AppFunctionsEntryPoint::class.java).deps()

    /** The outcome of running one of this app's functions. */
    @AppFunctionSerializable(isDescribedByKDoc = true)
    data class FunctionOutcome(
        /** A one-line summary of what happened, suitable for reading aloud to the user. */
        val summary: String,
        /** The full result, including any data the function looked up. */
        val details: String,
    )

    /**
     * Reports how much memory this phone has and the largest AI model it can run locally.
     *
     * Use this to answer questions about the device's RAM, its free storage, or what size of
     * on-device AI model would fit on it.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getDeviceAiCapability(appFunctionContext: AppFunctionContext): FunctionOutcome =
        run(appFunctionContext, "get_device_memory")

    /**
     * Lists the AI models that are already downloaded onto this phone and can run offline.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun listDownloadedModels(appFunctionContext: AppFunctionContext): FunctionOutcome =
        run(appFunctionContext, "list_downloaded_models")

    /**
     * Reports which on-device AI model is currently loaded, and which engine and processor it is
     * running on.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getCurrentModel(appFunctionContext: AppFunctionContext): FunctionOutcome =
        run(appFunctionContext, "get_current_model")

    /**
     * Changes how creative or focused the on-device AI's replies are.
     *
     * @param temperature A number between 0.0 and 2.0. Lower is more focused and repeatable;
     *   higher is more varied.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun setModelTemperature(
        appFunctionContext: AppFunctionContext,
        temperature: Double,
    ): FunctionOutcome = run(appFunctionContext, "set_temperature", mapOf("value" to temperature.toString()))

    /**
     * Runs one of the app's functions and adapts the result to the shape AppFunctions expects.
     *
     * Failures are raised as [AppFunctionInvalidArgumentException] rather than returned as an
     * outcome, because an agent needs to be able to *tell* that a call failed. Handing it a
     * successful-looking result whose text happens to say "that did not work" invites it to report
     * success to the user.
     */
    private suspend fun run(
        appFunctionContext: AppFunctionContext,
        name: String,
        arguments: Map<String, String> = emptyMap(),
    ): FunctionOutcome {
        val result = AppFunctionRegistry.Default.execute(
            ToolCall(name, arguments),
            deps(appFunctionContext.context),
        )

        if (result.isError) {
            throw AppFunctionInvalidArgumentException(result.output)
        }

        return FunctionOutcome(summary = result.summary, details = result.output)
    }
}
