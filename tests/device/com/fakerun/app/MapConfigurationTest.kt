package com.fakerun.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baidu.mapapi.map.MapView
import com.fakerun.app.data.MapSettingsRepository
import com.fakerun.app.ui.MainActivity
import com.fakerun.app.ui.SigningIdentity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapConfigurationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext

    private fun dialogWindow(): android.view.accessibility.AccessibilityNodeInfo {
        val deadline=android.os.SystemClock.elapsedRealtime()+5_000
        while (android.os.SystemClock.elapsedRealtime()<deadline) {
            val root=instrumentation.uiAutomation.rootInActiveWindow
            if (root!=null && root.findAccessibilityNodeInfosByViewId("com.fakerun.app:id/ak_input").isNotEmpty()) return root
            Thread.sleep(50)
        }
        throw AssertionError("AK 配置对话框未出现在活动窗口")
    }

    @Test fun emptyKeyAndDialogPersistence() {
        val settings=MapSettingsRepository(context)
        val previousKey=settings.loadApiKey()
        val privacy=context.getSharedPreferences("privacy",Context.MODE_PRIVATE)
        val previousPrivacy=privacy.getBoolean("agreed",false)
        settings.clearApiKey()
        privacy.edit().putBoolean("agreed",true).commit()
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val automation=instrumentation.uiAutomation
            automation.serviceInfo=automation.serviceInfo.apply {
                flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            val metadata=context.packageManager.getApplicationInfo(context.packageName,PackageManager.GET_META_DATA).metaData
            assertTrue("安装包不得预置 AK",metadata.getString("com.baidu.lbsapi.API_KEY").isNullOrEmpty())
            val expectedSha1=InstrumentationRegistry.getArguments().getString("expectedSha1")
            if (expectedSha1!=null) assertEquals(listOf(expectedSha1),SigningIdentity.sha1Fingerprints(context))
            instrumentation.runOnMainSync {
                val host=activity.findViewById<FrameLayout>(R.id.map_host)
                assertFalse((0 until host.childCount).any {host.getChildAt(it) is MapView})
                assertTrue(activity.findViewById<android.widget.TextView>(R.id.ak_status).text.contains("尚未填写"))
                activity.findViewById<Button>(R.id.ak_help).performClick()
            }
            instrumentation.waitForIdleSync()
            // 通过对话框填写，验证用户入口、空值提示和实际私有存储。
            val root=dialogWindow()
            val input=root.findAccessibilityNodeInfosByViewId("com.fakerun.app:id/ak_input").single()
            assertTrue(input.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                android.os.Bundle().apply {putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"   ")}))
            val save=root.findAccessibilityNodeInfosByText("保存 AK").first {it.text?.toString()=="保存 AK" && it.isClickable}
            assertTrue(save.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            assertEquals("",settings.loadApiKey())
            // 不触发 SDK 鉴权，后续真实鉴权场景单独运行。
            privacy.edit().putBoolean("agreed",false).commit()
            val current=dialogWindow()
            assertTrue(current.findAccessibilityNodeInfosByViewId("com.fakerun.app:id/ak_input").single()
                .performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                    android.os.Bundle().apply {putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"  fixtureKey123  ")}))
            assertTrue(current.findAccessibilityNodeInfosByText("保存 AK").first {it.text?.toString()=="保存 AK" && it.isClickable}.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            assertEquals("fixtureKey123",MapSettingsRepository(context).loadApiKey())
            try {settings.saveApiKey("has whitespace"); fail("AK 中间空白应被拒绝")}
            catch (_: IllegalArgumentException) {assertEquals("fixtureKey123",settings.loadApiKey())}
            instrumentation.runOnMainSync {activity.findViewById<Button>(R.id.ak_help).performClick()}
            instrumentation.waitForIdleSync()
            assertTrue(dialogWindow().findAccessibilityNodeInfosByText("清除 AK").first {it.text?.toString()=="清除 AK" && it.isClickable}
                .performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            assertEquals("",settings.loadApiKey())
        } finally {
            instrumentation.runOnMainSync {activity.finish()}
            if (previousKey.isEmpty()) settings.clearApiKey() else settings.saveApiKey(previousKey)
            privacy.edit().putBoolean("agreed",previousPrivacy).commit()
        }
    }

    @Test fun runtimeApiKeyAuthenticates() {
        assumeTrue("真实鉴权需显式启用 checkRuntimeAk=true 并提供设备本地 AK 文件",
            InstrumentationRegistry.getArguments().getString("checkRuntimeAk")=="true")
        val key=instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/fakerun-test-ak.txt").use {descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use {it.readBytes().toString(Charsets.UTF_8).trim()}
        }
        require(key.isNotEmpty()) {"设备本地测试 AK 文件为空"}
        MapSettingsRepository(context).saveApiKey(key)
        context.getSharedPreferences("privacy",Context.MODE_PRIVATE).edit().putBoolean("agreed",true).commit()
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val deadline=android.os.SystemClock.elapsedRealtime()+30_000
            while (MainActivity.latestMapAuthCode==null && android.os.SystemClock.elapsedRealtime()<deadline) Thread.sleep(50)
            assertEquals("动态 AK 鉴权需成功",0,MainActivity.latestMapAuthCode)
            instrumentation.runOnMainSync {
                val host=activity.findViewById<FrameLayout>(R.id.map_host)
                assertTrue((0 until host.childCount).any {host.getChildAt(it) is MapView})
            }
        } finally {instrumentation.runOnMainSync {activity.finish()}}
    }
}
