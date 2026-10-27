package com.cloudgallery.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.cloudgallery.app.data.*

/** Mock only: exercise capture, public image download and fixture drive upload when XHS cannot share. */
class QuickImportFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as GalleryApplication).services
        val fixture = intent.getBooleanExtra("fixture", false)
        val link = if (fixture) BuildConfig.BAIDU_API + "discovery/item/000000000000000000000001" else intent.getStringExtra("link").orEmpty()
        val page = intent.getIntExtra("page", 1)
        val total = intent.getIntExtra("total", 1)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 100, 40, 40) }
        layout.addView(TextView(this).apply { text = "模拟导图流程测试\n仅使用模拟网盘，不操作真实账户\n第 $page / $total 张"; textSize = 20f })
        layout.addView(Button(this).apply {
            text = "测试导入当前图片"
            setOnClickListener {
                val account = graph.store.loadBaidu()
                val settings = graph.preferences.state.value
                if (account == null || !settings.quickImport || settings.quickDestination.isBlank() || !fixture && XhsSource.shareUrl(link) == null || page !in 1..total) return@setOnClickListener
                val id = graph.quickImports.create(account.id, settings.quickDestination, XhsImagePage(page, total), settings.quickSaveLocal)
                if (fixture) {
                    graph.quickImports.update(id, "queued", "模拟导入", link = link)
                    scheduleQuickImport(this@QuickImportFixtureActivity, id)
                    finish()
                    return@setOnClickListener
                }
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Mock XHS link", if (intent.getBooleanExtra("emptyClip", false)) "" else link))
                startActivity(Intent(this@QuickImportFixtureActivity, QuickImportCaptureActivity::class.java).putExtra("task", id))
                finish()
            }
        })
        setContentView(layout)
    }
}
