package com.example.serialllllll;

import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.HexDump;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class SerialService extends Service implements  SerialInputOutputManager.Listener{
    private enum UsbPermission { Unknown, Requested, Granted, Denied }
    private Executor executors= Executors.newSingleThreadExecutor();

    private class Item{
        UsbDevice device;
        int port;
        UsbSerialDriver driver;
        Item(UsbDevice device, int port, UsbSerialDriver driver) {
            this.device = device;
            this.port = port;
            this.driver = driver;
        }

    }
    private static final String INTENT_ACTION_GRANT_USB = "com.example.serialllllll.GRANT_USB";
    private static final int WRITE_WAIT_MILLIS = 2000;
    private static final int READ_WAIT_MILLIS = 2000;


    private int deviceId=1001, portNum, baudRate=115200;
    private boolean withIoManager=true;

    private BroadcastReceiver broadcastReceiver;
    private Handler mainLooper;
    private TextView receiveText;
    // private ControlLines controlLines;

    UsbDevice device;
    private SerialInputOutputManager usbIoManager;
    private UsbSerialPort usbSerialPort;
    private UsbPermission usbPermission = UsbPermission.Unknown;
    private boolean connected = false;
    private UsbManager usbManager;
    List<Item> listItems=new ArrayList<>();

    public SerialService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        connect();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    void refresh() {
        if(!connected && (usbPermission == UsbPermission.Unknown || usbPermission == UsbPermission.Granted)) {
            mainLooper.post(new Runnable() {
                @Override
                public void run() {
                    connect();
                }
            });
        }
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        UsbSerialProber usbDefaultProber = UsbSerialProber.getDefaultProber();
        UsbSerialProber usbCustomProber = CustomProber.getCustomProber();
        listItems.clear();
        for(UsbDevice device : usbManager.getDeviceList().values()) {
            UsbSerialDriver driver = usbDefaultProber.probeDevice(device);
            if(driver == null) {
                driver = usbCustomProber.probeDevice(device);
            }
            if(driver != null) {

            } else {
                listItems.add(new Item(device, 0, null));
            }
        }

        if(!listItems.isEmpty()) {
            portNum=listItems.get(0).port;
            deviceId=listItems.get(0).device.getDeviceId();
            device=listItems.get(0).device;
            //  Toast.makeText(this, "hii"+deviceId+", "+listItems.size(), Toast.LENGTH_SHORT).show();
            read();
        }
        //listAdapter.notifyDataSetChanged();
    }

        
    private void disconnect() {
        connected = false;
        // controlLines.stop();
        if(usbIoManager != null) {
            usbIoManager.setListener(null);
            usbIoManager.stop();
        }
        usbIoManager = null;
        try {
            usbSerialPort.close();
        } catch (IOException ignored) {}
        usbSerialPort = null;
    }

    private void send(String str) {
        if(!connected) {
            Toast.makeText(this, "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            byte[] data = (str + '\n').getBytes();
            SpannableStringBuilder spn = new SpannableStringBuilder();
            spn.append("send " + data.length + " bytes\n");
            spn.append(HexDump.dumpHexString(data)).append("\n");
            spn.setSpan(new ForegroundColorSpan(ContextCompat.getColor(this,R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            usbSerialPort.write(data, WRITE_WAIT_MILLIS);
        } catch (Exception e) {
            //onRunError(e);
        }
    }

    private void read() {
        if(!connected) {
            Toast.makeText(this, "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            byte[] buffer = new byte[8192];
            int len = usbSerialPort.read(buffer, READ_WAIT_MILLIS);
            receive(Arrays.copyOf(buffer, len));
        } catch (IOException e) {
            // when using read with timeout, USB bulkTransfer returns -1 on timeout _and_ errors
            // like connection loss, so there is typically no exception thrown here on error
           // status("connection lost: " + e.getMessage());
            disconnect();
        }
    }

    private void receive(byte[] data) {
        SpannableStringBuilder spn = new SpannableStringBuilder();
        //spn.append("receive " + data.length + " bytes\n");

        if(data.length > 0)
            spn.append(new String(data)).append(" -> ");
        receiveText.append(spn);
//        scrollView.post(new Runnable() {
//            @Override
//            public void run() {
//                scrollView.fullScroll(View.FOCUS_DOWN);
//            }
//        });

    }
    @Override
    public void onNewData(byte[] data) {
        mainLooper.post(() -> {
            receive(data);
        });

    }

    @Override
    public void onRunError(Exception e) {
        mainLooper.post(() -> {
           // status("connection lost: " + e.getMessage());
            disconnect();
        });
    }
    private void connect() {
        UsbDevice device = null;
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        for(UsbDevice v : usbManager.getDeviceList().values())
            if(v.getDeviceId() == deviceId) {
                device = v;
                Toast.makeText(this, ""+deviceId, Toast.LENGTH_SHORT).show();
            }
        if(device == null) {
            //status("connection failed: device not found");
            return;
        }
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if(driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device);
        }
        if(driver == null) {
           // status("connection failed: no driver for device");
            return;
        }
        if(driver.getPorts().size() < portNum) {
            //status("connection failed: not enough ports at device");
            return;
        }
        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if(usbConnection == null && usbPermission == UsbPermission.Unknown && !usbManager.hasPermission(driver.getDevice())) {
            usbPermission = UsbPermission.Requested;
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_MUTABLE : 0;
            Intent intent = new Intent(INTENT_ACTION_GRANT_USB);
            intent.setPackage(getPackageName());
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(this, 0, intent, flags);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if(usbConnection == null) {
//            if (!usbManager.hasPermission(driver.getDevice()))
//                status("connection failed: permission denied");
//            else
//                status("connection failed: open failed");
            return;
        }

        try {
            usbSerialPort.open(usbConnection);
            try{
                usbSerialPort.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
            }catch (UnsupportedOperationException e){
                //status("unsupport setparameters");
            }
            if(withIoManager) {
                usbIoManager = new SerialInputOutputManager(usbSerialPort, this);
                usbIoManager.start();
            }
            //status("connected");
            connected = true;
            // controlLines.start();
        } catch (Exception e) {
            //status("connection failed: " + e.getMessage());
            disconnect();
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }



}