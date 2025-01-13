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
import com.ok.serialport.OkSerialPort
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

            val byteArr = getData()
            if (byteArr != null) {
                job = lifecycleScope.launch {
                    while (isActive) {
                        delay(500)
                        withContext(Dispatchers.Main) {
                            val request = Request(byteArr)
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
                            addLog("发送", ByteUtils.byteArrToHexStr(response.data))
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
                            addLog("发送", ByteUtils.byteArrToHexStr(response.data))
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
        binding.etCommand.setText("AA 55 02 1E 1F")
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
            binding.tvOpenState.text = "开启"
            binding.viewOpenState.setBackgroundColor(Color.RED)
            return
        }
        serialClient = OkSerialPort.Builder()
            .devicePath(devicePath!!)
            .baudRate(baudRate!!)
            .sendInterval(300)
            .addResponseRule(object : ResponseRule {
                override fun match(request: Request?, receive: ByteArray): Boolean {
                    request?.let {
                        return receive.size >= 6 && it.data()[3] == receive[3]
                    } ?: run {
                        return false
                    }
                }
            })
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
                Log.i("Ok-Serial", "onRequest:${ByteUtils.byteArrToHexStr(data)}")
                addLog("发送", ByteUtils.byteArrToHexStr(data))
            }

            override fun onResponse(data: ByteArray) {
                Log.i("Ok-Serial", "onResponse:${ByteUtils.byteArrToHexStr(data)}")
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
        binding.spDevice.setSelection(7)
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