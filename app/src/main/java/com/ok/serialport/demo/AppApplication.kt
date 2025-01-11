package com.ok.serialport.demo

import android.app.Application
import android.content.res.Configuration
import me.jessyan.autosize.AutoSizeConfig

/**
 *
 * @author Leyi
 * @date 2025/1/9 14:50
 */
class AppApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        initAutoSize()
    }

    /**
     * 初始化横竖屏适配方向
     */
    private fun initAutoSize() {
        AutoSizeConfig.getInstance().setCustomFragment(true)
        //根据屏幕方向，设置适配基准
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            //设置横屏基准
            AutoSizeConfig.getInstance()
                .setDesignWidthInDp(1920)
                .setDesignHeightInDp(1080)
        } else {
            //设置竖屏基准
            AutoSizeConfig.getInstance()
                .setDesignWidthInDp(1080)
                .setDesignHeightInDp(1920)
        }
    }
}