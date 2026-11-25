package com.neotalkie.device;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

/** Signature-protected handoff state; survives Activity changes, not process death. */
public final class PttStateProvider extends ContentProvider {
  public boolean onCreate() { return true; }
  public Bundle call(String method, String token, Bundle extras) {
    if (getContext().checkCallingOrSelfPermission("com.neotalkie.launcher.permission.CONTROL")
        != android.content.pm.PackageManager.PERMISSION_GRANTED) throw new SecurityException();
    if (token == null) throw new IllegalArgumentException("Missing handoff token");
    if ("release".equals(method)) PttNavigation.finishLease(token);
    else if (!"held".equals(method)) throw new IllegalArgumentException("Unknown PTT operation");
    Bundle result = new Bundle();
    result.putBoolean("held", PttNavigation.lease.isHeld(token, SystemClock.elapsedRealtime()));
    return result;
  }
  public Cursor query(Uri u,String[] p,String s,String[] a,String o) { throw new UnsupportedOperationException(); }
  public String getType(Uri u) { return null; }
  public Uri insert(Uri u,ContentValues v) { throw new UnsupportedOperationException(); }
  public int delete(Uri u,String s,String[] a) { throw new UnsupportedOperationException(); }
  public int update(Uri u,ContentValues v,String s,String[] a) { throw new UnsupportedOperationException(); }
}
