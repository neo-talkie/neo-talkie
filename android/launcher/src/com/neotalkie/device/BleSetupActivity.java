package com.neotalkie.device;

import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.util.Log;
import android.view.*;
import java.util.*;

/** Foreground BLE setup with a physically approved, encrypted Wi-Fi transaction. */
public final class BleSetupActivity extends Activity {
  private final Handler main = new Handler();
  private final BleProbeProtocol protocol = new BleProbeProtocol();
  private final Object lock = new Object();
  private WifiProvisionSession wifiSession;
  private WifiConnector connector;
  private String pairingCode="";
  private BluetoothAdapter adapter;
  private BluetoothLeAdvertiser advertiser;
  private BluetoothGattServer server;
  private BluetoothDevice peer;
  private AdvertiseCallback advertising;
  private boolean closed = true, wasEnabled;
  private int generation,sessionTicket;
  private String originalName, broadcastName;
  private Panel panel;
  private StatusStrip strip;
  private VolumeStrip volume;
  private String status = UiContract.T_BLE_STARTING;
  private String detail = UiContract.T_BLE_TEST_ONLY;

  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
    setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
    panel = new Panel(); strip = new StatusStrip(this,panel); volume = new VolumeStrip(this,panel);
    setContentView(panel);
    String suffix = getPreferences(0).getString("bleSuffix", "");
    if (suffix.isEmpty()) {
      suffix = String.format(Locale.US,"%04X",new java.security.SecureRandom().nextInt(65536));
      getPreferences(0).edit().putString("bleSuffix",suffix).apply();
    }
    broadcastName = "NeoTalkie-" + suffix;
  }
  protected void onResume() { super.onResume(); strip.start(); PttNavigation.resume(this); begin(); }
  protected void onPause() { end(); PttNavigation.pause(this); strip.stop(); volume.hide(); super.onPause(); }
  protected void onDestroy() { end(); super.onDestroy(); }
  private void show(final int run, final String state, final String text) {
    main.post(new Runnable(){public void run(){
      if (closed || run != generation) return;
      status = state; detail = text; panel.invalidate();
    }});
  }
  private void begin() {
    boolean inherited=adapter!=null, priorEnabled=wasEnabled;String priorName=originalName;
    end();
    closed = false; final int run = ++generation;
    status = UiContract.T_BLE_STARTING; detail = UiContract.T_BLE_SCAN; pairingCode="";
    ScreenPower.busy(this,true);
    BluetoothManager manager = (BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
    adapter = manager == null ? null : manager.getAdapter();
    if (adapter == null) { show(run,UiContract.T_BLE_UNAVAILABLE,UiContract.T_BLE_RETRY); return; }
    int initial=adapter.getState();
    wasEnabled=inherited ? priorEnabled : initial==BluetoothAdapter.STATE_ON || initial==BluetoothAdapter.STATE_TURNING_ON;
    originalName=inherited ? priorName : adapter.getName();
    // Wait through TURNING_OFF before enabling. Closing/reopening can overlap asynchronous adapter transitions.
    main.postDelayed(new Runnable(){int attempts;public void run(){
      if (closed || run != generation) return;
      try {
        int state=adapter.getState();
        if(state==BluetoothAdapter.STATE_ON){startGatt(run);return;}
        if(state==BluetoothAdapter.STATE_OFF)adapter.enable();
        if(++attempts<50)main.postDelayed(this,300);
        else show(run,UiContract.T_BLE_UNAVAILABLE,UiContract.T_BLE_RETRY);
      }catch(RuntimeException e){show(run,UiContract.T_BLE_UNAVAILABLE,UiContract.T_BLE_RETRY);}
    }},400);
    main.postDelayed(new Runnable(){public void run(){
      if (closed || run != generation) return;
      end(); status=UiContract.T_BLE_EXPIRED; detail=UiContract.T_BLE_RETRY; panel.invalidate();
    }},UiContract.BLE_SESSION_MS);
  }
  private void startGatt(final int run) {
    try {
      if (!adapter.isMultipleAdvertisementSupported()) { show(run,UiContract.T_BLE_UNAVAILABLE,UiContract.T_BLE_RETRY); return; }
      adapter.setName(broadcastName);
      advertiser = adapter.getBluetoothLeAdvertiser();
      if (advertiser == null) { show(run,UiContract.T_BLE_UNAVAILABLE,UiContract.T_BLE_RETRY); return; }
      final BluetoothManager manager = (BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
      synchronized(lock) {
        protocol.reset(); peer = null;
        server = manager.openGattServer(this,new BluetoothGattServerCallback(){
          public void onServiceAdded(int result,BluetoothGattService service) {
            main.post(new Runnable(){public void run(){
              if (closed || generation != run) return;
              if (result == BluetoothGatt.GATT_SUCCESS) advertise(run);
              else show(run,UiContract.T_BLE_UNAVAILABLE,UiContract.T_BLE_RETRY);
            }});
          }
          public void onConnectionStateChange(BluetoothDevice device,int result,int state) {
            synchronized(lock) {
              if (closed || generation != run || server == null) return;
              if (state == BluetoothProfile.STATE_CONNECTED) {
                if (peer != null && !peer.equals(device)) { server.cancelConnection(device); return; }
                peer=device; protocol.reset(); prepareWifi(run,device);
                Log.i("NeoTalkieBLE","connected");
                show(run,UiContract.T_BLE_CONNECTED,UiContract.T_BLE_SEND_TEST);
              } else if (state == BluetoothProfile.STATE_DISCONNECTED && device.equals(peer)) {
                boolean passed=protocol.passed() || wifiSession!=null && wifiSession.state()>=WifiProvisionSession.SUCCESS; peer=null; sessionTicket++; protocol.reset();
                if(wifiSession!=null)wifiSession.destroy();wifiSession=null;
                final WifiConnector disconnected=connector;
                main.post(new Runnable(){public void run(){if(run==generation && disconnected!=null && disconnected==connector){disconnected.cancel();connector=null;} if(peer==null)pairingCode="";}});
                Log.i("NeoTalkieBLE","disconnected");
                if (!passed) show(run,UiContract.T_BLE_READY,UiContract.T_BLE_SCAN);
              }
            }
          }
          public void onCharacteristicReadRequest(BluetoothDevice device,int id,int offset,BluetoothGattCharacteristic ch) {
            synchronized(lock) {
              if (closed || generation != run || server == null) return;
              if (!device.equals(peer) || (!ch.getUuid().toString().equals(BleProbeProtocol.VALUE) && !ch.getUuid().toString().equals(WifiProvisionSession.VALUE))) {
                server.sendResponse(device,id,BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,offset,null); return;
              }
              byte[] data=ch.getUuid().toString().equals(WifiProvisionSession.VALUE) ? (wifiSession==null ? new byte[]{0x7f} : wifiSession.reply()) : protocol.reply().getBytes(java.nio.charset.Charset.forName("US-ASCII"));
              if (offset<0 || offset>data.length) server.sendResponse(device,id,BluetoothGatt.GATT_INVALID_OFFSET,offset,null);
              else { server.sendResponse(device,id,BluetoothGatt.GATT_SUCCESS,offset,Arrays.copyOfRange(data,offset,data.length));
                if (protocol.passed()) Log.i("NeoTalkieBLE","roundtrip_reply_read"); }
            }
          }
          public void onCharacteristicWriteRequest(BluetoothDevice device,int id,BluetoothGattCharacteristic ch,boolean prepared,boolean response,int offset,byte[] value) {
            synchronized(lock) {
              if (closed || generation != run || server == null) return;
              int result=BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
              if(device.equals(peer) && ch.getUuid().toString().equals(WifiProvisionSession.VALUE) && !prepared && offset==0){
                if(wifiSession==null)result=BluetoothGatt.GATT_SUCCESS;
                else {
                  final WifiProvisionSession session=wifiSession;
                  result=session.accept(value,SystemClock.elapsedRealtime()) ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_FAILURE;
                  if(session.awaitingApproval()){pairingCode=session.fingerprint;show(run,UiContract.T_WIFI_PAIR,UiContract.T_WIFI_CONFIRM);}
                  final WifiProvisionSession.Credentials credentials=session.takeCredentials();
                  if(credentials!=null)main.post(new Runnable(){public void run(){
                    synchronized(lock){
                      if(closed || run!=generation || session!=wifiSession || peer==null){credentials.clear();return;}
                      show(run,UiContract.T_WIFI_CONNECTING,UiContract.T_WIFI_WAIT);
                      connector=new WifiConnector(BleSetupActivity.this,new WifiConnector.Listener(){public void finished(int code,int ip){
                        synchronized(lock){if(session!=wifiSession)return;session.result(code,ip);}
                        Log.i("NeoTalkieBLE","wifi_result code="+code);
                        show(run,wifiState(code),code==WifiProvisionSession.SUCCESS ? UiContract.T_WIFI_LOCAL : UiContract.T_WIFI_FAILURE_HINT);
                      }});connector.start(credentials);
                    }
                  }});
                }
              } else if (device.equals(peer) && ch.getUuid().toString().equals(BleProbeProtocol.VALUE) && !prepared && offset==0) {
                result=protocol.accept(value,SystemClock.elapsedRealtime()) ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_FAILURE;
                if (protocol.passed()) {
                  Log.i("NeoTalkieBLE","test_payload_verified bytes="+protocol.size());
                  show(run,UiContract.T_BLE_PASSED,UiContract.T_BLE_UTF8_OK);
                } else if (result != BluetoothGatt.GATT_SUCCESS) show(run,UiContract.T_BLE_FAILED,UiContract.T_BLE_RETRY);
              }
              if (response) server.sendResponse(device,id,result,offset,null);
            }
          }
        });
        if (server==null) { show(run,UiContract.T_BLE_UNAVAILABLE,UiContract.T_BLE_RETRY); return; }
        BluetoothGattService service=new BluetoothGattService(UUID.fromString(BleProbeProtocol.SERVICE),BluetoothGattService.SERVICE_TYPE_PRIMARY);
        service.addCharacteristic(new BluetoothGattCharacteristic(UUID.fromString(BleProbeProtocol.VALUE),BluetoothGattCharacteristic.PROPERTY_READ|BluetoothGattCharacteristic.PROPERTY_WRITE,BluetoothGattCharacteristic.PERMISSION_READ|BluetoothGattCharacteristic.PERMISSION_WRITE));
        service.addCharacteristic(new BluetoothGattCharacteristic(UUID.fromString(WifiProvisionSession.VALUE),BluetoothGattCharacteristic.PROPERTY_READ|BluetoothGattCharacteristic.PROPERTY_WRITE,BluetoothGattCharacteristic.PERMISSION_READ|BluetoothGattCharacteristic.PERMISSION_WRITE));
        if (!server.addService(service)) show(run,UiContract.T_BLE_UNAVAILABLE,UiContract.T_BLE_RETRY);
      }
    } catch (RuntimeException e) { Log.w("NeoTalkieBLE","BLE startup failed: "+e.getClass().getSimpleName()); show(run,UiContract.T_BLE_UNAVAILABLE,UiContract.T_BLE_RETRY); }
  }
  private void prepareWifi(final int run,final BluetoothDevice device){
    if(wifiSession!=null)wifiSession.destroy();wifiSession=null;pairingCode="";final int ticket=++sessionTicket;
    new Thread(new Runnable(){public void run(){
      try{
        WifiProvisionSession created=new WifiProvisionSession();
        synchronized(lock){if(closed || generation!=run || sessionTicket!=ticket || !device.equals(peer)){created.destroy();return;}wifiSession=created;}
      }catch(Exception e){show(run,UiContract.T_BLE_UNAVAILABLE,UiContract.T_BLE_RETRY);}
    }},"wifi-session").start();
  }
  private String wifiState(int code){
    if(code==WifiProvisionSession.SUCCESS)return UiContract.T_WIFI_SUCCESS;
    if(code==WifiProvisionSession.AUTH)return UiContract.T_WIFI_AUTH;
    if(code==WifiProvisionSession.TIMEOUT)return UiContract.T_WIFI_TIMEOUT;
    if(code==WifiProvisionSession.CANCELLED)return UiContract.T_WIFI_CANCELLED;
    return UiContract.T_WIFI_FAILED;
  }
  private void advertise(final int run) {
    advertising = new AdvertiseCallback(){
      public void onStartSuccess(AdvertiseSettings settings) { Log.i("NeoTalkieBLE","advertising_ready name="+broadcastName); show(run,UiContract.T_BLE_READY,UiContract.T_BLE_SCAN); }
      public void onStartFailure(int code) { Log.w("NeoTalkieBLE","advertising_failed code="+code); show(run,UiContract.T_BLE_UNAVAILABLE,UiContract.T_BLE_RETRY); }
    };
    try { advertiser.startAdvertising(new AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).build(),
      new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(UUID.fromString(BleProbeProtocol.SERVICE))).build(),
      new AdvertiseData.Builder().setIncludeDeviceName(true).build(),advertising);
    } catch(RuntimeException e) { show(run,UiContract.T_BLE_UNAVAILABLE,UiContract.T_BLE_RETRY); }
  }
  private void end() {
    synchronized(lock) {
      closed=true; generation++; sessionTicket++; main.removeCallbacksAndMessages(null);
      try { if (advertiser!=null && advertising!=null) advertiser.stopAdvertising(advertising); } catch (RuntimeException ignored) {}
      if (server!=null) { try { if(peer!=null)server.cancelConnection(peer); server.close(); } catch(RuntimeException ignored){} }
      if(connector!=null){connector.cancel();connector=null;}
      if(wifiSession!=null){wifiSession.destroy();wifiSession=null;}
      server=null; peer=null; advertiser=null; advertising=null; protocol.reset();
      if(adapter!=null) {
        try { if(originalName!=null)adapter.setName(originalName); if(!wasEnabled)adapter.disable(); } catch(RuntimeException ignored){}
      }
      adapter=null; originalName=null;
    }
    ScreenPower.busy(this,false);
  }
  public boolean dispatchKeyEvent(KeyEvent e) {
    if (PttNavigation.key(this,e)) return true;
    if (volume.handleKey(e,false)) return true;
    if (e.getKeyCode()==KeyEvent.KEYCODE_BACK) { if(e.getAction()==KeyEvent.ACTION_UP)finish(); return true; }
    if (e.getKeyCode()==KeyEvent.KEYCODE_DPAD_CENTER || e.getKeyCode()==KeyEvent.KEYCODE_ENTER) {
      if(e.getAction()==KeyEvent.ACTION_UP){synchronized(lock){if(wifiSession!=null && wifiSession.approve()){show(generation,UiContract.T_WIFI_APPROVED,UiContract.T_WIFI_PHONE);return true;}}}
      if(e.getAction()==KeyEvent.ACTION_UP && (closed || status.equals(UiContract.T_BLE_UNAVAILABLE) || status.equals(UiContract.T_BLE_FAILED))) begin();
      return true;
    }
    return super.dispatchKeyEvent(e);
  }
  final class Panel extends View {
    final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    Panel(){super(BleSetupActivity.this);setFocusable(true);}
    void text(Canvas c,String value,int y,int size,int color){paint.setColor(color);paint.setTextSize(size);c.drawText(value,6,y,paint);}
    protected void onDraw(Canvas c){
      c.save();c.scale(getWidth()/160f,getHeight()/128f);c.drawColor(UiContract.C_BG);strip.draw(c);
      text(c,UiContract.T_BLE_TITLE,47,18,UiContract.C_PAGE_TITLE);
      text(c,pairingCode.isEmpty() ? broadcastName : pairingCode,65,14,UiContract.C_ACCENT);
      text(c,status,84,16,UiContract.C_INK);
      text(c,detail,102,12,UiContract.C_INK);
      paint.setColor(UiContract.C_VOLUME_BG);c.drawRect(0,108,160,128,paint);
      text(c,closed ? UiContract.T_BLE_RETRY : UiContract.T_BLE_EXIT,123,12,UiContract.C_ACCENT);
      volume.draw(c);c.restore();
      setContentDescription(broadcastName+" "+status+" "+detail);
    }
  }
}
