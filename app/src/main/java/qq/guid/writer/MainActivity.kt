package qq.guid.writer

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import qq.guid.writer.databinding.ActivityMainBinding
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var cachedSuTemplate: Array<String>? = null

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.forgeButton.setOnClickListener {
            val guid = binding.guidEditText.text.toString().trim()
            if (guid.isEmpty()) {
                addUiLog("[ERROR] GUID 不能为空，请输入有效值。")
                return@setOnClickListener
            }
            startForgingProcess(guid)
        }

        binding.btnGetter.setOnClickListener {
            startReadGuidProcess()
        }
    }

    /**
     * 启动读取 GUID 流程
     */
    @SuppressLint("SdCardPath")
    @RequiresApi(Build.VERSION_CODES.O)
    private fun startReadGuidProcess() {
        binding.forgeButton.isEnabled = false
        binding.btnGetter.isEnabled = false
        binding.logTextView.text = ""

        lifecycleScope.launch {
            addLog("--- [读取进程开始] 正在从缓存读取 GUID... ---")
            try {
                // 停止 QQ，避免占用/并发写
                executeRootCommandCompat("am force-stop com.tencent.mobileqq")

                val path = "/data/data/com.tencent.mobileqq/files/wlogin_device.dat"
                val guidBytes = readFileWithRootCompat(path)

                if (guidBytes != null && guidBytes.isNotEmpty()) {
                    val guidHex = bytesToHexString(guidBytes)
                    addLog("[OK] 读取成功！")
                    addLog("[INFO] 解码后的 GUID: $guidHex")
                    withContext(Dispatchers.Main) {
                        binding.guidEditText.setText(guidHex)
                    }
                } else {
                    addLog("\nx --- [读取失败] 未找到 $path 文件或文件为空。请确保 QQ 已登录。 --- x")
                }
            } catch (e: Exception) {
                addLog("\nx --- [读取失败] ${e.message} --- x")
            } finally {
                withContext(Dispatchers.Main) {
                    binding.forgeButton.isEnabled = true
                    binding.btnGetter.isEnabled = true
                }
            }
        }
    }

    /**
     * 启动写入/写入 GUID 流程
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun startForgingProcess(guid: String) {
        binding.forgeButton.isEnabled = false
        binding.btnGetter.isEnabled = false
        binding.logTextView.text = ""

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    addLog("\n--- [写入进程开始] GUID 写入流程启动 ---")
                    addLog("[INFO] 目标 GUID: $guid")

                    // 改动点：第一步清数据
                    addLog("\n[步骤 1/6] 清除 QQ 数据...")
                    executeRootCommandCompat("pm clear com.tencent.mobileqq")

                    // 编码 GUID
                    addLog("\n[步骤 2/6] 编码 GUID 并准备写入...")
                    val encodedData = encodeGuidForQQ(guid)
                        ?: throw IOException("GUID 编码失败，请输入有效的 16 进制字符串。")
                    addLog("[INFO] GUID 编码完成。")

                    // 准备目录
                    addLog("\n[步骤 3/6] 创建 QQ 私有数据目录...")
                    val qqDir = "/data/data/com.tencent.mobileqq/files"
                    executeRootCommandCompat("mkdir -p $qqDir && chmod 771 /data/data/com.tencent.mobileqq && chmod 771 $qqDir")

                    // 写入 wlogin_device.dat
                    addLog("\n[步骤 4/6] 以 root 覆盖写入 wlogin_device.dat（base64 方式）...")
                    val target = "$qqDir/wlogin_device.dat"
                    val b64 = android.util.Base64.encodeToString(encodedData, android.util.Base64.NO_WRAP)
                    val writeCmd = """
                    (which base64 >/dev/null 2>&1 && echo '$b64' | base64 -d > '$target') \
                    || (which toybox >/dev/null 2>&1 && echo '$b64' | toybox base64 -d > '$target') \
                    || (which openssl >/dev/null 2>&1 && echo '$b64' | openssl base64 -d -A > '$target')
                """.trimIndent()
                    val wExit = executeRootCommandCompat(writeCmd)
                    if (wExit != 0) {
                        throw IOException("写入 wlogin_device.dat 失败，exit=$wExit")
                    }

                    // 修正属主/权限/SELinux 标签
                    addLog("\n[步骤 5/6] 修正属主/权限/SELinux 标签...")
                    // 这里直接用粗暴方式，确保 QQ 能正常访问
                    executeRootCommandCompat("chown -R system:system /data/data/com.tencent.mobileqq")
                    executeRootCommandCompat("chmod -R 777 /data/data/com.tencent.mobileqq")
                    // 恢复 SELinux 上下文
                    val restoreCmd = """
                    (which restorecon >/dev/null 2>&1 && restorecon -v '$target') \
                    || (which toybox >/dev/null 2>&1 && toybox restorecon -v '$target') \
                    || echo 'restorecon not found, skipped'
                """.trimIndent()
                    executeRootCommandCompat(restoreCmd)

                    addLog("\n🎉 --- [进程成功] GUID 写入完成！请启动 QQ 验证 --- 🎉")
                }
            } catch (e: Exception) {
                addLog("\nx --- [进程失败] ${e.message} --- x")
            } finally {
                withContext(Dispatchers.Main) {
                    binding.forgeButton.isEnabled = true
                    binding.btnGetter.isEnabled = true
                }
            }
        }
    }


    // ---------------------- 日志辅助 ----------------------

    private suspend fun addLog(message: String) {
        withContext(Dispatchers.Main) {
            binding.logTextView.append("$message\n")
            binding.logScrollView.post { binding.logScrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun addUiLog(message: String) {
        binding.logTextView.append("$message\n")
        binding.logScrollView.post { binding.logScrollView.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------------------- Root 适配层 ----------------------

    /**
     * 探测可用 su 模板并缓存。模板形如 ["su","-mm","-c","%s"]，其中 %s 会替换为实际命令。
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun detectSuTemplate(): Array<String>? = withContext(Dispatchers.IO) {
        cachedSuTemplate?.let { return@withContext it }

        val candidates = listOf(
            arrayOf("su", "-mm", "-c", "%s"),                     // Magisk 挂载 master
            arrayOf("su", "-c", "%s"),                            // 通用
            arrayOf("su", "-s", "/system/bin/sh", "-c", "%s"),    // 某些 ROM 需要 -s 指定 shell
            arrayOf("su", "-c", "sh -c \"%s\""),                  // 间接调用 sh
            arrayOf("su", "-p", "-c", "%s")                       // 个别 su 支持 -p
        )

        for (tmpl in candidates) {
            val testCmd = "echo SU_OK"
            val cmdArray = buildCommandArray(tmpl, testCmd)
            try {
                val (exit, out, _) = runProcessCapture(cmdArray, 3000)
                if (exit == 0 && out?.contains("SU_OK") == true) {
                    cachedSuTemplate = tmpl
                    return@withContext tmpl
                }
            } catch (_: Exception) {
                // 忽略，尝试下一个
            }
        }
        null
    }

    private fun buildCommandArray(template: Array<String>, actualCmd: String): Array<String> {
        return template.map { it.replace("%s", actualCmd) }.toTypedArray()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun runProcessCapture(cmdArray: Array<String>, timeoutMs: Long = 10000): Triple<Int, String?, String?> {
        val process = Runtime.getRuntime().exec(cmdArray)
        val stdoutBuf = StringBuilder()
        val stderrBuf = StringBuilder()

        val tOut = Thread {
            BufferedReader(InputStreamReader(process.inputStream)).use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    stdoutBuf.append(line).append('\n')
                }
            }
        }
        val tErr = Thread {
            BufferedReader(InputStreamReader(process.errorStream)).use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    stderrBuf.append(line).append('\n')
                }
            }
        }
        tOut.start(); tErr.start()

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            tOut.join(50); tErr.join(50)
            return Triple(-124, stdoutBuf.toString(), stderrBuf.toString()) // -124: 超时
        }
        tOut.join(); tErr.join()
        return Triple(process.exitValue(), stdoutBuf.toString(), stderrBuf.toString())
    }

    /**
     * 统一的 root 命令执行（自动探测 su 模式）
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun executeRootCommandCompat(command: String): Int {
        addLog("[CMD] # $command")
        return withContext(Dispatchers.IO) {
            val tmpl = detectSuTemplate()
            if (tmpl == null) {
                withContext(Dispatchers.Main) { addUiLog("[ERROR] 未检测到可用的 su，请确认已安装并授权。") }
                return@withContext -1
            }
            val cmdArray = buildCommandArray(tmpl, command)
            try {
                val (exit, _, err) = runProcessCapture(cmdArray, 20000)
                withContext(Dispatchers.Main) {
                    if (exit == 0) addUiLog("[OK] 命令成功执行。")
                    else {
                        addUiLog("[ERROR] 命令执行失败，退出码: $exit")
                        if (!err.isNullOrBlank()) addUiLog("[ERR] $err")
                    }
                }
                exit
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { addUiLog("[ERROR] 执行异常: ${e.message}") }
                -2
            }
        }
    }

    /**
     * 以 root 读取文件（优先 base64 读取，保证二进制安全；失败再 cat）
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun readFileWithRootCompat(filePath: String): ByteArray? {
        addLog("[CMD] # read $filePath (via root)")
        return withContext(Dispatchers.IO) {
            val tmpl = detectSuTemplate() ?: run {
                withContext(Dispatchers.Main) { addUiLog("[ERROR] 未检测到 su，请确认已安装并授权。") }
                return@withContext null
            }

            // 优先 base64
            val cmd = "which base64 >/dev/null 2>&1 && base64 -w0 '$filePath' || (which openssl >/dev/null 2>&1 && openssl base64 -in '$filePath' -A) || (which toybox >/dev/null 2>&1 && toybox base64 '$filePath')"
            val cmdArray = buildCommandArray(tmpl, cmd)
            val (exit, stdout, stderr) = runProcessCapture(cmdArray, 15000)
            if (exit == 0 && !stdout.isNullOrBlank()) {
                try {
                    return@withContext android.util.Base64.decode(stdout.trim(), android.util.Base64.DEFAULT)
                } catch (_: Exception) {
                    // 继续尝试 cat
                }
            }

            // 退回直接 cat（注意：某些环境 cat 二进制会受编码影响，此为最后手段）
            val catCmdArray = buildCommandArray(tmpl, "cat '$filePath'")
            val (exit2, out2, err2) = runProcessCapture(catCmdArray, 10000)
            if (exit2 == 0 && out2 != null) {
                return@withContext out2.toByteArray(Charsets.ISO_8859_1)
            }

            withContext(Dispatchers.Main) { addUiLog("[ERROR] 以 root 读取失败: exit=$exit stderr=$stderr ; fallbackExit=$exit2 err=$err2") }
            null
        }
    }

    // ---------------------- 业务工具 ----------------------

    private fun encodeGuidForQQ(guid: String): ByteArray? {
        if (!guid.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return null
        }
        val cleanGuid = if (guid.length % 2 != 0) "0$guid" else guid
        return try {
            cleanGuid.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    private fun bytesToHexString(bytes: ByteArray): String {
        val hexChars = "0123456789ABCDEF"
        val result = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            result.append(hexChars[v ushr 4]).append(hexChars[v and 0x0F])
        }
        return result.toString()
    }
}