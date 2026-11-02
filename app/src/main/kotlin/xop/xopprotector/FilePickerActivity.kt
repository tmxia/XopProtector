package xop.xopprotector

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.widget.ListView
import java.io.File

/**
 * 内置选卷器：浏览手机目录，点选 APK 返回绝对路径。
 * 依赖「所有文件」权限（MANAGE_EXTERNAL_STORAGE）；也可回退系统选择器。
 */
class FilePickerActivity : AppCompatActivity() {

    private lateinit var cardPerm: View
    private lateinit var txtPath: TextView
    private lateinit var listFiles: ListView

    private var cwd = File("/sdcard/Download")

    private val entries = mutableListOf<Entry>()

    class Entry(val name: String, val sub: String, val file: File, val isDir: Boolean)

    private val adapter = object : android.widget.BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_fs_entry, parent, false)
            val e = entries[position]
            v.findViewById<TextView>(R.id.txtName).text =
                if (e.isDir) "📁 " + e.name else "📦 " + e.name
            v.findViewById<TextView>(R.id.txtSub).text = e.sub
            return v
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_picker)

        cardPerm = findViewById(R.id.cardPerm)
        txtPath = findViewById(R.id.txtPath)
        listFiles = findViewById(R.id.listFiles)

        findViewById<TextView>(R.id.btnGrant).setOnClickListener { grantAllFiles() }
        findViewById<TextView>(R.id.btnUp).setOnClickListener { goUp() }
        findViewById<TextView>(R.id.btnSaf).setOnClickListener { launchSaf() }
        findViewById<TextView>(R.id.chipDownload).setOnClickListener { enter("Download") }
        findViewById<TextView>(R.id.chipDcim).setOnClickListener { enter("DCIM") }
        findViewById<TextView>(R.id.chipBluetooth).setOnClickListener { enter("Bluetooth") }

        listFiles.adapter = adapter
        listFiles.setOnItemClickListener { _, _, position, _ ->
            val e = entries.getOrNull(position) ?: return@setOnItemClickListener
            if (e.isDir) {
                cwd = e.file
                refresh()
            } else {
                // 返回选中的 APK 绝对路径
                setResult(Activity.RESULT_OK, Intent().putExtra("apk_path", e.file.absolutePath))
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun hasAllFiles(): Boolean = Environment.isExternalStorageManager()

    private fun grantAllFiles() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun launchSaf() {
        // 回退系统选择器：把 SAF 结果交给 MainActivity 处理
        setResult(RESULT_USE_SAF)
        finish()
    }

    private fun enter(name: String) {
        val f = File(Environment.getExternalStorageDirectory(), name)
        if (f.isDirectory) {
            cwd = f
            refresh()
        } else {
            Toast.makeText(this, "目录不存在: " + f.absolutePath, Toast.LENGTH_SHORT).show()
        }
    }

    private fun goUp() {
        val parent = cwd.parentFile ?: return
        if (parent.absolutePath == "/" || parent.absolutePath == "/storage" ||
            parent.absolutePath == "/storage/emulated") {
            // 不越过存储根
        }
        if (cwd.absolutePath != "/storage/emulated/0" && cwd.absolutePath != "/sdcard") {
            cwd = parent
            refresh()
        }
    }

    @SuppressLint("SdCardPath")
    private fun refresh() {
        val allow = hasAllFiles()
        cardPerm.visibility = if (allow) View.GONE else View.VISIBLE
        txtPath.text = cwd.absolutePath
        entries.clear()
        if (!allow) {
            adapter.notifyDataSetChanged()
            return
        }
        val kids = cwd.listFiles() ?: run {
            Toast.makeText(this, "无法读取目录", Toast.LENGTH_SHORT).show()
            adapter.notifyDataSetChanged()
            return
        }
        val dirs = kids.filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
        val apks = kids.filter { it.isFile && it.name.endsWith(".apk", true) }
            .sortedBy { it.name.lowercase() }
        for (d in dirs) {
            val count = d.listFiles()?.size ?: 0
            entries.add(Entry(d.name, count.toString() + " 项", d, true))
        }
        for (f in apks) {
            entries.add(Entry(f.name, describeApk(f), f, false))
        }
        adapter.notifyDataSetChanged()
    }

    private fun describeApk(f: File): String {
        val sizeMb = "%.1f MB".format(f.length() / 1048576.0)
        return try {
            val pm = packageManager
            val info = pm.getPackageArchiveInfo(f.absolutePath, 0)
            if (info != null) {
                val label = info.applicationInfo?.loadLabel(pm) ?: f.name
                "$label · v" + info.versionName + " · " + sizeMb
            } else {
                sizeMb
            }
        } catch (e: Exception) {
            sizeMb
        }
    }

    companion object {
        /** MainActivity 收到此结果时改用系统选择器。 */
        const val RESULT_USE_SAF = 77
    }
}
