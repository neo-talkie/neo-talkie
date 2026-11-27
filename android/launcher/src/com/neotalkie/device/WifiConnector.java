package com.neotalkie.device;

import android.content.*;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import java.util.*;

/** Main-thread Wi-Fi transaction. Success means target association and an IPv4 lease, not Internet reachability. */
final class WifiConnector {
  interface Listener {
    void progress(int diagnostic);
    void finished(int result,int value);
  }
  static final int PHASE_ENABLING=1, PHASE_CONFIGURING=2, PHASE_SCANNING=3, PHASE_ASSOCIATING=4,
      PHASE_AUTHENTICATING=5, PHASE_OBTAINING_IP=6, PHASE_VERIFYING=7, PHASE_DISCONNECTED=8;
  static final int FLAG_SCAN_SEEN=1, FLAG_24G=2, FLAG_5G=4, FLAG_WPA3=8,
      FLAG_WPA2=16, FLAG_OPEN=32, FLAG_AUTH_ERR=64, FLAG_HAS_SCAN=128;
  private final Context context;
  private final WifiManager wifi;
  private final Handler main=new Handler();
  private final Listener listener;
  private int previous=-1,target=-1,lastDiag;
  private boolean originalEnabled,added,active,registered,associationChanged,authErrorSeen;
  private long deadline,lastScanAt;
  private String targetSsid;
  private WifiProvisionSession.Credentials credentials;
  private final ArrayList<Integer> enabled=new ArrayList<Integer>();
  WifiConnector(Context context,Listener listener){this.context=context;this.listener=listener;wifi=(WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);}
  private final BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
    if(!active)return;
    if(target>=0 && WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(i.getAction())){NetworkInfo n=i.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);if(n!=null && !n.isConnected())associationChanged=true;}
    if(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION.equals(i.getAction()) && i.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR,-1)==WifiManager.ERROR_AUTHENTICATING){
      authErrorSeen=true;
      WifiInfo info=wifi.getConnectionInfo();
      if(target>=0 && info!=null && (info.getNetworkId()==target || info.getNetworkId()==-1)){
        updateDiag(PHASE_AUTHENTICATING,info,null);
        finish(WifiProvisionSession.AUTH,lastDiag);
      }
    }
  }};
  void start(WifiProvisionSession.Credentials value){
    credentials=value;targetSsid=value.ssid;active=true;authErrorSeen=false;lastDiag=0;lastScanAt=0;deadline=SystemClock.elapsedRealtime()+45000;
    if(wifi==null){finish(WifiProvisionSession.ERROR,0);return;}
    try{
      originalEnabled=wifi.isWifiEnabled();WifiInfo info=wifi.getConnectionInfo();if(info!=null)previous=info.getNetworkId();
      IntentFilter filter=new IntentFilter(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);context.registerReceiver(receiver,filter);registered=true;
      if(!originalEnabled && !wifi.setWifiEnabled(true)){finish(WifiProvisionSession.ERROR,0);return;}
      updateDiag(PHASE_ENABLING,info,null);
      main.post(poll);
    }catch(RuntimeException e){finish(WifiProvisionSession.ERROR,lastDiag);}
  }
  private final Runnable poll=new Runnable(){public void run(){
    if(!active)return;
    try{
      long now=SystemClock.elapsedRealtime();
      if(now>=deadline){finish(WifiProvisionSession.TIMEOUT,lastDiag);return;}
      if(wifi.getWifiState()==WifiManager.WIFI_STATE_ENABLED){
        if(target<0){
          updateDiag(PHASE_CONFIGURING,wifi.getConnectionInfo(),null);
          if(!configure()){finish(WifiProvisionSession.SAVE,lastDiag);return;}
        }
        WifiInfo info=wifi.getConnectionInfo();
        NetworkInfo network=((ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE)).getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        if(info==null || info.getNetworkId()!=target || info.getSupplicantState()!=SupplicantState.COMPLETED || network==null || !network.isConnected())associationChanged=true;
        updateDiag(phaseOf(info,network),info,network);
        if((lastDiag & (FLAG_SCAN_SEEN<<8))==0 && now-lastScanAt>=8000){
          lastScanAt=now;
          try{wifi.startScan();}catch(RuntimeException ignored){}
        }
        if(associationChanged && info!=null && info.getNetworkId()==target && info.getSupplicantState()==SupplicantState.COMPLETED && info.getIpAddress()!=0 && network!=null && network.isConnected()){
          if(!wifi.saveConfiguration()){finish(WifiProvisionSession.SAVE,lastDiag);return;}
          finish(WifiProvisionSession.SUCCESS,info.getIpAddress());return;
        }
      }else{
        updateDiag(PHASE_ENABLING,null,null);
      }
      main.postDelayed(this,500);
    }catch(RuntimeException e){finish(WifiProvisionSession.ERROR,lastDiag);}
  }};
  private int phaseOf(WifiInfo info,NetworkInfo network){
    SupplicantState s=info==null?null:info.getSupplicantState();
    boolean onTarget=info!=null && target>=0 && info.getNetworkId()==target;
    if(onTarget && s==SupplicantState.COMPLETED){
      if(info.getIpAddress()==0)return PHASE_OBTAINING_IP;
      return PHASE_VERIFYING;
    }
    if(network!=null && network.getDetailedState()==NetworkInfo.DetailedState.OBTAINING_IPADDR)return PHASE_OBTAINING_IP;
    if(s==SupplicantState.AUTHENTICATING || s==SupplicantState.FOUR_WAY_HANDSHAKE || s==SupplicantState.GROUP_HANDSHAKE)return PHASE_AUTHENTICATING;
    if(s==SupplicantState.ASSOCIATING || s==SupplicantState.ASSOCIATED)return PHASE_ASSOCIATING;
    if(s==SupplicantState.SCANNING)return PHASE_SCANNING;
    if(s==SupplicantState.DISCONNECTED || s==SupplicantState.INACTIVE || s==SupplicantState.INTERFACE_DISABLED)return PHASE_DISCONNECTED;
    return PHASE_SCANNING;
  }
  private static String normalizeSsid(String s){
    if(s==null)return "";
    return s.replace('\u2019','\'').replace('\u2018','\'').replace('\u201c','"').replace('\u201d','"').trim().toLowerCase(Locale.US);
  }
  private void updateDiag(int phase,WifiInfo info,NetworkInfo network){
    int flags=authErrorSeen?FLAG_AUTH_ERR:0;
    int bestRssi=0;
    boolean fuzzyMatch=false;
    try{
      List<ScanResult> scans=wifi.getScanResults();
      if(scans!=null && !scans.isEmpty()){
        flags|=FLAG_HAS_SCAN;
        String wanted=targetSsid==null?"":targetSsid;
        String wantedNorm=normalizeSsid(wanted);
        for(ScanResult r:scans){
          if(r==null || r.SSID==null)continue;
          if(wanted.equals(r.SSID)){
            flags|=FLAG_SCAN_SEEN;
            if(r.frequency>=2400 && r.frequency<2500)flags|=FLAG_24G;
            else if(r.frequency>=4900)flags|=FLAG_5G;
            String cap=r.capabilities==null?"":r.capabilities.toUpperCase(Locale.US);
            if(cap.contains("SAE") || cap.contains("WPA3"))flags|=FLAG_WPA3;
            if(cap.contains("WPA") || cap.contains("PSK"))flags|=FLAG_WPA2;
            if(!cap.contains("WPA") && !cap.contains("WEP") && !cap.contains("EAP") && !cap.contains("SAE"))flags|=FLAG_OPEN;
            int abs=r.level<0?Math.min(255,-r.level):0;
            if(abs>0 && (bestRssi==0 || abs<bestRssi))bestRssi=abs;
          }else if(!wantedNorm.isEmpty() && wantedNorm.equals(normalizeSsid(r.SSID))){
            fuzzyMatch=true;
          }
        }
      }
    }catch(RuntimeException ignored){}
    int extra=0;
    if(info!=null && info.getSupplicantState()!=null)extra|=(info.getSupplicantState().ordinal() & 0x0f);
    if(info!=null && target>=0 && info.getNetworkId()==target)extra|=0x10;
    if(info!=null && info.getIpAddress()!=0)extra|=0x20;
    if(network!=null && network.isConnected())extra|=0x40;
    if((flags & FLAG_SCAN_SEEN)==0 && fuzzyMatch)extra|=0x80;
    int diag=(phase & 0xff) | ((flags & 0xff)<<8) | ((bestRssi & 0xff)<<16) | ((extra & 0xff)<<24);
    if(diag!=lastDiag){
      lastDiag=diag;
      listener.progress(diag);
    }
  }
  private static String quote(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
  private boolean configure(){
    List<WifiConfiguration> known=wifi.getConfiguredNetworks();
    WifiConfiguration config=new WifiConfiguration();config.SSID=quote(credentials.ssid);config.hiddenSSID=credentials.hidden;
    if(known!=null)for(WifiConfiguration item:known){
      if(item.status!=WifiConfiguration.Status.DISABLED)enabled.add(item.networkId);
      if(config.SSID.equals(item.SSID))config.networkId=item.networkId;
    }
    if(credentials.open)config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
    else{
      config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
      config.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
      config.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
      config.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
      config.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
      config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
      config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
      config.preSharedKey=quote(credentials.password);
    }
    // This explicitly updates a saved target's password; masked old passwords cannot be restored by API 22 apps.
    added=config.networkId<0;target=added?wifi.addNetwork(config):wifi.updateNetwork(config);credentials.clear();credentials=null;
    if(target<0)return false;
    lastScanAt=SystemClock.elapsedRealtime();
    try{wifi.startScan();}catch(RuntimeException ignored){}
    wifi.disconnect();
    return wifi.enableNetwork(target,true) && wifi.reconnect();
  }
  void cancel(){if(active)finish(WifiProvisionSession.CANCELLED,lastDiag);}
  private void finish(int result,int value){
    if(!active)return;active=false;main.removeCallbacksAndMessages(null);
    if(registered){context.unregisterReceiver(receiver);registered=false;}
    if(credentials!=null){credentials.clear();credentials=null;}
    targetSsid=null;
    try{
      // Restore auto-connect eligibility of networks temporarily disabled by enableNetwork(...,true).
      for(int id:enabled)if(id!=target)wifi.enableNetwork(id,false);
      if(result!=WifiProvisionSession.SUCCESS && wifi!=null){
        if(added && target>=0)wifi.removeNetwork(target);
        if(previous>=0 && previous!=target){wifi.enableNetwork(previous,true);wifi.reconnect();}
        if(!originalEnabled)wifi.setWifiEnabled(false);
      }
    }catch(RuntimeException ignored){}
    listener.finished(result,value);
  }
}
