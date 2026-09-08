package io.github.ruix156.bilivestealth;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;

import io.github.ruix156.bilivestealth.hooks.CommonParamHook;
import io.github.ruix156.bilivestealth.hooks.DanmakuHook;
import io.github.ruix156.bilivestealth.ui.FloatingPanel;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

public class MainHook extends XposedModule {
  public static final String appName = "B站直播隐身观看";

  private Context mainContext;
  private String addCommonParamClass = "com.bilibili.bililive.infra.network.interceptor.a";

  @Override
  public void onPackageReady(PackageReadyParam param) {
    if (!param.getPackageName().equals("tv.danmaku.bili") && !param.getPackageName().equals("com.bilibili.app.in"))
      return;

    try {
      if (mainContext == null) {
        // 在 Application.attach 中获取目标应用上下文
        // noinspection DiscouragedPrivateApi
        Method attach = Application.class.getDeclaredMethod("attach", Context.class);
        hook(attach).intercept(chain -> {
          Object result = chain.proceed();
          mainContext = (Context) chain.getArg(0);
          hookApp(param);
          return result;
        });
      } else {
        hookApp(param);
      }
    } catch (Throwable t) {
      log(Log.ERROR, appName, "hook Application.attach failed", t);
    }
  }

  private void hookApp(PackageReadyParam param) {
    int appVersionCode;
    try {
      appVersionCode = mainContext.getPackageManager().getPackageInfo(mainContext.getPackageName(), 0).versionCode;
    } catch (Exception e) {
      log(Log.ERROR, appName, param.getPackageName() + " 获取版本信息失败", e);
      return;
    }
    getClassName(param, appVersionCode);

    Settings settings = new Settings(mainContext);
    settings.load();

    try {
      new CommonParamHook(this, param, addCommonParamClass, settings).hook();
      new DanmakuHook(this, param, settings).hook();
      new FloatingPanel(this, param, settings).install();
    } catch (Throwable throwable) {
      log(Log.ERROR, appName, appName + " " + param.getPackageName() + " " + appVersionCode + " 加载异常", throwable);
      Toast.makeText(mainContext, appName + ": " + appVersionCode + " 加载异常 ", Toast.LENGTH_LONG).show();
    }
  }

  private void getClassName(PackageReadyParam param, int versionCode) {
    SharedPreferences sp = mainContext.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE);
    int spVersionCode = sp.getInt("versionCode", 0);
    if (spVersionCode == versionCode) {
      addCommonParamClass = sp.getString("addCommonParamClass", "");
      if (addCommonParamClass == null || addCommonParamClass.isEmpty())
        addCommonParamClass = "com.bilibili.bililive.infra.network.interceptor.a";
    } else {
      System.loadLibrary("dexkit");
      try (DexKitBridge bridge = DexKitBridge.create(param.getApplicationInfo().sourceDir)) {
        String addCommonParamClassName = findAddCommonParam(bridge);
        if (addCommonParamClassName != null) {
          addCommonParamClass = addCommonParamClassName;
          sp.edit()
              .putInt("versionCode", versionCode)
              .putString("addCommonParamClass", addCommonParamClass)
              .apply();
        }
      }
    }
  }

  // 查找addCommonParam
  private String findAddCommonParam(DexKitBridge bridge) {
    MethodData methodData = bridge.findMethod(FindMethod.create()
        .searchPackages("com.bilibili.bililive.infra.network.interceptor")
        .matcher(MethodMatcher.create()
            .name("addCommonParam")))
        .singleOrNull();
    if (methodData != null)
      return methodData.getClassName();

    methodData = bridge.findMethod(FindMethod.create()
            .matcher(MethodMatcher.create()
                    .usingStrings(
                            "getDanmuInfo: account:")))
            .singleOrNull();

    return methodData != null ? methodData.getClassName() : null;
  }
}
