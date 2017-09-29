package com.example.xxh.smartcar;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private TextView status_view;
    private final String LOG_TAG = "SMARTCAR";

    private final String REMOTE_BL_NAME = "XXHBL";
    private final String REMOTE_BL_UUID = "00001101-0000-1000-8000-00805F9B34FB";

    /**
     * 1 程序一启动就打开蓝牙搜索小车蓝牙
     * 2 蓝牙启动后，就开始扫描主机设备
     */
    private BluetoothAdapter mBluetoothAdapter = null;
    private boolean isOpenedBL = false;

    /* 线程 */
    private ConnectThread mConnectThread;
    private WriteReadThread mWriteReadThread;

    private boolean isRemoteConnected = false; // 已经连接上了了，正在读写数据


    /* 蓝牙异步状态 */
    private final int REQUEST_OPEN_BL = 1;

    private final int CREAT_SOCKET_ERR = 2;
    private final int CONNECT_REMOTE_ERR = 3;
    private final int CONNECT_SUCCESS = 4;
    private final int SOCKET_READ_DATA = 5; // 收到数据
    private final int SOCKET_REMOTE_CLOSED = 6; // SmartCar 关闭socket


    /* 小车动作 */
    private final int CAR_START     = 0x01;
    private final int CAR_STOP      = 0x02;
    private final int CAR_RIGHT     = 0x03;
    private final int CAR_LEFT      = 0x04;
    private final int CAR_FORWARD   = 0x05;
    private final int CAR_BACK      = 0x06;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status_view = (TextView)findViewById(R.id.status_view);

        openBLRequst();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode == REQUEST_OPEN_BL) {
            if(resultCode == Activity.RESULT_OK) {
                log("已打开蓝牙");
                logView("已打开蓝牙");
                isOpenedBL = true;
                startServer();
            } else {
                log("打开蓝牙失败");
                logView("打开蓝牙失败");
                isOpenedBL = false;
            }
        }
    }

    // 退出时关闭蓝牙
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if(mBluetoothAdapter.isEnabled()) {
            mBluetoothAdapter.disable();
            isOpenedBL = false;
        }
        unregisterReceiver(mReceiver);
    }

    /**
     * 日志记录
     */
    private void log(String msg) {
        Log.i(LOG_TAG, msg);
    }

    private void logView(String msg) {
        status_view.append(msg + "\n");
        log(msg);
    }

    public void turnLeft(View view) {
        if(!isRemoteConnected)
            return;
        byte[] right = new byte[1];
        right[0] = CAR_LEFT;
        mWriteReadThread.write(right);
    }

    public void turnRight(View view) {
        if(!isRemoteConnected)
            return;
        byte[] right = new byte[1];
        right[0] = CAR_RIGHT;
        mWriteReadThread.write(right);
    }

    public void startStop(View view) {
        if(!isRemoteConnected)
            return;
        byte[] stop = new byte[1];
        stop[0] = CAR_STOP;
        mWriteReadThread.write(stop);
    }

    // 1 程序启动时请求蓝牙打开
    private void openBLRequst()
    {
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if(mBluetoothAdapter == null) {
            Toast.makeText(this, "您的设备不支持蓝牙或蓝牙驱动出问题", Toast.LENGTH_LONG).show();
            isOpenedBL = false;
            logView("您的设备不支持蓝牙或蓝牙驱动出问题");
            return;
        }
        if(mBluetoothAdapter.isEnabled()) {
            isOpenedBL = true;
            startServer();
            return;
        }
        Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        startActivityForResult(intent, REQUEST_OPEN_BL);
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case CREAT_SOCKET_ERR:
                    logView("创建 socket 失败");break;
                case CONNECT_REMOTE_ERR:
                    logView("连接 SmartCar 失败");break;
                case CONNECT_SUCCESS:
                    logView("SmartCar 连接成功");break;
                case SOCKET_READ_DATA:
                    String recvMsg = new String((byte[])msg.obj,0, msg.arg1);
                    logView("收到 SmartCar 数据:" + recvMsg); break;
                case SOCKET_REMOTE_CLOSED:
                    logView("SmartCar 失去连接"); break;
            }
        }
    };

    // 2 扫描小车蓝牙，在蓝牙成功启动后调用
    private void scanCarBL()
    {
        if(!isOpenedBL)
            return;
        if(isRemoteConnected)
            return;

        IntentFilter intentFilter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        registerReceiver(mReceiver, intentFilter);
        mBluetoothAdapter.startDiscovery();
    }

    // 3 广播通知
    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if(BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                String name = device.getName();
                log("发现设备 name:" + name);
                if(name != null && name.equals(REMOTE_BL_NAME)) {
                    log("发现小车");
                    logView("找到 SmartCar");
                    mBluetoothAdapter.cancelDiscovery(); // 取消扫描
                    // 启动连接线程
                    mConnectThread = new ConnectThread(device);
                    mConnectThread.start();
                }
            }
        }
    };


    /* 4 连接线程  第1个线程 */
    private class ConnectThread extends Thread {

        private BluetoothDevice mRemoteDevice;
        private BluetoothSocket mSocket;

        public ConnectThread(BluetoothDevice device) {
            mRemoteDevice = device;
            try {
                // 创建socket 安全匹对连接
                mSocket = device.createRfcommSocketToServiceRecord(UUID.fromString(REMOTE_BL_UUID));
            } catch (IOException e) {
                mHandler.obtainMessage(CREAT_SOCKET_ERR).sendToTarget();
                e.printStackTrace();
            }
        }

        @Override
        public void run() {
            try {
                mSocket.connect();
                mHandler.obtainMessage(CONNECT_SUCCESS).sendToTarget();
                isRemoteConnected = true;
            } catch (IOException e) {
                mHandler.obtainMessage(CONNECT_REMOTE_ERR).sendToTarget();
                e.printStackTrace();
            }

            // 启动收发线程
            mWriteReadThread = new WriteReadThread(mSocket);
            mWriteReadThread.start();
        }

        public void cancel() {
            try {
                mSocket.close();
                isRemoteConnected = false;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /* 5 收发线程 第2个线程 */
    private class WriteReadThread extends Thread {
        private final InputStream mmInputStream;
        private final OutputStream mmOutputStream;
        private final BluetoothSocket mmSocket;

        public WriteReadThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream inputStream = null;
            OutputStream outputStream = null;

            try {
                inputStream = socket.getInputStream();
                outputStream = socket.getOutputStream();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mmInputStream = inputStream;
            mmOutputStream = outputStream;
        }

        @Override
        public void run() {
            log("开始接收数据");
            byte[] buffer = new byte[1024];
            int bytes;

            while(true) {
                // 死循环阻塞地读数据
                try {
                    bytes = mmInputStream.read(buffer);
                    mHandler.obtainMessage(SOCKET_READ_DATA, bytes, -1, buffer).sendToTarget();
                } catch (IOException e) {
                    isRemoteConnected = false;
                    mHandler.obtainMessage(SOCKET_REMOTE_CLOSED).sendToTarget();
                    //e.printStackTrace();
                    break;
                }
            }
        }

        public void write(byte[] buffer) {
            try {
                mmOutputStream.write(buffer);
            } catch (IOException e) {
                log("发送数据失败");
            }
        }

        public void cancel() {
            try {
                mmSocket.close();
                isRemoteConnected = false;
            } catch (IOException e) {
                log("关闭socket错误:" + e.toString());
            }
        }
    }

    /* 线程管理 */
    // 1 重启服务
    public void startServer() {
        if(mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }
        if(mWriteReadThread != null) {
            mWriteReadThread.cancel();;
            mWriteReadThread = null;
        }
        isRemoteConnected = false;

        scanCarBL();
    }
}
