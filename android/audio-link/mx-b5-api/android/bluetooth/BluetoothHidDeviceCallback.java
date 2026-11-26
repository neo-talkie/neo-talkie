package android.bluetooth;
public abstract class BluetoothHidDeviceCallback {
 public void onAppStatusChanged(BluetoothDevice d,BluetoothHidDeviceAppConfiguration c,boolean ok){}
 public void onConnectionStateChanged(BluetoothDevice d,int state){}
}
