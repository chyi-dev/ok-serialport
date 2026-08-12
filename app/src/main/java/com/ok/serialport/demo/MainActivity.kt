package com.ok.serialport.demo

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.blankj.utilcode.util.TimeUtils
import com.chad.library.adapter4.BaseQuickAdapter
import com.chad.library.adapter4.viewholder.QuickViewHolder
import com.elvishew.xlog.XLog
import com.ok.serialport.OkSerialPort
import com.ok.serialport.data.AckNakConfig
import com.ok.serialport.data.Request
import com.ok.serialport.data.Response
import com.ok.serialport.data.ResponseRule
import com.ok.serialport.demo.databinding.ActivityMainBinding
import com.ok.serialport.jni.SerialPortFinder
import com.ok.serialport.listener.OnConnectListener
import com.ok.serialport.listener.OnDataListener
import com.ok.serialport.listener.OnResponseListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 *
 * @author Leyi
 * @date 2025/1/9 14:50
 */
class MainActivity : AppCompatActivity() {

    private var job: Job? = null
    private lateinit var adapter: BaseQuickAdapter<LogBean, QuickViewHolder>
    private lateinit var binding: ActivityMainBinding
    private val serialPortFinder by lazy {
        SerialPortFinder()
    }
    private var devicePath: String? = "/dev/ttyS7"
    private var baudRate: Int? = 9600
    private var serialClient: OkSerialPort? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initView()
    }

    private fun initView() {
        initLog()
        initDevice()
        initBaudRate()

        binding.btnOpen.setOnClickListener {
            if (devicePath == null || baudRate == null) {
                Toast.makeText(this, "请选择串口和波特率", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            openSerialPort()
        }

        binding.btnSend.setOnClickListener {
            val byteArr = getData()
            if (byteArr != null) {
                val request = Request(byteArr)
                serialClient?.request(request)
            }
        }

        binding.btnTimingSend.setOnClickListener {
            if (job != null) {
                job?.cancel()
                job = null
                binding.btnTimingSend.text = "循环发送"
                return@setOnClickListener
            }
            Toast.makeText(
                this@MainActivity,
                "当前为非阻塞式",
                Toast.LENGTH_LONG
            ).show()
            val byteArr = getData()
            if (byteArr != null) {
                job = lifecycleScope.launch {
                    while (isActive) {
                        delay(50)
                        withContext(Dispatchers.Main) {
                            val request = Request(byteArr)
//                                .blocking()
                            serialClient?.request(request)
                        }
                    }
                }
                binding.btnTimingSend.text = "执行中 - 点击停止"
            }
        }

        binding.btnClear.setOnClickListener {
            adapter.submitList(emptyList())
        }

        binding.btnTimeout.setOnClickListener {
            val byteArr = getData()
            if (byteArr != null) {
                val request = Request(byteArr)
                    .addResponseRule(object : ResponseRule {
                        override fun match(request: Request?, receive: ByteArray): Boolean {
                            return receive.size >= 9 && receive[3] == 0x1E.toByte()
                        }
                    })
                    .onResponseListener(object : OnResponseListener {
                        override fun onResponse(response: Response) {
                        }

                        override fun onFailure(request: Request?, e: Exception) {
                            addLog("响应", "失败：${e.message}")
                            Toast.makeText(
                                this@MainActivity,
                                "失败：${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    })
                serialClient?.request(request)
            }
        }

        binding.btnTimeoutRetry.setOnClickListener {
            val byteArr = getData()
            if (byteArr != null) {
                val request = Request(byteArr)
                    .timeoutRetry(3)
                    .addResponseRule(object : ResponseRule {
                        override fun match(request: Request?, receive: ByteArray): Boolean {
                            return receive.size >= 9 && receive[3] == 0x1E.toByte()
                        }
                    })
                    .onResponseListener(object : OnResponseListener {
                        override fun onResponse(response: Response) {
                        }

                        override fun onFailure(request: Request?, e: Exception) {
                            addLog("响应", "失败：${e.message}")
                            Toast.makeText(
                                this@MainActivity,
                                "失败：${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    })
                serialClient?.request(request)
            }
        }

        binding.btnResponseCount.setOnClickListener {
            val byteArr = getData()
            if (byteArr != null) {
                val request = Request(byteArr)
                    .responseCount(3)
                    .addResponseRule(object : ResponseRule {
                        override fun match(request: Request?, receive: ByteArray): Boolean {
                            return receive.size >= 9 && receive[3] == 0x1E.toByte()
                        }
                    })
                    .onResponseListener(object : OnResponseListener {
                        override fun onResponse(response: Response) {
                            Log.i("Ok-Serial", "response onResponse:${response.toHex()}")
                        }

                        override fun onFailure(request: Request?, e: Exception) {
                            Log.i("Ok-Serial", "response onFailure:${e.message}")
                            addLog("响应", "失败：${e.message}")
                        }
                    })
                serialClient?.request(request)
            }
        }

        binding.btnBlocking.setOnClickListener {
            val byteArr = getData()
            if (byteArr != null) {
                val request = Request(byteArr)
                    .blocking()
                    .timeout(1500)
                    .timeoutRetry(2)
                    .addResponseRule(object : ResponseRule {
                        override fun match(request: Request?, receive: ByteArray): Boolean {
                            return receive.size >= 9 && receive[3] == 0x1E.toByte()
                        }
                    })
                    .onResponseListener(object : OnResponseListener {
                        override fun onResponse(response: Response) {
                            addLog("发送", ByteUtils.byteArrToHexStr(response.data))
                            Log.i("Ok-Serial", "Blocking onResponse:${response.toHex()}")
                        }

                        override fun onFailure(request: Request?, e: Exception) {
                            Log.i("Ok-Serial", "Blocking onFailure:${e.message}")
                            addLog("响应", "失败：${e.message}")
                        }
                    })
                serialClient?.request(request)
            }
        }

        // ACK/NAK示例 - 带数据等待
        binding.btnAckNakWithData?.setOnClickListener {
            val byteArr = getData()
            if (byteArr != null) {
                // 配置ACK/NAK，假设：ACK=0x06，NAK=0x15，等待数据响应
                val request = Request(byteArr)
                    .tag("ACK-NAK-WithData")
                    .ackNakConfig(
                        AckNakConfig.Builder()
                            .ackRule { data -> data.isNotEmpty() && data[0] == 0x06.toByte() }
                            .nakRule { data -> data.isNotEmpty() && data[0] == 0x15.toByte() }
                            .waitData(true)              // 收到ACK后继续等待数据
                            .ackTimeout(1000L)           // ACK超时1秒
                            .ackRetryCount(3)            // ACK失败重试3次
                            .dataTimeout(3000L)          // 数据超时3秒
                            .build()
                    )
                    .addResponseRule(object : ResponseRule {
                        override fun match(request: Request?, receive: ByteArray): Boolean {
                            // 数据响应规则（排除ACK/NAK）
                            return receive.size >= 5 && receive[0] != 0x06.toByte() && receive[0] != 0x15.toByte()
                        }
                    })
                    .onResponseListener(object : OnResponseListener {
                        override fun onAckReceived(request: Request) {
                            Log.i("Ok-Serial", "ACK-NAK: 收到ACK确认")
                            addLog("ACK/NAK", "收到ACK确认，继续等待数据...")
                        }

                        override fun onNakReceived(request: Request) {
                            Log.i("Ok-Serial", "ACK-NAK: 收到NAK，将自动重试")
                            addLog("ACK/NAK", "收到NAK，触发ACK重试")
                        }

                        override fun onDataReceived(response: Response) {
                            Log.i("Ok-Serial", "ACK-NAK: 收到数据: ${response.toHex()}")
                            addLog("ACK/NAK数据", response.toHex())
                        }

                        override fun onResponse(response: Response) {
                            // 在非ACK/NAK模式下会调用，这里不会调用（因为有onDataReceived）
                        }

                        override fun onFailure(request: Request?, e: Exception) {
                            Log.i("Ok-Serial", "ACK-NAK: 失败: ${e.message}")
                            addLog("ACK/NAK", "失败: ${e.message}")
                            Toast.makeText(this@MainActivity, "ACK/NAK失败: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    })
                serialClient?.request(request)
            }
        }

        // ACK/NAK示例 - 不等待数据（收到ACK即完成）
        binding.btnAckNakNoData?.setOnClickListener {
            val byteArr = getData()
            if (byteArr != null) {
                // 配置ACK/NAK，假设：ACK=0x06，NAK=0x15，不等待数据
                val request = Request(byteArr)
                    .tag("ACK-NAK-NoData")
                    .ackNakConfig(
                        AckNakConfig.Builder()
                            .ackRule { data -> data.isNotEmpty() && data[0] == 0x06.toByte() }
                            .nakRule { data -> data.isNotEmpty() && data[0] == 0x15.toByte() }
                            .waitData(false)             // 收到ACK即完成，不等待数据
                            .ackTimeout(1000L)           // ACK超时1秒
                            .ackRetryCount(2)            // ACK失败重试2次
                            .build()
                    )
                    .onResponseListener(object : OnResponseListener {
                        override fun onAckReceived(request: Request) {
                            Log.i("Ok-Serial", "ACK-NAK-NoData: 收到ACK确认，流程完成")
                            addLog("ACK/NAK", "收到ACK确认（不等待数据），流程完成")
                        }

                        override fun onNakReceived(request: Request) {
                            Log.i("Ok-Serial", "ACK-NAK-NoData: 收到NAK，将自动重试")
                            addLog("ACK/NAK", "收到NAK，触发重试")
                        }

                        override fun onResponse(response: Response) {
                            // waitData=false时，onAckReceived已经处理完成，这里不会调用
                        }

                        override fun onFailure(request: Request?, e: Exception) {
                            Log.i("Ok-Serial", "ACK-NAK-NoData: 失败: ${e.message}")
                            addLog("ACK/NAK", "失败: ${e.message}")
                            Toast.makeText(this@MainActivity, "ACK/NAK失败: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    })
                serialClient?.request(request)
            }
        }

        binding.etCommand.setText("AA 55 02 1E 1F")

        binding.btnPerformanceStats.setOnClickListener {
            val stats = serialClient?.getPerformanceStats()
            stats?.let {
                binding.tvPerformanceStats.text =
                    "发送请求数:${it.totalSentRequests} " +
                            "成功数:${it.successCount} " +
                            "失败数:${it.failureCount} " +
                            "超时数:${it.timeoutCount} " +
                            "平均响应时间:${it.averageResponseTime} " +
                            "总发送字节数:${it.totalSentBytes} " +
                            "总接收字节数:${it.totalReceivedBytes} " +
                            "最大响应时间:${it.maxResponseTime} " +
                            "最小响应时间:${it.minResponseTime} " +
                            "当前待发送队列大小:${it.currentQueueSize} " +
                            "当前运行中请求数:${it.currentRunningRequests} " +
                            "统计开始时间:${TimeUtils.millis2String(it.startTime)} " +
                            "最后更新时间:${TimeUtils.millis2String(it.lastUpdateTime)} "

                XLog.i(binding.tvPerformanceStats.text.toString())
            }
        }
    }

    private fun getData(): ByteArray? {
        val isConnect = serialClient?.isConnect() ?: false
        if (!isConnect) {
            Toast.makeText(this, "请开启串口", Toast.LENGTH_LONG).show()
            return null
        }
        val data = binding.etCommand.text.toString()
        if (TextUtils.isEmpty(data)) {
            Toast.makeText(this, "请输入命令", Toast.LENGTH_LONG).show()
            return null
        }
        val byteArr = ByteUtils.strToByte(data)
        if (byteArr == null) {
            Toast.makeText(this, "请输入合理命令", Toast.LENGTH_LONG).show()
            return null
        }
        return byteArr
    }

    private fun openSerialPort() {
        if (serialClient?.isConnect() == true) {
            serialClient?.disconnect()
            serialClient = null
            binding.tvOpenState.text = "开启"
            binding.viewOpenState.setBackgroundColor(Color.RED)
            return
        }
        // 未连接时也可能有重连任务在跑，先停掉旧实例再重建
        serialClient?.disconnect()
        serialClient = OkSerialPort.Builder()
            .devicePath(devicePath!!)
            .baudRate(baudRate!!)
            .sendInterval(10)
            .enablePerformanceStats(true)
//            .addRequestInterceptor(RequestInterceptor())
//            .addResponseInterceptor(ResponseInterceptor())
//            .addResponseRule(object : ResponseRule {
//                override fun match(request: Request?, receive: ByteArray): Boolean {
//                    request?.let {
//                        return receive.size >= 6 && it.data[3] == receive[3]
//                    } ?: run {
//                        return false
//                    }
//                }
//            })
            .build()
        serialClient?.addConnectListener(object : OnConnectListener {
            override fun onConnect(devicePath: String) {
                binding.tvOpenState.text = "关闭"
                binding.viewOpenState.setBackgroundColor(Color.GREEN)
                Log.i("Ok-Serial", "${devicePath}连接成功")
            }

            override fun onDisconnect(devicePath: String, errorMag: Throwable?) {
                binding.tvOpenState.text = "开启"
                binding.viewOpenState.setBackgroundColor(Color.RED)
                Log.i("Ok-Serial", "${devicePath}连接失败：${errorMag}")
            }
        })

        serialClient?.addDataListener(object : OnDataListener {
            override fun onRequest(data: ByteArray) {
                XLog.i("onRequest:${ByteUtils.byteArrToHexStr(data)}")
                addLog("发送", ByteUtils.byteArrToHexStr(data))
            }

            override fun onResponse(data: ByteArray) {
                XLog.i("onResponse:${ByteUtils.byteArrToHexStr(data)}")
                addLog("响应", ByteUtils.byteArrToHexStr(data))
            }
        })
        serialClient?.connect()
    }

    fun addLog(name: String, data: String) {
        adapter.add(
            LogBean(
                TimeUtils.getNowString(),
                devicePath!!,
                name,
                data
            )
        )
        binding.rvLog.smoothScrollToPosition(adapter.itemCount - 1)
        if (adapter.itemCount > 100) {
            adapter.submitList(emptyList())
        }
    }

    private fun initLog() {
        adapter = object : BaseQuickAdapter<LogBean, QuickViewHolder>() {

            override fun onCreateViewHolder(
                context: Context,
                parent: ViewGroup,
                viewType: Int
            ): QuickViewHolder {
                return QuickViewHolder(R.layout.item_log, parent)
            }

            override fun onBindViewHolder(
                holder: QuickViewHolder,
                position: Int,
                item: LogBean?
            ) {
                if ("发送".equals(item?.name)) {
                    holder.setTextColor(R.id.tv_name, Color.parseColor("#F27E02"))
                } else {
                    holder.setTextColor(R.id.tv_name, Color.parseColor("#A82BE2"))
                }
                holder.setText(R.id.tv_path, item?.path)
                    .setText(R.id.tv_name, item?.name)
                    .setText(R.id.tv_time, item?.time)
                    .setText(R.id.tv_data, item?.data)
            }
        }
        adapter.animationEnable = true
        adapter.isStateViewEnable = false
        binding.rvLog.layoutManager = LinearLayoutManager(this)
        binding.rvLog.adapter = adapter
    }

    private fun initDevice() {
        val devices = serialPortFinder.getDevices()
        val deviceNameList = mutableListOf<String>()
        devices.forEach {
            deviceNameList.add(it.file.path)
        }
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_item)
        adapter.addAll(deviceNameList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spDevice.adapter = adapter

        binding.spDevice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                devicePath = adapter.getItem(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                devicePath = null
            }
        }
    }

    private fun initBaudRate() {
        val devices = serialPortFinder.getDevices()
        val deviceNameList = mutableListOf<String>()
        devices.forEach {
            deviceNameList.add(it.name)
        }
        val adapter = ArrayAdapter.createFromResource(
            this,
            R.array.baudrates,
            android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spBaudRate.adapter = adapter

        binding.spBaudRate.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                baudRate = adapter.getItem(position).toString().toInt()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                baudRate = null
            }
        }
        binding.spBaudRate.setSelection(6)
    }
}