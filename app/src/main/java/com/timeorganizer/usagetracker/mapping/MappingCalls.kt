package com.timeorganizer.usagetracker.mapping

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.timeorganizer.usagetracker.data.api.RetrofitClient
import com.timeorganizer.usagetracker.data.api.dto.TrackerActivityOptionsResponse
import com.timeorganizer.usagetracker.data.api.dto.TrackerProblemResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response

/** The mapping screens' calls: off the main thread, and `null` when nothing was learned (offline, a timeout). */
object MappingCalls {

    private val gson = Gson()

    suspend fun <T> call(block: suspend () -> Response<T>): Response<T>? = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** The answer as a [MappingOutcome]; the error body's `reason` decides between the 409s and the 404s. */
    fun outcome(response: Response<*>?): MappingOutcome {
        if (response == null) return MappingOutcome.of(null, null)
        val problem = if (response.isSuccessful) null else problemOf(response)
        return MappingOutcome.of(response.code(), problem?.reason, problem?.activityId)
    }

    private fun problemOf(response: Response<*>): TrackerProblemResponse? = try {
        response.errorBody()?.charStream()?.use { gson.fromJson(it, TrackerProblemResponse::class.java) }
    } catch (_: JsonParseException) {
        null
    } catch (_: java.io.IOException) {
        null
    }
}

/**
 * `GET tracker/android/activities` for one screen: read on first use and kept until something says it is stale (a new
 * activity, a gone one, a changed link). Names in it are personal: never logged.
 */
class MappingOptions {

    private var cached: TrackerActivityOptionsResponse? = null

    suspend fun get(): TrackerActivityOptionsResponse? = cached
        ?: MappingCalls.call { RetrofitClient.apiService.activityOptions() }
            ?.takeIf { it.isSuccessful }?.body()?.also { cached = it }

    fun invalidate() {
        cached = null
    }
}
