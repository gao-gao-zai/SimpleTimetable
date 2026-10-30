@file:Suppress("DEPRECATION")
package com.ggz.diagnostics
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.widget.*
import java.util.ArrayDeque
import java.util.UUID

@SuppressLint("MissingPermission")
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var rows: LinearLayout; private lateinit var log: TextView
    private val lines = ArrayDeque<String>(); private val found = mutableMapOf<String, BluetoothDevice>()
    private val operations = ArrayDeque<() -> Boolean>()
    private var gatt: BluetoothGatt? = null; private var scanning = false; private var refreshed = false
    private var received = ByteArray(64); private val blocks = mutableSetOf<Int>(); private var readCount = 0; private var listCount = 0
    private val adapter get() = getSystemService(BluetoothManager::class.java).adapter
    private val permissions get() = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20,20,20,20) }; setContentView(ScrollView(this).apply { addView(rows) })
        rows.addView(TextView(this).apply { text = "独立 BLE 诊断 1.0.3\n仅固定数据；不读取或修改课表；报告只保存在本机" })
        button("授权并扫描") { if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) requestPermissions(permissions, 1) else scan() }
        button("第二次发现服务") { gatt?.discoverServices() ?: report("服务尚未连接；未测试") }
        button("刷新选定设备缓存（每轮最多一次）") { refreshSelected() }
        button("运行固定数据测试") { runTests() }
        button("复制本地报告") { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("BLE report",lines.joinToString("\n"))) }
        button("断开") { closeGatt() }
        log = TextView(this); rows.addView(log); report("等待手表开启独立诊断服务；与课表同步 UUID 隔离")
    }
    private fun button(title: String, action: () -> Unit) { rows.addView(Button(this).apply { text=title;setOnClickListener { runCatching(action).onFailure { report("错误：${it.javaClass.simpleName}") } } }) }
    private fun report(message: String) { if(lines.size>=150)lines.removeFirst();lines.add("${SystemClock.elapsedRealtime()} $message");log.text=lines.joinToString("\n") }
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) { handler.post {
            val d=result.device
            if(found.put(d.address,d)==null)button("${d.name?:"附近设备"} · …${d.address.takeLast(5)}") { stopScan();closeGatt();refreshed=false;listCount=0;gatt=d.connectGatt(this@MainActivity,false,callback,BluetoothDevice.TRANSPORT_LE);report("连接选定设备 …${d.address.takeLast(5)}；connectable ${result.isConnectable}；type ${d.type}") }
        } }
        override fun onScanFailed(errorCode: Int) { handler.post { report("扫描错误 $errorCode") } }
    }
    private fun scan(){stopScan();found.clear();adapter.bluetoothLeScanner.startScan(scanCallback);scanning=true;handler.postDelayed({stopScan()},15000);report("现场扫描中；配对不算扫描证据")}
    private fun stopScan(){if(scanning)adapter.bluetoothLeScanner.stopScan(scanCallback);scanning=false}
    private val callback=object:BluetoothGattCallback(){
        override fun onConnectionStateChange(g:BluetoothGatt,status:Int,state:Int){handler.post{if(g!==gatt)return@post;report("GATT status=$status state=$state");if(status==0&&state==2)g.discoverServices()}}
        override fun onServicesDiscovered(g:BluetoothGatt,status:Int){handler.post{if(g!==gatt)return@post;listCount++;report("服务清单 $listCount status=$status："+g.services.joinToString { it.uuid.toString() });report(if(g.getService(Probe.service)==null)"独立诊断服务缺失；后续项目未测试" else "独立诊断服务已发现")}}
        override fun onDescriptorWrite(g:BluetoothGatt,d:BluetoothGattDescriptor,status:Int){handler.post{if(g!==gatt)return@post;report("CCCD $status");next()}}
        override fun onCharacteristicWrite(g:BluetoothGatt,c:BluetoothGattCharacteristic,status:Int){handler.post{if(g!==gatt)return@post;report("写完成 ${c.uuid} status=$status");next()}}
        override fun onCharacteristicRead(g:BluetoothGatt,c:BluetoothGattCharacteristic,status:Int){if(Build.VERSION.SDK_INT<33)read(g,c,c.value?:byteArrayOf(),status)}
        override fun onCharacteristicRead(g:BluetoothGatt,c:BluetoothGattCharacteristic,v:ByteArray,status:Int){read(g,c,v.clone(),status)}
        override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic){if(Build.VERSION.SDK_INT<33)notify(g,c.value?.clone()?:byteArrayOf())}
        override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic,v:ByteArray){notify(g,v.clone())}
    }
    private fun read(g:BluetoothGatt,c:BluetoothGattCharacteristic,v:ByteArray,status:Int){handler.post{if(g!==gatt)return@post;readCount++;report("读取 ${c.uuid} status=$status bytes=${v.size} callback=$readCount"+(if(c.uuid==Probe.uuid(3)&&v.size>=4)" 手表回调计数="+(v[0].toInt()and 255) else ""));next()}}
    private fun notify(g:BluetoothGatt,v:ByteArray){handler.post{if(g!==gatt)return@post;if(v.size==20&&(v[0].toInt()and 255)==0xd7){val seq=v[1].toInt()and 255;if(seq<4&&v[2].toInt()==4&&v[3].toInt()==16){v.copyInto(received,seq*16,4,20);blocks.add(seq);report("64B通知块 $seq / 4");if(blocks.size==4)report("64B整段校验="+Probe.verify(received)+" CRC="+Probe.crc(received))}}else report("通知回显 bytes=${v.size} match="+v.contentEquals(byteArrayOf(1,2,3,4,5,6,7,8)))}}
    private fun characteristic(n:Int)=gatt?.getService(Probe.service)?.getCharacteristic(Probe.uuid(n))
    private fun write(c:BluetoothGattCharacteristic,b:ByteArray,noResponse:Boolean=false):Boolean {val g=gatt?:return false;val type=if(noResponse)BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;return if(Build.VERSION.SDK_INT>=33)g.writeCharacteristic(c,b,type)==BluetoothStatusCodes.SUCCESS else {c.writeType=type;c.value=b;g.writeCharacteristic(c)}}
    private fun runTests(){
        operations.clear();blocks.clear();received=ByteArray(64)
        val g=gatt?:run{report("未连接；未测试");return};if(g.getService(Probe.service)==null){report("服务缺失；全部未测试");return}
        val tx=characteristic(6)
        if(tx!=null)operations.add {val d=tx.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));if(d==null)false else {g.setCharacteristicNotification(tx,true);if(Build.VERSION.SDK_INT>=33)g.writeDescriptor(d,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)==BluetoothStatusCodes.SUCCESS else {d.value=BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;g.writeDescriptor(d)}}}
        for(n in listOf(2,3))operations.add {characteristic(n)?.let {g.readCharacteristic(it)}?:false}
        operations.add {characteristic(4)?.let {write(it,byteArrayOf(1,2,3,4,5,6,7,8))}?:false}
        operations.add {characteristic(4)?.let {g.readCharacteristic(it)}?:false}
        operations.add {characteristic(5)?.let {write(it,byteArrayOf(9,8,7),true)}?:false}
        operations.add {characteristic(6)?.let {write(it,byteArrayOf(1,2,3,4,5,6,7,8))}?:false}
        for(seq in 0..3)operations.add {characteristic(7)?.let {write(it,Probe.packet(seq))}?:false}
        next()
    }
    private var operationGeneration=0
    private fun next(){val token=++operationGeneration;val op=operations.pollFirst()?:run{report("操作队列结束；通知结果另列，不能以写成功代替通知成功");return};if(!runCatching(op).getOrDefault(false)){report("操作未启动，继续后续测试");next();return};handler.postDelayed({if(token==operationGeneration){report("操作回调超时，继续后续测试");next()}},3000)}
    private fun refreshSelected(){val g=gatt?:run{report("未连接");return};if(refreshed){report("本轮已刷新一次，不重复");return};refreshed=true;val accepted=runCatching {g.javaClass.getMethod("refresh").invoke(g) as Boolean}.getOrNull();report("缓存刷新请求接受=$accepted；此值不能证明缓存已清除");if(accepted==true)handler.postDelayed({if(g===gatt){report("刷新等待后重新发现，以新服务清单为结果");g.discoverServices()}},1000)}
    private fun closeGatt(){++operationGeneration;operations.clear();gatt?.let {it.disconnect();it.close()};gatt=null}
    override fun onDestroy(){stopScan();closeGatt();handler.removeCallbacksAndMessages(null);super.onDestroy()}
}
