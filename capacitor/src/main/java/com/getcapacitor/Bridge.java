package com.getcapacitor;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.tencent.smtt.export.external.interfaces.WebResourceRequest;
import com.tencent.smtt.export.external.interfaces.WebResourceResponse;
import com.tencent.smtt.sdk.ValueCallback;
//import android.webkit.WebResourceRequest;
//import android.webkit.WebResourceResponse;
import com.tencent.smtt.sdk.WebSettings;
import com.tencent.smtt.sdk.WebView;
import com.tencent.smtt.sdk.WebViewClient;
import android.content.SharedPreferences;

import com.getcapacitor.android.BuildConfig;
import com.getcapacitor.plugin.Accessibility;
import com.getcapacitor.plugin.App;
import com.getcapacitor.plugin.Browser;
import com.getcapacitor.plugin.Camera;
import com.getcapacitor.plugin.Clipboard;
import com.getcapacitor.plugin.Console;
import com.getcapacitor.plugin.Device;
import com.getcapacitor.plugin.Filesystem;
import com.getcapacitor.plugin.Geolocation;
import com.getcapacitor.plugin.Haptics;
import com.getcapacitor.plugin.Keyboard;
import com.getcapacitor.plugin.LocalNotifications;
import com.getcapacitor.plugin.Modals;
import com.getcapacitor.plugin.Network;
import com.getcapacitor.plugin.Permissions;
import com.getcapacitor.plugin.Photos;
import com.getcapacitor.plugin.PushNotifications;
import com.getcapacitor.plugin.Share;
import com.getcapacitor.plugin.SplashScreen;
import com.getcapacitor.plugin.StatusBar;
import com.getcapacitor.plugin.Storage;
import com.getcapacitor.plugin.background.BackgroundTask;
import com.getcapacitor.ui.Toast;
import com.getcapacitor.util.HostMask;

import org.apache.cordova.CordovaInterfaceImpl;
import org.apache.cordova.CordovaPreferences;
import org.apache.cordova.PluginManager;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.ArrayList;


/**
 * The Bridge class is the main engine of Capacitor. It manages
 * loading and communicating with all Plugins,
 * proxying Native events to Plugins, executing Plugin methods,
 * communicating with the WebView, and a whole lot more.
 *
 * Generally, you'll not use Bridge directly, instead, extend from BridgeActivity
 * to get a WebView instance and proxy native events automatically.
 *
 * If you want to use this Bridge in an existing Android app, please
 * see the source for BridgeActivity for the methods you'll need to
 * pass through to Bridge:
 * <a href="https://github.com/ionic-team/capacitor/blob/master/android/capacitor/src/main/java/com/getcapacitor/BridgeActivity.java">
 *   BridgeActivity.java</a>
 */
public class Bridge {

  private static final String LOG_TAG = LogUtils.getCoreTag();
  private static final String PREFS_NAME = "CapacitorSettings";
  private static final String BUNDLE_LAST_PLUGIN_ID_KEY = "capacitorLastActivityPluginId";
  private static final String BUNDLE_LAST_PLUGIN_CALL_METHOD_NAME_KEY = "capacitorLastActivityPluginMethod";
  private static final String BUNDLE_PLUGIN_CALL_OPTIONS_SAVED_KEY = "capacitorLastPluginCallOptions";
  private static final String BUNDLE_PLUGIN_CALL_BUNDLE_KEY = "capacitorLastPluginCallBundle";
  private static final String LAST_BINARY_VERSION_CODE = "lastBinaryVersionCode";
  private static final String LAST_BINARY_VERSION_NAME = "lastBinaryVersionName";

  // The name of the directory we use to look for index.html and the rest of our web assets
  public static final String DEFAULT_WEB_ASSET_DIR = "public";
  public static final String CAPACITOR_HTTP_SCHEME = "http";
  public static final String CAPACITOR_HTTPS_SCHEME = "https";
  public static final String CAPACITOR_FILE_START = "/_capacitor_file_";
  public static final String CAPACITOR_CONTENT_START = "/_capacitor_content_";

  // Loaded Capacitor config
  private JSONObject config = new JSONObject();

  // A reference to the main activity for the app
  private final Activity context;
  private WebViewLocalServer localServer;
  private String localUrl;
  private String appUrl;
  private String appUrlConfig;
  private HostMask appAllowNavigationMask;
  // A reference to the main WebView for the app
  private final WebView webView;
  public final CordovaInterfaceImpl cordovaInterface;
  private CordovaPreferences preferences;

  // Our MessageHandler for sending and receiving data to the WebView
  private final MessageHandler msgHandler;

  // The ThreadHandler for executing plugin calls
  private final HandlerThread handlerThread = new HandlerThread("CapacitorPlugins");

  // Our Handler for posting plugin calls. Created from the ThreadHandler
  private Handler taskHandler = null;

  private final List<Class<? extends Plugin>> initialPlugins;

  // A map of Plugin Id's to PluginHandle's
  private Map<String, PluginHandle> plugins = new HashMap<>();

  // Stored plugin calls that we're keeping around to call again someday
  private Map<String, PluginCall> savedCalls = new HashMap<>();

  // Store a plugin that started a new activity, in case we need to resume
  // the app and return that data back
  private PluginCall pluginCallForLastActivity;

  // Any URI that was passed to the app on start
  private Uri intentUri;


  /**
   * Create the Bridge with a reference to the main {@link Activity} for the
   * app, and a reference to the {@link WebView} our app will use.
   * @param context
   * @param webView
   */
  public Bridge(Activity context, WebView webView, List<Class<? extends Plugin>> initialPlugins, CordovaInterfaceImpl cordovaInterface, PluginManager pluginManager, CordovaPreferences preferences) {
    this.context = context;
    this.webView = webView;
    this.initialPlugins = initialPlugins;
    this.cordovaInterface = cordovaInterface;
    this.preferences = preferences;



    // Start our plugin execution threads and handlers
    handlerThread.start();
    taskHandler = new Handler(handlerThread.getLooper());

    Config.load(getActivity());

    // Initialize web view and message handler for it
    this.initWebView();
    this.msgHandler = new MessageHandler(this, webView, pluginManager);

    // Grab any intent info that our app was launched with
    Intent intent = context.getIntent();
    Uri intentData = intent.getData();
    this.intentUri = intentData;

    // Register our core plugins
    this.registerAllPlugins();

    this.loadWebView();
  }

  private void loadWebView() {
    appUrlConfig = Config.getString("server.url");
    String[] appAllowNavigationConfig = Config.getArray("server.allowNavigation");

    ArrayList<String> authorities = new ArrayList<String>();
    if (appAllowNavigationConfig != null) {
      authorities.addAll(Arrays.asList(appAllowNavigationConfig));
    }
    this.appAllowNavigationMask = HostMask.Parser.parse(appAllowNavigationConfig);

    String authority = Config.getString("server.hostname", "localhost");
    authorities.add(authority);

    String scheme = this.getScheme();

    localUrl = scheme + "://" + authority;

    if (appUrlConfig != null) {
      try {
        URL appUrlObject = new URL(appUrlConfig);
        authorities.add(appUrlObject.getAuthority());
      } catch (Exception ex) {
      }

      if (BuildConfig.DEBUG) {
        Toast.show(getContext(), "Using app server " + appUrlConfig.toString());
      }
    }

    final boolean html5mode = Config.getBoolean("server.html5mode", true);

    // Start the local web server
    localServer = new WebViewLocalServer(context, this, getJSInjector(), authorities, html5mode);
    localServer.hostAssets(DEFAULT_WEB_ASSET_DIR);

    if (appUrlConfig == null) {
      appUrl = localUrl;
      // custom URL schemes requires path ending with /
      if (!scheme.equals(Bridge.CAPACITOR_HTTP_SCHEME) && !scheme.equals(CAPACITOR_HTTPS_SCHEME)) {
        appUrl += "/";
      }
    } else {
      appUrl = appUrlConfig;
    }

    Log.d(LOG_TAG, "Loading app at " + appUrl);

    webView.setWebChromeClient(new BridgeWebChromeClient(this));
    webView.setWebViewClient(new WebViewClient() {
      @Override
      public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        return localServer.shouldInterceptRequest(request);
      }

      @Override
      public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        Uri url = request.getUrl();
        return launchIntent(url);
      }

      @Override
      public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return launchIntent(Uri.parse(url));
      }

      private boolean launchIntent(Uri url) {
        if (!url.toString().contains(appUrl) && !appAllowNavigationMask.matches(url.getHost())) {
          try {
            Intent openIntent = new Intent(Intent.ACTION_VIEW, url);
            getContext().startActivity(openIntent);
          } catch (ActivityNotFoundException e) {
            // TODO - trigger an event
          }
          return true;
        }
        return false;
      }
    });

    if (!isDeployDisabled() && !isNewBinary()) {
      SharedPreferences prefs = getContext().getSharedPreferences(com.getcapacitor.plugin.WebView.WEBVIEW_PREFS_NAME, Activity.MODE_PRIVATE);
      String path = prefs.getString(com.getcapacitor.plugin.WebView.CAP_SERVER_PATH, null);
      if (path != null && !path.isEmpty() && new File(path).exists()) {
        setServerBasePath(path);
      }
    }
    // Get to work
    webView.loadUrl(appUrl);
  }


  private boolean isNewBinary() {
    String versionCode = "";
    String versionName = "";
    SharedPreferences prefs = getContext().getSharedPreferences(com.getcapacitor.plugin.WebView.WEBVIEW_PREFS_NAME, Activity.MODE_PRIVATE);
    String lastVersionCode = prefs.getString(LAST_BINARY_VERSION_CODE, null);
    String lastVersionName = prefs.getString(LAST_BINARY_VERSION_NAME, null);

    try {
      PackageInfo pInfo = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
      versionCode = Integer.toString(pInfo.versionCode);
      versionName = pInfo.versionName;
    } catch(Exception ex) {
      Log.e(LOG_TAG, "Unable to get package info", ex);
    }

    if (!versionCode.equals(lastVersionCode) || !versionName.equals(lastVersionName)) {
      SharedPreferences.Editor editor = prefs.edit();
      editor.putString(LAST_BINARY_VERSION_CODE, versionCode);
      editor.putString(LAST_BINARY_VERSION_NAME, versionName);
      editor.putString(com.getcapacitor.plugin.WebView.CAP_SERVER_PATH, "");
      editor.apply();
      return true;
    }
    return false;
  }

  public boolean isDeployDisabled() {
    return preferences.getBoolean("DisableDeploy", false);
  }


  public void handleAppUrlLoadError(Exception ex) {
    if (ex instanceof SocketTimeoutException) {
      if (BuildConfig.DEBUG) {
        Toast.show(getContext(), "Unable to load app. Are you sure the server is running at " + appUrl + "?");
      }
      Log.e(LOG_TAG, "Unable to load app. Ensure the server is running at " + appUrl + ", or modify the " +
          "appUrl setting in capacitor.config.json (make sure to npx cap copy after to commit changes).", ex);
    }
  }

  public boolean isDevMode() {
    return (getActivity().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
  }

  /**
   * Get the Context for the App
   * @return
   */
  public Context getContext() {
    return this.context;
  }

  /**
   * Get the activity for the app
   * @return
   */
  public Activity getActivity() { return this.context; }

  /**
   * Get the core WebView under Capacitor's control
   * @return
   */
  public WebView getWebView() {
    return this.webView;
  }


  /**
   * Get the URI that was used to launch the app (if any)
   * @return
   */
  public Uri getIntentUri() {
    return intentUri;
  }

  /**
   * Get scheme that is used to serve content
   * @return
   */
  public String getScheme() {
      return Config.getString("server.androidScheme", CAPACITOR_HTTP_SCHEME);
  }

  /*
  public void registerPlugins() {
    Log.d(LOG_TAG, "Finding plugins");
    try {
      Enumeration<URL> roots = getClass().getClassLoader().getResources("");
      while (roots.hasMoreElements()) {
        URL url = roots.nextElement();
        Log.d(LOG_TAG, "CLASSAPTH ROOT: " + url.getPath());
        //File root = new File(url.getPath());
      }
    } catch(Exception ex) {
      Log.e(LOG_TAG, "Unable to query for plugin classes", ex);
    }
  }
  */

  public void reset() {
    savedCalls = new HashMap<>();
  }


  /**
   * Initialize the WebView, setting required flags
   */
  private void initWebView() {
    WebSettings settings = webView.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setGeolocationEnabled(true);
    settings.setDatabaseEnabled(true);
    settings.setAppCacheEnabled(true);
    settings.setMediaPlaybackRequiresUserGesture(false);
    settings.setJavaScriptCanOpenWindowsAutomatically(true);
    if (Config.getBoolean("android.allowMixedContent", false)) {
    //  settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
      settings.setMixedContentMode(0);
    }
    String backgroundColor = Config.getString("android.backgroundColor" , Config.getString("backgroundColor", null));
    try {
      if (backgroundColor != null) {
        webView.setBackgroundColor(Color.parseColor(backgroundColor));
      }
    } catch (IllegalArgumentException ex) {
      Log.d(LogUtils.getCoreTag(), "WebView background color not applied");
    }
    boolean defaultDebuggable = false;
    if (isDevMode()) {
      defaultDebuggable = true;
    }

    WebView.setWebContentsDebuggingEnabled(Config.getBoolean("android.webContentsDebuggingEnabled", defaultDebuggable));
  }

  /**
   * Register our core Plugin APIs
   */
  private void registerAllPlugins() {
    this.registerPlugin(App.class);
    this.registerPlugin(Accessibility.class);
    this.registerPlugin(BackgroundTask.class);
    this.registerPlugin(Browser.class);
    this.registerPlugin(Camera.class);
    this.registerPlugin(Clipboard.class);
    this.registerPlugin(Console.class);
    this.registerPlugin(Device.class);
    this.registerPlugin(LocalNotifications.class);
    this.registerPlugin(Filesystem.class);
    this.registerPlugin(Geolocation.class);
    this.registerPlugin(Haptics.class);
    this.registerPlugin(Keyboard.class);
    this.registerPlugin(Modals.class);
    this.registerPlugin(Network.class);
    this.registerPlugin(Permissions.class);
    this.registerPlugin(Photos.class);
    this.registerPlugin(PushNotifications.class);
    this.registerPlugin(Share.class);
    this.registerPlugin(SplashScreen.class);
    this.registerPlugin(StatusBar.class);
    this.registerPlugin(Storage.class);
    this.registerPlugin(com.getcapacitor.plugin.Toast.class);
    this.registerPlugin(com.getcapacitor.plugin.WebView.class);

    for (Class<? extends Plugin> pluginClass : this.initialPlugins) {
      this.registerPlugin(pluginClass);
    }
  }

  /**
   * Register additional plugins
   * @param pluginClasses the plugins to register
   */
  public void registerPlugins(Class<? extends Plugin>[] pluginClasses) {
    for (Class<? extends Plugin> plugin : pluginClasses) {
      this.registerPlugin(plugin);
    }
  }

  /**
   * Register a plugin class
   * @param pluginClass a class inheriting from Plugin
   */
  public void registerPlugin(Class<? extends Plugin> pluginClass) {
    NativePlugin pluginAnnotation = pluginClass.getAnnotation(NativePlugin.class);

    if (pluginAnnotation == null) {
      Log.e(LOG_TAG, "NativePlugin doesn't have the @NativePlugin annotation. Please add it");
      return;
    }

    String pluginId = pluginClass.getSimpleName();

    // Use the supplied name as the id if available
    if (!pluginAnnotation.name().equals("")) {
      pluginId = pluginAnnotation.name();
    }

    Log.d(LOG_TAG, "Registering plugin: " + pluginId);

    try {
      this.plugins.put(pluginId, new PluginHandle(this, pluginClass));
    } catch (InvalidPluginException ex) {
      Log.e(LOG_TAG, "NativePlugin " + pluginClass.getName() +
          " is invalid. Ensure the @NativePlugin annotation exists on the plugin class and" +
          " the class extends Plugin");
    } catch (PluginLoadException ex) {
      Log.e(LOG_TAG, "NativePlugin " + pluginClass.getName() + " failed to load", ex);
    }
  }

  public PluginHandle getPlugin(String pluginId) {
    return this.plugins.get(pluginId);
  }

  /**
   * Find the plugin handle that responds to the given request code. This will
   * fire after certain Android OS intent results/permission checks/etc.
   * @param requestCode
   * @return
   */
  public PluginHandle getPluginWithRequestCode(int requestCode) {
    for (PluginHandle handle : this.plugins.values()) {
      NativePlugin pluginAnnotation = handle.getPluginAnnotation();
      if (pluginAnnotation == null) {
        continue;
      }

      int[] requestCodes = pluginAnnotation.requestCodes();
      for (int rc : requestCodes) {
        if (rc == requestCode) {
          return handle;
        }
      }

      if (pluginAnnotation.permissionRequestCode() == requestCode) {
        return handle;
      }
    }
    return null;
  }


  /**
   * Call a method on a plugin.
   * @param pluginId the plugin id to use to lookup the plugin handle
   * @param methodName the name of the method to call
   * @param call the call object to pass to the method
   */
  public void callPluginMethod(String pluginId, final String methodName, final PluginCall call) {
    try {
      final PluginHandle plugin = this.getPlugin(pluginId);

      if (plugin == null) {
        Log.e(LOG_TAG, "unable to find plugin : " + pluginId);
        call.errorCallback("unable to find plugin : " + pluginId);
        return;
      }

      Log.v(LOG_TAG, "callback: " + call.getCallbackId() +
          ", pluginId: " + plugin.getId() +
          ", methodName: " + methodName + ", methodData: " + call.getData().toString());

      Runnable currentThreadTask = new Runnable() {
        @Override
        public void run() {
          try {
            plugin.invoke(methodName, call);

            if (call.isSaved()) {
              saveCall(call);
            }
          } catch(PluginLoadException | InvalidPluginMethodException | PluginInvocationException ex) {
            Log.e(LOG_TAG, "Unable to execute plugin method", ex);
          } catch(Exception ex) {
            Log.e(LOG_TAG, "Serious error executing plugin", ex);
          }
        }
      };

      taskHandler.post(currentThreadTask);

    } catch (Exception ex) {
      Log.e("callPluginMethod", "error : " + ex);
      call.errorCallback(ex.toString());
    }
  }

  /**
   * Evaluate JavaScript in the web view. This method
   * executes on the main thread automatically.
   * @param js the JS to execute
   * @param callback an optional ValueCallback that will synchronously recieve a value
   *                 after calling the JS
   */
  public void eval(final String js, final ValueCallback<String> callback) {
    Handler mainHandler = new Handler(context.getMainLooper());
    mainHandler.post(new Runnable() {
      @Override
      public void run() {
        webView.evaluateJavascript(js, callback);
      }
    });
  }

  public void logToJs(final String message, final String level) {
    eval("window.Capacitor.logJs(\"" + message + "\", \"" + level + "\")", null);
  }

  public void logToJs(final String message) {
    logToJs(message, "log");
  }

  public void triggerJSEvent(final String eventName, final String target) {
    eval("window.Capacitor.triggerEvent(\"" + eventName + "\", \"" + target + "\")", new ValueCallback<String>() {
      @Override
      public void onReceiveValue(String s) {
      }
    });
  }

  public void triggerJSEvent(final String eventName, final String target, final String data) {
    eval("window.Capacitor.triggerEvent(\"" + eventName + "\", \"" + target + "\", " + data + ")", new ValueCallback<String>() {
      @Override
      public void onReceiveValue(String s) {
      }
    });
  }

  public void triggerWindowJSEvent(final String eventName) {
    this.triggerJSEvent(eventName, "window");
  }

  public void triggerWindowJSEvent(final String eventName, final String data) {
    this.triggerJSEvent(eventName, "window", data);
  }

  public void triggerDocumentJSEvent(final String eventName) {
    this.triggerJSEvent(eventName, "document");
  }

  public void triggerDocumentJSEvent(final String eventName, final String data) {
    this.triggerJSEvent(eventName, "document", data);
  }

  public void execute(Runnable runnable) {
    taskHandler.post(runnable);
  }

  public void executeOnMainThread(Runnable runnable) {
    Handler mainHandler = new Handler(context.getMainLooper());

    mainHandler.post(runnable);
  }
  /**
   * Retain a call between plugin invocations
   * @param call
   */
  public void saveCall(PluginCall call) {
    this.savedCalls.put(call.getCallbackId(), call);
  }


  /**
   * Get a retained plugin call
   * @param callbackId the callbackId to use to lookup the call with
   * @return the stored call
   */
  public PluginCall getSavedCall(String callbackId) {
    return this.savedCalls.get(callbackId);
  }

  /**
   * Release a retained call
   * @param call
   */
  public void releaseCall(PluginCall call) {
    this.savedCalls.remove(call.getCallbackId());
  }

  /**
   * Build the JSInjector that will be used to inject JS into files served to the app,
   * to ensure that Capacitor's JS and the JS for all the plugins is loaded each time.
   */
  private JSInjector getJSInjector() {
    try {
      String globalJS = JSExport.getGlobalJS(context, isDevMode());
      String coreJS = JSExport.getCoreJS(context);
      String pluginJS = JSExport.getPluginJS(plugins.values());
      String cordovaJS = JSExport.getCordovaJS(context);
      String cordovaPluginsJS = JSExport.getCordovaPluginJS(context);
      String cordovaPluginsFileJS = JSExport.getCordovaPluginsFileJS(context);
      String localUrlJS = "window.WEBVIEW_SERVER_URL = '" + localUrl + "';";

      return new JSInjector(globalJS, coreJS, pluginJS, cordovaJS, cordovaPluginsJS, cordovaPluginsFileJS, localUrlJS);
    } catch(JSExportException ex) {
      Log.e(LOG_TAG, "Unable to export Capacitor JS. App will not function!", ex);
    }
    return null;
  }

  protected void storeDanglingPluginResult(PluginCall call, PluginResult result) {
    PluginHandle appHandle = getPlugin("App");
    App appPlugin = (App) appHandle.getInstance();
    appPlugin.fireRestoredResult(result.getWrappedResult(call));
  }

  /**
   * Restore any saved bundle state data
   * @param savedInstanceState
   */
  protected void restoreInstanceState(Bundle savedInstanceState) {
    String lastPluginId = savedInstanceState.getString(BUNDLE_LAST_PLUGIN_ID_KEY);
    String lastPluginCallMethod = savedInstanceState.getString(BUNDLE_LAST_PLUGIN_CALL_METHOD_NAME_KEY);
    String lastOptionsJson = savedInstanceState.getString(BUNDLE_PLUGIN_CALL_OPTIONS_SAVED_KEY);

    if (lastPluginId != null) {

      // If we have JSON blob saved, create a new plugin call with the original options
      if (lastOptionsJson != null) {
        try {
          JSObject options = new JSObject(lastOptionsJson);

          pluginCallForLastActivity = new PluginCall(msgHandler,
              lastPluginId, PluginCall.CALLBACK_ID_DANGLING, lastPluginCallMethod, options);

        } catch (JSONException ex) {
          Log.e(LOG_TAG, "Unable to restore plugin call, unable to parse persisted JSON object", ex);
        }
      }

      // Let the plugin restore any state it needs
      Bundle bundleData = savedInstanceState.getBundle(BUNDLE_PLUGIN_CALL_BUNDLE_KEY);
      PluginHandle lastPlugin = getPlugin(lastPluginId);
      if (lastPlugin != null) {
        lastPlugin.getInstance().restoreState(bundleData);
      }
    }
  }

  public void saveInstanceState(Bundle outState) {
    Log.d(LOG_TAG, "Saving instance state!");

    // If there was a last PluginCall for a started activity, we need to
    // persist it so we can load it again in case our app gets terminated
    if (pluginCallForLastActivity != null) {
      PluginCall call = pluginCallForLastActivity;
      PluginHandle handle = getPlugin(call.getPluginId());

      if (handle != null) {
        outState.putString(BUNDLE_LAST_PLUGIN_ID_KEY, call.getPluginId());
        outState.putString(BUNDLE_LAST_PLUGIN_CALL_METHOD_NAME_KEY, call.getMethodName());
        outState.putString(BUNDLE_PLUGIN_CALL_OPTIONS_SAVED_KEY, call.getData().toString());
        outState.putBundle(BUNDLE_PLUGIN_CALL_BUNDLE_KEY, handle.getInstance().saveInstanceState());
      }
    }
  }

  public void startActivityForPluginWithResult(PluginCall call, Intent intent, int requestCode) {
    Log.d(LOG_TAG, "Starting activity for result");

    pluginCallForLastActivity = call;

    getActivity().startActivityForResult(intent, requestCode);
  }

  /**
   * Handle a request permission result by finding the that requested
   * the permission and calling their permission handler
   * @param requestCode the code that was requested
   * @param permissions the permissions requested
   * @param grantResults the set of granted/denied permissions
   */

  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    PluginHandle plugin = getPluginWithRequestCode(requestCode);

    if (plugin == null) {
      Log.d(LOG_TAG, "Unable to find a Capacitor plugin to handle permission requestCode, trying Cordova plugins " + requestCode);
      try {
        cordovaInterface.onRequestPermissionResult(requestCode, permissions, grantResults);
      } catch (JSONException e) {
        Log.d(LOG_TAG, "Error on Cordova plugin permissions request " + e.getMessage());
      }
      return;
    }

    plugin.getInstance().handleRequestPermissionsResult(requestCode, permissions, grantResults);
  }

  /**
   * Handle an activity result and pass it to a plugin that has indicated it wants to
   * handle the result.
   * @param requestCode
   * @param resultCode
   * @param data
   */
  public void onActivityResult(int requestCode, int resultCode, Intent data) {
    PluginHandle plugin = getPluginWithRequestCode(requestCode);

    if (plugin == null || plugin.getInstance() == null) {
      Log.d(LOG_TAG, "Unable to find a Capacitor plugin to handle requestCode, trying Cordova plugins " + requestCode);
      cordovaInterface.onActivityResult(requestCode, resultCode, data);
      return;
    }

    PluginCall lastCall = plugin.getInstance().getSavedCall();

    // If we don't have a saved last call (because our app was killed and restarted, for example),
    // Then we should see if we have any saved plugin call information and generate a new,
    // "dangling" plugin call (a plugin call that doesn't have a corresponding web callback)
    // and then send that to the plugin
    if (lastCall == null && pluginCallForLastActivity != null) {
      plugin.getInstance().saveCall(pluginCallForLastActivity);
    }

    plugin.getInstance().handleOnActivityResult(requestCode, resultCode, data);

    // Clear the plugin call we may have re-hydrated on app launch
    pluginCallForLastActivity = null;
  }

  /**
   * Handle an onNewIntent lifecycle event and notify the plugins
   * @param intent
   */
  public void onNewIntent(Intent intent) {
    for (PluginHandle plugin : plugins.values()) {
      plugin.getInstance().handleOnNewIntent(intent);
    }
  }

  /**
   * Handle onRestart lifecycle event and notify the plugins
   */
  public void onRestart() {
    for (PluginHandle plugin : plugins.values()) {
      plugin.getInstance().handleOnRestart();
    }
  }

  /**
   * Handle onStart lifecycle event and notify the plugins
   */
  public void onStart() {
    for (PluginHandle plugin : plugins.values()) {
      plugin.getInstance().handleOnStart();
    }
  }

  /**
   * Handle onResume lifecycle event and notify the plugins
   */
  public void onResume() {
    for (PluginHandle plugin : plugins.values()) {
      plugin.getInstance().handleOnResume();
    }
  }

  /**
   * Handle onPause lifecycle event and notify the plugins
   */
  public void onPause() {
    Splash.onPause();

    for (PluginHandle plugin : plugins.values()) {
      plugin.getInstance().handleOnPause();
    }
  }

  /**
   * Handle onStop lifecycle event and notify the plugins
   */
  public void onStop() {
    for (PluginHandle plugin : plugins.values()) {
      plugin.getInstance().handleOnStop();
    }
  }

  public void onBackPressed() {
    PluginHandle appHandle = getPlugin("App");
    if (appHandle != null) {
      App appPlugin = (App) appHandle.getInstance();

      // If there are listeners, don't do the default action, as this means the user
      // wants to override the back button
      if (appPlugin.hasBackButtonListeners()) {
        appPlugin.fireBackButton();
      } else {
        if (webView.canGoBack()) {
          webView.goBack();
        }
      }
    }

  }

  public String getServerBasePath() {
    return this.localServer.getBasePath();
  }

  public void setServerBasePath(String path){
    localServer.hostFiles(path);
    webView.post(new Runnable() {
      @Override
      public void run() {
        webView.loadUrl(appUrl);
      }
    });
  }


  public String getLocalUrl() {
    return localUrl;
  }

  public WebViewLocalServer getLocalServer() {
    return localServer;
  }
}
