package com.example.aiagenttestapp.functions.tools

import com.example.aiagent.engine.core.SamplingParams
import com.example.aiagenttestapp.functions.AppFunction
import com.example.aiagenttestapp.functions.AppFunctionDeps
import com.example.aiagenttestapp.functions.AppFunctionResult
import com.example.aiagenttestapp.functions.NumberParam

/**
 * A function with a *visible* side effect the user can go and verify. Navigation is easy to fake
 * convincingly; changing a number that then shows up on the Settings screen is not.
 */
class SetTemperature : AppFunction() {

    override val name = "set_temperature"

    override val description =
        "Change how creative or focused replies are: lower is more focused and repeatable, " +
            "higher more varied."

    val value = NumberParam(
        name = "value",
        description = "A number between 0.0 and 2.0.",
    )

    override val parameters = listOf(value)

    override suspend fun run(
        arguments: Map<String, String>,
        deps: AppFunctionDeps,
    ): AppFunctionResult {
        val temperature = value.read(arguments)

        // Out of range is reported rather than clamped: a model that asked for 5.0 has misunderstood
        // the scale, and silently giving it 2.0 would hide that from the user *and* from the model.
        if (temperature == null || temperature !in VALID_RANGE) {
            return AppFunctionResult(
                summary = "Could not set the temperature",
                output = "\"${arguments[value.name]}\" is not a number between 0.0 and 2.0.",
                isError = true,
            )
        }

        deps.settingsStore.update { settings ->
            settings.copy(sampling = settings.sampling.copy(temperature = temperature))
        }
        return AppFunctionResult(
            summary = "Set temperature to $temperature",
            output = "Temperature is now $temperature. It applies to the next conversation.",
        )
    }

    private companion object {
        val VALID_RANGE = 0f..2f
    }
}

/** The way back from any sampling change, without the user having to know the defaults. */
class ResetSampling : AppFunction() {

    override val name = "reset_sampling"

    override val description = "Put the AI's creativity settings back to their defaults."

    override suspend fun run(
        arguments: Map<String, String>,
        deps: AppFunctionDeps,
    ): AppFunctionResult {
        deps.settingsStore.update { it.copy(sampling = SamplingParams()) }
        return AppFunctionResult(
            summary = "Reset sampling to defaults",
            output = "Sampling is back to the defaults: temperature 0.8, top-P 0.95, top-K 40.",
        )
    }
}
