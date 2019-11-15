package com.getcapacitor;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;

import com.orhanobut.logger.BuildConfig;
import com.orhanobut.logger.DiskLogAdapter;
import com.orhanobut.logger.Logger;
import com.tencent.smtt.sdk.QbSdk;
import com.tencent.smtt.sdk.TbsDownloader;
import com.tencent.smtt.sdk.TbsListener;
import com.tencent.smtt.sdk.WebView;
import com.getcapacitor.android.R;
import com.getcapacitor.cordova.MockCordovaInterfaceImpl;
import com.getcapacitor.cordova.MockCordovaWebViewImpl;
import com.getcapacitor.plugin.App;

import org.apache.cordova.ConfigXmlParser;
import org.apache.cordova.CordovaPreferences;
import org.apache.cordova.PluginEntry;
import org.apache.cordova.PluginManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

public class BridgeActivity extends AppCompatActivity {

  private static final int NOT_NOTICE = 2;//如果勾选了不再询问
  private AlertDialog alertDialog;
  private AlertDialog mDialog;

  QbSdk.PreInitCallback cb = new QbSdk.PreInitCallback() {

    @Override
    public void onViewInitFinished(boolean arg0) {
      // TODO Auto-generated method stub
      //x5內核初始化完成的回调，为true表示x5内核加载成功，否则表示x5内核加载失败，会自动切换到系统内核。
      Log.d("App", "app :" + "______________________________________onViewInitFinished is " + arg0);

      if (arg0) {
        Intent intent = new Intent(BridgeActivity.this, NewBridgeActivity.class);
        onDestroy();
        startActivity(intent);
        overridePendingTransition(0,0);
      } else {
        InitContext(getApplication(), cb);
      }
    }


    @Override
    public void onCoreInitFinished() {
      // TODO Auto-generated method stub
    }
  };

  String[] ManifestPer = new String[]{Manifest.permission.READ_PHONE_STATE,
          Manifest.permission.WRITE_EXTERNAL_STORAGE,
          Manifest.permission.ACCESS_NETWORK_STATE,
          Manifest.permission.ACCESS_WIFI_STATE,
          Manifest.permission.INTERNET,
          Manifest.permission.REQUEST_INSTALL_PACKAGES
  };
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    //TODO 添加日志文件
    Logger.addLogAdapter(new DiskLogAdapter(SingleDiskLogAdapter.WriteHandler.getFormatStrategy(getApplicationContext(),"logger")){
      @Override public boolean isLoggable(int priority, String tag) {
        return !BuildConfig.DEBUG;
      }
    });
    myRequetPermission();
  }

  public  void InitContext(Context var0,QbSdk.PreInitCallback var1){
    //x5内核初始化接口
    QbSdk.initX5Environment(var0, var1);
  }

  private void myRequetPermission() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(this, ManifestPer, 1);
    }else{
      InitContext(getApplicationContext(),cb);
    }
  }
  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    Context currentContext = this.getApplicationContext();
    boolean isFlag = true;
    if (requestCode == 1) {
      //x5内核初始化接口
      InitContext(getApplicationContext(),cb);
      for (int i = 0; i < permissions.length; i++) {
        if (grantResults[i] == PERMISSION_GRANTED) {
          isFlag = true;
        } else {
          if (!ActivityCompat.shouldShowRequestPermissionRationale(this, permissions[i])){//用户选择了禁止不再询问

            AlertDialog.Builder builder = new AlertDialog.Builder(BridgeActivity.this);
            builder.setTitle("permission")
                    .setMessage("点击允许才可以使用我们的app哦")
                    .setPositiveButton("去允许", new DialogInterface.OnClickListener() {
                      public void onClick(DialogInterface dialog, int id) {
                        if (mDialog != null && mDialog.isShowing()) {
                          mDialog.dismiss();
                        }
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        Uri uri = Uri.fromParts("package", getPackageName(), null);//注意就是"package",不用改成自己的包名
                        intent.setData(uri);
                        startActivityForResult(intent, NOT_NOTICE);
                      }
                    });
          }else {//选择禁止
            AlertDialog.Builder builder = new AlertDialog.Builder(BridgeActivity.this);
            builder.setTitle("permission")
                    .setMessage("点击允许才可以使用我们的app哦")
                    .setPositiveButton("去允许", new DialogInterface.OnClickListener() {
                      public void onClick(DialogInterface dialog, int id) {
                        if (alertDialog != null && alertDialog.isShowing()) {
                          alertDialog.dismiss();
                        }
                        ActivityCompat.requestPermissions(BridgeActivity.this,
                                ManifestPer, 1);
                      }
                    });
            alertDialog = builder.create();
            alertDialog.setCanceledOnTouchOutside(false);
            alertDialog.show();
          }
        }
      }
    }
  }

  protected void init(Bundle savedInstanceState, List<Class<? extends Plugin>> plugins) {

  }
  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if(requestCode==NOT_NOTICE){
      myRequetPermission();//由于不知道是否选择了允许所以需要再次判断
    }
  }
}
