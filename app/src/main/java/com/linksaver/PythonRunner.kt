package com.linksaver

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import com.jcraft.jsch.JSch
import org.json.JSONArray
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.*

object OutputLogger {
    val sessionLogs = mutableStateMapOf<String, MutableList<String>>()

    fun log(scriptName: String, text: String) {
        if (!sessionLogs.containsKey(scriptName)) {
            sessionLogs[scriptName] = mutableStateListOf()
        }
        sessionLogs[scriptName]?.add(text)
    }
}

interface PythonLogger {
    fun log(msg: String)
}

object PythonRunner {

    fun executeSingleScript(context: Context, fileName: String): Boolean {
        val prefs = context.getSharedPreferences("linksaver_prefs", Context.MODE_PRIVATE)
        val scriptsDirPath = prefs.getString("script_folder_path", null)
        val dbPath = prefs.getString("db_path", null)
        if (scriptsDirPath == null || dbPath == null) return false

        DatabaseManager.initDb(dbPath)

        val scriptFile = File(scriptsDirPath, fileName)
        if (!scriptFile.exists()) {
            DatabaseManager.addLog("FAILURE", "Script not found: $fileName")
            return false
        }
        
        val success = runSingleFile(context, dbPath, scriptFile)
        
        if (success) {
            val schedule = DatabaseManager.getSchedule(fileName)
            if (schedule != null && schedule.triggerScript.isNotBlank() && schedule.triggerScript != "None") {
                val triggerSchedule = DatabaseManager.getSchedule(schedule.triggerScript)
                if (triggerSchedule?.runAsServer == true) {
                    val sIntent = Intent(context, ServerService::class.java)
                    sIntent.putExtra("SCRIPT_NAME", schedule.triggerScript)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(sIntent)
                    } else {
                        context.startService(sIntent)
                    }
                } else {
                    val intent = Intent(context, ScriptService::class.java)
                    intent.putExtra("SCRIPT_NAME", schedule.triggerScript)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                }
            }
        }
        return success
    }

    private fun runSingleFile(context: Context, dbPath: String, scriptFile: File): Boolean {
        val py = Python.getInstance()
        val mainModule = py.getModule("__main__")
        
        mainModule.put("DB_PATH", "$dbPath/linksaver.db")
        mainModule.put("DB_DIR", dbPath)

        val sitePackages = File(context.filesDir, "site-packages")
        if (!sitePackages.exists()) sitePackages.mkdirs()

        val loggerObj = object : PythonLogger {
            override fun log(msg: String) {
                OutputLogger.log(scriptFile.name, msg)
            }
        }
        mainModule.put("android_logger", loggerObj)

        val mainGlobals = mainModule.get("__dict__")
        val setupCode = """
import sys
site_pkgs = "${sitePackages.absolutePath}"
if site_pkgs not in sys.path:
    sys.path.insert(0, site_pkgs)

class AndroidWriter:
    def __init__(self, logger):
        self.logger = logger
    def write(self, msg):
        if not msg:
            return
        if isinstance(msg, bytes):
            msg = msg.decode('utf-8', errors='replace')
        text = msg.strip()
        if text:
            self.logger.log(text)
    def flush(self):
        pass

sys.stdout = AndroidWriter(android_logger)
sys.stderr = AndroidWriter(android_logger)
"""
        py.getModule("builtins").callAttr("exec", setupCode, mainGlobals)

        return try {
            val scriptCode = scriptFile.readText()
            py.getModule("builtins").callAttr("exec", scriptCode, mainGlobals)
            DatabaseManager.addLog("SUCCESS", "Executed ${scriptFile.name} successfully.")
            true
        } catch (e: Exception) {
            val errorMsg = e.stackTraceToString()
            DatabaseManager.addLog("FAILURE", "Error in ${scriptFile.name}:\n${e.message}")

            val isNetwork = errorMsg.contains("Timeout", true) || 
                            errorMsg.contains("ConnectionError", true) || 
                            errorMsg.contains("404")
            if (!isNetwork) {
                sendNotification(context, "Script Error", "Error in ${scriptFile.name}. Check Logs.")
            }
            false
        }
    }

    fun installPipPackage(context: Context, scriptDirPath: String, packageName: String, onLog: (String) -> Unit): Boolean {
        val sitePackages = File(context.filesDir, "site-packages")
        if (!sitePackages.exists()) sitePackages.mkdirs()
        
        val py = Python.getInstance()
        val mainModule = py.getModule("__main__")

        val loggerObj = object : PythonLogger {
            override fun log(msg: String) {
                onLog(msg)
            }
        }
        mainModule.put("pip_logger", loggerObj)

        val installerCode = """
import urllib.request, json, zipfile, tarfile, os, re

def find_local_package(pkg, modules_dir):
    base_name_wheel = pkg.replace('-', '_').lower()
    base_name_tar = pkg.lower()
    candidates = []
    if os.path.exists(modules_dir):
        for f in os.listdir(modules_dir):
            fl = f.lower()
            if fl.endswith('.whl') and fl.startswith(base_name_wheel + '-'):
                candidates.append(f)
            elif fl.endswith('.tar.gz') and fl.startswith(base_name_tar + '-'):
                candidates.append(f)
    if candidates:
        candidates.sort(reverse=True)
        return os.path.join(modules_dir, candidates[0])
    return None

def install_package(pkg, target_site_packages, modules_dir, log, visited=None):
    if visited is None: visited = set()
    pkg_clean = pkg.lower().replace("-", "_")
    if pkg_clean in visited: return True
    visited.add(pkg_clean)
    
    local_path = find_local_package(pkg, modules_dir)
    requires = []
    
    if local_path:
        log.log(f"[INFO] Found {pkg} locally in modules/: {os.path.basename(local_path)}")
        temp_path = local_path
        fn = os.path.basename(local_path)
    else:
        log.log(f"[INFO] {pkg} not found locally. Querying PyPI...")
        try:
            url = f"https://pypi.org/pypi/{pkg}/json"
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req) as resp:
                data = json.loads(resp.read().decode())
            
            info = data.get("info", {})
            urls = data.get("urls", [])
            
            wheel_url = None
            for u in urls:
                fn_u = u.get("filename", "")
                if fn_u.endswith(".whl") and ("none-any" in fn_u or "any" in fn_u):
                    wheel_url = u.get("url"); break
            if not wheel_url:
                for u in urls:
                    if u.get("filename", "").endswith(".whl"): wheel_url = u.get("url"); break
            if not wheel_url:
                for u in urls:
                    if u.get("filename", "").endswith(".tar.gz"): wheel_url = u.get("url"); break
                    
            if not wheel_url:
                log.log(f"[ERROR] No compatible distribution found for {pkg}.")
                return False
                
            fn = wheel_url.split("/")[-1]
            if not os.path.exists(modules_dir):
                os.makedirs(modules_dir)
            temp_path = os.path.join(modules_dir, fn)
            
            log.log(f"[INFO] Downloading {fn} into external modules/ cache...")
            urllib.request.urlretrieve(wheel_url, temp_path)
            requires = info.get("requires_dist") or []
            
        except Exception as e:
            log.log(f"[ERROR] PyPI download failed for {pkg}: {str(e)}")
            return False

    log.log(f"[INFO] Extracting to internal /site-packages...")
    try:
        if fn.endswith(".whl") or fn.endswith(".zip"):
            with zipfile.ZipFile(temp_path, 'r') as zf: zf.extractall(target_site_packages)
        elif fn.endswith(".tar.gz"):
            with tarfile.open(temp_path, "r:gz") as tf: tf.extractall(target_site_packages)
        log.log(f"[SUCCESS] Extracted {pkg}!")
    except Exception as e:
        log.log(f"[ERROR] Failed to extract {pkg}. Corrupted cache file?")
        if local_path and os.path.exists(temp_path):
            os.remove(temp_path)
            log.log(f"[INFO] Deleted corrupted file {temp_path}. Try again.")
        return False

    if not requires:
        base_name_search = pkg.replace('-', '_').lower()
        for d in os.listdir(target_site_packages):
            if d.lower().startswith(base_name_search) and d.endswith('.dist-info'):
                meta_path = os.path.join(target_site_packages, d, 'METADATA')
                if os.path.exists(meta_path):
                    with open(meta_path, 'r', encoding='utf-8') as f:
                        for line in f:
                            if line.startswith('Requires-Dist:'):
                                req_val = line.split(':', 1)[1].strip()
                                requires.append(req_val)
                break
    
    for r_str in requires:
        if ";" in r_str:
            marker = r_str.split(";")[1]
            if "win32" in marker or "darwin" in marker or "msys" in marker: continue
            r_str = r_str.split(";")[0]
        match = re.match(r"^([a-zA-Z0-9\-_]+)", r_str.strip())
        if match:
            dep = match.group(1)
            dep_dir = os.path.join(target_site_packages, dep.lower().replace("-", "_"))
            if not os.path.exists(dep_dir):
                install_package(dep, target_site_packages, modules_dir, log, visited)
                
    return True

install_success = install_package("${packageName}", "${sitePackages.absolutePath}", "${scriptDirPath}/modules", pip_logger)
"""
        return try {
            py.getModule("builtins").callAttr("exec", installerCode, mainModule.get("__dict__"))
            mainModule.get("install_success")?.toBoolean() ?: false
        } catch (e: Exception) {
            onLog("[ERROR] Installation failure: ${e.message}")
            false
        }
    }

    fun uninstallPipPackage(context: Context, packageFolder: String): Boolean {
        return try {
            val sitePackages = File(context.filesDir, "site-packages")
            val distInfoDir = File(sitePackages, packageFolder)
            if (!distInfoDir.exists()) return false

            val namePart = packageFolder.substringBefore("-").lowercase().replace("-", "_")
            val mainDir = File(sitePackages, namePart)
            if (mainDir.exists()) mainDir.deleteRecursively()
            
            distInfoDir.deleteRecursively()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getInstalledPackages(context: Context): List<String> {
        val sitePackages = File(context.filesDir, "site-packages")
        if (!sitePackages.exists() || !sitePackages.isDirectory) return emptyList()
        return sitePackages.listFiles { file -> file.name.endsWith(".dist-info") || file.name.endsWith(".egg-info") }
            ?.map { it.name.substringBefore(".dist-info").substringBefore(".egg-info") }
            ?.sorted() ?: emptyList()
    }

    private fun parseCronField(field: String, min: Int, max: Int): Set<Int> {
        val result = mutableSetOf<Int>()
        if (field == "*") {
            return (min..max).toSet()
        }
        val parts = field.split(",")
        for (part in parts) {
            if (part.contains("/")) {
                val subParts = part.split("/")
                val range = subParts[0]
                val step = subParts[1].toInt()
                val start: Int
                val end: Int
                if (range == "*") {
                    start = min
                    end = max
                } else if (range.contains("-")) {
                    val rangeParts = range.split("-")
                    start = rangeParts[0].toInt()
                    end = rangeParts[1].toInt()
                } else {
                    start = range.toInt()
                    end = max
                }
                for (i in start..end step step) {
                    result.add(i)
                }
            } else if (part.contains("-")) {
                val rangeParts = part.split("-")
                val start = rangeParts[0].toInt()
                val end = rangeParts[1].toInt()
                for (i in start..end) {
                    result.add(i)
                }
            } else {
                result.add(part.toInt())
            }
        }
        return result
    }

    fun getNextCronTime(cron: String, startCal: Calendar): Calendar {
        val fields = cron.trim().split("\\s+".toRegex())
        if (fields.size < 5) return startCal

        val minSet = parseCronField(fields[0], 0, 59)
        val hourSet = parseCronField(fields[1], 0, 23)
        val domSet = parseCronField(fields[2], 1, 31)
        val monthSet = parseCronField(fields[3], 1, 12)
        val dowSet = parseCronField(fields[4], 0, 6)

        val next = startCal.clone() as Calendar
        next.set(Calendar.SECOND, 0)
        next.set(Calendar.MILLISECOND, 0)
        next.add(Calendar.MINUTE, 1)

        for (i in 0..525600) { 
            val min = next.get(Calendar.MINUTE)
            val hour = next.get(Calendar.HOUR_OF_DAY)
            val dom = next.get(Calendar.DAY_OF_MONTH)
            val month = next.get(Calendar.MONTH) + 1
            val dow = next.get(Calendar.DAY_OF_WEEK) - 1

            val dowMatch = dowSet.contains(dow) || (fields[4] == "*")
            val domMatch = domSet.contains(dom) || (fields[2] == "*")

            if (minSet.contains(min) && hourSet.contains(hour) && monthSet.contains(month)) {
                val isDomRestricted = fields[2] != "*"
                val isDowRestricted = fields[4] != "*"
                val matchesDays = when {
                    isDomRestricted && isDowRestricted -> domMatch || dowMatch
                    isDomRestricted -> domMatch
                    isDowRestricted -> dowMatch
                    else -> true
                }
                if (matchesDays) {
                    return next
                }
            }
            next.add(Calendar.MINUTE, 1)
        }
        return startCal
    }

    fun syncAlarms(context: Context) {
        val prefs = context.getSharedPreferences("linksaver_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("script_enabled", false)
        val dbPath = prefs.getString("db_path", null)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        if (dbPath != null) {
            DatabaseManager.initDb(dbPath)
        }

        val schedules = DatabaseManager.getAllSchedules()
        
        for (schedule in schedules) {
            val intent = Intent(context, ScriptAlarmReceiver::class.java).apply {
                action = "RUN_SCHEDULED_SCRIPT"
                putExtra("SCRIPT_NAME", schedule.scriptName)
                putExtra("RUN_AS_SERVER", schedule.runAsServer)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, 
                schedule.scriptName.hashCode(), 
                intent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            alarmManager.cancel(pendingIntent)
            
            if (!enabled) continue

            val timesJson = schedule.timesJson
            val now = Calendar.getInstance()
            var nextRun: Calendar? = null

            if (timesJson.startsWith("cron:")) {
                val cronExpr = timesJson.substringAfter("cron:").trim()
                nextRun = getNextCronTime(cronExpr, now)
            } else {
                val timesArr = try { JSONArray(timesJson) } catch (e: Exception) { null }
                val times = if (timesArr != null) {
                    (0 until timesArr.length()).map { timesArr.getString(it) }
                } else emptyList()
                if (times.isEmpty()) continue

                val parsedTimes = times.map { 
                    val parts = it.split(":")
                    parts[0].toInt() to parts[1].toInt()
                }.sortedBy { it.first * 60 + it.second }

                for ((h, m) in parsedTimes) {
                    val cal = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, h)
                        set(Calendar.MINUTE, m)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    if (cal.after(now)) {
                        nextRun = cal
                        break
                    }
                }

                if (nextRun == null) {
                    val first = parsedTimes.firstOrNull()
                    if (first != null) {
                        nextRun = Calendar.getInstance().apply {
                            add(Calendar.DAY_OF_YEAR, 1)
                            set(Calendar.HOUR_OF_DAY, first.first)
                            set(Calendar.MINUTE, first.second)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                    }
                }
            }

            if (nextRun == null) continue

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, nextRun.timeInMillis, pendingIntent)
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextRun.timeInMillis, pendingIntent)
                }
            } catch (e: SecurityException) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, nextRun.timeInMillis, pendingIntent)
            }
        }
    }

    private fun sendNotification(context: Context, title: String, message: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "script_fatal_errors"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Script Errors", NotificationManager.IMPORTANCE_HIGH)
            manager.createNotificationChannel(channel)
        }
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
        manager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}

object TunnelerManager {
    fun startTunnel(context: Context, scriptName: String, port: Int, customSubdomain: String, hostInfo: String) {
        Thread {
            try {
                OutputLogger.log(scriptName, "[INFO] Preparing SSH Tunnel to $hostInfo...")
                val jsch = JSch()
                
                val user = if (hostInfo == "serveo.net") {
                    if (customSubdomain.isNotBlank()) customSubdomain else "serveo"
                } else {
                    "nokey"
                }

                val session = jsch.getSession(user, hostInfo, 22)
                session.setConfig("StrictHostKeyChecking", "no")
                session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")

                OutputLogger.log(scriptName, "[INFO] Negotiating connection with $hostInfo...")
                session.connect(30000)

                session.setPortForwardingR(80, "127.0.0.1", port)
                OutputLogger.log(scriptName, "[SUCCESS] SSH Tunnel Authenticated & Forwarded!")

                val channel = session.openChannel("shell")
                val input = BufferedReader(InputStreamReader(channel.inputStream))
                channel.connect()

                var line: String?
                while (input.readLine().also { line = it } != null) {
                    val text = line!!.trim()
                    if (text.isNotBlank()) {
                        if (text.contains("http://") || text.contains("https://")) {
                            OutputLogger.log(scriptName, "[SUCCESS] URL ALLOCATED: $text")
                            OutputLogger.log(scriptName, "========================================")
                        } else {
                            OutputLogger.log(scriptName, "[TUNNEL] $text")
                        }
                    }
                }
                OutputLogger.log(scriptName, "[INFO] Tunnel connection closed by remote host.")
            } catch (e: Exception) {
                OutputLogger.log(scriptName, "[ERROR] Tunnel failed:")
                OutputLogger.log(scriptName, e.stackTraceToString())
            }
        }.start()
    }
}