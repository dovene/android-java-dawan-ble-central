package com.dov.blecentral;

import android.annotation.SuppressLint;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;


import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;
import android.widget.TextView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class CentralActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 1;
    private static final String[] PERMISSIONS = {
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN
    };

    private static final UUID BATTERY_CHARACTERISTIC = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final UUID TEMPERATURE_CHARACTERISTIC_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fc");
    private static final UUID HUMIDITY_CHARACTERISTIC_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fd");
    private static final UUID TARGET_SERVICE_UUID = UUID.fromString("00000000-1111-2222-3333-444444444444");

    private final Set<String> connectedDevices = new HashSet<>();


    private BluetoothLeScanner scanner;
    private BluetoothGatt bluetoothGatt;
    private TextView statusText, batteryText, temperatureText, humidityText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_central);

        statusText = findViewById(R.id.statusText);
        batteryText = findViewById(R.id.batteryText);
        temperatureText = findViewById(R.id.temperatureText);
        humidityText = findViewById(R.id.humidityText);


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(PERMISSIONS, REQUEST_PERMISSIONS);
        } else {
            setupBluetoothScanning();
        }

    }

    @SuppressLint("MissingPermission")
    private void setupBluetoothScanning() {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            statusText.setText("Bluetooth is not available");
            return;
        }
        scanner = bluetoothAdapter.getBluetoothLeScanner();
        scanner.startScan(scanCallback);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null || connectedDevices.contains(device.getAddress())) {
                return; // Skip already connected devices
            }
            List<ParcelUuid> serviceUuids = result.getScanRecord().getServiceUuids();
            if (serviceUuids != null && serviceUuids.contains(ParcelUuid.fromString(TARGET_SERVICE_UUID.toString()))) {
                connectedDevices.add(device.getAddress());
                device.connectGatt(CentralActivity.this, false, gattCallback);
            }
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.d("BLE", "onConnectionStateChange: " + newState + " status " + status);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                // Handle connection failure
                Log.e("BLE", "Connection unsuccessful. Status: " + status);
                gatt.close(); // Properly close the previous connection
                return;
            }

            if (newState == BluetoothGatt.STATE_CONNECTED) {
                gatt.requestMtu(512); // Request a higher MTU size
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);

                runOnUiThread(() -> {
                    statusText.setText("Connected to Device "+ gatt.getDevice().getAddress());
                } );
                new Handler(Looper.getMainLooper()).postDelayed(() -> gatt.discoverServices(), 500);

            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                runOnUiThread(() -> {
                    statusText.setText("Disconnected from Device");
                });
                new Handler(Looper.getMainLooper()).postDelayed(() -> gatt.connect(), 2000);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(TARGET_SERVICE_UUID);
                if (service != null) {
                    // Read all characteristics sequentially
                    readCharacteristics(gatt, service);
                }
            } else {
                Log.e("BLE", "Services discovery failed, status: " + status);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Use byte array to handle potential multi-byte values
                byte[] value = characteristic.getValue();

                if (characteristic.getUuid().equals(BATTERY_CHARACTERISTIC)) {
                    int batteryLevel = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0);
                   // int batteryLevel = value != null && value.length > 0 ? (value[0] & 0xFF) : 0;
                    runOnUiThread(() -> batteryText.setText("Battery: " + batteryLevel + "%"));

                    // Read next characteristic
                    readNextCharacteristic(gatt, TEMPERATURE_CHARACTERISTIC_UUID);
                } else if (characteristic.getUuid().equals(TEMPERATURE_CHARACTERISTIC_UUID)) {
                    int temperatureLevel = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0);

                   // int temperatureLevel = value != null && value.length > 0 ? (value[0] & 0xFF) : 0;
                    runOnUiThread(() -> temperatureText.setText("Temperature: " + temperatureLevel + "°C"));

                    // Read next characteristic
                    readNextCharacteristic(gatt, HUMIDITY_CHARACTERISTIC_UUID);
                } else if (characteristic.getUuid().equals(HUMIDITY_CHARACTERISTIC_UUID)) {
                    int humidityLevel = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0);

                    // int humidityLevel = value != null && value.length > 0 ? (value[0] & 0xFF) : 0;
                    runOnUiThread(() -> humidityText.setText("Humidity: " + humidityLevel + "%"));
                }
            } else {
                Log.e("BLE", "Characteristic read failed: " + status);
            }
        }
    };



    @SuppressLint("MissingPermission")
    private void readCharacteristics(BluetoothGatt gatt, BluetoothGattService service) {
        // Start with battery characteristic
        BluetoothGattCharacteristic batteryChar = service.getCharacteristic(BATTERY_CHARACTERISTIC);
        if (batteryChar != null) {
            gatt.readCharacteristic(batteryChar);
        }
    }

    @SuppressLint("MissingPermission")
    private void readNextCharacteristic(BluetoothGatt gatt, UUID characteristicUUID) {
        BluetoothGattService service = gatt.getService(TARGET_SERVICE_UUID);
        if (service != null) {
            BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristicUUID);
            if (characteristic != null) {
                gatt.readCharacteristic(characteristic);
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                setupBluetoothScanning();
            } else {
                Log.e("BLE", "Permissions denied");
            }
        }
    }
}