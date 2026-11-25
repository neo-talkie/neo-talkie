package com.neotalkie.device;

import android.content.*;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import java.util.*;

/** Main-thread Wi-Fi transaction. Success means target association and an IPv4 lease, not Internet reachability. */
final class WifiConnector {
  interface Listener { void finished(int result,int ip); }
  private final Context context;
  private final WifiManager wifi;
  private final Handler main=new Handler();
  private final Listener listener;
  private int previous=-1,target=-1;
  private boolean originalEnabled,added,active,registered,associationChanged;
  private long deadline;
  private WifiProvisionSession.Credentials credentials;
  private final ArrayList<Integer> enabled=new ArrayList<Integer>();
  WifiConnector(Context context,Listener listener){this.context=context;this.listener=listener;wifi=(WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);}
  private final BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
    if(!active)return;
    if(target>=0 && WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(i.getAction())){NetworkInfo n=i.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);if(n!=null && !n.isConnected())associationChanged=true;}
    if(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION.equals(i.getAction()) && i.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR,-1)==WifiManager.ERROR_AUTHENTICATING){
      WifiInfo info=wifi.getConnectionInfo();
      if(target>=0 && info!=null && (info.getNetworkId()==target || info.getNetworkId()==-1))finish(WifiProvisionSession.AUTH,0);
    }
  }};
  void start(WifiProvisionSession.Credentials value){
    credentials=value;active=true;deadline=SystemClock.elapsedRealtime()+45000;
    if(wifi==null){finish(WifiProvisionSession.ERROR,0);return;}
    try{
      originalEnabled=wifi.isWifiEnabled();WifiInfo info=wifi.getConnectionInfo();if(info!=null)previous=info.getNetworkId();
      IntentFilter filter=new IntentFilter(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);context.registerReceiver(receiver,filter);registered=true;
      if(!originalEnabled && !wifi.setWifiEnabled(true)){finish(WifiProvisionSession.ERROR,0);return;}
      main.post(poll);
    }catch(RuntimeException e){finish(WifiProvisionSession.ERROR,0);}
  }
  private final Runnable poll=new Runnable(){public void run(){
    if(!active)return;
    try{
      if(SystemClock.elapsedRealtime()>=deadline){finish(WifiProvisionSession.TIMEOUT,0);return;}
      if(wifi.getWifiState()==WifiManager.WIFI_STATE_ENABLED){
        if(target<0 && !configure()){finish(WifiProvisionSession.SAVE,0);return;}
        WifiInfo info=wifi.getConnectionInfo();
        NetworkInfo network=((ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE)).getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        if(info==null || info.getNetworkId()!=target || info.getSupplicantState()!=SupplicantState.COMPLETED || network==null || !network.isConnected())associationChanged=true;
        if(associationChanged && info!=null && info.getNetworkId()==target && info.getSupplicantState()==SupplicantState.COMPLETED && info.getIpAddress()!=0 && network!=null && network.isConnected()){
          if(!wifi.saveConfiguration()){finish(WifiProvisionSession.SAVE,0);return;}
          finish(WifiProvisionSession.SUCCESS,info.getIpAddress());return;
        }
      }
      main.postDelayed(this,500);
    }catch(RuntimeException e){finish(WifiProvisionSession.ERROR,0);}
  }};
  private static String quote(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
  private boolean configure(){
    List<WifiConfiguration> known=wifi.getConfiguredNetworks();
    WifiConfiguration config=new WifiConfiguration();config.SSID=quote(credentials.ssid);config.hiddenSSID=credentials.hidden;
    if(known!=null)for(WifiConfiguration item:known){
      if(item.status!=WifiConfiguration.Status.DISABLED)enabled.add(item.networkId);
      if(config.SSID.equals(item.SSID))config.networkId=item.networkId;
    }
    if(credentials.open)config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
    else{config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);config.preSharedKey=quote(credentials.password);}
    // This explicitly updates a saved target's password; masked old passwords cannot be restored by API 22 apps.
    added=config.networkId<0;target=added?wifi.addNetwork(config):wifi.updateNetwork(config);credentials.clear();credentials=null;
    if(target<0)return false;
    wifi.disconnect();
    return wifi.enableNetwork(target,true) && wifi.reconnect();
  }
  void cancel(){if(active)finish(WifiProvisionSession.CANCELLED,0);}
  private void finish(int result,int ip){
    if(!active)return;active=false;main.removeCallbacksAndMessages(null);
    if(registered){context.unregisterReceiver(receiver);registered=false;}
    if(credentials!=null){credentials.clear();credentials=null;}
    try{
      // Restore auto-connect eligibility of networks temporarily disabled by enableNetwork(...,true).
      for(int id:enabled)if(id!=target)wifi.enableNetwork(id,false);
      if(result!=WifiProvisionSession.SUCCESS && wifi!=null){
        if(added && target>=0)wifi.removeNetwork(target);
        if(previous>=0 && previous!=target){wifi.enableNetwork(previous,true);wifi.reconnect();}
        if(!originalEnabled)wifi.setWifiEnabled(false);
      }
    }catch(RuntimeException ignored){}
    listener.finished(result,ip);
  }
}
