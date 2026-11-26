package android.bluetooth;

/** Compile-time signature from the mx_b5 Android 5.1 system framework. */
public abstract class BluetoothHeadsetClient implements BluetoothProfile {
  public abstract int getAudioState(BluetoothDevice device);
}
