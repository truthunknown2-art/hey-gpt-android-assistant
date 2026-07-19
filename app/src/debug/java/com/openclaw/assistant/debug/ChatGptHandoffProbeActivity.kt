package com.openclaw.assistant.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.openclaw.assistant.chatgpt.ChatGptLiveLauncher

/** ADB-callable debug-only smoke test for the public ChatGPT launcher path. */
class ChatGptHandoffProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = ChatGptLiveLauncher.launch(this)
        Log.i(TAG, "RESULT=$result")
        finish()
    }

    companion object {
        const val TAG = "ChatGptHandoffProbe"
    }
}
