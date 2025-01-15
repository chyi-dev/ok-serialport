package com.ok.serialport.demo

import android.app.Application
import android.content.res.Configuration
import com.elvishew.xlog.LogConfiguration
import com.elvishew.xlog.LogLevel
import com.elvishew.xlog.XLog
import com.elvishew.xlog.flattener.PatternFlattener
import com.elvishew.xlog.printer.AndroidPrinter
import com.elvishew.xlog.printer.Printer
import com.elvishew.xlog.printer.file.FilePrinter
import com.elvishew.xlog.printer.file.backup.NeverBackupStrategy
import com.elvishew.xlog.printer.file.clean.FileLastModifiedCleanStrategy
import com.elvishew.xlog.printer.file.naming.FileNameGenerator
import me.jessyan.autosize.AutoSizeConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 *
 * @author Leyi
 * @date 2025/1/9 14:50
 */
class AppApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        initAutoSize()
        initLog()
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

    /**
     * 初始化日志框架
     */
    private fun initLog() {
        val config = LogConfiguration.Builder()
            .logLevel(LogLevel.ALL) // 指定日志级别，低于该级别的日志将不会被打印，默认为 LogLevel.ALL
            .tag("ok-demo") // 指定 TAG，默认为 "X-LOG"
            .build()

        val androidPrinter: Printer = AndroidPrinter(true) // 通过 android.util.Log 打印日志的打印器
        val filePrinter: Printer = FilePrinter.Builder(Constant.LOG_PATH) // 指定保存日志文件的路径
            .flattener(PatternFlattener("{d} {l}/{t}: {m}")) // 自定义日志格式
            .fileNameGenerator(DateFileNameGenerator()) // 指定日志文件名生成器，默认为 ChangelessFileNameGenerator("log")
            .backupStrategy(NeverBackupStrategy()) // 指定日志文件备份策略，默认为 FileSizeBackupStrategy(1024 * 1024)
            .cleanStrategy(FileLastModifiedCleanStrategy((1000 * 60 * 60 * 24 * 7).toLong())) // 指定日志文件清除策略，默认为 NeverCleanStrategy()
            .build()

        if (!BuildConfig.DEBUG) {
            XLog.init( // 初始化 XLog
                config,  // 指定日志配置，如果不指定，会默认使用 new LogConfiguration.Builder().build()
                androidPrinter,  // 添加任意多的打印器。如果没有添加任何打印器，会默认使用 AndroidPrinter(Android)/ConsolePrinter(java)
                filePrinter
            )
        } else {
            XLog.init(
                // 初始化 XLog
                config,  // 指定日志配置，如果不指定，会默认使用 new LogConfiguration.Builder().build()
                androidPrinter,  // 添加任意多的打印器。如果没有添加任何打印器，会默认使用 AndroidPrinter(Android)/ConsolePrinter(java)
            )
        }
    }
}

class DateFileNameGenerator : FileNameGenerator {
    var mLocalDateFormat: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            return SimpleDateFormat("yyyy-MM-dd", Locale.US)
        }
    }

    override fun isFileNameChangeable(): Boolean {
        return true
    }

    /**
     * Generate a file name which represent a specific date.
     */
    override fun generateFileName(logLevel: Int, timestamp: Long): String {
        val sdf = mLocalDateFormat.get()
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(Date(timestamp)) + ".txt"
    }
}