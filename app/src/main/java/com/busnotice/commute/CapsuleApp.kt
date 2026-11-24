package com.busnotice.commute

import android.app.Application

class CapsuleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Transit.init(this)
        ChinaWorkdays.init(this)
        // 家/公司识别已移除：清掉历史采样坐标
        deleteSharedPreferences("places")
    }
}
