package android.bluetooth;
public abstract class BluetoothHidDevice implements BluetoothProfile {
 public abstract boolean registerApp(BluetoothHidDeviceAppSdpSettings s,BluetoothHidDeviceAppQosSettings q,BluetoothHidDeviceAppQosSettings q2,BluetoothHidDeviceCallback c);
 public abstract boolean connect();
 public abstract boolean unregisterApp(BluetoothHidDeviceAppConfiguration c);
 public abstract boolean sendReport(int id,byte[] data);
}
