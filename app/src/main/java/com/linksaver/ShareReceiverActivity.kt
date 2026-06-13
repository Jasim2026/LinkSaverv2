package com.linksaver

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            val urlRegex = "(https?://[^ \\n]+)".toRegex()
            val url = urlRegex.find(text)?.value
            
            if (url != null) {
                val tag = text.replace(url, "").trim()
                val prefs = getSharedPreferences("linksaver_prefs", Context.MODE_PRIVATE)
                val dbPath = prefs.getString("db_path", null)
                val hasPermission = Environment.isExternalStorageManager()

                if (dbPath != null && hasPermission) {
                    // Launch in background to allow retry delays without blocking UI thread
                    CoroutineScope(Dispatchers.IO).launch {
                        var success = false
                        var attempts = 0
                        
                        // Retry loop for cold starts where storage mounting may lag by a few milliseconds
                        while (!success && attempts < 4) {
                            try {
                                if (DatabaseManager.initDb(dbPath)) {
                                    val isYoutube = url.contains("youtube.com", true) || url.contains("youtu.be", true)
                                    DatabaseManager.saveLink(url, tag, isYoutube)
                                    success = true
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(this@ShareReceiverActivity, "Link Saved!", Toast.LENGTH_SHORT).show()
                                        finishAndRemoveTask()
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            
                            if (!success) {
                                attempts++
                                delay(300) // Wait 300ms before trying again
                            }
                        }
                        
                        if (!success) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@ShareReceiverActivity, "Error accessing database file.", Toast.LENGTH_SHORT).show()
                                finishAndRemoveTask()
                            }
                        }
                    }
                    return // Prevent immediate finishAndRemoveTask() below
                } else {
                    Toast.makeText(this, "LinkSaver setup incomplete. Open the app first.", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "No valid link found in shared text", Toast.LENGTH_SHORT).show()
            }
        }
        
        finishAndRemoveTask()
    }
}