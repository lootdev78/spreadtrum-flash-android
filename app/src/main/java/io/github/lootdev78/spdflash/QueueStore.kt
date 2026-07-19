package io.github.lootdev78.spdflash

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class QueueStore(context: Context) {
    private val prefs = context.getSharedPreferences("flash_state", Context.MODE_PRIVATE)

    fun loadQueue(): List<QueuedCommand> = runCatching {
        val array = JSONArray(prefs.getString(KEY_QUEUE, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val tokensJson = item.getJSONArray("tokens")
                val tokens = buildList { for (tokenIndex in 0 until tokensJson.length()) add(tokensJson.getString(tokenIndex)) }
                add(
                    QueuedCommand(
                        id = item.optLong("id", System.nanoTime()),
                        title = item.optString("title", tokens.firstOrNull().orEmpty()),
                        tokens = tokens,
                        risk = runCatching { RiskLevel.valueOf(item.optString("risk", RiskLevel.SAFE.name)) }.getOrDefault(RiskLevel.SAFE),
                        expectedBytes = if (item.has("expectedBytes") && !item.isNull("expectedBytes")) item.getLong("expectedBytes") else null,
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    fun saveQueue(queue: List<QueuedCommand>) {
        val array = JSONArray()
        queue.forEach { command ->
            array.put(JSONObject().apply {
                put("id", command.id)
                put("title", command.title)
                put("risk", command.risk.name)
                put("tokens", JSONArray(command.tokens))
                put("expectedBytes", command.expectedBytes ?: JSONObject.NULL)
            })
        }
        check(prefs.edit().putString(KEY_QUEUE, array.toString()).commit()) { "Could not save the queue" }
    }

    var fdl1Path: String?
        get() = prefs.getString(KEY_FDL1, null)
        set(value) = prefs.edit().putString(KEY_FDL1, value).apply()

    var fdl2Path: String?
        get() = prefs.getString(KEY_FDL2, null)
        set(value) = prefs.edit().putString(KEY_FDL2, value).apply()

    var fdl1Address: String
        get() = prefs.getString(KEY_FDL1_ADDRESS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FDL1_ADDRESS, value).apply()

    var fdl2Address: String
        get() = prefs.getString(KEY_FDL2_ADDRESS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FDL2_ADDRESS, value).apply()

    var interruptedRun: Boolean
        get() = prefs.getBoolean(KEY_INTERRUPTED, false)
        set(value) { prefs.edit().putBoolean(KEY_INTERRUPTED, value).commit() }

    fun loadPipelineDraft(): PipelineDraft = PipelineDraft(
        waitSeconds = prefs.getInt(KEY_WAIT_SECONDS, 30).coerceIn(1, 3600),
        verbose = prefs.getInt(KEY_VERBOSE, 1).coerceIn(0, 2),
        reconnect = prefs.getBoolean(KEY_RECONNECT, false),
        sync = prefs.getBoolean(KEY_SYNC, false),
        kickMode = prefs.getInt(KEY_KICK_MODE, 0).coerceIn(0, 2),
        kickToMode = prefs.getInt(KEY_KICK_TO, 2).coerceIn(0, 127),
        useLoaders = prefs.getBoolean(KEY_USE_LOADERS, true),
        executeFdl2 = prefs.getBoolean(KEY_EXECUTE_FDL2, true),
        directSafOutput = prefs.getBoolean(KEY_DIRECT_SAF, false),
    )

    fun savePipelineDraft(draft: PipelineDraft) {
        prefs.edit()
            .putInt(KEY_WAIT_SECONDS, draft.waitSeconds.coerceIn(1, 3600))
            .putInt(KEY_VERBOSE, draft.verbose.coerceIn(0, 2))
            .putBoolean(KEY_RECONNECT, draft.reconnect)
            .putBoolean(KEY_SYNC, draft.sync)
            .putInt(KEY_KICK_MODE, draft.kickMode.coerceIn(0, 2))
            .putInt(KEY_KICK_TO, draft.kickToMode.coerceIn(0, 127))
            .putBoolean(KEY_USE_LOADERS, draft.useLoaders)
            .putBoolean(KEY_EXECUTE_FDL2, draft.executeFdl2)
            .putBoolean(KEY_DIRECT_SAF, draft.directSafOutput)
            .apply()
    }

    companion object {
        private const val KEY_QUEUE = "queue"
        private const val KEY_FDL1 = "fdl1_path"
        private const val KEY_FDL2 = "fdl2_path"
        private const val KEY_FDL1_ADDRESS = "fdl1_address"
        private const val KEY_FDL2_ADDRESS = "fdl2_address"
        private const val KEY_INTERRUPTED = "interrupted_run"
        private const val KEY_WAIT_SECONDS = "wait_seconds"
        private const val KEY_VERBOSE = "verbose"
        private const val KEY_RECONNECT = "reconnect"
        private const val KEY_SYNC = "sync"
        private const val KEY_KICK_MODE = "kick_mode"
        private const val KEY_KICK_TO = "kick_to"
        private const val KEY_USE_LOADERS = "use_loaders"
        private const val KEY_EXECUTE_FDL2 = "execute_fdl2"
        private const val KEY_DIRECT_SAF = "direct_saf"
    }
}
