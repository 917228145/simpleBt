package cn.chenanduo.simplebt;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAdapter.LeScanCallback;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.support.annotation.RequiresApi;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import cn.chenanduo.simplebt.bean.DeviceBean;

/**
 * Created by chen on 5/28/17.
 */

@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
public class BluetoothBLeClass extends BleBase implements LeScanCallback {
    private static final String DISENABLE = "00002902-0000-1000-8000-00805f9b34fb";
    private static BluetoothBLeClass mBLE;
    private final static String TAG = "simpleBtTest";
    //uuid 由构造函数传入
    private static String SERVICE_UUID;
    private static String NOTIFI_UUID;
    private static String WRITE_UUID;
    private static BluetoothManager mBluetoothManager;
    private static BluetoothAdapter mBluetoothAdapter;
    //本次连接的蓝牙地址
    public String mBluetoothDeviceAddress;
    public BluetoothGatt mBluetoothGatt;
    //扫描时间
    private static long SCAN_PERIOD = 5000;
    //是否在扫描
    private boolean mScanning = false;
    private Handler mHandler = new Handler();
    // 设备连接断开
    public static final int STATE_DISCONNECTED = 0;
    // 设备正在扫描
    public static final int STATE_SCANNING = 1;
    // 设备扫描结束
    public static final int STATE_SCANNED = 2;
    // 设备正在连接
    public static final int STATE_CONNECTING = 3;
    // 设备连接成功
    public static final int STATE_CONNECTED = 4;
    // 正在尝试重连
    public static final int STATE_RESETCONNECT = 5;
    // 当前设备状态
    private int connectionState = STATE_DISCONNECTED;
    //设置自动重连
    public static boolean isAutoConnect = false;
    //蓝牙是否连接
    public static boolean isBleConnect = false;
    //定时器 处理断开自动重连
    private Timer mTimer;
    private static Context mContext;
    //用来判断集合中是否已经有重复蓝牙设备
    boolean exist = false;
    //每次断开连接是否清除缓存
    public static boolean isCloseCleanCache = false;
    //过滤条件
    public static String filtration = null;
    //写的uuid
    private BluetoothGattCharacteristic mWriteCharacteristic;
    //这个集合是为了过滤掉同设备 但是广播数据会一直刷新
    private Map<String, DeviceBean> map = new HashMap<>();
    //这个集合是为了存放已经过滤好的设备 直接回调给外部
    private List<DeviceBean> datas = new ArrayList<>();
    //是否具备通信条件
    private boolean isCommunication;
    private BluetoothChangeListener mBluetoothChangeListener;
    private boolean mIsWriteDescriptor;
    private BluetoothGattCharacteristic mNotifiCharacteristic;
    private boolean mIsSetCharacteristicNotification;

    /**
     * 通过此接口回调所有和蓝牙的交互出去给开发者
     *
     * @param bluetoothChangeListener
     */
    @Override
    public void getBleCurrentState(BluetoothChangeListener bluetoothChangeListener) {
        mBluetoothChangeListener = bluetoothChangeListener;
    }

    private BluetoothBLeClass(Context c) {
        mContext = c;
    }

    public static BluetoothBLeClass getInstane(Context context, String serviceuuid, String notifiuuid, String writeuuid) {
        if (mBLE == null) {
            mContext = context;
            mBLE = new BluetoothBLeClass(mContext);
            SERVICE_UUID = serviceuuid;
            NOTIFI_UUID = notifiuuid;
            WRITE_UUID = writeuuid;

            if (!mContext.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_BLUETOOTH_LE)) {
                Log.d(TAG, "该设备不支持ble");
                return null;
            }
            if (mBluetoothManager == null) {
                mBluetoothManager = (BluetoothManager) mContext
                        .getSystemService(Context.BLUETOOTH_SERVICE);
            }
            if (mBluetoothAdapter == null) {
                mBluetoothAdapter = mBluetoothManager.getAdapter();
            }
        }
        return mBLE;
    }

    //设置扫描时间 不设置默认5秒
    @Override
    public BluetoothBLeClass setScanTime(int time) {
        SCAN_PERIOD = time;
        return mBLE;
    }

    //设置断开自动连接
    @Override
    public BluetoothBLeClass setAutoConnect(boolean isAutoConnect) {
        this.isAutoConnect = isAutoConnect;
        return mBLE;
    }

    //设置每次断开连接都清除缓存
    @Override
    public BluetoothBLeClass closeCleanCache(boolean isCloseCleanCache) {
        this.isCloseCleanCache = isCloseCleanCache;
        return mBLE;
    }

    //设置扫描过滤
    @Override
    public BluetoothBLeClass setFiltration(String filtration) {
        this.filtration = filtration;
        return mBLE;
    }

    /**
     * 通过实现此callBack管理和Ble交互
     */
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        /**
         * 蓝牙连接状态
         * @param gatt
         * @param status
         * @param newState
         */
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                //连接上蓝牙设备
                initConnected(gatt);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                //断开蓝牙连接
                initDisconnected();
            }
        }

        /**
         * 搜索周边服务
         * @param gatt
         * @param status
         */
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                displayGattServices(gatt);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            super.onMtuChanged(gatt, mtu, status);

        }

        /**
         * ble终端数据交互的事件
         * @param gatt
         * @param characteristic
         * @param status
         */
        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicRead(gatt, characteristic, status);
        }

        /**
         * 收到ble返回数据
         * @param gatt
         * @param characteristic
         */
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            final BluetoothGattCharacteristic characteristic) {
            if (mBluetoothChangeListener != null) {
                ThreadUtils.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mBluetoothChangeListener.onBleWriteResult(characteristic.getValue());
                    }
                });
            }
        }

        /**
         * 写入数据成功回调此方法
         * @param gatt
         * @param characteristic
         * @param status
         */
        @Override
        public void onCharacteristicWrite(final BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic, int status) {
            if (mBluetoothChangeListener != null) {
                ThreadUtils.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mBluetoothChangeListener.onWriteDataSucceed(characteristic.getValue());
                    }
                });
            }
            Log.d(TAG, "写入蓝牙设备成功!");
        }
    };

    /*启动通知通道并将给定描述符的值写入到远程设备*/
    private void displayGattServices(BluetoothGatt gatt) {
        if (SERVICE_UUID == null || NOTIFI_UUID == null || WRITE_UUID == null) {
            return;
        }
        BluetoothGattService service = gatt.getService(UUID.fromString(SERVICE_UUID));
        if (service == null) {
            return;
        }
        mNotifiCharacteristic = service.getCharacteristic(UUID.fromString(NOTIFI_UUID));
        if (mNotifiCharacteristic == null) {
            return;
        }
        //启用通知
        mIsSetCharacteristicNotification = mBluetoothGatt.setCharacteristicNotification(mNotifiCharacteristic, true);

        BluetoothGattDescriptor descriptor = mNotifiCharacteristic
                .getDescriptor(UUID.fromString(DISENABLE));
        if (descriptor == null) {
            descriptor = new BluetoothGattDescriptor(
                    UUID.fromString(DISENABLE),
                    BluetoothGattDescriptor.PERMISSION_WRITE);
        }
        mIsWriteDescriptor = descriptor
                .setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        mBluetoothGatt.writeDescriptor(descriptor);

     /*   BluetoothGattDescriptor descriptor = notifiCharacteristic.getDescriptor(UUID.fromString(DISENABLE));
        if (descriptor != null) {
            descriptor.setValue(new byte[]{0x01});
            //将给定描述符的值写入到远程设备。
            mBluetoothGatt.writeDescriptor(descriptor);
        }*/
        //拿到写的uuid
        mWriteCharacteristic = service.getCharacteristic(UUID.fromString(WRITE_UUID));
        if (mNotifiCharacteristic != null && mWriteCharacteristic != null) {
            Log.d(TAG, "通知和写特征找到,已具备通信条件!");
            //已经具备通信条件
            isCommunication = true;
        }
    }

    /*处理连接上蓝牙设备的逻辑*/
    private void initConnected(BluetoothGatt gatt) {
        /**
         搜索连接设备所支持的service  需要连接上才可以 这个方法是异步操作
         在回调函数onServicesDiscovered中得到status
         通过判断status是否等于BluetoothGatt.GATT_SUCCESS来判断查找Service是否成功
         设备连接成功就开始查找该设备所有的服务 这有一点延迟
         */
        gatt.discoverServices();
        isBleConnect = true;
        runonUiThread(STATE_CONNECTED);
    }

    /*处理断开连接的逻辑*/
    private void initDisconnected() {
        isBleConnect = false;
        runonUiThread(STATE_DISCONNECTED);
        //判断用户是否开启了每次断开连接都清除缓存
        if (isCloseCleanCache) {
            refreshDeviceCache();
        }
        //如果用户开启自动重连 且蓝牙是断开连接状态会走进去
        if (isAutoConnect && !isBleConnect) {
            //开启定时器 每五秒重连一次蓝牙设备  必须判断是否为Null  始终保证只有一个定时器对象
            if (mTimer == null) {
                mTimer = new Timer();
            }
            mTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    //如果已经连接上就停止定时器
                    if (isBleConnect == true) {
                        if (mTimer != null) {
                            mTimer.cancel();
                            mTimer = null;
                        }
                    } else {
                        //设置状态正在尝试重连
                        runonUiThread(STATE_RESETCONNECT);
                        Log.d(TAG, "run : " + "正在尝试重连");
                        //连接蓝牙
                        ThreadUtils.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                connect(mBluetoothDeviceAddress);
                            }
                        });
                    }
                }
            }, 20, 5000);
        }
    }

    /*
     * 初始化对本地蓝牙适配器的引用 并判断蓝牙是否是开启状态
     * 如果初始化成功则返回true
     */
    @Override
    public boolean initialize() {
        //判断是否开启蓝牙  如果没有开启 弹窗提示用户开启蓝牙
        return mBluetoothAdapter.isEnabled() ? true : false;
    }

    /*
     * 根据address连接蓝牙设备
     */
    @Override
    public boolean connect(final String address) {
        if (mBluetoothAdapter == null || address == null) {
            return false;
        }
        stopScanDevices();
        /*设置状态连接中*/
        setBleCurrentState(STATE_CONNECTING);
        // 之前连接的设备尝试重新连接
        if (mBluetoothDeviceAddress != null
                && address.equals(mBluetoothDeviceAddress)
                && mBluetoothGatt != null) {
            if (mBluetoothGatt.connect()) {
                return true;
            } else {
                return false;
            }
        }
        //根据mac地址连接蓝牙
        final BluetoothDevice device = mBluetoothAdapter
                .getRemoteDevice(address);
        if (device == null) {
            return false;
        }
        // 想直接连接设备传入false
        mBluetoothGatt = device.connectGatt(mContext, false, mGattCallback);
        mBluetoothDeviceAddress = address;
        return true;
    }

    /*
     *取消等待连接或断开一个现有的连接
     */
    @Override
    public void disconnect() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            return;
        }
        //不具备通信条件
        isCommunication = false;
        mBluetoothGatt.disconnect();
    }

    /*
     * 使用完Ble后一定要调用此方法释放资源
     */
    @Override
    public void close() {
        isAutoConnect = false;
        isBleConnect = false;
        //不具备通信条件了
        isCommunication = false;
        if (mTimer != null) {
            Log.d(TAG, "close : " + "应用销毁 停止计时器");
            mTimer.cancel();
            mTimer = null;
        }
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    /*
     * 发送指令方法
     * @param string
     * @param uuid
     * @return
     */
    @Override
    public boolean writeCharacteristic(byte[] value) {
        if (value == null || mWriteCharacteristic == null || isCommunication == false) {
            Log.d(TAG, "不满足写数据条件,写入失败");
            return false;
        }
        //TODO 待测试 数据大于20字节 可以自动分包
        //characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        //设置数据内容
        mWriteCharacteristic.setValue(value);
        //往蓝牙模块写入数据
        return mBluetoothGatt.writeCharacteristic(mWriteCharacteristic);
    }

    /*
     * 开始搜索设备
     * @param enable
     * @param mLeScanCallback
     */
    @Override
    public void startScanDevices(final boolean enable) {
        //五秒后停止扫描
        if (enable) {
            //开始扫描前清空集合 并停止上一次扫描
            map.clear();
            stopScanDevices();
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mScanning = false;
                    mBluetoothAdapter.stopLeScan(BluetoothBLeClass.this);
                    ThreadUtils.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setScanfinish();
                        }
                    });
                    Log.d(TAG, "停止扫描");
                }
            }, SCAN_PERIOD);
            mScanning = true;
            mBluetoothAdapter.startLeScan(BluetoothBLeClass.this);
            //设置状态扫描中
            setBleCurrentState(STATE_SCANNING);
        } else {
            mBluetoothAdapter.stopLeScan(BluetoothBLeClass.this);
            mHandler.removeCallbacksAndMessages(null);
            mScanning = false;
            setScanfinish();
        }
    }

    /*
     *停止扫描设备
     *
     */
    @Override
    public void stopScanDevices() {
        //如果当前有设备正在连接的话 先断开连接
        disconnect();
        if (mScanning) {
            mBluetoothAdapter.stopLeScan(BluetoothBLeClass.this);
            //扫描蓝牙设备对bluetoothAdapter来说是一个非常消耗资源的工作 停止扫描时 应该要取消这一过程
            mBluetoothAdapter.cancelDiscovery();
            mScanning = false;
            setScanfinish();
            mHandler.removeCallbacksAndMessages(null);
        }
    }

    //设置扫描结束状态
    private void setScanfinish() {
        //设置扫描结束
        setBleCurrentState(STATE_SCANNED);
    }

    /**
     * 所有蓝牙设备连接状态回调给调用者处理
     *
     * @param state
     */
    private void setBleCurrentState(int state) {
        connectionState = state;
        if (mBluetoothChangeListener != null) {
            mBluetoothChangeListener.onCurrentState(state);//200 150 300 200 200
        }
    }

    /*扫描结果  此方法应尽量避免耗时操作*/
    @Override
    public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
        /**
         * 需求是扫描的蓝牙不重复 但是广播需要一直传递出去
         * 1.获取到对应的广播
         */
        String address = device.getAddress();
        String name = device.getName();
        if (null != name) {
            //设置了过滤
            if (filtration != null) {
                //过滤mac地址或者名称
                if (name.contains(filtration) || address.contains(filtration)) {
                    DeviceBean bean = new DeviceBean();
                    bean.setName(name);
                    bean.setAddress(address);
                    bean.setRssi(rssi);
                    bean.setScanRecord(scanRecord);
                    map.put(address, bean);
                }
            } else {
                //没有设置过滤
                DeviceBean bean = new DeviceBean();
                bean.setName(name);
                bean.setAddress(address);
                bean.setRssi(rssi);
                bean.setScanRecord(scanRecord);
                map.put(address, bean);
            }
            Set<Map.Entry<String, DeviceBean>> entries = map.entrySet();
            Iterator<Map.Entry<String, DeviceBean>> iterator = entries.iterator();
            datas.clear();
            while (iterator.hasNext()) {
                datas.add(iterator.next().getValue());
            }
            if (mBluetoothChangeListener != null) {
                mBluetoothChangeListener.onBleScanResult(datas);
            }
        }
    }

    //返回当前设备连接状态
    @Override
    public int getBleConnectState() {
        return connectionState;
    }

    //清除缓存
    @Override
    public boolean refreshDeviceCache() {
        if (mBluetoothGatt != null) {
            try {
                BluetoothGatt localBluetoothGatt = mBluetoothGatt;
                Method localMethod = localBluetoothGatt
                        .getClass()
                        .getMethod("refresh", new Class[0]);
                if (localMethod != null) {
                    boolean bool = ((Boolean) localMethod.invoke(localBluetoothGatt,
                            new Object[0])).booleanValue();
                    return bool;
                }
            } catch (Exception localException) {
                Log.i(TAG, "Exception localException:" + localException.getMessage());
            }
        }
        return false;
    }

    //将子线程设置的状态处理到主线程
    private void runonUiThread(final int state) {
        ThreadUtils.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                setBleCurrentState(state);
            }
        });
    }
}
