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
import com.ok.serialport.OkSerialClient
import com.ok.serialport.demo.databinding.ActivityMainBinding
import com.ok.serialport.jni.SerialPortFinder
import com.ok.serialport.listener.OnConnectListener
import com.ok.serialport.listener.OnDataListener
import com.ok.serialport.model.SerialRequest
import com.ok.serialport.model.SerialResponse
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
    private var devicePath: String? = null
    private var baudRate: Int? = null
    private var serialClient: OkSerialClient? = null

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
            val isConnect = serialClient?.isConnect() ?: false
            if (!isConnect) {
                Toast.makeText(this, "请开启串口", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val data = binding.etCommand.text.toString()
            if (TextUtils.isEmpty(data)) {
                Toast.makeText(this, "请输入命令", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val byteArr = ByteUtils.strToByte(data)
            if (byteArr == null) {
                Toast.makeText(this, "请输入合理命令", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val request = object : SerialRequest(byteArr) {
                override fun process(bytes: ByteArray): Boolean {
                    return true
                }

                override fun response(response: SerialResponse) {
                }
            }
            serialClient?.send(request)
        }

        binding.btnTimingSend.setOnClickListener {
            if (job != null) {
                job?.cancel()
                job = null
                binding.btnTimingSend.text = "循环发送"
                return@setOnClickListener
            }

            val isConnect = serialClient?.isConnect() ?: false
            if (!isConnect) {
                Toast.makeText(this, "请开启串口", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val data = binding.etCommand.text.toString()
            if (TextUtils.isEmpty(data)) {
                Toast.makeText(this, "请输入命令", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val byteArr = ByteUtils.strToByte(data)
            if (byteArr == null) {
                Toast.makeText(this, "请输入合理命令", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            job = lifecycleScope.launch {
                while (isActive) {
                    delay(1000)
                    withContext(Dispatchers.Main) {
                        val request = object : SerialRequest(byteArr) {
                            override fun process(bytes: ByteArray): Boolean {
                                return false
                            }

                            override fun response(response: SerialResponse) {
                            }
                        }
                        serialClient?.send(request)
                    }
                }
            }
            binding.btnTimingSend.text = "执行中"
        }

        binding.btnClear.setOnClickListener {
            adapter.submitList(emptyList())
        }
    }

    private fun openSerialPort() {
        if (serialClient?.isConnect() == true) {
            serialClient?.disconnect()
            binding.tvOpenState.text = "开启"
            binding.viewOpenState.setBackgroundColor(Color.RED)
            return
        }
        serialClient = OkSerialClient.Builder()
            .devicePath(devicePath!!)
            .baudRate(baudRate!!)
            .build()
        serialClient?.addConnectListener(object : OnConnectListener {
            override fun onConnect(devicePath: String) {
                binding.tvOpenState.text = "关闭"
                binding.viewOpenState.setBackgroundColor(Color.GREEN)
                Log.i("Ok-Serial", "${devicePath}连接成功")
            }

            override fun onDisconnect(devicePath: String, errorMag: String?) {
                binding.tvOpenState.text = "开启"
                binding.viewOpenState.setBackgroundColor(Color.RED)
                Log.i("Ok-Serial", "${devicePath}连接失败：${errorMag}")
            }
        })

        serialClient?.addDataListener(object : OnDataListener {
            override fun onRequest(data: ByteArray) {
                adapter.add(
                    LogBean(
                        TimeUtils.getNowString(),
                        devicePath!!,
                        "发送",
                        ByteUtils.byteArrToHexStr(data)
                    )
                )
                binding.rvLog.smoothScrollToPosition(adapter.itemCount - 1)
                if (adapter.itemCount > 100) {
                    adapter.submitList(emptyList())
                }
            }

            override fun onResponse(data: ByteArray) {
                adapter.add(
                    LogBean(
                        TimeUtils.getNowString(),
                        devicePath!!,
                        "响应",
                        ByteUtils.byteArrToHexStr(data)
                    )
                )
                binding.rvLog.smoothScrollToPosition(adapter.itemCount - 1)
                if (adapter.itemCount > 100) {
                    adapter.submitList(emptyList())
                }
            }
        })
        serialClient?.connect()
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
    }
}